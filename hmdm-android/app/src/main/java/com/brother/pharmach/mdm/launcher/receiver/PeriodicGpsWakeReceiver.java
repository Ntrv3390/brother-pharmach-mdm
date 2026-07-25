package com.brother.pharmach.mdm.launcher.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.service.LocationForegroundService;
import com.brother.pharmach.mdm.launcher.util.DozeExitHelper;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;
import com.brother.pharmach.mdm.launcher.util.Utils;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * Autonomous periodic GPS refresh — every {@link #INTERVAL_MS} the device wakes its own screen
 * and captures + uploads a fresh fix, with NO admin action and NO server push required.
 *
 * This is the standalone "always tracking" heartbeat the on-device continuous listener can't
 * guarantee on its own: on ColorOS/MIUI the GPS chip is suppressed while the screen is off, so a
 * screen wake is what lets GNSS actually run. Rather than only waking on a "Get Latest GPS" click
 * or during a live-tracking session, this fires on a fixed 2.5-minute cadence.
 *
 * Uses setExactAndAllowWhileIdle + self-reschedule (same Doze-resilient pattern as
 * {@link LocationWatchdogReceiver}). On the Device-Owner fleet Doze is disabled outright, so the
 * alarm fires cleanly; where it isn't, allow-while-idle still fires (the OS may stretch the
 * interval in deep idle, but each fire's screen wake keeps the device out of deep idle).
 */
public class PeriodicGpsWakeReceiver extends BroadcastReceiver {

    static final String ACTION =
            "com.brother.pharmach.mdm.launcher.ACTION_PERIODIC_GPS_WAKE";
    // Fired a short time after a periodic wake to turn the screen back off (battery saving).
    static final String ACTION_LOCK_SCREEN =
            "com.brother.pharmach.mdm.launcher.ACTION_PERIODIC_LOCK_SCREEN";

    // Screen-on + GPS upload cadence. 20 minutes.
    private static final long INTERVAL_MS = TimeUnit.MINUTES.toMillis(20);

    // Active window (device local time) for the autonomous screen-wake tracker. Outside this
    // window it does nothing — but manual "Get Latest GPS", live tracking, and the app's normal
    // interval uploads are separate paths and keep working 24h. Minutes-of-day, inclusive of the
    // end boundary: active 08:00 → 21:00; inactive 21:01 → 07:59.
    private static final int ACTIVE_START_MIN = 8 * 60;    // 08:00
    private static final int ACTIVE_END_MIN = 21 * 60;     // 21:00
    // How long to keep the screen on after waking, so the GPS cold-start capture (≤20s) plus
    // upload finishes before we turn it back off. GNSS needs the screen on the whole time on
    // ColorOS, so this must comfortably exceed the capture window.
    private static final long SCREEN_OFF_DELAY_MS = TimeUnit.SECONDS.toMillis(35);
    private static final int REQ_WAKE = 0;
    private static final int REQ_LOCK = 1;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (ACTION_LOCK_SCREEN.equals(action)) {
            turnScreenOff(context);
            return;
        }

        if (!ACTION.equals(action)) return;

        // Reschedule first so the chain survives even if the work below throws. schedule() picks
        // the next fire time based on the active window: +20min while active, or a single jump to
        // the next 08:00 while inactive (so there are no wasteful periodic wakeups overnight).
        schedule(context);

        // 24/7: the active-hours (08:00–21:00) gate is disabled — the 20-min wake+upload now runs
        // around the clock. (Re-enable the block below to restrict it to daytime again.)
        // if (!isWithinActiveHours()) {
        //     RemoteLogger.log(context, Const.LOG_INFO,
        //             "PeriodicGpsWake: outside active hours (08:00–21:00) — skipping periodic wake");
        //     return;
        // }

        // Only turn the screen back off afterwards if WE are the ones turning it on — i.e. it was
        // off and the device is on battery. If the screen was already on (user is using it) or the
        // device is charging (vehicle mount wants the screen on), leave it as-is.
        boolean screenWasOff = DozeExitHelper.isScreenOff(context);
        boolean charging = isCharging(context);

        RemoteLogger.log(context, Const.LOG_INFO,
                "PeriodicGpsWake: 20-min tick — waking screen and capturing GPS"
                        + " (screenWasOff=" + screenWasOff + ", charging=" + charging + ")");

        // Guaranteed screen wake (unthrottled) so the GPS chip is not suppressed, then run the
        // normal urgent capture+upload pipeline (which stamps the fix as current).
        DozeExitHelper.wakeDeviceNow(context.getApplicationContext(), "periodic20min");
        LocationForegroundService.triggerUrgent(
                context.getApplicationContext(), "periodic20minWake");

        if (screenWasOff && !charging) {
            scheduleScreenOff(context);
        }
    }

    /** Turns the screen back off via the Device Owner lock API — best effort. */
    private void turnScreenOff(Context context) {
        // Re-check: if the device is now charging, a mount wants the screen on — don't lock.
        if (isCharging(context)) {
            RemoteLogger.log(context, Const.LOG_INFO,
                    "PeriodicGpsWake: skipping screen-off — device is now charging");
            return;
        }
        if (!Utils.isDeviceOwner(context)) return;
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return;
            dpm.lockNow();
            RemoteLogger.log(context, Const.LOG_INFO,
                    "PeriodicGpsWake: capture done — screen turned back off (lockNow) to save battery");
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "PeriodicGpsWake: lockNow failed: " + e.getMessage());
        }
    }

    private static boolean isCharging(Context context) {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return bm.isCharging();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Arms the next wake alarm. While inside the active window the next fire is +2.5min; while
     * outside it, the next fire is scheduled directly for the next 08:00 so the alarm doesn't
     * wake the CPU every 2.5 minutes all night just to skip. Idempotent — UPDATE_CURRENT dedups.
     */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(context, PeriodicGpsWakeReceiver.class);
        i.setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(context, REQ_WAKE, i, flags);

        // Fire at the next clock-aligned 20-min slot (…08:00, 08:20, 08:40, 09:00…) inside the
        // active window, so every device uploads at the SAME wall-clock times (3× per hour) instead
        // of drifting by whenever each device happened to (re)start.
        long triggerAt = nextAlignedSlotMillis();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                } else {
                    // SCHEDULE_EXACT_ALARM not granted — setWindow still wakes from idle.
                    am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt,
                            TimeUnit.SECONDS.toMillis(30), pi);
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "PeriodicGpsWake: failed to schedule alarm: " + e.getMessage());
        }
    }

    /** True when the device's local time is within the active window [08:00, 21:00]. */
    private static boolean isWithinActiveHours() {
        Calendar c = Calendar.getInstance();
        int minuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return minuteOfDay >= ACTIVE_START_MIN && minuteOfDay <= ACTIVE_END_MIN;
    }

    /**
     * Wall-clock millis of the next clock-aligned 20-min slot within the active window.
     * Slots are :00, :20, :40 of each hour from 08:00 through 21:00 inclusive. Always strictly
     * after "now", so a fire that lands exactly on a slot schedules the following one (no double
     * fire). Before 08:00 or after the last slot → the next day's 08:00. Device-clock based, so all
     * devices sharing a timezone wake at the same instants.
     */
    private static long nextAlignedSlotMillis() {
        Calendar slot = Calendar.getInstance();
        slot.set(Calendar.SECOND, 0);
        slot.set(Calendar.MILLISECOND, 0);
        int minute = slot.get(Calendar.MINUTE);
        int nextMult = ((minute / 20) + 1) * 20;   // 0→20, 20→40, 40→60(next hour); rolls over cleanly
        slot.set(Calendar.MINUTE, 0);
        slot.add(Calendar.MINUTE, nextMult);
        // 24/7: always fire at the next clock-aligned 20-min slot (…00:00, 00:20, 00:40, 01:00…),
        // around the clock. The active-hours (08:00–21:00) jump is disabled so the wake keeps
        // running overnight. (Re-enable the block below to restrict scheduling to daytime again.)
        // int slotMinOfDay = slot.get(Calendar.HOUR_OF_DAY) * 60 + slot.get(Calendar.MINUTE);
        // if (slotMinOfDay < ACTIVE_START_MIN || slotMinOfDay > ACTIVE_END_MIN) {
        //     slot.set(Calendar.HOUR_OF_DAY, ACTIVE_START_MIN / 60);
        //     slot.set(Calendar.MINUTE, ACTIVE_START_MIN % 60);
        //     if (!slot.after(Calendar.getInstance())) {
        //         slot.add(Calendar.DAY_OF_MONTH, 1);
        //     }
        // }
        return slot.getTimeInMillis();
    }

    /** Wall-clock millis of the next 08:00 (today if still upcoming, else tomorrow). */
    private static long nextActiveStartMillis() {
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, ACTIVE_START_MIN / 60);
        target.set(Calendar.MINUTE, ACTIVE_START_MIN % 60);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (!target.after(Calendar.getInstance())) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }
        return target.getTimeInMillis();
    }

    /** Arms a one-shot alarm to turn the screen back off once the capture window has elapsed. */
    private static void scheduleScreenOff(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, PeriodicGpsWakeReceiver.class);
        i.setAction(ACTION_LOCK_SCREEN);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(context, REQ_LOCK, i, flags);
        long triggerAt = System.currentTimeMillis() + SCREEN_OFF_DELAY_MS;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "PeriodicGpsWake: failed to schedule screen-off: " + e.getMessage());
        }
    }

    /** Cancels the periodic alarm (call on MDM unenrollment). */
    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, PeriodicGpsWakeReceiver.class);
        i.setAction(ACTION);
        int flags = PendingIntent.FLAG_NO_CREATE
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, flags);
        if (pi != null) am.cancel(pi);
    }
}
