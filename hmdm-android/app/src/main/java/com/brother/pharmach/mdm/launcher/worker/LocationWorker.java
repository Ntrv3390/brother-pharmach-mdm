package com.brother.pharmach.mdm.launcher.worker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.HandlerThread;
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
            RemoteLogger.log(context, Const.LOG_INFO, "LocationWorker: success using "
                    + location.getProvider()
                    + " (age=" + locationAgeS + "s"
                    + ", accuracy=" + (location.hasAccuracy() ? location.getAccuracy() + "m" : "unknown")
                    + ", staleFallback=" + usedStaleFallback + ")");

            synchronized (UPLOAD_LOCK) {
                DatabaseHelper helper = DatabaseHelper.instance(context);
                if (helper == null) {
                    // Transient DB failure — retry so the location capture is not permanently lost.
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "LocationWorker: DatabaseHelper unavailable — scheduling retry");
                    return Result.retry();
                }

                LocationTable.Location tableLocation = new LocationTable.Location(location);

                if (forceFreshFix && !usedStaleFallback) {
                    // Only stamp ts=now for genuinely fresh locations so that the server
                    // timeline correctly distinguishes a new fix from a recycled old one.
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
                networkFuture = executor.submit(() -> {
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWorker: Network request started");
                    return tryRequestLiveUpdate(context, locationManager,
                            LocationManager.NETWORK_PROVIDER, NETWORK_FIX_WAIT_SECONDS);
                });
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

    private static Location tryRequestLiveUpdate(Context context,
                                                  LocationManager locationManager,
                                                  String provider,
                                                  long timeoutSeconds) {
        // Check provider availability BEFORE starting a HandlerThread — avoids thread churn
        // on devices where NETWORK_PROVIDER is disabled (Device-only mode, Google Location
        // Accuracy toggle off, Huawei non-GMS).
        if (!locationManager.isProviderEnabled(provider)) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: provider '" + provider
                            + "' is disabled (device-only mode / accuracy toggle / no GMS)");
            return null;
        }

        // Start HandlerThread AFTER the provider check so we don't waste a thread on OEM
        // devices with strict background thread limits (Realme AutoDroid, MIUI).
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
                // Restore interrupt status so the calling executor thread can detect cancellation
                // (e.g. Future.cancel(true) from requestProvidersInParallel).
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
                return bestObserved[0];
            }
        } catch (SecurityException e) {
            // Permission revoked between isProviderEnabled() check and requestLocationUpdates().
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
            // removeUpdates MUST be in finally so it runs even when latch.await() throws
            // InterruptedException. Without this, Future.cancel(true) leaves a live listener
            // registered with LocationManager — GPS radio keeps running and LocationManager
            // holds a dead HandlerThread reference causing binder NPEs on some OEMs.
            if (listener != null) {
                try {
                    locationManager.removeUpdates(listener);
                } catch (Exception ignored) {
                }
            }
            // Always shut down the HandlerThread to release resources.
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

    private static class ParallelProviderResult {
        private Location rawGps;
        private Location rawNetwork;
        private Location gps;
        private Location network;
        private Location firstFresh;
    }
}
