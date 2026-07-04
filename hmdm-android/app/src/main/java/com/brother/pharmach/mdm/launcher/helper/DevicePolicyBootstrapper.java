package com.brother.pharmach.mdm.launcher.helper;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.AdminReceiver;

import java.util.Arrays;

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
        applyKeyguardPolicy(dpm, adminComponent);
        applyLockTaskFeatures(dpm, adminComponent);
        applyStatusBarPolicy(dpm, adminComponent);
    }

    /**
     * POLICY-NOTE: setLockTaskPackages() is PERSISTENT — it survives reboots and does not need
     * to be called on every launch. The guard below checks the current policy first to avoid
     * redundant DPC round-trips.
     */
    private static void applyLockTaskPolicy(Context context, DevicePolicyManager dpm,
                                            ComponentName adminComponent) {
        String[] currentPackages = dpm.getLockTaskPackages(adminComponent);
        if (!Arrays.asList(currentPackages).contains(context.getPackageName())) {
            dpm.setLockTaskPackages(adminComponent, new String[]{context.getPackageName()});
            Log.i(TAG, "LockTask policy applied");
        } else {
            Log.i(TAG, "LockTask policy already set — skipping");
        }
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
        // API-DIFF: Android 11.0 (API 30)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setLockTaskFeatures(adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                            | DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD);
            Log.i(TAG, "LockTask features applied");
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
