package com.brother.pharmach.mdm.launcher.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.helper.Initializer;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.pro.ProUtils;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(Const.LOG_TAG, "Got the BOOT_RECEIVER broadcast");
        RemoteLogger.log(context, Const.LOG_DEBUG, "Got the BOOT_RECEIVER broadcast");

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context.getApplicationContext());
        if (!settingsHelper.isBaseUrlSet()) {
            // We're here before initializing after the factory reset! Let's ignore this call
            return;
        }

        long lastAppStartTime = settingsHelper.getAppStartTime();
        long bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        Log.d(Const.LOG_TAG, "appStartTime=" + lastAppStartTime + ", bootTime=" + bootTime);
        if (lastAppStartTime < bootTime) {
            Log.i(Const.LOG_TAG, "Brother Pharmamach MDM wasn't started since boot, start initializing services");
        } else {
            Log.i(Const.LOG_TAG, "Brother Pharmamach MDM is already started, ignoring BootReceiver");
            return;
        }

        Initializer.init(context, () -> {
            Initializer.startServicesAndLoadConfig(context);

            SettingsHelper.getInstance(context).setMainActivityRunning(false);
            if (ProUtils.kioskModeRequired(context)) {
                Log.i(Const.LOG_TAG, "Kiosk mode required, forcing Brother Pharmamach MDM to run in the foreground");
                // If kiosk mode is required, then we just simulate clicking Home and starting MainActivity
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(homeIntent);
            }
        });
    }
}
