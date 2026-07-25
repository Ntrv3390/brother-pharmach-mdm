package com.brother.pharmach.mdm.launcher.helper;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.AdminReceiver;

import java.util.Arrays;
import java.util.LinkedHashSet;

public final class DevicePolicyBootstrapper {

    private static final String TAG = "DPBootstrapper";

    private DevicePolicyBootstrapper() {}

    /**
     * Apply MDM device owner policies idempotently.
     *
     * This method is safe to call repeatedly — every DPC call is guarded to skip
     * if the policy is already applied, avoiding unnecessary round-trips.
     *
     * POLICY-NOTE: Call this once during initial provisioning (e.g. from AdminReceiver
     * or InitialSetupActivity). Do NOT call on every app launch.
     */
    public static void applyPolicies(Context context) {
        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            Log.e(TAG, "DevicePolicyManager unavailable — policy application skipped");
            return;
        }

        ComponentName adminComponent = new ComponentName(context, AdminReceiver.class);

        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            Log.e(TAG, "Not Device Owner — policy application skipped");
            return;
        }

        applyLockTaskPolicy(context, dpm, adminComponent);
        applyLocationPermissions(context, dpm, adminComponent);
        applyNotificationPolicy(context, dpm, adminComponent);
        applyKeyguardPolicy(dpm, adminComponent);
        applyLockTaskFeatures(dpm, adminComponent);
        applyStatusBarPolicy(dpm, adminComponent);
        applyDialerPreferredActivity(context, dpm, adminComponent);
        applyOverlayPermission(context);
        applyDefaultDialerPolicy(context);
    }

    /**
     * Grant "Display over other apps" (SYSTEM_ALERT_WINDOW) up front at provisioning so the overlay
     * gate and incoming-call overlay can render. Silent on platform/privileged builds; a no-op
     * (caught) on ordinary Device Owner, where MainActivity's mandatory dialog handles it later.
     * Cross-version: needed on API 23+ ; below that the permission is install-granted.
     */
    private static void applyOverlayPermission(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !com.brother.pharmach.mdm.launcher.util.Utils.canDrawOverlays(context)) {
                com.brother.pharmach.mdm.launcher.util.SystemUtils
                        .autoSetOverlayPermission(context, context.getPackageName());
                Log.i(TAG, "Overlay permission provisioning attempted, canDraw="
                        + com.brother.pharmach.mdm.launcher.util.Utils.canDrawOverlays(context));
            }
        } catch (Exception e) {
            Log.w(TAG, "applyOverlayPermission failed: " + e.getMessage());
        }
    }

    /**
     * SUPPLEMENTARY (not the telephony role): as Device Owner, pin our dial UI as the persistent
     * preferred activity for {@code ACTION_DIAL} / {@code tel:} intents via
     * {@link DevicePolicyManager#addPersistentPreferredActivity} (API 21). This is intent
     * <b>routing</b> only — it makes outgoing dial intents resolve to us and <b>persists across
     * updates</b>, but it does NOT grant ROLE_DIALER and does NOT bind our InCallService for
     * incoming calls. It keeps outbound dialing stable even on tier-B devices where the role is
     * dropped by an update until re-consented.
     */
    private static void applyDialerPreferredActivity(Context context, DevicePolicyManager dpm,
                                                     ComponentName admin) {
        try {
            android.content.ComponentName dialer = new android.content.ComponentName(
                    context, "com.brother.pharmach.mdm.launcher.ui.DialerActivity");
            // Clear our previous entries first so repeated provisioning doesn't stack duplicates.
            dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());

            android.content.IntentFilter dial = new android.content.IntentFilter(
                    android.content.Intent.ACTION_DIAL);
            dial.addCategory(android.content.Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, dial, dialer);

            android.content.IntentFilter dialTel = new android.content.IntentFilter(
                    android.content.Intent.ACTION_DIAL);
            dialTel.addCategory(android.content.Intent.CATEGORY_DEFAULT);
            dialTel.addDataScheme("tel");
            dpm.addPersistentPreferredActivity(admin, dialTel, dialer);

            Log.i(TAG, "Persistent preferred DIAL activity applied (intent routing only, NOT the role)");
        } catch (Exception e) {
            Log.w(TAG, "applyDialerPreferredActivity failed: " + e.getMessage());
        }
    }

    /**
     * Custom call receiver: make the app the default dialer so incoming cellular calls are routed
     * to our {@code CustomInCallService} and our full-screen UI. Runs LAST so that the call
     * permissions it grants (including POST_NOTIFICATIONS, needed for the full-screen-intent
     * notification) win over the kiosk-default deny applied in {@link #applyNotificationPolicy}.
     *
     * POLICY-NOTE: self-guards on device owner + API 23; idempotent (skips if already the dialer).
     */
    private static void applyDefaultDialerPolicy(Context context) {
        try {
            boolean ok = DefaultDialerHelper.ensureDefaultDialer(context);
            Log.i(TAG, "Default dialer policy applied, isDefaultDialer=" + ok);
        } catch (Exception e) {
            Log.w(TAG, "applyDefaultDialerPolicy failed: " + e.getMessage());
        }
    }

    /**
     * POLICY-NOTE: By default we keep our app notifications off on all devices.
     * On Android 13+ (API 33+), we deny the POST_NOTIFICATIONS permission for our own app
     * so that the OS suppresses all notifications from the status bar / shade.
     */
    private static void applyNotificationPolicy(Context context, DevicePolicyManager dpm,
                                                 ComponentName adminComponent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                dpm.setPermissionGrantState(adminComponent, context.getPackageName(),
                        Manifest.permission.POST_NOTIFICATIONS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED);
                Log.i(TAG, "POST_NOTIFICATIONS permission set to DENIED by default");
            } catch (Exception e) {
                Log.w(TAG, "Failed to set POST_NOTIFICATIONS permission grant state: " + e.getMessage());
            }
        }
    }

    /**
     * POLICY-NOTE: setLockTaskPackages() is PERSISTENT — it survives reboots and does not need
     * to be called on every launch. The guard below checks the current policy first to avoid
     * redundant DPC round-trips.
     */
    private static void applyLockTaskPolicy(Context context, DevicePolicyManager dpm,
                                            ComponentName adminComponent) {
        // The whitelist must include the phone/dialer packages. Otherwise, in strict lock-task
        // (COSU) mode the framework silently refuses the incoming-call activity, so an arriving
        // call rings but the accept/decline screen never appears.
        LinkedHashSet<String> desired = new LinkedHashSet<>();
        desired.add(context.getPackageName());   // launcher must always remain launchable
        desired.addAll(getPhonePackages(context));

        String[] currentPackages = dpm.getLockTaskPackages(adminComponent);
        if (currentPackages == null || !Arrays.asList(currentPackages).containsAll(desired)) {
            dpm.setLockTaskPackages(adminComponent, desired.toArray(new String[0]));
            Log.i(TAG, "LockTask policy applied (" + desired.size() + " packages)");
        } else {
            Log.i(TAG, "LockTask policy already set — skipping");
        }
    }

    /**
     * Resolves the phone/dialer packages that must stay launchable so incoming calls work in
     * lock-task mode: the current default dialer plus the AOSP telephony package.
     */
    private static LinkedHashSet<String> getPhonePackages(Context context) {
        // Delegate to the single authoritative resolver so the provisioning-time whitelist stays
        // in lockstep with the runtime kiosk whitelist (ProUtils) — both must always permit the
        // incoming-call UI so calls are never blocked in lock-task mode.
        return new LinkedHashSet<>(
                com.brother.pharmach.mdm.launcher.util.Utils.getPhoneCallPackages(context));
    }

    /**
     * POLICY-NOTE: setPermissionGrantState() is idempotent — re-setting the same state is safe.
     * The API 23 guard is required; the method does not exist below API 23.
     * API-DIFF: Android 6.0 (API 23)
     */
    private static void applyLocationPermissions(Context context, DevicePolicyManager dpm,
                                                 ComponentName adminComponent) {
        // API-DIFF: Android 6.0 (API 23) — runtime permission grant via DPC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dpm.setPermissionGrantState(adminComponent, context.getPackageName(),
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);

            // API-DIFF: Android 10.0 (API 29) — background location requires separate permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dpm.setPermissionGrantState(adminComponent, context.getPackageName(),
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            }
        }
    }

    /**
     * POLICY-NOTE: setKeyguardDisabled() is persistent but not idempotent in all cases.
     * Safe to re-call; the OS ignores it if the state hasn't changed.
     */
    private static void applyKeyguardPolicy(DevicePolicyManager dpm,
                                            ComponentName adminComponent) {
        dpm.setKeyguardDisabled(adminComponent, true);
        Log.i(TAG, "Keyguard disabled for kiosk operation");
    }

    /**
     * POLICY-NOTE: setLockTaskFeatures() is persistent. The API 30 guard is mandatory;
     * LOCK_TASK_FEATURE_NOTIFICATIONS and LOCK_TASK_FEATURE_KEYGUARD do not exist below API 28.
     * API-DIFF: Android 11.0 (API 30) — R constant used for the broadest safe guard.
     */
    private static void applyLockTaskFeatures(DevicePolicyManager dpm,
                                              ComponentName adminComponent) {
        // API-DIFF: Android 9.0 (API 28)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Disable status bar expansion by omitting LOCK_TASK_FEATURE_NOTIFICATIONS.
            // Enable SYSTEM_INFO to show time, battery and network icons.
            // Enable OVERVIEW to keep the Recents button functional.
            int flags = DevicePolicyManager.LOCK_TASK_FEATURE_HOME 
                    | DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
                    | DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                    | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
            dpm.setLockTaskFeatures(adminComponent, flags);
            Log.i(TAG, "LockTask features applied (HOME | KEYGUARD | SYSTEM_INFO | OVERVIEW)");
        }
    }

    /**
     * Disables the notification shade/status bar expansion for kiosk-style operation.
     * This is a best-effort device-owner mitigation; Android still reserves some system
     * notifications and privacy indicators for the framework itself.
     */
    private static void applyStatusBarPolicy(DevicePolicyManager dpm,
                                             ComponentName adminComponent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.setStatusBarDisabled(adminComponent, true);
            Log.i(TAG, "Status bar disabled for kiosk operation");
        }
    }
}
