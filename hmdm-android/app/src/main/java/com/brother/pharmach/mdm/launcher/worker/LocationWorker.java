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
import androidx.work.OutOfQuotaPolicy;
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
    private static final long FRESH_FIX_WAIT_SECONDS = 30;
    private static final long URGENT_MAX_FIX_AGE_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long PERIODIC_MAX_FIX_AGE_MS = TimeUnit.MINUTES.toMillis(30);
    private static final float MAX_FIX_ACCURACY_METERS = 2000f;

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
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                WORK_TAG_ONE_SHOT,
                ExistingWorkPolicy.REPLACE,
                request);
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

            long maxFixAgeMs = forceFreshFix ? URGENT_MAX_FIX_AGE_MS : PERIODIC_MAX_FIX_AGE_MS;

            Location lastKnown = getBestLastKnownLocation(locationManager);
            if (!isLocationFresh(lastKnown, maxFixAgeMs)) {
                lastKnown = null;
            }

            Location freshGps = fineGranted
                    ? tryRequestSingleUpdate(locationManager, LocationManager.GPS_PROVIDER)
                    : null;
            if (!isLocationFresh(freshGps, maxFixAgeMs)) {
                freshGps = null;
            }

            Location freshNetwork = coarseGranted
                    ? tryRequestSingleUpdate(locationManager, LocationManager.NETWORK_PROVIDER)
                    : null;
            if (!isLocationFresh(freshNetwork, maxFixAgeMs)) {
                freshNetwork = null;
            }

            Location location = chooseBestLocation(lastKnown, freshGps, freshNetwork);

            if (location == null) {
                if (forceFreshFix) {
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "LocationWorker: urgent refresh failed to get a fresh fix (GPS: " +
                                    (freshGps != null ? "ok" : "null") + ", Network: " +
                                    (freshNetwork != null ? "ok" : "null") + ")");
                }
                return Result.success();
            }

            RemoteLogger.log(context, Const.LOG_INFO, "LocationWorker: success using " +
                    location.getProvider() + " (freshness: " +
                    (System.currentTimeMillis() - location.getTime()) / 1000 + "s)");

            DatabaseHelper helper = DatabaseHelper.instance(context);
            if (helper == null) {
                return Result.success();
            }

            LocationTable.Location tableLocation = new LocationTable.Location(location);

            if (forceFreshFix && LocationService.sendUrgentLocation(context, tableLocation)) {
                RemoteLogger.log(context, Const.LOG_INFO, "LocationWorker: urgent location uploaded successfully");
                return Result.success();
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

    private static boolean isLocationFresh(Location location, long maxAgeMs) {
        if (location == null) {
            return false;
        }
        if (location.getLatitude() == 0 && location.getLongitude() == 0) {
            return false;
        }

        long ageMs = System.currentTimeMillis() - location.getTime();
        if (ageMs < 0) {
            ageMs = 0;
        }
        if (ageMs > maxAgeMs) {
            return false;
        }

        return !location.hasAccuracy() || location.getAccuracy() <= MAX_FIX_ACCURACY_METERS;
    }

    private static Location chooseBestLocation(Location... locations) {
        Location best = null;
        for (Location candidate : locations) {
            if (candidate == null) {
                continue;
            }
            if (best == null || isBetterLocation(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isBetterLocation(Location candidate, Location currentBest) {
        if (currentBest == null) {
            return true;
        }

        long candidateAge = Math.max(0, System.currentTimeMillis() - candidate.getTime());
        long currentAge = Math.max(0, System.currentTimeMillis() - currentBest.getTime());
        if (candidateAge + 30_000L < currentAge) {
            return true;
        }
        if (currentAge + 30_000L < candidateAge) {
            return false;
        }

        float candidateAccuracy = candidate.hasAccuracy() ? candidate.getAccuracy() : MAX_FIX_ACCURACY_METERS;
        float currentAccuracy = currentBest.hasAccuracy() ? currentBest.getAccuracy() : MAX_FIX_ACCURACY_METERS;
        if (candidateAccuracy + 50f < currentAccuracy) {
            return true;
        }
        if (currentAccuracy + 50f < candidateAccuracy) {
            return false;
        }

        String candidateProvider = candidate.getProvider() == null ? "" : candidate.getProvider();
        String currentProvider = currentBest.getProvider() == null ? "" : currentBest.getProvider();
        if (LocationManager.GPS_PROVIDER.equals(candidateProvider)
                && !LocationManager.GPS_PROVIDER.equals(currentProvider)) {
            return true;
        }

        return candidate.getTime() > currentBest.getTime();
    }
}
