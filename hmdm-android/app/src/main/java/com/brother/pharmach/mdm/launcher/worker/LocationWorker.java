package com.brother.pharmach.mdm.launcher.worker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.db.DatabaseHelper;
import com.brother.pharmach.mdm.launcher.db.LocationTable;
import com.brother.pharmach.mdm.launcher.service.LocationForegroundService;
import com.brother.pharmach.mdm.launcher.util.LocationUploader;
import android.content.SharedPreferences;

import com.brother.pharmach.mdm.launcher.util.ExperimentalFlags;
import com.brother.pharmach.mdm.launcher.util.LocationDiag;
import com.brother.pharmach.mdm.launcher.util.OemCompat;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.Tasks;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class LocationWorker extends Worker {

    public static final int FIRE_PERIOD_MINS = 15;

    private static final String WORK_TAG_PERIODIC = "com.brother.pharmach.mdm.launcher.WORK_TAG_LOCATION_PERIODIC";
    private static final String WORK_TAG_ONE_SHOT = "com.brother.pharmach.mdm.launcher.WORK_TAG_LOCATION_ONE_SHOT";
    // GPS reacquisition while moving (e.g. low-power mode exit) can take 30-90 s.
    // 45 s gives the chip enough time without blocking too long for periodic runs.
    private static final long GPS_FIX_WAIT_SECONDS = 45;
    // Urgent captures have a hard ~30s answer budget: instant cached upload at ~1s, fresh
    // attempt capped at 20s, upload overhead ~2-5s. The admin never waits out a 45s timeout.
    private static final long URGENT_FIX_WAIT_SECONDS = 20;
    private static final long URGENT_MAX_FIX_AGE_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long PERIODIC_MAX_FIX_AGE_MS = TimeUnit.MINUTES.toMillis(30);
    private static final float MAX_FIX_ACCURACY_METERS = 2000f;
    // Covers parallel GPS+network wait (max 47 s) + DB write + upload.
    private static final long WAKE_LOCK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(180);
    // GPS fix timestamps while moving can lag delivery by up to ~10 s; use 10 s grace.
    private static final long LIVE_UPDATE_FRESHNESS_GRACE_MS = TimeUnit.SECONDS.toMillis(10);

    // Bounded to 1 running + 1 queued. DiscardOldestPolicy: newest push always wins.
    private static final ThreadPoolExecutor URGENT_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "urgent-gps-queue");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    // Serialises DB insert + upload to prevent duplicate rows when a periodic and
    // an urgent capture complete at the same time.
    private static final Object UPLOAD_LOCK = new Object();

    // Tracks consecutive FGS cache misses to detect when AutoDroid has killed the FGS.
    // Static so it persists across doWork() calls within the same process lifetime.
    private static final java.util.concurrent.atomic.AtomicInteger FGS_CACHE_MISS_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int FGS_RESTART_THRESHOLD = 2;

    // H2 experimental fix (ExperimentalFlags.ABORT_ON_INTERRUPT_ENABLED): single-flight gate so
    // a periodic and an urgent captureAndUpload() cannot run requestProvidersInParallel()
    // concurrently. Unused (never acquired) when the flag is off.
    private static final Semaphore PROVIDER_REQUEST_GATE = new Semaphore(1);

    /** Allows captureAndUpload() to poll for WorkManager stop without holding a Worker reference. */
    public interface StopChecker {
        boolean isStopped();
    }

    private final Context context;

    public LocationWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.context = context;
    }

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(LocationWorker.class,
                        FIRE_PERIOD_MINS, TimeUnit.MINUTES,
                        5, TimeUnit.MINUTES)          // 5-min flex window for OS batching
                        .addTag(Const.WORK_TAG_COMMON)
                        .addTag(WORK_TAG_PERIODIC)
                        .setConstraints(constraints)
                        .build();
        // UPDATE (API 31+) replaces parameters without resetting the interval clock.
        // CANCEL_AND_REENQUEUE on older APIs avoids KEEP silently dropping rescheduling
        // when a previous job is stuck.
        ExistingPeriodicWorkPolicy policy =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? ExistingPeriodicWorkPolicy.UPDATE
                        : ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE;
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                WORK_TAG_PERIODIC, policy, request);
    }

    public static void scheduleOneShot(Context context) {
        // Not expedited: avoids foreground-service notification on Android ≤ 11.
        // Truly urgent captures use enqueueUrgentNow() which runs directly in a thread.
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(LocationWorker.class)
                .addTag(Const.WORK_TAG_COMMON)
                .addTag(WORK_TAG_ONE_SHOT)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                WORK_TAG_ONE_SHOT,
                ExistingWorkPolicy.REPLACE,
                request);
    }

    // Executes an urgent GPS refresh immediately in the current process.
    // This bypasses WorkManager scheduling latency and is used by push-triggered refreshes.
    public static Result runUrgentNow(Context context, String reqId) {
        LocationDiag.timeline(context, reqId, "runUrgentNow:entered");
        return captureAndUpload(context, true, () -> false, reqId);
    }

    /**
     * {@code reqId} must already be active (via {@link LocationDiag#beginRequest}) — this is the
     * fallback path used when {@code startForegroundService()} itself throws, so the request was
     * already begun by the caller (LocationForegroundService.triggerUrgent()). This method ends
     * the request once the capture completes, since it is the terminal consumer for this path.
     */
    public static void enqueueUrgentNow(Context context, String reqId) {
        final Context appContext = context.getApplicationContext();
        LocationDiag.timeline(appContext, reqId, "enqueueUrgentNow:queued");
        if (URGENT_EXECUTOR.getQueue().size() >= 1) {
            RemoteLogger.log(appContext, Const.LOG_WARN,
                    "LocationWorker: urgent queue saturated — oldest queued request replaced by new push");
        }
        URGENT_EXECUTOR.execute(() -> {
            try {
                LocationDiag.timeline(appContext, reqId, "enqueueUrgentNow:threadStarted");
                runUrgentNow(appContext, reqId);
            } catch (Exception e) {
                RemoteLogger.log(appContext, Const.LOG_WARN,
                        "LocationWorker: queued urgent capture failed: " + e.getMessage());
            } finally {
                LocationDiag.endRequest(appContext, reqId);
            }
        });
    }

    @NonNull
    @Override
    public Result doWork() {
        if (isStopped()) return Result.success();
        // Ensure the FGS is alive — restarts it if the OS killed it since last boot.
        // (Reverted from the LocationService experiment — see LocationService.java.)
        LocationForegroundService.start(context);
        boolean forceFreshFix = getTags().contains(WORK_TAG_ONE_SHOT);
        String origin = forceFreshFix ? "workManagerOneShot" : "workManagerPeriodic";
        String reqId = LocationDiag.beginRequest(context, origin);
        try {
            return captureAndUpload(context, forceFreshFix, this::isStopped, reqId);
        } finally {
            LocationDiag.endRequest(context, reqId);
        }
    }

    @NonNull
    public static Result captureAndUpload(Context context, boolean forceFreshFix,
                                          StopChecker stopChecker, String reqId) {
        PowerManager.WakeLock wakeLock = null;
        boolean fineGranted = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: neither ACCESS_FINE_LOCATION nor ACCESS_COARSE_LOCATION granted");
            return Result.failure();
        }

        if (fineGranted) {
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: ACCESS_FINE_LOCATION granted — GPS available");
        } else {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: only ACCESS_COARSE_LOCATION granted"
                            + " — GPS unavailable (API 31+ approximate location grant)");
        }

        // WorkManager workers always run in the background on API 29+.
        // Without ACCESS_BACKGROUND_LOCATION:
        //   - getLastKnownLocation() returns null silently on API 34+
        //   - requestLocationUpdates() throws SecurityException (caught silently inside tryRequestLiveUpdate)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean bgGranted = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!bgGranted) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationWorker: ACCESS_BACKGROUND_LOCATION not granted (API "
                                + Build.VERSION.SDK_INT
                                + ") — user likely selected 'Allow only while using the app'."
                                + " All background location calls will fail silently.");
                return Result.failure();
            }
        }

        try {
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: started (urgent=" + forceFreshFix
                            + ", api=" + Build.VERSION.SDK_INT
                            + ", device=" + Build.MANUFACTURER + "/" + Build.MODEL + ")");

            LocationDiag.timeline(context, reqId, "captureAndUpload:entered");
            LocationDiag.logProcessAndPowerState(context, "captureAndUpload:entered");
            LocationDiag.logDeviceMetadata(context);

            // Acquire wake lock for EVERY capture, not just urgent ones.
            // During the 45 s CountDownLatch.await() in tryRequestLiveUpdate, the CPU can enter
            // deep sleep on unexempted devices (Realme AutoDroid, Xiaomi MIUI, Samsung Sleeping Apps),
            // freezing HandlerThread looper delivery and causing the latch to time out with no fix.
            wakeLock = acquireWakeLock(context);

            if (stopChecker.isStopped()) return Result.success();

            LocationManager locationManager =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                RemoteLogger.log(context, Const.LOG_WARN, "LocationWorker: LocationManager is null");
                return Result.success();
            }

            long maxFixAgeMs = forceFreshFix ? URGENT_MAX_FIX_AGE_MS : PERIODIC_MAX_FIX_AGE_MS;
            long fixWaitSeconds = forceFreshFix ? URGENT_FIX_WAIT_SECONDS : GPS_FIX_WAIT_SECONDS;
            long now = System.currentTimeMillis();

            // SharedPrefs cache fast-path: on OEMs with frozen HandlerThread loopers,
            // LocationForegroundService writes every fix here via its PendingIntent path.
            // Read it instead of waiting 45 s for a HandlerThread that may never fire.
            if (OemCompat.requiresPendingIntentLocationUpdates()) {
                Location cached = readFixFromSharedPrefs(context, maxFixAgeMs);
                if (cached != null) {
                    FGS_CACHE_MISS_COUNT.set(0);
                    long ageS = (System.currentTimeMillis() - cached.getTime()) / 1000;
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWorker: served from FGS shared prefs cache (age=" + ageS
                            + "s, accuracy=" + (cached.hasAccuracy()
                                    ? cached.getAccuracy() + "m" : "unknown")
                            + ", source=sharedPrefsCache)");
                    return performUpload(context, cached, forceFreshFix, false);
                }

                int missCount = FGS_CACHE_MISS_COUNT.incrementAndGet();
                SharedPreferences fgsPrefs =
                        context.getSharedPreferences("mdm_fgs_state", Context.MODE_PRIVATE);
                boolean fgsAlive = fgsPrefs.getBoolean("fgs_alive", false);
                long fgsLastStart = fgsPrefs.getLong("fgs_last_start_ms", 0);
                long fgsUptimeSec = fgsLastStart > 0
                        ? (System.currentTimeMillis() - fgsLastStart) / 1000 : -1;
                PowerManager pmDiag = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                boolean batteryExempt = pmDiag != null
                        && pmDiag.isIgnoringBatteryOptimizations(context.getPackageName());
                RemoteLogger.log(context, Const.LOG_INFO,
                        "LocationWorker: FGS cache miss or stale — falling through to HandlerThread"
                        + " (source=handlerThreadFallback, consecutiveMisses=" + missCount + ")");
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationWorker: FGS state check — alive=" + fgsAlive
                        + " uptimeSec=" + fgsUptimeSec
                        + " batteryOptExempt=" + batteryExempt
                        + " (batteryOptExempt=false means GPS callbacks suppressed by OEM)");

                if (missCount >= FGS_RESTART_THRESHOLD) {
                    FGS_CACHE_MISS_COUNT.set(0);
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "LocationWorker: " + missCount
                            + " consecutive FGS cache misses — FGS likely killed by OEM,"
                            + " attempting restart");
                    if (!batteryExempt) {
                        RemoteLogger.log(context, Const.LOG_WARN,
                                "LocationWorker: battery optimization is ACTIVE"
                                + " — GPS callbacks will be suppressed after restart too."
                                + " Skipping FGS restart; using direct capture with getCurrentLocation().");
                        // Skip the futile 2-second restart wait — jump straight to
                        // getCurrentLocation() / HandlerThread capture below.
                    } else {
                        // (Reverted from the LocationService experiment — see LocationService.java.)
                        LocationForegroundService.start(context);
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        Location cachedAfterRestart = readFixFromSharedPrefs(context, maxFixAgeMs);
                        if (cachedAfterRestart != null) {
                            RemoteLogger.log(context, Const.LOG_INFO,
                                    "LocationWorker: FGS restart successful"
                                    + " — cache populated (source=sharedPrefsCache)");
                            return performUpload(context, cachedAfterRestart, forceFreshFix, false);
                        }
                        RemoteLogger.log(context, Const.LOG_WARN,
                                "LocationWorker: FGS restart did not populate cache in time"
                                + " — continuing with HandlerThread");
                    }
                }
            }

            Location lastKnownAny = getBestLastKnownLocation(context, locationManager);
            if (lastKnownAny == null) {
                RemoteLogger.log(context, Const.LOG_INFO,
                        "LocationWorker: all getLastKnownLocation calls returned null"
                                + " (fresh boot, cache cleared, or API 34+ background restriction)");
            }
            Location lastKnownFresh = isLocationFresh(lastKnownAny, maxFixAgeMs) ? lastKnownAny : null;

            // Urgent instant-response: upload the best cached fix from ANY source (LocationManager,
            // GMS FusedLocation cache, FGS prefs cache, offline SQLite queue) right away, so the
            // admin sees a position within seconds even on devices where a fresh fix takes 45 s or
            // never arrives (ColorOS GNSS suppression with screen off). The fresh capture below
            // still runs and uploads a second, better fix when it succeeds. A stale cached fix
            // keeps its original timestamp so the server keeps polling for the fresh one.
            // Entirely off-thread (including cache collection — the fused lookup alone can block
            // ~3s) so the fresh capture below starts with zero added latency.
            final java.util.concurrent.atomic.AtomicReference<Location> immediateSentRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // Set just before the fresh result uploads, so a slow instant-response lookup can't
            // land its (older) fix AFTER the fresh one and regress the position on the server.
            final java.util.concurrent.atomic.AtomicBoolean freshUploadStarted =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            if (forceFreshFix) {
                final Location lastKnownForInstant = lastKnownAny;
                new Thread(() -> {
                    Location cachedAnyAge = chooseBestLocation(lastKnownForInstant,
                            tryFusedLastLocation(context),
                            readFixFromSharedPrefs(context, Long.MAX_VALUE),
                            readLatestQueuedDbFix(context));
                    if (freshUploadStarted.get()) {
                        RemoteLogger.log(context, Const.LOG_INFO,
                                "LocationWorker: instant-response skipped — fresh fix already"
                                + " uploading");
                        return;
                    }
                    if (cachedAnyAge == null) {
                        RemoteLogger.log(context, Const.LOG_WARN,
                                "LocationWorker: urgent instant-response — no cached fix in any"
                                + " source (LocationManager, FusedLocation cache, prefs cache,"
                                + " offline queue all empty)");
                        return;
                    }
                    boolean cachedFresh = isLocationFresh(cachedAnyAge, maxFixAgeMs);
                    long cachedAgeS = Math.max(0,
                            System.currentTimeMillis() - cachedAnyAge.getTime()) / 1000;
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWorker: urgent instant-response — uploading cached fix now"
                            + " (provider=" + cachedAnyAge.getProvider()
                            + ", age=" + cachedAgeS + "s, fresh=" + cachedFresh
                            + ") while fresh capture continues");
                    LocationDiag.timeline(context, reqId,
                            "urgent:instantCachedUpload(fresh=" + cachedFresh + ")");
                    immediateSentRef.set(cachedAnyAge);
                    performUpload(context, cachedAnyAge, true, !cachedFresh);
                }, "urgent-instant-upload").start();
            }

            if (stopChecker.isStopped()) return Result.success();

            RemoteLogger.log(context, Const.LOG_INFO, "LocationWorker: requesting fresh location updates");

            if (forceFreshFix) {
                LocationDiag.firePreCaptureVibrationPulseIfEnabled(context, reqId);
            }

            // H2 experimental fix: single-flight gate around the provider-request phase so a
            // periodic and an urgent capture never run requestProvidersInParallel() at once.
            // With the flag off (default), gateEnabled is false so gateAcquired stays true and
            // no acquire/release is attempted — behavior is identical to before this instrumentation.
            // gateEnabled is captured once so a mid-flight flag toggle can't unbalance acquire/release.
            boolean gateEnabled = ExperimentalFlags.ABORT_ON_INTERRUPT_ENABLED;
            boolean gateAcquired = true;
            if (gateEnabled) {
                try {
                    gateAcquired = PROVIDER_REQUEST_GATE.tryAcquire(2, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    gateAcquired = false;
                }
            }

            ParallelProviderResult parallelResult = new ParallelProviderResult();
            if (!gateAcquired) {
                RemoteLogger.log(context, Const.LOG_WARN, "LocationDiag: SKIPPED_CONCURRENT_REQUEST reqId="
                        + reqId + " — another capture already holds the provider-request gate,"
                        + " skipping fresh GPS/Network request for this attempt");
            } else {
                LocationDiag.logProcessAndPowerState(context, "beforeProvidersRequested");
                LocationDiag.timeline(context, reqId, "providersRequested");
                LocationDiag.GnssWatch gnssWatch = LocationDiag.GnssWatch.start(
                        context, locationManager, forceFreshFix ? "urgent" : "periodic");

                boolean allowNetwork = fineGranted || coarseGranted;
                try {
                    parallelResult = requestProvidersInParallel(
                            context,
                            locationManager,
                            fineGranted,
                            allowNetwork,
                            maxFixAgeMs,
                            fixWaitSeconds,
                            forceFreshFix,
                            stopChecker,
                            reqId);
                } finally {
                    gnssWatch.stopAndSummarize();
                    LocationDiag.timeline(context, reqId, "providersResolved");
                    if (gateEnabled) {
                        PROVIDER_REQUEST_GATE.release();
                    }
                }
            }

            if (stopChecker.isStopped()) return Result.success();

            Location freshGps = isLocationFresh(parallelResult.gps, maxFixAgeMs)
                    ? parallelResult.gps : null;
            Location freshNetwork = isLocationFresh(parallelResult.network, maxFixAgeMs)
                    ? parallelResult.network : null;

            // GPS accuracy (3–10 m) >> network (100–500 m) for vehicle MDM.
            // firstFresh tracks whichever provider won the race (often network).
            // If GPS also produced a fresh fix, override firstFresh so we always report
            // the highest-accuracy result.
            Location chosenFresh = parallelResult.firstFresh;
            if (freshGps != null) {
                chosenFresh = freshGps;
            }

            Location location = chosenFresh != null
                    ? chosenFresh
                    : chooseBestLocation(lastKnownFresh, freshGps, freshNetwork);

            // Track whether we ended up using a stale fallback so we can avoid
            // stamping a fake "now" timestamp on old coordinates (which would
            // fool the server into thinking a fresh position was received while
            // the map pin still shows the old stationary location).
            boolean usedStaleFallback = false;
            if (location == null && forceFreshFix) {
                // No fresh fix was obtained (e.g. GPS chip reacquiring while moving).
                // Upload the best stale reading so the server has *something*, but
                // preserve its original timestamp so the server knows it is stale
                // and keeps "Waiting for updated GPS data..." visible.
                location = chooseBestLocation(parallelResult.rawGps, parallelResult.rawNetwork, lastKnownAny);
                if (location != null) {
                    usedStaleFallback = true;
                    long ageMs = Math.max(0, now - location.getTime());
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "LocationWorker: using stale fallback location, age=" + (ageMs / 1000) + "s"
                                    + " — coordinates are from before movement started");
                }
            }

            Location immediateSent = immediateSentRef.get();
            if (location == null) {
                if (forceFreshFix) {
                    if (immediateSent != null) {
                        RemoteLogger.log(context, Const.LOG_INFO,
                                "LocationWorker: no fresh fix obtained — cached fix was already"
                                + " uploaded by the instant-response path");
                    } else {
                        RemoteLogger.log(context, Const.LOG_WARN,
                                "LocationWorker: urgent refresh could not find any location to upload");
                    }
                }
                return Result.success();
            }

            if (immediateSent != null
                    && location.getTime() == immediateSent.getTime()
                    && location.getLatitude() == immediateSent.getLatitude()
                    && location.getLongitude() == immediateSent.getLongitude()) {
                RemoteLogger.log(context, Const.LOG_INFO,
                        "LocationWorker: final result is identical to the instant-response upload"
                        + " — skipping duplicate");
                return Result.success();
            }

            long locationAgeS = (System.currentTimeMillis() - location.getTime()) / 1000;
            if (locationAgeS > 3600) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationWorker: implausibly old fix (" + locationAgeS / 3600
                                + " h) — possible system clock skew on first boot");
            }
            freshUploadStarted.set(true);
            String locationSource = usedStaleFallback ? "staleFallback"
                    : (chosenFresh != null ? "handlerThread" : "lastKnown");
            RemoteLogger.log(context, Const.LOG_INFO, "LocationWorker: success using "
                    + location.getProvider()
                    + " (age=" + locationAgeS + "s"
                    + ", accuracy=" + (location.hasAccuracy() ? location.getAccuracy() + "m" : "unknown")
                    + ", staleFallback=" + usedStaleFallback
                    + ", source=" + locationSource + ")");

            return performUpload(context, location, forceFreshFix, usedStaleFallback);
        } catch (SecurityException e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: SecurityException — permission revoked mid-session: "
                            + e.getMessage());
            return Result.failure();
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "LocationWorker failed: " + e.getMessage());
            return Result.success();
        } finally {
            releaseWakeLock(wakeLock);
            LocationDiag.timeline(context, reqId, "captureAndUpload:complete");
        }
    }

    private static PowerManager.WakeLock acquireWakeLock(Context context) {
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationWorker: PowerManager null — CPU may sleep during GPS wait");
                return null;
            }
            // Tag format: "packageName:tag" per Android docs.
            // Using a short alias like "hmdm:" misattributes wake lock in battery stats.
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    context.getPackageName() + ":LocationWorker");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: wake lock acquired");
            return wakeLock;
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: failed to acquire wake lock: " + e.getMessage());
            return null;
        }
    }

    private static void releaseWakeLock(PowerManager.WakeLock wakeLock) {
        if (wakeLock == null) {
            return;
        }
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private static ParallelProviderResult requestProvidersInParallel(
            Context context,
            LocationManager locationManager,
            boolean allowGps,
            boolean allowNetwork,
            long maxFixAgeMs,
            long fixWaitSeconds,
            boolean urgentFastReturn,
            StopChecker stopChecker,
            String reqId) {
        ParallelProviderResult result = new ParallelProviderResult();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Location> gpsFuture = null;
        Future<Location> networkFuture = null;

        try {
            if (allowGps) {
                gpsFuture = executor.submit(() -> {
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWorker: GPS request started (timeout=" + fixWaitSeconds + "s)");
                    return tryRequestLiveUpdate(context, locationManager,
                            LocationManager.GPS_PROVIDER, fixWaitSeconds, reqId);
                });
            }

            if (allowNetwork) {
                if (!OemCompat.isGmsAvailable(context)) {
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWorker: GMS absent — NETWORK_PROVIDER skipped"
                                    + " (Google NLP unavailable, would cause a dead-wait)");
                } else {
                    networkFuture = executor.submit(() -> {
                        RemoteLogger.log(context, Const.LOG_INFO,
                                "LocationWorker: Network request started (timeout="
                                + fixWaitSeconds + "s)");
                        return tryRequestLiveUpdate(context, locationManager,
                                LocationManager.NETWORK_PROVIDER, fixWaitSeconds, reqId);
                    });
                }
            }

            // +2s so the outer loop never expires before the inner provider latches finish.
            long outerWaitSeconds = fixWaitSeconds + 2;
            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(outerWaitSeconds);
            boolean gpsPending = gpsFuture != null;
            boolean networkPending = networkFuture != null;

            while ((gpsPending || networkPending) && System.nanoTime() < deadlineNs) {
                if (stopChecker.isStopped()) break;

                if (gpsPending && gpsFuture.isDone()) {
                    result.rawGps = getFutureResult(context, gpsFuture, 0);
                    gpsPending = false;
                    LocationDiag.timeline(context, reqId,
                            "fallback:gpsResolved(fix=" + (result.rawGps != null) + ")");
                    if (isLocationFresh(result.rawGps, maxFixAgeMs) && result.firstFresh == null) {
                        result.firstFresh = result.rawGps;
                    }
                }

                if (networkPending && networkFuture.isDone()) {
                    result.rawNetwork = getFutureResult(context, networkFuture, 0);
                    networkPending = false;
                    LocationDiag.timeline(context, reqId,
                            "fallback:networkResolved(fix=" + (result.rawNetwork != null) + ")");
                    if (isLocationFresh(result.rawNetwork, maxFixAgeMs) && result.firstFresh == null) {
                        result.firstFresh = result.rawNetwork;
                    }
                }

                // Urgent captures return the FIRST fresh fix immediately — latency beats GPS
                // accuracy when an admin is waiting on "Get Latest GPS". Periodic captures keep
                // the old behavior: let both providers finish so GPS accuracy wins.
                if (urgentFastReturn && result.firstFresh != null) {
                    LocationDiag.timeline(context, reqId,
                            "fallback:urgentFastReturn(provider=" + result.firstFresh.getProvider() + ")");
                    break;
                }

                if (!gpsPending && !networkPending) {
                    break;
                }

                if (gpsPending || networkPending) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (gpsPending || networkPending) {
                LocationDiag.timeline(context, reqId, "fallback:outerWaitDeadlineHit(gpsPending="
                        + gpsPending + ",networkPending=" + networkPending + ")");
            }
            if (result.rawGps == null && gpsFuture != null) {
                result.rawGps = getFutureResult(context, gpsFuture, 0);
            }
            if (result.rawNetwork == null && networkFuture != null) {
                result.rawNetwork = getFutureResult(context, networkFuture, 0);
            }

            result.gps = isLocationFresh(result.rawGps, maxFixAgeMs) ? result.rawGps : null;
            result.network = isLocationFresh(result.rawNetwork, maxFixAgeMs) ? result.rawNetwork : null;
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: provider results gps=" + (result.rawGps != null)
                            + " (fresh=" + (result.gps != null) + ")"
                            + ", network=" + (result.rawNetwork != null)
                            + " (fresh=" + (result.network != null) + ")");
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: provider request failed: " + e.getMessage());
        } finally {
            if (gpsFuture != null && !gpsFuture.isDone()) {
                gpsFuture.cancel(true);
            }
            if (networkFuture != null && !networkFuture.isDone()) {
                networkFuture.cancel(true);
            }
            executor.shutdownNow();
        }

        return result;
    }

    private static Location getFutureResult(Context context, Future<Location> future,
                                            long timeoutSeconds) {
        if (future == null) {
            return null;
        }
        try {
            if (timeoutSeconds <= 0) {
                return future.isDone() ? future.get() : null;
            }
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.CancellationException ignored) {
            return null; // expected when future.cancel(true) is called
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: provider future threw: " + e.getCause());
            return null;
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: getFutureResult failed: " + e.getMessage());
            return null;
        }
    }

    private static Location getBestLastKnownLocation(Context context,
                                                      LocationManager locationManager) {
        Location gps = null;
        Location network = null;
        Location passive = null;
        try {
            gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: getLastKnownLocation(GPS) denied — "
                            + "ACCESS_BACKGROUND_LOCATION may be missing: " + e.getMessage());
        } catch (Exception ignored) {
        }
        try {
            network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: getLastKnownLocation(NETWORK) denied: " + e.getMessage());
        } catch (Exception ignored) {
        }
        try {
            passive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
        } catch (SecurityException e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: getLastKnownLocation(PASSIVE) denied: " + e.getMessage());
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

    /**
     * Uses {@link LocationManager#getCurrentLocation} (API 34+) to request a single fresh
     * fix. This bypasses HandlerThread entirely — the system service uses its own internal
     * worker thread, so Realme/Xiaomi/Vivo OEM process managers cannot freeze delivery.
     *
     * On API 34+ this is the sole path used by tryRequestLiveUpdate (the HandlerThread
     * fallback is only for API < 34). The CancellationSignal is auto-cancelled after
     * timeoutSeconds to avoid leaking the GPS request on the system side.
     *
     * The consumer only counts down the latch on a VALID fix. If the consumer is called
     * with null (e.g., because Realme's LocationManager immediately refuses to start a
     * fresh fix when batteryOptExempt=false), the latch waits the full timeout. During
     * that window we also attempt a {@link LocationManager#requestLocationUpdates} on the
     * main looper as a rescue path — some OEMs suppress getCurrentLocation but still
     * deliver through a live listener on the UI thread.
     */
    private static Location tryGetCurrentLocation(
            Context context,
            LocationManager locationManager,
            String provider,
            long timeoutSeconds,
            String reqId) {
        if (Build.VERSION.SDK_INT < 34) return null;
        if (!locationManager.isProviderEnabled(provider)) return null;

        final CountDownLatch latch = new CountDownLatch(1);
        final Location[] result = new Location[1];
        final CancellationSignal cancellationSignal =
                LocationDiag.wrapWithCancelLogging(context, reqId, "getCurrentLocation:" + provider);
        final Handler cancelHandler = new Handler(Looper.getMainLooper());

        // Auto-cancel after timeout so the system doesn't keep the GPS radio alive
        // for a stale request if the caller has moved on.
        cancelHandler.postDelayed(cancellationSignal::cancel,
                TimeUnit.SECONDS.toMillis(timeoutSeconds));

        RemoteLogger.log(context, Const.LOG_INFO,
                "LocationWorker: getCurrentLocation(" + provider + ") started (API 34+)");

        try {
            // Phase 1: getCurrentLocation(provider, ...) — fast one-shot.
            // The consumer only counts down on VALID fix so we can fall through to
            // Phase 2 if the system returns null immediately (Realme battery opt).
            locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    context.getMainExecutor(),
                    location -> {
                        if (location == null) {
                            RemoteLogger.log(context, Const.LOG_INFO,
                                    "LocationDiag: getCurrentLocation(" + provider
                                            + ") consumer invoked with NULL (callback fired, no fix)");
                            return;
                        }
                        if (location.getLatitude() == 0.0 && location.getLongitude() == 0.0) {
                            RemoteLogger.log(context, Const.LOG_INFO,
                                    "LocationDiag: getCurrentLocation(" + provider
                                            + ") consumer invoked with 0,0 (uninitialized fix)");
                            return;
                        }
                        RemoteLogger.log(context, Const.LOG_INFO,
                                "LocationDiag: getCurrentLocation(" + provider
                                        + ") consumer invoked with VALID fix");
                        result[0] = location;
                        latch.countDown();
                    }
            );

            // Phase 2: also register a live listener on the main looper as a backup.
            // Some OEMs (Realme) suppress getCurrentLocation() but may still deliver
            // through a live requestLocationUpdates() listener on the UI thread.
            Handler mainHandler = new Handler(Looper.getMainLooper());
            LocationListener backupListener = location -> {
                if (location == null
                        || (location.getLatitude() == 0.0 && location.getLongitude() == 0.0)) {
                    return;
                }
                RemoteLogger.log(context, Const.LOG_INFO,
                        "LocationDiag: backupListener(" + provider + ") fired with VALID fix"
                                + " (main-looper rescue path, not getCurrentLocation)");
                result[0] = location;
                latch.countDown();
            };
            mainHandler.post(() -> {
                try {
                    locationManager.requestLocationUpdates(
                            provider, 0L, 0f, backupListener, Looper.getMainLooper());
                } catch (Exception ignored) {}
            });

            boolean obtained = latch.await(timeoutSeconds, TimeUnit.SECONDS);

            // Clean up both paths.
            cancellationSignal.cancel();
            cancelHandler.removeCallbacksAndMessages(null);
            mainHandler.post(() -> {
                try { locationManager.removeUpdates(backupListener); } catch (Exception ignored) {}
            });

            if (result[0] != null) {
                RemoteLogger.log(context, Const.LOG_INFO,
                        "LocationWorker: getCurrentLocation(" + provider + ") success"
                        + " (age=" + ((System.currentTimeMillis() - result[0].getTime()) / 1000) + "s"
                        + ", accuracy=" + (result[0].hasAccuracy()
                                ? result[0].getAccuracy() + "m" : "unknown") + ")");
            } else {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationWorker: getCurrentLocation(" + provider + ") returned no fix"
                        + " (timeout=" + timeoutSeconds + "s)");
            }

            return result[0];
        } catch (Exception e) {
            // Ensure cleanup on any unexpected error.
            cancellationSignal.cancel();
            cancelHandler.removeCallbacksAndMessages(null);
            if (e instanceof InterruptedException) {
                // Preserve interrupt status — catching InterruptedException clears it, and the
                // caller (tryRequestLiveUpdate) needs to see it via checkInterruptGate() to know
                // this fallback chain was cancelled rather than genuinely timed out.
                Thread.currentThread().interrupt();
            }
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: getCurrentLocation(" + provider + ") failed: "
                    + e.getClass().getSimpleName() + " — " + e.getMessage());
            return null;
        }
    }

    /**
     * Registers a one-shot LocationListener on the MAIN looper as a last-resort fallback
     * for OEM devices where HandlerThread loopers are frozen by the process manager
     * (Realme AutoDroid, Xiaomi MIUI, Vivo OriginOS).
     *
     * The main thread's looper is the UI-thread looper — the OEM cannot freeze it without
     * making the entire device unresponsive. This is only used as a rescue path when the
     * primary getCurrentLocation() / HandlerThread approaches fail.
     */
    private static Location tryRequestOnMainLooper(
            Context context,
            LocationManager locationManager,
            String provider,
            long timeoutSeconds,
            String reqId) {
        if (!locationManager.isProviderEnabled(provider)) return null;
        if (Looper.myLooper() == Looper.getMainLooper()) return null; // already on main — avoid deadlock

        Handler mainHandler = new Handler(Looper.getMainLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        final Location[] result = new Location[1];
        final LocationListener[] listenerRef = new LocationListener[1];

        try {
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: main-looper fallback for " + provider + " started reqId=" + reqId);

            LocationListener listener = location -> {
                if (location == null
                        || (location.getLatitude() == 0.0 && location.getLongitude() == 0.0)) return;
                result[0] = location;
                latch.countDown();
            };
            listenerRef[0] = listener;

            // Run requestLocationUpdates on the main thread to attach the listener
            // to the main looper.
            mainHandler.post(() -> {
                try {
                    locationManager.requestLocationUpdates(
                            provider, 0L, 0f, listener, Looper.getMainLooper());
                } catch (Exception ignored) {}
            });

            latch.await(timeoutSeconds, TimeUnit.SECONDS);

            // Clean up regardless of success.
            mainHandler.post(() -> {
                try {
                    locationManager.removeUpdates(listener);
                } catch (Exception ignored) {}
            });

            if (result[0] != null) {
                RemoteLogger.log(context, Const.LOG_INFO,
                        "LocationWorker: main-looper fallback success for " + provider);
            }
            return result[0];
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: main-looper fallback for " + provider + " failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Attempts to get the current location via Google Play Services'
     * {@link FusedLocationProviderClient#getCurrentLocation(int, CancellationToken)}.
     *
     * This is used as a fallback when raw {@link LocationManager} APIs are suppressed by
     * OEM battery optimization (Realme ColorOS, Xiaomi MIUI). GMS runs as a
     * system-privileged process with its own location stack — Realme cannot suppress it.
     *
     * No Google account or sign-in is required. Only needs GMS APK installed, which is
     * the case on any device that passes {@link OemCompat#isGmsAvailable(Context)}.
     */
    private static Location tryFusedLocation(Context context, long timeoutSeconds, String reqId) {
        return tryFusedLocation(context, timeoutSeconds, reqId, null,
                Priority.PRIORITY_HIGH_ACCURACY, TimeUnit.SECONDS.toMillis(60), "fusedHigh");
    }

    /**
     * Runs a lightweight balanced-power (WiFi/cell, no GNSS wait) fused request. Used by the
     * FGS proactive cache refresher to keep the cached position under 10 minutes old on devices
     * where the app's own GPS callbacks are suppressed. Must NOT be called on the main thread.
     */
    public static Location tryFusedBalancedCurrentLocation(Context context, long timeoutSeconds) {
        CancellationTokenSource cancelSource = new CancellationTokenSource();
        try {
            return tryFusedLocation(context, timeoutSeconds, "proactiveRefresh",
                    cancelSource.getToken(), Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    TimeUnit.MINUTES.toMillis(2), "fusedBalanced");
        } finally {
            cancelSource.cancel();
        }
    }

    private static Location tryFusedLocation(Context context, long timeoutSeconds, String reqId,
                                              CancellationToken cancellationToken, int priority,
                                              long maxUpdateAgeMs, String label) {
        if (!OemCompat.isGmsAvailable(context)) return null;

        try {
            FusedLocationProviderClient fusedClient =
                    LocationServices.getFusedLocationProviderClient(context);

            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: FusedLocationProvider(" + label + ") started reqId=" + reqId);

            // maxUpdateAge lets GMS answer instantly from a fix it already has (its cache is fed
            // by every app on the device), instead of always waiting for a brand-new computation.
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                    .setPriority(priority)
                    .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                    .setMaxUpdateAgeMillis(maxUpdateAgeMs)
                    .setDurationMillis(TimeUnit.SECONDS.toMillis(timeoutSeconds))
                    .build();
            com.google.android.gms.tasks.Task<Location> task = fusedClient.getCurrentLocation(
                    request, cancellationToken);

            Location location = Tasks.await(task, timeoutSeconds, TimeUnit.SECONDS);

            if (location != null
                    && location.getLatitude() != 0.0
                    && location.getLongitude() != 0.0) {
                RemoteLogger.log(context, Const.LOG_INFO,
                        "LocationWorker: FusedLocationProvider(" + label + ") success"
                        + " (provider=" + location.getProvider()
                        + ", accuracy=" + (location.hasAccuracy()
                                ? location.getAccuracy() + "m" : "unknown") + ")");
                return location;
            }

            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: FusedLocationProvider(" + label + ") returned no fix"
                    + " (timeout=" + timeoutSeconds + "s)");
            return null;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            StringBuilder sb = new StringBuilder("LocationDiag: FusedLocationProvider(")
                    .append(label).append(") failed reqId=")
                    .append(reqId).append(": ")
                    .append(e.getClass().getName()).append(" — ").append(e.getMessage());
            if (cause instanceof ApiException) {
                ApiException apiEx = (ApiException) cause;
                sb.append(" | ApiException statusCode=").append(apiEx.getStatusCode())
                        .append(" status=").append(apiEx.getStatus());
            } else if (cause != e) {
                sb.append(" | cause=").append(cause.getClass().getName())
                        .append(" — ").append(cause.getMessage());
            }
            sb.append(" | stackTrace=").append(android.util.Log.getStackTraceString(e));
            RemoteLogger.log(context, Const.LOG_WARN, sb.toString());
            return null;
        }
    }

    /**
     * Races {@link #tryGetCurrentLocation} against {@link #tryFusedLocation} instead of running
     * them sequentially, and returns whichever produces a valid fix first.
     *
     * Evidence from field logs (Realme RMX3998, Android 15/ColorOS 15): raw LocationManager
     * behaves in a strictly bimodal way on this device class — it either rejects a request
     * within milliseconds, or delivers absolutely nothing (not even a null callback) for the
     * ENTIRE timeout, especially while the device is in Doze. FusedLocationProvider, in
     * contrast, has consistently resolved (success or failure) in under ~5 seconds in every
     * capture observed. Under the old sequential order (raw for the full timeout, THEN try
     * Fused), a request that only Fused could ever satisfy paid the full raw timeout — up to
     * 45s — before even attempting the one thing that worked, turning a sub-5-second answer
     * into a 45-90+ second one.
     *
     * No extra "grace window" is used when Fused wins first: every capture analyzed so far
     * shows raw LocationManager either failing near-instantly or staying completely silent for
     * the whole timeout — never a case of "raw would have delivered a better fix a few seconds
     * after Fused already answered". If that pattern is ever observed, this is the place to add
     * a bounded grace wait (re-checking {@link #isBetterLocation}) before finalizing — but doing
     * that unconditionally today would tax every fast Fused success for a benefit never seen in
     * practice, working against "efficient" for no proven gain in "accurate".
     *
     * If raw DOES win the race with a genuine fix, it is taken immediately and preferred — real
     * GPS is at least as accurate as, usually more accurate than, a Fused/network-derived fix.
     */
    private static Location tryGetCurrentLocationRacingFused(
            Context context, LocationManager locationManager, String provider,
            long timeoutSeconds, String reqId) {
        ExecutorService racer = Executors.newFixedThreadPool(3);
        CancellationTokenSource fusedCancelSource = new CancellationTokenSource();
        CancellationTokenSource balancedCancelSource = new CancellationTokenSource();
        Future<Location> rawFuture = null;
        Future<Location> fusedFuture = null;
        Future<Location> balancedFuture = null;
        try {
            rawFuture = racer.submit(() ->
                    tryGetCurrentLocation(context, locationManager, provider, timeoutSeconds, reqId));
            fusedFuture = racer.submit(() ->
                    tryFusedLocation(context, timeoutSeconds, reqId, fusedCancelSource.getToken(),
                            Priority.PRIORITY_HIGH_ACCURACY,
                            TimeUnit.SECONDS.toMillis(60), "fusedHigh"));
            // Third racer: balanced power = WiFi/cell only, no GNSS wait. Indoors or under OEM
            // GNSS suppression this typically resolves in 2-10s while the other two starve.
            // maxUpdateAge 30s keeps it from "winning" with an old cached fix — the instant
            // -response path already reported the cache; this racer must contribute freshness.
            balancedFuture = racer.submit(() ->
                    tryFusedLocation(context, timeoutSeconds, reqId, balancedCancelSource.getToken(),
                            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                            TimeUnit.SECONDS.toMillis(30), "fusedBalanced"));

            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            boolean rawPending = true;
            boolean fusedPending = true;
            boolean balancedPending = true;
            Location rawResult = null;
            Location fusedResult = null;
            Location balancedResult = null;

            while ((rawPending || fusedPending || balancedPending)
                    && System.nanoTime() < deadlineNs) {
                if (LocationDiag.checkInterruptGate(context, reqId, "race:" + provider)) {
                    return null;
                }

                if (rawPending && rawFuture.isDone()) {
                    rawResult = getFutureResult(context, rawFuture, 0);
                    rawPending = false;
                    if (rawResult != null) {
                        LocationDiag.timeline(context, reqId, "race:" + provider + ":rawWon");
                        break;
                    }
                }
                if (fusedPending && fusedFuture.isDone()) {
                    fusedResult = getFutureResult(context, fusedFuture, 0);
                    fusedPending = false;
                    if (fusedResult != null) {
                        LocationDiag.timeline(context, reqId, "race:" + provider + ":fusedWon");
                        break;
                    }
                }
                if (balancedPending && balancedFuture.isDone()) {
                    balancedResult = getFutureResult(context, balancedFuture, 0);
                    balancedPending = false;
                    if (balancedResult != null) {
                        LocationDiag.timeline(context, reqId, "race:" + provider + ":balancedWon");
                        break;
                    }
                }
                if (!rawPending && !fusedPending && !balancedPending) {
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Neither loop-exit branch above re-checked a future that finished during the last
            // sleep — pick up any last-moment result non-blockingly before giving up.
            if (rawResult == null && rawFuture.isDone()) {
                rawResult = getFutureResult(context, rawFuture, 0);
            }
            if (fusedResult == null && fusedFuture.isDone()) {
                fusedResult = getFutureResult(context, fusedFuture, 0);
            }
            if (balancedResult == null && balancedFuture.isDone()) {
                balancedResult = getFutureResult(context, balancedFuture, 0);
            }

            Location winner = rawResult != null ? rawResult
                    : (fusedResult != null ? fusedResult : balancedResult);
            String winnerLabel = winner == null ? "none"
                    : (winner == rawResult ? "raw"
                        : (winner == fusedResult ? "fusedHigh" : "fusedBalanced"));
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationDiag: race:" + provider + " reqId=" + reqId
                            + " rawResult=" + (rawResult != null)
                            + " fusedResult=" + (fusedResult != null)
                            + " balancedResult=" + (balancedResult != null)
                            + " winner=" + winnerLabel);
            return winner;
        } finally {
            if (rawFuture != null && !rawFuture.isDone()) {
                rawFuture.cancel(true);
            }
            // Cancel via GMS's own tokens (stops the underlying requests), not just Thread
            // interruption — Tasks.await() alone doesn't stop the Play Services side.
            if (fusedFuture != null && !fusedFuture.isDone()) {
                fusedCancelSource.cancel();
                fusedFuture.cancel(true);
            }
            if (balancedFuture != null && !balancedFuture.isDone()) {
                balancedCancelSource.cancel();
                balancedFuture.cancel(true);
            }
            racer.shutdownNow();
        }
    }

    private static Location tryRequestLiveUpdate(Context context,
                                                  LocationManager locationManager,
                                                  String provider,
                                                  long timeoutSeconds,
                                                  String reqId) {
        if (!locationManager.isProviderEnabled(provider)) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: provider '" + provider
                            + "' is disabled (device-only mode / accuracy toggle / no GMS)");
            return null;
        }

        // API 34+: use getCurrentLocation() as the primary path (system service internal
        // thread, no HandlerThread freezing). On the GPS provider, RACE it against
        // FusedLocationProvider instead of waiting out the full raw timeout first — see
        // tryGetCurrentLocationRacingFused() for why. FusedLocationProvider already combines
        // GPS + Network internally, so we only race/fall back on the GPS provider path to
        // avoid parallel duplicate calls against NETWORK_PROVIDER too.
        if (Build.VERSION.SDK_INT >= 34) {
            if (LocationManager.GPS_PROVIDER.equals(provider) && OemCompat.isGmsAvailable(context)) {
                return tryGetCurrentLocationRacingFused(context, locationManager, provider, timeoutSeconds, reqId);
            }
            return tryGetCurrentLocation(context, locationManager, provider, timeoutSeconds, reqId);
        }

        // API < 34: classic HandlerThread-based requestLocationUpdates.
        HandlerThread handlerThread = new HandlerThread("gps-update-" + provider);
        handlerThread.start();
        RemoteLogger.log(context, Const.LOG_INFO,
                "LocationDiag: HandlerThread(" + provider + ") stage=requestStart reqId=" + reqId
                        + " isAlive=" + handlerThread.isAlive());
        LocationListener listener = null;

        try {
            final long requestStartedAt = System.currentTimeMillis();
            final Object lock = new Object();
            final Location[] bestObserved = new Location[1];
            final Location[] bestFresh = new Location[1];
            final CountDownLatch latch = new CountDownLatch(1);

            listener = location -> {
                if (location == null || (location.getLatitude() == 0 && location.getLongitude() == 0)) {
                    return;
                }

                synchronized (lock) {
                    if (bestObserved[0] == null || isBetterLocation(location, bestObserved[0])) {
                        bestObserved[0] = new Location(location);
                    }
                    if (location.getTime() >= requestStartedAt - LIVE_UPDATE_FRESHNESS_GRACE_MS
                            && (bestFresh[0] == null || isBetterLocation(location, bestFresh[0]))) {
                        bestFresh[0] = new Location(location);
                        latch.countDown();
                    }
                }
            };

            locationManager.requestLocationUpdates(provider, 0L, 0f, listener,
                    handlerThread.getLooper());

            try {
                latch.await(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            boolean queueIdle = false;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && handlerThread.isAlive()) {
                    queueIdle = handlerThread.getLooper().getQueue().isIdle();
                }
            } catch (Exception ignored) {
            }
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationDiag: HandlerThread(" + provider + ") stage=timeoutOrFired isAlive="
                            + handlerThread.isAlive() + " queueIdle=" + queueIdle
                            + " fixReceived=" + (bestFresh[0] != null || bestObserved[0] != null));

            if (!handlerThread.isAlive()) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationWorker: HandlerThread for " + provider
                                + " was killed externally — OEM process manager ("
                                + Build.MANUFACTURER + " API " + Build.VERSION.SDK_INT + ")");
            }

            synchronized (lock) {
                if (bestFresh[0] != null) {
                    return bestFresh[0];
                }
                if (bestObserved[0] != null) {
                    return bestObserved[0];
                }
            }

            if (LocationDiag.checkInterruptGate(context, reqId, "beforeMainLooperRescue:" + provider)) {
                return null;
            }

            // Last resort: main-looper-based listener. On Realme/Xiaomi/Vivo the HandlerThread
            // looper may be frozen, but the main thread looper always runs.
            Location mainLoc = tryRequestOnMainLooper(context, locationManager, provider, timeoutSeconds, reqId);
            if (mainLoc != null) return mainLoc;

            if (LocationDiag.checkInterruptGate(context, reqId, "beforeFusedFallback:" + provider)) {
                return null;
            }

            // Final fallback: FusedLocationProviderClient (GMS). Bypasses OEM LocationManager
            // suppression because GMS runs as a system-privileged process.
            // Only on GPS provider — fused already combines all providers internally.
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                return tryFusedLocation(context, timeoutSeconds, reqId);
            }
            return null;
        } catch (SecurityException e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: SecurityException on " + provider
                            + " — permission revoked mid-session: " + e.getMessage());
            return null;
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: exception in tryRequestLiveUpdate(" + provider + "): "
                            + e.getMessage());
            return null;
        } finally {
            if (listener != null) {
                try {
                    locationManager.removeUpdates(listener);
                } catch (Exception ignored) {
                }
            }
            try {
                handlerThread.quitSafely();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isLocationFresh(Location location, long maxAgeMs) {
        if (location == null) {
            return false;
        }
        // Lat=0/Lon=0 indicates an uninitialized GPS fix on all real-world deployments.
        // Legitimate Gulf of Guinea coordinates are rejected — acceptable trade-off for
        // land/vehicle MDM.
        if (location.getLatitude() == 0 && location.getLongitude() == 0) {
            return false;
        }

        long ageMs = System.currentTimeMillis() - location.getTime();
        if (ageMs < 0) {
            ageMs = 0; // GPS clock is highly accurate; negative age = chip clock skew on first boot
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

    // ---------------------------------------------------------------------------
    // SharedPrefs cache — populated by LocationForegroundService on every fix.
    // Read here to skip the 45 s HandlerThread wait on OEMs with frozen loopers.
    // Uses longBitsToDouble for full precision (the writer uses doubleToRawLongBits).
    // ---------------------------------------------------------------------------

    static Location readFixFromSharedPrefs(Context context, long maxAgeMs) {
        SharedPreferences prefs = context.getSharedPreferences(
                "mdm_location_cache", Context.MODE_PRIVATE);
        long fixTime = prefs.getLong("last_fix_time", 0);
        if (fixTime == 0) return null;

        long ageMs = System.currentTimeMillis() - fixTime;
        if (ageMs > maxAgeMs) return null;

        double lat = Double.longBitsToDouble(prefs.getLong("last_fix_lat_bits", 0));
        double lng = Double.longBitsToDouble(prefs.getLong("last_fix_lng_bits", 0));
        if (lat == 0.0 && lng == 0.0) return null;

        float accuracy = prefs.getFloat("last_fix_accuracy", -1f);
        if (accuracy > 0 && accuracy > MAX_FIX_ACCURACY_METERS) return null;

        Location location = new Location(prefs.getString("last_fix_provider", "cache"));
        location.setLatitude(lat);
        location.setLongitude(lng);
        location.setTime(fixTime);
        if (accuracy > 0) location.setAccuracy(accuracy);
        return location;
    }

    /**
     * GMS FusedLocationProvider's passive cache — populated by Google services and every other
     * app on the device, so it usually has a fix even when LocationManager's per-provider cache
     * is empty (cleared on reboot / location toggle) and our own FGS never got a callback.
     * Must NOT be called on the main thread (Tasks.await throws there).
     */
    public static Location tryFusedLastLocation(Context context) {
        if (!OemCompat.isGmsAvailable(context)) return null;
        try {
            FusedLocationProviderClient fusedClient =
                    LocationServices.getFusedLocationProviderClient(context);
            Location location = Tasks.await(fusedClient.getLastLocation(), 3, TimeUnit.SECONDS);
            if (location == null
                    || (location.getLatitude() == 0.0 && location.getLongitude() == 0.0)) {
                return null;
            }
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: fused getLastLocation hit (age="
                    + ((System.currentTimeMillis() - location.getTime()) / 1000) + "s"
                    + ", accuracy=" + (location.hasAccuracy()
                            ? location.getAccuracy() + "m" : "unknown") + ")");
            return location;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: fused getLastLocation failed: "
                    + e.getClass().getSimpleName() + " — " + e.getMessage());
            return null;
        }
    }

    /** Newest fix waiting in the offline SQLite queue, or null. Survives process restarts. */
    private static Location readLatestQueuedDbFix(Context context) {
        try {
            DatabaseHelper helper = DatabaseHelper.instance(context);
            if (helper == null) return null;
            java.util.List<LocationTable.Location> rows =
                    LocationTable.select(helper.getReadableDatabase(), 1);
            if (rows.isEmpty()) return null;
            LocationTable.Location row = rows.get(0);
            if (row.getLat() == 0.0 && row.getLon() == 0.0) return null;
            Location location = new Location("offlineQueue");
            location.setLatitude(row.getLat());
            location.setLongitude(row.getLon());
            location.setTime(row.getTs());
            return location;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Best cached location from every source we have, with no age limit: LocationManager
     * last-known (GPS/Network/Passive), GMS Fused cache, FGS prefs cache, offline SQLite queue.
     * Used by the urgent instant-response path and the FGS 30s heartbeat so "send the last
     * recorded position" works even when the app's own capture pipeline is fully suppressed.
     * Must NOT be called on the main thread.
     */
    public static Location getBestCachedLocationAnyAge(Context context,
                                                       LocationManager locationManager) {
        Location lastKnown = locationManager != null
                ? getBestLastKnownLocation(context, locationManager) : null;
        return chooseBestLocation(lastKnown,
                tryFusedLastLocation(context),
                readFixFromSharedPrefs(context, Long.MAX_VALUE),
                readLatestQueuedDbFix(context));
    }

    // ---------------------------------------------------------------------------
    // Upload helper — extracted so both the cache fast-path and the normal path
    // share identical DB + upload logic without duplicating the UPLOAD_LOCK block.
    // ---------------------------------------------------------------------------

    private static Result performUpload(Context context, Location location,
                                        boolean forceFreshFix, boolean usedStaleFallback) {
        synchronized (UPLOAD_LOCK) {
            DatabaseHelper helper = DatabaseHelper.instance(context);
            if (helper == null) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationWorker: DatabaseHelper unavailable — scheduling retry");
                return Result.retry();
            }

            LocationTable.Location tableLocation = new LocationTable.Location(location);

            if (forceFreshFix && !usedStaleFallback) {
                tableLocation.setTs(System.currentTimeMillis());
            }

            if (forceFreshFix && LocationUploader.sendUrgentLocation(context, tableLocation)) {
                RemoteLogger.log(context, Const.LOG_INFO,
                        "LocationWorker: urgent location uploaded successfully"
                        + (usedStaleFallback ? " (stale fallback — server will keep polling)" : ""));
                return Result.success();
            }

            try {
                LocationTable.insert(helper.getWritableDatabase(), tableLocation);
            } catch (Exception e) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationWorker: DB insert failed: " + e.getMessage());
                return Result.retry();
            }

            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: queued location for regular upload path");
            LocationUploader.sendLocations(context);
        }
        return Result.success();
    }

    private static class ParallelProviderResult {
        private Location rawGps;
        private Location rawNetwork;
        private Location gps;
        private Location network;
        private Location firstFresh;
    }
}
