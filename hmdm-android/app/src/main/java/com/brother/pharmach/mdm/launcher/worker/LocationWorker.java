package com.brother.pharmach.mdm.launcher.worker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.db.DatabaseHelper;
import com.brother.pharmach.mdm.launcher.db.LocationTable;
import com.brother.pharmach.mdm.launcher.service.LocationService;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LocationWorker extends Worker {

    public static final int FIRE_PERIOD_MINS = 15;

    private static final String WORK_TAG_PERIODIC = "com.brother.pharmach.mdm.launcher.WORK_TAG_LOCATION_PERIODIC";
    private static final String WORK_TAG_ONE_SHOT = "com.brother.pharmach.mdm.launcher.WORK_TAG_LOCATION_ONE_SHOT";
    private static final long FRESH_FIX_WAIT_SECONDS = 12;

    private final Context context;

    public LocationWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.context = context;
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(LocationWorker.class, FIRE_PERIOD_MINS, TimeUnit.MINUTES)
                        .addTag(Const.WORK_TAG_COMMON)
                .addTag(WORK_TAG_PERIODIC)
                        .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                WORK_TAG_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }

    public static void scheduleOneShot(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(LocationWorker.class)
                .addTag(Const.WORK_TAG_COMMON)
                .addTag(WORK_TAG_ONE_SHOT)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                WORK_TAG_ONE_SHOT,
                ExistingWorkPolicy.REPLACE,
                request);
    }

    public static void uploadLatestLocationNow(Context context) {
        new Thread(() -> captureAndUpload(context.getApplicationContext(), true)).start();
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean forceFreshFix = getTags().contains(WORK_TAG_ONE_SHOT);
        return captureAndUpload(context, forceFreshFix);
        }

        @NonNull
        private static Result captureAndUpload(Context context, boolean forceFreshFix) {
        boolean fineGranted = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            RemoteLogger.log(context, Const.LOG_WARN, "LocationWorker: location permission is missing");
            return Result.failure();
        }

        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                return Result.success();
            }

            Location location = getBestLastKnownLocation(locationManager);
            if ((location == null || forceFreshFix) && fineGranted) {
                Location freshGps = tryRequestSingleUpdate(locationManager, LocationManager.GPS_PROVIDER);
                if (freshGps != null) {
                    location = freshGps;
                }
            }
            // Only try network if GPS didn't produce a fresh fix (avoids wasting 12s + overwriting GPS)
            if (location == null && coarseGranted) {
                Location freshNetwork = tryRequestSingleUpdate(locationManager, LocationManager.NETWORK_PROVIDER);
                if (freshNetwork != null) {
                    location = freshNetwork;
                }
            }

            if (location == null) {
                return Result.success();
            }

            DatabaseHelper helper = DatabaseHelper.instance(context);
            if (helper == null) {
                return Result.success();
            }

            LocationTable.Location tableLocation = new LocationTable.Location(location);
            if (forceFreshFix) {
                // Use current wall-clock time so the server always sees a fresh timestamp,
                // even when the GPS fix itself is a cached/stale reading.
                tableLocation.setTs(System.currentTimeMillis());
            }
            LocationTable.insert(helper.getWritableDatabase(), tableLocation);
            LocationService.sendLocations(context);
            return Result.success();
        } catch (SecurityException e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: location permission denied while querying providers");
            return Result.failure();
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "LocationWorker failed: " + e.getMessage());
            return Result.success();
        }
    }

    private static Location getBestLastKnownLocation(LocationManager locationManager) {
        Location gps = null;
        Location network = null;
        Location passive = null;
        try {
            gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {
        }
        try {
            network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {
        }
        try {
            passive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
        } catch (Exception ignored) {
        }

        Location best = gps;
        if (best == null || (network != null && network.getTime() > best.getTime())) {
            best = network;
        }
        if (best == null || (passive != null && passive.getTime() > best.getTime())) {
            best = passive;
        }
        return best;
    }

    private static Location tryRequestSingleUpdate(LocationManager locationManager, String provider) {
        try {
            if (!locationManager.isProviderEnabled(provider)) {
                return null;
            }
            final Location[] result = new Location[1];
            final CountDownLatch latch = new CountDownLatch(1);
            final LocationListener listener = location -> {
                result[0] = location;
                latch.countDown();
            };

            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            latch.await(FRESH_FIX_WAIT_SECONDS, TimeUnit.SECONDS);
            try {
                locationManager.removeUpdates(listener);
            } catch (Exception ignored) {
            }
            return result[0];
        } catch (Exception ignored) {
            return null;
        }
    }
}
