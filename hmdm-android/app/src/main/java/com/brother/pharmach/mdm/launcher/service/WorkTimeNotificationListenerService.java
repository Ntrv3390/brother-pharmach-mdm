package com.brother.pharmach.mdm.launcher.service;

import android.content.Context;
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
                if (isCallNotification(svc, sbn)) continue;   // never cancel a call notification
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

        // A call notification is NEVER suppressed — this listener otherwise cancels every
        // notification from every other package (see NotificationSuppressionPolicy, which returns
        // true for all of them on a device-owner build), which would silently swallow the dialer's
        // incoming-call accept/decline notification and defeat the whole point of lifting the
        // status-bar lock while the phone rings. A call must never be blocked by kiosk or
        // work-hours policy.
        if (isCallNotification(this, sbn)) {
            // Also the most reactive ring signal we have on devices where telephony callbacks are
            // throttled: the notification is posted at the same moment the phone starts ringing.
            try {
                com.brother.pharmach.mdm.launcher.helper.CallStatusBarLift
                        .liftForRingingCall(getApplicationContext(), "notification-listener");
            } catch (Exception e) {
                Log.w(TAG, "CallStatusBarLift.liftForRingingCall failed", e);
            }
            Log.d(TAG, "Call notification from " + pkg + " — exempt from suppression");
            return;
        }

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

    /**
     * True when this notification belongs to a phone call, by any of three independent signals — an
     * OEM dialer may set only one of them:
     * <ul>
     *   <li>{@code CATEGORY_CALL} — the standard marker for incoming/ongoing calls;</li>
     *   <li>a full-screen intent — how a ringing call asks to take over the screen;</li>
     *   <li>the posting package is a resolved telephony/dialer/InCallService package.</li>
     * </ul>
     */
    private static boolean isCallNotification(Context context, StatusBarNotification sbn) {
        try {
            android.app.Notification n = sbn.getNotification();
            if (n != null) {
                if (android.app.Notification.CATEGORY_CALL.equals(n.category)) {
                    return true;
                }
                if (n.fullScreenIntent != null) {
                    return true;
                }
            }
            String pkg = sbn.getPackageName();
            if (pkg != null && com.brother.pharmach.mdm.launcher.util.Utils
                    .getPhoneCallPackages(context).contains(pkg)) {
                return true;
            }
        } catch (Exception e) {
            // Fail SAFE for calls: if we cannot classify it, do not cancel it.
            return true;
        }
        return false;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // No-op
    }
}
