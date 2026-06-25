package com.brother.pharmach.mdm.launcher.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

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
import com.brother.pharmach.mdm.launcher.util.Utils;
import com.brother.pharmach.mdm.launcher.worker.LocationWorker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persistent foreground service for location tracking.
 *
 * Continuous mode: GPS listener always registered, fixes stream in every 4s/10m.
 * Upload throttle: upload when device moves >= 100m or 30s elapsed, whichever comes first.
 * Force upload: at least every 60s so static devices still report.
 *
 * Urgent mode (Get Latest Location): serves from the in-memory latest fix if < 20s old
 * (instant, sub-second response). Falls back to cold-start GPS only if no recent fix exists.
 * Runs at Thread.MAX_PRIORITY — highest priority user-space thread in the process.
 *
 * Offline resilience: fixes that fail to upload are queued in SQLite and flushed on the
 * periodic 15-min cycle or on the next successful upload window.
 */
public class LocationForegroundService extends Service {

    public static final String ACTION_URGENT_GPS = "com.brother.pharmach.mdm.launcher.ACTION_URGENT_GPS";

    private static final String CHANNEL_ID = "location_service_channel";
    private static final int NOTIFICATION_ID = 1002;

    // Continuous listener update frequency — aggressive for vehicle tracking.
    private static final long GPS_MIN_TIME_MS = 4_000L;       // GPS callback at most every 4s
    private static final float GPS_MIN_DISTANCE_M = 10f;      // GPS callback at most every 10m
    private static final long NETWORK_MIN_TIME_MS = 8_000L;
    private static final float NETWORK_MIN_DISTANCE_M = 20f;

    // Upload throttle — prevents HTTP call on every GPS callback.
    private static final long MIN_UPLOAD_INTERVAL_MS = 15_000L; // don't upload more often than every 15s
    private static final float MIN_UPLOAD_DISTANCE_M = 100f;    // upload when device moved >= 100m
    private static final long MAX_UPLOAD_INTERVAL_MS = 60_000L; // force upload every 60s regardless of movement

    // Urgent: use the streaming in-memory fix if it's < 20s old (GPS is warm, instant response).
    private static final long URGENT_FIX_MAX_AGE_MS = 20_000L;

    // ---------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------

    // Latest fix from the continuous listener — shared between listener thread and urgent executor.
    private volatile Location latestContinuousFix;

    // Upload throttle state — set optimistically before queuing to prevent duplicate uploads.
    private volatile long lastUploadTimeMs;
    private volatile Location lastUploadedFix;

    // Location listener infrastructure
    private LocationManager locationManager;
    private HandlerThread listenerThread;

    // Executors
    private ExecutorService urgentExecutor;    // Thread.MAX_PRIORITY, non-daemon
    private ExecutorService uploadExecutor;    // background HTTP uploads from continuous stream
    private ScheduledExecutorService flushScheduler; // periodic queue-flush cycle

    // Track in-flight urgent task so a new push cancels any pending one.
    private volatile Future<?> currentUrgentTask;

    // Flush the offline SQLite queue when the network comes back.
    private ConnectivityManager.NetworkCallback networkCallback;

    // ---------------------------------------------------------------------------
    // Continuous LocationListener — callbacks delivered on listenerThread.
    // ---------------------------------------------------------------------------

    private final LocationListener continuousListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (location.getLatitude() == 0.0 && location.getLongitude() == 0.0) {
                return;
            }
            latestContinuousFix = location;
            considerUpload(location);
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            // Provider was disabled and just came back (user toggled GPS, left tunnel, etc.).
            // Re-register on the main looper to avoid self-deadlock on the listener looper.
            new Handler(Looper.getMainLooper()).post(() -> reRegisterProvider(provider));
            RemoteLogger.log(getApplicationContext(), Const.LOG_INFO,
                    "LocationForegroundService: provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            RemoteLogger.log(getApplicationContext(), Const.LOG_WARN,
                    "LocationForegroundService: provider disabled: " + provider
                    + " — will re-register when it comes back");
        }
    };

    // ---------------------------------------------------------------------------
    // Static helpers
    // ---------------------------------------------------------------------------

    public static void start(Context context) {
        Intent intent = new Intent(context, LocationForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void triggerUrgent(Context context) {
        Intent intent = new Intent(context, LocationForegroundService.class);
        intent.setAction(ACTION_URGENT_GPS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(intent);
            } catch (Exception e) {
                // ForegroundServiceStartNotAllowedException (API 31+) is thrown on Android 12+
                // when startForegroundService() is called from a background context — tightened
                // further in Android 14/15. Fall back to a direct thread-based capture that
                // bypasses FGS restrictions so urgent GPS never silently fails.
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationForegroundService: startForegroundService blocked ("
                        + e.getClass().getSimpleName() + "), falling back to direct capture");
                LocationWorker.enqueueUrgentNow(context);
            }
        } else {
            context.startService(intent);
        }
    }

    // ---------------------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();

        urgentExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "urgent-gps-fg");
            t.setPriority(Thread.MAX_PRIORITY);
            t.setDaemon(false);
            return t;
        });

        uploadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "location-upload-fg");
            t.setDaemon(true);
            return t;
        });

        flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "location-flush-fg");
            t.setDaemon(true);
            return t;
        });

        startForegroundWithNotification();
        startContinuousTracking();
        requestBatteryOptimizationExemptionIfNeeded();
        registerNetworkCallback();

        // Flush the SQLite queue every 15 min — clears any fixes that were queued
        // while the device was offline. Initial delay = 15 min since the continuous
        // listener already handles real-time uploads.
        flushScheduler.scheduleAtFixedRate(
                this::runFlushCycle,
                LocationWorker.FIRE_PERIOD_MINS,
                LocationWorker.FIRE_PERIOD_MINS,
                TimeUnit.MINUTES);

        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService started — continuous GPS active");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_URGENT_GPS.equals(intent.getAction())) {
            handleUrgentRequest();
        }
        // START_STICKY: OS automatically restarts this service if killed.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopContinuousTracking();
        unregisterNetworkCallback();
        flushScheduler.shutdownNow();
        urgentExecutor.shutdownNow();
        uploadExecutor.shutdownNow();
        RemoteLogger.log(this, Const.LOG_INFO, "LocationForegroundService stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------------------
    // Continuous tracking — register/unregister
    // ---------------------------------------------------------------------------

    private void startContinuousTracking() {
        boolean fineGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: location permission missing, continuous tracking unavailable");
            return;
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: LocationManager unavailable");
            return;
        }

        listenerThread = new HandlerThread("location-listener-fg");
        listenerThread.start();

        registerProvider(LocationManager.GPS_PROVIDER, fineGranted,
                GPS_MIN_TIME_MS, GPS_MIN_DISTANCE_M);
        registerProvider(LocationManager.NETWORK_PROVIDER, coarseGranted || fineGranted,
                NETWORK_MIN_TIME_MS, NETWORK_MIN_DISTANCE_M);

        // Seed latestContinuousFix from the system cache so urgent requests before
        // the first live callback have something to work with.
        seedLastKnownFix();
    }

    private void registerProvider(String provider, boolean hasPermission, long minTime, float minDistance) {
        if (!hasPermission || locationManager == null || listenerThread == null) {
            return;
        }
        try {
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.requestLocationUpdates(
                        provider, minTime, minDistance, continuousListener, listenerThread.getLooper());
                RemoteLogger.log(this, Const.LOG_INFO,
                        "LocationForegroundService: registered provider " + provider);
            } else {
                RemoteLogger.log(this, Const.LOG_INFO,
                        "LocationForegroundService: provider " + provider + " disabled at start");
            }
        } catch (SecurityException e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: permission denied for " + provider);
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: failed to register " + provider + ": " + e.getMessage());
        }
    }

    private void reRegisterProvider(String provider) {
        boolean fineGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            registerProvider(provider, fineGranted, GPS_MIN_TIME_MS, GPS_MIN_DISTANCE_M);
        } else if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            registerProvider(provider, coarseGranted || fineGranted,
                    NETWORK_MIN_TIME_MS, NETWORK_MIN_DISTANCE_M);
        }
    }

    private void seedLastKnownFix() {
        if (locationManager == null) return;
        Location best = null;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (Exception ignored) {}
        }
        if (best != null && best.getLatitude() != 0 && best.getLongitude() != 0) {
            latestContinuousFix = best;
            RemoteLogger.log(this, Const.LOG_INFO,
                    "LocationForegroundService: seeded last known fix ("
                    + ((System.currentTimeMillis() - best.getTime()) / 1000) + "s old)");
        }
    }

    private void stopContinuousTracking() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(continuousListener);
            } catch (Exception ignored) {}
        }
        if (listenerThread != null) {
            listenerThread.quitSafely();
            listenerThread = null;
        }
    }

    // ---------------------------------------------------------------------------
    // Upload logic — continuous stream path
    // ---------------------------------------------------------------------------

    private void considerUpload(Location location) {
        long now = System.currentTimeMillis();
        long timeSinceLast = now - lastUploadTimeMs;
        float distSinceLast = lastUploadedFix != null
                ? location.distanceTo(lastUploadedFix) : Float.MAX_VALUE;

        boolean mustUpload = timeSinceLast >= MAX_UPLOAD_INTERVAL_MS;
        boolean movedAndTimeOk = timeSinceLast >= MIN_UPLOAD_INTERVAL_MS
                && distSinceLast >= MIN_UPLOAD_DISTANCE_M;

        if (mustUpload || movedAndTimeOk) {
            // Set optimistically before queuing to prevent duplicate uploads from rapid callbacks.
            lastUploadTimeMs = now;
            lastUploadedFix = location;
            uploadFix(new Location(location), false);
        }
    }

    /**
     * Queues a location for upload on the background upload executor.
     * On success: done. On failure (offline): inserts into SQLite for the flush cycle.
     */
    private void uploadFix(Location location, boolean isUrgent) {
        final Context appContext = getApplicationContext();
        uploadExecutor.execute(() -> {
            try {
                LocationTable.Location tableLocation = new LocationTable.Location(location);
                tableLocation.setTs(System.currentTimeMillis());

                boolean sent = LocationUploader.sendUrgentLocation(appContext, tableLocation, isUrgent);
                if (!sent) {
                    // Offline — queue in SQLite, flush cycle will retry.
                    DatabaseHelper helper = DatabaseHelper.instance(appContext);
                    if (helper != null) {
                        LocationTable.insert(helper.getWritableDatabase(), tableLocation);
                    }
                }
                RemoteLogger.log(appContext, Const.LOG_INFO,
                        "LocationForegroundService: fix " + (sent ? "uploaded" : "queued offline")
                        + " (" + (isUrgent ? "urgent" : "stream") + ")");
            } catch (Exception e) {
                RemoteLogger.log(appContext, Const.LOG_WARN,
                        "LocationForegroundService: uploadFix failed: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Urgent request handler — MAX_PRIORITY path
    // ---------------------------------------------------------------------------

    private void handleUrgentRequest() {
        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService: urgent GPS triggered — MAX_PRIORITY");

        // Cancel any queued urgent task so the newest request always wins.
        Future<?> prev = currentUrgentTask;
        if (prev != null && !prev.isDone()) {
            prev.cancel(true);
        }

        final Context appContext = getApplicationContext();
        currentUrgentTask = urgentExecutor.submit(() -> {
            // Explicit re-set — thread factory sets MAX_PRIORITY but re-applying after
            // executor internals guarantees it survives any wrapping.
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

            Location recent = latestContinuousFix;
            long fixAge = recent != null
                    ? System.currentTimeMillis() - recent.getTime() : Long.MAX_VALUE;

            if (recent != null && fixAge < URGENT_FIX_MAX_AGE_MS) {
                // GPS is warm — serve the latest streaming fix instantly (sub-second).
                RemoteLogger.log(appContext, Const.LOG_INFO,
                        "LocationForegroundService: urgent served from stream (fix age=" + fixAge + "ms)");
                uploadFix(new Location(recent), true);
                return;
            }

            // No recent fix: GPS lost signal (tunnel, indoors, cold boot).
            // Fall back to a full cold-start capture which tries GPS + Network with 45s timeout.
            RemoteLogger.log(appContext, Const.LOG_INFO,
                    "LocationForegroundService: no recent fix (age="
                    + (fixAge == Long.MAX_VALUE ? "none" : fixAge + "ms")
                    + ") — falling back to cold-start capture");
            try {
                LocationWorker.captureAndUpload(appContext, true, () -> false);
            } catch (Exception e) {
                RemoteLogger.log(appContext, Const.LOG_WARN,
                        "LocationForegroundService: urgent cold-start failed: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Network reconnect callback — flushes the offline SQLite queue immediately
    // when connectivity is restored (e.g. device leaves a tunnel or Wi-Fi dead-zone)
    // ---------------------------------------------------------------------------

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@androidx.annotation.NonNull Network network) {
                RemoteLogger.log(getApplicationContext(), Const.LOG_INFO,
                        "LocationForegroundService: network available — flushing offline queue");
                uploadExecutor.execute(LocationForegroundService.this::runFlushCycle);
            }
        };
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(req, networkCallback);
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: failed to register NetworkCallback: " + e.getMessage());
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
        networkCallback = null;
    }

    // ---------------------------------------------------------------------------
    // Periodic queue flush — runs every 15 min to drain SQLite offline buffer
    // ---------------------------------------------------------------------------

    private void runFlushCycle() {
        try {
            LocationUploader.sendLocations(getApplicationContext());
            RemoteLogger.log(getApplicationContext(), Const.LOG_INFO,
                    "LocationForegroundService: offline queue flush complete");
        } catch (Exception e) {
            RemoteLogger.log(getApplicationContext(), Const.LOG_WARN,
                    "LocationForegroundService: flush cycle failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // Battery optimization exemption — request once per install so Doze mode
    // does not suspend network access for the MQTT client or this service.
    // Foreground services CAN start activities on Android 10+ (unlike pure background),
    // so the system settings dialog is safe to launch from here.
    // ---------------------------------------------------------------------------

    private static final String PREFS_SERVICE = "mdm_service_prefs";
    private static final String PREF_BATTERY_OPT_REQUESTED = "battery_opt_requested";

    private void requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;

        RemoteLogger.log(this, Const.LOG_WARN,
                "LocationForegroundService: app is subject to battery optimization — "
                + "GPS and MQTT delivery may be delayed when the device is idle");

        // Only prompt once: a SharedPreferences flag survives app restarts/upgrades
        // but is cleared on factory reset, which is the correct behaviour.
        SharedPreferences prefs = getSharedPreferences(PREFS_SERVICE, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_BATTERY_OPT_REQUESTED, false)) return;
        prefs.edit().putBoolean(PREF_BATTERY_OPT_REQUESTED, true).apply();

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            RemoteLogger.log(this, Const.LOG_INFO,
                    "LocationForegroundService: requested battery optimization exemption");
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: could not request battery exemption: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // Foreground notification — IMPORTANCE_MIN so it is silent and invisible in
    // the status bar. Disabling app notifications from Settings hides it entirely.
    // ---------------------------------------------------------------------------

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Location Service",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.white_app_name))
                .setContentText(getString(R.string.location_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        Utils.startStableForegroundService(this, NOTIFICATION_ID, notification);
    }
}
