package com.brother.pharmach.mdm.launcher.util;

import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.HandlerThread;
import android.os.PowerManager;

import com.brother.pharmach.mdm.launcher.Const;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic-only instrumentation for the "Get Latest GPS" investigation.
 *
 * Every method here only reads system/OS state and forwards to {@link RemoteLogger}. Nothing
 * in this class may alter location-capture timing, ordering, or fallback decisions — it exists
 * solely to give the next failure capture enough resolution to pinpoint the divergence point.
 *
 * All lines are tagged "LocationDiag:" so they can be grepped out of the log stream.
 */
public final class LocationDiag {

    private LocationDiag() {}

    private static final String TAG = "LocationDiag";

    // ---------------------------------------------------------------------------
    // F. Timeline reconstruction.
    //
    // Best-effort only: correlates stages of a single in-flight urgent request via a shared
    // static timestamp. The urgent path is bounded to ~1 concurrent request in practice
    // (LocationForegroundService.currentUrgentTask cancels any prior one, and
    // LocationWorker's URGENT_EXECUTOR is a 1-running/1-queued pool), so this is good enough
    // for reconstructing a single reproduction's timeline without threading a request-id
    // parameter through every method signature. It is NOT safe to rely on if two urgent
    // requests from different sources race — the elapsed times would be misattributed.
    // ---------------------------------------------------------------------------

    private static final AtomicLong URGENT_REQUEST_START_NS = new AtomicLong(0);

    /** Call once, as early as possible, when an urgent GPS request is first observed. */
    public static void markUrgentRequestStart(Context context, String origin) {
        URGENT_REQUEST_START_NS.set(System.nanoTime());
        RemoteLogger.log(context, Const.LOG_INFO, TAG + ": TIMELINE stage=requestReceived origin="
                + origin + " wallClockMs=" + System.currentTimeMillis());
    }

    /** Logs elapsed time since the last {@link #markUrgentRequestStart}, tagged with a stage name. */
    public static void timeline(Context context, String stage) {
        long startNs = URGENT_REQUEST_START_NS.get();
        String elapsed = startNs == 0 ? "n/a (no urgent request marked)"
                : ((System.nanoTime() - startNs) / 1_000_000L) + "ms";
        RemoteLogger.log(context, Const.LOG_INFO, TAG + ": TIMELINE stage=" + stage
                + " elapsedSinceRequestStart=" + elapsed + " wallClockMs=" + System.currentTimeMillis());
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
    public static CancellationSignal wrapWithCancelLogging(Context context, String provider) {
        final long startNs = System.nanoTime();
        final CancellationSignal signal = new CancellationSignal();
        signal.setOnCancelListener(() -> RemoteLogger.log(context, Const.LOG_INFO,
                TAG + ": CANCEL_FIRED provider=" + provider
                        + " elapsedMs=" + ((System.nanoTime() - startNs) / 1_000_000L)));
        return signal;
    }

    // ---------------------------------------------------------------------------
    // D. Raw GNSS visibility — ground truth, independent of the app's request layer.
    // ---------------------------------------------------------------------------

    public static final class GnssWatch {
        private final Context context;
        private final String stage;
        private final LocationManager locationManager;
        private GnssStatus.Callback callback;
        private final AtomicInteger statusChangeCount = new AtomicInteger(0);
        private volatile int lastSatelliteCount = -1;
        private volatile int lastUsedInFixCount = -1;
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
                        int usedInFix = 0;
                        for (int i = 0; i < total; i++) {
                            if (status.usedInFix(i)) usedInFix++;
                        }
                        watch.lastSatelliteCount = total;
                        watch.lastUsedInFixCount = usedInFix;
                        watch.statusChangeCount.incrementAndGet();
                        RemoteLogger.log(context, Const.LOG_INFO,
                                TAG + ": GNSS_STATUS stage=" + stage
                                        + " satellitesVisible=" + total
                                        + " satellitesUsedInFix=" + usedInFix);
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
                            watch.callback, new android.os.Handler(watch.gnssThread.getLooper()));
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
                            + (statusChangeCount.get() == 0
                                    ? " (NO GnssStatus callback fired during this window"
                                            + " — chip visibility unknown to the app)"
                                    : ""));
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
}
