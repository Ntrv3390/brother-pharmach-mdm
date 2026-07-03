package com.brother.pharmach.mdm.launcher.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.db.DatabaseHelper;
import com.brother.pharmach.mdm.launcher.db.LocationTable;
import com.brother.pharmach.mdm.launcher.util.LocationUploader;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simplified "Get Latest GPS" handler, adapted from upstream Headwind MDM's LocationService.java
 * (the open-source free-tier version), tried out as an alternative to
 * LocationForegroundService's urgent-request path. Two deliberate adaptations from the pasted
 * source:
 *
 * 1. {@code ProUtils.processLocation()} is a no-op stub in both this fork and upstream's free
 *    tier (the real implementation is upstream's closed-source Pro build, not available here).
 *    Uploading is wired directly to {@link LocationUploader#sendUrgentLocation} /
 *    {@link LocationTable}, the same real pipeline LocationForegroundService already uses.
 * 2. This service handles ONLY the on-demand "Get Latest GPS" request — it does not run its own
 *    continuous background listener. LocationForegroundService's continuous tracking and the
 *    15-minute periodic WorkManager job are untouched and keep running exactly as before;
 *    duplicating a second always-on listener here would recreate the multi-source GPS/GMS
 *    contention (three concurrent requests hitting the same chip/GMS client at once) that this
 *    investigation spent most of its effort diagnosing.
 *
 * LocationForegroundService.java itself is left completely intact and uncommented — only the
 * "Get Latest GPS" trigger call sites (PushNotificationProcessor, DetailedInfoWorker,
 * Initializer) are rerouted here, so the previous implementation can be restored by pointing
 * those call sites back.
 */
public class LocationService extends Service {

    public static final String ACTION_URGENT_GPS =
            "com.brother.pharmach.mdm.launcher.LocationService.ACTION_URGENT_GPS";

    private static final String CHANNEL_ID = "location_service_simple_channel";
    private static final int NOTIFICATION_ID = 1113;
    private static final long SINGLE_UPDATE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);

    private LocationManager locationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private volatile LocationListener activeListener;

    public static void triggerUrgent(Context context, String origin) {
        RemoteLogger.log(context, Const.LOG_INFO, "LocationService: triggerUrgent origin=" + origin);
        Intent intent = new Intent(context, LocationService.class);
        intent.setAction(ACTION_URGENT_GPS);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationService: failed to start for urgent request: "
                            + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startAsForeground();
    }

    private void startAsForeground() {
        NotificationCompat.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Location (simple)", NotificationManager.IMPORTANCE_MIN);
            channel.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder(this);
        }
        Notification notification = builder
                .setContentTitle(getString(R.string.white_app_name))
                .setContentText(getString(R.string.location_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_URGENT_GPS.equals(intent.getAction())) {
            handleUrgentRequest();
        }
        return START_NOT_STICKY;
    }

    private void handleUrgentRequest() {
        if (!requestInFlight.compareAndSet(false, true)) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationService: urgent request already in flight, ignoring duplicate trigger");
            return;
        }

        boolean fineGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if ((!fineGranted && !coarseGranted) || locationManager == null) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationService: no location permission or LocationManager unavailable");
            requestInFlight.set(false);
            stopSelf();
            return;
        }

        RemoteLogger.log(this, Const.LOG_INFO, "LocationService: urgent request started");

        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                RemoteLogger.log(LocationService.this, Const.LOG_INFO,
                        "LocationService: urgent fix received provider=" + location.getProvider());
                finishUrgent(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
            }
        };
        activeListener = listener;

        try {
            if (fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper());
            }
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationService: requestSingleUpdate failed: " + e.getMessage());
        }

        mainHandler.postDelayed(() -> {
            if (activeListener != listener) return; // already finished via a fix
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationService: urgent request timed out after " + SINGLE_UPDATE_TIMEOUT_MS + "ms");
            finishUrgent(bestLastKnown());
        }, SINGLE_UPDATE_TIMEOUT_MS);
    }

    @Nullable
    private Location bestLastKnown() {
        Location best = null;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    /** Called exactly once per urgent request, either from a fresh fix or the timeout fallback. */
    private synchronized void finishUrgent(@Nullable Location location) {
        LocationListener listener = activeListener;
        if (listener == null) return; // already finished by the other path
        activeListener = null;

        try {
            locationManager.removeUpdates(listener);
        } catch (Exception ignored) {
        }

        if (location == null || (location.getLatitude() == 0.0 && location.getLongitude() == 0.0)) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationService: urgent request found no location to upload");
        } else {
            LocationTable.Location tableLocation = new LocationTable.Location(location);
            tableLocation.setTs(System.currentTimeMillis());

            boolean sent = LocationUploader.sendUrgentLocation(getApplicationContext(), tableLocation, true);
            if (!sent) {
                DatabaseHelper helper = DatabaseHelper.instance(getApplicationContext());
                if (helper != null) {
                    LocationTable.insert(helper.getWritableDatabase(), tableLocation);
                }
            }
            RemoteLogger.log(this, Const.LOG_INFO,
                    "LocationService: urgent location " + (sent ? "uploaded" : "queued offline")
                            + " (provider=" + location.getProvider() + ")");
        }

        requestInFlight.set(false);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LocationListener listener = activeListener;
        if (listener != null) {
            try {
                locationManager.removeUpdates(listener);
            } catch (Exception ignored) {
            }
            activeListener = null;
        }
        requestInFlight.set(false);
        RemoteLogger.log(this, Const.LOG_INFO, "LocationService: stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
