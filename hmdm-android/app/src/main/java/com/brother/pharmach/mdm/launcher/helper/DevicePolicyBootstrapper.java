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
            // WARNING: clearPackagePersistentPreferredActivities() is all-or-nothing for this
            // package — there is no per-filter removal — so this ALSO drops the HOME pin that makes
            // us the unchangeable launcher. It must therefore be re-applied below, in the same
            // method, or kiosk mode silently loses its launcher lock on every provisioning run.
            dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());

            // HOME first: the launcher pin is the more important of the two.
            applyHomeLauncherPin(context, dpm, admin);

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
     * Pins this app as the device's home (launcher) app so it cannot be changed by the user.
     *
     * <p>This is the mechanism that replaces lock-task for kiosk mode: a persistent preferred
     * activity for {@code ACTION_MAIN + CATEGORY_HOME} is set by the Device Owner, survives reboots
     * and updates, and cannot be overridden from Settings — Home always comes back here, with no
     * "screen pinned" UI and with Recents left usable.
     *
     * <p>Skipped when the admin has asked for the stock launcher instead
     * ({@code runDefaultLauncher}), which is the same condition {@code ConfigUpdater} uses, so the
     * two never fight over the pin.
     */
    private static void applyHomeLauncherPin(Context context, DevicePolicyManager dpm,
                                             ComponentName admin) {
        try {
            com.brother.pharmach.mdm.launcher.json.ServerConfig config =
                    SettingsHelper.getInstance(context.getApplicationContext()).getConfig();
            if (config != null && Boolean.TRUE.equals(config.getRunDefaultLauncher())) {
                Log.i(TAG, "Home launcher pin skipped — config asks for the stock launcher");
                return;
            }
            android.content.IntentFilter home = new android.content.IntentFilter(
                    android.content.Intent.ACTION_MAIN);
            home.addCategory(android.content.Intent.CATEGORY_HOME);
            home.addCategory(android.content.Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, home,
                    new ComponentName(context, com.brother.pharmach.mdm.launcher.ui.MainActivity.class));
            Log.i(TAG, "Home launcher pinned — this app is the unchangeable default launcher");
        } catch (Exception e) {
            Log.w(TAG, "applyHomeLauncherPin failed: " + e.getMessage());
        }
    }

    /**
     * Call permissions for the launcher. Runs LAST so the permissions it grants (including
     * POST_NOTIFICATIONS) win over the kiosk-default deny applied in
     * {@link #applyNotificationPolicy}.
     *
     * <p>REQUIREMENT CHANGE: the app no longer needs to be the default dialer. Incoming calls are
     * presented by whichever dialer the device already uses, and {@link CallStatusBarLift} unlocks
     * the shade while the phone rings so that dialer's notification gets through. We still grant the
     * call permissions, because READ_PHONE_STATE is what lets us detect the ring and READ_CALL_LOG
     * is what feeds the call-log upload worker.
     *
     * POLICY-NOTE: self-guards on device owner + API 23; idempotent.
     */
    private static void applyDefaultDialerPolicy(Context context) {
        try {
            DefaultDialerHelper.grantCallPermissions(context);
            Log.i(TAG, "Call permissions granted (default-dialer role no longer required)");
            // Default-dialer role acquisition is intentionally disabled — see the note above.
            // boolean ok = DefaultDialerHelper.ensureDefaultDialer(context);
            // Log.i(TAG, "Default dialer policy applied, isDefaultDialer=" + ok);
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
            // The feature set is owned by CallStatusBarLift: normally it omits
            // LOCK_TASK_FEATURE_NOTIFICATIONS (blocking shade expansion + heads-up alerts), and
            // includes it only while a call is ringing. Reading it from there means provisioning
            // that happens to run mid-ring cannot clobber the lift.
            int flags = CallStatusBarLift.currentLockTaskFeatures();
            dpm.setLockTaskFeatures(adminComponent, flags);
            Log.i(TAG, "LockTask features applied (HOME | KEYGUARD | SYSTEM_INFO | OVERVIEW"
                    + (CallStatusBarLift.isLifted() ? " | NOTIFICATIONS (call ringing)" : "") + ")");
        }
    }

    /**
     * Disables the notification shade/status bar expansion for kiosk-style operation.
     * This is a best-effort device-owner mitigation; Android still reserves some system
     * notifications and privacy indicators for the framework itself.
     *
     * <p>Skipped while a call is ringing — {@link CallStatusBarLift} owns the status bar state then,
     * and re-locking here would suppress the incoming-call notification.
     */
    private static void applyStatusBarPolicy(DevicePolicyManager dpm,
                                             ComponentName adminComponent) {
        if (CallStatusBarLift.isLifted()) {
            Log.i(TAG, "Status bar lock deferred — a call is ringing (CallStatusBarLift owns it)");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.setStatusBarDisabled(adminComponent, true);
            Log.i(TAG, "Status bar disabled for kiosk operation");
        }
    }
}
