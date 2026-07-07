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
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

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

    // Mobile data policy watchdog: guarantees data is switched back on within ~1 second
    // even if the user finds an OS path around the device-owner restrictions
    // (seen on Android 15: QS internet dialog / Settings toggle miss the restriction check).
    private final long MOBILE_DATA_WATCHDOG_INTERVAL_MS = 1000;
    private final long MOBILE_DATA_ESCALATE_INTERVAL_MS = 30000;
    // Give the platform a few watchdog ticks to apply an accepted re-enable call
    // before bothering the user with the blocking dialog.
    private final int MOBILE_DATA_ESCALATE_AFTER_TICKS = 3;

    public static final int MOBILE_DATA_NOTIFICATION_ID = 2001;
    private static final String MOBILE_DATA_CHANNEL_ID = "mdm_mobile_data_channel";

    public static final int STATUS_CONTROL_NOTIFICATION_ID = 2002;
    private static final String STATUS_CONTROL_CHANNEL_ID = "mdm_status_control_channel";

    private long lastSmsTriggerMs = 0;
    private ContentObserver smsObserver;
    private ContentObserver mobileDataObserver;
    private int mobileDataViolationTicks = 0;
    private long lastMobileDataEscalationMs = 0;

    private List<TelephonyCallback> telephonyCallbacks = new ArrayList<>();
    private List<PhoneStateListener> phoneStateListeners = new ArrayList<>();

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
        unregisterMobileDataCallback();
        unregisterGpsStateReceiver();

        threadPoolExecutor.shutdownNow();
        threadPoolExecutor = new ScheduledThreadPoolExecutor(1);

        Log.i(Const.LOG_TAG, "StatusControlService: service stopped");

        super.onDestroy();
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, StatusControlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private Notification buildForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    STATUS_CONTROL_CHANNEL_ID,
                    "Device Management Status",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableLights(false);
            channel.enableVibration(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(this, STATUS_CONTROL_CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Device security policy is active")
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.startStableForegroundService(this, STATUS_CONTROL_NOTIFICATION_ID, buildForegroundNotification());
    }

    private void registerMobileDataCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            unregisterMobileDataCallback();
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (tm != null && sm != null) {
                try {
                    List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                    if (subs != null && !subs.isEmpty()) {
                        for (SubscriptionInfo sub : subs) {
                            int subId = sub.getSubscriptionId();
                            TelephonyManager subTm = tm.createForSubscriptionId(subId);
                            registerCallbackForManager(subTm);
                        }
                    } else {
                        registerCallbackForManager(tm);
                    }
                } catch (SecurityException e) {
                    registerCallbackForManager(tm);
                } catch (Exception e) {
                    registerCallbackForManager(tm);
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            unregisterMobileDataCallback();
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (tm != null && sm != null) {
                try {
                    List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                    if (subs != null && !subs.isEmpty()) {
                        for (SubscriptionInfo sub : subs) {
                            int subId = sub.getSubscriptionId();
                            TelephonyManager subTm = tm.createForSubscriptionId(subId);
                            registerListenerForManager(subTm);
                        }
                    } else {
                        registerListenerForManager(tm);
                    }
                } catch (SecurityException e) {
                    registerListenerForManager(tm);
                } catch (Exception e) {
                    registerListenerForManager(tm);
                }
            }
        }
    }

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.S)
    private void registerCallbackForManager(TelephonyManager tm) {
        class MobileDataCallback extends TelephonyCallback implements TelephonyCallback.UserMobileDataStateListener {
            @Override
            public void onUserMobileDataStateChanged(boolean enabled) {
                try {
                    threadPoolExecutor.execute(() -> enforceMobileDataPolicy());
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        MobileDataCallback callback = new MobileDataCallback();
        try {
            tm.registerTelephonyCallback(getMainExecutor(), callback);
            telephonyCallbacks.add(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void registerListenerForManager(TelephonyManager tm) {
        PhoneStateListener listener = new PhoneStateListener() {
            @Override
            public void onUserMobileDataStateChanged(boolean enabled) {
                try {
                    threadPoolExecutor.execute(() -> enforceMobileDataPolicy());
                } catch (Exception e) {
                    // ignore
                }
            }
        };
        try {
            tm.listen(listener, 0x00080000);
            phoneStateListeners.add(listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void unregisterMobileDataCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                for (TelephonyCallback callback : telephonyCallbacks) {
                    try {
                        tm.unregisterTelephonyCallback(callback);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            telephonyCallbacks.clear();
        } else {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                for (PhoneStateListener listener : phoneStateListeners) {
                    try {
                        tm.listen(listener, PhoneStateListener.LISTEN_NONE);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            phoneStateListeners.clear();
        }
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
        threadPoolExecutor.scheduleWithFixedDelay(
                () -> enforceMobileDataPolicy(),
                MOBILE_DATA_WATCHDOG_INTERVAL_MS,
                MOBILE_DATA_WATCHDOG_INTERVAL_MS,
                TimeUnit.MILLISECONDS);

        registerSmsObserverIfNeeded();
        registerMobileDataObserver();
        registerMobileDataCallback();
        applyInitialGpsPolicy();
        registerGpsStateReceiver();

        return Service.START_STICKY;
    }

    private void registerMobileDataObserver() {
        unregisterMobileDataObserver();
        mobileDataObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, android.net.Uri uri) {
                // Only react to the settings we care about. On multi-SIM / eSIM-capable
                // devices (most modern phones) the state is stored per subscription as
                // "mobile_data<subId>", not "mobile_data" — that is why the old observer
                // on the exact "mobile_data" URI never fired on Android 15.
                if (uri != null) {
                    String uriStr = uri.toString();
                    if (!uriStr.contains("mobile_data") && !uriStr.contains("airplane_mode")) {
                        return;
                    }
                }
                try {
                    threadPoolExecutor.execute(() -> enforceMobileDataPolicy());
                } catch (Exception e) {
                    // Executor shut down during service stop — ignore
                }
            }
        };
        // notifyForDescendants=true on the global settings root catches both
        // "mobile_data" and per-subscription "mobile_data<subId>" keys.
        getContentResolver().registerContentObserver(
                android.provider.Settings.Global.CONTENT_URI,
                true,
                mobileDataObserver
        );
    }

    /**
     * Runs every second (and instantly on a settings change via the content observer).
     * If the server policy requires mobile data ON and it is off, switches it back on
     * programmatically; if the platform refuses (stock Android gives device owners no
     * direct toggle API), escalates to the blocking dialog, throttled.
     */
    private void enforceMobileDataPolicy() {
        try {
            ServerConfig config = settingsHelper.getConfig();
            if (config == null || controlDisabled || !Boolean.TRUE.equals(config.getMobileData())) {
                mobileDataViolationTicks = 0;
                return;
            }
            if (Utils.isSimAbsent(this)) {
                mobileDataViolationTicks = 0;
                return;
            }
            if (Utils.isMobileDataEnabled(this)) {
                if (mobileDataViolationTicks > 0) {
                    RemoteLogger.log(this, Const.LOG_INFO,
                            "StatusControlService: mobile data is back ON (policy enforced)");
                }
                mobileDataViolationTicks = 0;
                return;
            }

            // Violation: try to silently switch data back on
            mobileDataViolationTicks++;
            Utils.setMobileDataEnabled(this, true);
            if (Utils.isMobileDataEnabled(this)) {
                RemoteLogger.log(this, Const.LOG_INFO,
                        "StatusControlService: mobile data was disabled by user, re-enabled automatically");
                mobileDataViolationTicks = 0;
                return;
            }

            // Still off after a few attempts — the OS rejected the programmatic toggle.
            // Force the user to turn it back on via the blocking dialog.
            long now = System.currentTimeMillis();
            if (mobileDataViolationTicks >= MOBILE_DATA_ESCALATE_AFTER_TICKS
                    && now - lastMobileDataEscalationMs >= MOBILE_DATA_ESCALATE_INTERVAL_MS) {
                lastMobileDataEscalationMs = now;
                enforceMobileDataAndBringToFront();
            }
        } catch (Exception e) {
            // ignore
        }
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
                    if (!enabled) {
                        enforceGpsEnabled();
                        return;
                    }
                } else if (Boolean.FALSE.equals(config.getGps())) {
                    if (enabled) {
                        notifyStatusViolation(Const.GPS_OFF_REQUIRED);
                        return;
                    }
                }
            }
        }

        if (!Utils.isSimAbsent(this)) {
            try {
                if (Boolean.TRUE.equals(config.getMobileData())) {
                    // Lock the toggle (Settings + Quick Settings become read-only) and block
                    // the airplane-mode bypass. Turning data back on if it is off is handled
                    // by the 1-second watchdog in enforceMobileDataPolicy().
                    Utils.setMobileDataLocked(true, this);
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
        // Clear any DISALLOW_CONFIG_LOCATION restriction that may have been applied by a
        // previous build — device policy restrictions survive app updates until explicitly removed.
        clearGpsLock();

        ServerConfig config = settingsHelper.getConfig();
        if (config == null) return;
        if (Boolean.TRUE.equals(config.getGps())) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                enforceGpsEnabled();
            }
        }
    }

    private void clearGpsLock() {
        if (!Utils.isDeviceOwner(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = LegacyUtils.getAdminComponentName(this);
            if (dpm == null || admin == null) return;
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION);
            RemoteLogger.log(this, Const.LOG_INFO,
                    "StatusControlService: cleared DISALLOW_CONFIG_LOCATION restriction");
        } catch (Exception e) {
            // Not applied or already cleared — safe to ignore
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
