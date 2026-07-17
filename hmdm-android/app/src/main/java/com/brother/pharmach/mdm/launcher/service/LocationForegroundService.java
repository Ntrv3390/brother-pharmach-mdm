package com.brother.pharmach.mdm.launcher.service;

import android.Manifest;
import android.app.AlarmManager;
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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

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

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.IntentFilter;

import com.brother.pharmach.mdm.launcher.util.LegacyUtils;
import com.brother.pharmach.mdm.launcher.util.LocationDiag;
import com.brother.pharmach.mdm.launcher.util.OemCompat;

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
    private static final String EXTRA_REQ_ID = "reqId";

    // Best-effort hand-off of the reqId from triggerUrgent() to onCreate(), since onCreate() has
    // no Intent parameter. Safe because Android serializes a single service instance's onCreate()
    // — a concurrent plain start() cannot race a second onCreate() while one is in progress.
    // Consumed (read-and-cleared) exactly once by onCreate().
    private static volatile String pendingColdStartReqId;

    private static final String CHANNEL_ID = "location_service_channel";
    private static final int NOTIFICATION_ID = 1002;
    private static final String PREFS_LOCATION_CACHE = "mdm_location_cache";
    private static final String ACTION_PENDING_INTENT_LOCATION =
            "com.brother.pharmach.mdm.launcher.LOCATION_UPDATE";

    // Continuous listener update frequency — aggressive for vehicle tracking.
    private static final long GPS_MIN_TIME_MS = 4_000L;       // GPS callback at most every 4s
    private static final float GPS_MIN_DISTANCE_M = 10f;      // GPS callback at most every 10m
    private static final long NETWORK_MIN_TIME_MS = 8_000L;
    private static final float NETWORK_MIN_DISTANCE_M = 20f;

    // Upload throttle — prevents HTTP call on every GPS callback.
    private static final long MIN_UPLOAD_INTERVAL_MS = 15_000L; // don't upload more often than every 15s
    private static final float MIN_UPLOAD_DISTANCE_M = 100f;    // upload when device moved >= 100m
    private static final long MAX_UPLOAD_INTERVAL_MS = 60_000L; // force upload every 60s regardless of movement

    // Guaranteed heartbeat: uploads a location every 30s regardless of whether the continuous
    // listener has delivered any callback at all. considerUpload()'s MAX_UPLOAD_INTERVAL_MS above
    // only fires from WITHIN onLocationChanged() — if the listener gets zero callbacks (OEM
    // suppression, Doze, etc.), that logic never runs at all. This is an independent timer so
    // there's always a periodic upload attempt even during a total callback blackout.
    private static final long HEARTBEAT_INTERVAL_SECONDS = 30L;

    // Urgent: use the streaming in-memory fix if it's < 20s old (GPS is warm, instant response).
    private static final long URGENT_FIX_MAX_AGE_MS = 20_000L;

    // A held in-memory fix older than this is superseded by ANY incoming fix regardless of
    // provider/accuracy — under Doze or GNSS blackout the coarse network stream must still take
    // over so tracking never goes silent.
    private static final long FIX_SUPERSEDE_AGE_MS = 60_000L;

    // Uploads stamp "now" only when the fix is at most this old. Older (cached/heartbeat
    // fallback) fixes keep their real time so the server can tell a live position from a stale
    // one — mirrors LocationWorker.performUpload's stale-fallback semantics.
    private static final long FRESH_FIX_STAMP_MAX_AGE_MS = 30_000L;

    // A duplicate urgent request within this window joins the in-flight capture instead of
    // cancelling it. Sized to the urgent cold-start capture (20s provider wait + 2s outer
    // margin + upload) plus headroom; anything still running past this is stuck and replaced.
    private static final long URGENT_COALESCE_WINDOW_MS = 35_000L;

    // Heartbeat re-checks all caches (incl. the GMS Fused cache fed by other apps) once the
    // in-memory fix is older than this, so a newer external fix is picked up while our own
    // location callbacks are suppressed.
    private static final long HEARTBEAT_CACHE_RECHECK_AGE_MS = 60_000L;

    // Proactive cache refresh: when the best known fix is older than this, each heartbeat also
    // fires a lightweight balanced-power (WiFi/cell, no GNSS) fused request so the cache the
    // urgent instant-response serves from stays under ~10 minutes old. Throttled to one attempt
    // per PROACTIVE_REFRESH_MIN_INTERVAL_MS.
    private static final long PROACTIVE_REFRESH_AGE_MS = 8 * 60_000L;
    private static final long PROACTIVE_REFRESH_MIN_INTERVAL_MS = 60_000L;
    private static final long PROACTIVE_REFRESH_TIMEOUT_SECONDS = 15L;

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
    private LocationDiag.ContinuousGnssMonitor continuousGnssMonitor;

    // Executors
    private ExecutorService urgentExecutor;    // Thread.MAX_PRIORITY, non-daemon
    private ExecutorService uploadExecutor;    // background HTTP uploads from continuous stream
    private ScheduledExecutorService flushScheduler; // periodic queue-flush cycle
    private ScheduledExecutorService heartbeatScheduler; // guaranteed every-30s upload

    // Track in-flight urgent task so duplicate pushes coalesce into it (see handleUrgentRequest).
    private volatile Future<?> currentUrgentTask;
    private volatile long urgentTaskStartedMs;

    // Proactive refresh single-flight guard + attempt throttle.
    private final java.util.concurrent.atomic.AtomicBoolean proactiveRefreshInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long lastProactiveRefreshAttemptMs;

    // Flush the offline SQLite queue when the network comes back.
    private ConnectivityManager.NetworkCallback networkCallback;

    // PendingIntent-based location path — active in parallel with HandlerThread on restricted OEMs.
    private PendingIntent locationPendingIntent;
    private BroadcastReceiver pendingIntentReceiver;

    // ---------------------------------------------------------------------------
    // Continuous LocationListener — callbacks delivered on listenerThread.
    // ---------------------------------------------------------------------------

    private final LocationListener continuousListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (location.getLatitude() == 0.0 && location.getLongitude() == 0.0) {
                return;
            }
            if (!shouldReplaceFix(location, latestContinuousFix)) {
                return;
            }
            latestContinuousFix = location;
            writeFixToSharedPrefs(location);
            considerUpload(location, "handlerThread");
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // ForegroundServiceStartNotAllowedException (API 31+) is thrown when
            // startForegroundService() is called from a background context (e.g. the 15-minute
            // AlarmManager watchdog or a connectivity callback at boot). Swallow it so the alarm
            // chain survives — an uncaught throw here would kill the process and drop the user to
            // the system launcher. The periodic watchdog and worker fallbacks will retry.
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationForegroundService: start blocked (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * @param origin identifies which code path is asking for an urgent GPS refresh (e.g.
     *               "pushMessage:fetchGpsUrgent", "pushMessage:configUpdatedSideEffect",
     *               "initializerConfigComplete") — this is what lets the next log capture prove
     *               or disprove whether multiple origins are firing for a single admin action.
     */
    public static void triggerUrgent(Context context, String origin) {
        triggerUrgent(context, origin, -1);
    }

    /** @param upstreamTimestampMs wall-clock time of the event that caused this call (e.g. push
     *                             receipt), or -1 if there is no meaningful upstream timestamp. */
    public static void triggerUrgent(Context context, String origin, long upstreamTimestampMs) {
        String reqId = LocationDiag.beginRequest(context, origin);
        LocationDiag.logUpstreamLatency(context, reqId, origin, upstreamTimestampMs);
        LocationDiag.timeline(context, reqId, "triggerUrgent:entered");

        Intent intent = new Intent(context, LocationForegroundService.class);
        intent.setAction(ACTION_URGENT_GPS);
        intent.putExtra(EXTRA_REQ_ID, reqId);
        pendingColdStartReqId = reqId;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(intent);
                LocationDiag.timeline(context, reqId, "triggerUrgent:startForegroundServiceReturned");
                // Request is now handed off to onStartCommand()/handleUrgentRequest() — it will
                // call LocationDiag.endRequest() once the capture completes.
            } catch (Exception e) {
                // ForegroundServiceStartNotAllowedException (API 31+) is thrown on Android 12+
                // when startForegroundService() is called from a background context — tightened
                // further in Android 14/15. Fall back to a direct thread-based capture that
                // bypasses FGS restrictions so urgent GPS never silently fails.
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationForegroundService: startForegroundService blocked ("
                        + e.getClass().getSimpleName() + "), falling back to direct capture");
                LocationDiag.timeline(context, reqId, "triggerUrgent:startForegroundServiceBlocked:"
                        + e.getClass().getSimpleName());
                pendingColdStartReqId = null;
                // enqueueUrgentNow() is now the terminal consumer — it calls endRequest() itself.
                LocationWorker.enqueueUrgentNow(context, reqId);
            }
        } else {
            context.startService(intent);
            LocationDiag.timeline(context, reqId, "triggerUrgent:startServiceReturned(legacy)");
        }
    }

    // ---------------------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        String reqId = pendingColdStartReqId;
        pendingColdStartReqId = null;
        if (reqId == null) reqId = "none(coldStartNotFromTriggerUrgent)";
        LocationDiag.timeline(this, reqId, "onCreate:entered");
        LocationDiag.DozeTracker.register(this);

        // Call startForeground() IMMEDIATELY — before executors, OemCompat checks, or any I/O.
        // AutoDroid kills services that don't call startForeground() within ~5s of
        // startForegroundService(). The placeholder satisfies the 5-second window; the proper
        // notification replaces it below once the OEM channel is built.
        Utils.startStableForegroundService(this, NOTIFICATION_ID, buildPlaceholderNotification());
        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService: startForeground() called immediately (Realme fast-path)");
        LocationDiag.timeline(this, reqId, "onCreate:placeholderForegroundReturned");
        LocationDiag.logFgsRegistration(this, LocationForegroundService.class,
                "onCreate:afterPlaceholderForeground");
        LocationDiag.logDeviceMetadata(this);

        long onCreateStart = System.currentTimeMillis();

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

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "location-heartbeat-fg");
            t.setDaemon(true);
            return t;
        });

        // Replace placeholder with the proper OEM-specific notification.
        startForegroundWithNotification();
        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService: onCreate timing — notificationMs="
                + (System.currentTimeMillis() - onCreateStart));
        LocationDiag.timeline(this, reqId, "onCreate:fullNotificationReturned");
        LocationDiag.logFgsRegistration(this, LocationForegroundService.class,
                "onCreate:afterFullNotification");

        // Mark FGS as alive so LocationWorker can detect if we get killed mid-session.
        getSharedPreferences(PREFS_FGS_ALIVE, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_FGS_ALIVE, true)
                .putLong(KEY_FGS_LAST_START, System.currentTimeMillis())
                .apply();

        checkAndLogStandbyBucket();
        // Ensure battery optimization exemption BEFORE registering listeners so Realme's
        // LocationManager dispatch layer does not suppress callbacks on registration.
        ensureBatteryOptimizationExempted();
        // Best-effort, off the main thread (binder-heavy): disable Battery Saver and its
        // auto-trigger so the OS never throttles power to the location hardware.
        uploadExecutor.execute(this::hardenPowerSettingsAsDeviceOwner);
        startContinuousTracking();
        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService: onCreate timing — trackingStartMs="
                + (System.currentTimeMillis() - onCreateStart));
        LocationDiag.timeline(this, reqId, "onCreate:listenersRegistered");
        LocationDiag.logProcessAndPowerState(this, "onCreate:listenersRegistered");

        registerNetworkCallback();

        // Flush the SQLite queue every 15 min — clears any fixes that were queued
        // while the device was offline. Initial delay = 15 min since the continuous
        // listener already handles real-time uploads.
        flushScheduler.scheduleAtFixedRate(
                this::runFlushCycle,
                LocationWorker.FIRE_PERIOD_MINS,
                LocationWorker.FIRE_PERIOD_MINS,
                TimeUnit.MINUTES);

        // Guaranteed upload every 30s regardless of whether the continuous listener has
        // delivered any callback — see HEARTBEAT_INTERVAL_SECONDS for why this is independent
        // of considerUpload()'s own MAX_UPLOAD_INTERVAL_MS throttle.
        heartbeatScheduler.scheduleAtFixedRate(
                this::runHeartbeatUpload,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService started — continuous GPS active");
        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService: onCreate complete — totalMs="
                + (System.currentTimeMillis() - onCreateStart));
        LocationDiag.timeline(this, reqId, "onCreate:complete");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_URGENT_GPS.equals(intent.getAction())) {
            String reqId = intent.getStringExtra(EXTRA_REQ_ID);
            if (reqId == null) {
                // Defensive: should not happen since triggerUrgent() always sets this extra, but
                // a missing correlation id must not silently break CONCURRENT_REQUESTS accounting.
                reqId = LocationDiag.beginRequest(this, "onStartCommand:unknownOrigin(missingReqIdExtra)");
            }
            LocationDiag.timeline(this, reqId, "onStartCommand:urgentActionReceived");
            LocationDiag.logFgsRegistration(this, LocationForegroundService.class,
                    "onStartCommand:urgentActionReceived");
            handleUrgentRequest(reqId);
        }
        // START_STICKY: OS automatically restarts this service if killed.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getSharedPreferences(PREFS_FGS_ALIVE, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_FGS_ALIVE, false)
                .apply();
        LocationDiag.DozeTracker.unregister(this);
        stopPendingIntentTracking();
        stopContinuousTracking();
        unregisterNetworkCallback();
        flushScheduler.shutdownNow();
        heartbeatScheduler.shutdownNow();
        urgentExecutor.shutdownNow();
        uploadExecutor.shutdownNow();
        RemoteLogger.log(this, Const.LOG_INFO, "LocationForegroundService stopped");
        // Nothing in the app ever stops this service intentionally — any onDestroy means the
        // OS/OEM killed it. Resurrect in ~5s; the 15-min watchdog remains the safety net.
        scheduleSelfRestart("onDestroy");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Some OEMs (MIUI, ColorOS) kill the whole process when the task is swiped away.
        scheduleSelfRestart("onTaskRemoved");
    }

    /** Arms a one-shot alarm that restarts this FGS shortly after it is killed. */
    private void scheduleSelfRestart(String origin) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(getApplicationContext(), LocationForegroundService.class);
            PendingIntent pi = PendingIntent.getForegroundService(
                    getApplicationContext(), 2001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long triggerAt = System.currentTimeMillis() + 5_000L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10_000L, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: killed (" + origin + ") — restart alarm armed for 5s");
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: failed to arm restart alarm: " + e.getMessage());
        }
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

        // Runs for the FGS lifetime (not just during urgent windows) — answers whether the
        // continuous listener path gets ANY GNSS engagement at all, independent of urgent requests.
        continuousGnssMonitor = LocationDiag.ContinuousGnssMonitor.start(this, locationManager);

        listenerThread = new HandlerThread("location-listener-fg");
        listenerThread.start();

        registerProvider(LocationManager.GPS_PROVIDER, fineGranted,
                GPS_MIN_TIME_MS, GPS_MIN_DISTANCE_M);
        registerProvider(LocationManager.NETWORK_PROVIDER, coarseGranted || fineGranted,
                NETWORK_MIN_TIME_MS, NETWORK_MIN_DISTANCE_M);

        if (OemCompat.requiresPendingIntentLocationUpdates()) {
            startPendingIntentTracking();
        }

        // Seed latestContinuousFix from the system cache so urgent requests before
        // the first live callback have something to work with.
        seedLastKnownFix();
    }

    private void registerProvider(String provider, boolean hasPermission, long minTime, float minDistance) {
        if (!hasPermission || locationManager == null || listenerThread == null) {
            return;
        }
        try {
            if (LocationManager.NETWORK_PROVIDER.equals(provider) && !OemCompat.isGmsAvailable(this)) {
                RemoteLogger.log(this, Const.LOG_INFO,
                        "LocationForegroundService: NETWORK_PROVIDER skipped — GMS absent, NLP unavailable");
                return;
            }
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
        Location best = getBestLastKnownLocation();
        if (best != null) {
            latestContinuousFix = best;
            RemoteLogger.log(this, Const.LOG_INFO,
                    "LocationForegroundService: seeded last known fix ("
                    + ((System.currentTimeMillis() - best.getTime()) / 1000) + "s old)");
        }
        // GMS Fused cache often has a fix when LocationManager's cache is empty (cleared on
        // reboot/location toggle). Blocking lookup — must run off the main thread.
        uploadExecutor.execute(() -> {
            Location fused = LocationWorker.tryFusedLastLocation(getApplicationContext());
            if (fused != null
                    && (latestContinuousFix == null
                        || fused.getTime() > latestContinuousFix.getTime())) {
                latestContinuousFix = fused;
                writeFixToSharedPrefs(fused);
                RemoteLogger.log(this, Const.LOG_INFO,
                        "LocationForegroundService: seeded fix from fused cache ("
                        + ((System.currentTimeMillis() - fused.getTime()) / 1000) + "s old)");
            }
        });
    }

    /**
     * Accuracy-aware replacement rule for the in-memory fix. A coarse network callback must not
     * overwrite a fresh, more accurate GPS fix — cell-tower fixes in dense urban areas are off
     * by 0.5–2 km and previously won purely by being a few seconds newer, which is what put the
     * server pin at the locality centroid instead of the vehicle's position. Once the held fix
     * ages past {@link #FIX_SUPERSEDE_AGE_MS}, anything newer wins, preserving the
     * Doze/GNSS-blackout behavior where the network stream takes over rather than tracking
     * going silent.
     */
    private static boolean shouldReplaceFix(@NonNull Location incoming, @Nullable Location current) {
        if (current == null) return true;
        if (System.currentTimeMillis() - current.getTime() > FIX_SUPERSEDE_AGE_MS) return true;
        if (LocationManager.GPS_PROVIDER.equals(incoming.getProvider())) return true;
        if (!LocationManager.GPS_PROVIDER.equals(current.getProvider())) return true;
        // Incoming is a network fix, current is a fresh GPS fix — replace only if the network
        // fix is genuinely more accurate (rare, but possible right after a GNSS cold start).
        float incomingAcc = incoming.hasAccuracy() ? incoming.getAccuracy() : Float.MAX_VALUE;
        float currentAcc = current.hasAccuracy() ? current.getAccuracy() : Float.MAX_VALUE;
        return incomingAcc < currentAcc;
    }

    /** Best available getLastKnownLocation() across GPS/Network/Passive, or null if none. */
    @Nullable
    private Location getBestLastKnownLocation() {
        if (locationManager == null) return null;
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
        if (best != null && best.getLatitude() == 0 && best.getLongitude() == 0) {
            return null;
        }
        return best;
    }

    /**
     * Guaranteed periodic upload, independent of whether the continuous listener has delivered
     * any callback. Prefers the live in-memory fix; falls back to the device's best last-known
     * location (across GPS/Network/Passive) if nothing fresh has arrived — this is the "if fresh
     * fails, send the last known location instead" behavior, applied every 30s rather than only
     * on-demand.
     */
    private void runHeartbeatUpload() {
        try {
            Location location = latestContinuousFix;
            boolean usedLastKnownFallback = false;
            long inMemoryAgeMs = location != null
                    ? System.currentTimeMillis() - location.getTime() : Long.MAX_VALUE;
            if (inMemoryAgeMs > HEARTBEAT_CACHE_RECHECK_AGE_MS) {
                // Fix has gone stale — if that's because the device dozed off, wake it
                // alarm-clock-style so the next cycles upload a genuinely fresh position.
                // No-op when not dozing; throttled internally, so safe every 30s.
                com.brother.pharmach.mdm.launcher.util.DozeExitHelper.escapeDozeIfNeeded(
                        getApplicationContext(), "heartbeat:staleFix");
            }
            if (location == null || inMemoryAgeMs > HEARTBEAT_CACHE_RECHECK_AGE_MS) {
                // In-memory fix missing or aging — re-check every cache: LocationManager
                // last-known, GMS Fused cache, FGS prefs cache, offline SQLite queue. The GMS
                // Fused cache is fed by other apps, so it can hold a NEWER fix than ours even
                // while our own callbacks are suppressed (Doze/ColorOS). Take whichever is
                // newest. Runs on the heartbeat thread, so the blocking Fused lookup is safe.
                Location cached = LocationWorker.getBestCachedLocationAnyAge(
                        getApplicationContext(), locationManager);
                if (cached != null && (location == null || cached.getTime() > location.getTime())) {
                    location = cached;
                    usedLastKnownFallback = true;
                    // Seed the in-memory fix so the next urgent request serves instantly.
                    latestContinuousFix = cached;
                }
            }
            // Regardless of what we upload below, keep the cache young: if the best fix we have
            // is older than PROACTIVE_REFRESH_AGE_MS, actively request a new balanced-power fix.
            maybeProactiveRefresh(location);

            if (location == null) {
                RemoteLogger.log(this, Const.LOG_WARN,
                        "LocationForegroundService: 30s heartbeat — no continuous fix and no"
                        + " cached location in any source, skipping this cycle");
                return;
            }

            long ageS = (System.currentTimeMillis() - location.getTime()) / 1000;
            RemoteLogger.log(this, Const.LOG_INFO,
                    "LocationForegroundService: 30s heartbeat uploading ("
                    + (usedLastKnownFallback ? "lastKnownFallback" : "continuousFix")
                    + ", age=" + ageS + "s"
                    + ", accuracy=" + (location.hasAccuracy() ? location.getAccuracy() + "m" : "unknown") + ")");
            uploadFix(new Location(location), false,
                    usedLastKnownFallback ? "heartbeat30sLastKnown" : "heartbeat30sContinuous");
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: 30s heartbeat failed: " + e.getMessage());
        }
    }

    /**
     * Fires a lightweight balanced-power (WiFi/cell, no GNSS wait) fused request when the best
     * known fix has aged past {@link #PROACTIVE_REFRESH_AGE_MS}, so the cache served by the
     * urgent instant-response path stays under ~10 minutes old even while the app's own GPS
     * callbacks are suppressed (Doze, ColorOS). Single-flight and throttled; a success feeds
     * the in-memory fix and the prefs cache, and the next heartbeat uploads it.
     */
    private void maybeProactiveRefresh(@Nullable Location current) {
        long ageMs = current != null
                ? System.currentTimeMillis() - current.getTime() : Long.MAX_VALUE;
        if (ageMs < PROACTIVE_REFRESH_AGE_MS) return;
        long sinceLastAttempt = System.currentTimeMillis() - lastProactiveRefreshAttemptMs;
        if (sinceLastAttempt < PROACTIVE_REFRESH_MIN_INTERVAL_MS) return;
        if (!proactiveRefreshInFlight.compareAndSet(false, true)) return;
        lastProactiveRefreshAttemptMs = System.currentTimeMillis();

        new Thread(() -> {
            try {
                RemoteLogger.log(getApplicationContext(), Const.LOG_INFO,
                        "LocationForegroundService: proactive refresh — best fix is "
                        + (ageMs == Long.MAX_VALUE ? "absent" : (ageMs / 1000) + "s old")
                        + ", requesting balanced-power fix");
                Location fresh = LocationWorker.tryFusedBalancedCurrentLocation(
                        getApplicationContext(), PROACTIVE_REFRESH_TIMEOUT_SECONDS);
                if (fresh != null) {
                    latestContinuousFix = fresh;
                    writeFixToSharedPrefs(fresh);
                    RemoteLogger.log(getApplicationContext(), Const.LOG_INFO,
                            "LocationForegroundService: proactive refresh success (accuracy="
                            + (fresh.hasAccuracy() ? fresh.getAccuracy() + "m" : "unknown")
                            + ") — cache is fresh again");
                }
            } catch (Exception e) {
                RemoteLogger.log(getApplicationContext(), Const.LOG_WARN,
                        "LocationForegroundService: proactive refresh failed: " + e.getMessage());
            } finally {
                proactiveRefreshInFlight.set(false);
            }
        }, "proactive-loc-refresh").start();
    }

    private void stopContinuousTracking() {
        if (continuousGnssMonitor != null) {
            continuousGnssMonitor.stop();
            continuousGnssMonitor = null;
        }
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

    private void considerUpload(Location location, String source) {
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
            uploadFix(new Location(location), false, source);
        }
    }

    /**
     * Queues a location for upload on the background upload executor.
     * On success: done. On failure (offline): inserts into SQLite for the flush cycle.
     */
    private void uploadFix(Location location, boolean isUrgent, String source) {
        final Context appContext = getApplicationContext();
        uploadExecutor.execute(() -> {
            try {
                LocationTable.Location tableLocation = new LocationTable.Location(location);
                long fixAgeMs = System.currentTimeMillis() - location.getTime();
                if (fixAgeMs >= 0 && fixAgeMs <= FRESH_FIX_STAMP_MAX_AGE_MS) {
                    tableLocation.setTs(System.currentTimeMillis());
                }
                // else: cached/heartbeat-fallback fix — keep its real time (constructor default)
                // so the server sees it as stale instead of a live position.

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
                        + " (" + (isUrgent ? "urgent" : "stream") + ", source=" + source + ")");
            } catch (Exception e) {
                RemoteLogger.log(appContext, Const.LOG_WARN,
                        "LocationForegroundService: uploadFix failed: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Urgent request handler — MAX_PRIORITY path
    // ---------------------------------------------------------------------------

    private void handleUrgentRequest(String reqId) {
        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService: urgent GPS triggered — MAX_PRIORITY reqId=" + reqId);

        // Coalesce instead of cancel: one "Get Latest GPS" click makes the server send BOTH a
        // fetchGpsUrgent push and a configUpdated push, and the configUpdated side-effect used to
        // cancel(true) the real capture mid-flight (field logs: InterruptedException 5s in, then a
        // fresh 45s wait from zero). An in-flight capture's upload serves every caller, so a new
        // request just joins it. Only a capture stuck past the full provider timeout is replaced.
        Future<?> prev = currentUrgentTask;
        if (prev != null && !prev.isDone()) {
            long inFlightMs = System.currentTimeMillis() - urgentTaskStartedMs;
            if (inFlightMs < URGENT_COALESCE_WINDOW_MS) {
                RemoteLogger.log(this, Const.LOG_INFO,
                        "LocationForegroundService: urgent request coalesced — capture already"
                        + " in flight for " + inFlightMs + "ms, its upload serves this request too"
                        + " reqId=" + reqId);
                LocationDiag.timeline(this, reqId, "handleUrgentRequest:coalescedIntoInFlight");
                LocationDiag.endRequest(this, reqId);
                return;
            }
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: previous urgent capture stuck for " + inFlightMs
                    + "ms — cancelling and starting fresh");
            prev.cancel(true);
        }
        urgentTaskStartedMs = System.currentTimeMillis();

        final Context appContext = getApplicationContext();
        LocationDiag.timeline(appContext, reqId, "handleUrgentRequest:submittedToExecutor");
        currentUrgentTask = urgentExecutor.submit(() -> {
            try {
                // Explicit re-set — thread factory sets MAX_PRIORITY but re-applying after
                // executor internals guarantees it survives any wrapping.
                Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                LocationDiag.timeline(appContext, reqId, "handleUrgentRequest:threadStarted");
                LocationDiag.logProcessAndPowerState(appContext, "handleUrgentRequest:threadStarted");

                Location recent = latestContinuousFix;
                long fixAge = recent != null
                        ? System.currentTimeMillis() - recent.getTime() : Long.MAX_VALUE;

                if (recent != null && fixAge < URGENT_FIX_MAX_AGE_MS) {
                    // GPS is warm — serve the latest streaming fix instantly (sub-second).
                    RemoteLogger.log(appContext, Const.LOG_INFO,
                            "LocationForegroundService: urgent served from stream (fix age=" + fixAge
                            + "ms, source=fgsMemoryCache)");
                    LocationDiag.timeline(appContext, reqId, "handleUrgentRequest:servedFromMemoryCache");
                    uploadFix(new Location(recent), true, "fgsMemoryCache");
                    return;
                }

                // No recent fix: GPS lost signal (tunnel, indoors, cold boot).
                // Fall back to a full cold-start capture which tries GPS + Network with 45s timeout.
                RemoteLogger.log(appContext, Const.LOG_INFO,
                        "LocationForegroundService: no recent fix (age="
                        + (fixAge == Long.MAX_VALUE ? "none" : fixAge + "ms")
                        + ") — falling back to cold-start capture");
                LocationDiag.timeline(appContext, reqId, "handleUrgentRequest:coldStartBegin");
                try {
                    LocationWorker.captureAndUpload(appContext, true, () -> false, reqId);
                } catch (Exception e) {
                    RemoteLogger.log(appContext, Const.LOG_WARN,
                            "LocationForegroundService: urgent cold-start failed: " + e.getMessage());
                } finally {
                    LocationDiag.timeline(appContext, reqId, "handleUrgentRequest:coldStartEnd");
                }
            } finally {
                // This is the terminal consumer for both the memory-cache and cold-start branches.
                LocationDiag.endRequest(appContext, reqId);
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
    // Battery optimization exemption — required for GPS callback delivery on Realme/MIUI.
    // Realme's LocationManager dispatch layer suppresses callbacks when battery optimization
    // is active, even for foreground services with PendingIntent registration.
    //
    // Strategy: Device Owner → programmatic exemption (no dialog).
    // Fallback: system dialog (no one-time gate — retry until actually granted).
    // ---------------------------------------------------------------------------

    private static final String PREFS_SERVICE = "mdm_service_prefs";
    private static final String PREFS_FGS_ALIVE = "mdm_fgs_state";
    private static final String KEY_FGS_ALIVE = "fgs_alive";
    private static final String KEY_FGS_LAST_START = "fgs_last_start_ms";
    private static final String PREF_STANDBY_NOTIF_SHOWN = "standby_notif_shown";
    private static final int NOTIFICATION_ID_STANDBY = 1003;
    private static final String CHANNEL_ID_ALERT = "location_alert_channel";

    /**
     * Ensures battery optimization is disabled for this package, using only programmatic
     * (no-dialog) approaches. No user-facing dialogs or intents are launched — on a kiosk/MDM
     * device there is no user to tap "Allow", so we rely on Device Owner APIs and the
     * location-capture fix in LocationWorker (getCurrentLocation on API 34+) which bypasses
     * Realme's callback-suppression layer entirely.
     *
     * Strategy:
     * 1. Already exempt — do nothing.
     * 2. Device Owner — setApplicationExemptions (API 35) + cmd deviceidle whitelist via DPM.
     *
     * Called from onCreate() BEFORE startContinuousTracking() so listeners are registered
     * only after exemption is in effect.
     */
    private void ensureBatteryOptimizationExempted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;

        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }

        boolean deviceOwner = Utils.isDeviceOwner(this);
        RemoteLogger.log(this, Const.LOG_WARN,
                "LocationForegroundService: battery optimization active"
                + " (deviceOwner=" + deviceOwner + ", OEM=" + Build.MANUFACTURER + ")"
                + " — using programmatic exemption only (no dialogs)");

        if (deviceOwner) {
            boolean attempted = tryGrantExemptionAsDeviceOwner();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            if (attempted && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                RemoteLogger.log(this, Const.LOG_INFO,
                        "LocationForegroundService: battery optimization exempted"
                        + " via Device Owner API");
                return;
            }
        }

        // Exemption could not be granted programmatically (Realme/ColorOS uses its own
        // battery optimization layer that AOSP APIs may not control). The location-capture
        // fix in LocationWorker.getCurrentLocation() (API 34+) bypasses this by using the
        // system service's internal thread, so GPS can still be obtained on this device.
        RemoteLogger.log(this, Const.LOG_WARN,
                "LocationForegroundService: battery optimization NOT exempted"
                + " — relying on LocationWorker.getCurrentLocation() fallback");
    }

    // Once per process — the FGS can be recreated many times per day and these settings stick.
    private static volatile boolean powerHardeningAttempted = false;

    /**
     * Best-effort Device Owner hardening of global power settings: turns Battery Saver off and
     * sets its auto-trigger level to 0 so the OS never throttles power to the location hardware
     * (Battery Saver forces location to "only while screen on" even for foreground services).
     * Uses the same hidden executeShellCommand channel as tryGrantExemptionAsDeviceOwner —
     * fails harmlessly (logged) on devices where that channel is unsupported.
     */
    private void hardenPowerSettingsAsDeviceOwner() {
        if (powerHardeningAttempted) return;
        powerHardeningAttempted = true;
        if (!Utils.isDeviceOwner(this)) return;
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) return;
        ComponentName admin = LegacyUtils.getAdminComponentName(this);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService: power hardening — batterySaverActive="
                + (pm != null && pm.isPowerSaveMode())
                + ", disabling Battery Saver + auto-trigger via DPM shell (best-effort)");

        runDpmShell(dpm, admin, "settings put global low_power 0");
        runDpmShell(dpm, admin, "settings put global low_power_trigger_level 0");

        // Kiosk fleet: disable Doze (deep idle) entirely so GNSS, timers and network are never
        // suspended between trips. Resets on reboot, but this runs on every FGS process start
        // (BootReceiver → FGS → here). DozeExitHelper remains the runtime escape hatch for
        // devices where this shell command is unsupported.
        runDpmShell(dpm, admin, "cmd deviceidle disable");

        // PUBLIC Device Owner API — works on stock builds where the hidden shell channel above
        // throws NoSuchMethodException. Keeps the screen on whenever the device is charging
        // (AC/USB/wireless = 7); a lit screen makes Doze impossible, so vehicle-mounted devices
        // on a charger never lose GNSS.
        try {
            dpm.setGlobalSetting(admin,
                    android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    String.valueOf(android.os.BatteryManager.BATTERY_PLUGGED_AC
                            | android.os.BatteryManager.BATTERY_PLUGGED_USB
                            | android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS));
            RemoteLogger.log(this, Const.LOG_INFO,
                    "LocationForegroundService: STAY_ON_WHILE_PLUGGED_IN set — screen stays on"
                    + " while charging, preventing Doze on vehicle-mounted devices");
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: setGlobalSetting(STAY_ON_WHILE_PLUGGED_IN)"
                    + " failed: " + e.getMessage());
        }
    }

    /** Executes a shell command via the hidden DPM channel. Returns true if it ran. */
    private boolean runDpmShell(DevicePolicyManager dpm, ComponentName admin, String shellCmd) {
        try {
            android.os.ParcelFileDescriptor[] pipe =
                    android.os.ParcelFileDescriptor.createPipe();
            dpm.getClass()
               .getMethod("executeShellCommand", ComponentName.class, String.class,
                       android.os.ParcelFileDescriptor.class,
                       android.os.ParcelFileDescriptor.class)
               .invoke(dpm, admin, shellCmd, pipe[1], null);
            pipe[0].close();
            pipe[1].close();
            RemoteLogger.log(this, Const.LOG_INFO,
                    "LocationForegroundService: DPM shell succeeded: " + shellCmd);
            return true;
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: DPM shell failed (" + shellCmd + "): "
                    + e.getClass().getSimpleName() + " — " + e.getMessage());
            return false;
        }
    }

    /**
     * Attempts to grant battery optimization exemption using Device Owner privileges.
     * Tries approaches in order from most specific to most compatible. Returns true if
     * any approach executed without fatal exception (caller must verify with
     * isIgnoringBatteryOptimizations() after this returns).
     *
     * Note: only AOSP-standard approaches are used. OEM-specific shell commands
     * (appops, settings put global) are not included because they require root or
     * undocumented permissions that a Device Owner does not have.
     */
    private boolean tryGrantExemptionAsDeviceOwner() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) return false;
        ComponentName admin = LegacyUtils.getAdminComponentName(this);

        // Approach A: API 35 — setApplicationExemptions() (Device Owner API, Android 15+)
        // AOSP signature: setApplicationExemptions(ComponentName, List<String>, Set<Integer>)
        if (Build.VERSION.SDK_INT >= 35) {
            java.util.Set<Integer> exemptions = new java.util.HashSet<>();
            exemptions.add(1); // DevicePolicyManager.BATTERY_OPTIMIZATION_EXEMPTION

            // Try (ComponentName, List, Set) — the most likely AOSP signature
            try {
                java.util.List<String> packages = java.util.Collections.singletonList(getPackageName());
                dpm.getClass()
                   .getMethod("setApplicationExemptions", ComponentName.class,
                           java.util.List.class, java.util.Set.class)
                   .invoke(dpm, admin, packages, exemptions);
                RemoteLogger.log(this, Const.LOG_INFO,
                        "LocationForegroundService: setApplicationExemptions(ComponentName,List,Set) succeeded");
                return true;
            } catch (Exception e) {
                RemoteLogger.log(this, Const.LOG_WARN,
                        "LocationForegroundService: setApplicationExemptions(ComponentName,List,Set) failed: "
                        + e.getClass().getSimpleName() + " — " + e.getMessage());
            }

            // Try (ComponentName, String, Set) — older signature variant
            try {
                dpm.getClass()
                   .getMethod("setApplicationExemptions", ComponentName.class,
                           String.class, java.util.Set.class)
                   .invoke(dpm, admin, getPackageName(), exemptions);
                RemoteLogger.log(this, Const.LOG_INFO,
                        "LocationForegroundService: setApplicationExemptions(ComponentName,String,Set) succeeded");
                return true;
            } catch (Exception e) {
                RemoteLogger.log(this, Const.LOG_WARN,
                        "LocationForegroundService: setApplicationExemptions(ComponentName,String,Set) failed: "
                        + e.getClass().getSimpleName() + " — " + e.getMessage());
            }
        }

        // Approach B: cmd deviceidle whitelist via DPM executeShellCommand.
        // Device Owner can execute shell commands; equivalent to:
        //   adb shell cmd deviceidle whitelist +<package>
        // This adds the package to the AOSP Doze whitelist, though Realme's
        // proprietary battery optimization layer may still suppress GPS callbacks.
        try {
            String shellCmd = "cmd deviceidle whitelist +" + getPackageName();
            android.os.ParcelFileDescriptor[] pipe =
                    android.os.ParcelFileDescriptor.createPipe();
            dpm.getClass()
               .getMethod("executeShellCommand", ComponentName.class, String.class,
                       android.os.ParcelFileDescriptor.class,
                       android.os.ParcelFileDescriptor.class)
               .invoke(dpm, admin, shellCmd, pipe[1], null);
            pipe[0].close();
            pipe[1].close();
            RemoteLogger.log(this, Const.LOG_INFO,
                    "LocationForegroundService: DPM shell succeeded: " + shellCmd);
            // Give the system a moment to process the whitelist change.
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            return true;
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: DPM shell failed: "
                    + e.getClass().getSimpleName() + " — " + e.getMessage());
        }

        return false;
    }

    // ---------------------------------------------------------------------------
    // Foreground notification — IMPORTANCE_MIN so it is silent and invisible in
    // the status bar. Disabling app notifications from Settings hides it entirely.
    // ---------------------------------------------------------------------------

    /**
     * Builds a minimal placeholder notification for the immediate startForeground() call in
     * onCreate(). This satisfies the 5-second FGS startup window on Realme/AutoDroid before
     * the full OEM-specific channel is constructed. Replaced by startForegroundWithNotification().
     */
    private Notification buildPlaceholderNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Location Service", NotificationManager.IMPORTANCE_MIN);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.white_app_name))
                .setContentText(getString(R.string.location_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void startForegroundWithNotification() {
        // Use a new channel ID for IMPORTANCE_LOW so Android doesn't silently ignore
        // the importance upgrade (channel importance cannot be lowered after creation,
        // and Android ignores changes to an already-created channel ID).
        int importance = OemCompat.requiredFgsNotificationImportance();
        String channelId = (importance == NotificationManager.IMPORTANCE_LOW)
                ? "location_service_channel_low"
                : CHANNEL_ID;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Location Service", importance);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            if (importance > NotificationManager.IMPORTANCE_MIN) {
                channel.enableLights(false);
                channel.enableVibration(false);
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService: notification channelId=" + channelId
                + " importance=" + importance + " OEM=" + Build.MANUFACTURER);

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.white_app_name))
                .setContentText(getString(R.string.location_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(OemCompat.requiredNotificationPriority())
                .build();

        Utils.startStableForegroundService(this, NOTIFICATION_ID, notification);
    }

    // ---------------------------------------------------------------------------
    // PendingIntent tracking — parallel path for OEMs that freeze HandlerThread loopers
    // (Realme AutoDroid, Xiaomi MIUI, Vivo OriginOS). PendingIntent delivery routes
    // through ActivityManagerService → BroadcastQueue in system server, which the OEM
    // process manager cannot freeze.
    // ---------------------------------------------------------------------------

    private void startPendingIntentTracking() {
        try {
            pendingIntentReceiver = new LocationUpdateReceiver();
            IntentFilter filter = new IntentFilter(ACTION_PENDING_INTENT_LOCATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pendingIntentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(pendingIntentReceiver, filter);
            }

            Intent baseIntent = new Intent(ACTION_PENDING_INTENT_LOCATION)
                    .setPackage(getPackageName());
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                piFlags |= PendingIntent.FLAG_MUTABLE;
            }
            locationPendingIntent = PendingIntent.getBroadcast(this, 0, baseIntent, piFlags);

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        GPS_MIN_TIME_MS, GPS_MIN_DISTANCE_M, locationPendingIntent);
            }
            if (OemCompat.isGmsAvailable(this)
                    && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        NETWORK_MIN_TIME_MS, NETWORK_MIN_DISTANCE_M, locationPendingIntent);
            }

            RemoteLogger.log(this, Const.LOG_INFO,
                    "LocationForegroundService: OEM=" + Build.MANUFACTURER
                    + " — PendingIntent path active (parallel with HandlerThread)");
        } catch (SecurityException e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: PendingIntent path SecurityException: " + e.getMessage());
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: PendingIntent path failed: " + e.getMessage());
        }
    }

    private void stopPendingIntentTracking() {
        if (pendingIntentReceiver != null) {
            try {
                unregisterReceiver(pendingIntentReceiver);
            } catch (Exception ignored) {}
            pendingIntentReceiver = null;
        }
        if (locationPendingIntent != null && locationManager != null) {
            try {
                locationManager.removeUpdates(locationPendingIntent);
            } catch (Exception ignored) {}
            locationPendingIntent = null;
        }
    }

    // ---------------------------------------------------------------------------
    // SharedPreferences cache — written on every fix so LocationWorker can read it
    // without waiting 45 s for a HandlerThread that may be frozen by the OEM.
    // Uses doubleToRawLongBits for full double precision (putFloat loses accuracy).
    // ---------------------------------------------------------------------------

    void writeFixToSharedPrefs(Location location) {
        getApplicationContext()
                .getSharedPreferences(PREFS_LOCATION_CACHE, Context.MODE_PRIVATE)
                .edit()
                .putLong("last_fix_lat_bits", Double.doubleToRawLongBits(location.getLatitude()))
                .putLong("last_fix_lng_bits", Double.doubleToRawLongBits(location.getLongitude()))
                .putLong("last_fix_time", location.getTime())
                .putFloat("last_fix_accuracy", location.hasAccuracy() ? location.getAccuracy() : -1f)
                .putString("last_fix_provider",
                        location.getProvider() != null ? location.getProvider() : "")
                .apply();
    }

    // ---------------------------------------------------------------------------
    // LocationUpdateReceiver — receives PendingIntent-delivered GPS fixes.
    // Validates KEY_LOCATION_CHANGED presence (security: rejects spoofed intents
    // without the system-filled extra). Package-scoped action means only system can send.
    // ---------------------------------------------------------------------------

    // ---------------------------------------------------------------------------
    // App Standby Bucket — AOSP feature (API 28+), not Samsung-specific.
    // Checks once at startup; warns user if in RARE/RESTRICTED state.
    // ---------------------------------------------------------------------------

    private void checkAndLogStandbyBucket() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return;

        int bucket = usm.getAppStandbyBucket();
        String bucketName;
        if (bucket == UsageStatsManager.STANDBY_BUCKET_ACTIVE) bucketName = "ACTIVE";
        else if (bucket == UsageStatsManager.STANDBY_BUCKET_WORKING_SET) bucketName = "WORKING_SET";
        else if (bucket == UsageStatsManager.STANDBY_BUCKET_FREQUENT) bucketName = "FREQUENT";
        else if (bucket == UsageStatsManager.STANDBY_BUCKET_RARE) bucketName = "RARE";
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED) bucketName = "RESTRICTED";
        else bucketName = "UNKNOWN(" + bucket + ")";

        RemoteLogger.log(this, Const.LOG_INFO,
                "OemCompat: standbyBucket=" + bucketName + " OEM=" + Build.MANUFACTURER
                + " hibernationRisk=" + (bucket >= UsageStatsManager.STANDBY_BUCKET_RARE));

        if (bucket >= UsageStatsManager.STANDBY_BUCKET_RARE) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationForegroundService: app in " + bucketName
                    + " standby — background location may fail."
                    + " Direct user to battery unrestricted settings.");
            Intent settingsIntent = OemCompat.getBatterySettingsIntent(this);
            String message = OemCompat.isSamsung()
                    ? "Set battery to 'Unrestricted' in App Settings"
                    : "Battery optimization may block GPS updates — tap to fix";
            showBatterySettingsNotification(message, settingsIntent);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED) {
            RemoteLogger.log(this, Const.LOG_ERROR,
                    "LocationForegroundService: app HIBERNATED — all background work blocked");
        }
    }

    private void showBatterySettingsNotification(String message, Intent settingsIntent) {
        SharedPreferences prefs = getSharedPreferences(PREFS_SERVICE, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_STANDBY_NOTIF_SHOWN, false)) return;
        prefs.edit().putBoolean(PREF_STANDBY_NOTIF_SHOWN, true).apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID_ALERT, "Location Alerts", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        PendingIntent pi = null;
        if (settingsIntent != null) {
            try {
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                ? PendingIntent.FLAG_IMMUTABLE : 0);
                pi = PendingIntent.getActivity(this, 1, settingsIntent, piFlags);
            } catch (Exception ignored) {}
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .setContentTitle(getString(R.string.white_app_name))
                .setContentText(message)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pi != null) builder.setContentIntent(pi);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID_STANDBY, builder.build());
    }

    private class LocationUpdateReceiver extends BroadcastReceiver {
        @SuppressWarnings("deprecation")
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.hasExtra(LocationManager.KEY_LOCATION_CHANGED)) return;

            Location location;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                location = intent.getParcelableExtra(
                        LocationManager.KEY_LOCATION_CHANGED, Location.class);
            } else {
                location = intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED);
            }

            if (location == null) return;
            if (location.getLatitude() == 0.0 && location.getLongitude() == 0.0) return;
            if (!shouldReplaceFix(location, latestContinuousFix)) return;

            latestContinuousFix = location;
            writeFixToSharedPrefs(location);
            considerUpload(location, "pendingIntent");

            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationForegroundService: PendingIntent fix received provider="
                    + location.getProvider()
                    + " accuracy=" + (location.hasAccuracy()
                            ? location.getAccuracy() + "m" : "unknown")
                    + " source=pendingIntent");
        }
    }
}
