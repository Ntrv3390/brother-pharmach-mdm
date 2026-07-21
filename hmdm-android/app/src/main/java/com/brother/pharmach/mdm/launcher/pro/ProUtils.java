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

package com.brother.pharmach.mdm.launcher.pro;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.location.Location;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.brother.pharmach.mdm.launcher.AdminReceiver;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.json.ServerConfig;
import com.brother.pharmach.mdm.launcher.ui.custom.BlockingBar;
import com.brother.pharmach.mdm.launcher.util.Utils;

import android.app.ActivityManager;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;

import java.util.Calendar;

/**
 * These functions are available in Pro-version only
 * In a free version, the class contains stubs
 */
public class ProUtils {

    public static boolean isPro() {
        return true;
    }

    public static boolean kioskModeRequired(Context context) {
        try {
            SettingsHelper settingsHelper = SettingsHelper.getInstance(context.getApplicationContext());
            ServerConfig config = settingsHelper.getConfig();
            return config != null && config.isKioskMode();
        } catch (Exception e) {
            return false;
        }
    }

    public static void initCrashlytics(Context context) {
        // Stub
    }

    public static void sendExceptionToCrashlytics(Throwable e) {
        // Stub
    }

    // Returns true if our accessibility service is currently enabled in system settings
    public static boolean checkAccessibilityService(Context context) {
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null || enabledServices.isEmpty()) return false;
        String packageName = context.getPackageName();
        for (String service : enabledServices.split(":")) {
            if (service.startsWith(packageName)) return true;
        }
        return false;
    }

    // Pro-version
    public static boolean checkUsageStatistics(Context context) {
        // Stub
        return true;
    }

    static int getSystemBarsBehavior(int sdkInt) {
        if (sdkInt >= Build.VERSION_CODES.S) {
            return WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH;
        }
        return WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
    }

    private static void applyDevicePolicyStatusBarLock(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }

        DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return;
        }

        try {
            ComponentName adminComponent = new ComponentName(activity, AdminReceiver.class);
            if (dpm.isDeviceOwnerApp(activity.getPackageName())) {
                dpm.setStatusBarDisabled(adminComponent, true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADJUST_VOLUME);
                }
            }
        } catch (Exception e) {
            Log.w("ProUtils", "Unable to disable status bar via device policy", e);
        }
    }

    private static View addBlockingOverlay(Activity activity) {
        int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBarHeight = 0;
        if (resourceId > 0) {
            statusBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
        }
        if (statusBarHeight <= 0) {
            statusBarHeight = (int) (24 * activity.getResources().getDisplayMetrics().density);
        }

        View blockingView = new BlockingBar(activity);
        blockingView.setBackgroundColor(Color.TRANSPARENT);
        blockingView.setClickable(true);
        blockingView.setFocusable(true);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = Utils.OverlayWindowType();
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = statusBarHeight;
        params.gravity = Gravity.TOP | Gravity.START;
        params.format = PixelFormat.TRANSPARENT;

        WindowManager windowManager = (WindowManager) activity.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            try {
                windowManager.addView(blockingView, params);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        return blockingView;
    }

    // Add a transparent view on top of the status bar which prevents user interaction with the status bar
    public static View preventStatusBarExpansion(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }

        applyDevicePolicyStatusBarLock(activity);

        if (kioskModeRequired(activity)) {
            return null;
        }

        View overlayView = addBlockingOverlay(activity);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+): keep the bars hidden and re-hide them if the framework
            // tries to reveal them after the overlay intercepts the swipe.
            Window window = activity.getWindow();
            View decorView = window.getDecorView();

            window.setDecorFitsSystemWindows(false);
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);

            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(getSystemBarsBehavior(Build.VERSION.SDK_INT));
            }

            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            decorView.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int visibility) {
                    if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        WindowInsetsController ctrl = window.getInsetsController();
                        if (ctrl != null) {
                            ctrl.hide(WindowInsets.Type.statusBars()
                                    | WindowInsets.Type.navigationBars());
                        }
                    }
                }
            });

            decorView.setOnApplyWindowInsetsListener((view, insets) -> {
                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
                return insets;
            });
        }

        return overlayView;
    }

    // Add a transparent view on top of a swipeable area at the right (opens app list on Samsung tablets)
    public static View preventApplicationsList(Activity activity) {
        // Stub
        return null;
    }

    public static View createKioskUnlockButton(Activity activity) {
        // Stub
        return null;
    }

    public static boolean isKioskAppInstalled(Context context) {
        try {
            SettingsHelper settingsHelper = SettingsHelper.getInstance(context.getApplicationContext());
            ServerConfig config = settingsHelper.getConfig();
            if (config != null) {
                String kioskApp = config.getMainApp();
                if (kioskApp != null && !kioskApp.trim().isEmpty()) {
                    context.getPackageManager().getPackageInfo(kioskApp, 0);
                    return true;
                }
            }
        } catch (Exception e) {
            // App not found or error
        }
        return false;
    }

    public static boolean isKioskModeRunning(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
            } else {
                return am.isInLockTaskMode();
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static Intent getKioskAppIntent(String kioskApp, Activity activity) {
        try {
            return activity.getPackageManager().getLaunchIntentForPackage(kioskApp);
        } catch (Exception e) {
            return null;
        }
    }

    // Start COSU kiosk mode
    public static boolean startCosuKioskMode(String kioskApp, Activity activity, boolean enableSettings) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(activity, AdminReceiver.class);
            if (dpm != null && dpm.isDeviceOwnerApp(activity.getPackageName())) {
                // Include the phone/dialer/incoming-call packages from the start so a call that
                // arrives before the first launcher render (which rebuilds the whitelist) can still
                // bring up the accept/decline screen in lock-task mode.
                java.util.LinkedHashSet<String> pkgSet = new java.util.LinkedHashSet<>();
                pkgSet.add(activity.getPackageName());
                pkgSet.add(kioskApp);
                pkgSet.addAll(com.brother.pharmach.mdm.launcher.util.Utils.getPhoneCallPackages(activity));
                String[] packages = pkgSet.toArray(new String[0]);
                dpm.setLockTaskPackages(adminComponent, packages);
                updateKioskOptions(activity);
                activity.startLockTask();
                
                if (!kioskApp.equals(activity.getPackageName())) {
                    Intent intent = activity.getPackageManager().getLaunchIntentForPackage(kioskApp);
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    }
                }
                return true;
            }
        } catch (Exception e) {
            Log.e("ProUtils", "Failed to start kiosk mode", e);
        }
        return false;
    }

    // Set/update kiosk mode options (lock tack features)
    public static void updateKioskOptions(Activity activity) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(activity, AdminReceiver.class);
            if (dpm != null && dpm.isDeviceOwnerApp(activity.getPackageName())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Disable status bar expansion by omitting LOCK_TASK_FEATURE_NOTIFICATIONS
                    // Enable SYSTEM_INFO to show time, battery and network icons.
                    // Enable OVERVIEW to keep the Recents button functional.
                    int flags = DevicePolicyManager.LOCK_TASK_FEATURE_HOME 
                            | DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
                            | DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                            | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
                    dpm.setLockTaskFeatures(adminComponent, flags);
                    Log.i("ProUtils", "Kiosk lock task features updated: " + flags);
                }
            }
        } catch (Exception e) {
            Log.e("ProUtils", "Failed to update kiosk options", e);
        }
    }

    // Update app list in the kiosk mode.
    //
    // ROOT CAUSE (Issue 2): when the launcher itself runs as the kiosk app it enters lock-task
    // mode via startCosuKioskMode(), which whitelists ONLY the launcher package. In lock-task
    // mode Android SILENTLY refuses to start any activity whose package is not whitelisted, so
    // tapping any rendered app did nothing. This rebuilds the lock-task whitelist to include
    // every app currently visible on the launcher (already filtered by WorkTimeManager at render
    // time), keeping the whitelist in lockstep with what the user can tap.
    public static void updateKioskAllowedApps(String kioskApp, Activity activity, boolean enableSettings) {
        java.util.List<String> pkgs = new java.util.ArrayList<>();
        if (kioskApp != null && !kioskApp.trim().isEmpty()) {
            pkgs.add(kioskApp);
        }
        try {
            for (com.brother.pharmach.mdm.launcher.util.AppInfo info :
                    com.brother.pharmach.mdm.launcher.ui.AppShortcutManager.getInstance().getInstalledApps(activity, false)) {
                if (info.type == com.brother.pharmach.mdm.launcher.util.AppInfo.TYPE_APP
                        && info.packageName != null && !info.packageName.trim().isEmpty()) {
                    pkgs.add(info.packageName);
                }
            }
            for (com.brother.pharmach.mdm.launcher.util.AppInfo info :
                    com.brother.pharmach.mdm.launcher.ui.AppShortcutManager.getInstance().getInstalledApps(activity, true)) {
                if (info.type == com.brother.pharmach.mdm.launcher.util.AppInfo.TYPE_APP
                        && info.packageName != null && !info.packageName.trim().isEmpty()) {
                    pkgs.add(info.packageName);
                }
            }
        } catch (Exception e) {
            Log.w("ProUtils", "updateKioskAllowedApps: failed to enumerate launcher apps", e);
        }
        setKioskLockTaskWhitelist(activity, pkgs);
    }

    /**
     * Rebuilds the lock-task whitelist as {launcher package} + the given allowed packages.
     * Safe to call on every render; setLockTaskPackages() is a cheap, idempotent DPM call.
     * The launcher package is always included so the home surface itself never gets locked out.
     */
    public static void setKioskLockTaskWhitelist(Activity activity, java.util.Collection<String> allowedPackages) {
        try {
            if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return;
            }
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(activity, AdminReceiver.class);
            if (dpm == null || !dpm.isDeviceOwnerApp(activity.getPackageName())) {
                return;
            }
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            set.add(activity.getPackageName());   // launcher must always remain launchable
            // Phone/dialer/incoming-call UI must ALWAYS stay whitelisted, independent of WorkTime.
            // This rebuild runs on every render and otherwise reflects only the worktime-filtered
            // launcher apps; without this the incoming-call activity's package drops out of the
            // lock-task whitelist during work hours and the framework silently refuses it when a
            // call arrives with the screen already on (the ringtone plays but no accept/decline
            // screen appears). A call must never be blocked by a work-hours policy.
            set.addAll(com.brother.pharmach.mdm.launcher.util.Utils.getPhoneCallPackages(activity));
            if (allowedPackages != null) {
                for (String p : allowedPackages) {
                    if (p != null && !p.trim().isEmpty()) {
                        set.add(p);
                    }
                }
            }
            dpm.setLockTaskPackages(adminComponent, set.toArray(new String[0]));
            Log.i("ProUtils", "Kiosk lock-task whitelist updated: " + set.size() + " packages");
        } catch (Exception e) {
            Log.e("ProUtils", "setKioskLockTaskWhitelist failed", e);
        }
    }

    /**
     * Ensures a single package is present in the lock-task whitelist right before launching it.
     * Belt-and-suspenders for the race where a worktime transition re-rendered an app but the
     * whitelist rebuild hasn't run yet. No-op when not device owner / not in lock-task mode.
     */
    public static void ensureLockTaskWhitelisted(Activity activity, String packageName) {
        try {
            if (activity == null || packageName == null || packageName.trim().isEmpty()
                    || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return;
            }
            if (!isKioskModeRunning(activity)) {
                return;
            }
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(activity, AdminReceiver.class);
            if (dpm == null || !dpm.isDeviceOwnerApp(activity.getPackageName())) {
                return;
            }
            String[] current = dpm.getLockTaskPackages(adminComponent);
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            set.add(activity.getPackageName());
            if (current != null) {
                java.util.Collections.addAll(set, current);
            }
            if (set.add(packageName)) {
                dpm.setLockTaskPackages(adminComponent, set.toArray(new String[0]));
                Log.i("ProUtils", "ensureLockTaskWhitelisted: added " + packageName + " to lock-task whitelist");
            }
        } catch (Exception e) {
            Log.w("ProUtils", "ensureLockTaskWhitelisted failed for " + packageName, e);
        }
    }

    public static void unlockKiosk(Activity activity) {
        try {
            activity.stopLockTask();
            Log.i("ProUtils", "Kiosk mode stopped");
        } catch (Exception e) {
            Log.e("ProUtils", "Failed to stop kiosk mode", e);
        }
    }

    public static void processConfig(Context context, ServerConfig config) {
        // Stub
    }

    public static void processLocation(Context context, Location location, String provider) {
        // Stub    
    }

    public static String getAppName(Context context) {
        return context.getString(R.string.app_name);
    }

    public static String getCopyright(Context context) {
        return "(c) " + Calendar.getInstance().get(Calendar.YEAR) + " " + context.getString(R.string.vendor);
    }
}
