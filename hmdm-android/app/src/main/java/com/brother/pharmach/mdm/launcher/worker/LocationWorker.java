package com.brother.pharmach.mdm.launcher.worker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.HandlerThread;
import android.os.PowerManager;

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
import com.brother.pharmach.mdm.launcher.util.LocationUploader;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    // Wake lock must outlast the longest provider wait plus a small buffer.
    private static final long WAKE_LOCK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(120);
    // GPS fix timestamps while moving can lag delivery by up to ~10 s; use 15 s grace.
    private static final long LIVE_UPDATE_FRESHNESS_GRACE_MS = TimeUnit.SECONDS.toMillis(15);
    private static final ExecutorService URGENT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "urgent-gps-queue");
        thread.setDaemon(true);
        return thread;
    });

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
        // Not expedited: avoids foreground-service notification on Android ≤ 11.
        // Truly urgent captures use enqueueUrgentNow() which runs directly in a thread.
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(LocationWorker.class)
                .addTag(Const.WORK_TAG_COMMON)
                .addTag(WORK_TAG_ONE_SHOT)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                WORK_TAG_ONE_SHOT,
                ExistingWorkPolicy.REPLACE,
                request);
    }

    // Executes an urgent GPS refresh immediately in the current process.
    // This bypasses WorkManager scheduling latency and is used by push-triggered refreshes.
    public static Result runUrgentNow(Context context) {
        return captureAndUpload(context, true);
    }

    public static void enqueueUrgentNow(Context context) {
        final Context appContext = context.getApplicationContext();
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
        boolean forceFreshFix = getTags().contains(WORK_TAG_ONE_SHOT);
        return captureAndUpload(context, forceFreshFix);
    }

    @NonNull
    private static Result captureAndUpload(Context context, boolean forceFreshFix) {
        PowerManager.WakeLock wakeLock = null;
        boolean fineGranted = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            RemoteLogger.log(context, Const.LOG_WARN, "LocationWorker: location permission is missing");
            return Result.failure();
        }

        try {
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: started (urgent=" + forceFreshFix + ")");

            if (forceFreshFix) {
                wakeLock = acquireWakeLock(context);
            }

            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                RemoteLogger.log(context, Const.LOG_WARN, "LocationWorker: LocationManager is null");
                return Result.success();
            }

            long maxFixAgeMs = forceFreshFix ? URGENT_MAX_FIX_AGE_MS : PERIODIC_MAX_FIX_AGE_MS;
            long now = System.currentTimeMillis();

            Location lastKnownAny = getBestLastKnownLocation(locationManager);
            Location lastKnownFresh = isLocationFresh(lastKnownAny, maxFixAgeMs) ? lastKnownAny : null;

            RemoteLogger.log(context, Const.LOG_INFO, "LocationWorker: requesting fresh location updates");

            ParallelProviderResult parallelResult = requestProvidersInParallel(
                    context,
                    locationManager,
                    fineGranted,
                    coarseGranted,
                    maxFixAgeMs);

            Location freshGps = parallelResult.gps;
            if (!isLocationFresh(freshGps, maxFixAgeMs)) {
                freshGps = null;
            }

            Location freshNetwork = parallelResult.network;
            if (!isLocationFresh(freshNetwork, maxFixAgeMs)) {
                freshNetwork = null;
            }

            Location location = parallelResult.firstFresh != null
                    ? parallelResult.firstFresh
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

            RemoteLogger.log(context, Const.LOG_INFO, "LocationWorker: success using " +
                    location.getProvider() + " (freshness: " +
                    (System.currentTimeMillis() - location.getTime()) / 1000 + "s, staleFallback=" + usedStaleFallback + ")");

            DatabaseHelper helper = DatabaseHelper.instance(context);
            if (helper == null) {
                return Result.success();
            }

            LocationTable.Location tableLocation = new LocationTable.Location(location);

            if (forceFreshFix && !usedStaleFallback) {
                // Only stamp ts=now for genuinely fresh locations so that the server
                // timeline correctly distinguishes a new fix from a recycled old one.
                // A stale fallback keeps its original GPS fix time; the server will
                // see latestUpdateTime <= baselineTs and keep polling for real data.
                tableLocation.setTs(System.currentTimeMillis());
            }

            if (forceFreshFix && LocationUploader.sendUrgentLocation(context, tableLocation)) {
                RemoteLogger.log(context, Const.LOG_INFO, "LocationWorker: urgent location uploaded successfully"
                        + (usedStaleFallback ? " (stale fallback — server will keep polling)" : ""));
                return Result.success();
            }

            LocationTable.insert(helper.getWritableDatabase(), tableLocation);
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: queued location for regular upload path");
            LocationUploader.sendLocations(context);
            return Result.success();
        } catch (SecurityException e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWorker: location permission denied while querying providers");
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
                        "LocationWorker: failed to acquire wake lock (PowerManager is null)");
                return null;
            }
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "hmdm:UrgentLocationWorker");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: wake lock acquired for urgent capture");
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
            long maxFixAgeMs) {
        ParallelProviderResult result = new ParallelProviderResult();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Location> gpsFuture = null;
        Future<Location> networkFuture = null;

        try {
            if (allowGps) {
                gpsFuture = executor.submit(() -> {
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWorker: GPS request started");
                    return tryRequestLiveUpdate(locationManager, LocationManager.GPS_PROVIDER, GPS_FIX_WAIT_SECONDS);
                });
            }

            if (allowNetwork) {
                networkFuture = executor.submit(() -> {
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWorker: Network request started");
                    return tryRequestLiveUpdate(locationManager, LocationManager.NETWORK_PROVIDER, NETWORK_FIX_WAIT_SECONDS);
                });
            }

            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(NETWORK_FIX_WAIT_SECONDS + 2);
            boolean gpsPending = gpsFuture != null;
            boolean networkPending = networkFuture != null;

            while ((gpsPending || networkPending) && System.nanoTime() < deadlineNs) {
                if (gpsPending && gpsFuture.isDone()) {
                    result.rawGps = getFutureResult(gpsFuture, 0);
                    gpsPending = false;
                    if (isLocationFresh(result.rawGps, maxFixAgeMs) && result.firstFresh == null) {
                        result.firstFresh = result.rawGps;
                    }
                }

                if (networkPending && networkFuture.isDone()) {
                    result.rawNetwork = getFutureResult(networkFuture, 0);
                    networkPending = false;
                    if (isLocationFresh(result.rawNetwork, maxFixAgeMs) && result.firstFresh == null) {
                        result.firstFresh = result.rawNetwork;
                    }
                }

                if (result.firstFresh != null) {
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
                result.rawGps = getFutureResult(gpsFuture, 0);
            }
            if (result.rawNetwork == null && networkFuture != null) {
                result.rawNetwork = getFutureResult(networkFuture, 0);
            }

            result.gps = isLocationFresh(result.rawGps, maxFixAgeMs) ? result.rawGps : null;
            result.network = isLocationFresh(result.rawNetwork, maxFixAgeMs) ? result.rawNetwork : null;
            RemoteLogger.log(context, Const.LOG_INFO,
                    "LocationWorker: provider results gps=" + (result.rawGps != null)
                            + ", network=" + (result.rawNetwork != null));
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

    private static Location getFutureResult(Future<Location> future, long timeoutSeconds) {
        if (future == null) {
            return null;
        }
        try {
            if (timeoutSeconds <= 0) {
                return future.isDone() ? future.get() : null;
            }
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return null;
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

    private static Location tryRequestLiveUpdate(LocationManager locationManager, String provider, long timeoutSeconds) {
        // Use a dedicated HandlerThread so GPS callbacks are delivered on their own
        // looper and never blocked by main-thread work or UI rendering.  This is
        // especially important for urgent refreshes triggered while the user is in
        // a background app or when the phone is moving and the GPS chip is waking up.
        HandlerThread handlerThread = new HandlerThread("gps-update-" + provider);
        handlerThread.start();
        try {
            if (!locationManager.isProviderEnabled(provider)) {
                return null;
            }
            final long requestStartedAt = System.currentTimeMillis();
            final Object lock = new Object();
            final Location[] bestObserved = new Location[1];
            final Location[] bestFresh = new Location[1];
            final CountDownLatch latch = new CountDownLatch(1);
            final LocationListener listener = location -> {
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

            locationManager.requestLocationUpdates(provider, 0L, 0f, listener, handlerThread.getLooper());
            latch.await(timeoutSeconds, TimeUnit.SECONDS);
            try {
                locationManager.removeUpdates(listener);
            } catch (Exception ignored) {
            }

            synchronized (lock) {
                if (bestFresh[0] != null) {
                    return bestFresh[0];
                }
                return bestObserved[0];
            }
        } catch (Exception ignored) {
            return null;
        } finally {
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

    private static class ParallelProviderResult {
        private Location rawGps;
        private Location rawNetwork;
        private Location gps;
        private Location network;
        private Location firstFresh;
    }
}
