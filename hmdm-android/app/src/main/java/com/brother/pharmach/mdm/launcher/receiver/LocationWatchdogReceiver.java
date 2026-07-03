package com.brother.pharmach.mdm.launcher.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.service.LocationForegroundService;
import com.brother.pharmach.mdm.launcher.service.LocationService;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import java.util.concurrent.TimeUnit;

/**
 * Doze-proof watchdog for LocationForegroundService.
 *
 * Uses setExactAndAllowWhileIdle so the alarm fires even in deep Doze — unlike WorkManager
 * periodic jobs which can be deferred by 80+ minutes. Fires every 15 minutes, checks if
 * the FGS is alive, and restarts it if needed. Rescheduled inside onReceive so the chain
 * is self-perpetuating across reboots (BootReceiver calls schedule() on boot).
 */
public class LocationWatchdogReceiver extends BroadcastReceiver {

    static final String ACTION =
            "com.brother.pharmach.mdm.launcher.ACTION_LOCATION_WATCHDOG";
    private static final long INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;

        RemoteLogger.log(context, Const.LOG_INFO,
                "LocationWatchdog: heartbeat — ensuring LocationService is alive");

        // Always reschedule next alarm first so the chain survives even if start fails.
        schedule(context);

        // Trying out LocationService in place of LocationForegroundService — see
        // LocationService.java. Not deleted, just disabled:
        // LocationForegroundService.start(context.getApplicationContext());
        // start() is idempotent: if already running, onStartCommand fires with no action and
        // returns immediately. If it was killed, it restarts from scratch (periodic capture
        // resumes from onCreate()).
        LocationService.start(context.getApplicationContext());
    }

    /** Arms the next watchdog alarm. Safe to call multiple times — UPDATE_CURRENT deduplicates. */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(context, LocationWatchdogReceiver.class);
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
                    RemoteLogger.log(context, Const.LOG_INFO,
                            "LocationWatchdog: exact alarm scheduled");
                } else {
                    // USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM not granted — setWindow gives a
                    // ~10-min delivery window and still wakes the device from Doze.
                    am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt,
                            TimeUnit.MINUTES.toMillis(10), pi);
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "LocationWatchdog: exact alarm unavailable — using setWindow fallback");
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                RemoteLogger.log(context, Const.LOG_INFO,
                        "LocationWatchdog: exact alarm scheduled (API < 31)");
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "LocationWatchdog: failed to schedule alarm: " + e.getMessage());
        }
    }

    /** Cancels any pending watchdog alarm (call on MDM unenrollment). */
    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, LocationWatchdogReceiver.class);
        i.setAction(ACTION);
        int flags = PendingIntent.FLAG_NO_CREATE
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, flags);
        if (pi != null) am.cancel(pi);
    }
}
