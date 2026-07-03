package com.brother.pharmach.mdm.launcher.util;

import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.brother.pharmach.mdm.launcher.Const;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic-only instrumentation for the "Get Latest GPS" investigation.
 *
 * Every method here only reads system/OS state and forwards to {@link RemoteLogger}, with two
 * narrow exceptions that are explicitly gated behind {@link ExperimentalFlags} and documented at
 * their call sites: aborting a fallback chain on interrupt, and a pre-capture vibration pulse.
 * With both flags off (the default) nothing in this class alters location-capture timing,
 * ordering, or fallback decisions.
 *
 * All lines are tagged "LocationDiag:" so they can be grepped out of the log stream.
 */
public final class LocationDiag {

    private LocationDiag() {}

    private static final String TAG = "LocationDiag";

    // ---------------------------------------------------------------------------
    // F. Per-request correlation + concurrency proof.
    //
    // Replaces the Phase-1 single global timestamp (which produced "n/a" on every line
    // because nothing was actually threading a shared marker through the real entry point
    // that fired). Every logical "Get Latest GPS" attempt now gets its own reqId, threaded
    // through method parameters (and one Intent extra for the onCreate() hand-off, since
    // onCreate() has no Intent parameter) rather than shared static state — safe even if
    // requests genuinely overlap, which is exactly the thing we're trying to prove/disprove.
    // ---------------------------------------------------------------------------

    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger(0);

    private static final class RequestMeta {
        final long startNs;
        final String origin;
        RequestMeta(long startNs, String origin) {
            this.startNs = startNs;
            this.origin = origin;
        }
    }

    private static final Map<String, RequestMeta> ACTIVE_REQUESTS = new ConcurrentHashMap<>();

    /**
     * Mints a new request id and marks it active. Call exactly once per logical
     * "Get Latest GPS" attempt, at its true entry point (e.g. {@code triggerUrgent()} or
     * {@code doWork()}) — NOT at every intermediate method it passes through.
     */
    public static String beginRequest(Context context, String origin) {
        String reqId = "req" + REQUEST_COUNTER.incrementAndGet();
        ACTIVE_REQUESTS.put(reqId, new RequestMeta(System.nanoTime(), origin));
        logConcurrency(context, "begin", reqId, origin);
        return reqId;
    }

    /** Call exactly once, wherever a logical request's outcome is finally determined. */
    public static void endRequest(Context context, String reqId) {
        RequestMeta meta = ACTIVE_REQUESTS.remove(reqId);
        long elapsedMs = meta == null ? -1 : (System.nanoTime() - meta.startNs) / 1_000_000L;
        logConcurrency(context, "end elapsedMs=" + elapsedMs, reqId,
                meta == null ? "unknown(already ended?)" : meta.origin);
    }

    private static void logConcurrency(Context context, String event, String reqId, String origin) {
        int active = ACTIVE_REQUESTS.size();
        // This is the direct, non-inferred proof (or disproof) of H2: if active > 1 here,
        // two logical "Get Latest GPS" attempts are genuinely in flight at the same time.
        RemoteLogger.log(context, active > 1 ? Const.LOG_WARN : Const.LOG_INFO,
                TAG + ": CONCURRENT_REQUESTS event=" + event + " reqId=" + reqId + " origin=" + origin
                        + " activeCount=" + active + " activeIds=" + ACTIVE_REQUESTS.keySet());
    }

    /** Logs elapsed time since {@code reqId}'s own {@link #beginRequest}, not a global marker. */
    public static void timeline(Context context, String reqId, String stage) {
        RequestMeta meta = ACTIVE_REQUESTS.get(reqId);
        String elapsed = meta == null ? "unknown (reqId not active — logged after endRequest?)"
                : ((System.nanoTime() - meta.startNs) / 1_000_000L) + "ms";
        RemoteLogger.log(context, Const.LOG_INFO, TAG + ": TIMELINE reqId=" + reqId + " stage=" + stage
                + " elapsedSinceRequestStart=" + elapsed + " wallClockMs=" + System.currentTimeMillis());
    }

    /**
     * Logs the gap between an upstream event (e.g. push receipt) and this request actually
     * starting on-device. Pass -1 for {@code upstreamTimestampMs} when there is no meaningful
     * upstream timestamp (e.g. a WorkManager-originated request) to skip the log line.
     */
    public static void logUpstreamLatency(Context context, String reqId, String upstreamEvent,
                                          long upstreamTimestampMs) {
        if (upstreamTimestampMs <= 0) return;
        RemoteLogger.log(context, Const.LOG_INFO, TAG + ": UPSTREAM_LATENCY reqId=" + reqId
                + " upstreamEvent=" + upstreamEvent
                + " latencyMs=" + (System.currentTimeMillis() - upstreamTimestampMs));
    }

    // ---------------------------------------------------------------------------
    // G. Interrupt visibility — makes "cancelled but kept running anyway" directly observable.
    // ---------------------------------------------------------------------------

    /**
     * Call at the top of every fallback-chain continuation point (e.g. right before falling
     * from getCurrentLocation() to the main-looper rescue, or from either of those to
     * FusedLocationProvider). Always logs when the thread is interrupted — regardless of the
     * {@link ExperimentalFlags#ABORT_ON_INTERRUPT_ENABLED} flag — so the baseline
     * "proceeded anyway" behavior is visible even with the fix disabled.
     *
     * @return true if the caller should abort and return immediately (only when the gated fix
     *         is enabled); false if the caller should proceed to the next fallback as before.
     */
    public static boolean checkInterruptGate(Context context, String reqId, String stage) {
        if (!Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (ExperimentalFlags.ABORT_ON_INTERRUPT_ENABLED) {
            RemoteLogger.log(context, Const.LOG_INFO, TAG + ": ABORTED_ON_INTERRUPT stage=" + stage
                    + " reqId=" + reqId);
            return true;
        }
        RemoteLogger.log(context, Const.LOG_WARN, TAG + ": PROCEEDING_DESPITE_INTERRUPT stage=" + stage
                + " reqId=" + reqId);
        return false;
    }

    // ---------------------------------------------------------------------------
    // A. Process & OS state at the moment a capture is triggered.
    // ---------------------------------------------------------------------------

    public static void logProcessAndPowerState(Context context, String stage) {
        try {
            ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean deviceIdle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && pm != null && pm.isDeviceIdleMode();
            boolean powerSave = pm != null && pm.isPowerSaveMode();
            boolean batteryExempt = pm != null
                    && pm.isIgnoringBatteryOptimizations(context.getPackageName());
            boolean interactive = pm != null && pm.isInteractive();

            String bucket = "n/a";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                UsageStatsManager usm =
                        (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    bucket = standbyBucketName(usm.getAppStandbyBucket());
                }
            }

            // importanceReasonCode is deprecated (always 0) on API 28+, but still populated
            // on API < 28 devices and costs nothing to log.
            RemoteLogger.log(context, Const.LOG_INFO, TAG + ": PROCESS_STATE stage=" + stage
                    + " importance=" + info.importance
                    + " importanceReasonCode=" + info.importanceReasonCode
                    + " deviceIdle(Doze)=" + deviceIdle
                    + " " + DozeTracker.durationSummary()
                    + " screenInteractive=" + interactive
                    + " powerSaveMode=" + powerSave
                    + " standbyBucket=" + bucket
                    + " batteryOptExempt=" + batteryExempt);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, TAG + ": logProcessAndPowerState failed: " + e);
        }
    }

    private static String standbyBucketName(int bucket) {
        if (bucket == UsageStatsManager.STANDBY_BUCKET_ACTIVE) return "ACTIVE";
        if (bucket == UsageStatsManager.STANDBY_BUCKET_WORKING_SET) return "WORKING_SET";
        if (bucket == UsageStatsManager.STANDBY_BUCKET_FREQUENT) return "FREQUENT";
        if (bucket == UsageStatsManager.STANDBY_BUCKET_RARE) return "RARE";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED) return "RESTRICTED";
        return "UNKNOWN(" + bucket + ")";
    }

    // ---------------------------------------------------------------------------
    // H. Continuous Doze transition tracking — point-in-time isDeviceIdleMode() only tells
    // you the current state, not how long it's been that way. Register once for the FGS
    // lifetime; every PROCESS_STATE line then reports how long the current state has held.
    // ---------------------------------------------------------------------------

    public static final class DozeTracker {
        private static volatile boolean currentlyIdle = false;
        private static volatile long lastTransitionNs = 0;
        private static volatile boolean seeded = false;
        private static BroadcastReceiver receiver;

        private DozeTracker() {}

        public static synchronized void register(Context context) {
            if (receiver != null) return;
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            currentlyIdle = pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && pm.isDeviceIdleMode();
            lastTransitionNs = System.nanoTime();
            seeded = true;

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    PowerManager pm2 = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    boolean idleNow = pm2 != null && pm2.isDeviceIdleMode();
                    long now = System.nanoTime();
                    long heldMs = seeded ? (now - lastTransitionNs) / 1_000_000L : -1;
                    RemoteLogger.log(ctx, Const.LOG_INFO, TAG + ": DOZE_TRANSITION enteredIdle=" + idleNow
                            + " previousStateHeldMs=" + heldMs + " wallClockMs=" + System.currentTimeMillis());
                    currentlyIdle = idleNow;
                    lastTransitionNs = now;
                    seeded = true;
                }
            };
            try {
                context.getApplicationContext().registerReceiver(receiver,
                        new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
                RemoteLogger.log(context, Const.LOG_INFO, TAG + ": DOZE_TRACKER registered initialIdle="
                        + currentlyIdle);
            } catch (Exception e) {
                RemoteLogger.log(context, Const.LOG_WARN, TAG + ": DozeTracker.register failed: " + e);
            }
        }

        public static synchronized void unregister(Context context) {
            if (receiver == null) return;
            try {
                context.getApplicationContext().unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }

        /** Human-readable "how long has the device been in its current Doze state" fragment. */
        static String durationSummary() {
            if (!seeded) return "dozeStateHeldMs=unknown(tracker not registered)";
            long heldMs = (System.nanoTime() - lastTransitionNs) / 1_000_000L;
            return "dozeStateHeldMs=" + heldMs + "(currentlyIdle=" + currentlyIdle + ")";
        }
    }

    // ---------------------------------------------------------------------------
    // B. Foreground service type registration (Android 15 is strict about this).
    // ---------------------------------------------------------------------------

    /**
     * Logs the manifest-declared foregroundServiceType for {@code serviceClass}, plus whether
     * ActivityManager currently reports the running instance as foreground.
     *
     * Caveat: the public API does not expose which foregroundServiceType bits the OS actually
     * *granted* at runtime (only what the manifest declares and whether the service is
     * foreground at all) — a silently-downgraded type is not directly observable this way.
     * If the manifest type includes bits the app is not entitled to (e.g. systemExempted
     * without qualifying), startForegroundService()/startForeground() throws instead of
     * degrading silently — call this right after that call returns so a thrown exception is
     * caught by the caller's existing try/catch and the absence of this log line itself is
     * diagnostic.
     */
    public static void logFgsRegistration(Context context, Class<?> serviceClass, String stage) {
        try {
            String declaredType;
            try {
                ServiceInfo si = context.getPackageManager().getServiceInfo(
                        new ComponentName(context, serviceClass), PackageManager.GET_META_DATA);
                declaredType = Build.VERSION.SDK_INT >= 34
                        ? foregroundServiceTypeToString(si.getForegroundServiceType())
                        : "not enforced below API 34 (running API " + Build.VERSION.SDK_INT + ")";
            } catch (Exception e) {
                declaredType = "lookup failed: " + e;
            }

            boolean foundInRunningServices = false;
            boolean osReportsForeground = false;
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                @SuppressWarnings("deprecation")
                List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(Integer.MAX_VALUE);
                if (services != null) {
                    for (ActivityManager.RunningServiceInfo rsi : services) {
                        if (rsi.service != null
                                && serviceClass.getName().equals(rsi.service.getClassName())) {
                            foundInRunningServices = true;
                            osReportsForeground = rsi.foreground;
                            break;
                        }
                    }
                }
            }

            RemoteLogger.log(context, Const.LOG_INFO, TAG + ": FGS_REGISTRATION stage=" + stage
                    + " service=" + serviceClass.getSimpleName()
                    + " declaredForegroundServiceType=" + declaredType
                    + " foundInRunningServices=" + foundInRunningServices
                    + " osReportsForeground=" + osReportsForeground);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, TAG + ": logFgsRegistration failed: " + e);
        }
    }

    private static String foregroundServiceTypeToString(int type) {
        StringBuilder sb = new StringBuilder();
        if ((type & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0) sb.append("location|");
        if ((type & ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) != 0) sb.append("specialUse|");
        if ((type & ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) != 0) sb.append("systemExempted|");
        if (sb.length() == 0) return "NONE(raw=0x" + Integer.toHexString(type) + ")";
        sb.setLength(sb.length() - 1);
        return sb + " (raw=0x" + Integer.toHexString(type) + ")";
    }

    // ---------------------------------------------------------------------------
    // C. Location API call helpers — cancellation timing.
    // ---------------------------------------------------------------------------

    /** Wraps a CancellationSignal so the exact elapsed time of an ACTUAL cancel is logged. */
    public static CancellationSignal wrapWithCancelLogging(Context context, String reqId, String provider) {
        final long startNs = System.nanoTime();
        final CancellationSignal signal = new CancellationSignal();
        signal.setOnCancelListener(() -> RemoteLogger.log(context, Const.LOG_INFO,
                TAG + ": CANCEL_FIRED reqId=" + reqId + " provider=" + provider
                        + " elapsedMs=" + ((System.nanoTime() - startNs) / 1_000_000L)));
        return signal;
    }

    // ---------------------------------------------------------------------------
    // D. Raw GNSS visibility — ground truth, independent of the app's request layer.
    // Per-request window (started/stopped around one parallel-provider request).
    // ---------------------------------------------------------------------------

    private static int countUsedInFix(GnssStatus status) {
        int used = 0;
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            if (status.usedInFix(i)) used++;
        }
        return used;
    }

    /**
     * Carrier-to-noise density (C/N0, dB-Hz) across all currently-tracked satellites — the
     * actual signal-strength ground truth. "Satellites visible" alone (used elsewhere in this
     * class) can stay high even indoors, since GnssStatus reports satellites the chip is
     * tracking at ANY signal level, not just ones strong enough to use in a fix. C/N0 is what
     * distinguishes "43 satellites, all weak (indoor/obstructed)" from "43 satellites, strong
     * (open sky, something else is blocking the fix)".
     *
     * Rough field reference: >=30 dB-Hz is typical open-sky signal strength; 20-30 dB-Hz is
     * partial obstruction (near windows/doors, light roofing); <20 dB-Hz is consistent with
     * being indoors or under heavy obstruction — most chips need roughly 18-20+ dB-Hz on enough
     * satellites to compute a fix at all.
     */
    private static final class Cn0Stats {
        final float min;
        final float max;
        final float avg;

        Cn0Stats(float min, float max, float avg) {
            this.min = min;
            this.max = max;
            this.avg = avg;
        }

        static Cn0Stats compute(GnssStatus status) {
            int total = status.getSatelliteCount();
            if (total == 0) return new Cn0Stats(0f, 0f, 0f);
            float min = Float.MAX_VALUE;
            float max = 0f;
            float sum = 0f;
            for (int i = 0; i < total; i++) {
                float cn0 = status.getCn0DbHz(i);
                if (cn0 < min) min = cn0;
                if (cn0 > max) max = cn0;
                sum += cn0;
            }
            return new Cn0Stats(min, max, sum / total);
        }

        String qualityLabel() {
            return qualityLabelForMax(max);
        }

        static String qualityLabelForMax(float maxCn0) {
            if (maxCn0 <= 0f) return "NONE";
            if (maxCn0 >= 30f) return "STRONG(open-sky range)";
            if (maxCn0 >= 20f) return "MODERATE(partial obstruction likely)";
            return "WEAK(consistent with indoor/heavy obstruction)";
        }

        @Override
        public String toString() {
            return "min=" + min + " max=" + max + " avg=" + String.format(java.util.Locale.US, "%.1f", avg)
                    + " quality=" + qualityLabel();
        }
    }

    public static final class GnssWatch {
        private final Context context;
        private final String stage;
        private final LocationManager locationManager;
        private GnssStatus.Callback callback;
        private final AtomicInteger statusChangeCount = new AtomicInteger(0);
        private volatile int lastSatelliteCount = -1;
        private volatile int lastUsedInFixCount = -1;
        private volatile float bestMaxCn0Seen = 0f;
        private HandlerThread gnssThread;

        private GnssWatch(Context context, String stage, LocationManager locationManager) {
            this.context = context;
            this.stage = stage;
            this.locationManager = locationManager;
        }

        /** Registers a GnssStatus.Callback for the duration of a capture attempt. No-op below API 24. */
        public static GnssWatch start(Context context, LocationManager locationManager, String stage) {
            GnssWatch watch = new GnssWatch(context, stage, locationManager);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || locationManager == null) {
                return watch;
            }
            try {
                watch.callback = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(GnssStatus status) {
                        int total = status.getSatelliteCount();
                        int usedInFix = countUsedInFix(status);
                        Cn0Stats cn0 = Cn0Stats.compute(status);
                        watch.lastSatelliteCount = total;
                        watch.lastUsedInFixCount = usedInFix;
                        if (cn0.max > watch.bestMaxCn0Seen) watch.bestMaxCn0Seen = cn0.max;
                        watch.statusChangeCount.incrementAndGet();
                        RemoteLogger.log(context, Const.LOG_INFO,
                                TAG + ": GNSS_STATUS stage=" + stage
                                        + " satellitesVisible=" + total
                                        + " satellitesUsedInFix=" + usedInFix
                                        + " cn0DbHz(" + cn0 + ")");
                    }

                    @Override
                    public void onFirstFix(int ttffMillis) {
                        RemoteLogger.log(context, Const.LOG_INFO,
                                TAG + ": GNSS_FIRST_FIX stage=" + stage + " ttffMs=" + ttffMillis);
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    watch.locationManager.registerGnssStatusCallback(
                            Runnable::run, watch.callback);
                } else {
                    watch.gnssThread = new HandlerThread("gnss-diag-" + stage);
                    watch.gnssThread.start();
                    watch.locationManager.registerGnssStatusCallback(
                            watch.callback, new Handler(watch.gnssThread.getLooper()));
                }
            } catch (Exception e) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        TAG + ": GnssWatch registration failed for stage=" + stage + ": " + e);
                watch.callback = null;
            }
            return watch;
        }

        /** Unregisters the callback and logs a summary. Safe to call even if start() no-op'd. */
        public void stopAndSummarize() {
            if (callback != null) {
                try {
                    locationManager.unregisterGnssStatusCallback(callback);
                } catch (Exception ignored) {
                }
            }
            if (gnssThread != null) {
                try {
                    gnssThread.quitSafely();
                } catch (Exception ignored) {
                }
            }
            RemoteLogger.log(context, Const.LOG_INFO,
                    TAG + ": GNSS_SUMMARY stage=" + stage
                            + " statusUpdates=" + statusChangeCount.get()
                            + " lastSatellitesVisible=" + lastSatelliteCount
                            + " lastSatellitesUsedInFix=" + lastUsedInFixCount
                            + " bestMaxCn0DbHzSeen=" + bestMaxCn0Seen
                            + " signalQuality=" + Cn0Stats.qualityLabelForMax(bestMaxCn0Seen)
                            + (statusChangeCount.get() == 0
                                    ? " (NO GnssStatus callback fired during this window"
                                            + " — chip visibility unknown to the app)"
                                    : ""));
        }
    }

    // ---------------------------------------------------------------------------
    // D2. Continuous GNSS visibility — runs for the FGS's whole lifetime, not just during
    // urgent request windows. Answers: is the continuous listener path getting ANY GNSS
    // engagement at all, independent of whether an urgent request happens to be running?
    // ---------------------------------------------------------------------------

    public static final class ContinuousGnssMonitor {
        private final Context context;
        private final LocationManager locationManager;
        private GnssStatus.Callback callback;
        private ScheduledExecutorService scheduler;
        private final AtomicInteger statusChangeCount = new AtomicInteger(0);
        private volatile int lastSatelliteCount = -1;
        private volatile int lastUsedInFixCount = -1;
        private volatile float lastMaxCn0 = 0f;
        private volatile long lastCallbackNs = 0;

        private ContinuousGnssMonitor(Context context, LocationManager locationManager) {
            this.context = context;
            this.locationManager = locationManager;
        }

        public static ContinuousGnssMonitor start(Context context, LocationManager locationManager) {
            ContinuousGnssMonitor monitor = new ContinuousGnssMonitor(context, locationManager);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || locationManager == null) {
                return monitor;
            }
            try {
                monitor.callback = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(GnssStatus status) {
                        monitor.lastSatelliteCount = status.getSatelliteCount();
                        monitor.lastUsedInFixCount = countUsedInFix(status);
                        monitor.lastMaxCn0 = Cn0Stats.compute(status).max;
                        monitor.statusChangeCount.incrementAndGet();
                        monitor.lastCallbackNs = System.nanoTime();
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    monitor.locationManager.registerGnssStatusCallback(Runnable::run, monitor.callback);
                } else {
                    monitor.locationManager.registerGnssStatusCallback(
                            monitor.callback, new Handler(context.getMainLooper()));
                }
                monitor.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "gnss-continuous-diag");
                    t.setDaemon(true);
                    return t;
                });
                monitor.scheduler.scheduleAtFixedRate(monitor::logSummary, 30, 30, TimeUnit.SECONDS);
                RemoteLogger.log(context, Const.LOG_INFO, TAG + ": GNSS_CONTINUOUS_MONITOR started");
            } catch (Exception e) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        TAG + ": ContinuousGnssMonitor start failed: " + e);
                monitor.callback = null;
            }
            return monitor;
        }

        private void logSummary() {
            long msSinceLastCallback = lastCallbackNs == 0 ? -1
                    : (System.nanoTime() - lastCallbackNs) / 1_000_000L;
            RemoteLogger.log(context, Const.LOG_INFO, TAG + ": GNSS_CONTINUOUS_SUMMARY"
                    + " statusUpdatesTotal=" + statusChangeCount.get()
                    + " lastSatellitesVisible=" + lastSatelliteCount
                    + " lastSatellitesUsedInFix=" + lastUsedInFixCount
                    + " lastMaxCn0DbHz=" + lastMaxCn0
                    + " signalQuality=" + Cn0Stats.qualityLabelForMax(lastMaxCn0)
                    + " msSinceLastCallback=" + (msSinceLastCallback < 0 ? "never" : msSinceLastCallback)
                    + " concurrentUrgentRequestsInFlight=" + ACTIVE_REQUESTS.size());
        }

        public void stop() {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            if (callback != null) {
                try {
                    locationManager.unregisterGnssStatusCallback(callback);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // E. Device/build metadata — logged once per capture attempt so we can rule a
    // specific OS build or GMS version in/out as the common factor across devices.
    // ---------------------------------------------------------------------------

    public static void logDeviceMetadata(Context context) {
        try {
            String securityPatch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? Build.VERSION.SECURITY_PATCH : "n/a";
            int targetSdk = context.getApplicationInfo().targetSdkVersion;

            String gmsVersion = "absent";
            try {
                PackageInfo pi = context.getPackageManager()
                        .getPackageInfo("com.google.android.gms", 0);
                gmsVersion = pi.versionName + " (code="
                        + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                ? pi.getLongVersionCode() : pi.versionCode) + ")";
            } catch (PackageManager.NameNotFoundException ignored) {
            }

            RemoteLogger.log(context, Const.LOG_INFO, TAG + ": DEVICE_METADATA"
                    + " fingerprint=" + Build.FINGERPRINT
                    + " securityPatch=" + securityPatch
                    + " androidApi=" + Build.VERSION.SDK_INT
                    + " targetSdkVersionRuntime=" + targetSdk
                    + " gmsVersion=" + gmsVersion
                    + " manufacturer=" + Build.MANUFACTURER
                    + " model=" + Build.MODEL);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, TAG + ": logDeviceMetadata failed: " + e);
        }
    }

    // ---------------------------------------------------------------------------
    // I. H1 experiment — low-risk vibration pulse to nudge Doze exit immediately before an
    // urgent capture. Only fires when ExperimentalFlags.DOZE_VIBRATION_PULSE_ENABLED is true.
    // ---------------------------------------------------------------------------

    public static void firePreCaptureVibrationPulseIfEnabled(Context context, String reqId) {
        if (!ExperimentalFlags.DOZE_VIBRATION_PULSE_ENABLED) return;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean dozeBefore = pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && pm.isDeviceIdleMode();
            RemoteLogger.log(context, Const.LOG_INFO, TAG + ": DOZE_PULSE reqId=" + reqId
                    + " stage=beforePulse dozeIdle=" + dozeBefore);

            Vibrator vibrator = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vibrator = vm.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(80);
                }
            } else {
                RemoteLogger.log(context, Const.LOG_WARN, TAG + ": DOZE_PULSE reqId=" + reqId
                        + " — no vibrator available on this device");
            }

            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                RemoteLogger.log(context, Const.LOG_INFO, TAG + ": DOZE_PULSE reqId=" + reqId
                        + " — interrupted during post-pulse wait, proceeding immediately");
                return;
            }

            boolean dozeAfter = pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && pm.isDeviceIdleMode();
            RemoteLogger.log(context, Const.LOG_INFO, TAG + ": DOZE_PULSE reqId=" + reqId
                    + " stage=afterPulse dozeIdle=" + dozeAfter
                    + (dozeBefore && !dozeAfter ? " (Doze EXITED shortly after pulse)" : " (no change)"));
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, TAG + ": firePreCaptureVibrationPulseIfEnabled failed: " + e);
        }
    }
}
