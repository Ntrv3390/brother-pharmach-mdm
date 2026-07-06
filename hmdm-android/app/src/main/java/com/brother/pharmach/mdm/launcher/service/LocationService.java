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
 * Simplified location handler, adapted from upstream Headwind MDM's LocationService.java (the
 * open-source free-tier version), tried out as an alternative to LocationForegroundService.
 * Two deliberate adaptations from the pasted source:
 *
 * 1. {@code ProUtils.processLocation()} is a no-op stub in both this fork and upstream's free
 *    tier (the real implementation is upstream's closed-source Pro build, not available here).
 *    Uploading is wired directly to {@link LocationUploader#sendUrgentLocation} /
 *    {@link LocationTable}, the same real pipeline LocationForegroundService already uses.
 * 2. Rather than the pasted code's always-registered LocationListener, this service takes one
 *    fresh fix at a time (on-demand or once a minute) via requestSingleUpdate() and stops
 *    listening immediately after — see the class-level note in captureAndUploadOnce() about why.
 *
 * Behavior:
 *  - "Get Latest GPS" (PushNotificationProcessor / DetailedInfoWorker / Initializer, all
 *    rerouted here) triggers one immediate capture-and-upload.
 *  - Once running, this service ALSO captures and uploads a fresh fix once every 60 seconds on
 *    its own, independent of any urgent trigger.
 *  - Both paths share one in-flight guard, so an urgent trigger and the periodic tick can never
 *    stack into two simultaneous location requests from this service.
 *
 * LocationForegroundService.java itself is left completely intact and untouched — it still runs
 * its OWN continuous tracking and the 15-minute periodic WorkManager job in parallel with this.
 * That means there are now two independent sources polling location on their own schedules
 * (this service every 60s, LocationForegroundService's continuous listener continuously plus
 * every 15 minutes via WorkManager) — worth watching for the same multi-source GPS/GMS
 * contention this investigation spent most of its effort diagnosing. If that shows up, the fix
 * is to pick one source, not run both.
 */
public class LocationService extends Service {

    public static final String ACTION_URGENT_GPS =
            "com.brother.pharmach.mdm.launcher.LocationService.ACTION_URGENT_GPS";

    private static final String CHANNEL_ID = "location_service_simple_channel";
    private static final int NOTIFICATION_ID = 1113;
    private static final long SINGLE_UPDATE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);
    private static final long PERIODIC_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    private LocationManager locationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private volatile LocationListener activeListener;

    private final Runnable periodicTick = new Runnable() {
        @Override
        public void run() {
            captureAndUploadOnce("periodic1Min");
            mainHandler.postDelayed(this, PERIODIC_INTERVAL_MS);
        }
    };

    /**
     * Ensures the service is running without forcing an urgent capture — used at app startup so
     * the once-a-minute periodic capture (scheduled from onCreate()) is always active while the
     * kiosk app is running, not just after the first "Get Latest GPS" click.
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, LocationService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationService: failed to start: " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }

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
        // Once running, keep capturing/uploading once a minute on its own, in addition to
        // whatever urgent triggers arrive via onStartCommand().
        mainHandler.postDelayed(periodicTick, PERIODIC_INTERVAL_MS);
        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationService: started — periodic capture every " + PERIODIC_INTERVAL_MS + "ms");
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
            captureAndUploadOnce("urgent");
        }
        // START_STICKY: this service is now meant to keep running (periodic capture), not just
        // handle one urgent request and stop — the OS restarts it if killed, same as
        // LocationForegroundService.
        return START_STICKY;
    }

    /**
     * Takes exactly one fresh fix and uploads it, then stops listening — used for both the
     * urgent trigger and the once-a-minute periodic tick. Guarded by {@link #requestInFlight} so
     * the two callers can never run a location request at the same time from this service.
     */
    private void captureAndUploadOnce(String origin) {
        if (!requestInFlight.compareAndSet(false, true)) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationService: capture already in flight, skipping " + origin + " trigger");
            return;
        }

        boolean fineGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if ((!fineGranted && !coarseGranted) || locationManager == null) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationService: no location permission or LocationManager unavailable, origin=" + origin);
            requestInFlight.set(false);
            return;
        }

        RemoteLogger.log(this, Const.LOG_INFO, "LocationService: capture started, origin=" + origin);

        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                RemoteLogger.log(LocationService.this, Const.LOG_INFO,
                        "LocationService: fix received provider=" + location.getProvider()
                                + " origin=" + origin);
                finish(location);
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
                    "LocationService: requestSingleUpdate failed, origin=" + origin + ": " + e.getMessage());
        }

        mainHandler.postDelayed(() -> {
            if (activeListener != listener) return; // already finished via a fix
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationService: capture timed out after " + SINGLE_UPDATE_TIMEOUT_MS
                            + "ms, origin=" + origin);
            finish(bestLastKnown());
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

    /** Called exactly once per capture, either from a fresh fix or the timeout fallback. */
    private synchronized void finish(@Nullable Location location) {
        LocationListener listener = activeListener;
        if (listener == null) return; // already finished by the other path
        activeListener = null;

        try {
            locationManager.removeUpdates(listener);
        } catch (Exception ignored) {
        }

        // This runs on the main thread from the once-a-minute periodic tick. The upload/DB/log
        // tail below can throw (SQLiteException on a locked/full/corrupt DB, uploader errors);
        // an uncaught throw would reach the global handler → System.exit(0) → periodic
        // "crash then recover". Guard it so a bad capture can never crash the process.
        try {
            if (location == null || (location.getLatitude() == 0.0 && location.getLongitude() == 0.0)) {
                RemoteLogger.log(this, Const.LOG_WARN, "LocationService: capture found no location to upload");
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
                        "LocationService: location " + (sent ? "uploaded" : "queued offline")
                                + " (provider=" + location.getProvider() + ")");
            }
        } catch (Throwable t) {
            android.util.Log.w(Const.LOG_TAG, "LocationService.finish: upload/persist failed", t);
        }

        requestInFlight.set(false);
        // No stopSelf() here anymore — the service stays alive for the once-a-minute periodic
        // capture. It only stops if the OS kills it (then START_STICKY restarts it) or if
        // something else explicitly stops the service.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(periodicTick);
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
