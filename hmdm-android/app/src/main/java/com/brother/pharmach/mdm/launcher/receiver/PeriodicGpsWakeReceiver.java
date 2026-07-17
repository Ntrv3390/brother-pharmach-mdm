package com.brother.pharmach.mdm.launcher.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.service.LocationForegroundService;
import com.brother.pharmach.mdm.launcher.util.DozeExitHelper;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

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

    // Screen-on + GPS upload cadence. 2.5 minutes.
    private static final long INTERVAL_MS = TimeUnit.SECONDS.toMillis(150);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;

        // Reschedule first so the chain survives even if the work below throws.
        schedule(context);

        RemoteLogger.log(context, Const.LOG_INFO,
                "PeriodicGpsWake: 2.5-min tick — waking screen and capturing GPS");

        // Guaranteed screen wake (unthrottled) so the GPS chip is not suppressed, then run the
        // normal urgent capture+upload pipeline (which stamps the fix as current).
        DozeExitHelper.wakeDeviceNow(context.getApplicationContext(), "periodic2.5min");
        LocationForegroundService.triggerUrgent(
                context.getApplicationContext(), "periodic2.5minWake");
    }

    /** Arms the next 2.5-minute alarm. Idempotent — UPDATE_CURRENT deduplicates. */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(context, PeriodicGpsWakeReceiver.class);
        i.setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, flags);

        long triggerAt = System.currentTimeMillis() + INTERVAL_MS;
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
