package com.brother.pharmach.mdm.launcher.util;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

/**
 * OEM-specific compatibility flags for background location delivery.
 *
 * Root cause: On Realme/ColorOS (AutoDroid), Xiaomi/MIUI, and Vivo/OriginOS,
 * HandlerThread loopers are frozen by the OEM background process manager even
 * when PARTIAL_WAKE_LOCK is held and DPM protection is active. This causes
 * LocationListener callbacks to never fire and CountDownLatch to time out.
 *
 * Solution: PendingIntent-based requestLocationUpdates() routes through
 * ActivityManagerService → BroadcastQueue in the system server process,
 * which AutoDroid/MIUI/OriginOS cannot freeze.
 *
 * IMPORTANT: Do NOT add Build.VERSION.SDK_INT >= 35 to requiresPendingIntentLocationUpdates().
 * Android 15 itself is not the problem — OEM power management is.
 * Pixel/stock Android 15 devices work correctly with HandlerThread.
 */
public final class OemCompat {

    private OemCompat() {}

    private static Boolean cachedGmsResult = null;

    // ---------------------------------------------------------------------------
    // OEM Detection
    // ---------------------------------------------------------------------------

    public static boolean isRealmeColorOs() {
        String mfr = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        return mfr.contains("realme") || mfr.contains("oppo") || mfr.contains("oneplus")
                || brand.contains("realme") || brand.contains("oppo") || brand.contains("oneplus");
    }

    public static boolean isXiaomiMiui() {
        String mfr = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        return mfr.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco");
    }

    public static boolean isSamsung() {
        return Build.MANUFACTURER.equalsIgnoreCase("samsung");
    }

    public static boolean isMotorola() {
        return Build.MANUFACTURER.equalsIgnoreCase("motorola");
    }

    public static boolean isVivo() {
        return Build.MANUFACTURER.equalsIgnoreCase("vivo");
    }

    /**
     * Returns true if Google Mobile Services (GMS) are present.
     * Use this to gate NETWORK_PROVIDER registration — on non-GMS devices (Huawei, Honor)
     * NETWORK_PROVIDER appears enabled but returns no fixes because Google's NLP is absent.
     * Result is cached: PackageManager lookups are not free.
     */
    public static synchronized boolean isGmsAvailable(Context context) {
        if (cachedGmsResult != null) return cachedGmsResult;
        try {
            context.getApplicationContext().getPackageManager()
                    .getPackageInfo("com.google.android.gms", 0);
            cachedGmsResult = true;
        } catch (PackageManager.NameNotFoundException e) {
            cachedGmsResult = false;
        }
        return cachedGmsResult;
    }

    // ---------------------------------------------------------------------------
    // Behavior Flags
    // ---------------------------------------------------------------------------

    /**
     * Returns true if HandlerThread-bound location listeners are unreliable on this device.
     * Affected: Realme/ColorOS (AutoDroid), Xiaomi/MIUI/HyperOS, Vivo/OriginOS.
     * Fix: use PendingIntent-based requestLocationUpdates() in parallel with HandlerThread.
     */
    public static boolean requiresPendingIntentLocationUpdates() {
        return isRealmeColorOs() || isXiaomiMiui() || isVivo();
    }

    /**
     * Returns the minimum notification channel importance for the FGS to survive OEM kill.
     * IMPORTANCE_MIN is deprioritized on Xiaomi HyperOS and Vivo OriginOS.
     */
    public static int requiredFgsNotificationImportance() {
        if (isXiaomiMiui() || isVivo()) return NotificationManager.IMPORTANCE_LOW;
        return NotificationManager.IMPORTANCE_MIN;
    }

    /**
     * Returns the notification priority matching the channel importance.
     */
    public static int requiredNotificationPriority() {
        if (isXiaomiMiui() || isVivo()) return NotificationCompat.PRIORITY_LOW;
        return NotificationCompat.PRIORITY_MIN;
    }

    /**
     * Returns an OEM-specific deep-link Intent to battery unrestricted settings.
     * Always check resolveActivity() != null before launching.
     */
    /**
     * Packages that host battery/power-saving settings across OEMs. On many ROMs the
     * battery exemption UI lives outside com.android.settings (e.g. MIUI Security Center,
     * Samsung Device Care, Huawei System Manager), so all of them must stay reachable
     * while the user is granting the battery optimization exemption.
     */
    public static boolean isSettingsFamilyPackage(String pkg) {
        if (pkg == null) return false;
        switch (pkg) {
            case "com.android.settings":                 // AOSP / most OEMs
            case "com.android.settings.intelligence":    // Settings search
            case "com.samsung.android.lool":             // Samsung Device Care (battery)
            case "com.samsung.android.sm_cn":            // Samsung Device Care (CN)
            case "com.miui.securitycenter":              // Xiaomi Security Center (autostart/battery)
            case "com.miui.powerkeeper":                 // Xiaomi battery manager
            case "com.huawei.systemmanager":             // Huawei/Honor Phone Manager
            case "com.coloros.phonemanager":             // Oppo/Realme Phone Manager
            case "com.coloros.oppoguardelf":             // Oppo/Realme power monitor
            case "com.oplus.battery":                    // Newer ColorOS/OxygenOS battery
            case "com.oneplus.security":                 // OnePlus security/battery
            case "com.iqoo.secure":                      // Vivo iManager (battery)
            case "com.vivo.abe":                         // Vivo background power manager
            case "com.evenwell.powersaving.g3":          // Nokia/HMD power saver
            case "com.transsion.phonemanager":           // Tecno/Infinix/Itel Phone Master
                return true;
            default:
                return false;
        }
    }

    public static Intent getBatterySettingsIntent(Context context) {
        if (isXiaomiMiui()) {
            Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
            i.setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity");
            i.putExtra("extra_pkgname", context.getPackageName());
            if (context.getPackageManager().resolveActivity(i, 0) != null) return i;
            // Fallback for newer MIUI/HyperOS
            Intent i2 = new Intent("miui.intent.action.APP_PERM_EDITOR");
            i2.putExtra("extra_pkgname", context.getPackageName());
            if (context.getPackageManager().resolveActivity(i2, 0) != null) return i2;
        }
        if (isSamsung()) {
            return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
        }
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }
}
