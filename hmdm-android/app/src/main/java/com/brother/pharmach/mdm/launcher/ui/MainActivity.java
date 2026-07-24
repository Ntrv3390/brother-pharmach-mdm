/*
 * Brother Pharmamach MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.brother.pharmach.mdm.launcher.ui;

import android.Manifest;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import androidx.viewpager2.widget.ViewPager2;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.GridLayoutManager;

import com.github.anrwatchdog.ANRWatchDog;
import com.brother.pharmach.mdm.launcher.AdminReceiver;
import com.brother.pharmach.mdm.launcher.BuildConfig;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.databinding.ActivityMainBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogAccessibilityServiceBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogAdministratorModeBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogEnterPasswordBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogFileDownloadingFailedBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogHistorySettingsBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogManageStorageBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogMiuiPermissionsBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogOverlaySettingsBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogPermissionsBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogSystemSettingsBinding;
import com.brother.pharmach.mdm.launcher.databinding.DialogUnknownSourcesBinding;
import com.brother.pharmach.mdm.launcher.helper.ConfigUpdater;
import com.brother.pharmach.mdm.launcher.helper.CryptoHelper;
import com.brother.pharmach.mdm.launcher.helper.Initializer;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.Application;
import com.brother.pharmach.mdm.launcher.json.DeviceInfo;
import com.brother.pharmach.mdm.launcher.json.RemoteFile;
import com.brother.pharmach.mdm.launcher.json.PushMessage;
import com.brother.pharmach.mdm.launcher.json.ServerConfig;
import com.brother.pharmach.mdm.launcher.pro.ProUtils;
import com.brother.pharmach.mdm.launcher.pro.service.CheckForegroundAppAccessibilityService;
import com.brother.pharmach.mdm.launcher.pro.service.CheckForegroundApplicationService;
import com.brother.pharmach.mdm.launcher.receiver.ScreenOffReceiver;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;
import com.brother.pharmach.mdm.launcher.server.UnsafeOkHttpClient;
import com.brother.pharmach.mdm.launcher.service.PluginApiService;
import com.brother.pharmach.mdm.launcher.service.StatusControlService;
import com.brother.pharmach.mdm.launcher.task.GetServerConfigTask;
import com.brother.pharmach.mdm.launcher.task.SendDeviceInfoTask;
import com.brother.pharmach.mdm.launcher.ui.custom.StatusBarUpdater;
import com.brother.pharmach.mdm.launcher.ui.quickpanel.QuickPanelController;
import com.brother.pharmach.mdm.launcher.util.AppInfo;
import com.brother.pharmach.mdm.launcher.util.CrashLoopProtection;
import com.brother.pharmach.mdm.launcher.util.DeviceInfoProvider;
import com.brother.pharmach.mdm.launcher.util.InstallUtils;
import com.brother.pharmach.mdm.launcher.util.PreferenceLogger;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;
import com.brother.pharmach.mdm.launcher.util.SystemUtils;
import com.brother.pharmach.mdm.launcher.util.LocationUploader;
import com.brother.pharmach.mdm.launcher.util.Utils;
import com.brother.pharmach.mdm.launcher.worker.SmsLogUploadWorker;
import com.brother.pharmach.mdm.launcher.worker.SendDeviceInfoWorker;
import com.jakewharton.picasso.OkHttp3Downloader;
import com.squareup.picasso.NetworkPolicy;
import com.squareup.picasso.Picasso;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import okhttp3.Cache;
import okhttp3.OkHttpClient;

public class MainActivity
        extends BaseActivity
        implements View.OnLongClickListener, BaseAppListAdapter.OnAppChooseListener,
        BaseAppListAdapter.SwitchAdapterListener, View.OnClickListener,
        ConfigUpdater.UINotifier {

    private static final int PERMISSIONS_REQUEST = 1000;

    private ActivityMainBinding binding;
    private SettingsHelper settingsHelper;

    // Ongoing-call banner controller (custom call receiver). Only instantiated on API 23+.
    private com.brother.pharmach.mdm.launcher.phone.OngoingCallBanner ongoingCallBanner;

    private Dialog fileNotDownloadedDialog;
    private DialogFileDownloadingFailedBinding dialogFileDownloadingFailedBinding;

    private Dialog enterPasswordDialog;
    private DialogEnterPasswordBinding dialogEnterPasswordBinding;

    private Dialog overlaySettingsDialog;
    private DialogOverlaySettingsBinding dialogOverlaySettingsBinding;

    private Dialog historySettingsDialog;
    private DialogHistorySettingsBinding dialogHistorySettingsBinding;

    private Dialog manageStorageDialog;
    private DialogManageStorageBinding dialogManageStorageBinding;

    private Dialog miuiPermissionsDialog;
    private DialogMiuiPermissionsBinding dialogMiuiPermissionsBinding;

    private Dialog unknownSourcesDialog;
    private DialogUnknownSourcesBinding dialogUnknownSourcesBinding;

    private Dialog administratorModeDialog;
    private DialogAdministratorModeBinding dialogAdministratorModeBinding;

    private Dialog accessibilityServiceDialog;
    private DialogAccessibilityServiceBinding dialogAccessibilityServiceBinding;

    private Dialog systemSettingsDialog;
    // Whether systemSettingsDialog is currently showing the "turn on mobile data" prompt. Unlike
    // the GPS/password/turn-off-mobile-data variants (which genuinely should be torn down when the
    // activity pauses, e.g. because the user navigated to Settings), this one must survive a
    // transient pause — the mobile-data violation state doesn't resolve just because the activity
    // was briefly paused (e.g. by the accessibility service's own home-bounce reacting to the
    // app-lockdown sweep), so dismissing-and-rebuilding it on every such pause is exactly what
    // made it flicker.
    private boolean systemSettingsDialogIsMobileDataOn = false;
    private DialogSystemSettingsBinding dialogSystemSettingsBinding;

    private Dialog permissionsDialog;
    private DialogPermissionsBinding dialogPermissionsBinding;

    private Handler handler = new Handler();
    private View applicationNotAllowed;
    private View lockScreen;

    private SharedPreferences preferences;

    private MainAppListAdapter mainAppListAdapter;
    private BottomAppListAdapter bottomAppListAdapter;
    private PagedAppListAdapter pagedAppListAdapter;
    private java.util.List<com.brother.pharmach.mdm.launcher.util.AppInfo> mainAppItems;
    private boolean pagerCallbackRegistered = false;
    private volatile boolean contentLoadInProgress = false;
    private boolean pendingContentReload = false;
    private String lastContentSignature = null;
    private int spanCount;
    private StatusBarUpdater statusBarUpdater = new StatusBarUpdater();
    private Boolean settingsLockedByWorkTime = null;

    private static boolean configInitialized = false;
    // This flag is used to exit kiosk to avoid looping in onResume()
    private static boolean interruptResumeFlow = false;
    private static final int BOOT_DURATION_SEC = 120;
    private static final int PAUSE_BETWEEN_AUTORUNS_SEC = 5;
    private boolean sendDeviceInfoScheduled = false;
    // This flag notifies "download error" dialog what we're downloading: application or file
    // We cannot send this flag as the method parameter because dialog calls MainActivity methods
    private boolean downloadingFile = false;

    private int kioskUnlockCounter = 0;

    private boolean configFault = false;

    private boolean needSendDeviceInfoAfterReconfigure = false;
    private boolean needRedrawContentAfterReconfigure = false;
    private boolean orientationLocked = false;

    private int REQUEST_CODE_GPS_STATE_CHANGE = 1;

    // This flag is used by the broadcast receiver to determine what to do if it gets a policy violation report
    private boolean isBackground;

    private ANRWatchDog anrWatchDog;

    // Issue 7: debounce guard — minimum ms between showContent() calls from periodic/broadcast sources
    private static final long SHOW_CONTENT_DEBOUNCE_MS = 500;
    private long lastShowContentMs = 0;

    // Auto-refresh config when push delivery is delayed: refresh if config is stale.
    private static final long STALE_CONFIG_REFRESH_MS = 20 * 60 * 1000L;
    private static final long STALE_CONFIG_MIN_RETRY_MS = 5 * 60 * 1000L;
    private long lastStaleConfigRefreshAttemptMs = 0;

    // Issue 7: single-thread executor for background policy/enforcement work
    private static final java.util.concurrent.ExecutorService POLICY_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private int lastNetworkType;

    private ConfigUpdater configUpdater = new ConfigUpdater();

    private Picasso picasso = null;

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive( Context context, Intent intent ) {
            switch ( intent.getAction() ) {
                case Const.ACTION_UPDATE_CONFIGURATION:
                    RemoteLogger.log(context, Const.LOG_DEBUG, "Update configuration by MainActivity");
                    updateConfig(false);
                    // Force refresh of WorkTime policy
                    com.brother.pharmach.mdm.launcher.util.WorkTimeManager.getInstance().updatePolicy(context, true);
                    // Force UI Refresh explicitly to reflect policy changes immediately
                    ServerConfig updatedConfig = SettingsHelper.getInstance(MainActivity.this).getConfig();
                    if (updatedConfig != null) {
                         showContent(updatedConfig);
                    }
                    break;
                case Const.ACTION_HIDE_SCREEN:
                    String blockedPackage = intent.getStringExtra(Const.PACKAGE_NAME);
                    RemoteLogger.log(MainActivity.this, Const.LOG_DEBUG, "Received ACTION_HIDE_SCREEN for package: " + blockedPackage);
                    // Drop stale block events if policy changed (e.g., device exception became active)
                    // between the foreground check and this receiver callback.
                    if (blockedPackage != null && com.brother.pharmach.mdm.launcher.util.WorkTimeManager
                            .getInstance().isAppAllowed(context, blockedPackage)) {
                        RemoteLogger.log(MainActivity.this, Const.LOG_DEBUG,
                                "Ignoring stale ACTION_HIDE_SCREEN for now-allowed package: " + blockedPackage);
                        break;
                    }
                    // Respect the user-launch grace window so a freshly-tapped app isn't yanked away.
                    if (blockedPackage != null && com.brother.pharmach.mdm.launcher.util.WorkTimeManager
                            .getInstance().isWithinUserLaunchGrace(blockedPackage)) {
                        RemoteLogger.log(MainActivity.this, Const.LOG_DEBUG,
                                "Ignoring ACTION_HIDE_SCREEN during user-launch grace for: " + blockedPackage);
                        break;
                    }
                        enforceWorkTimeAsync(context, true);
                    ServerConfig serverConfig = SettingsHelper.getInstance(MainActivity.this).getConfig();
                    if (serverConfig != null && serverConfig.getLock() != null && serverConfig.getLock()) {
                        // Device is locked by the server administrator — show the lock screen.
                        RemoteLogger.log(MainActivity.this, Const.LOG_DEBUG, "Showing lock screen due to server lock");
                        showLockScreen();
                    }
                    // WorkTime restriction: enforceWorkTimeAsync above already killed the app
                    // and will bring the launcher to the foreground — no overlay needed.
                    break;

                case Const.ACTION_DISABLE_BLOCK_WINDOW:
                    if ( applicationNotAllowed != null) {
                        applicationNotAllowed.setVisibility(View.GONE);
                    }
                    break;

                case Const.ACTION_EXIT:
                    finish();
                    break;

                case Const.ACTION_POLICY_VIOLATION:
                    if (isBackground) {
                        // If we're in the background, let's bring Brother Pharmamach MDM to top and the notification will be raised in onResume
                        Intent restoreLauncherIntent = new Intent(context, MainActivity.class);
                        restoreLauncherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(restoreLauncherIntent);
                    } else {
                        // Calling startActivity always calls onPause / onResume which is not what we want
                        // So just show dialog if it isn't already shown
                        if (systemSettingsDialog == null || !systemSettingsDialog.isShowing()) {
                            notifyPolicyViolation(intent.getIntExtra(Const.POLICY_VIOLATION_CAUSE, 0));
                        }
                    }
                    break;

                case Const.ACTION_EXIT_KIOSK:
                    ServerConfig config = settingsHelper.getConfig();
                    if (config != null) {
                        config.setKioskMode(false);
                        RemoteLogger.log(MainActivity.this, Const.LOG_INFO, "Exit kiosk by admin command");
                        showContent(config);
                    }
                    break;

                case Const.ACTION_ADMIN_PANEL:
                    openAdminPanel();
                    break;

                case com.brother.pharmach.mdm.launcher.util.WorkTimeManager.ACTION_WORKTIME_POLICY_UPDATED:
                    ServerConfig cfg = settingsHelper != null ? settingsHelper.getConfig() : null;
                    if (cfg != null) {
                        // shouldRefreshUI() consumes the state change, so call it before showContent
                        com.brother.pharmach.mdm.launcher.util.WorkTimeManager wm2 =
                                com.brother.pharmach.mdm.launcher.util.WorkTimeManager.getInstance();
                        boolean worktimeStateChanged = wm2.shouldRefreshUI();
                        showContent(cfg);
                        // Only bring launcher to front when worktime just became active (transition),
                        // not on every periodic policy refresh.
                        enforceWorkTimeAsync(context, worktimeStateChanged && wm2.isWorkTimeActive());
                    }
                    break;
            }

        }
    };

    private final BroadcastReceiver pushReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String configUpdatedAction = Const.INTENT_PUSH_NOTIFICATION_PREFIX + PushMessage.TYPE_CONFIG_UPDATED;
            if (action != null && action.equals(configUpdatedAction)) {
                RemoteLogger.log(context, Const.LOG_DEBUG, "Update configuration by Push");
                com.brother.pharmach.mdm.launcher.util.WorkTimeManager.getInstance().updatePolicy(context, true);
                ServerConfig updatedConfig = settingsHelper.getConfig();
                if (updatedConfig != null) {
                    showContent(updatedConfig);
                }
            }
        }
    };

    private final BroadcastReceiver screenOffReceiver = new ScreenOffReceiver();

    // Issue 2: enforce restrictions immediately when user unlocks the screen
    private final BroadcastReceiver userPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;
            com.brother.pharmach.mdm.launcher.util.WorkTimeManager wm =
                    com.brother.pharmach.mdm.launcher.util.WorkTimeManager.getInstance();
            wm.updatePolicy(context);
            if (settingsHelper != null && settingsHelper.getConfig() != null) {
                showContentDebounced(settingsHelper.getConfig());
                // On screen unlock, enforce suspensions. Bring launcher to front only if
                // worktime is active — the screen unlock itself means the user is present,
                // so we should show the launcher home rather than a restricted app.
                enforceWorkTimeAsync(context, wm.isWorkTimeActive());
            }
        }
    };

    private final BroadcastReceiver stateChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_TIME_TICK.equals(intent.getAction())) {
                 com.brother.pharmach.mdm.launcher.util.WorkTimeManager wm =
                         com.brother.pharmach.mdm.launcher.util.WorkTimeManager.getInstance();
                  wm.updatePolicy(context);
                 // shouldRefreshUI() returns true only when worktime state actually transitions
                 // (e.g., enforcement just started or ended). Use this to gate bringToFront.
                 boolean stateChanged = wm.shouldRefreshUI();
                 if (stateChanged) {
                     needRedrawContentAfterReconfigure = true;
                     showContentDebounced(settingsHelper.getConfig()); // Issue 7: debounced
                 }
                  // Enforce every tick (suspend/kill restricted apps), but only bring
                  // launcher to front when the worktime window actually transitions.
                  // This prevents startActivity() from firing every 60s causing flashing.
                  enforceWorkTimeAsync(context, stateChanged && wm.isWorkTimeActive());
                   requestStaleConfigRefreshIfNeeded(context);
                 return;
            }

            // Log new connection type and flush queued locations on reconnect
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (null != activeNetwork) {
                    if (lastNetworkType != activeNetwork.getType()) {
                        lastNetworkType = activeNetwork.getType();
                        RemoteLogger.log(MainActivity.this, Const.LOG_DEBUG, "Network type changed: " + activeNetwork.getTypeName());
                        // Flush any locations queued while offline instead of waiting for next WorkManager cycle
                        new Thread(() -> LocationUploader.sendLocations(context)).start();
                    }
                } else {
                    if (lastNetworkType != -1) {
                        lastNetworkType = -1;
                        RemoteLogger.log(MainActivity.this, Const.LOG_DEBUG, "Network connection lost");
                    }
                }
            }

            // Issue 7 Plan A: post applyEarlyPolicies off the hot broadcast path
            // so connectivity changes don't block the main thread
            final ServerConfig connectivityConfig = settingsHelper.getConfig();
            handler.post(() -> {
                try {
                    applyEarlyPolicies(connectivityConfig);
                } catch (Exception e) {
                }
            });
        }
    };

    private GradientDrawable selectedManageButtonBorder = new GradientDrawable();
    private ImageView exitView;
    private long exitFirstTapTime = 0;
    private int exitTapCount = 0;
    private ImageView infoView;
    private ImageView updateView;

    private View statusBarView;
    private View rightToolbarView;

    // Swipe-down quick settings panel (in-app overlay, kiosk-safe)
    private QuickPanelController quickPanelController;

    private boolean firstStartAfterProvisioning = false;

    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );

        Intent intent = getIntent();
        Log.d(Const.LOG_TAG, "MainActivity started" + (intent != null && intent.getAction() != null ?
                ", action: " + intent.getAction() : ""));
        if (intent != null && "android.app.action.PROVISIONING_SUCCESSFUL".equalsIgnoreCase(intent.getAction())) {
            firstStartAfterProvisioning = true;
        }

        if (CrashLoopProtection.isCrashLoopDetected(this)) {
            Toast.makeText(MainActivity.this, R.string.fault_loop_detected, Toast.LENGTH_LONG).show();
            return;
        }

        // Disable crashes to avoid "select a launcher" popup
        // Crashlytics will show an exception anyway!
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                e.printStackTrace();

                ProUtils.sendExceptionToCrashlytics(e);

                CrashLoopProtection.registerFault(MainActivity.this);
                // Restart launcher if there's a launcher restarter (and we're not in a crash loop)
                if (!CrashLoopProtection.isCrashLoopDetected(MainActivity.this)) {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(Const.LAUNCHER_RESTARTER_PACKAGE_ID);
                    if (intent != null) {
                        startActivity(intent);
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    finishAffinity();
                }
                System.exit(0);
            }
        });


        if (BuildConfig.ANR_WATCHDOG) {
            // Issue 7 Plan E: re-enable ANRWatchDog with a non-crashing reporter so ANRs are
            // logged to the remote server for diagnosis instead of killing the app
            anrWatchDog = new ANRWatchDog();
            anrWatchDog.setANRListener(error -> {
                StringBuilder sb = new StringBuilder("ANR detected:\n");
                for (StackTraceElement frame : error.getStackTrace()) {
                    sb.append("  at ").append(frame.toString()).append("\n");
                }
                RemoteLogger.log(MainActivity.this, Const.LOG_ERROR, sb.toString());
            });
            anrWatchDog.start();
        }

        // Prevent showing the lock screen during the app download/installation
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        binding.setMessage(getString( R.string.main_start_preparations));
        binding.loading.setVisibility(View.VISIBLE);

        settingsHelper = SettingsHelper.getInstance(this);
        preferences = getSharedPreferences(Const.PREFERENCES, MODE_PRIVATE);

        if ("".equals(settingsHelper.getDeviceId()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AdminReceiver.updateSettingsFromFile(this);
        }

        settingsHelper.setAppStartTime(System.currentTimeMillis());

        Initializer.init(this, () -> {

            // Try to start services in onCreate(), this may fail, we will try again on each onResume.
            startServicesWithRetry();

            initReceiver();

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            intentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
            intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            intentFilter.addAction(Intent.ACTION_TIME_TICK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(stateChangeReceiver, intentFilter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(stateChangeReceiver, intentFilter);
            }

            intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(screenOffReceiver, intentFilter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(screenOffReceiver, intentFilter);
            }

            intentFilter = new IntentFilter(Const.INTENT_PUSH_NOTIFICATION_PREFIX + PushMessage.TYPE_CONFIG_UPDATED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(pushReceiver, intentFilter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(pushReceiver, intentFilter);
            }

            // Issue 2: listen for screen unlock to enforce restrictions on recents
            IntentFilter userPresentFilter = new IntentFilter(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(userPresentReceiver, userPresentFilter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(userPresentReceiver, userPresentFilter);
            }

            if (!getIntent().getBooleanExtra(Const.RESTORED_ACTIVITY, false)) {
                startAppsAtBoot();
            }

            settingsHelper.setMainActivityRunning(true);
        });
    }

    // On some Android firmwares, onResume is called before onCreate, so the fields are not initialized
    // Here we initialize all required fields to avoid crash at startup
    private void reinitApp() {
        if (binding == null) {
            binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
            binding.setMessage(getString(R.string.main_start_preparations));
            binding.loading.setVisibility(View.VISIBLE);
        }

        if (settingsHelper == null) {
            settingsHelper = SettingsHelper.getInstance(this);
        }
        if (preferences == null) {
            preferences = getSharedPreferences(Const.PREFERENCES, MODE_PRIVATE);
        }
    }

    private static final int REQUEST_CODE_ACCESSIBILITY_SETTINGS = 100;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_ACCESSIBILITY_SETTINGS) {
            // User returned from Accessibility settings — check if they enabled our service
            if (ProUtils.checkAccessibilityService(this)) {
                preferences.edit().putInt(Const.PREFERENCES_ACCESSIBILITY_SERVICE, Const.PREFERENCES_ON).commit();
            }
        }
    }

    @Override
    protected void updateSettingsFromQr(String qrcode) {
        super.updateSettingsFromQr(qrcode);
        // Dismiss any open enrollment dialogs so the flow can proceed cleanly
        // when onResume calls startLauncher() with the newly saved settings.
        dismissDialog(enterDeviceIdDialog);
        dismissDialog(enterServerDialog);
    }

    private void initReceiver() {
        IntentFilter intentFilter = new IntentFilter(Const.ACTION_UPDATE_CONFIGURATION);
        intentFilter.addAction(Const.ACTION_HIDE_SCREEN);
        intentFilter.addAction(Const.ACTION_EXIT);
        intentFilter.addAction(Const.ACTION_POLICY_VIOLATION);
        intentFilter.addAction(Const.ACTION_EXIT_KIOSK);
        intentFilter.addAction(Const.ACTION_ADMIN_PANEL);
        intentFilter.addAction(com.brother.pharmach.mdm.launcher.util.WorkTimeManager.ACTION_WORKTIME_POLICY_UPDATED);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, intentFilter);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Second safety net: synchronous compliance check independent of BatteryOptimizationMonitor.
        // Catches the case where the service hasn't started yet (e.g. first launch, crash recovery).
        checkBatteryOptimizationCompliance();

        isBackground = false;

        // Issue 5: refresh WorkTime policy on every resume so the Favorites page
        // never shows restricted apps after screen unlock or app switch
        com.brother.pharmach.mdm.launcher.util.WorkTimeManager.getInstance().updatePolicy(this);
        // Do NOT bringToFront here: we are already resuming (already coming to the foreground).
        // Calling startActivity on an already-resuming activity causes a flash/loop.
        enforceWorkTimeAsync(this, false);

        // On some Android firmwares, onResume is called before onCreate, so the fields are not initialized
        // Here we initialize all required fields to avoid crash at startup
        reinitApp();

        initQuickPanel();

        statusBarUpdater.startUpdating(this, binding.clock, binding.batteryState);

        startServicesWithRetry();

        checkMobileDataViolation();
        enforceOverlayPermission();

        // Custom call receiver: set default dialer silently when Device Owner; otherwise hard-gate
        // the user with the blocking gatekeeper until the app is the default phone app. Only after
        // the device is provisioned, so we never block the initial enrollment flow.
        try {
            if (settingsHelper != null && settingsHelper.isBaseUrlSet()) {
                com.brother.pharmach.mdm.launcher.helper.DefaultDialerHelper.ensureCallSetup(this);
                // Overlay-based hard gate: blocks the device until the app is the default phone app.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    com.brother.pharmach.mdm.launcher.phone.DefaultDialerGate.update(this);
                }
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "ensureCallSetup/enforce failed: " + e.getMessage());
        }

        // Show the "return to call" banner if a call is live (so the call screen is always reachable
        // from the launcher, even with no keyguard).
        startOngoingCallBanner();

        if (interruptResumeFlow) {
            interruptResumeFlow = false;
            return;
        }

        if (!BuildConfig.SYSTEM_PRIVILEGES) {
            if (firstStartAfterProvisioning) {
                firstStartAfterProvisioning = false;
                waitForProvisioning(10);
            } else {
                setDefaultLauncherEarly();
            }
        } else {
            setSelfAsDeviceOwner();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null
                && intent.getIntExtra(Const.POLICY_VIOLATION_CAUSE, 0) == Const.MOBILE_DATA_ON_REQUIRED) {
            // Dismiss the enforcement notification (posted on Android 12+ when data was disabled).
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(StatusControlService.MOBILE_DATA_NOTIFICATION_ID);

            if (Utils.isMobileDataEnabled(this)) {
                // Already back on (auto-restored by policy or the user complied) — nothing to
                // show the user, just clear any stale prompt that may still be up.
                dismissDialog(systemSettingsDialog);
                systemSettingsDialogIsMobileDataOn = false;
                StatusControlService.setMobileDataDialogVisible(false);
            } else if (systemSettingsDialog == null || !systemSettingsDialog.isShowing()) {
                createAndShowSystemSettingDialog(getString(R.string.message_turn_on_mobile_data),
                        mobileNetworkSettingsIntent(), null, null);
            }
        }
    }

    private void lockOrientation() {
        int orientation = getResources().getConfiguration().orientation;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Log.d(Const.LOG_TAG, "Lock orientation: orientation=" + orientation + ", rotation=" + rotation);
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            setRequestedOrientation(rotation < Surface.ROTATION_180 ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
        } else {
            setRequestedOrientation(rotation < Surface.ROTATION_180 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mainAppListAdapter != null && event.getAction() == KeyEvent.ACTION_UP) {
            if (!mainAppListAdapter.onKey(keyCode)) {
                if (bottomAppListAdapter != null) {
                    return bottomAppListAdapter.onKey(keyCode);
                }
            };
        }
        return super.onKeyUp(keyCode, event);
    }

    // Workaround against crash "App is in background" on Android 9: this is an Android OS bug
    // https://stackoverflow.com/questions/52013545/android-9-0-not-allowed-to-start-service-app-is-in-background-after-onresume
    private void startServicesWithRetry() {
        try {
            startServices();
        } catch (Exception e) {
            // Android OS bug!!!
            e.printStackTrace();

            // Repeat an attempt to start services after one second
            handler.postDelayed(new Runnable() {
                public void run() {
                    try {
                        startServices();
                    } catch (Exception e) {
                        // Still failed, now give up!
                        // startService may fail after resuming, but the service may be already running (there's a WorkManager)
                        // So if we get an exception here, just ignore it and hope the app will work further
                        e.printStackTrace();
                    }
                }
            }, 1000);
        }
    }

    private void requestStaleConfigRefreshIfNeeded(Context context) {
        if (settingsHelper == null || configUpdater == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastStaleConfigRefreshAttemptMs < STALE_CONFIG_MIN_RETRY_MS) {
            return;
        }

        long lastConfigUpdate = settingsHelper.getConfigUpdateTimestamp();
        if (lastConfigUpdate != 0 && now - lastConfigUpdate < STALE_CONFIG_REFRESH_MS) {
            return;
        }

        if (configUpdater.isConfigInitializing()) {
            return;
        }

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm != null ? cm.getActiveNetworkInfo() : null;
        if (activeNetwork == null || !activeNetwork.isConnected()) {
            return;
        }

        lastStaleConfigRefreshAttemptMs = now;
        RemoteLogger.log(context, Const.LOG_DEBUG, "Config is stale, requesting background refresh");
        updateConfig(false);
    }

    private void startAppsAtBoot() {
        // Let's assume that we start within two minutes after boot
        // This should work even for slow devices
        long uptimeMillis = SystemClock.uptimeMillis();
        if (uptimeMillis > BOOT_DURATION_SEC * 1000) {
            return;
        }
        final ServerConfig config = settingsHelper.getConfig();
        if (config == null || config.getApplications() == null) {
            // First start
            return;
        }

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                boolean appStarted = false;
                for (Application application : config.getApplications()) {
                    if (application.isRunAtBoot()) {
                        if (!com.brother.pharmach.mdm.launcher.util.WorkTimeManager.getInstance()
                                .isAppAllowed(application.getPkg())) {
                            RemoteLogger.log(MainActivity.this, Const.LOG_INFO,
                                    "Skipping run-at-boot for restricted app during WorkTime: " + application.getPkg());
                            continue;
                        }
                        // Delay start of each application to 5 sec
                        try {
                            Thread.sleep(PAUSE_BETWEEN_AUTORUNS_SEC * 1000);
                        } catch (InterruptedException e) {
                        }
                        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(application.getPkg());
                        if (launchIntent != null) {
                            startActivity(launchIntent);
                            appStarted = true;
                        }
                    }
                }
                // Hide apps after start to avoid users confusion
                if (appStarted && !config.isAutostartForeground()) {
                    try {
                        Thread.sleep(PAUSE_BETWEEN_AUTORUNS_SEC * 1000);
                    } catch (InterruptedException e) {
                    }
                    // Notice: if MainActivity will be destroyed after running multiple apps at startup,
                    // we can get the looping here, because startActivity will create a new instance!
                    // That's why we put a boolean extra preventing apps from start
                    Intent intent = new Intent(MainActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    intent.putExtra(Const.RESTORED_ACTIVITY, true);
                    startActivity(intent);
                }

                return null;
            }
        }.execute();

    }

    // Does not seem to work, though. See the comment to SystemUtils.becomeDeviceOwner()
    private void setSelfAsDeviceOwner() {
        // We set self as device owner each time so we could trace errors if device owner setup fails
        if (Utils.isDeviceOwner(this)) {
            checkAndStartLauncher();
            return;
        }

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                if (!SystemUtils.becomeDeviceOwnerByCommand(MainActivity.this)) {
                    SystemUtils.becomeDeviceOwnerByXmlFile(MainActivity.this);
                };
                return null;
            }

            @Override
            protected void onPostExecute(Void v) {
                setDefaultLauncherEarly();
            }
        }.execute();
    }

    private void startServices() {
        // Foreground apps checks are not available in a free version: services are the stubs
        if (preferences.getInt(Const.PREFERENCES_USAGE_STATISTICS, Const.PREFERENCES_OFF) == Const.PREFERENCES_ON) {
            startService(new Intent(MainActivity.this, CheckForegroundApplicationService.class));
        }
        if (BuildConfig.USE_ACCESSIBILITY &&
            preferences.getInt(Const.PREFERENCES_ACCESSIBILITY_SERVICE, Const.PREFERENCES_OFF) == Const.PREFERENCES_ON) {
            startService(new Intent(MainActivity.this, CheckForegroundAppAccessibilityService.class));
        }
        StatusControlService.start(MainActivity.this);

        // Moved to onResume!
        // https://stackoverflow.com/questions/51863600/java-lang-illegalstateexception-not-allowed-to-start-service-intent-from-activ
        startService(new Intent(MainActivity.this, PluginApiService.class));

        // Send pending logs to server
        RemoteLogger.resetState();
        RemoteLogger.sendLogsToServer(MainActivity.this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (Utils.isDeviceOwner(this)) {
                // Even in device owner mode, if "Ask for location" is requested by the admin,
                // let's ask permissions (so do nothing here, fall through)
                if (settingsHelper.getConfig() == null || !ServerConfig.APP_PERMISSIONS_ASK_ALL.equals(settingsHelper.getConfig().getAppPermissions()) &&
                        !ServerConfig.APP_PERMISSIONS_ASK_LOCATION.equals(settingsHelper.getConfig().getAppPermissions())) {
                    // This may be called on Android 10, not sure why; just continue the flow
                    Log.i(Const.LOG_TAG, "Called onRequestPermissionsResult: permissions=" + Arrays.toString(permissions) +
                            ", grantResults=" + Arrays.toString(grantResults));
                    if (hasRequiredPhonePermissions()) {
                        // Remove a stale permissions dialog shown by the fallback
                        // while the system permission prompt was on screen
                        dismissDialog(permissionsDialog);
                    }
                    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
                    return;
                }
            }

            boolean locationDisabled = false;
            for (int n = 0; n < permissions.length; n++) {
                if (permissions[n].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    if (grantResults[n] != PackageManager.PERMISSION_GRANTED) {
                        // The user didn't allow to determine location, this is not critical, just ignore it
                        preferences.edit().putInt(Const.PREFERENCES_DISABLE_LOCATION, Const.PREFERENCES_ON).commit();
                        locationDisabled = true;
                    }
                }
            }

            boolean requestPermissions = false;
            for (int n = 0; n < permissions.length; n++) {
                if (grantResults[n] != PackageManager.PERMISSION_GRANTED) {
                    if (permissions[n].equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION) &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || locationDisabled)) {
                        // Background location is not available on Android 9 and below
                        // Also we don't need to grant background location permission if we don't grant location at all
                        continue;
                    }

                    if (permissions[n].equals(Manifest.permission.ACCESS_FINE_LOCATION) &&
                            locationDisabled) {
                        // Skip fine location permission if user intentionally disabled it
                        continue;
                    }

                    if (permissions[n].equals(Manifest.permission.READ_CALL_LOG) ||
                            permissions[n].equals(Manifest.permission.READ_SMS)) {
                        // These are hard-restricted since Android 10: the system silently
                        // denies them on non-whitelisted installs, so re-showing the
                        // permissions dialog for them would loop forever. Call log / SMS
                        // upload degrade gracefully without them.
                        continue;
                    }

                    // Let user know that he need to grant permissions
                     requestPermissions = true;
                }
            }

            if (requestPermissions) {
                createAndShowPermissionsDialog();
            } else {
                // All mandatory permissions granted: remove a stale dialog which may have
                // been shown by the fallback while the system prompt was on screen
                dismissDialog(permissionsDialog);
                if (BuildConfig.ENABLE_SMS_LOG) {
                    SmsLogUploadWorker.schedule(this);
                }
            }
        }
    }

    // AdminReceiver may be called later than onCreate() and onResume()
    // so the launcher setup and other methods requiring device owner permissions may fail
    // Here we wait up to 10 seconds until the app gets the device owner permissions
    private void waitForProvisioning(int attempts) {
        if (Utils.isDeviceOwner(this) || attempts <= 0) {
            setDefaultLauncherEarly();
        } else {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    waitForProvisioning(attempts - 1);
                }
            }, 1000);
        }
    }

    private void setDefaultLauncherEarly() {
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        if (BuildConfig.SET_DEFAULT_LAUNCHER_EARLY && config == null && Utils.isDeviceOwner(this)) {
            // At first start, temporarily set Brother Pharmamach MDM as a default launcher
            // to prevent the user from clicking Home to stop running Brother Pharmamach MDM
            String defaultLauncher = Utils.getDefaultLauncher(this);

            // As per the documentation, setting the default preferred activity should not be done on the main thread
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... voids) {
                    if (!getPackageName().equalsIgnoreCase(defaultLauncher)) {
                        Utils.setDefaultLauncher(MainActivity.this);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void v) {
                    checkAndStartLauncher();
                }
            }.execute();
            return;
        }
        checkAndStartLauncher();
    }

    private void checkAndStartLauncher() {

        boolean deviceOwner = Utils.isDeviceOwner(this);
        preferences.edit().putInt(Const.PREFERENCES_DEVICE_OWNER, deviceOwner ?
            Const.PREFERENCES_ON : Const.PREFERENCES_OFF).commit();

        int miuiPermissionMode = preferences.getInt(Const.PREFERENCES_MIUI_PERMISSIONS, -1);
        if (miuiPermissionMode == -1) {
            preferences.
                    edit().
                    putInt( Const.PREFERENCES_MIUI_PERMISSIONS, Const.PREFERENCES_ON ).
                    commit();
            if (checkMiuiPermissions(Const.MIUI_PERMISSIONS)) {
                // Permissions dialog opened, break the flow!
                return;
            }
        }

        int miuiDeveloperMode = preferences.getInt(Const.PREFERENCES_MIUI_DEVELOPER, -1);
        if (miuiDeveloperMode == -1) {
            preferences.
                    edit().
                    putInt( Const.PREFERENCES_MIUI_DEVELOPER, Const.PREFERENCES_ON ).
                    commit();
            if (checkMiuiPermissions(Const.MIUI_DEVELOPER)) {
                // Permissions dialog opened, break the flow!
                return;
            }
        }

        int miuiOptimizationMode = preferences.getInt(Const.PREFERENCES_MIUI_OPTIMIZATION, -1);
        if (miuiOptimizationMode == -1) {
            preferences.
                    edit().
                    putInt( Const.PREFERENCES_MIUI_OPTIMIZATION, Const.PREFERENCES_ON ).
                    commit();
            if (checkMiuiPermissions(Const.MIUI_OPTIMIZATION)) {
                // Permissions dialog opened, break the flow!
                return;
            }
        }

        int unknownSourceMode = preferences.getInt(Const.PREFERENCES_UNKNOWN_SOURCES, -1);
        if (!deviceOwner && unknownSourceMode == -1) {
            if (checkUnknownSources()) {
                preferences.
                        edit().
                        putInt( Const.PREFERENCES_UNKNOWN_SOURCES, Const.PREFERENCES_ON ).
                        commit();
            } else {
                return;
            }
        }

        int administratorMode = preferences.getInt( Const.PREFERENCES_ADMINISTRATOR, - 1 );
//        RemoteLogger.log(this, Const.LOG_DEBUG, "Saved device admin state: " + administratorMode);
        if ( administratorMode == -1 ) {
            if (checkAdminMode()) {
                RemoteLogger.log(this, Const.LOG_DEBUG, "Saving device admin state as 1 (TRUE)");
                preferences.
                        edit().
                        putInt( Const.PREFERENCES_ADMINISTRATOR, Const.PREFERENCES_ON ).
                        commit();
            } else {
                return;
            }
        }

        int overlayMode = preferences.getInt( Const.PREFERENCES_OVERLAY, - 1 );
        if (ProUtils.isPro() && overlayMode == -1 && needRequestOverlay()) {
            if ( checkAlarmWindow() ) {
                preferences.
                        edit().
                        putInt( Const.PREFERENCES_OVERLAY, Const.PREFERENCES_ON ).
                        commit();
            } else {
                return;
            }
        }

        int usageStatisticsMode = preferences.getInt( Const.PREFERENCES_USAGE_STATISTICS, - 1 );
        if (ProUtils.isPro() && usageStatisticsMode == -1 && needRequestUsageStats()) {
            if ( checkUsageStatistics() ) {
                preferences.
                        edit().
                        putInt( Const.PREFERENCES_USAGE_STATISTICS, Const.PREFERENCES_ON ).
                        commit();

                // If usage statistics is on, there's no need to turn on accessibility services
                preferences.
                        edit().
                        putInt( Const.PREFERENCES_ACCESSIBILITY_SERVICE, Const.PREFERENCES_OFF ).
                        commit();
            } else {
                return;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int manageStorageMode = preferences.getInt(Const.PREFERENCES_MANAGE_STORAGE, -1);
            if (manageStorageMode == -1) {
                if (checkManageStorage()) {
                    preferences.
                            edit().
                            putInt(Const.PREFERENCES_MANAGE_STORAGE, Const.PREFERENCES_ON).
                            commit();
                } else {
                    return;
                }
            }
        }

        int accessibilityService = preferences.getInt( Const.PREFERENCES_ACCESSIBILITY_SERVICE, - 1 );
        // Check the same condition as for usage stats here
        // because accessibility is used as a secondary condition when usage stats is not available
        if (ProUtils.isPro() && BuildConfig.USE_ACCESSIBILITY && accessibilityService == -1 && needRequestUsageStats()) {
            if ( checkAccessibilityService() ) {
                preferences.
                        edit().
                        putInt( Const.PREFERENCES_ACCESSIBILITY_SERVICE, Const.PREFERENCES_ON ).
                        commit();
            } else if (Utils.isDeviceOwner(this)) {
                // Device owners can auto-enable the accessibility service via Settings.Secure
                try {
                    String componentName = getPackageName()
                            + "/com.brother.pharmach.mdm.launcher.pro.service.CheckForegroundAppAccessibilityService";
                    String enabledServices = Settings.Secure.getString(
                            getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    if (enabledServices == null || enabledServices.isEmpty()) {
                        Settings.Secure.putString(
                                getContentResolver(),
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                componentName);
                    } else if (!enabledServices.contains(componentName)) {
                        Settings.Secure.putString(
                                getContentResolver(),
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                enabledServices + ":" + componentName);
                    }
                    Settings.Secure.putInt(
                            getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    preferences.
                            edit().
                            putInt( Const.PREFERENCES_ACCESSIBILITY_SERVICE, Const.PREFERENCES_ON ).
                            commit();
                    startService(new Intent(MainActivity.this, CheckForegroundAppAccessibilityService.class));
                    Log.d(Const.LOG_TAG, "WorkTime accessibility service auto-enabled for device owner");
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "Failed to auto-enable accessibility service", e);
                    createAndShowAccessibilityServiceDialog();
                    return;
                }
            } else {
                createAndShowAccessibilityServiceDialog();
                return;
            }
        }

        if (settingsHelper != null && settingsHelper.getConfig() != null &&
                ((settingsHelper.getConfig().getLockStatusBar() != null && settingsHelper.getConfig().getLockStatusBar()) ||
                settingsHelper.getConfig().isKioskMode())) {
            // If the admin requested status bar lock or kiosk mode is active, block the status bar and right bar (App list) expansion
            statusBarView = ProUtils.preventStatusBarExpansion(this);
            rightToolbarView = ProUtils.preventApplicationsList(this);
        }

        createApplicationNotAllowedScreen();
        createLockScreen();
        startLauncher();
    }

    private void createAndShowPermissionsDialog() {
        dismissDialog(permissionsDialog);
        permissionsDialog = new Dialog( this );
        dialogPermissionsBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_permissions,
                null,
                false );
        permissionsDialog.setCancelable( false );
        permissionsDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );

        String missingPermissions = getMissingMandatoryPermissionLabels();
        if (!missingPermissions.isEmpty()) {
            dialogPermissionsBinding.missingPermissions.setText(missingPermissions);
            dialogPermissionsBinding.missingPermissions.setVisibility(View.VISIBLE);
        }

        permissionsDialog.setContentView( dialogPermissionsBinding.getRoot() );
        permissionsDialog.show();
    }

    // Human-readable list of the mandatory permissions which are still not granted,
    // shown in the permissions dialog so the user knows what exactly to enable
    private String getMissingMandatoryPermissionLabels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "";
        }

        List<String> missing = new LinkedList<>();
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        if (preferences.getInt(Const.PREFERENCES_DISABLE_LOCATION, Const.PREFERENCES_OFF) != Const.PREFERENCES_ON) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
        }

        PackageManager pm = getPackageManager();
        StringBuilder sb = new StringBuilder();
        for (String permission : missing) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            String label;
            try {
                label = pm.getPermissionInfo(permission, 0).loadLabel(pm).toString();
            } catch (Exception e) {
                label = permission.substring(permission.lastIndexOf('.') + 1);
            }
            sb.append("• ").append(label);
        }
        return sb.toString();
    }

    public void permissionsRetryClicked(View view) {
        dismissDialog(permissionsDialog);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasRequiredPhonePermissions()) {
            // Some OEM ROMs suppress runtime permission popups after QR provisioning,
            // so force open app settings as a fallback.
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            try {
                startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
                startLauncher();
            }
            return;
        }

        startLauncher();
    }

    public void permissionsExitClicked(View view) {
        dismissDialog(permissionsDialog);
        finish();
    }

    private void createAndShowAccessibilityServiceDialog() {
        dismissDialog(accessibilityServiceDialog);
        accessibilityServiceDialog = new Dialog( this );
        dialogAccessibilityServiceBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_accessibility_service,
                null,
                false );
        dialogAccessibilityServiceBinding.hint.setText(
                getString(R.string.dialog_accessibility_service_message, getString(R.string.white_app_name)));
        accessibilityServiceDialog.setCancelable( false );
        accessibilityServiceDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );

        accessibilityServiceDialog.setContentView( dialogAccessibilityServiceBinding.getRoot() );
        accessibilityServiceDialog.show();
    }

    public void skipAccessibilityService( View view ) {
        try { accessibilityServiceDialog.dismiss(); }
        catch ( Exception e ) { e.printStackTrace(); }
        accessibilityServiceDialog = null;

        preferences.
                edit().
                putInt( Const.PREFERENCES_ACCESSIBILITY_SERVICE, Const.PREFERENCES_OFF ).
                commit();

        checkAndStartLauncher();
    }

    public void setAccessibilityService( View view ) {
        try { accessibilityServiceDialog.dismiss(); }
        catch ( Exception e ) { e.printStackTrace(); }
        accessibilityServiceDialog = null;

        Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivityForResult(intent, REQUEST_CODE_ACCESSIBILITY_SETTINGS);
    }

    // Accessibility services are needed in the Pro-version only
    private boolean checkAccessibilityService() {
        return ProUtils.checkAccessibilityService(this);
    }

    private void createLauncherButtons() {
        createExitButton();
        createInfoButton();
        createUpdateButton();
    }

    private void createButtons() {
        ServerConfig config = settingsHelper.getConfig();
        if (ProUtils.kioskModeRequired(this) && !getPackageName().equals(settingsHelper.getConfig().getMainApp())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    !Settings.canDrawOverlays( this ) &&
                    !BuildConfig.ENABLE_KIOSK_WITHOUT_OVERLAYS) {
                RemoteLogger.log(this, Const.LOG_WARN, "Kiosk mode disabled: no permission to draw over other windows.");
                Toast.makeText(this, getString(R.string.kiosk_mode_requires_overlays,
                        getString(R.string.white_app_name)), Toast.LENGTH_LONG).show();
                config.setKioskMode(false);
                settingsHelper.updateConfig(config);
                createLauncherButtons();
                return;
            }
            View kioskUnlockButton = null;
            if (config.isKioskExit()) {     // Should be true by default, but false on older web panel versions
                kioskUnlockButton = ProUtils.createKioskUnlockButton(this);
            }
            if (kioskUnlockButton != null) {
                kioskUnlockButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        kioskUnlockCounter++;
                        if (kioskUnlockCounter >= Const.KIOSK_UNLOCK_CLICK_COUNT) {
                            // We are in the main app: let's open launcher activity
                            interruptResumeFlow = true;
                            Intent restoreLauncherIntent = new Intent(MainActivity.this, MainActivity.class);
                            restoreLauncherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            startActivity(restoreLauncherIntent);
                            createAndShowEnterPasswordDialog();
                            kioskUnlockCounter = 0;
                        }
                    }
                });
            }
        } else {
            createLauncherButtons();
        }
    }

    private void startLauncher() {
        createButtons();

        if (configUpdater.isPendingAppInstall()) {
            // Here we go after completing the user confirmed app installation
            configUpdater.repeatDownloadApps();
        } else if ( !checkPermissions(true)) {
            // Permissions are requested inside checkPermissions, so do nothing here
            Log.i(Const.LOG_TAG, "startLauncher: requesting permissions");
            enforceMandatoryPermissionsDialogFallback();
        } else if (!settingsHelper.isBaseUrlSet() && BuildConfig.REQUEST_SERVER_URL) {
            // For common public version, here's an option to change the server
            createAndShowServerDialog(false, settingsHelper.getBaseUrl(), settingsHelper.getServerProject());
        } else if ( settingsHelper.getDeviceId().length() == 0 ) {
            Log.d(Const.LOG_TAG, "Device ID is empty");
            settingsHelper.setStartupSyncComplete(false);
            Utils.autoGrantPhonePermission(this);
            if (!SystemUtils.autoSetDeviceId(this)) {
                createAndShowEnterDeviceIdDialog(false, null);
            } else {
                // Retry after automatical setting of device ID
                // We shouldn't get looping here because autoSetDeviceId cannot return true if deviceId.length == 0
                startLauncher();
            }
        } else if (!configInitialized) {
            Log.i(Const.LOG_TAG, "Updating configuration in startLauncher()");
            ServerConfig currentConfig = settingsHelper.getConfig();
            boolean hasCachedConfig = currentConfig != null;
            boolean startupSyncComplete = settingsHelper.isStartupSyncComplete();
            boolean integratedProvisioningFlow = settingsHelper.isIntegratedProvisioningFlow();
            boolean forceForegroundInit = integratedProvisioningFlow && !startupSyncComplete;
            if (integratedProvisioningFlow) {
                // InitialSetupActivity just started and this is the first start after
                // the admin integrated provisioning flow, we need to show the process of loading apps
                // Notice the config is not null because it's preloaded in InitialSetupActivity
                settingsHelper.setIntegratedProvisioningFlow(false);
            }
            // Always prefer cached content over a blocking startup screen.
            if (hasCachedConfig) {
                showContent(currentConfig);
            }
            boolean userInteraction = !hasCachedConfig || forceForegroundInit;
            updateConfig(userInteraction);
        } else {
            showContent(settingsHelper.getConfig());
        }
    }

    private boolean checkAdminMode() {
        if (!Utils.checkAdminMode(this)) {
            createAndShowAdministratorDialog();
            return false;
        }
        return true;
    }

    private boolean needRequestUsageStats() {
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        if (config == null) {
            // The app hasn't been properly provisioned because
            // config should be initialized in a setup activity.
            // So we request permissions anyway.
            return true;
        }
        // Usage stats is only required to detect unwanted apps
        // when permissive mode is off and kiosk mode is also off
        return !config.isPermissive() && !config.isKioskMode();
    }

    // Access to usage statistics is required in the Pro-version only
    private boolean checkUsageStatistics() {
        if (!ProUtils.checkUsageStatistics(this)) {
            if (SystemUtils.autoSetUsageStatsPermission(this, getPackageName())) {
                // Permission auto granted, but we double check
                if (ProUtils.checkUsageStatistics(this)) {
                    return true;
                }
            }
            createAndShowHistorySettingsDialog();
            return false;
        }
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private boolean checkManageStorage() {
        if (!Environment.isExternalStorageManager()) {
            if (SystemUtils.autoSetStoragePermission(this, getPackageName())) {
                // Permission auto granted, but we double check
                if (Environment.isExternalStorageManager()) {
                    return true;
                }
            }
            createAndShowManageStorageDialog();
            return false;
        }
        return true;
    }

    private boolean needRequestOverlay() {
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        if (config == null) {
            // The app hasn't been properly provisioned because
            // config should be initialized in a setup activity.
            // So we request permissions anyway.
            return true;
        }
        if (config.isKioskMode() && config.isKioskExit()) {
            // We need to draw the kiosk exit button
            return true;
        }
        if (!config.isKioskMode() && !config.isPermissive()) {
            // Overlay window is required to block unwanted apps
            return true;
        }
        return false;
    }

    private void enforceOverlayPermission() {
        if (Utils.canDrawOverlays(this)) {
            return;
        }
        // For device owners, try to auto-grant silently first (works on most Android versions).
        if (Utils.isDeviceOwner(this)) {
            SystemUtils.autoSetOverlayPermission(this, getPackageName());
            if (Utils.canDrawOverlays(this)) {
                return;
            }
        }
        // Mandatory dialog — no skip button, user must grant the permission.
        // setCancelable(false) prevents back-button dismissal.
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(getString(R.string.overlay_permission_required_message,
                        getString(R.string.white_app_name)))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.overlay_permission_grant), (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.overlays_not_supported,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private boolean checkAlarmWindow() {
        if (ProUtils.isPro() && !Utils.canDrawOverlays(this)) {
            if (SystemUtils.autoSetOverlayPermission(this, getPackageName())) {
                // Permission auto granted, but we double check
                if (Utils.canDrawOverlays(this)) {
                    return true;
                }
            }
            createAndShowOverlaySettingsDialog();
            return false;
        } else {
            return true;
        }
    }

    private boolean checkMiuiPermissions(int screen) {
        // Permissions to open popup from background first appears in MIUI 11 (Android 9)
        // Also a workaround against https://qa.h-mdm.com/3119/
        if (Utils.isMiui(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_ENABLE_SETTINGS));
            createAndShowMiuiPermissionsDialog(screen);
            // It is not known how to check this setting programmatically, so return true
            return true;
        }
        return false;
    }

    private boolean checkUnknownSources() {
        if ( !Utils.canInstallPackages(this) ) {
            createAndShowUnknownSourcesDialog();
            return false;
        } else {
            return true;
        }
    }

    private WindowManager.LayoutParams overlayLockScreenParams() {
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = Utils.OverlayWindowType();
        layoutParams.gravity = Gravity.RIGHT;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT;
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        layoutParams.format = PixelFormat.TRANSPARENT;

        return layoutParams;
    }

    private void createApplicationNotAllowedScreen() {
        if ( applicationNotAllowed != null ) {
            return;
        }
        WindowManager manager = ((WindowManager)getApplicationContext().getSystemService(Context.WINDOW_SERVICE));

        applicationNotAllowed = LayoutInflater.from( this ).inflate( R.layout.layout_application_not_allowed, null );
        applicationNotAllowed.findViewById( R.id.layout_application_not_allowed_continue ).setOnClickListener( new View.OnClickListener() {
            @Override
            public void onClick( View v ) {
                enforceWorkTimeAsync(MainActivity.this, true);
                applicationNotAllowed.setVisibility( View.GONE );
            }
        } );
        applicationNotAllowed.findViewById( R.id.layout_application_not_allowed_admin ).setOnClickListener( new View.OnClickListener() {
            @Override
            public void onClick( View v ) {
                applicationNotAllowed.setVisibility( View.GONE );
                createAndShowEnterPasswordDialog();
            }
        } );
        final TextView tvPackageId = applicationNotAllowed.findViewById(R.id.package_id);
        tvPackageId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Package ID", tvPackageId.getText().toString());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(MainActivity.this, R.string.package_id_copied, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        applicationNotAllowed.setVisibility( View.GONE );

        try {
            manager.addView( applicationNotAllowed, overlayLockScreenParams() );
        } catch ( Exception e ) {
            // No permission to show overlays; let's try to add view to main view
            try {
                RelativeLayout root = findViewById(R.id.activity_main);
                root.addView(applicationNotAllowed);
            } catch ( Exception e1 ) {
                e1.printStackTrace();
            }
        }
    }

    private void createLockScreen() {
        if ( lockScreen != null ) {
            return;
        }

        WindowManager manager = ((WindowManager)getApplicationContext().getSystemService(Context.WINDOW_SERVICE));

        // Reuse existing "Application not allowed" screen but hide buttons
        lockScreen = LayoutInflater.from( this ).inflate( R.layout.layout_application_not_allowed, null );
        lockScreen.findViewById( R.id.layout_application_not_allowed_continue ).setVisibility(View.GONE);
        lockScreen.findViewById( R.id.layout_application_not_allowed_admin ).setVisibility(View.GONE);
        lockScreen.findViewById( R.id.package_id ).setVisibility(View.GONE);
        lockScreen.findViewById( R.id.message2 ).setVisibility(View.GONE);
        TextView textView = lockScreen.findViewById( R.id.message );
        textView.setText(getString(R.string.device_locked, SettingsHelper.getInstance(this).getDeviceId()));

        lockScreen.setVisibility( View.GONE );

        try {
            manager.addView( lockScreen, overlayLockScreenParams() );
        } catch ( Exception e ) {
            // No permission to show overlays; let's try to add view to main view
            try {
                RelativeLayout root = findViewById(R.id.activity_main);
                root.addView(lockScreen);
            } catch ( Exception e1 ) {
                e1.printStackTrace();
            }
        }
    }

    private boolean isDarkBackground() {
        try {
            ServerConfig config = settingsHelper.getConfig();
            if (config.getBackgroundColor() != null) {
                int color = Color.parseColor(config.getBackgroundColor());
                return !Utils.isLightColor(color);
            }
        } catch (Exception e) {
        }
        return true;
    }

    private ImageView createManageButton(int imageResource, int imageResourceBlack, int offset) {
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.addRule(RelativeLayout.CENTER_VERTICAL);
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);

        int offsetRight = 0;
        if (settingsHelper != null && settingsHelper.getConfig() != null && settingsHelper.getConfig().getLockStatusBar() != null && settingsHelper.getConfig().getLockStatusBar()) {
            // If we lock the right bar, let's shift buttons to avoid overlapping
            offsetRight = getResources().getDimensionPixelOffset(R.dimen.prevent_applications_list_width);
        }

        RelativeLayout view = new RelativeLayout(this);
        // Offset is multiplied by 2 because the view is centered. Yeah I know its an Induism)
        view.setPadding(0, offset * 2, offsetRight, 0);
        view.setLayoutParams(layoutParams);

        ImageView manageButton = new ImageView( this );
        manageButton.setImageResource(isDarkBackground() ? imageResource : imageResourceBlack);
        view.addView(manageButton);

        selectedManageButtonBorder.setColor(0); // transparent background
        selectedManageButtonBorder.setStroke(2, isDarkBackground() ? 0xa0ffffff : 0xa0000000); // white or black border with some transparency
        manageButton.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(hasFocus ? selectedManageButtonBorder : null);
        });

        try {
            RelativeLayout root = findViewById(R.id.activity_main);
            root.addView(view);
        } catch ( Exception e ) { e.printStackTrace(); }
        return manageButton;
    }

    private void createExitButton() {
        if ( exitView != null ) {
            return;
        }
        exitView = createManageButton(R.drawable.ic_vpn_key_opaque_24dp, R.drawable.ic_vpn_key_black_24dp, 0);
        exitView.setOnClickListener(view -> {
            if (view.hasFocus()) {
                // 6 subsequent taps within 3 secs open the hidden password view
                long now = System.currentTimeMillis();
                if (exitFirstTapTime < now - 3000) {
                    exitFirstTapTime = now;
                    exitTapCount = 1;
                } else {
                    exitTapCount++;
                    if (exitTapCount >= 6) {
                        exitFirstTapTime = 0;
                        exitTapCount = 0;
                        createAndShowEnterPasswordDialog();
                    }
                }
            }
        });
        exitView.setOnLongClickListener(this);
    }

    private void createInfoButton() {
        if ( infoView != null ) {
            return;
        }
        infoView = createManageButton(R.drawable.ic_info_opaque_24dp, R.drawable.ic_info_black_24dp,
                getResources().getDimensionPixelOffset(R.dimen.info_icon_margin));
        infoView.setOnClickListener(this);
    }

    private void createUpdateButton() {
        if ( updateView != null ) {
            return;
        }
        updateView = createManageButton(R.drawable.ic_system_update_opaque_24dp, R.drawable.ic_system_update_black_24dp,
                (int)(2.05f * getResources().getDimensionPixelOffset(R.dimen.info_icon_margin)));
        updateView.setOnClickListener(this);
    }

    // The userInteraction flag denotes whether the config has been updated from the UI or in the background
    // If this flag is set to true, network error dialog is displayed, and app update schedule is ignored
    private void updateConfig( final boolean userInteraction ) {
        needSendDeviceInfoAfterReconfigure = true;
        needRedrawContentAfterReconfigure = true;
        if (!orientationLocked && !BuildConfig.DISABLE_ORIENTATION_LOCK) {
            lockOrientation();
            orientationLocked = true;
        }
        configUpdater.updateConfig(this, this, userInteraction);
    }

    @Override
    public void onConfigUpdateStart() {
        binding.setMessage( getString( R.string.main_activity_update_config ) );
    }

    @Override
    public void onConfigUpdateServerError(String errorText) {
        if ( enterDeviceIdDialog != null ) {
            enterDeviceIdDialogBinding.setError( true );
            enterDeviceIdDialog.show();
        } else {
            networkErrorDetails = errorText;
            createAndShowEnterDeviceIdDialog( true, settingsHelper.getDeviceId() );
        }
    }

    @Override
    public void onConfigUpdateNetworkError(String errorText) {
        if (settingsHelper.getConfig() != null && !isContentShown()) {
            showContent(settingsHelper.getConfig());
        }
        if (ProUtils.isKioskModeRunning(this) && settingsHelper.getConfig() != null &&
                !getPackageName().equals(settingsHelper.getConfig().getMainApp())) {
            interruptResumeFlow = true;
            Intent restoreLauncherIntent = new Intent(MainActivity.this, MainActivity.class);
            restoreLauncherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(restoreLauncherIntent);
        }
        // Do not show the reset button if the launcher is installed by scanning a QR code
        // Only show the reset button on manual setup at first start (when config is not yet loaded)
        createAndShowNetworkErrorDialog(settingsHelper.getBaseUrl(), settingsHelper.getServerProject(), errorText,
                settingsHelper.getConfig() == null && !settingsHelper.isQrProvisioning(),
                settingsHelper.getConfig() == null || (settingsHelper.getConfig() != null && settingsHelper.getConfig().isShowWifi()));
    }

    @Override
    public void onConfigLoaded() {
        settingsHelper.setStartupSyncComplete(true);
        applyEarlyPolicies(settingsHelper.getConfig());
    }

    @Override
    public void onPoliciesUpdated() {
    }

    @Override
    public void onFileDownloading(RemoteFile remoteFile) {
        handler.post( new Runnable() {
            @Override
            public void run() {
                binding.setMessage(getString(R.string.main_file_downloading) + " " + remoteFile.getPath());
                binding.setDownloading( true );
            }
        } );
    }

    @Override
    public void onDownloadProgress(final int progress, final long total, final long current) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                binding.progress.setMax(100);
                binding.progress.setProgress(progress);

                binding.setFileLength(total);
                binding.setDownloadedLength(current);
            }
        });
    }

    @Override
    public void onFileDownloadError(RemoteFile remoteFile) {
        if (!ProUtils.kioskModeRequired(this) && !isContentShown()) {
            // Notify the error dialog that we're downloading a file, not an app
            downloadingFile = true;
            createAndShowFileNotDownloadedDialog(remoteFile.getUrl(), null);
            binding.setDownloading( false );
        } else {
            // Avoid user interaction in kiosk mode, just ignore download error and keep the old version
            // Also, avoid unexpected messages when the user is seeing the desktop
            configUpdater.skipDownloadFiles();
        }
    }

    @Override
    public void onFileInstallError(RemoteFile remoteFile) {
        if (!ProUtils.kioskModeRequired(MainActivity.this) && !isContentShown()) {
            try {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(getString(R.string.file_create_error) + " " + remoteFile.getPath())
                        .setPositiveButton(R.string.dialog_administrator_mode_continue, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                configUpdater.skipDownloadFiles();
                            }
                        })
                        .create()
                        .show();
            } catch (Exception e) {
                // Activity closed before showing a dialog, just ignore this exception
                e.printStackTrace();
            }
        } else {
            // Avoid user interaction in kiosk mode, just ignore download error and keep the old version
            // Also, avoid unexpected messages when the user is seeing the desktop
            configUpdater.skipDownloadFiles();
        }
    }

    @Override
    public void onAppUpdateStart() {
        binding.setMessage( getString( R.string.main_activity_applications_update ) );
        configInitialized = true;
    }

    @Override
    public void onAppInstalling(final Application application) {
        handler.post( new Runnable() {
            @Override
            public void run() {
                binding.setMessage(getString(R.string.main_app_installing) + " " + application.getName());
                binding.setDownloading( false );
            }
        } );
    }

    @Override
    public void onAppDownloadError(Application application, String error) {
        if (!ProUtils.kioskModeRequired(MainActivity.this) && !isContentShown()) {
            // Notify the error dialog that we're downloading an app
            downloadingFile = false;
            createAndShowFileNotDownloadedDialog(application.getName(), error);
            binding.setDownloading( false );
        } else {
            // Avoid user interaction in kiosk mode, just ignore download error and keep the old version
            // Also, avoid unexpected messages when the user is seeing the desktop
            configUpdater.skipDownloadApps();
        }
    }

    @Override
    public void onAppInstallError(String packageName) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!ProUtils.kioskModeRequired(MainActivity.this) && !isContentShown()) {

                    try {
                        new AlertDialog.Builder(MainActivity.this)
                                .setMessage(getString(R.string.install_error) + " " + packageName)
                                .setPositiveButton(R.string.dialog_administrator_mode_continue, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        configUpdater.repeatDownloadApps();
                                    }
                                })
                                .create()
                                .show();
                    } catch (Exception e) {
                        // Activity closed before showing a dialog, just ignore this exception
                        e.printStackTrace();
                    }
                } else {
                    // Avoid unexpected messages when the config is updated "silently"
                    // (in kiosk mode or when user is seeing the desktop
                    configUpdater.repeatDownloadApps();
                }
            }
        });
    }

    @Override
    public void onAppInstallComplete(String packageName) {

    }

    @Override
    public void onConfigUpdateComplete() {
        configInitialized = true;
        settingsHelper.setStartupSyncComplete(true);
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(Const.PREFERENCES, MODE_PRIVATE);
        String deviceAdminLog = PreferenceLogger.getLogString(preferences);
        if (deviceAdminLog != null && !deviceAdminLog.equals("")) {
            RemoteLogger.log(this, Const.LOG_DEBUG, deviceAdminLog);
            PreferenceLogger.clearLogString(preferences);
        }
        Log.i(Const.LOG_TAG, "Showing content from setActions()");
        settingsHelper.refreshConfig(this);         // Avoid NPE in showContent()
        showContent(settingsHelper.getConfig());
    }

    @Override
    public void onAllAppInstallComplete() {
        Log.i(Const.LOG_TAG, "Refreshing content - new apps installed");
        settingsHelper.refreshConfig(this);         // Avoid NPE in showContent()
        handler.post(new Runnable() {
            @Override
            public void run() {
                showContent(settingsHelper.getConfig());
            }
        });
    }

    @Override
    public void onAppDownloading(final Application application) {
        handler.post( new Runnable() {
            @Override
            public void run() {
                binding.setMessage(getString(R.string.main_app_downloading) + " " + application.getName());
                binding.setDownloading(true);
            }
        } );
    }

    @Override
    public void onAppRemoving(final Application application) {
        handler.post( new Runnable() {
            @Override
            public void run() {
                binding.setMessage(getString(R.string.main_app_removing) + " " + application.getName());
                binding.setDownloading(false);
            }
        } );
    }

    private boolean applyEarlyPolicies(ServerConfig config) {
        // Issue 7 Plan C: run non-interactive policies on a background thread to avoid
        // blocking the main thread with Bluetooth/volume/brightness/Settings.Secure I/O
        final Context appContext = getApplicationContext();
        POLICY_EXECUTOR.execute(() -> Initializer.applyEarlyNonInteractivePolicies(appContext, config));
        return true;
    }

    // Network policies are applied after getting all applications
    // These are interactive policies so can't be used when in background mode
    private boolean applyLatePolicies(ServerConfig config) {
        // To delay opening the settings activity
        boolean dialogWillShow = false;

        if (config.getGps() != null) {
            LocationManager lm = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                boolean enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
                if (config.getGps() && !enabled) {
                    dialogWillShow = true;
                    // System settings dialog should return result so we could re-initialize location service
                    postDelayedSystemSettingDialog(getString(R.string.message_turn_on_gps),
                            new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), REQUEST_CODE_GPS_STATE_CHANGE);

                } else if (!config.getGps() && enabled) {
                    dialogWillShow = true;
                    postDelayedSystemSettingDialog(getString(R.string.message_turn_off_gps),
                            new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), REQUEST_CODE_GPS_STATE_CHANGE);
                }
            }
        }

        if (config.getMobileData() != null && !Utils.isSimAbsent(this)) {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && !dialogWillShow) {
                try {
                    boolean enabled = Utils.isMobileDataEnabled(this);
                    // Mobile data are turned on/off in the status bar! No settings (as the user can go back in settings and do something nasty)
                    if (config.getMobileData() && !enabled) {
                        postDelayedMobileDataDialog(true);
                    } else if (!config.getMobileData() && enabled) {
                        postDelayedMobileDataDialog(false);
                    }
                } catch (Exception e) {
                    // Some problem accessible private API
                }
            }
        }

        if (!Utils.setPasswordMode(config.getPasswordMode(), this)) {
            Intent updatePasswordIntent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
            // Different Android versions/builds use different activities to setup password
            // So we have to enable temporary access to settings here (and only here!)
            postDelayedSystemSettingDialog(getString(R.string.message_set_password), updatePasswordIntent, null, true);
        }
        return true;
    }

    private boolean isContentShown() {
        if (binding != null) {
            return binding.getShowContent() != null && binding.getShowContent();
        }
        return false;
    }

    /**
     * Issue 7: runs enforceWorkTimeRestrictions() on the background policy executor
     * (avoids blocking the main thread with PackageManager iteration + DPM IPC calls),
     * then optionally brings the launcher to the foreground on the main thread.
     */
    /**
     * Show/refresh the "return to call" banner. Guarded at API 23 (the call stack does not exist
     * below that); {@link com.brother.pharmach.mdm.launcher.phone.OngoingCallBanner} keeps it in
     * sync with the live call and re-opens the call screen on tap.
     */
    private void startOngoingCallBanner() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            View banner = findViewById(R.id.ongoing_call_banner);
            android.widget.TextView text = findViewById(R.id.ongoing_call_text);
            if (banner == null) {
                return;
            }
            if (ongoingCallBanner == null) {
                ongoingCallBanner = new com.brother.pharmach.mdm.launcher.phone.OngoingCallBanner(
                        this, banner, text);
            }
            ongoingCallBanner.start();
            // A still-ringing call must not be left behind the launcher — pull it back to front.
            ongoingCallBanner.returnIfRinging();
        } catch (Throwable t) {
            Log.w(Const.LOG_TAG, "startOngoingCallBanner failed: " + t.getMessage());
        }
    }

    private void stopOngoingCallBanner() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ongoingCallBanner == null) {
            return;
        }
        try {
            ongoingCallBanner.stop();
        } catch (Throwable ignored) {
        }
    }

    private void enforceWorkTimeAsync(Context context, boolean bringToFront) {
        POLICY_EXECUTOR.execute(() -> {
            // This lambda runs via ExecutorService.execute() (not a scheduled executor), so any
            // uncaught exception here propagates to the global uncaught-exception handler, which
            // calls System.exit(0). Because this fires every 60s (TIME_TICK), that would show up
            // as a periodic "crash then recover". Guard the whole body defensively.
            try {
                com.brother.pharmach.mdm.launcher.util.WorkTimeManager.getInstance()
                        .enforceWorkTimeRestrictions(context);
            } catch (Throwable t) {
                RemoteLogger.log(context, Const.LOG_ERROR,
                        "enforceWorkTimeRestrictions failed: " + t.getClass().getSimpleName()
                                + ": " + t.getMessage());
                Log.e(Const.LOG_TAG, "enforceWorkTimeRestrictions failed", t);
            }
            if (bringToFront) {
                handler.post(() -> {
                    try {
                        Intent launchSelf = new Intent(MainActivity.this, MainActivity.class);
                        launchSelf.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launchSelf);
                    } catch (Throwable t) {
                        // Background-activity-start can be blocked/throw on Android 10+ and some OEMs.
                        Log.w(Const.LOG_TAG, "enforceWorkTimeAsync: bringToFront startActivity failed", t);
                    }
                });
            }
        });
    }

    /**
     * Issue 7: debounced wrapper for showContent() — use this from periodic/broadcast paths
     * (TIME_TICK, userPresent, connectivity) to prevent excessive main-thread work.
     * Direct calls from user-driven or config-driven paths should still use showContent() directly.
     */
    private void showContentDebounced(ServerConfig config) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastShowContentMs < SHOW_CONTENT_DEBOUNCE_MS) {
            return;
        }
        lastShowContentMs = now;
        showContent(config);
    }

    /** Collects the launchable package names currently shown by an adapter into {@code out}. */
    private void collectPackages(BaseAppListAdapter adapter, java.util.Set<String> out) {
        if (adapter == null) {
            return;
        }
        collectPackages(adapter.items, out);
    }

    private void collectPackages(java.util.List<com.brother.pharmach.mdm.launcher.util.AppInfo> apps, java.util.Set<String> out) {
        if (apps == null) {
            return;
        }
        for (com.brother.pharmach.mdm.launcher.util.AppInfo info : apps) {
            if (info != null && info.type == com.brother.pharmach.mdm.launcher.util.AppInfo.TYPE_APP
                    && info.packageName != null && !info.packageName.trim().isEmpty()) {
                out.add(info.packageName);
            }
        }
    }

    /**
     * Measures the real rendered height of one app-grid cell (icon at the configured size +
     * a worst-case 2-line label + its margins/padding), so the page row count fits the available
     * height exactly on any device/density instead of relying on a fixed estimate.
     */
    private int measureAppCellHeight(int cellWidth, int iconPx) {
        View sample = getLayoutInflater().inflate(R.layout.item_app, binding.activityMainPager, false);
        ImageView iv = sample.findViewById(R.id.imageView);
        if (iv != null && iconPx > 0) {
            iv.getLayoutParams().width = iconPx;
            iv.getLayoutParams().height = iconPx;
        }
        android.widget.TextView tv = sample.findViewById(R.id.textView);
        if (tv != null) {
            tv.setLines(2);
        }
        sample.measure(
                View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = sample.getMeasuredHeight();
        ViewGroup.LayoutParams lp = sample.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            height += mlp.topMargin + mlp.bottomMargin;
        }
        return Math.max(1, height);
    }

    /**
     * A compact fingerprint of a rendered app list — order-sensitive and includes label/icon so
     * any real change is detected, but ignores volatile state so identical lists compare equal.
     * Used to skip needless re-renders.
     */
    private String appsSignature(List<com.brother.pharmach.mdm.launcher.util.AppInfo> apps) {
        if (apps == null || apps.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(apps.size() * 24);
        for (com.brother.pharmach.mdm.launcher.util.AppInfo a : apps) {
            if (a != null) {
                sb.append(a.signature()).append(';');
            }
        }
        return sb.toString();
    }

    /**
     * Updates the dots only when the page count actually changed, so a smooth in-place content
     * update doesn't rebuild (flash) the indicator.
     */
    private void syncPageIndicator(int count) {
        int shown = binding.pageIndicator.getChildCount();
        int expected = count <= 1 ? (count == 1 ? 1 : 0) : count;
        if (shown != expected) {
            buildPageIndicator(count);
        }
    }

    /**
     * (Re)builds the page-indicator dots. Hidden entirely when there are no pages.
     */
    private void buildPageIndicator(int count) {
        android.widget.LinearLayout indicator = binding.pageIndicator;
        indicator.removeAllViews();
        if (count < 1) {
            indicator.setVisibility(View.GONE);
            return;
        }
        indicator.setVisibility(View.VISIBLE);
        int dot = (int) (8 * getResources().getDisplayMetrics().density);
        int gap = (int) (4 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < count; i++) {
            ImageView iv = new ImageView(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(dot, dot);
            lp.leftMargin = gap;
            lp.rightMargin = gap;
            iv.setLayoutParams(lp);
            iv.setImageResource(R.drawable.page_dot_unselected);
            indicator.addView(iv);
        }
    }

    private void updatePageIndicator(int selected) {
        android.widget.LinearLayout indicator = binding.pageIndicator;
        for (int i = 0; i < indicator.getChildCount(); i++) {
            View child = indicator.getChildAt(i);
            if (child instanceof ImageView) {
                ((ImageView) child).setImageResource(i == selected
                        ? R.drawable.page_dot_selected
                        : R.drawable.page_dot_unselected);
            }
        }
    }

    private void showContent(ServerConfig config ) {
        if (!applyEarlyPolicies(config)) {
            // Here we go when the settings window is opened;
            // Next time we're here after we returned from the Android settings through onResume()
            return;
        }
        applyLatePolicies(config);
        applyWorkTimeSettingsRestriction();

        sendDeviceInfoAfterReconfigure();
        scheduleDeviceInfoSending();
        scheduleInstalledAppsRun();

        if (config.getLock() != null && config.getLock()) {
            showLockScreen();
            return;
        } else {
            hideLockScreen();
        }

        // Run default launcher option
        if (config.getRunDefaultLauncher() != null && config.getRunDefaultLauncher() &&
            !getPackageName().equals(Utils.getDefaultLauncher(this)) && !Utils.isLauncherIntent(getIntent())) {
            openDefaultLauncher();
            return;
        }

        if (orientationLocked && !BuildConfig.DISABLE_ORIENTATION_LOCK) {
            Utils.setOrientation(this, config);
            orientationLocked = false;
        }

        if (ProUtils.kioskModeRequired(this)) {
            String kioskApp = settingsHelper.getConfig().getMainApp();
            if (kioskApp != null && kioskApp.trim().length() > 0 &&
                    // If Brother Pharmamach MDM itself is set as kiosk app, the kiosk mode is already turned on;
                    // So here we just proceed to drawing the content
                    (!kioskApp.equals(getPackageName()) || !ProUtils.isKioskModeRunning(this))) {
                if (ProUtils.getKioskAppIntent(kioskApp, this) != null && startKiosk(kioskApp)) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    return;
                } else {
                    Log.e(Const.LOG_TAG, "Kiosk mode failed, proceed with the default flow");
                }
            } else {
                if (kioskApp != null && kioskApp.equals(getPackageName()) && ProUtils.isKioskModeRunning(this)) {
                    // Here we go if the configuration is changed when launcher is in the kiosk mode
                    ProUtils.updateKioskAllowedApps(kioskApp, this, false);
                    ProUtils.updateKioskOptions(this);
                } else {
                    Log.e(Const.LOG_TAG, "Kiosk mode disabled: please setup the main app!");
                }
            }
        } else {
            if (ProUtils.isKioskModeRunning(this)) {
                // Turn off kiosk and show desktop if it is turned off in the configuration
                ProUtils.unlockKiosk(this);
                openDefaultLauncher();
            }
        }

        // TODO: Somehow binding is null here which causes a crash. Not sure why this could happen.
        if ( config.getBackgroundColor() != null ) {
            try {
                binding.activityMainContentWrapper.setBackgroundColor(Color.parseColor(config.getBackgroundColor()));
            } catch (Exception e) {
                // Invalid color
                e.printStackTrace();
                binding.activityMainContentWrapper.setBackgroundColor( getResources().getColor(R.color.defaultBackground));
            }
        } else {
            binding.activityMainContentWrapper.setBackgroundColor( getResources().getColor(R.color.defaultBackground));
        }
        updateTitle(config);

        statusBarUpdater.updateControlsState(config.isDisplayStatus(), isDarkBackground());

        if (mainAppListAdapter == null || needRedrawContentAfterReconfigure) {
            needRedrawContentAfterReconfigure = false;

            if ( config.getBackgroundImageUrl() != null && config.getBackgroundImageUrl().length() > 0 ) {
                if (picasso == null) {
                    // Initialize it once because otherwise it doesn't work offline
                    Picasso.Builder builder = new Picasso.Builder(this);
                    if (BuildConfig.TRUST_ANY_CERTIFICATE) {
                        builder.downloader(new OkHttp3Downloader(UnsafeOkHttpClient.getUnsafeOkHttpClient()));
                    } else {
                        // Add signature to all requests to protect against unauthorized API calls
                        // For TRUST_ANY_CERTIFICATE, we won't add signatures because it's unsafe anyway
                        // and is just a workaround to use Brother Pharmamach MDM on the LAN
                        OkHttpClient clientWithSignature = new OkHttpClient.Builder()
                                .cache(new Cache(new File(getApplication().getCacheDir(), "image_cache"), 1000000L))
                                .addInterceptor(chain -> {
                                    okhttp3.Request.Builder requestBuilder = chain.request().newBuilder();
                                    String signature = InstallUtils.getRequestSignature(chain.request().url().toString());
                                    if (signature != null) {
                                        requestBuilder.addHeader("X-Request-Signature", signature);
                                    }
                                    return chain.proceed(requestBuilder.build());

                                })
                                .build();
                        builder.downloader(new OkHttp3Downloader(clientWithSignature));
                    }
                    builder.listener(new Picasso.Listener()
                    {
                        @Override
                        public void onImageLoadFailed(Picasso picasso, Uri uri, Exception exception)
                        {
                            // On fault, get the background image from the cache
                            // This is a workaround against a bug in Picasso: it doesn't display cached images by default!
                            picasso.load(config.getBackgroundImageUrl())
                                    .networkPolicy(NetworkPolicy.OFFLINE)
                                    .fit()
                                    .centerCrop()
                                    .into(binding.activityMainBackground);
                        }
                    });
                    picasso = builder.build();
                }

                picasso.load(config.getBackgroundImageUrl())
                    // fit and centerCrop is a workaround against a crash on too large images on some devices
                    .fit()
                    .centerCrop()
                    .into(binding.activityMainBackground);

            } else {
                binding.activityMainBackground.setImageDrawable(null);
            }

            Display display = getWindowManager().getDefaultDisplay();
            Point size = new Point();
            display.getSize(size);

            int width = size.x;
            int itemWidth = getResources().getDimensionPixelSize(R.dimen.app_list_item_size);

            spanCount = (int) (width * 1.0f / itemWidth);
            if (spanCount < 1) {
                spanCount = 1;
            }

            final int columns = spanCount;
            // Icon-aware cell size so pages stay responsive when the server scales the icon size.
            Integer iconScaleCfg = config.getIconSize();
            int iconScale = iconScaleCfg == null ? ServerConfig.DEFAULT_ICON_SIZE : iconScaleCfg;
            final int iconPx = getResources().getDimensionPixelOffset(R.dimen.app_icon_size) * iconScale / 100;
            final int fallbackHeight = (int) (size.y * 0.8f);
            final int screenWidth = size.x;
            final ServerConfig renderConfig = config;

            // Enumerating installed apps (PackageManager scan + per-app IPC + label loading) is
            // expensive and used to run on the main thread here, freezing the UI — the classic
            // "app becomes unresponsive" ANR. Do it on a background thread, then hop back to the
            // main thread to build the adapters/pager/dock (all view work stays on the UI thread).
            if (!contentLoadInProgress) {
                contentLoadInProgress = true;
                new Thread(() -> {
                    List<com.brother.pharmach.mdm.launcher.util.AppInfo> mainItems;
                    List<com.brother.pharmach.mdm.launcher.util.AppInfo> bottomItems;
                    try {
                        mainItems = AppShortcutManager.getInstance().getInstalledApps(this, false);
                        bottomItems = AppShortcutManager.getInstance().getInstalledApps(this, true);
                    } catch (Exception e) {
                        mainItems = new java.util.ArrayList<>();
                        bottomItems = new java.util.ArrayList<>();
                    }
                    final List<com.brother.pharmach.mdm.launcher.util.AppInfo> fMain = mainItems;
                    final List<com.brother.pharmach.mdm.launcher.util.AppInfo> fBottom = bottomItems;
                    runOnUiThread(() -> {
                        try {
                            if (!isFinishing() && !isDestroyed()) {
                                // Skip re-rendering entirely when the visible app set (and column
                                // count) is unchanged — most triggers (worktime ticks that don't
                                // change the allowed set, unrelated config refreshes) produce an
                                // identical list, and re-rendering those is the "keeps refreshing"
                                // bad UX. Render only on a real change, and then smoothly (diff).
                                String sig = appsSignature(fMain) + "##" + appsSignature(fBottom) + "##" + columns;
                                if (pagedAppListAdapter == null || !sig.equals(lastContentSignature)) {
                                    lastContentSignature = sig;
                                    renderAppContent(renderConfig, columns, iconPx, fallbackHeight, screenWidth, fMain, fBottom);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            contentLoadInProgress = false;
                            // If a config change / rotation arrived mid-load (which would otherwise
                            // render with stale columns), re-render once now with fresh data.
                            if (pendingContentReload) {
                                pendingContentReload = false;
                                ServerConfig latest = settingsHelper != null ? settingsHelper.getConfig() : null;
                                if (latest != null && !isFinishing() && !isDestroyed()) {
                                    needRedrawContentAfterReconfigure = true;
                                    showContent(latest);
                                }
                            }
                        }
                    });
                }, "app-list-load").start();
            } else {
                // A load is already running; remember that another redraw was requested so we
                // reconcile once it finishes.
                pendingContentReload = true;
            }
        }
        binding.loading.setVisibility(View.GONE);
        binding.setShowContent(true);
        // We can now sleep, uh
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * Builds the paged app grid, the page-indicator dots, the bottom dock and (in kiosk mode) the
     * lock-task whitelist from already-enumerated app lists. Runs on the UI thread; the expensive
     * package enumeration was done on a background thread by showContent().
     */
    private void renderAppContent(ServerConfig config, int columns, int iconPx,
                                  int fallbackHeight, int screenWidth,
                                  List<com.brother.pharmach.mdm.launcher.util.AppInfo> mainItems,
                                  List<com.brother.pharmach.mdm.launcher.util.AppInfo> bottomItems) {
        mainAppItems = mainItems;

        // Reuse the pager adapter across renders. Recreating it + setAdapter() resets the whole
        // ViewPager2 and is the visible "refresh" flash; instead we build it once and thereafter
        // update its data in place (DiffUtil), so unchanged icons never reload.
        final boolean firstBuild = (pagedAppListAdapter == null);
        final ViewPager2 pager = binding.activityMainPager;
        if (firstBuild) {
            pagedAppListAdapter = new PagedAppListAdapter(this, this, this);
            pager.setAdapter(pagedAppListAdapter);

            if (!pagerCallbackRegistered) {
                pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        updatePageIndicator(position);
                        if (pagedAppListAdapter != null) {
                            MainAppListAdapter pageAdapter = pagedAppListAdapter.getPageAdapter(position);
                            if (pageAdapter != null) {
                                mainAppListAdapter = pageAdapter;
                            }
                        }
                    }
                });
                pagerCallbackRegistered = true;
            }
        }

        // The pager must be laid out before we can read its real size, which we need to compute
        // how many rows actually fit — an estimate overshoots and spills an extra scrollable row.
        // We measure a real item at the current icon size (worst-case 2-line label) so the row
        // count fits every screen size and density exactly.
        pager.post(() -> {
            int h = pager.getHeight();
            if (h <= 0) {
                h = fallbackHeight;
            }
            int pagerWidth = pager.getWidth() > 0 ? pager.getWidth() : screenWidth;
            int cellWidth = Math.max(1, pagerWidth / columns);
            int rowHeightPx = measureAppCellHeight(cellWidth, iconPx);
            int rows = Math.max(1, h / rowHeightPx);
            if (firstBuild) {
                pagedAppListAdapter.setData(mainAppItems, columns, rows);
            } else {
                pagedAppListAdapter.updateData(mainAppItems, columns, rows);
            }
            syncPageIndicator(pagedAppListAdapter.getPageCount());
            updatePageIndicator(pager.getCurrentItem());
            // Route hardware-key navigation at the page currently on screen.
            pager.post(() -> {
                MainAppListAdapter pageAdapter = pagedAppListAdapter.getPageAdapter(pager.getCurrentItem());
                if (pageAdapter != null) {
                    mainAppListAdapter = pageAdapter;
                }
            });
        });

        int bottomAppCount = bottomItems != null ? bottomItems.size() : 0;
        if (bottomAppCount > 0) {
            int bottomSpan = bottomAppCount < columns ? bottomAppCount : columns;
            if (bottomAppListAdapter == null) {
                bottomAppListAdapter = new BottomAppListAdapter(this, this, this, bottomItems);
                bottomAppListAdapter.setSpanCount(columns);
                binding.activityBottomLine.setLayoutManager(new GridLayoutManager(this, bottomSpan));
                binding.activityBottomLine.setAdapter(bottomAppListAdapter);
            } else {
                // Update in place so the dock doesn't flash either.
                bottomAppListAdapter.setSpanCount(columns);
                if (binding.activityBottomLine.getLayoutManager() instanceof GridLayoutManager) {
                    ((GridLayoutManager) binding.activityBottomLine.getLayoutManager()).setSpanCount(bottomSpan);
                }
                bottomAppListAdapter.updateItems(bottomItems);
            }
            binding.activityBottomLayout.setVisibility(View.VISIBLE);
        } else {
            bottomAppListAdapter = null;
            binding.activityBottomLine.setAdapter(null);
            binding.activityBottomLayout.setVisibility(View.GONE);
        }

        // Issue 2: keep the kiosk lock-task whitelist in lockstep with the rendered app set.
        // When the launcher itself is the kiosk app, lock-task mode silently blocks launching
        // any package that isn't whitelisted. Rebuild the whitelist from exactly the apps the
        // user can see/tap right now (already worktime-filtered), so taps always launch.
        if (ProUtils.kioskModeRequired(this)
                && getPackageName().equals(config.getMainApp())
                && ProUtils.isKioskModeRunning(this)) {
            java.util.LinkedHashSet<String> visiblePackages = new java.util.LinkedHashSet<>();
            collectPackages(mainAppItems, visiblePackages);
            collectPackages(bottomAppListAdapter, visiblePackages);
            ProUtils.setKioskLockTaskWhitelist(this, visiblePackages);
        }
    }

    // Added an option to delay restarting the kiosk app
    // Because some apps need time to finish their work
    private boolean startKiosk(String kioskApp) {
        String kioskDelayStr = settingsHelper.getAppPreference(getPackageName(), "kiosk_restart_delay_ms");
        int kioskDelay = 0;
        try {
            if (kioskDelayStr != null) {
                kioskDelay = Integer.parseInt(kioskDelayStr);
            }
        } catch (/*NumberFormat*/Exception e) {
        }
        if (kioskDelay == 0) {
            // Standard flow: no delay as earlier
            return ProUtils.startCosuKioskMode(kioskApp, MainActivity.this, false);
        } else {
            // Delayed kiosk start
            handler.postDelayed(() -> ProUtils.startCosuKioskMode(kioskApp, MainActivity.this, false), kioskDelay);
            return true;
        }
    }

    private void showLockScreen() {
        if (lockScreen == null) {
            createLockScreen();
            if (lockScreen == null) {
                // Why cannot we create the lock screen? Give up and return
                // The locked device will show the launcher, but still cannot run any application
                return;
            }
        }
        String lockAdminMessage = settingsHelper.getConfig().getLockMessage();
        String lockMessage = getString(R.string.device_locked, SettingsHelper.getInstance(this).getDeviceId());
        if (lockAdminMessage != null) {
            lockMessage += " " + lockAdminMessage;
        }
        TextView textView = lockScreen.findViewById( R.id.message );
        textView.setText(lockMessage);
        lockScreen.setVisibility(View.VISIBLE);
    }

    private void hideLockScreen() {
        if (lockScreen != null && lockScreen.getVisibility() == View.VISIBLE) {
            lockScreen.setVisibility(View.GONE);
        }
    }

    /**
     * Intent that opens the phone's mobile-network settings screen (where the mobile-data toggle
     * lives) as directly as the platform allows. If the OEM doesn't expose the specific screen,
     * the dialog's click handler falls back to the top-level Settings.
     */
    private Intent mobileNetworkSettingsIntent() {
        // First: open the mobile network settings page for the default data subscription.
        // On Android 10+ this typically shows the SIM's mobile network page with the data toggle.
        Intent networkOps = new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
        if (networkOps.resolveActivity(getPackageManager()) != null) {
            return networkOps;
        }
        // Second: data usage settings also has a mobile data toggle at the top on most devices.
        Intent dataUsage = new Intent(Settings.ACTION_DATA_USAGE_SETTINGS);
        if (dataUsage.resolveActivity(getPackageManager()) != null) {
            return dataUsage;
        }
        // Last resort: wireless settings (may or may not have the data toggle).
        return new Intent(Settings.ACTION_WIRELESS_SETTINGS);
    }

    private void checkMobileDataViolation() {
        ServerConfig config = settingsHelper != null ? settingsHelper.getConfig() : null;
        if (config == null || !Boolean.TRUE.equals(config.getMobileData())) {
            return;
        }
        if (!Utils.hasValidSim(this)) {
            return;
        }
        try {
            if (!Utils.isMobileDataEnabled(this)) {
                if (systemSettingsDialog == null || !systemSettingsDialog.isShowing()) {
                    createAndShowSystemSettingDialog(getString(R.string.message_turn_on_mobile_data),
                            mobileNetworkSettingsIntent(), null, null);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void notifyPolicyViolation(int cause) {
        switch (cause) {
            case Const.GPS_ON_REQUIRED:
                postDelayedSystemSettingDialog(getString(R.string.message_turn_on_gps),
                        new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), REQUEST_CODE_GPS_STATE_CHANGE);
                break;
            case Const.GPS_OFF_REQUIRED:
                postDelayedSystemSettingDialog(getString(R.string.message_turn_off_gps),
                        new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), REQUEST_CODE_GPS_STATE_CHANGE);
                break;
            case Const.MOBILE_DATA_ON_REQUIRED:
                createAndShowSystemSettingDialog(getString(R.string.message_turn_on_mobile_data),
                        mobileNetworkSettingsIntent(), null, null);
                break;
            case Const.MOBILE_DATA_OFF_REQUIRED:
                createAndShowSystemSettingDialog(getString(R.string.message_turn_off_mobile_data), null, 0, false);
                break;
        }
    }

    // Run default launcher (Brother Pharmamach MDM) as if the user clicked Home button
    private void openDefaultLauncher() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    // If we updated the configuration, let's send the final state to the server
    private void sendDeviceInfoAfterReconfigure() {
        if (needSendDeviceInfoAfterReconfigure) {
            needSendDeviceInfoAfterReconfigure = false;
            // Building DeviceInfo enumerates all packages and checksums configured files — heavy
            // work that used to run on the main thread here and could ANR. Build it on a background
            // thread (safe: getDeviceInfo is already invoked off-main by SimChangedReceiver and
            // SendDeviceInfoWorker, and collects the same GPS/app/file payload regardless of
            // thread), then hand it to SendDeviceInfoTask on the main thread. AsyncTask.execute()
            // must be called on the main thread; the network send still happens in the task's
            // doInBackground, exactly as before — the send/retry path is unchanged.
            new Thread(() -> {
                final DeviceInfo deviceInfo;
                try {
                    deviceInfo = DeviceInfoProvider.getDeviceInfo(this, true, true);
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    try {
                        new SendDeviceInfoTask(MainActivity.this).execute(deviceInfo);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }, "device-info-build").start();
        }
    }

    private void scheduleDeviceInfoSending() {
        if (sendDeviceInfoScheduled) {
            return;
        }
        sendDeviceInfoScheduled = true;
        SendDeviceInfoWorker.scheduleDeviceInfoSending(this);
    }

    private void scheduleInstalledAppsRun() {
        List<Application> applicationsForRun = configUpdater.getApplicationsForRun();

        if (applicationsForRun.size() == 0) {
            return;
        }
        int pause = PAUSE_BETWEEN_AUTORUNS_SEC;
        while (applicationsForRun.size() > 0) {
            final Application application = applicationsForRun.get(0);
            applicationsForRun.remove(0);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!com.brother.pharmach.mdm.launcher.util.WorkTimeManager.getInstance()
                            .isAppAllowed(application.getPkg())) {
                        RemoteLogger.log(MainActivity.this, Const.LOG_INFO,
                                "Skipping scheduled autorun for restricted app during WorkTime: " + application.getPkg());
                        return;
                    }
                    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(application.getPkg());
                    if (launchIntent != null) {
                        startActivity(launchIntent);
                    }
                }
            }, pause * 1000);
            pause += PAUSE_BETWEEN_AUTORUNS_SEC;
        }
    }

    private void updateTitle(ServerConfig config) {
        String titleType = config.getTitle();
        if (titleType != null) {
            if (titleType.equals(ServerConfig.TITLE_NONE)) {
                binding.activityMainTitle.setVisibility(View.GONE);
                return;
            }
            if (config.getTextColor() != null) {
                try {
                    binding.activityMainTitle.setTextColor(Color.parseColor(settingsHelper.getConfig().getTextColor()));
                } catch (Exception e) {
                    // Invalid color
                    e.printStackTrace();
                }
            }
            binding.activityMainTitle.setVisibility(View.VISIBLE);
            String imei = DeviceInfoProvider.getImei(this);
            if (imei == null) {
                imei = "";
            }
            String serial = DeviceInfoProvider.getSerialNumber();
            if (serial == null) {
                serial = "";
            }
            String ip = SettingsHelper.getInstance(this).getExternalIp();
            if (ip == null) {
                ip = "";
            }
            String titleText = titleType
                    .replace(ServerConfig.TITLE_DEVICE_ID, SettingsHelper.getInstance(this).getDeviceId())
                    .replace(ServerConfig.TITLE_DESCRIPTION, config.getDescription() != null ? config.getDescription() : "")
                    .replace(ServerConfig.TITLE_CUSTOM1, config.getCustom1() != null ? config.getCustom1() : "")
                    .replace(ServerConfig.TITLE_CUSTOM2, config.getCustom2() != null ? config.getCustom2() : "")
                    .replace(ServerConfig.TITLE_CUSTOM3, config.getCustom3() != null ? config.getCustom3() : "")
                    .replace(ServerConfig.TITLE_IMEI, imei)
                    .replace(ServerConfig.TITLE_SERIAL, serial)
                    .replace(ServerConfig.TITLE_EXTERNAL_IP, ip)
                    .replace("\\n", "\n");
            binding.activityMainTitle.setText(titleText);
        } else {
            binding.activityMainTitle.setVisibility(View.GONE);
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (quickPanelController != null) {
            quickPanelController.destroy();
            quickPanelController = null;
        }

        settingsHelper.setMainActivityRunning(false);

        WindowManager manager = ((WindowManager)getApplicationContext().getSystemService(Context.WINDOW_SERVICE));
        if ( applicationNotAllowed != null ) {
            try { manager.removeView( applicationNotAllowed ); }
            catch ( Exception e ) { e.printStackTrace(); }
        }

        if ( statusBarView != null ) {
            try { manager.removeView( statusBarView ); }
            catch ( Exception e ) { e.printStackTrace(); }
        }

        if ( rightToolbarView != null ) {
            try { manager.removeView( rightToolbarView ); }
            catch ( Exception e ) { e.printStackTrace(); }
        }

        if ( exitView != null ) {
            try { manager.removeView( exitView ); }
            catch ( Exception e ) { e.printStackTrace(); }
        }

        if ( infoView != null ) {
            try { manager.removeView( infoView ); }
            catch ( Exception e ) { e.printStackTrace(); }
        }

        if ( updateView != null ) {
            try { manager.removeView( updateView ); }
            catch ( Exception e ) { e.printStackTrace(); }
        }

        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
            unregisterReceiver(stateChangeReceiver);
            unregisterReceiver(screenOffReceiver);
            unregisterReceiver(userPresentReceiver);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        isBackground = true;

        stopOngoingCallBanner();

        // Never leave the quick panel open across pause/screen-off; its open state
        // is intentionally not persisted so kiosk re-entry always starts closed
        if (quickPanelController != null) {
            quickPanelController.onPause();
        }

        statusBarUpdater.stopUpdating();

        dismissDialog(fileNotDownloadedDialog);
        dismissDialog(enterServerDialog);
        dismissDialog(enterDeviceIdDialog);
        dismissDialog(networkErrorDialog);
        dismissDialog(enterPasswordDialog);
        dismissDialog(historySettingsDialog);
        dismissDialog(unknownSourcesDialog);
        dismissDialog(overlaySettingsDialog);
        dismissDialog(administratorModeDialog);
        dismissDialog(deviceInfoDialog);
        dismissDialog(accessibilityServiceDialog);
        // Don't tear down the "turn on mobile data" prompt here: the violation it's reporting
        // doesn't resolve just because the activity was transiently paused (e.g. by the
        // accessibility service's own home-bounce reacting to the mobile-data app-lockdown sweep
        // suspending/closing several apps at once) — dismissing and letting onResume recreate it
        // on every such pause is exactly what made it flicker open/closed repeatedly.
        if (!systemSettingsDialogIsMobileDataOn) {
            dismissDialog(systemSettingsDialog);
            StatusControlService.setMobileDataDialogVisible(false);
        }
        dismissDialog(permissionsDialog);

        try {
            unregisterReceiver(pushReceiver);
        } catch (Exception e) {}

        LocalBroadcastManager.getInstance( this ).sendBroadcast( new Intent( Const.ACTION_SHOW_LAUNCHER ) );
    }

    private void createAndShowAdministratorDialog() {
        dismissDialog(administratorModeDialog);
        administratorModeDialog = new Dialog( this );
        dialogAdministratorModeBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_administrator_mode,
                null,
                false );
        dialogAdministratorModeBinding.hint.setText(
                getString(R.string.dialog_administrator_mode_message, getString(R.string.white_app_name)));
        administratorModeDialog.setCancelable( false );
        administratorModeDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );

        administratorModeDialog.setContentView( dialogAdministratorModeBinding.getRoot() );
        administratorModeDialog.show();
    }

    public void skipAdminMode( View view ) {
        dismissDialog(administratorModeDialog);

        RemoteLogger.log(this, Const.LOG_INFO, "Manually skipped the device admin permissions setup");
        preferences.
                edit().
                putInt( Const.PREFERENCES_ADMINISTRATOR, Const.PREFERENCES_OFF ).
                commit();

        checkAndStartLauncher();
    }

    public void setAdminMode( View view ) {
        dismissDialog(administratorModeDialog);
        // Use a proxy activity because of an Android bug (see comment to AdminModeRequestActivity!)
        startActivity( new Intent( MainActivity.this, AdminModeRequestActivity.class ) );
    }

    private void createAndShowFileNotDownloadedDialog(String fileName, String details) {
        dismissDialog(fileNotDownloadedDialog);
        fileNotDownloadedDialog = new Dialog( this );
        dialogFileDownloadingFailedBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_file_downloading_failed,
                null,
                false );
        int errorTextResource = this.downloadingFile ? R.string.main_file_downloading_error : R.string.main_app_downloading_error;
        String message = getString(errorTextResource) + " " + fileName;
        if (details != null && !details.isEmpty()) {
            message += "\n\n" + details;
        }
        dialogFileDownloadingFailedBinding.title.setText( message );
        fileNotDownloadedDialog.setCancelable( false );
        fileNotDownloadedDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );

        fileNotDownloadedDialog.setContentView( dialogFileDownloadingFailedBinding.getRoot() );
        try {
            fileNotDownloadedDialog.show();
        } catch (Exception e) {
            // BadTokenException ignored
        }
    }

    public void repeatDownloadClicked( View view ) {
        dismissDialog(fileNotDownloadedDialog);
        if (downloadingFile) {
            configUpdater.repeatDownloadFiles();
        } else {
            configUpdater.repeatDownloadApps();
        }
    }

    public void confirmDownloadFailureClicked( View view ) {
        dismissDialog(fileNotDownloadedDialog);

        if (downloadingFile) {
            configUpdater.skipDownloadFiles();
        } else {
            configUpdater.skipDownloadApps();
        }
    }

    private void createAndShowHistorySettingsDialog() {
        dismissDialog(historySettingsDialog);
        historySettingsDialog = new Dialog( this );
        dialogHistorySettingsBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_history_settings,
                null,
                false );
        dialogHistorySettingsBinding.hint.setText(
                getString(R.string.dialog_history_settings_title, getString(R.string.white_app_name)));
        historySettingsDialog.setCancelable( false );
        historySettingsDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );

        historySettingsDialog.setContentView( dialogHistorySettingsBinding.getRoot() );
        historySettingsDialog.show();
    }

    public void historyWithoutPermission( View view ) {
        dismissDialog(historySettingsDialog);

        preferences.
                edit().
                putInt( Const.PREFERENCES_USAGE_STATISTICS, Const.PREFERENCES_OFF ).
                commit();
        checkAndStartLauncher();
    }

    public void continueHistory( View view ) {
        dismissDialog(historySettingsDialog);

        startActivity( new Intent( Settings.ACTION_USAGE_ACCESS_SETTINGS ) );
    }

    private void createAndShowManageStorageDialog() {
        dismissDialog(manageStorageDialog);
        manageStorageDialog = new Dialog( this );
        dialogManageStorageBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_manage_storage,
                null,
                false );
        manageStorageDialog.setCancelable( false );
        manageStorageDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );

        manageStorageDialog.setContentView( dialogManageStorageBinding.getRoot() );
        manageStorageDialog.show();
    }

    public void storageWithoutPermission(View view) {
        dismissDialog(manageStorageDialog);

        preferences.
                edit().
                putInt( Const.PREFERENCES_MANAGE_STORAGE, Const.PREFERENCES_OFF ).
                commit();
        checkAndStartLauncher();
    }

    public void continueStorage(View view) {
        dismissDialog(manageStorageDialog);
        try {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            Uri uri = Uri.fromParts("package", this.getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            } catch (Exception e1) {
                Toast.makeText(this, R.string.manage_storage_not_supported, Toast.LENGTH_LONG).show();
                preferences.
                        edit().
                        putInt( Const.PREFERENCES_MANAGE_STORAGE, Const.PREFERENCES_OFF ).
                        commit();
                checkAndStartLauncher();
            }
        }
    }

    private void createAndShowOverlaySettingsDialog() {
        dismissDialog(overlaySettingsDialog);
        overlaySettingsDialog = new Dialog( this );
        dialogOverlaySettingsBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_overlay_settings,
                null,
                false );
        dialogOverlaySettingsBinding.hint.setText(
                getString(R.string.dialog_overlay_settings_title, getString(R.string.white_app_name)));
        overlaySettingsDialog.setCancelable( false );
        overlaySettingsDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );

        overlaySettingsDialog.setContentView( dialogOverlaySettingsBinding.getRoot() );
        overlaySettingsDialog.show();
    }

    public void overlayWithoutPermission( View view ) {
        dismissDialog(overlaySettingsDialog);

        preferences.
                edit().
                putInt( Const.PREFERENCES_OVERLAY, Const.PREFERENCES_OFF ).
                commit();
        checkAndStartLauncher();
    }

    public void continueOverlay( View view ) {
        dismissDialog(overlaySettingsDialog);

        Intent intent = new Intent( Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse( "package:" + getPackageName() ) );
        try {
            startActivityForResult(intent, 1001);
        } catch (/* ActivityNotFound*/Exception e) {
            Toast.makeText(this, R.string.overlays_not_supported, Toast.LENGTH_LONG).show();
            overlayWithoutPermission(view);
        }
    }


    public void saveDeviceId( View view ) {
        String deviceId = enterDeviceIdDialogBinding.deviceId.getText().toString();
        if ( "".equals( deviceId ) ) {
            return;
        } else {
            settingsHelper.setDeviceId( deviceId );
            enterDeviceIdDialogBinding.setError( false );

            dismissDialog(enterDeviceIdDialog);

            if ( checkPermissions( true ) ) {
                Log.i(Const.LOG_TAG, "saveDeviceId(): calling updateConfig()");
                updateConfig( true );
            }
        }
    }


    public void saveServerUrl( View view ) {
        if (saveServerUrlBase()) {
            ServerServiceKeeper.resetServices();
            checkAndStartLauncher();
        }
    }


    public void networkErrorRepeatClicked( View view ) {
        dismissDialog(networkErrorDialog);

        Log.i(Const.LOG_TAG, "networkErrorRepeatClicked(): calling updateConfig()");
        updateConfig( true );
    }

    public void networkErrorResetClicked( View view ) {
        dismissDialog(networkErrorDialog);

        Log.i(Const.LOG_TAG, "networkErrorResetClicked(): calling updateConfig()");
        settingsHelper.setDeviceId("");
        settingsHelper.setBaseUrl("");
        settingsHelper.setSecondaryBaseUrl("");
        settingsHelper.setServerProject("");
        settingsHelper.setStartupSyncComplete(false);
        createAndShowServerDialog(false, settingsHelper.getBaseUrl(), settingsHelper.getServerProject());
    }

    public void networkErrorWifiClicked( View view ) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_ENABLE_SETTINGS));
        if (ProUtils.kioskModeRequired(this) && ProUtils.isKioskModeRunning(this)) {
            String kioskApp = settingsHelper.getConfig().getMainApp();
            ProUtils.startCosuKioskMode(kioskApp, this, true);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        }, 500);
    }

    public void networkErrorCancelClicked(View view) {
        dismissDialog(networkErrorDialog);

        if (configFault) {
            Log.i(Const.LOG_TAG, "networkErrorCancelClicked(): no configuration available, quit");
            Toast.makeText(this, getString(R.string.critical_server_failure,
                    getString(R.string.white_app_name)), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Log.i(Const.LOG_TAG, "networkErrorCancelClicked()");
        if ( settingsHelper.getConfig() != null ) {
            showContent( settingsHelper.getConfig() );
            configUpdater.skipConfigLoad();
        } else {
            Log.i(Const.LOG_TAG, "networkErrorCancelClicked(): no configuration available, retrying");
            Toast.makeText(this, R.string.empty_configuration, Toast.LENGTH_LONG).show();
            configFault = true;
            updateConfig( false );
        }
    }

    public void networkErrorDetailsClicked(View view) {
        ErrorDetailsActivity.display(this, networkErrorDetails, false);
    }

    private boolean checkPermissions( boolean startSettings ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        // If the user didn't grant permissions, let him know and do not request until he confirms he want to retry
        if (permissionsDialog != null && permissionsDialog.isShowing()) {
            return false;
        }

        if (Utils.isDeviceOwner(this)) {
            if (settingsHelper.getConfig() != null && (ServerConfig.APP_PERMISSIONS_ASK_ALL.equals(settingsHelper.getConfig().getAppPermissions()) ||
                    ServerConfig.APP_PERMISSIONS_ASK_LOCATION.equals(settingsHelper.getConfig().getAppPermissions()))) {
                // Even in device owner mode, if "Ask for location" is requested by the admin,
                // let's ask permissions (so do nothing here, fall through)
            } else {
                // Prefer auto-grant in device owner mode, but verify dangerous permissions before continuing.
                Utils.autoGrantPhonePermission(this);
                // Self-heal location permissions on every start: location must always be
                // "Allow all the time" for the launcher on managed devices
                Utils.autoGrantLocationPermissions(this);

                // Only READ_PHONE_STATE is mandatory; call log / SMS permissions are
                // hard-restricted and may be undeniable by the user, so they are
                // requested below but never block the launcher.
                if (hasRequiredPhonePermissions()) {
                    return true;
                }

                if (startSettings) {
                    if (BuildConfig.ENABLE_SMS_LOG) {
                        requestPermissions(new String[]{
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_CALL_LOG,
                                Manifest.permission.READ_SMS
                        }, PERMISSIONS_REQUEST);
                    } else {
                        requestPermissions(new String[]{
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_CALL_LOG
                        }, PERMISSIONS_REQUEST);
                    }
                }
                return false;
            }
        }

        if (preferences.getInt(Const.PREFERENCES_DISABLE_LOCATION, Const.PREFERENCES_OFF) == Const.PREFERENCES_ON) {
            // READ_CALL_LOG / READ_SMS are requested below but intentionally excluded
            // from this gate: they are hard-restricted since Android 10 and may never
            // become grantable, which would loop the permissions dialog forever.
            if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.R && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) ||
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) ||
                    checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {

                if (startSettings) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        if (BuildConfig.ENABLE_SMS_LOG) {
                            requestPermissions(new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.READ_CALL_LOG,
                                    Manifest.permission.READ_SMS
                            }, PERMISSIONS_REQUEST);
                        } else {
                            requestPermissions(new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.READ_CALL_LOG
                            }, PERMISSIONS_REQUEST);
                        }
                    } else {
                        if (BuildConfig.ENABLE_SMS_LOG) {
                            requestPermissions(new String[]{
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.READ_CALL_LOG,
                                    Manifest.permission.READ_SMS
                            }, PERMISSIONS_REQUEST);
                        } else {
                            requestPermissions(new String[]{
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.READ_CALL_LOG
                            }, PERMISSIONS_REQUEST);
                        }
                    }
                }
                return false;
            } else {
                return true;
            }
        } else {
            return checkLocationPermissions(startSettings);
        }
    }

    private boolean hasRequiredPhonePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        // READ_CALL_LOG and READ_SMS are intentionally NOT checked here: they are
        // hard-restricted since Android 10, so on non-whitelisted installs the system
        // silently denies them and they can't even be enabled from app settings.
        // Treating them as mandatory makes the permissions dialog reappear forever.
        // Call log / SMS upload degrade gracefully when the permission is missing.
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private void enforceMandatoryPermissionsDialogFallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        handler.postDelayed(() -> {
            if (isFinishing()) {
                return;
            }

            if (!hasRequiredPhonePermissions() && (permissionsDialog == null || !permissionsDialog.isShowing())) {
                createAndShowPermissionsDialog();
            }
        }, 700);
    }

    // Location permissions request on Android 10 and above is rather tricky (shame on Google for their stupid logic!!!)
    // So it's implemented in a separate method
    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean checkLocationPermissions(boolean startSettings) {
        // READ_CALL_LOG / READ_SMS are requested below but intentionally excluded from
        // this gate: they are hard-restricted since Android 10 and may never become
        // grantable, which would loop the permissions dialog forever.
        if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.R && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) ||
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) ||
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {

            if (startSettings) {
                boolean activeModeLocation = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        activeModeLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED /* &&
                                !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)*/;
                    } catch (Exception e) {
                        // On some older models:
                        // java.lang.IllegalArgumentException
                        // Unknown permission: android.permission.ACCESS_BACKGROUND_LOCATION
                        // Update: since there's the Android version check, we should never be here!
                        e.printStackTrace();
                    }
                }

                if (activeModeLocation) {
                    // The following flow happened
                    // The user has enabled locations, but when the app prompted for the background location,
                    // the user clicked "Locations only in active mode".
                    // In this case, requestPermissions won't show dialog any more!
                    // So we need to open the general permissions dialog
                    // Let's confirm with the user once again, then display the settings sheet
                    try {
                        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_ENABLE_SETTINGS));
                        new AlertDialog.Builder(MainActivity.this)
                                .setMessage(getString(R.string.background_location, getString(R.string.white_app_name)))
                                .setPositiveButton(R.string.background_location_continue, (dialog, which) -> {
                                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", getPackageName(), null)));
                                })
                                .setNegativeButton(R.string.location_disable, (dialog, which) -> {
                                    preferences.edit().putInt(Const.PREFERENCES_DISABLE_LOCATION, Const.PREFERENCES_ON).commit();
                                    // Continue the main flow!
                                    startLauncher();
                                })
                                .create()
                                .show();
                    } catch (Exception e) {
                        // Activity closed before showing a dialog, just ignore this exception
                        e.printStackTrace();
                    }
                } else {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        if (BuildConfig.ENABLE_SMS_LOG) {
                            requestPermissions(new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.READ_CALL_LOG,
                                    Manifest.permission.READ_SMS
                            }, PERMISSIONS_REQUEST);
                        } else {
                            requestPermissions(new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.READ_CALL_LOG
                            }, PERMISSIONS_REQUEST);
                        }
                    } else {
                        if (BuildConfig.ENABLE_SMS_LOG) {
                            requestPermissions(new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION,
// This location can't be requested here: the dialog fails to show when we use SDK 30+
// https://developer.android.com/develop/sensors-and-location/location/permissions#request-location-access-runtime
//                                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.READ_CALL_LOG,
                                    Manifest.permission.READ_SMS
                            }, PERMISSIONS_REQUEST);
                        } else {
                            requestPermissions(new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION,
// This location can't be requested here: the dialog fails to show when we use SDK 30+
// https://developer.android.com/develop/sensors-and-location/location/permissions#request-location-access-runtime
//                                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.READ_CALL_LOG
                            }, PERMISSIONS_REQUEST);
                        }
                    }
                }
            }
            return false;
        } else {
            return true;
        }

    }

    private void createAndShowEnterPasswordDialog() {
        dismissDialog(enterPasswordDialog);
        enterPasswordDialog = new Dialog( this );
        dialogEnterPasswordBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_enter_password,
                null,
                false );
        enterPasswordDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );
        enterPasswordDialog.setCancelable( false );

        enterPasswordDialog.setContentView( dialogEnterPasswordBinding.getRoot() );
        dialogEnterPasswordBinding.setLoading( false );
        try {
            enterPasswordDialog.show();
        } catch (Exception e) {
            // Sometimes here we get a Fatal Exception: android.view.WindowManager$BadTokenException
            // Unable to add window -- token android.os.BinderProxy@f307de for displayid = 0 is not valid; is your activity running?
            Toast.makeText(getApplicationContext(), R.string.internal_error, Toast.LENGTH_LONG).show();
        }
    }

    public void closeEnterPasswordDialog( View view ) {
        dismissDialog(enterPasswordDialog);
        if (ProUtils.kioskModeRequired(this)) {
            checkAndStartLauncher();
            updateConfig(false);
        }
    }

    public void checkAdministratorPassword( View view ) {
        dialogEnterPasswordBinding.setLoading( true );
        GetServerConfigTask task = new GetServerConfigTask( this ) {
            @Override
            protected void onPostExecute( Integer result ) {
                dialogEnterPasswordBinding.setLoading( false );

                String masterPassword = CryptoHelper.getMD5String( "12345678" );
                if ( settingsHelper.getConfig() != null && settingsHelper.getConfig().getPassword() != null ) {
                    masterPassword = settingsHelper.getConfig().getPassword();
                }

                if ( CryptoHelper.getMD5String( dialogEnterPasswordBinding.password.getText().toString() ).
                        equals( masterPassword ) ) {
                    dismissDialog(enterPasswordDialog);
                    dialogEnterPasswordBinding.setError( false );
                    openAdminPanel();
                } else {
                    dialogEnterPasswordBinding.setError( true );
                }
            }
        };
        task.execute();
    }

    private void openAdminPanel() {
        if (ProUtils.kioskModeRequired(MainActivity.this)) {
            ProUtils.unlockKiosk(MainActivity.this);
        }
        RemoteLogger.log(MainActivity.this, Const.LOG_INFO, "Administrator panel opened");
        startActivity( new Intent( MainActivity.this, AdminActivity.class ) );
    }

    private void createAndShowUnknownSourcesDialog() {
        dismissDialog(unknownSourcesDialog);
        unknownSourcesDialog = new Dialog( this );
        dialogUnknownSourcesBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_unknown_sources,
                null,
                false );
        unknownSourcesDialog.setCancelable( false );
        unknownSourcesDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );

        unknownSourcesDialog.setContentView( dialogUnknownSourcesBinding.getRoot() );
        unknownSourcesDialog.show();
    }

    public void continueUnknownSources( View view ) {
        dismissDialog(unknownSourcesDialog);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
        } else {
            // In Android Oreo and above, permission to install packages are set per each app
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        }
    }

    private void createAndShowMiuiPermissionsDialog(int screen) {
        dismissDialog(miuiPermissionsDialog);
        miuiPermissionsDialog = new Dialog( this );
        dialogMiuiPermissionsBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_miui_permissions,
                null,
                false );
        miuiPermissionsDialog.setCancelable( false );
        miuiPermissionsDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );

        switch (screen) {
            case Const.MIUI_PERMISSIONS:
                dialogMiuiPermissionsBinding.title.setText(R.string.dialog_miui_permissions_title);
                break;
            case Const.MIUI_DEVELOPER:
                dialogMiuiPermissionsBinding.title.setText(R.string.dialog_miui_developer_title);
                break;
            case Const.MIUI_OPTIMIZATION:
                dialogMiuiPermissionsBinding.title.setText(R.string.dialog_miui_optimization_title);
                break;
        }

        miuiPermissionsDialog.setContentView( dialogMiuiPermissionsBinding.getRoot() );
        miuiPermissionsDialog.show();
    }

    public void continueMiuiPermissions( View view ) {
        String titleText = dialogMiuiPermissionsBinding.title.getText().toString();
        dismissDialog(miuiPermissionsDialog);

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_ENABLE_SETTINGS));
        Intent intent;
        if (titleText.equals(getString(R.string.dialog_miui_permissions_title))) {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
        } else if (titleText.equals(getString(R.string.dialog_miui_developer_title))) {
            intent = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS);
        } else {
            // if (titleText.equals(getString(R.string.dialog_miui_optimization_title))
            intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onBackPressed() {
        // Only dismisses the quick panel; deliberately never calls super so the
        // kiosk/back-press lockdown behavior is unchanged
        if (quickPanelController != null) {
            quickPanelController.close();
        }
    }

    // Top-band swipe detection for the quick panel happens here, before the view
    // hierarchy, so a downward swipe starting anywhere along the top of the
    // screen opens the panel — even when it begins over app icons. When the
    // swipe commits, children that already received the gesture get a CANCEL.
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (quickPanelController != null) {
            int verdict = quickPanelController.onActivityTouch(ev);
            if (verdict == QuickPanelController.TOUCH_STEAL) {
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                try {
                    super.dispatchTouchEvent(cancel);
                } finally {
                    cancel.recycle();
                }
                return true;
            }
            if (verdict == QuickPanelController.TOUCH_CONSUMED) {
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // Lazily creates the swipe-down quick settings panel and (re-)attaches it to
    // the current root view. Idempotent; called from onResume because the binding
    // may be recreated there on some firmwares (see reinitApp).
    private void initQuickPanel() {
        if (binding == null) {
            return;
        }
        if (quickPanelController == null) {
            quickPanelController = new QuickPanelController(this);
        }
        try {
            quickPanelController.attach((ViewGroup) binding.getRoot());
        } catch (Exception e) {
            // The panel is a convenience layer; never let it break launcher startup
            Log.w(Const.LOG_TAG, "Failed to attach quick panel: " + e.getMessage());
        }
    }

    @Override
    public void onAppChoose( @NonNull AppInfo resolveInfo ) {

    }

    @Override
    public boolean switchAppListAdapter(BaseAppListAdapter adapter, int direction) {
        if (adapter == mainAppListAdapter && bottomAppListAdapter != null &&
                (direction == Const.DIRECTION_RIGHT || direction == Const.DIRECTION_DOWN)) {
            bottomAppListAdapter.setFocused(true);
            return true;
        } else if (adapter == bottomAppListAdapter &&
                (direction == Const.DIRECTION_LEFT || direction == Const.DIRECTION_UP)) {
            mainAppListAdapter.setFocused(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean onLongClick( View v ) {
        createAndShowEnterPasswordDialog();
        return true;
    }

    private void applyWorkTimeSettingsRestriction() {
        // Settings is treated like any other app: it will be allowed or blocked by
        // the normal worktime allowlist/blocklist configuration.
        settingsLockedByWorkTime = false;
    }

    private boolean isBatteryOptimizationComplianceRequired() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @Override
    public void onClick( View v ) {
        if (v.equals(infoView)) {
            createAndShowInfoDialog();
        } else if (v.equals(updateView)) {
            if (enterDeviceIdDialog != null && enterDeviceIdDialog.isShowing()) {
                Log.i(Const.LOG_TAG, "Occasional update request when device info is entered, ignoring!");
                return;
            }
            Log.i(Const.LOG_TAG, "updating config on request");
            binding.loading.setVisibility(View.VISIBLE);
            binding.setShowContent(false);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            updateConfig(true);
        }
    }

    private void postDelayedMobileDataDialog(final boolean dataOnRequired) {
        final String message = getString(dataOnRequired
                ? R.string.message_turn_on_mobile_data
                : R.string.message_turn_off_mobile_data);
        // When data must be turned ON, give the "Continue" button a real settings screen to open —
        // there's nothing to gate on since the whole point of tapping it is to reach that screen.
        // When data must be turned OFF there's no settings deep-link for that, so Continue just
        // confirms compliance (requiredMobileDataState below).
        final Intent settingsIntent = dataOnRequired ? mobileNetworkSettingsIntent() : null;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                createAndShowSystemSettingDialog(message, settingsIntent, null,
                        dataOnRequired ? null : false);
            }
        }, 5000);
    }

    private void postDelayedSystemSettingDialog(final String message, final Intent settingsIntent) {
        postDelayedSystemSettingDialog(message, settingsIntent, null);
    }

    private void postDelayedSystemSettingDialog(final String message, final Intent settingsIntent, final Integer requestCode) {
        postDelayedSystemSettingDialog(message, settingsIntent, requestCode, false);
    }

    private void postDelayedSystemSettingDialog(final String message, final Intent settingsIntent, final Integer requestCode, final boolean forceEnableSettings) {
        if (settingsIntent != null) {
            // If settings are controlled by usage stats, safe settings are allowed, so we need to enable settings in accessibility mode only
            // Accessibility mode is only enabled when usage stats is off
            if (preferences.getInt(Const.PREFERENCES_ACCESSIBILITY_SERVICE, Const.PREFERENCES_OFF) == Const.PREFERENCES_ON || forceEnableSettings) {
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_ENABLE_SETTINGS));
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Const.ACTION_STOP_CONTROL));
        }
        // Delayed start prevents the race of ENABLE_SETTINGS handle and tapping "Next" button
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                createAndShowSystemSettingDialog(message, settingsIntent, requestCode);
            }
        }, 5000);
    }

    private void createAndShowSystemSettingDialog(final String message, final Intent settingsIntent, final Integer requestCode) {
        createAndShowSystemSettingDialog(message, settingsIntent, requestCode, null);
    }

    // requiredMobileDataState: true = mobile data must be ON, false = must be OFF, null = no check
    private void createAndShowSystemSettingDialog(final String message, final Intent settingsIntent, final Integer requestCode, final Boolean requiredMobileDataState) {
        dismissDialog(systemSettingsDialog);
        final boolean isMobileDataOnDialog = message.equals(getString(R.string.message_turn_on_mobile_data));
        systemSettingsDialogIsMobileDataOn = isMobileDataOnDialog;
        systemSettingsDialog = new Dialog( this );
        dialogSystemSettingsBinding = DataBindingUtil.inflate(
                LayoutInflater.from( this ),
                R.layout.dialog_system_settings,
                null,
                false );
        systemSettingsDialog.requestWindowFeature( Window.FEATURE_NO_TITLE );
        systemSettingsDialog.setCancelable( false );

        systemSettingsDialog.setContentView( dialogSystemSettingsBinding.getRoot() );

        dialogSystemSettingsBinding.setMessage(message);

        // Since we need to send Intent to the listener, here we don't use "event" attribute in XML resource as everywhere else
        systemSettingsDialog.findViewById(R.id.continueButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (requiredMobileDataState != null) {
                    boolean currentlyEnabled = Utils.isMobileDataEnabled(MainActivity.this);
                    if (requiredMobileDataState && !currentlyEnabled) {
                        Toast.makeText(MainActivity.this, getString(R.string.message_turn_on_mobile_data), Toast.LENGTH_SHORT).show();
                        return;
                    } else if (!requiredMobileDataState && currentlyEnabled) {
                        Toast.makeText(MainActivity.this, getString(R.string.message_turn_off_mobile_data), Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                dismissDialog(systemSettingsDialog);
                if (isMobileDataOnDialog) {
                    systemSettingsDialogIsMobileDataOn = false;
                    StatusControlService.setMobileDataDialogVisible(false);
                }
                if (settingsIntent == null) {
                    return;
                }
                // Enable settings once again, because the dialog may be shown more than 3 minutes
                // This is not necessary: the problem is resolved by clicking "Continue" in a popup window
                /*LocalBroadcastManager.getInstance( MainActivity.this ).sendBroadcast( new Intent( Const.ACTION_ENABLE_SETTINGS ) );
                // Open settings with a slight delay so Broadcast would certainly be handled
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startActivity(settingsIntent);
                    }
                }, 300);*/
                try {
                    startActivityOptionalResult(settingsIntent, requestCode);
                } catch (/*ActivityNotFound*/Exception e) {
                    // Open settings by default
                    startActivityOptionalResult(new Intent(android.provider.Settings.ACTION_SETTINGS), requestCode);
                }
            }
        });

        try {
            systemSettingsDialog.show();
            if (isMobileDataOnDialog) {
                StatusControlService.setMobileDataDialogVisible(true);
            }
        } catch (Exception e) {
            // BadTokenException: activity closed before dialog is shown
            RemoteLogger.log(this, Const.LOG_WARN, "Failed to open a popup system dialog! " + e.getMessage());
            e.printStackTrace();
            systemSettingsDialog = null;
        }
    }

    private void startActivityOptionalResult(Intent intent, Integer requestCode) {
        if (requestCode != null) {
            startActivityForResult(intent, requestCode);
        } else {
            startActivity(intent);
        }
    }

    // The following algorithm of launcher restart works in EMUI:
    // Run EMUI_LAUNCHER_RESTARTER activity once and send the old version number to it.
    // The restarter application will check the launcher version each second, and restart it
    // when it is changed.
    private void startLauncherRestarter() {
        // Disabled self-restart attempt to avoid crash loop on non-EMUI devices
        // or when restarter app is missing
        Log.i("LauncherRestarter", "Launcher restarter is disabled.");
        return;
//        // Sending an intent before updating, otherwise the launcher may be terminated at any time
//        Intent intent = getPackageManager().getLaunchIntentForPackage(Const.LAUNCHER_RESTARTER_PACKAGE_ID);
//        if (intent == null) {
//            Log.i("LauncherRestarter", "No restarter app, please add it in the config!");
//            return;
//        }
//        intent.putExtra(Const.LAUNCHER_RESTARTER_OLD_VERSION, BuildConfig.VERSION_NAME);
//        startActivity(intent);
//        Log.i("LauncherRestarter", "Calling launcher restarter from the launcher");
    }

    // Create a new file from the template file
    // (replace DEVICE_NUMBER, IMEI, CUSTOM* by their values)
    private void createFileFromTemplate(File srcFile, File dstFile, String deviceId, ServerConfig config) throws IOException {
        // We are supposed to process only small text files
        // So here we are reading the whole file, replacing variables, and save the content
        // It is not optimal for large files - it would be better to replace in a stream (how?)
        String content = FileUtils.readFileToString(srcFile);
        content = content.replace("DEVICE_NUMBER", deviceId)
                .replace("CUSTOM1", config.getCustom1() != null ? config.getCustom1() : "")
                .replace("CUSTOM2", config.getCustom2() != null ? config.getCustom2() : "")
                .replace("CUSTOM3", config.getCustom3() != null ? config.getCustom3() : "");
        FileUtils.writeStringToFile(dstFile, content);
    }

    /**
     * Second safety net compliance check, independent of BatteryOptimizationMonitor.
     * Called every onResume() to catch cases where the service hasn't started yet.
     */
    private void checkBatteryOptimizationCompliance() {
        // DISABLED: do not redirect to the battery optimization (compliance gatekeeper) screen
        // even when the exemption is missing. Kept for possible future re-enabling.
//        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
//        if (pm == null) return;
//        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
//            Log.w(Const.LOG_TAG, "Battery optimization not exempt — redirecting to ComplianceGatekeeperActivity");
//            Intent gatekeeperIntent = new Intent(this, ComplianceGatekeeperActivity.class);
//            gatekeeperIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
//            startActivity(gatekeeperIntent);
//        }
    }
}
