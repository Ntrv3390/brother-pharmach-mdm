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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * These functions are available in Pro-version only
 * In a free version, the class contains stubs
 */
public class ProUtils {

    private static final ExecutorService DPM_EXECUTOR = Executors.newSingleThreadExecutor();

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

    private static void applyDevicePolicyStatusBarLock(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }

        if (com.brother.pharmach.mdm.launcher.helper.CallStatusBarLift.isLifted()) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        final String packageName = context.getPackageName();
        DPM_EXECUTOR.execute(() -> {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpm == null) return;
                ComponentName adminComponent = new ComponentName(appContext, AdminReceiver.class);
                if (dpm.isDeviceOwnerApp(packageName)) {
                    dpm.setStatusBarDisabled(adminComponent, true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADJUST_VOLUME);
                    }
                }
            } catch (Exception e) {
                Log.w("ProUtils", "Unable to disable status bar via device policy", e);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Transparent status-bar touch blocker. Held statically (added with the APPLICATION context's
    // WindowManager, so it outlives the Activity) because CallStatusBarLift must be able to drop it
    // the instant a call rings and put it back afterwards, from a service or a receiver that has no
    // Activity at hand.
    // ---------------------------------------------------------------------------------------------

    private static View sBlockerView;
    private static int sBlockerHeightPx;

    /**
     * Adds/removes the transparent bar over the status bar strip.
     *
     * <p>Callable from any thread: adding/removing a View through the WindowManager must happen on
     * the main thread, and the callers include a background status-control tick, so the work is
     * hopped onto the main looper when needed.
     *
     * @param enabled true to block status-bar touches, false to let them through (ringing call).
     */
    public static void setStatusBarBlockerEnabled(final Context context, final boolean enabled) {
        if (context == null) {
            return;
        }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> setStatusBarBlockerEnabled(context, enabled));
            return;
        }
        Context app = context.getApplicationContext();
        if (enabled) {
            if (sBlockerView == null && sBlockerHeightPx > 0) {
                // Only re-create a blocker we previously owned: a zero height means
                // preventStatusBarExpansion never ran for this config (e.g. kiosk mode, where the
                // native lock-task policy does the blocking) and there is nothing to restore.
                sBlockerView = addBlockingOverlay(app, sBlockerHeightPx);
            }
        } else {
            removeBlockingOverlay(app);
        }
    }

    private static void removeBlockingOverlay(Context app) {
        View view = sBlockerView;
        sBlockerView = null;
        if (view == null) {
            return;
        }
        try {
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.removeView(view);
            }
        } catch (Exception e) {
            Log.w("ProUtils", "removeBlockingOverlay failed", e);
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

        // Remember the geometry so a blocker dropped for a ringing call can be rebuilt afterwards
        // without an Activity (see setStatusBarBlockerEnabled).
        sBlockerHeightPx = statusBarHeight;
        removeBlockingOverlay(activity.getApplicationContext());
        sBlockerView = addBlockingOverlay(activity.getApplicationContext(), statusBarHeight);
        return sBlockerView;
    }

    private static View addBlockingOverlay(Context app, int statusBarHeight) {
        View blockingView = new BlockingBar(app);
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

        WindowManager windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
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

    /**
     * Blocks status-bar expansion without hiding the status bar.
     *
     * <p>Two layers, both of which leave the clock / battery / signal icons visible:
     * <ol>
     *   <li>{@code setStatusBarDisabled} (API 24+, device owner). Contrary to its name this does not
     *       blank the bar — the platform applies only {@code DISABLE_EXPAND},
     *       {@code DISABLE_NOTIFICATION_ICONS}, {@code DISABLE_NOTIFICATION_ALERTS},
     *       {@code DISABLE_SEARCH} and {@code DISABLE2_QUICK_SETTINGS}, so system info stays put
     *       while the shade and quick settings become unreachable.</li>
     *   <li>A transparent touch blocker over the status-bar strip, which is what covers Android 6
     *       (API 23), where {@code setStatusBarDisabled} does not exist.</li>
     * </ol>
     *
     * <p>REQUIREMENT CHANGE — this now runs in kiosk mode too. It used to bail out when kiosk mode
     * was configured, because lock-task did the blocking; with lock-task gone these two layers are
     * the entire mechanism, so they must always be applied.
     *
     * <p>Deliberately does NOT hide the bars: the earlier Android 12+ immersive/fullscreen path is
     * commented out below because hiding the bar is what produced the black status bar that only
     * revealed itself on hover/swipe. Blocking expansion is the requirement; hiding is not.
     */
    public static View preventStatusBarExpansion(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }

        applyDevicePolicyStatusBarLock(activity);

        View overlayView = addBlockingOverlay(activity);

        // If a call started ringing while the launcher was coming up, hand the state straight back
        // to the lift (which drops the blocker again) instead of leaving the shade blocked.
        if (com.brother.pharmach.mdm.launcher.helper.CallStatusBarLift.isLifted()) {
            setStatusBarBlockerEnabled(activity, false);
            overlayView = null;
        }

        return overlayView;
    }

    /* DISABLED — hiding the system bars is what caused the black status bar that only appeared on
     * hover. Kept for reference; preventStatusBarExpansion() above now blocks expansion while
     * leaving the bar (clock, battery, signal) fully visible.
    private static void hideSystemBarsLegacy(Activity activity) {
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
    }
    */

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

    /**
     * True when kiosk mode is ENGAGED.
     *
     * <p>REQUIREMENT CHANGE: the app no longer uses Android lock-task (pinning) at all, so this can
     * no longer be answered by asking the platform. Kiosk mode is now purely our own policy —
     * launcher pinned as the unchangeable home app + status bar locked — so "engaged" means "the
     * server config asks for kiosk mode". A genuine lock-task state is still ORed in so that a
     * device upgrading from a build that DID pin keeps behaving correctly until we drop out of it.
     *
     * <p>Defining it this way keeps every existing caller correct: the launcher render path, the
     * lock-task allowlist upkeep, and the kiosk flag reported to the MDM server all continue to see
     * kiosk mode as active when the admin has switched it on. Callers that need the literal platform
     * state must use {@link #isInLockTask(Context)} instead.
     */
    public static boolean isKioskModeRunning(Context context) {
        return kioskModeRequired(context) || isInLockTask(context);
    }

    /** The literal platform lock-task (screen pinning) state. Should always be false now. */
    public static boolean isInLockTask(Context context) {
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

    /**
     * Leaves lock-task mode if the device is somehow in it — the upgrade path from a build that
     * called {@code startLockTask()}, since lock-task survives an in-place app update. Called early
     * in the launcher lifecycle so a device updating into this build drops out of pinning on the
     * first render instead of staying pinned forever.
     */
    public static void ensureNotInLockTask(Activity activity) {
        if (activity == null || !isInLockTask(activity)) {
            return;
        }
        try {
            activity.stopLockTask();
            Log.i("ProUtils", "Left lock-task mode — lock task is no longer used by this build");
        } catch (Exception e) {
            Log.w("ProUtils", "stopLockTask failed", e);
        }
    }

    public static Intent getKioskAppIntent(String kioskApp, Activity activity) {
        try {
            return activity.getPackageManager().getLaunchIntentForPackage(kioskApp);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Engage kiosk mode.
     *
     * <p>REQUIREMENT CHANGE — NO LOCK TASK: this used to call {@code startLockTask()} (Android
     * screen pinning / COSU). It no longer does, and nothing else in the app does either. Kiosk mode
     * is now enforced entirely by our own policy:
     * <ul>
     *   <li>the launcher is pinned as the unchangeable default home app
     *       ({@code addPersistentPreferredActivity}, see DevicePolicyBootstrapper /
     *       Utils.setDefaultLauncher), so Home always returns here and the user cannot switch it;</li>
     *   <li>the status bar cannot be expanded ({@code setStatusBarDisabled} + our touch blocker),
     *       except while a call is ringing (see CallStatusBarLift).</li>
     * </ul>
     * Dropping lock-task also removes its side effects: no "screen pinned" system UI, Recents and
     * Home behave normally, whitelisted-app launching is no longer silently filtered, and — the
     * reason this matters most here — the shade lift for a ringing call now takes effect on Android
     * 7+ instead of only Android 9+, because {@code setStatusBarDisabled} is no longer deferred by
     * the framework while lock-task is active.
     *
     * <p>The lock-task allowlist is still maintained (a cheap, idempotent DPM call) so that turning
     * pinning back on would need only the one commented-out line below.
     */
    public static boolean startCosuKioskMode(String kioskApp, Activity activity, boolean enableSettings) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(activity, AdminReceiver.class);
            if (dpm != null && dpm.isDeviceOwnerApp(activity.getPackageName())) {
                // DPM binder calls (setLockTaskPackages, setLockTaskFeatures) are 10-50ms each —
                // run them off the main thread so the launcher renders immediately.
                final Context app = activity.getApplicationContext();
                final String ownPkg = activity.getPackageName();
                DPM_EXECUTOR.execute(() -> {
                    try {
                        java.util.LinkedHashSet<String> pkgSet = new java.util.LinkedHashSet<>();
                        pkgSet.add(ownPkg);
                        pkgSet.add(kioskApp);
                        pkgSet.addAll(com.brother.pharmach.mdm.launcher.util.Utils.getPhoneCallPackages(app));
                        String[] packages = pkgSet.toArray(new String[0]);
                        dpm.setLockTaskPackages(adminComponent, packages);
                        updateKioskOptionsSync(app);
                    } catch (Exception e) {
                        Log.e("ProUtils", "Background kiosk DPM setup failed", e);
                    }
                });

                ensureNotInLockTask(activity);

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

    // Set/update kiosk mode options (lock task features).
    // Safe to call from any thread: DPM work is dispatched to DPM_EXECUTOR.
    public static void updateKioskOptions(Context context) {
        final Context app = context.getApplicationContext();
        DPM_EXECUTOR.execute(() -> updateKioskOptionsSync(app));
    }

    private static void updateKioskOptionsSync(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(context, AdminReceiver.class);
            if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    int flags = com.brother.pharmach.mdm.launcher.helper.CallStatusBarLift
                            .currentLockTaskFeatures();
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
        final Context appContext = activity.getApplicationContext();
        DPM_EXECUTOR.execute(() -> {
            java.util.List<String> pkgs = new java.util.ArrayList<>();
            if (kioskApp != null && !kioskApp.trim().isEmpty()) {
                pkgs.add(kioskApp);
            }
            try {
                for (com.brother.pharmach.mdm.launcher.util.AppInfo info :
                        com.brother.pharmach.mdm.launcher.ui.AppShortcutManager.getInstance().getInstalledApps(appContext, false)) {
                    if (info.type == com.brother.pharmach.mdm.launcher.util.AppInfo.TYPE_APP
                            && info.packageName != null && !info.packageName.trim().isEmpty()) {
                        pkgs.add(info.packageName);
                    }
                }
                for (com.brother.pharmach.mdm.launcher.util.AppInfo info :
                        com.brother.pharmach.mdm.launcher.ui.AppShortcutManager.getInstance().getInstalledApps(appContext, true)) {
                    if (info.type == com.brother.pharmach.mdm.launcher.util.AppInfo.TYPE_APP
                            && info.packageName != null && !info.packageName.trim().isEmpty()) {
                        pkgs.add(info.packageName);
                    }
                }
            } catch (Exception e) {
                Log.w("ProUtils", "updateKioskAllowedApps: failed to enumerate launcher apps", e);
            }
            setKioskLockTaskWhitelistSync(appContext, pkgs);
        });
    }

    /**
     * Rebuilds the lock-task whitelist as {launcher package} + the given allowed packages.
     * Safe to call on every render; setLockTaskPackages() is a cheap, idempotent DPM call.
     * The launcher package is always included so the home surface itself never gets locked out.
     */
    public static void setKioskLockTaskWhitelist(Activity activity, java.util.Collection<String> allowedPackages) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        final Context app = activity.getApplicationContext();
        final java.util.ArrayList<String> pkgSnapshot = allowedPackages != null
                ? new java.util.ArrayList<>(allowedPackages)
                : null;
        DPM_EXECUTOR.execute(() -> setKioskLockTaskWhitelistSync(app, pkgSnapshot));
    }

    private static void setKioskLockTaskWhitelistSync(Context context, java.util.Collection<String> allowedPackages) {
        try {
            if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return;
            }
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(context, AdminReceiver.class);
            if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
                return;
            }
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            set.add(context.getPackageName());
            set.addAll(com.brother.pharmach.mdm.launcher.util.Utils.getPhoneCallPackages(context));
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
        if (activity == null || packageName == null || packageName.trim().isEmpty()
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        if (!isKioskModeRunning(activity)) {
            return;
        }
        final Context app = activity.getApplicationContext();
        final String ownPkg = activity.getPackageName();
        DPM_EXECUTOR.execute(() -> {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName adminComponent = new ComponentName(app, AdminReceiver.class);
                if (dpm == null || !dpm.isDeviceOwnerApp(ownPkg)) {
                    return;
                }
                String[] current = dpm.getLockTaskPackages(adminComponent);
                java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
                set.add(ownPkg);
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
        });
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
