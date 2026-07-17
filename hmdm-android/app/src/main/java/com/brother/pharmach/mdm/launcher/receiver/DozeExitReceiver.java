package com.brother.pharmach.mdm.launcher.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.service.LocationForegroundService;
import com.brother.pharmach.mdm.launcher.util.DozeExitHelper;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

/**
 * Fired by DozeExitHelper's setAlarmClock() kick — the alarm delivery itself pulls the device
 * out of deep idle (the alarm-clock-app mechanism). This receiver then wakes the screen/CPU and
 * triggers an urgent GPS capture while the device is briefly guaranteed awake.
 *
 * Deliberately does NOT call escapeDozeIfNeeded() again — that would arm another alarm and loop.
 */
public class DozeExitReceiver extends BroadcastReceiver {

    public static final String ACTION_DOZE_EXIT_GPS =
            "com.brother.pharmach.mdm.launcher.ACTION_DOZE_EXIT_GPS";
    public static final String EXTRA_REASON = "reason";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_DOZE_EXIT_GPS.equals(intent.getAction())) return;

        String reason = intent.getStringExtra(EXTRA_REASON);
        RemoteLogger.log(context, Const.LOG_INFO,
                "DozeExitReceiver: alarm-clock kick fired (reason=" + reason
                        + ") — waking device and capturing GPS");

        DozeExitHelper.wakeDeviceNow(context, "alarmClockKick:" + reason);
        LocationForegroundService.triggerUrgent(
                context.getApplicationContext(), "dozeExitAlarmClock:" + reason);
    }
}
