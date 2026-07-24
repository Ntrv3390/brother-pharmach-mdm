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

package com.brother.pharmach.mdm.launcher.helper;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import com.brother.pharmach.mdm.launcher.AdminReceiver;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;
import com.brother.pharmach.mdm.launcher.util.Utils;

/**
 * Makes the app the system default dialer (default phone app) — the only supported Android
 * mechanism for a custom {@code InCallService} to receive cellular calls and draw its own UI.
 *
 * <p>Three branches (§3):
 * <ul>
 *   <li>Device Owner (any API): grant call runtime permissions silently, then try a silent role
 *       set (works on platform-signed / privileged deployments), else fall back to the request.</li>
 *   <li>API 29+ : {@code RoleManager.createRequestRoleIntent(ROLE_DIALER)}.</li>
 *   <li>API 23-28: {@code TelecomManager.ACTION_CHANGE_DEFAULT_DIALER}.</li>
 * </ul>
 *
 * <p>Honesty note: a fully prompt-free dialer set is only guaranteed on platform-signed /
 * privileged builds (this app ships with {@code sharedUserId} + privileged telephony permissions).
 * On a plain device-owner install the reflective silent set may be refused, in which case the
 * one-time system role dialog ({@link #requestDefaultDialer(Activity, int)}) is required.
 */
public final class DefaultDialerHelper {

    private static final String TAG = "DefaultDialerHelper";

    private static final String PREFS = "CallReceiverPrefs";
    private static final String PREF_PERMS_PROMPTED = "call_perms_prompted";
    private static final String PREF_DIALER_PROMPTED = "dialer_prompted";
    private static final String PREF_FSI_PROMPTED = "fsi_prompted";

    /** Request codes surfaced to the hosting Activity's onActivityResult / onRequestPermissionsResult. */
    public static final int REQUEST_CALL_PERMISSIONS = 1471;
    public static final int REQUEST_DEFAULT_DIALER = 1472;

    private DefaultDialerHelper() {}

    /**
     * Silent setup path only: when we are Device Owner, grant the call permissions and set the
     * dialer role with <b>zero popups</b>. For non-DO devices this does nothing on its own — the
     * blocking {@code DefaultDialerGatekeeperActivity} owns the interactive flow so the user cannot
     * proceed until the app is the default phone app.
     *
     * @return true if the app is the default dialer after this call.
     */
    public static boolean ensureCallSetup(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false; // No InCallService below API 23 — nothing to set up.
        }
        if (Utils.isDeviceOwner(context)) {
            grantCallPermissions(context);
            if (!isDefaultDialer(context)) {
                trySilentSet(context);
            }
        }
        return isDefaultDialer(context);
    }

    /**
     * True when the app SHOULD be (but is not yet) the default dialer, i.e. the enforcement
     * gatekeeper must be shown. False on devices without telephony / the dialer role (tablets,
     * Wi-Fi-only) so we never trap a user on a device that can never satisfy the requirement, and
     * false once we already hold the role.
     */
    public static boolean shouldEnforceDefaultDialer(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
                if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    return false; // dialer role not offered on this device
                }
            } else {
                if (!context.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                    return false;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "shouldEnforceDefaultDialer probe failed: " + e.getMessage());
        }
        return !isDefaultDialer(context);
    }

    /** Dangerous runtime permissions the dialer needs that are not yet granted. */
    public static List<String> missingRuntimeCallPermissions(Context context) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return missing;
        }
        addIfMissing(context, missing, Manifest.permission.READ_PHONE_STATE);
        addIfMissing(context, missing, Manifest.permission.CALL_PHONE);
        addIfMissing(context, missing, Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addIfMissing(context, missing, Manifest.permission.ANSWER_PHONE_CALLS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(context, missing, Manifest.permission.POST_NOTIFICATIONS);
        }
        // READ_CALL_LOG is hard-restricted since Android 10; the OS auto-grants it to the dialer
        // role holder, so we intentionally do not block the flow on it.
        return missing;
    }

    private static void addIfMissing(Context context, List<String> out, String perm) {
        try {
            if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                out.add(perm);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * API 34+: if the OS is withholding USE_FULL_SCREEN_INTENT (only happens for non-role apps),
     * send the user once to the dedicated settings page. No-op when we already hold the dialer
     * role (which auto-grants it) or below API 34.
     */
    public static void ensureFullScreenIntentPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && !nm.canUseFullScreenIntent() && !wasPrompted(activity, PREF_FSI_PROMPTED)) {
                markPrompted(activity, PREF_FSI_PROMPTED);
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureFullScreenIntentPermission failed: " + e.getMessage());
        }
    }

    private static boolean wasPrompted(Context context, String key) {
        return prefs(context).getBoolean(key, false);
    }

    private static void markPrompted(Context context, String key) {
        prefs(context).edit().putBoolean(key, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True when this package currently holds the default-dialer role. */
    public static boolean isDefaultDialer(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    return rm.isRoleHeld(RoleManager.ROLE_DIALER);
                }
            }
            TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            return tm != null && context.getPackageName().equals(tm.getDefaultDialerPackage());
        } catch (Exception e) {
            Log.w(TAG, "isDefaultDialer check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Provisioning entry point. Grants the call permissions the dialer needs (silently, as device
     * owner) and attempts a silent role set. Safe to call repeatedly and on any API level.
     *
     * @return true if the app is default dialer after this call.
     */
    public static boolean ensureDefaultDialer(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        grantCallPermissions(context);
        if (isDefaultDialer(context)) {
            return true;
        }
        boolean ok = trySilentSet(context);
        if (ok) {
            RemoteLogger.log(context, Const.LOG_INFO, "App set as default dialer silently");
        } else {
            Log.i(TAG, "Silent dialer set unavailable; interactive request required");
        }
        return isDefaultDialer(context);
    }

    /**
     * Grants the runtime permissions Telecom expects from the default dialer. Device-owner only;
     * a no-op otherwise. This deliberately GRANTS POST_NOTIFICATIONS (overriding the kiosk-default
     * deny in {@code DevicePolicyBootstrapper}) so the full-screen-intent notification is delivered.
     */
    public static void grantCallPermissions(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Utils.isDeviceOwner(context)) {
            return;
        }
        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return;
        }
        ComponentName admin = new ComponentName(context, AdminReceiver.class);
        String pkg = context.getPackageName();

        grant(dpm, admin, pkg, Manifest.permission.READ_PHONE_STATE);
        grant(dpm, admin, pkg, Manifest.permission.CALL_PHONE);
        grant(dpm, admin, pkg, Manifest.permission.READ_CONTACTS);
        grant(dpm, admin, pkg, Manifest.permission.READ_CALL_LOG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            grant(dpm, admin, pkg, Manifest.permission.ANSWER_PHONE_CALLS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grant(dpm, admin, pkg, Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private static void grant(DevicePolicyManager dpm, ComponentName admin, String pkg, String perm) {
        try {
            dpm.setPermissionGrantState(admin, pkg, perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        } catch (Exception e) {
            Log.w(TAG, "grant " + perm + " failed: " + e.getMessage());
        }
    }

    /**
     * Best-effort silent role set for device owner / privileged deployments. Tries the modern
     * RoleManager.addRoleHolderAsUser (needs MANAGE_ROLE_HOLDERS — platform) then the legacy
     * TelecomManager path, all reflectively so the code compiles on the public SDK. Returns true
     * only if the app actually became the default dialer.
     */
    private static boolean trySilentSet(Context context) {
        if (!Utils.isDeviceOwner(context)) {
            return false;
        }
        String pkg = context.getPackageName();

        // Path 1 (API 29+): RoleManager.addRoleHolderAsUser(ROLE_DIALER, pkg, 0, user, executor, cb)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                RoleManager rm = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
                if (rm != null) {
                    java.lang.reflect.Method m = RoleManager.class.getMethod(
                            "addRoleHolderAsUser",
                            String.class, String.class, int.class,
                            android.os.UserHandle.class,
                            java.util.concurrent.Executor.class,
                            java.util.function.Consumer.class);
                    m.invoke(rm, RoleManager.ROLE_DIALER, pkg, 0,
                            Process.myUserHandle(),
                            (java.util.concurrent.Executor) Runnable::run,
                            (java.util.function.Consumer<Boolean>) granted ->
                                    Log.i(TAG, "addRoleHolderAsUser result: " + granted));
                    // Give the framework a moment; verification happens by the caller.
                    if (isDefaultDialer(context)) {
                        return true;
                    }
                }
            } catch (Throwable t) {
                Log.i(TAG, "addRoleHolderAsUser unavailable: " + t.getMessage());
            }
        }

        // Path 2 (legacy): TelecomManager hidden setter, if the platform exposes one.
        try {
            TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) {
                try {
                    java.lang.reflect.Method m =
                            TelecomManager.class.getMethod("setDefaultDialer", String.class);
                    m.invoke(tm, pkg);
                    if (isDefaultDialer(context)) {
                        return true;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "TelecomManager.setDefaultDialer unavailable: " + t.getMessage());
        }
        return false;
    }

    /**
     * Interactive request (the reliable cross-OEM path when silent set is refused). Launches the
     * system role dialog; the result arrives in the activity's onActivityResult with requestCode.
     */
    public static void requestDefaultDialer(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isDefaultDialer(activity)) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    Intent intent = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    activity.startActivityForResult(intent, requestCode);
                    RemoteLogger.log(activity, Const.LOG_INFO,
                            "Default-dialer role picker shown (RoleManager.ROLE_DIALER)");
                    return;
                }
                RemoteLogger.log(activity, Const.LOG_WARN,
                        "ROLE_DIALER not available via RoleManager — using legacy change-dialer intent");
            }
            // API 23-28
            Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    activity.getPackageName());
            activity.startActivityForResult(intent, requestCode);
            RemoteLogger.log(activity, Const.LOG_INFO,
                    "Default-dialer request shown (ACTION_CHANGE_DEFAULT_DIALER)");
        } catch (Exception e) {
            Log.w(TAG, "requestDefaultDialer failed: " + e.getMessage());
            RemoteLogger.log(activity, Const.LOG_WARN,
                    "Default dialer request failed: " + e.getMessage());
        }
    }
}
