package com.brother.pharmach.mdm.launcher.worker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

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

import java.util.concurrent.TimeUnit;

public class LocationWorker extends Worker {

    public static final int FIRE_PERIOD_MINS = 15;

    private static final String WORK_TAG_PERIODIC = "com.brother.pharmach.mdm.launcher.WORK_TAG_LOCATION_PERIODIC";
    private static final String WORK_TAG_ONE_SHOT = "com.brother.pharmach.mdm.launcher.WORK_TAG_LOCATION_ONE_SHOT";

    private final Context context;

    public LocationWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.context = context;
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(LocationWorker.class, FIRE_PERIOD_MINS, TimeUnit.MINUTES)
                        .addTag(Const.WORK_TAG_COMMON)
                        .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                WORK_TAG_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }

    public static void scheduleOneShot(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(LocationWorker.class)
                .addTag(Const.WORK_TAG_COMMON)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                WORK_TAG_ONE_SHOT,
                ExistingWorkPolicy.REPLACE,
                request);
    }

    @NonNull
    @Override
    public Result doWork() {
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

            Location location = null;
            location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            }

            if (location == null) {
                return Result.success();
            }

            DatabaseHelper helper = DatabaseHelper.instance(context);
            if (helper == null) {
                return Result.success();
            }

            LocationTable.insert(helper.getWritableDatabase(), new LocationTable.Location(location));
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
}
