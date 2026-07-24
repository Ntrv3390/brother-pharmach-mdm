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

package com.brother.pharmach.mdm.launcher.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.phone.IncomingCallOverlay;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.phone.CallManager;
import com.brother.pharmach.mdm.launcher.receiver.CallActionReceiver;
import com.brother.pharmach.mdm.launcher.ui.IncomingCallActivity;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

/**
 * System-managed in-call service. Bound by Telecom only when this app holds the default-dialer
 * role, at which point every cellular call — incoming and outgoing — is delivered here instead of
 * to the stock phone app. We then draw our own UI ({@code IncomingCallActivity}).
 *
 * <p>The engine that makes all three required scenarios work uniformly (screen off/Doze, our app
 * foreground, another app foreground) is a high-priority notification carrying a full-screen intent
 * to {@code IncomingCallActivity}. Because we hold the dialer role the full-screen intent is honored
 * without the API 34+ redirect, and the InCallService is exempt from background-activity-launch
 * limits, so we additionally start the activity directly as a fast path.
 *
 * <p>API-DIFF: {@code InCallService} is Android 6.0 (API 23). Below that the service is simply never
 * bound. The manifest declares it with {@code minSdk 21}; the framework ignores it on 21/22.
 */
@RequiresApi(api = Build.VERSION_CODES.M)
public class CustomInCallService extends InCallService {

    private static final String TAG = "CustomInCallService";

    public static final String CHANNEL_ID = "incoming_call_channel";
    private static final int NOTIFICATION_ID = 0xCA11; // 51729

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        // Confirms Telecom actually bound us — if this never appears in the server log, the app is
        // not being used as the default dialer (or the OEM stripped the InCallService).
        RemoteLogger.log(this, Const.LOG_INFO, "CustomInCallService bound (default dialer active)");
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Log.i(TAG, "onCallAdded: " + CallManager.stateName(stateOf(call)));
        RemoteLogger.log(this, Const.LOG_DEBUG, "Incoming/outgoing call received by InCallService");

        CallManager cm = CallManager.getInstance();
        cm.attachService(this);
        cm.setActiveCall(call);

        boolean ringing = stateOf(call) == Call.STATE_RINGING;

        // Doze safety net: a brief partial wake lock so the notification is posted and the activity
        // has time to take over the screen (the activity itself does turnScreenOn — §4).
        acquireBriefWakeLock();

        // Foreground with a full-screen-intent notification. This is the reliable path when the
        // device is LOCKED or the screen is OFF (the FSI launches the activity, keyguard bypassed).
        startCallForeground(call, ringing);

        presentCallUi(ringing);
        startWatchdog();
    }

    /**
     * Bring up the call UI, choosing the mechanism that actually works for the current screen state:
     *
     * <ul>
     *   <li><b>Screen ON + unlocked</b> (user is in another app): a full-screen-intent notification
     *       does NOT launch its activity on Android 14/15 — it only shows a heads-up — and a direct
     *       startActivity is BAL-blocked. So we draw a {@code SYSTEM_ALERT_WINDOW} overlay, which is
     *       exempt from all of that and paints on top of whatever app is showing.</li>
     *   <li><b>Screen OFF / locked</b>: overlays cannot cover a secure keyguard, so we rely on the
     *       FSI notification (already posted) plus the turn-screen-on / show-when-locked activity.</li>
     * </ul>
     */
    private void presentCallUi(boolean ringing) {
        boolean interactive;
        boolean locked;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            interactive = pm != null && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
                    ? pm.isInteractive() : pm.isScreenOn());
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            locked = km != null && km.isKeyguardLocked();
        } catch (Exception e) {
            interactive = true;
            locked = false;
        }

        boolean canOverlay = IncomingCallOverlay.canShow(this);
        boolean secureLock = isKeyguardSecure();

        // Remote diagnostic: this single line (visible on the MDM server) tells us exactly which
        // path ran and why, so a field failure can be diagnosed without physical access.
        RemoteLogger.log(this, Const.LOG_INFO,
                "Call UI present: ringing=" + ringing
                        + " interactive=" + interactive
                        + " keyguardLocked=" + locked
                        + " keyguardSecure=" + secureLock
                        + " canDrawOverlays=" + canOverlay
                        + " defaultDialer=" + isDefaultDialerSelf()
                        + " notifEnabled=" + areNotificationsEnabled()
                        + " canUseFSI=" + canUseFullScreenIntent());

        // Always ATTEMPT the overlay when we can draw one. It is the only reliable presenter when
        // the screen is on (in another app / on a swipe lock), and we cannot trust isInteractive()
        // here — onCallAdded fires a moment before the ring lights the screen, so it often reads
        // false even when the screen is (about to be) on. The overlay is idempotent.
        boolean overlayShown = false;
        if (canOverlay) {
            try {
                IncomingCallOverlay.getInstance(getApplicationContext()).show();
                overlayShown = true;
            } catch (Exception e) {
                Log.w(TAG, "Overlay show threw: " + e.getMessage());
                RemoteLogger.log(this, Const.LOG_ERROR, "Overlay show threw: " + e.getMessage());
            }
        }

        // Also run the activity + full-screen-intent path when it is needed:
        //  - no overlay available, OR
        //  - the device is locked (screen-off wake needs the activity's turnScreenOn / the FSI, and
        //    a SECURE keyguard cannot be covered by an overlay — only the show-when-locked activity
        //    and the FSI notification can appear over it).
        // ALWAYS launch the show-when-locked activity too — do NOT skip it when the overlay was
        // "shown". On ColorOS/Realme (and MIUI/Vivo) addView() succeeds but the OEM silently
        // suppresses background overlay windows unless the separate "Display pop-up windows while
        // running in background" permission is on — which Settings.canDrawOverlays() does not
        // reflect. So the overlay alone can be invisible; the activity (via the SAW background-
        // activity-launch exemption + FSI) is the more reliable on-screen presenter there.
        // Locked / screen-off: force the display on so it can actually be seen; otherwise a brief
        // CPU wake lock is enough.
        if (locked || !interactive) {
            acquireScreenWakeLock();
        } else {
            acquireBriefWakeLock();
        }
        launchIncomingCallUi(ringing);
    }

    /** True if the keyguard requires a PIN/pattern/password (an overlay cannot cover it). */
    private boolean isKeyguardSecure() {
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardSecure();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        CallManager cm = CallManager.getInstance();

        java.util.List<Call> remaining = null;
        try {
            remaining = getCalls();
        } catch (Exception ignored) {
        }

        if (remaining == null || remaining.isEmpty()) {
            // Last call gone — tear everything down and dismiss the UI.
            Log.i(TAG, "onCallRemoved: no calls remain");
            stopWatchdog();
            cm.clearCall(call);
            stopCallForeground();
            releaseWakeLock();
            try {
                IncomingCallOverlay.getInstance(getApplicationContext()).dismiss();
            } catch (Exception ignored) {
            }
        } else {
            // Call waiting / conference leg ended: promote the next call so the foreground service
            // and UI keep tracking a live call instead of being torn down prematurely.
            Call next = remaining.get(0);
            Log.i(TAG, "onCallRemoved: promoting remaining call " + CallManager.stateName(stateOf(next)));
            cm.setActiveCall(next);
            startCallForeground(next, stateOf(next) == Call.STATE_RINGING);
            cm.notifyStateChanged();
        }
    }

    @Override
    public void onDestroy() {
        stopWatchdog();
        RemoteLogger.log(this, Const.LOG_INFO, "CustomInCallService unbound");
        CallManager.getInstance().detachService(this);
        releaseWakeLock();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------------
    // Keep-on-top watchdog: while a call is live, ensure our call UI stays visible. If it is ever
    // not showing (activity got covered, overlay failed/was dismissed), re-assert it via
    // presentCallUi(). The overlay floats above all apps and cannot be covered, so in practice this
    // only fires when the overlay was never added or the activity path got buried — exactly the
    // "ringtone but no accept screen" case. Debounced (2 consecutive misses ≈ 2s) to avoid flicker.
    // ---------------------------------------------------------------------------------------------

    private final android.os.Handler watchdogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable watchdogRunnable;
    private int uiMissCount;

    private void startWatchdog() {
        stopWatchdog();
        uiMissCount = 0;
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    checkCallUiVisible();
                } catch (Throwable ignored) {
                }
                watchdogHandler.postDelayed(this, 1000L);
            }
        };
        // First check ~1.5s after present so the initial UI has time to attach.
        watchdogHandler.postDelayed(watchdogRunnable, 1500L);
    }

    private void stopWatchdog() {
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
        uiMissCount = 0;
    }

    private void checkCallUiVisible() {
        CallManager cm = CallManager.getInstance();
        if (!cm.hasCall()) {
            stopWatchdog();
            return;
        }
        boolean visible;
        try {
            visible = com.brother.pharmach.mdm.launcher.ui.IncomingCallActivity.isForeground()
                    || IncomingCallOverlay.getInstance(getApplicationContext()).isShown();
        } catch (Exception e) {
            visible = false;
        }
        if (visible) {
            uiMissCount = 0;
            return;
        }
        uiMissCount++;
        if (uiMissCount >= 2) {
            uiMissCount = 0;
            RemoteLogger.log(this, Const.LOG_WARN,
                    "Call UI not visible during live call — re-asserting (state="
                            + CallManager.stateName(cm.getState()) + ")");
            presentCallUi(cm.isIncomingRinging());
        }
    }

    // ---------------------------------------------------------------------------------------------

    private int stateOf(Call call) {
        if (call == null) {
            return Call.STATE_DISCONNECTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return call.getDetails().getState();
        }
        return call.getState();
    }

    /** Whether this app is the current default dialer (should be true here — confirms it). */
    private boolean isDefaultDialerSelf() {
        try {
            android.telecom.TelecomManager tm =
                    (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            return tm != null && getPackageName().equals(tm.getDefaultDialerPackage());
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether notifications are enabled for us — if false, the FSI notification is suppressed. */
    private boolean areNotificationsEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.areNotificationsEnabled();
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    /** API 34+: whether the OS will honor our full-screen intent (vs heads-up only). */
    private boolean canUseFullScreenIntent() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.canUseFullScreenIntent();
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private void launchIncomingCallUi(boolean ringing) {
        try {
            Intent i = IncomingCallActivity.newIntent(this, ringing);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            RemoteLogger.log(this, Const.LOG_INFO, "Call activity startActivity issued");
        } catch (Exception e) {
            // On some OEMs a background start can still be refused (BAL); the FSI notification is the
            // fallback and will bring the activity up when the screen is locked/off.
            Log.w(TAG, "Direct activity start refused, relying on full-screen intent: " + e.getMessage());
            RemoteLogger.log(this, Const.LOG_ERROR,
                    "Call activity start REFUSED (BAL) — relying on FSI notification: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void startCallForeground(Call call, boolean ringing) {
        createChannel();
        Notification notification = buildNotification(ringing);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // If FGS start is refused (e.g. missing type permission on a locked-down OEM), fall back
            // to a plain notification so the FSI still fires.
            Log.w(TAG, "startForeground failed, posting plain notification: " + e.getMessage());
            RemoteLogger.log(this, Const.LOG_ERROR,
                    "startForeground(phoneCall) failed, posting plain notification: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try {
                    nm.notify(NOTIFICATION_ID, notification);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void stopCallForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.incoming_call_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(getString(R.string.incoming_call_channel_desc));
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build());
        ch.enableVibration(true);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(boolean ringing) {
        Intent fullScreen = IncomingCallActivity.newIntent(this, ringing);
        fullScreen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent fullScreenPi = PendingIntent.getActivity(this, 1, fullScreen, piFlags);

        String number = CallManager.getInstance().getCallerNumber();
        String name = CallManager.getInstance().getCallerName();
        String title = ringing ? getString(R.string.incoming_call_title)
                : getString(R.string.ongoing_call_title);
        String text = name != null && !name.isEmpty() ? name
                : (number != null && !number.isEmpty() ? number
                : getString(R.string.unknown_caller));
        String sim = CallManager.getInstance().getSimLabel();
        if (sim != null && !sim.isEmpty()) {
            text = text + "  ·  " + sim;
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_call_answer)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(fullScreenPi)
                .setFullScreenIntent(fullScreenPi, true);

        if (ringing) {
            b.addAction(R.drawable.ic_call_answer, getString(R.string.answer),
                    CallActionReceiver.actionPendingIntent(this, CallActionReceiver.ACTION_ANSWER));
            b.addAction(R.drawable.ic_call_end, getString(R.string.decline),
                    CallActionReceiver.actionPendingIntent(this, CallActionReceiver.ACTION_REJECT));
        } else {
            b.addAction(R.drawable.ic_call_end, getString(R.string.hang_up),
                    CallActionReceiver.actionPendingIntent(this, CallActionReceiver.ACTION_END));
        }
        return b.build();
    }

    private void acquireBriefWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            releaseWakeLock();
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "hmdm:incoming-call");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10_000L); // auto-release after 10s; the Activity keeps the screen on
        } catch (Exception e) {
            Log.w(TAG, "wake lock acquire failed: " + e.getMessage());
        }
    }

    /**
     * Force the display ON for a locked / screen-off call. PARTIAL_WAKE_LOCK only keeps the CPU
     * awake — the screen stays black, so an overlay is invisible and the activity has no lit screen.
     * These SCREEN_BRIGHT / ACQUIRE_CAUSES_WAKEUP flags are deprecated but still the only way to
     * actively turn the display on from a service; the Activity's turnScreenOn is the modern path
     * but only fires if the activity actually launches. Belt-and-suspenders for the locked case.
     */
    @SuppressWarnings("deprecation")
    private void acquireScreenWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            releaseWakeLock();
            wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "hmdm:incoming-call-screen");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(15_000L);
            RemoteLogger.log(this, Const.LOG_INFO, "Screen wake lock acquired (locked/off call)");
        } catch (Exception e) {
            Log.w(TAG, "screen wake lock acquire failed: " + e.getMessage());
            acquireBriefWakeLock();
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }
}
