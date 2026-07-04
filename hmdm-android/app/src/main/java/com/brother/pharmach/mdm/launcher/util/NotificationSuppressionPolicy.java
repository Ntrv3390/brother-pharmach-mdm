package com.brother.pharmach.mdm.launcher.util;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.Build;

public final class NotificationSuppressionPolicy {

    private NotificationSuppressionPolicy() {
    }

    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    public static boolean shouldSuppressNotification(String packageName, String selfPackageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        if (selfPackageName != null && selfPackageName.equals(packageName)) {
            return false;
        }
        return true;
    }
}
