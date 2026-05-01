package com.brother.pharmach.mdm.launcher.service;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.util.WorkTimeManager;

/**
 * Suppresses notifications from apps that are restricted during the current WorkTime window.
 * This prevents users from tapping notifications to open a blocked app.
 *
 * Requires the user (or device owner policy) to grant Notification Access for this app.
 */
public class WorkTimeNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "WorkTimeNotifListener";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;

        if (!WorkTimeManager.getInstance().isAppAllowed(pkg)) {
            Log.d(TAG, "Cancelling notification from restricted app: " + pkg);
            try {
                cancelNotification(sbn.getKey());
            } catch (Exception e) {
                Log.w(TAG, "Failed to cancel notification for " + pkg, e);
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // No-op
    }
}
