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

import com.brother.pharmach.mdm.launcher.util.OemCompat;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class LocationWorker extends Worker {

    public static final int FIRE_PERIOD_MINS = 15;

    private static final String WORK_TAG_PERIODIC = "com.brother.pharmach.mdm.launcher.WORK_TAG_LOCATION_PERIODIC";
    private static final String WORK_TAG_ONE_SHOT = "com.brother.pharmach.mdm.launcher.WORK_TAG_LOCATION_ONE_SHOT";
    // GPS reacquisition while moving (e.g. low-power mode exit) can take 30-90 s.
    // 45 s gives the chip enough time without blocking too long for periodic runs.
    private static final long GPS_FIX_WAIT_SECONDS = 45;
    private static final long NETWORK_FIX_WAIT_SECONDS = 45;
    private static final long URGENT_MAX_FIX_AGE_MS = TimeUnit.MINUTES.toMillis(5);
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
    public static Result runUrgentNow(Context context) {
        return captureAndUpload(context, true, () -> false);
    }

    public static void enqueueUrgentNow(Context context) {
        final Context appContext = context.getApplicationContext();
        if (URGENT_EXECUTOR.getQueue().size() >= 1) {
            RemoteLogger.log(appContext, Const.LOG_WARN,
                    "LocationWorker: urgent queue saturated — oldest queued request replaced by new push");
        }
        URGENT_EXECUTOR.execute(() -> {
            try {
                runUrgentNow(appContext);
            } catch (Exception e) {
                RemoteLogger.log(appContext, Const.LOG_WARN,
                        "LocationWorker: queued urgent capture failed: " + e.getMessage());
            }
        });
    }

    @NonNull
    @Override
    public Result doWork() {
        if (isStopped()) return Result.success();
        // Ensure the FGS is alive — restarts it if the OS killed it since last boot.
        LocationForegroundService.start(context);
        boolean forceFreshFix = getTags().contains(WORK_TAG_ONE_SHOT);
        return captureAndUpload(context, forceFreshFix, this::isStopped);
    }

    @NonNull
    public static Result captureAndUpload(Context context, boolean forceFreshFix,
                                          StopChecker stopChecker) {
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

            if (stopChecker.isStopped()) return Result.success();

            RemoteLogger.log(context, Const.LOG_INFO, "LocationWorker: requesting fresh location updates");

            boolean allowNetwork = fineGranted || coarseGranted;
            ParallelProviderResult parallelResult = requestProvidersInParallel(
                    context,
                    locationManager,
                    fineGranted,
                    allowNetwork,
                    maxFixAgeMs,
                    stopChecker);

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

            if (location == null) {
                if (forceFreshFix) {
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "LocationWorker: urgent refresh could not find any location to upload");
                }
                return Result.success();
            }

            long locationAgeS = (System.currentTimeMillis() - location.getTime()) / 1000;
            if (locationAgeS > 3600) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationWorker: implausibly old fix (" + locationAgeS / 3600
                                + " h) — possible system clock skew on first boot");
            }
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
            StopChecker stopChecker) {
        ParallelProviderResult result = new ParallelProviderResult();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Location> gpsFuture = null;
        Future<Location> networkFuture = null;

        try {
            if (allowGps) {
                gpsFuture = executor.submit(() -> {
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWorker: GPS request started");
                    return tryRequestLiveUpdate(context, locationManager,
                            LocationManager.GPS_PROVIDER, GPS_FIX_WAIT_SECONDS);
                });
            }

            if (allowNetwork) {
                if (!OemCompat.isGmsAvailable(context)) {
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWorker: GMS absent — NETWORK_PROVIDER skipped"
                                    + " (Google NLP unavailable, would cause 45s dead-wait)");
                } else {
                    networkFuture = executor.submit(() -> {
                        RemoteLogger.log(context, Const.LOG_INFO,
                                "LocationWorker: Network request started");
                        return tryRequestLiveUpdate(context, locationManager,
                                LocationManager.NETWORK_PROVIDER, NETWORK_FIX_WAIT_SECONDS);
                    });
                }
            }

            // Use max of both timeouts so future increases to GPS_FIX_WAIT_SECONDS
            // never cause the outer loop to expire before the inner GPS latch finishes.
            long outerWaitSeconds = Math.max(GPS_FIX_WAIT_SECONDS, NETWORK_FIX_WAIT_SECONDS) + 2;
            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(outerWaitSeconds);
            boolean gpsPending = gpsFuture != null;
            boolean networkPending = networkFuture != null;

            while ((gpsPending || networkPending) && System.nanoTime() < deadlineNs) {
                if (stopChecker.isStopped()) break;

                if (gpsPending && gpsFuture.isDone()) {
                    result.rawGps = getFutureResult(context, gpsFuture, 0);
                    gpsPending = false;
                    if (isLocationFresh(result.rawGps, maxFixAgeMs) && result.firstFresh == null) {
                        result.firstFresh = result.rawGps;
                    }
                }

                if (networkPending && networkFuture.isDone()) {
                    result.rawNetwork = getFutureResult(context, networkFuture, 0);
                    networkPending = false;
                    if (isLocationFresh(result.rawNetwork, maxFixAgeMs) && result.firstFresh == null) {
                        result.firstFresh = result.rawNetwork;
                    }
                }

                // Do NOT break on firstFresh alone. Let both providers run so captureAndUpload
                // can prefer GPS accuracy over network speed. Break only when all are done.
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
     * Uses {@link LocationManager#getCurrentLocation(String, CancellationSignal, Executor, java.util.function.Consumer)}
     * (API 34+) to request a single fresh fix. This bypasses HandlerThread entirely — the
     * system service uses its own internal worker thread, so Realme/Xiaomi/Vivo OEM process
     * managers cannot freeze delivery.
     *
     * On API 34+ this is the sole path used by tryRequestLiveUpdate (the HandlerThread
     * fallback is only for API < 34). The CancellationSignal is auto-cancelled after
     * timeoutSeconds to avoid leaking the GPS request on the system side.
     */
    private static Location tryGetCurrentLocation(
            Context context,
            LocationManager locationManager,
            String provider,
            long timeoutSeconds) {
        if (Build.VERSION.SDK_INT < 34) return null;
        if (!locationManager.isProviderEnabled(provider)) return null;

        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final Location[] result = new Location[1];
            final CancellationSignal cancellationSignal = new CancellationSignal();
            final Handler cancelHandler = new Handler(Looper.getMainLooper());

            // Auto-cancel after timeout so the system doesn't keep the GPS radio alive
            // for a stale request if the caller has moved on.
            cancelHandler.postDelayed(cancellationSignal::cancel,
                    TimeUnit.SECONDS.toMillis(timeoutSeconds));

            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: getCurrentLocation(" + provider + ") started (API 34+)");

            locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    context.getMainExecutor(),
                    location -> {
                        if (location != null
                                && location.getLatitude() != 0.0
                                && location.getLongitude() != 0.0) {
                            result[0] = location;
                        }
                        latch.countDown();
                    }
            );

            latch.await(timeoutSeconds, TimeUnit.SECONDS);

            // Cancel the request on the system side and remove our safety-net timeout.
            cancellationSignal.cancel();
            cancelHandler.removeCallbacksAndMessages(null);

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
            long timeoutSeconds) {
        if (!locationManager.isProviderEnabled(provider)) return null;
        if (Looper.myLooper() == Looper.getMainLooper()) return null; // already on main — avoid deadlock

        Handler mainHandler = new Handler(Looper.getMainLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        final Location[] result = new Location[1];
        final LocationListener[] listenerRef = new LocationListener[1];

        try {
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: main-looper fallback for " + provider + " started");

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
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: main-looper fallback for " + provider + " failed: " + e.getMessage());
            return null;
        }
    }

    private static Location tryRequestLiveUpdate(Context context,
                                                  LocationManager locationManager,
                                                  String provider,
                                                  long timeoutSeconds) {
        if (!locationManager.isProviderEnabled(provider)) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: provider '" + provider
                            + "' is disabled (device-only mode / accuracy toggle / no GMS)");
            return null;
        }

        // API 34+: use getCurrentLocation() exclusively. It is the modern replacement for
        // HandlerThread-based requestLocationUpdates in one-shot mode — it uses the system
        // service's internal worker thread, so OEM process managers (Realme/Xiaomi/Vivo)
        // cannot freeze delivery. It also works without ACCESS_BACKGROUND_LOCATION.
        if (Build.VERSION.SDK_INT >= 34) {
            return tryGetCurrentLocation(context, locationManager, provider, timeoutSeconds);
        }

        // API < 34: classic HandlerThread-based requestLocationUpdates.
        HandlerThread handlerThread = new HandlerThread("gps-update-" + provider);
        handlerThread.start();
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

            // Last resort: main-looper-based listener. On Realme/Xiaomi/Vivo the HandlerThread
            // looper may be frozen, but the main thread looper always runs.
            return tryRequestOnMainLooper(context, locationManager, provider, timeoutSeconds);
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
