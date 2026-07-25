package com.brother.pharmach.mdm.launcher.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.receiver.DozeExitReceiver;

import java.util.Calendar;

/**
 * Doze escape hatch for urgent GPS capture.
 *
 * Doze (API 23+) suspends GNSS, network and timers while the screen is off and the device is
 * still. The battery-optimization whitelist softens this but deep idle still throttles location
 * on many OEMs. Alarm-clock apps break out of Doze with two primitives, both reused here:
 *
 *  1. {@link AlarmManager#setAlarmClock} — the one alarm type the system delivers IMMEDIATELY
 *     even in deep idle: no batching, no maintenance-window wait, no 9-minute while-idle
 *     throttle. The receiver then runs with the device briefly out of idle.
 *  2. Turning the screen on — Doze only exists while the screen is off, so a screen wake ends
 *     it entirely (the same reason an alarm's full-screen UI leaves the phone fully awake).
 *
 * {@link #escapeDozeIfNeeded} is a no-op when the device is not dozing and is throttled to one
 * attempt per {@link #MIN_ESCAPE_INTERVAL_MS} (20 min), so high-frequency callers (the 30-second
 * location heartbeat) can invoke it unconditionally. Automatic callers (force=false) are further
 * gated to the {@link #ACTIVE_START_MIN}–{@link #ACTIVE_END_MIN} window (08:00–21:00), leaving the
 * device asleep overnight. Operator-initiated captures ("Get Latest GPS" / live tracking) pass
 * force=true and bypass BOTH the throttle and the active-hours window, so they wake the device 24h.
 *
 * On Android 5.x (API < 23) Doze does not exist and everything here degrades to a no-op or a
 * plain wake lock, so the class is safe across the app's full minSdk 21 → current range.
 */
public final class DozeExitHelper {

    private static final long MIN_ESCAPE_INTERVAL_MS = 20 * 60_000L;   // 20 minutes

    // Active window (device local time) for the AUTOMATIC screen-wake/Doze-escape paths (the 30s
    // heartbeat and the 15-min watchdog). Outside this window the automatic wake stays idle so the
    // device is left to sleep overnight. Operator-initiated captures ("Get Latest GPS" / live
    // tracking) call with force=true and are exempt — they wake the screen 24h, every time.
    // Minutes-of-day, inclusive of the end boundary: active 08:00 → 21:00; inactive 21:01 → 07:59.
    private static final int ACTIVE_START_MIN = 8 * 60;    // 08:00
    private static final int ACTIVE_END_MIN = 21 * 60;     // 21:00

    private static final long SCREEN_WAKE_RETRY_MS = 30_000L;
    private static final long CPU_WAKELOCK_TIMEOUT_MS = 90_000L;
    private static final long SCREEN_WAKELOCK_TIMEOUT_MS = 10_000L;
    private static final int ALARM_REQUEST_CODE = 2002;
    private static final int KEYCODE_WAKEUP = 224;
    // Anti-cascade: the alarm-clock kick may re-arm at most once per this interval, EVEN for
    // force=true. Without this, an urgent capture on a device that resists waking from Doze (Realme/
    // ColorOS) loops: escape→kick→DozeExitReceiver→urgent→escape→kick… hundreds of times, flooding
    // uploads and even auto-tripping live mode. One kick per 90s is plenty to pierce Doze; the actual
    // capture still runs each urgent request regardless of whether a new kick was armed.
    private static final long KICK_MIN_INTERVAL_MS = 90_000L;

    private static volatile long lastEscapeAttemptMs;
    private static volatile long lastScreenWakeMs;
    private static volatile long lastKickArmedMs;

    private DozeExitHelper() {}

    /** True when the device is in Doze (deep idle). Always false below API 23 — no Doze there. */
    public static boolean isDozing(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isDeviceIdleMode();
    }

    /** True when the screen is off (non-interactive). */
    public static boolean isScreenOff(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            return !pm.isInteractive();
        }
        return !pm.isScreenOn();
    }

    /**
     * True when the device's local time is inside the automatic-wake window (08:00–21:00, end
     * inclusive). Gates the automatic (force=false) wake paths only; operator captures ignore it.
     */
    private static boolean isWithinActiveHours() {
        Calendar c = Calendar.getInstance();
        int minuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return minuteOfDay >= ACTIVE_START_MIN && minuteOfDay <= ACTIVE_END_MIN;
    }

    /**
     * Call right before an urgent, foreground-initiated GPS capture. On ColorOS/MIUI the GPS
     * chip is suspended for background apps whenever the SCREEN IS OFF — even when the device is
     * NOT in Doze (confirmed in field logs: deviceIdle=false, screenInteractive=false, zero
     * GnssStatus callbacks). A screen wake is what lets GNSS actually run for the capture.
     *
     *  - In Doze: the full alarm-clock escape (wake + setAlarmClock kick).
     *  - Screen off but not Doze: a direct screen wake (throttled to {@link #SCREEN_WAKE_RETRY_MS}).
     *  - Screen already on: no-op.
     */
    public static void prepareForForegroundCapture(Context context, String reason) {
        prepareForForegroundCapture(context, reason, false);
    }

    /**
     * As above, but {@code force=true} bypasses the {@link #SCREEN_WAKE_RETRY_MS} /
     * {@link #MIN_ESCAPE_INTERVAL_MS} throttles so an operator-initiated urgent capture
     * ("Get Latest GPS" / live tracking) wakes the screen the instant it is requested, every time —
     * not just once per throttle window. Still a no-op when the screen is already on.
     */
    public static void prepareForForegroundCapture(Context context, String reason, boolean force) {
        if (isDozing(context)) {
            escapeDozeIfNeeded(context, reason, force);
            return;
        }
        if (!isScreenOff(context)) return;
        // Automatic paths (force=false) only wake the screen inside the active window; operator
        // captures (force=true) are exempt and wake 24h.
        if (!force && !isWithinActiveHours()) {
            RemoteLogger.log(context, Const.LOG_INFO,
                    "DozeExitHelper: outside active hours (08:00–21:00) — skipping automatic screen"
                            + " wake (reason=" + reason + ")");
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastScreenWakeMs < SCREEN_WAKE_RETRY_MS) return;
        lastScreenWakeMs = now;
        RemoteLogger.log(context, Const.LOG_INFO,
                "DozeExitHelper: screen off (not Doze) before capture — waking screen so the GPS"
                        + " chip is not suppressed (reason=" + reason + ", force=" + force + ")");
        wakeDeviceNow(context, "screenOff:" + reason);
    }

    /**
     * If the device is dozing, wakes it the way an alarm clock does: immediate CPU + screen
     * wake, plus a setAlarmClock() kick ~1s out whose receiver re-triggers the urgent GPS
     * capture with the device guaranteed out of idle. Throttled; safe to call from any thread
     * and from high-frequency paths.
     */
    public static void escapeDozeIfNeeded(Context context, String reason) {
        escapeDozeIfNeeded(context, reason, false);
    }

    /**
     * As above, but {@code force=true} bypasses the {@link #MIN_ESCAPE_INTERVAL_MS} throttle so an
     * operator-initiated urgent capture escapes Doze immediately every time it is requested.
     */
    public static void escapeDozeIfNeeded(Context context, String reason, boolean force) {
        if (!isDozing(context)) return;
        // Automatic paths (force=false) only wake the device inside the active window; operator
        // captures (force=true) are exempt and wake 24h.
        if (!force && !isWithinActiveHours()) {
            RemoteLogger.log(context, Const.LOG_INFO,
                    "DozeExitHelper: outside active hours (08:00–21:00) — skipping automatic Doze"
                            + " escape (reason=" + reason + ")");
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastEscapeAttemptMs < MIN_ESCAPE_INTERVAL_MS) return;
        lastEscapeAttemptMs = now;

        RemoteLogger.log(context, Const.LOG_WARN,
                "DozeExitHelper: device is in Doze — escaping (reason=" + reason
                        + ", force=" + force + ")");
        wakeDeviceNow(context, reason);
        armAlarmClockKick(context, reason);
    }

    /**
     * Immediate best-effort wake: a partial wake lock so the CPU runs through the capture, and
     * a screen wake because a lit screen ends Doze entirely. The deprecated screen wake lock
     * still works on every API level including current Android; on OEMs that ignore it, the
     * Device Owner shell KEYCODE_WAKEUP injection is the backup.
     */
    @SuppressWarnings("deprecation")
    public static void wakeDeviceNow(Context context, String reason) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock cpu = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "hmdm:dozeExitCpu");
                cpu.acquire(CPU_WAKELOCK_TIMEOUT_MS);

                PowerManager.WakeLock screen = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "hmdm:dozeExitScreen");
                screen.acquire(SCREEN_WAKELOCK_TIMEOUT_MS);
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "DozeExitHelper: wake lock acquisition failed: " + e.getMessage());
        }

        // The reliable, public-API screen wake: an invisible activity with setTurnScreenOn().
        // ColorOS/MIUI ignore the deprecated wake lock above, and the DPM shell channel below
        // does not exist on stock builds — this is the path that must work on those devices.
        // Background-activity-launch restrictions don't block us: this app is the default HOME
        // launcher, a Device Owner, and holds SYSTEM_ALERT_WINDOW — any one of which exempts it.
        try {
            Intent wakeIntent = new Intent(context,
                    com.brother.pharmach.mdm.launcher.ui.WakeUpActivity.class);
            wakeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(wakeIntent);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "DozeExitHelper: WakeUpActivity launch failed: " + e.getMessage());
        }

        if (Utils.isDeviceOwner(context)) {
            runDpmShell(context, "input keyevent " + KEYCODE_WAKEUP);
        }
        RemoteLogger.log(context, Const.LOG_INFO,
                "DozeExitHelper: wake attempted (reason=" + reason + ", deviceOwner="
                        + Utils.isDeviceOwner(context) + ")");
    }

    /**
     * Arms a one-shot alarm-clock alarm ~1s out at {@link DozeExitReceiver}, which wakes the
     * device again and triggers an urgent GPS capture. setAlarmClock() is used when exact
     * alarms are permitted (manifest declares USE_EXACT_ALARM + SCHEDULE_EXACT_ALARM);
     * otherwise setAndAllowWhileIdle() still pierces Doze, just without the immediacy
     * guarantee.
     */
    private static void armAlarmClockKick(Context context, String reason) {
        // Anti-cascade throttle: never re-arm a kick within KICK_MIN_INTERVAL_MS, regardless of
        // force. This breaks the escape→kick→urgent→escape→kick loop on devices that don't leave
        // Doze immediately. The in-flight urgent capture still runs; we just don't stack kicks.
        long now = System.currentTimeMillis();
        if (now - lastKickArmedMs < KICK_MIN_INTERVAL_MS) {
            RemoteLogger.log(context, Const.LOG_INFO,
                    "DozeExitHelper: alarm-clock kick suppressed — one was armed "
                            + ((now - lastKickArmedMs) / 1000) + "s ago (anti-cascade, reason=" + reason + ")");
            return;
        }
        lastKickArmedMs = now;
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, DozeExitReceiver.class);
            intent.setAction(DozeExitReceiver.ACTION_DOZE_EXIT_GPS);
            intent.putExtra(DozeExitReceiver.EXTRA_REASON, reason);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent operation = PendingIntent.getBroadcast(
                    context, ALARM_REQUEST_CODE, intent, flags);

            long triggerAt = System.currentTimeMillis() + 1_000L;
            boolean exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || am.canScheduleExactAlarms();
            if (exactAllowed) {
                // The status-bar alarm icon appears briefly — that's the cost of the only
                // alarm type that exits deep idle immediately.
                PendingIntent showIntent = buildShowIntent(context, flags);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation);
                RemoteLogger.log(context, Const.LOG_INFO,
                        "DozeExitHelper: alarm-clock kick armed (reason=" + reason + ")");
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation);
                RemoteLogger.log(context, Const.LOG_WARN,
                        "DozeExitHelper: exact alarms not permitted — armed while-idle kick"
                                + " instead (reason=" + reason + ")");
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "DozeExitHelper: failed to arm alarm-clock kick: " + e.getMessage());
        }
    }

    private static PendingIntent buildShowIntent(Context context, int flags) {
        try {
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            if (launch != null) {
                return PendingIntent.getActivity(context, ALARM_REQUEST_CODE, launch, flags);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Executes a shell command through the hidden Device Owner channel (same mechanism
     * LocationForegroundService uses for power hardening). Fails harmlessly where unsupported.
     */
    static boolean runDpmShell(Context context, String shellCmd) {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            ComponentName admin = LegacyUtils.getAdminComponentName(context);
            android.os.ParcelFileDescriptor[] pipe = android.os.ParcelFileDescriptor.createPipe();
            dpm.getClass()
                    .getMethod("executeShellCommand", ComponentName.class, String.class,
                            android.os.ParcelFileDescriptor.class,
                            android.os.ParcelFileDescriptor.class)
                    .invoke(dpm, admin, shellCmd, pipe[1], null);
            pipe[0].close();
            pipe[1].close();
            RemoteLogger.log(context, Const.LOG_INFO,
                    "DozeExitHelper: DPM shell succeeded: " + shellCmd);
            return true;
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "DozeExitHelper: DPM shell failed (" + shellCmd + "): "
                            + e.getClass().getSimpleName() + " — " + e.getMessage());
            return false;
        }
    }
}
