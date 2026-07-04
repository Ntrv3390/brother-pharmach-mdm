package com.brother.pharmach.mdm.launcher.service;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.util.NotificationSuppressionPolicy;
import com.brother.pharmach.mdm.launcher.util.WorkTimeManager;

import java.lang.ref.WeakReference;

/**
 * Suppresses notifications from apps that are restricted during the current WorkTime window.
 * This prevents users from tapping notifications to open a blocked app.
 *
 * Requires the user (or device owner policy) to grant Notification Access for this app.
 */
public class WorkTimeNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "WorkTimeNotifListener";

    // Weak reference to the active service instance so WorkTimeManager can sweep
    // pre-existing notifications when a worktime window transition occurs.
    private static volatile WeakReference<WorkTimeNotificationListenerService> sInstance;

    /** Call this when worktime enforcement starts to cancel pre-existing restricted notifications. */
    public static void cancelAllRestricted() {
        WeakReference<WorkTimeNotificationListenerService> ref = sInstance;
        if (ref == null) return;
        WorkTimeNotificationListenerService svc = ref.get();
        if (svc == null) return;
        try {
            StatusBarNotification[] active = svc.getActiveNotifications();
            if (active == null) return;
            WorkTimeManager wm = WorkTimeManager.getInstance();
            for (StatusBarNotification sbn : active) {
                if (sbn == null) continue;
                String pkg = sbn.getPackageName();
                if (pkg == null || pkg.equals(svc.getPackageName())) continue;
                if (!wm.isAppAllowed(pkg)) {
                    Log.d(TAG, "Cancelling pre-existing notification from restricted app: " + pkg);
                    try {
                        svc.cancelNotification(sbn.getKey());
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to cancel existing notification for " + pkg, e);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cancelAllRestricted failed", e);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = new WeakReference<>(this);
        // Sweep any existing restricted notifications now that the listener is live.
        cancelAllRestricted();
    }

    @Override
    public void onListenerDisconnected() {
        sInstance = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;

        boolean suppress = NotificationSuppressionPolicy.isEnabled(getApplicationContext())
                && NotificationSuppressionPolicy.shouldSuppressNotification(pkg, getPackageName());
        if (suppress || !WorkTimeManager.getInstance().isAppAllowed(pkg)) {
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
