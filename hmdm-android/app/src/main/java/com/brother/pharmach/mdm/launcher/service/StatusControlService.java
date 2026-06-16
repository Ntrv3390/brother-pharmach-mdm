package com.brother.pharmach.mdm.launcher.service;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
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
import android.provider.Telephony;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.BuildConfig;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.ui.MainActivity;
import com.brother.pharmach.mdm.launcher.json.ServerConfig;
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

    private final long ENABLE_CONTROL_DELAY = 60;
    private final long STATUS_CHECK_INTERVAL_MS = 10000;
    private final long SMS_TRIGGER_MIN_INTERVAL_MS = 4000;

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
                if (config.getGps() && !enabled) {
                    notifyStatusViolation(Const.GPS_ON_REQUIRED);
                    return;
                } else if (!config.getGps() && enabled) {
                    notifyStatusViolation(Const.GPS_OFF_REQUIRED);
                    return;
                }
            }
        }

        if (config.getMobileData() != null && !Utils.isSimAbsent(this)) {
            try {
                boolean enabled = Utils.isMobileDataEnabled(this);
                if (config.getMobileData() && !enabled) {
                    // Policy requires ON — re-enable programmatically (read-only enforcement).
                    // Same pattern as Wi-Fi enforcement above: direct API call, popup only on failure.
                    boolean reEnabled = Utils.setMobileDataEnabled(this, true);
                    if (!reEnabled) {
                        notifyStatusViolation(Const.MOBILE_DATA_ON_REQUIRED);
                    }
                } else if (!config.getMobileData() && enabled) {
                    notifyStatusViolation(Const.MOBILE_DATA_OFF_REQUIRED);
                }
            } catch (Exception e) {
                // Some problem accessing private API
            }
        }
    }

    private void enforceMobileDataAndBringToFront() {
        // Re-enable mobile data immediately (best-effort).
        Utils.setMobileDataEnabled(this, true);
        // Bring MainActivity to front so the user sees the enforcement popup instantly,
        // even if they disabled data from another app (Chrome, Settings, etc.).
        // onNewIntent() in MainActivity checks the current data state and shows the
        // appropriate message ("re-enabled by policy" vs "please enable").
        try {
            Intent launchIntent = new Intent(this, MainActivity.class);
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            launchIntent.putExtra(Const.POLICY_VIOLATION_CAUSE, Const.MOBILE_DATA_ON_REQUIRED);
            startActivity(launchIntent);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "StatusControlService: could not bring app to front: " + e.getMessage());
            notifyStatusViolation(Const.MOBILE_DATA_ON_REQUIRED);
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
