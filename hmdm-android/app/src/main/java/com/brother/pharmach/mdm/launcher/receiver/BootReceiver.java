package com.brother.pharmach.mdm.launcher.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.helper.Initializer;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.pro.ProUtils;
import com.brother.pharmach.mdm.launcher.service.BatteryOptimizationMonitor;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // After OTA self-update the launcher is killed; relaunch it immediately so the
        // user is not stranded on the system launcher.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Log.i(Const.LOG_TAG, "Package replaced (OTA update) — relaunching launcher");
            RemoteLogger.log(context, Const.LOG_DEBUG, "Package replaced (OTA update) — relaunching launcher");
            // Post-OTA role status: confirms whether this update dropped the default launcher /
            // default phone app roles (which silently breaks the custom call receiver).
            try {
                RemoteLogger.log(context, Const.LOG_INFO,
                        "Default Launcher Our App: " + isDefaultLauncher(context));
                RemoteLogger.log(context, Const.LOG_INFO,
                        "Default Dialer Our App: "
                                + com.brother.pharmach.mdm.launcher.helper.DefaultDialerHelper
                                        .isDefaultDialer(context));
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "post-OTA role status log failed: " + e.getMessage());
            }
            Intent launch = new Intent(context, com.brother.pharmach.mdm.launcher.ui.MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(launch);
            return;
        }

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

            // Custom call receiver: the dialer role persists across reboots, but re-assert it and
            // re-grant the call permissions defensively in case an OEM cleared them on update/boot.
            try {
                com.brother.pharmach.mdm.launcher.helper.DefaultDialerHelper.ensureDefaultDialer(context);
                // Bring up the hard gate right after boot if we're not the default phone app.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    com.brother.pharmach.mdm.launcher.phone.DefaultDialerGate.update(context);
                }
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "BootReceiver: default dialer re-verify failed: " + e.getMessage());
            }

            // Start the battery optimization compliance monitor.
            // API-DIFF: Android 8.0 (API 26) — background service start restriction
            // requires ContextCompat.startForegroundService() (called inside startMonitor()).
            BatteryOptimizationMonitor.startMonitor(context);

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

    /** True if this app is the current default home/launcher app. */
    private static boolean isDefaultLauncher(Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo res = context.getPackageManager()
                    .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return res != null && res.activityInfo != null
                    && context.getPackageName().equals(res.activityInfo.packageName);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "isDefaultLauncher check failed: " + e.getMessage());
            return false;
        }
    }
}
