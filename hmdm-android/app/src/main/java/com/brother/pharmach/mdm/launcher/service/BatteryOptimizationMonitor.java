package com.brother.pharmach.mdm.launcher.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.Constants;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.ui.ComplianceGatekeeperActivity;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

public class BatteryOptimizationMonitor extends Service {

    private static final String TAG = "BatteryOptMonitor";

    private Handler mHandler;
    private Runnable mPollingRunnable;

    // Set to true by stopByDpc() so onDestroy() knows this was an intentional stop
    private boolean mStoppedByDpc = false;
    private boolean mWasCompliant = true;

    @Override
    public void onCreate() {
        super.onCreate();
        // Handler must be attached explicitly to the main looper — never rely on implicit looper
        mHandler = new Handler(Looper.getMainLooper());
        mPollingRunnable = new Runnable() {
            @Override
            public void run() {
                checkCompliance();
                enforceDefaultDialer();
                mHandler.postDelayed(this, Constants.BATTERY_POLL_INTERVAL_MS);
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(Constants.NOTIFICATION_ID_COMPLIANCE, buildNotification());

        // Run an immediate check on the first tick rather than waiting 30 seconds
        checkCompliance();
        mHandler.postDelayed(mPollingRunnable, Constants.BATTERY_POLL_INTERVAL_MS);

        // START_STICKY: instructs the OS to restart this service after it is killed,
        // passing null as the intent. This is correct for a polling loop that does not
        // depend on the triggering intent.
        // OEM-QUIRK: On MIUI, ColorOS, One UI, and EMUI, START_STICKY alone is
        // insufficient. These ROMs kill services via their own battery managers
        // independently of the Android OS service lifecycle. See README.md for
        // ADB-based whitelisting commands that are required on these devices.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Cancel the polling loop to prevent Handler leaks after service destruction
        mHandler.removeCallbacks(mPollingRunnable);
        if (!mStoppedByDpc) {
            Log.w(TAG, "Service destroyed by OS — START_STICKY should trigger a restart");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Called by the DPC when it intentionally stops monitoring (e.g. during unenrollment). */
    public void stopByDpc() {
        mStoppedByDpc = true;
        stopSelf();
    }

    private void checkCompliance() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isCompliant = pm.isIgnoringBatteryOptimizations(getPackageName());

        if (!isCompliant) {
            if (mWasCompliant) {
                // Transition: compliant → non-compliant. Log once, not on every tick.
                RemoteLogger.log(this, Const.LOG_WARN,
                        "Battery optimization exemption removed — device is no longer exempt. " +
                        "Compliance gatekeeper will be shown until the user re-grants the exemption.");
            }
            // DISABLED: do not launch the battery optimization (compliance gatekeeper) screen
            // even when the exemption is missing. Kept for possible future re-enabling.
//            if (ComplianceGatekeeperActivity.isUserExploringSettings()) {
//                // The user left the gatekeeper via its button and is navigating Settings
//                // (possibly deep OEM battery menus). Re-launching the gatekeeper now would
//                // yank them out before they can grant the exemption — skip until the grace
//                // window expires or they return to the gatekeeper.
//                Log.i(TAG, "User is exploring Settings to grant the exemption — skipping gatekeeper re-launch");
//            } else {
//                Log.w(TAG, "Battery optimization exemption not active — enforcing compliance");
//                startComplianceEnforcement();
//            }
            Log.i(TAG, "Battery optimization exemption not active — gatekeeper screen disabled, not enforcing");
            mWasCompliant = false;
        } else {
            if (!mWasCompliant) {
                Log.i(TAG, "Battery optimization compliance restored");
                RemoteLogger.log(this, Const.LOG_INFO,
                        "Battery optimization exemption granted — device is now exempt from " +
                        "battery optimization. MDM monitoring service will run without OS restrictions.");
                LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(new Intent(Constants.ACTION_COMPLIANCE_RESTORED));
            }
            mWasCompliant = true;
        }
    }

    /**
     * Background arm of the default-dialer hard gate: if the app is provisioned and not yet the
     * default phone app, bring the blocking gatekeeper to the front — this is what catches the case
     * where the user managed to switch to another app instead of setting the default. Self-guards
     * on telephony/role availability and on the request grace window (so it never yanks away the
     * system role picker the user is actively using).
     */
    private void enforceDefaultDialer() {
        try {
            SettingsHelper settingsHelper = SettingsHelper.getInstance(getApplicationContext());
            if (settingsHelper == null || !settingsHelper.isBaseUrlSet()) {
                return; // not provisioned yet — do not block the enrollment flow
            }
            com.brother.pharmach.mdm.launcher.ui.DefaultDialerGatekeeperActivity.enforce(this);
        } catch (Exception e) {
            Log.w(TAG, "enforceDefaultDialer failed: " + e.getMessage());
        }
    }

    private void startComplianceEnforcement() {
        Intent intent = new Intent(this, ComplianceGatekeeperActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void createNotificationChannel() {
        // API-DIFF: Android 8.0 (API 26) — NotificationChannel required before startForeground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_ID_COMPLIANCE,
                    "MDM Compliance Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors battery optimization exemption for MDM compliance");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID_COMPLIANCE)
                .setContentTitle("MDM Compliance Active")
                .setContentText("Monitoring battery optimization exemption")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    /** Convenience method: start this service from a BroadcastReceiver or other background context.
     *
     * API-DIFF: Android 8.0 (API 26) — background service start restriction requires
     * ContextCompat.startForegroundService() instead of startService().
     */
    public static void startMonitor(Context context) {
        Intent intent = new Intent(context, BatteryOptimizationMonitor.class);
        // API-DIFF: Android 8.0 (API 26) — background service start restriction
        ContextCompat.startForegroundService(context, intent);
    }
}
