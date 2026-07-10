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

package com.brother.pharmach.mdm.launcher.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.brother.pharmach.mdm.launcher.BuildConfig;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.json.Action;
import com.brother.pharmach.mdm.launcher.json.ServerConfig;
import com.brother.pharmach.mdm.launcher.ui.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Utils {
    public static boolean isDeviceOwner(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    // In the open source variant, there are no flavors, so by default it's "opensource"
    public static String getLauncherVariant() {
        return BuildConfig.FLAVOR == null || BuildConfig.FLAVOR.equals("") ? "opensource" : BuildConfig.FLAVOR;
    }

    // Automatically grant permission to get phone state (for IMEI and serial)
    @TargetApi(Build.VERSION_CODES.M)
    public static boolean autoGrantPhonePermission(Context context) {
        try {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

            if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                    context.getPackageName(), Manifest.permission.READ_PHONE_STATE) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
                        context.getPackageName(), Manifest.permission.READ_PHONE_STATE, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                if (!success) {
                    return false;
                }
            }
            // READ_CALL_LOG and READ_SMS are hard-restricted since Android 10: the grant
            // fails unless the installer whitelisted them, so treat them as best-effort
            // and never fail the whole flow because of them.
            try {
                if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                        context.getPackageName(), Manifest.permission.READ_CALL_LOG) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                    devicePolicyManager.setPermissionGrantState(adminComponentName,
                            context.getPackageName(), Manifest.permission.READ_CALL_LOG, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (BuildConfig.ENABLE_SMS_LOG) {
                try {
                    if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                            context.getPackageName(), Manifest.permission.READ_SMS) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                        devicePolicyManager.setPermissionGrantState(adminComponentName,
                                context.getPackageName(), Manifest.permission.READ_SMS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                        context.getPackageName(), Manifest.permission.READ_PHONE_NUMBERS) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                    boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
                            context.getPackageName(), Manifest.permission.READ_PHONE_NUMBERS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    if (!success) {
                        return false;
                    }
                }
            }
        } catch (NoSuchMethodError e) {
            // This exception is raised on Android 5.1
            e.printStackTrace();
            return false;
        } catch (/* SecurityException */ Exception e) {
            // No active admin ComponentInfo (not sure why could that happen)
            e.printStackTrace();
            return false;
        }
        Log.i(Const.LOG_TAG, "READ_PHONE_STATE automatically granted");
        return true;
    }

    // Force "Allow all the time" location for the launcher itself in device owner mode.
    // Location tracking is a core MDM feature: this self-heals devices where the
    // background location grant was lost or never applied (e.g. because an earlier
    // permission grant failure aborted the auto-grant loop).
    // Foreground location is granted first, then background — the system rejects
    // the background grant if foreground location is not granted yet.
    @TargetApi(Build.VERSION_CODES.M)
    public static boolean autoGrantLocationPermissions(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !isDeviceOwner(context)) {
            return false;
        }
        try {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            String packageName = context.getPackageName();

            boolean ok = grantPermissionIfNeeded(devicePolicyManager, adminComponentName, packageName,
                    Manifest.permission.ACCESS_FINE_LOCATION);
            ok &= grantPermissionIfNeeded(devicePolicyManager, adminComponentName, packageName,
                    Manifest.permission.ACCESS_COARSE_LOCATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ok &= grantPermissionIfNeeded(devicePolicyManager, adminComponentName, packageName,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
            if (ok) {
                Log.i(Const.LOG_TAG, "Location permissions automatically granted (allow all the time)");
            }
            return ok;
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static boolean grantPermissionIfNeeded(DevicePolicyManager devicePolicyManager,
                                                   ComponentName adminComponentName,
                                                   String packageName, String permission) {
        try {
            if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                    packageName, permission) == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                return true;
            }
            boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
                    packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            if (!success) {
                Log.w(Const.LOG_TAG, "Failed to grant permission " + permission + " to package " + packageName);
            }
            return success;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to grant permission " + permission + " to package "
                    + packageName + ": " + e.getMessage());
            return false;
        }
    }

    // Automatically get dangerous permissions
    // Notice: default (null) app permission strategy is "Grant all"
    @TargetApi(Build.VERSION_CODES.M)
    public static boolean autoGrantRequestedPermissions(Context context, String packageName,
                                                        @Nullable String appPermissionStrategy,
                                                        boolean forceSdCardPermissions) {
        int locationPermissionState = DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;
        int otherPermissionsState = DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;

        // Determine the app permission strategy
        if (ServerConfig.APP_PERMISSIONS_ASK_LOCATION.equals(appPermissionStrategy)) {
            locationPermissionState = DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
        } else if (ServerConfig.APP_PERMISSIONS_DENY_LOCATION.equals(appPermissionStrategy)) {
            locationPermissionState = DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED;
        } else if (ServerConfig.APP_PERMISSIONS_ASK_ALL.equals(appPermissionStrategy)) {
            locationPermissionState = DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
            if (!packageName.equals(context.getPackageName())) {
                otherPermissionsState = DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
            }
        }

        // The launcher itself must always get location as "Allow all the time":
        // device location tracking is a core MDM feature, so the app permission
        // strategy configured for managed apps must not downgrade the launcher's
        // own location permissions.
        if (packageName.equals(context.getPackageName())) {
            locationPermissionState = DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            List<String> permissions = getRuntimePermissions(context.getPackageManager(), packageName);

            // Some devices do not include SD card permissions in the list of runtime permissions
            // So the files could not be read or written.
            // Here we add SD card permissions manually (device owner can grant them!)
            // This is done for the Brother Pharmamach MDM launcher only
            if (forceSdCardPermissions) {
                boolean hasReadExtStorage = false;
                boolean hasWriteExtStorage = false;
                for (String s : permissions) {
                    if (s.equals(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        hasReadExtStorage = true;
                    }
                    if (s.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        hasWriteExtStorage = true;
                    }
                }
                if (!hasReadExtStorage) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
                if (!hasWriteExtStorage) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }

            // ACCESS_BACKGROUND_LOCATION must be granted last: the system only accepts
            // the grant ("Allow all the time") when foreground location is already granted
            if (permissions.remove(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }

            boolean allGranted = true;
            for (String permission : permissions) {
                int permissionState = isLocationPermission(permission) ? locationPermissionState : otherPermissionsState;
                try {
                    if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                            packageName, permission) != permissionState) {
                        boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
                                packageName, permission, permissionState);
                        if (!success) {
                            // Continue with the remaining permissions: aborting here used to leave
                            // e.g. location permissions ungranted whenever a hard-restricted
                            // permission (READ_CALL_LOG / READ_SMS) earlier in the list failed
                            Log.w(Const.LOG_TAG, "Failed to grant permission " + permission + " to package " + packageName);
                            allGranted = false;
                        } else {
                            Log.d(Const.LOG_TAG, "Permission " + permission + " granted to package " + packageName);
                        }
                    }
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "Failed to grant permission " + permission + " to package "
                            + packageName + ": " + e.getMessage());
                    allGranted = false;
                }
            }
            if (!allGranted) {
                return false;
            }
        } catch (NoSuchMethodError e) {
            // This exception is raised on Android 5.1
            e.printStackTrace();
            return false;
        } catch (/* SecurityException */ Exception e) {
            // No active admin ComponentInfo (not sure why could that happen)
            e.printStackTrace();
            return false;
        }
        Log.i(Const.LOG_TAG, "Permissions automatically granted");
        return true;
    }

    public static boolean isLocationPermission(String permission) {
        return Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission) ||
               Manifest.permission.ACCESS_FINE_LOCATION.equals(permission) ||
               Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission);
    }

    private static List<String> getRuntimePermissions(PackageManager packageManager, String packageName) {
        List<String> permissions = new ArrayList<>();
        PackageInfo packageInfo;
        try {
            packageInfo =
                    packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            return permissions;
        }

        boolean manageStorage = false;
        if (packageInfo != null && packageInfo.requestedPermissions != null) {
            for (String requestedPerm : packageInfo.requestedPermissions) {
                if (requestedPerm.equals(Manifest.permission.MANAGE_EXTERNAL_STORAGE)) {
                    manageStorage = true;
                }
                if (isRuntimePermission(packageManager, requestedPerm)) {
                    permissions.add(requestedPerm);
                }
            }
            // There's a bug in Android 11+: MANAGE_EXTERNAL_STORAGE can't be automatically granted
            // but if Brother Pharmamach MDM is granting WRITE_EXTERNAL_STORAGE, then the app can't request
            // MANAGE_EXTERNAL_STORAGE, it's locked!
            // So the workaround is do not request WRITE_EXTERNAL_STORAGE in this case
            if (manageStorage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                permissions.removeIf(s -> (s.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                        s.equals(Manifest.permission.READ_EXTERNAL_STORAGE)));
            }
        }
        return permissions;
    }

    private static boolean isRuntimePermission(PackageManager packageManager, String permission) {
        try {
            PermissionInfo pInfo = packageManager.getPermissionInfo(permission, 0);
            if (pInfo != null) {
                if ((pInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                        == PermissionInfo.PROTECTION_DANGEROUS) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        return false;
    }

    public static int OverlayWindowType() {
        // https://stackoverflow.com/questions/45867533/system-alert-window-permission-on-api-26-not-working-as-expected-permission-den
        if (  Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        }
    }

    public static boolean isLightColor(int color) {
        final int THRESHOLD = 0xA0;
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return red >= THRESHOLD && green >= THRESHOLD && blue >= THRESHOLD;
    }

    @TargetApi(Build.VERSION_CODES.M)
    public static void setSystemUpdatePolicy(Context context, int systemUpdateType, String scheduledFrom, String scheduledTo) {
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager)context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName deviceAdmin = LegacyUtils.getAdminComponentName(context);

        SystemUpdatePolicy currentPolicy = null;
        try {
            currentPolicy = devicePolicyManager.getSystemUpdatePolicy();
        } catch (NoSuchMethodError e) {
            // This exception is raised on Android 5.1
            Log.e(Const.LOG_TAG, "Failed to set system update policy: " + e.getMessage());
            return;
        }
        if (currentPolicy != null) {
            // Check if policy type shouldn't be changed
            if (systemUpdateType == ServerConfig.SYSTEM_UPDATE_INSTANT && currentPolicy.getPolicyType() == SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC ||
                systemUpdateType == ServerConfig.SYSTEM_UPDATE_MANUAL && currentPolicy.getPolicyType() == SystemUpdatePolicy.TYPE_POSTPONE) {
                return;
            }
        }
        SystemUpdatePolicy newPolicy = null;
        switch (systemUpdateType) {
            case ServerConfig.SYSTEM_UPDATE_INSTANT:
                newPolicy = SystemUpdatePolicy.createAutomaticInstallPolicy();
                break;
            case ServerConfig.SYSTEM_UPDATE_SCHEDULE:
                // Here we use update window times
                if (scheduledFrom != null && scheduledTo != null) {
                    int windowStart = getMinutesFromString(scheduledFrom);
                    int windowEnd = getMinutesFromString(scheduledTo);
                    if (windowStart == -1) {
                        Log.e(Const.LOG_TAG, "Ignoring scheduled system update policy: wrong start time: " + scheduledFrom);
                        return;
                    }
                    if (windowEnd == -1) {
                        Log.e(Const.LOG_TAG, "Ignoring scheduled system update policy: wrong end time: " + scheduledFrom);
                        return;
                    }
                    newPolicy = SystemUpdatePolicy.createWindowedInstallPolicy(windowStart, windowEnd);
                } else {
                    Log.e(Const.LOG_TAG, "Ignoring scheduled system update policy: update window is not set on server");
                    return;
                }
                break;
            case ServerConfig.SYSTEM_UPDATE_MANUAL:
                newPolicy = SystemUpdatePolicy.createPostponeInstallPolicy();
                break;
        }
        try {
            devicePolicyManager.setSystemUpdatePolicy(deviceAdmin, newPolicy);
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "Failed to set system update policy: " + e.getMessage());
        }
    }

    private static int getMinutesFromString(String s) {
        try {
            // s has a fixed format: hh:mm with heading zeroes
            String hours = s.substring(0, 2);
            String minutes = s.substring(3, 5);
            int h = Integer.parseInt(hours);
            int m = Integer.parseInt(minutes);
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean canInstallPackages(Context context) {
        if (BuildConfig.SYSTEM_PRIVILEGES) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Global setting works for Android 7 and below
            try {
                return Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS) == 1;
            } catch (Settings.SettingNotFoundException e) {
                return true;
            }
        } else {
            return context.getPackageManager().canRequestPackageInstalls();
        }
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context);
    }

    public static boolean checkAdminMode(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            boolean isAdminActive = dpm.isAdminActive(adminComponentName);
//            RemoteLogger.log(context, Const.LOG_DEBUG, "Admin component active: " + isAdminActive);
            return isAdminActive;
        } catch (Exception e) {
//            RemoteLogger.log(context, Const.LOG_WARN, "Failed to get device administrator status: " + e.getMessage());
            return true;
        }
    }

    public static boolean factoryReset(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                dpm.wipeData(0);
            } else {
                dpm.wipeDevice(0);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean reboot(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            dpm.reboot(adminComponentName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getDataToken(Context context) {
        String token = context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE).getString(Const.PREFERENCES_DATA_TOKEN, null);
        if (token == null) {
            token = java.util.UUID.randomUUID().toString();
            context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putString(Const.PREFERENCES_DATA_TOKEN, token)
                    .commit();
        }
        return token;
    }

    public static void initPasswordReset(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                String token = getDataToken(context);
                DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
                if (dpm.setResetPasswordToken(adminComponentName, token.getBytes())) {
                    if (!dpm.isResetPasswordTokenActive(adminComponentName)) {
                        RemoteLogger.log(context, Const.LOG_WARN, "Password reset token will be activated once the user enters the current password next time.");
                    }
                } else {
                    RemoteLogger.log(context, Const.LOG_WARN, "Failed to setup password reset token, password reset requests will fail");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static boolean passwordReset(Context context, String password) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
                boolean tokenActive = dpm.isResetPasswordTokenActive(adminComponentName);
                if (!tokenActive) {
                    return false;
                }
                return dpm.resetPasswordWithToken(adminComponentName, password, getDataToken(context).getBytes(), 0);
            } else {
                return dpm.resetPassword(password, 0);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isMobileDataEnabled(Context context) {
        // Public API, works up to Android 16. The old reflection hack
        // (ConnectivityManager.getMobileDataEnabled) is blocked by hidden-API enforcement
        // since Android 9 and silently reported "enabled" on every modern device,
        // which killed the mobile data policy enforcement on Android 15.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
                    if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        return tm.createForSubscriptionId(dataSubId).isDataEnabled();
                    }
                    // No default data subscription (common mid-swap on dual-SIM / eSIM devices).
                    // Consider data enabled if ANY active subscription has it enabled, so the
                    // watchdog does not fire a false violation during the switch window.
                    try {
                        SubscriptionManager sm = (SubscriptionManager)
                                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                        List<SubscriptionInfo> subs =
                                sm != null ? sm.getActiveSubscriptionInfoList() : null;
                        if (subs != null && !subs.isEmpty()) {
                            for (SubscriptionInfo sub : subs) {
                                if (tm.createForSubscriptionId(sub.getSubscriptionId()).isDataEnabled()) {
                                    return true;
                                }
                            }
                            return false;
                        }
                    } catch (Exception ignored) {
                        // Fall back to the default-manager reading below.
                    }
                    return tm.isDataEnabled();
                }
            } catch (Exception e) {
                // Fall through to the settings-based check
            }
        }

        // Settings fallback: single-SIM stores "mobile_data", multi-SIM capable devices
        // store "mobile_data<subId>"
        try {
            int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
            String perSubKey = "mobile_data" + dataSubId;
            String value = Settings.Global.getString(context.getContentResolver(), perSubKey);
            if (value == null) {
                value = Settings.Global.getString(context.getContentResolver(), "mobile_data");
            }
            if (value != null) {
                return "1".equals(value);
            }
        } catch (Exception e) {
            // ignore
        }

        // Legacy reflection for pre-Oreo devices
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Class clazz = Class.forName(cm.getClass().getName());
            Method method = clazz.getDeclaredMethod("getMobileDataEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(cm);
        } catch (Exception e) {
            // Let it be true by default
            return true;
        }
    }

    /**
     * Programmatically enable or disable mobile data, per active SIM subscription.
     *
     * Tries, in order (all require MODIFY_PHONE_STATE or carrier privileges — granted when the
     * launcher is installed as priv-app / signed with the platform key; stock device owner has
     * no direct toggle API up to and including Android 16):
     *  1. TelephonyManager.setDataEnabledForReason(DATA_ENABLED_REASON_USER)  (API 31+)
     *  2. TelephonyManager.setDataEnabled                                     (API 26-30, public)
     *  3. TelephonyManager.setDataEnabled / ConnectivityManager.setMobileDataEnabled (reflection,
     *     legacy ROMs)
     *
     * Returns true if at least one method executed without throwing. Callers must verify the
     * actual state afterwards via isMobileDataEnabled() — an accepted call can still be a no-op.
     */
    public static boolean setMobileDataEnabled(Context context, boolean enabled) {
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            return false;
        }

        boolean invoked = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Apply to every active subscription so multi-SIM / eSIM devices are covered
            List<TelephonyManager> targets = new ArrayList<>();
            try {
                SubscriptionManager sm = (SubscriptionManager)
                        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                List<SubscriptionInfo> subs = sm != null ? sm.getActiveSubscriptionInfoList() : null;
                if (subs != null) {
                    for (SubscriptionInfo sub : subs) {
                        targets.add(tm.createForSubscriptionId(sub.getSubscriptionId()));
                    }
                }
            } catch (Exception ignored) {
                // No READ_PHONE_STATE or subscription info unavailable — use the default manager
            }
            if (targets.isEmpty()) {
                targets.add(tm);
            }

            for (TelephonyManager target : targets) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        target.setDataEnabledForReason(
                                TelephonyManager.DATA_ENABLED_REASON_USER, enabled);
                        invoked = true;
                        continue;
                    } catch (Exception ignored) {}
                }
                try {
                    target.setDataEnabled(enabled);
                    invoked = true;
                } catch (Exception ignored) {}
            }
            if (invoked) {
                return true;
            }
        }

        // Legacy fallbacks for old ROMs
        try {
            Method method = TelephonyManager.class
                    .getDeclaredMethod("setDataEnabled", boolean.class);
            method.setAccessible(true);
            method.invoke(tm, enabled);
            return true;
        } catch (Exception ignored) {}

        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Method method = Class.forName(cm.getClass().getName())
                        .getDeclaredMethod("setMobileDataEnabled", boolean.class);
                method.setAccessible(true);
                method.invoke(cm, enabled);
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    public static boolean isSimAbsent(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_ABSENT;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if at least one usable SIM (physical or eSIM, either slot) is present.
     *
     * A SIM counts as "valid" only when it is both active (present AND enabled — this excludes
     * empty slots and downloaded-but-disabled eSIM profiles, which never appear in
     * getActiveSubscriptionInfoList()) and its slot is in a READY/LOADED state. PIN/PUK-locked,
     * NOT_READY, CARD_IO_ERROR and PERM_DISABLED SIMs are treated as not-yet-valid so the
     * enforcement engine idles instead of nagging the user behind a PIN prompt.
     *
     * This is stricter than isSimAbsent(), which only inspects the default slot and so misses a
     * SIM present in slot 2 only, or a locked SIM that reports "not absent".
     */
    public static boolean hasValidSim(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                return false;
            }
            // Inspect each physical SIM slot's state directly. getSimState(slot) needs no special
            // permission and reflects a physically-present, unlocked SIM, unlike
            // getActiveSubscriptionInfoList() which requires READ_PHONE_STATE and can return
            // empty even when a usable SIM is inserted (that earlier caused enforcement to idle
            // and the "enable mobile data" prompt to never appear). SIM_STATE_READY excludes
            // empty slots and PIN/PUK-locked SIMs, matching the "valid SIM" definition.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int slots;
                try {
                    slots = tm.getPhoneCount();
                } catch (Exception e) {
                    slots = 1;
                }
                if (slots < 1) {
                    slots = 1;
                }
                for (int slot = 0; slot < slots; slot++) {
                    try {
                        if (tm.getSimState(slot) == TelephonyManager.SIM_STATE_READY) {
                            return true;
                        }
                    } catch (Exception ignored) {
                        // Some OEMs throw on out-of-range slots — keep probing the rest.
                    }
                }
                return false;
            }
            // Pre-Oreo: only the aggregate SIM state is available.
            return tm.getSimState() == TelephonyManager.SIM_STATE_READY;
        } catch (Exception e) {
            // On any failure fall back to the lenient "not absent" check rather than falsely
            // idling enforcement on a device where a SIM really is present.
            return !isSimAbsent(context);
        }
    }

    // Cached lazily: resolveActivity() is a PackageManager IPC call, and this helper runs once
    // per installed app during a mobile-data-violation app-block pass — it must not re-resolve
    // on every call.
    private static volatile String sSettingsPkgForMobileDataViolation;

    /**
     * Packages the user may still reach while a confirmed mobile-data policy violation is
     * active: system Settings (to fix it), phone/dialer/incall/telecom/emergency UI (calls must
     * never be blocked), System UI (status bar / quick settings / our own enforcement
     * notification render through it) and the permission-grant UI. Shared by
     * CheckForegroundAppAccessibilityService, StatusControlService.isUserInAllowedSettingsApp()
     * and MobileDataAppBlocker so the three enforcement paths can never disagree about what's
     * allowed.
     */
    public static boolean isAllowedDuringMobileDataViolation(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (sSettingsPkgForMobileDataViolation == null) {
            String resolved = null;
            try {
                ResolveInfo ri = context.getPackageManager().resolveActivity(
                        new Intent(Settings.ACTION_SETTINGS), 0);
                if (ri != null && ri.activityInfo != null) {
                    resolved = ri.activityInfo.packageName;
                }
            } catch (Exception ignored) {
            }
            sSettingsPkgForMobileDataViolation = resolved != null ? resolved : Const.SETTINGS_PACKAGE_NAME;
        }
        if (pkg.equals(sSettingsPkgForMobileDataViolation) || pkg.contains("settings")) {
            return true;
        }
        if (pkg.equals("com.android.phone")
                || pkg.contains("dialer")
                || pkg.contains("incall")
                || pkg.contains("telecom")
                || pkg.contains("emergency")) {
            return true;
        }
        return pkg.contains("systemui") || pkg.contains("permissioncontroller");
    }

    /**
     * Locks or unlocks the mobile data toggle for the user.
     * When locked=true:
     *  - DISALLOW_CONFIG_MOBILE_NETWORKS hides/disables the mobile data switch in the
     *    Settings app and in the Quick Settings internet panel (read-only for the user);
     *  - DISALLOW_AIRPLANE_MODE (Android 9+) closes the airplane-mode bypass that would
     *    otherwise kill mobile data without touching the mobile data toggle.
     * Device owner only; no-op on non-owner builds.
     */
    public static boolean setMobileDataLocked(boolean locked, Context context) {
        if (!isDeviceOwner(context)) {
            return false;
        }
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        if (dpm == null || admin == null) {
            return false;
        }
        try {
            if (locked) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE);
                }
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE);
                }
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isPackageInstalled(Context context, String targetPackage){
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(targetPackage,PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return true;
    }

    public static boolean isMiui(Context context) {
        return isPackageInstalled(context, "com.miui.home") ||
                isPackageInstalled(context, "com.miui.securitycenter");
    }

    public static boolean lockSafeBoot(Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_SAFE_BOOT);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean lockUsbStorage(boolean lock, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                // Deprecated way to lock USB
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.USB_MASS_STORAGE_ENABLED, 0);
                } else {
                    Settings.Global.putInt(context.getContentResolver(), Settings.Global.USB_MASS_STORAGE_ENABLED, 0);
                }
            } catch (Exception e) {
                return false;
            }
            return true;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            if (lock) {
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_USB_FILE_TRANSFER);
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            } else {
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_USB_FILE_TRANSFER);
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean setBrightnessPolicy(Boolean auto, Integer brightness, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            if (auto == null) {
                // This means we should unlock brightness
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_CONFIG_BRIGHTNESS);
            } else {
                // Managed brightness
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_CONFIG_BRIGHTNESS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // This option is available in Android 9 and above
                    if (auto) {
                        devicePolicyManager.setSystemSetting(adminComponentName, Settings.System.SCREEN_BRIGHTNESS_MODE, "1");
                    } else {
                        devicePolicyManager.setSystemSetting(adminComponentName, Settings.System.SCREEN_BRIGHTNESS_MODE, "0");
                        if (brightness != null) {
                            devicePolicyManager.setSystemSetting(adminComponentName, Settings.System.SCREEN_BRIGHTNESS, "" + brightness);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean setScreenTimeoutPolicy(Boolean lock, Integer timeout, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            if (lock == null || !lock) {
                // This means we should unlock screen timeout
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT);
            } else {
                // Managed screen timeout
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && timeout != null) {
                    // This option is available in Android 9 and above
                    devicePolicyManager.setSystemSetting(adminComponentName, Settings.System.SCREEN_OFF_TIMEOUT, "" + (timeout * 1000));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean lockVolume(Boolean lock, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            if (lock == null || !lock) {
                Log.d(Const.LOG_TAG, "Unlocking volume");
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_ADJUST_VOLUME);
            } else {
                Log.d(Const.LOG_TAG, "Locking volume");
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_ADJUST_VOLUME);
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to lock/unlock volume: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean setVolume(int percent, Context context) {
        int[] streams = {
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM
        };
        try {
            AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            for (int s : streams) {
                setVolumeInternal(audioManager, s, percent);

                int v = audioManager.getStreamVolume(s);
                if (v == 0) {
                    v = 1;
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to set volume: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static void setVolumeInternal(AudioManager audioManager, int stream, int percent) throws Exception {
        int maxVolume = audioManager.getStreamMaxVolume(stream);
        int volume = (maxVolume * percent) / 100;
        audioManager.setStreamVolume(stream, volume, 0);
    }

    public static boolean disableScreenshots(Boolean disabled, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            devicePolicyManager.setScreenCaptureDisabled(adminComponentName, disabled);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // Returns true if the current password is good enough, or false elsewhere
    public static boolean setPasswordMode(String passwordMode, Context context) {
        // This function works with a (deprecated) device admin as well
        // So we don't check that it has device owner rights!
        try {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

            if (passwordMode == null) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
            } else if (passwordMode.equals(Const.PASSWORD_QUALITY_PRESENT)) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC);
                devicePolicyManager.setPasswordMinimumLength(adminComponentName, 1);
            } else if (passwordMode.equals(Const.PASSWORD_QUALITY_EASY)) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC);
                devicePolicyManager.setPasswordMinimumLength(adminComponentName, 6);
            } else if (passwordMode.equals(Const.PASSWORD_QUALITY_MODERATE)) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC);
                devicePolicyManager.setPasswordMinimumLength(adminComponentName, 8);
            } else if (passwordMode.equals(Const.PASSWORD_QUALITY_STRONG)) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
                devicePolicyManager.setPasswordMinimumLowerCase(adminComponentName, 1);
                devicePolicyManager.setPasswordMinimumUpperCase(adminComponentName, 1);
                devicePolicyManager.setPasswordMinimumNumeric(adminComponentName, 1);
                devicePolicyManager.setPasswordMinimumSymbols(adminComponentName, 1);
                devicePolicyManager.setPasswordMinimumLength(adminComponentName, 8);
            }
            boolean result = devicePolicyManager.isActivePasswordSufficient();
            if (passwordMode != null) {
                RemoteLogger.log(context, Const.LOG_DEBUG, "Active password quality sufficient: " + result);
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            // If the app doesn't have enough rights, let's leave password quality as is
            if (passwordMode != null) {
                RemoteLogger.log(context, Const.LOG_WARN, "Failed to update password quality: " + e.getMessage());
            }
            return true;
        }
    }

    public static boolean setTimeZone(String timeZone, Context context) {
        if (!Utils.isDeviceOwner(context) || timeZone == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return true;
        }

        try {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

            if (timeZone.equals("auto")) {
                // Note: in Android 11, there is a special method for setting auto time zone
                devicePolicyManager.setGlobalSetting(adminComponentName, Settings.Global.AUTO_TIME_ZONE, "1");
            } else {
                devicePolicyManager.setGlobalSetting(adminComponentName, Settings.Global.AUTO_TIME_ZONE, "0");
                return devicePolicyManager.setTimeZone(adminComponentName, timeZone);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }
        return true;
    }

    @SuppressLint("SourceLockedOrientationActivity")
    public static void setOrientation(Activity activity, ServerConfig config) {
        String loggedOrientation = "unspecified";
        if (config.getOrientation() != null && config.getOrientation() != 0) {
            switch (config.getOrientation()) {
                case Const.SCREEN_ORIENTATION_PORTRAIT:
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    loggedOrientation = "portrait";
                    break;
                case Const.SCREEN_ORIENTATION_LANDSCAPE:
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    loggedOrientation = "landscape";
                    break;
                default:
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    break;
            }
        } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        Log.i(Const.LOG_TAG, "Set orientation: " + loggedOrientation);
    }

    public static boolean isLauncherIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        Set<String> categories = intent.getCategories();
        if (categories == null) {
            return false;
        }
        for (String c : categories) {
            if (c.equals(Intent.CATEGORY_LAUNCHER)) {
                return true;
            }
        }
        return false;
    }

    public static String getDefaultLauncher(Context context) {
        ActivityInfo defaultLauncherInfo = getDefaultLauncherInfo(context);
        if (defaultLauncherInfo != null) {
            return defaultLauncherInfo.packageName;
        } else {
            return null;
        }
    }

    public static ActivityInfo getDefaultLauncherInfo(Context context) {
        PackageManager localPackageManager = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = localPackageManager.resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null) {
            return null;
        }
        return info.activityInfo;
    }

    public static boolean isServiceRunning(Context context, Class<?> serviceClass) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> runningServices = manager.getRunningServices(Integer.MAX_VALUE);
            for (ActivityManager.RunningServiceInfo service : runningServices) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void setDefaultLauncher(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);

        ComponentName activity = new ComponentName(context, MainActivity.class);
        setPreferredActivity(context, filter, activity, "Set Brother Pharmamach MDM as default launcher");
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void clearDefaultLauncher(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);

        setPreferredActivity(context, filter, null, "Reset default launcher");
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void setAction(Context context, Action action) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter("android.intent.action." + action.getAction());

            if (action.getCategories() != null && action.getCategories().length() > 0) {
                String[] categories = action.getCategories().split(",");
                for (String category : categories) {
                    filter.addCategory("android.intent.category." + category);
                }
            }

            if (action.getMimeTypes() != null && action.getMimeTypes().length() > 0) {
                String[] mimeTypes = action.getMimeTypes().split(",");
                for (String mimeType : mimeTypes) {
                    try {
                        filter.addDataType(mimeType);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                    }
                }
            }

            if (action.getSchemes() != null && action.getSchemes().length() > 0) {
                String[] schemes = action.getSchemes().split(",");
                for (String scheme : schemes) {
                    filter.addDataScheme(scheme);
                }

                if (action.getHosts() != null && action.getHosts().length() > 0) {
                    String[] hosts = action.getHosts().split(",");
                    for (String host : hosts) {
                        String[] hostport = host.split(":");
                        switch (hostport.length) {
                            case 0:
                                break;
                            case 1:
                                filter.addDataAuthority(hostport[0], null);
                                break;
                            case 2:
                                filter.addDataAuthority(hostport[0], hostport[1]);
                                break;
                        }
                    }
                }
            }

            ComponentName activity = new ComponentName(action.getPackageId(), action.getActivity());
            if (activity != null) {
                setPreferredActivity(context, filter, activity, "Set " + action.getPackageId() + "/" + action.getActivity() + " as default for " + action.getAction());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static void setPreferredActivity(Context context, IntentFilter filter, ComponentName activity, String logMessage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        // Set the activity as the preferred option for the device.
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        try {
            if (activity != null) {
                dpm.addPersistentPreferredActivity(adminComponentName, filter, activity);
            } else {
                dpm.clearPackagePersistentPreferredActivities(adminComponentName, context.getPackageName());
            }
            RemoteLogger.log(context, Const.LOG_DEBUG, logMessage + " - success");
        } catch (Exception e) {
            e.printStackTrace();
            RemoteLogger.log(context, Const.LOG_WARN, logMessage + " - failure: " + e.getMessage());
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void releaseUserRestrictions(Context context, String restrictions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }

        String[] restrictionList = restrictions.split(",");
        for (String r : restrictionList) {
            try {
                dpm.clearUserRestriction(adminComponentName, r.trim());
            } catch (Exception e) {
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void lockUserRestrictions(Context context, String restrictions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }

        String[] restrictionList = restrictions.split(",");
        for (String r : restrictionList) {
            try {
                dpm.addUserRestriction(adminComponentName, r.trim());
            } catch (Exception e) {
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void unlockUserRestrictions(Context context, String restrictions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }

        String[] restrictionList = restrictions.split(",");
        for (String r : restrictionList) {
            try {
                dpm.clearUserRestriction(adminComponentName, r.trim());
            } catch (Exception e) {
            }
        }
    }

    // Setting proxyUrl=null clears the proxy previously set up
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public static boolean setProxy(Context context, String proxyUrl) {
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        try {
            ProxyInfo proxyInfo = null;
            if (proxyUrl != null) {
                String[] parts = proxyUrl.split(":");
                if (parts.length != 2) {
                    Log.d(Const.LOG_TAG, "Invalid proxy URL: " + proxyUrl);
                    return false;
                }
                int port = Integer.parseInt(parts[1]);
                proxyInfo = ProxyInfo.buildDirectProxy(parts[0], port);
            }
            dpm.setRecommendedGlobalProxy(adminComponentName, proxyInfo);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Load file content to String
     */
    public static String loadFileAsString(String filePath) throws java.io.IOException {
        StringBuffer fileData = new StringBuffer();
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        char[] buf = new char[1024];
        int numRead = 0;
        while((numRead = reader.read(buf)) != -1){
            String readData = String.valueOf(buf, 0, numRead);
            fileData.append(readData);
        }
        reader.close();
        return fileData.toString();
    }


    /**
     * Load input stream as String
     */
    public static String loadStreamAsString(InputStreamReader inputStreamReader) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(inputStreamReader);
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = reader.readLine()) != null) {
                sb.append(s + "\n");
            }
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    /**
     * Write String to file
     */
    public static boolean writeStringToFile(String fileName, String fileContent, boolean overwrite) {
        try {
            File file = new File(fileName);
            if (file.exists()) {
                if (overwrite) {
                    file.delete();
                } else {
                    return false;
                }
            }

            file.createNewFile();
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos);
            writer.append(fileContent);
            writer.close();
            fos.close();
            return true;
        } catch (Exception e) {
            return false;
        }

    }

    /**
     * Starting foreground service of special use
     */
    public static void startStableForegroundService(Service service, int notificationId, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = Utils.isDeviceOwner(service) ?
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED :
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            try {
                service.startForeground(notificationId, notification, serviceType);
            } catch (/* ForegroundServiceTypeNotAllowed*/Exception e) {
                // The first failure is usually a service-type mismatch — retry with SPECIAL_USE.
                // But if the real cause was a background-start restriction (Android 12+
                // ForegroundServiceStartNotAllowedException), the retry throws the same thing, and
                // an uncaught throw inside a service onCreate kills the process. Swallow the second
                // failure: the service simply runs without foreground status until it is next
                // (re)started from an allowed context.
                try {
                    service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } catch (Exception e2) {
                    Log.w(Const.LOG_TAG, "startStableForegroundService: startForeground blocked ("
                            + e2.getClass().getSimpleName() + ")");
                }
            }
        } else {
            try {
                service.startForeground(notificationId, notification);
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "startStableForegroundService: startForeground blocked ("
                        + e.getClass().getSimpleName() + ")");
            }
        }
    }

    /**
     * Lock or unlock packages
     */
    public static void lockPackages(Context context, String packages, boolean lock) {
        if (packages != null &&
                Utils.isDeviceOwner(context) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ComponentName deviceAdmin = LegacyUtils.getAdminComponentName(context);
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            packages.replace(" ", "");
            String[] pkgs = packages.split(",");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    devicePolicyManager.setPackagesSuspended(deviceAdmin, pkgs, lock);
                }
                for (String pkg : pkgs) {
                    devicePolicyManager.setApplicationHidden(deviceAdmin, pkg, lock);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Checks if a package is launchable (i.e. has a launcher activity/icon or is currently suspended/hidden).
     */
    public static boolean isAppLaunchable(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        PackageManager pm = context.getPackageManager();

        // If it has a launch intent, it's launchable
        if (pm.getLaunchIntentForPackage(packageName) != null) {
            return true;
        }

        // If it is suspended, it was suspended by the MDM and is launchable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (pm.isPackageSuspended(packageName)) {
                    return true;
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        // If it is hidden, it was hidden by the MDM and is launchable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName adminComponent = LegacyUtils.getAdminComponentName(context);
                if (dpm != null && adminComponent != null && dpm.isApplicationHidden(adminComponent, packageName)) {
                    return true;
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        return false;
    }

    /**
     * Ensures a package is unsuspended and unhidden so it can be launched immediately.
     * Call this before getLaunchIntentForPackage() when the intent returns null for an icon that
     * should be launchable (e.g. after WorkTime transitions).
     */
    public static void ensureAppUnsuspended(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }

        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = LegacyUtils.getAdminComponentName(context);
        boolean isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());

        if (!isDeviceOwner || dpm == null || adminComponent == null) {
            Log.w("Utils", "ensureAppUnsuspended: not device owner, cannot unsuspend " + packageName);
            return;
        }

        // Unsuspend via DPM (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                String[] suspended = dpm.setPackagesSuspended(adminComponent, new String[]{packageName}, false);
                if (suspended != null && suspended.length == 0) {
                    Log.i("Utils", "ensureAppUnsuspended: unsuspended " + packageName);
                } else {
                    Log.w("Utils", "ensureAppUnsuspended: setPackagesSuspended may have failed for " + packageName);
                }
            } catch (Exception e) {
                Log.e("Utils", "ensureAppUnsuspended: exception unsuspending " + packageName, e);
            }
        }

        // Unhide via DPM (API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                if (dpm.isApplicationHidden(adminComponent, packageName)) {
                    boolean result = dpm.setApplicationHidden(adminComponent, packageName, false);
                    Log.i("Utils", "ensureAppUnsuspended: unhide result=" + result + " for " + packageName);
                }
            } catch (Exception e) {
                Log.e("Utils", "ensureAppUnsuspended: exception unhiding " + packageName, e);
            }
        }
    }
}
