package com.brother.pharmach.mdm.launcher.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.Const;

/**
 * Fires OEM-specific intents to whitelist the app from battery kill lists on
 * Xiaomi (MIUI autostart), Realme/ColorOS, Oppo, Vivo, and Huawei EMUI.
 *
 * Each OEM ships their own hidden settings component; none of these are in AOSP.
 * All attempts are wrapped in try-catch — missing components are silently skipped.
 *
 * Call once from InitialSetupActivity after device-owner enrollment completes.
 * Note: some OEMs (Realme) suppress this intent from background callers — firing
 * it from an Activity context (foreground) is required, and that is exactly where
 * we call it from.
 */
public final class OemCompatHelper {

    private OemCompatHelper() {}

    /**
     * Attempts to open the OEM autostart/power-save whitelist screen for this package.
     * Must be called from a foreground Activity context.
     */
    public static void tryEnableAutostart(Context context) {
        String pkg = context.getPackageName();

        // Xiaomi MIUI — "Autostart" toggle in Security app
        tryStart(context, "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity", pkg);

        // Realme / ColorOS (OPPO) — "Auto-launch" in Phone Manager
        tryStart(context, "com.coloros.safecenter",
                "com.coloros.privacypermissionsentry.PermissionTopActivity", pkg);
        // Fallback for older ColorOS versions
        tryStart(context, "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity", pkg);

        // Vivo — iManager auto-start
        tryStart(context, "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity", pkg);

        // Huawei EMUI — Protected apps
        tryStart(context, "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity", pkg);

        // Samsung OneUI (API 28+) — Device care Sleeping apps
        tryStart(context, "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity", pkg);
    }

    private static void tryStart(Context context, String targetPkg, String className, String appPkg) {
        try {
            context.getPackageManager().getPackageInfo(targetPkg, 0);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(targetPkg, className));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            RemoteLogger.log(context, Const.LOG_INFO,
                    "OemCompatHelper: opened autostart screen on " + targetPkg);
        } catch (PackageManager.NameNotFoundException ignored) {
            // OEM package not present on this device — skip silently.
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "OemCompatHelper: failed to open " + targetPkg + ": " + e.getMessage());
        }
    }
}
