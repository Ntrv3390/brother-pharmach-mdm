package com.brother.pharmach.mdm.launcher.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.BuildConfig;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.ui.MainActivity;
import com.brother.pharmach.mdm.launcher.json.ServerConfig;
import com.brother.pharmach.mdm.launcher.util.LegacyUtils;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;
import com.brother.pharmach.mdm.launcher.util.Utils;
import com.brother.pharmach.mdm.launcher.worker.SmsLogUploadWorker;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class StatusControlService extends Service {

    private SettingsHelper settingsHelper;
    private ScheduledThreadPoolExecutor threadPoolExecutor = new ScheduledThreadPoolExecutor(1);
    private boolean controlDisabled = false;
    private Timer disableControlTimer;
    private BroadcastReceiver gpsStateReceiver;

    private final long ENABLE_CONTROL_DELAY = 60;
    private final long STATUS_CHECK_INTERVAL_MS = 10000;
    private final long SMS_TRIGGER_MIN_INTERVAL_MS = 4000;

    public static final int MOBILE_DATA_NOTIFICATION_ID = 2001;
    private static final String MOBILE_DATA_CHANNEL_ID = "mdm_mobile_data_channel";

    private long lastSmsTriggerMs = 0;
    private ContentObserver smsObserver;
    private ContentObserver mobileDataObserver;

    private static class PackageInfo {
        public String packageName;
        public String className;

        public PackageInfo(String packageName, String className) {
            this.packageName = packageName;
            this.className = className;
        }
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Const.ACTION_SERVICE_STOP:
                    stopSelf();
                    break;
                case Const.ACTION_STOP_CONTROL:
                    disableControl();
                    break;
            }
        }
    };

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        unregisterSmsObserver();
        unregisterMobileDataObserver();
        unregisterGpsStateReceiver();

        threadPoolExecutor.shutdownNow();
        threadPoolExecutor = new ScheduledThreadPoolExecutor(1);

        Log.i(Const.LOG_TAG, "StatusControlService: service stopped");

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        settingsHelper = SettingsHelper.getInstance(this);

        Log.i(Const.LOG_TAG, "StatusControlService: service started.");

        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);

        IntentFilter intentFilter = new IntentFilter(Const.ACTION_SERVICE_STOP);
        intentFilter.addAction(Const.ACTION_STOP_CONTROL);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, intentFilter);

        threadPoolExecutor.shutdownNow();

        threadPoolExecutor = new ScheduledThreadPoolExecutor(1);
        threadPoolExecutor.scheduleWithFixedDelay(
                () -> controlStatus(),
                STATUS_CHECK_INTERVAL_MS,
                STATUS_CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS);

        registerSmsObserverIfNeeded();
        registerMobileDataObserver();
        applyInitialGpsPolicy();
        registerGpsStateReceiver();

        return Service.START_STICKY;
    }

    private void registerMobileDataObserver() {
        unregisterMobileDataObserver();
        mobileDataObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                ServerConfig config = settingsHelper.getConfig();
                if (config == null || !Boolean.TRUE.equals(config.getMobileData())) {
                    return;
                }
                if (Utils.isSimAbsent(StatusControlService.this)) {
                    return;
                }
                try {
                    if (!Utils.isMobileDataEnabled(StatusControlService.this)) {
                        enforceMobileDataAndBringToFront();
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        };
        getContentResolver().registerContentObserver(
                android.provider.Settings.Global.getUriFor("mobile_data"),
                false,
                mobileDataObserver
        );
    }

    private void unregisterMobileDataObserver() {
        if (mobileDataObserver != null) {
            getContentResolver().unregisterContentObserver(mobileDataObserver);
            mobileDataObserver = null;
        }
    }

    private void registerSmsObserverIfNeeded() {
        if (!BuildConfig.ENABLE_SMS_LOG || smsObserver != null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(Const.LOG_TAG, "StatusControlService: READ_SMS not granted, SMS observer is skipped");
            return;
        }

        smsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                onSmsStoreChanged();
            }

            @Override
            public void onChange(boolean selfChange, android.net.Uri uri) {
                onSmsStoreChanged();
            }
        };

        getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver);
        Log.i(Const.LOG_TAG, "StatusControlService: SMS content observer registered");
    }

    private void unregisterSmsObserver() {
        if (smsObserver == null) {
            return;
        }

        try {
            getContentResolver().unregisterContentObserver(smsObserver);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "StatusControlService: failed to unregister SMS observer", e);
        }
        smsObserver = null;
    }

    private void onSmsStoreChanged() {
        long now = System.currentTimeMillis();
        if (now - lastSmsTriggerMs < SMS_TRIGGER_MIN_INTERVAL_MS) {
            return;
        }

        lastSmsTriggerMs = now;
        Log.i(Const.LOG_TAG, "StatusControlService: SMS store changed, triggering immediate upload");
        SmsLogUploadWorker.triggerNow(this, 3, "sms-content-observer");
    }

    private void disableControl() {
        Log.i(Const.LOG_TAG, "StatusControlService: request to disable control");

        if (disableControlTimer != null) {
            try {
                disableControlTimer.cancel();
            } catch (Exception e) {
            }
            disableControlTimer = null;
        }
        controlDisabled = true;
        disableControlTimer = new Timer();
        disableControlTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                controlDisabled = false;
                Log.i(Const.LOG_TAG, "StatusControlService: control enabled");
            }
        }, ENABLE_CONTROL_DELAY * 1000);
        Log.i(Const.LOG_TAG, "StatusControlService: control disabled for 60 sec");
    }

    private void controlStatus() {
        ServerConfig config = settingsHelper.getConfig();
        if (config == null || controlDisabled) {
            return;
        }

        if (config.getBluetooth() != null) {
            try {
                BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter != null) {
                    boolean enabled = bluetoothAdapter.isEnabled();
                    if (config.getBluetooth() && !enabled) {
                        bluetoothAdapter.enable();
                    } else if (!config.getBluetooth() && enabled) {
                        bluetoothAdapter.disable();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Note: SecurityException here on Mediatek
        // Looks like com.mediatek.permission.CTA_ENABLE_WIFI needs to be explicitly granted
        // or even available to system apps only
        // By now, let's just ignore this issue
        if (config.getWifi() != null) {
            try {
                WifiManager wifiManager = (WifiManager) this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    boolean enabled = wifiManager.isWifiEnabled();
                    if (config.getWifi() && !enabled) {
                        wifiManager.setWifiEnabled(true);
                    } else if (!config.getWifi() && enabled) {
                        wifiManager.setWifiEnabled(false);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (config.getGps() != null) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                boolean enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
                if (Boolean.TRUE.equals(config.getGps())) {
                    // GPS must be ON: lock the settings screen and force enable if currently off.
                    applyGpsLock(true);
                    if (!enabled) {
                        enforceGpsEnabled();
                        return;
                    }
                } else if (Boolean.FALSE.equals(config.getGps())) {
                    // GPS must be OFF: remove lock and notify user if still on.
                    applyGpsLock(false);
                    if (enabled) {
                        notifyStatusViolation(Const.GPS_OFF_REQUIRED);
                        return;
                    }
                }
            }
        } else {
            // No GPS policy — remove any previously applied lock so user can freely configure.
            applyGpsLock(false);
        }

        if (!Utils.isSimAbsent(this)) {
            try {
                if (Boolean.TRUE.equals(config.getMobileData())) {
                    // Lock the toggle so the user cannot disable mobile data at all.
                    Utils.setMobileDataLocked(true, this);
                    // Also ensure data is currently on (device may have just booted with it off).
                    if (!Utils.isMobileDataEnabled(this)) {
                        boolean reEnabled = Utils.setMobileDataEnabled(this, true);
                        if (!reEnabled) {
                            notifyStatusViolation(Const.MOBILE_DATA_ON_REQUIRED);
                        }
                    }
                } else if (Boolean.FALSE.equals(config.getMobileData())) {
                    // Policy says OFF — unlock so we can read the real state, then warn if on.
                    Utils.setMobileDataLocked(false, this);
                    if (Utils.isMobileDataEnabled(this)) {
                        notifyStatusViolation(Const.MOBILE_DATA_OFF_REQUIRED);
                    }
                } else {
                    // No policy — remove the lock so the user can freely configure.
                    Utils.setMobileDataLocked(false, this);
                }
            } catch (Exception e) {
                // Some problem accessing private API
            }
        }
    }

    private void enforceMobileDataAndBringToFront() {
        Utils.setMobileDataEnabled(this, true);

        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        launchIntent.putExtra(Const.POLICY_VIOLATION_CAUSE, Const.MOBILE_DATA_ON_REQUIRED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: the foreground-service exemption for background startActivity was
            // removed for apps targeting SDK 31+. The OS silently intercepts the call (no
            // exception thrown), so the app never comes to front. Use a high-priority
            // notification with fullScreenIntent instead — the Android-recommended approach.
            showMobileDataEnforcementNotification(launchIntent);
        } else {
            // Android 10-11: foreground-service exemption still applies; direct startActivity
            // immediately brings the app to front.
            try {
                startActivity(launchIntent);
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "StatusControlService: startActivity failed: " + e.getMessage());
                notifyStatusViolation(Const.MOBILE_DATA_ON_REQUIRED);
            }
        }
    }

    private void showMobileDataEnforcementNotification(Intent launchIntent) {
        // Android 13+ requires POST_NOTIFICATIONS runtime permission.
        // Device owner apps should have it auto-granted during enrollment, but guard anyway.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // Can't post a notification — fall back to the LocalBroadcast path which at least
            // works if the user returns to the MDM app voluntarily.
            notifyStatusViolation(Const.MOBILE_DATA_ON_REQUIRED);
            return;
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    MOBILE_DATA_CHANNEL_ID,
                    "Mobile Data Policy",
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }

        PendingIntent pi = PendingIntent.getActivity(
                this, MOBILE_DATA_NOTIFICATION_ID, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, MOBILE_DATA_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.message_turn_on_mobile_data))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        nm.notify(MOBILE_DATA_NOTIFICATION_ID, notification);
    }

    // ---------------------------------------------------------------------------
    // GPS enforcement — force GPS on and lock user from disabling it
    // ---------------------------------------------------------------------------

    private void applyInitialGpsPolicy() {
        ServerConfig config = settingsHelper.getConfig();
        if (config == null) return;
        if (Boolean.TRUE.equals(config.getGps())) {
            applyGpsLock(true);
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                enforceGpsEnabled();
            }
        } else {
            applyGpsLock(false);
        }
    }

    private void registerGpsStateReceiver() {
        unregisterGpsStateReceiver();
        gpsStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                ServerConfig config = settingsHelper.getConfig();
                if (config == null || !Boolean.TRUE.equals(config.getGps())) return;
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    enforceGpsEnabled();
                }
            }
        };
        // PROVIDERS_CHANGED_ACTION is a protected system broadcast — no RECEIVER_EXPORTED flag
        // required or permitted (Android 14 docs: system-broadcast receivers must omit the flag).
        IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
        registerReceiver(gpsStateReceiver, filter);
    }

    private void unregisterGpsStateReceiver() {
        if (gpsStateReceiver != null) {
            try {
                unregisterReceiver(gpsStateReceiver);
            } catch (Exception ignored) {}
            gpsStateReceiver = null;
        }
    }

    private void enforceGpsEnabled() {
        if (!Utils.isDeviceOwner(this)) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "StatusControlService: GPS enforcement requested but app is not device owner — showing user dialog");
            notifyStatusViolation(Const.GPS_ON_REQUIRED);
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = LegacyUtils.getAdminComponentName(this);
            if (dpm == null || admin == null) {
                notifyStatusViolation(Const.GPS_ON_REQUIRED);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
                // API 32+ (Android 12L): proper device-owner API to force location on.
                dpm.setLocationEnabled(admin, true);
            } else {
                // API < 32: set location_mode to HIGH_ACCURACY via setSecureSetting.
                dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE,
                        String.valueOf(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY));
            }
            RemoteLogger.log(this, Const.LOG_INFO,
                    "StatusControlService: GPS forced ON via DevicePolicyManager");
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "StatusControlService: GPS enforcement failed: " + e.getMessage());
            notifyStatusViolation(Const.GPS_ON_REQUIRED);
        }
    }

    private void applyGpsLock(boolean lock) {
        if (!Utils.isDeviceOwner(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return; // DISALLOW_CONFIG_LOCATION requires device owner on API 28+
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = LegacyUtils.getAdminComponentName(this);
            if (dpm == null || admin == null) return;
            if (lock) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION);
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION);
            }
            RemoteLogger.log(this, Const.LOG_INFO,
                    "StatusControlService: location settings " + (lock ? "locked" : "unlocked"));
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "StatusControlService: applyGpsLock(" + lock + ") failed: " + e.getMessage());
        }
    }

    private void notifyStatusViolation(int cause) {
        Intent intent = new Intent(Const.ACTION_POLICY_VIOLATION);
        intent.putExtra(Const.POLICY_VIOLATION_CAUSE, cause);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
