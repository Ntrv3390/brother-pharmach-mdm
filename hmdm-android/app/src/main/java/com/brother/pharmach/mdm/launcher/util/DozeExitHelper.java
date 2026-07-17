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
 * attempt per {@link #MIN_ESCAPE_INTERVAL_MS}, so high-frequency callers (the 30-second location
 * heartbeat) can invoke it unconditionally.
 *
 * On Android 5.x (API < 23) Doze does not exist and everything here degrades to a no-op or a
 * plain wake lock, so the class is safe across the app's full minSdk 21 → current range.
 */
public final class DozeExitHelper {

    private static final long MIN_ESCAPE_INTERVAL_MS = 2 * 60_000L;
    private static final long CPU_WAKELOCK_TIMEOUT_MS = 90_000L;
    private static final long SCREEN_WAKELOCK_TIMEOUT_MS = 10_000L;
    private static final int ALARM_REQUEST_CODE = 2002;
    private static final int KEYCODE_WAKEUP = 224;

    private static volatile long lastEscapeAttemptMs;

    private DozeExitHelper() {}

    /** True when the device is in Doze (deep idle). Always false below API 23 — no Doze there. */
    public static boolean isDozing(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isDeviceIdleMode();
    }

    /**
     * If the device is dozing, wakes it the way an alarm clock does: immediate CPU + screen
     * wake, plus a setAlarmClock() kick ~1s out whose receiver re-triggers the urgent GPS
     * capture with the device guaranteed out of idle. Throttled; safe to call from any thread
     * and from high-frequency paths.
     */
    public static void escapeDozeIfNeeded(Context context, String reason) {
        if (!isDozing(context)) return;
        long now = System.currentTimeMillis();
        if (now - lastEscapeAttemptMs < MIN_ESCAPE_INTERVAL_MS) return;
        lastEscapeAttemptMs = now;

        RemoteLogger.log(context, Const.LOG_WARN,
                "DozeExitHelper: device is in Doze — escaping (reason=" + reason + ")");
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
