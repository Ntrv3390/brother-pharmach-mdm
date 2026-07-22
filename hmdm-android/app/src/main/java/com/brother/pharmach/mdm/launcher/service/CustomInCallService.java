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

        if (interactive && IncomingCallOverlay.canShow(this)) {
            // Screen is ON (locked or in another app) → System Window Overlay presents caller UI reliably
            // bypassing Android 14/15 background activity launch restrictions.
            try {
                IncomingCallOverlay.getInstance(getApplicationContext()).show();
                return;
            } catch (Exception e) {
                Log.w(TAG, "Overlay path failed, falling back to activity: " + e.getMessage());
            }
        }

        // Screen off or overlays unavailable: wake screen and launch activity; FSI notification acts as fallback.
        acquireBriefWakeLock();
        launchIncomingCallUi(ringing);
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
        CallManager.getInstance().detachService(this);
        releaseWakeLock();
        super.onDestroy();
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

    private void launchIncomingCallUi(boolean ringing) {
        try {
            Intent i = IncomingCallActivity.newIntent(this, ringing);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            // On some OEMs a background start can still be refused; the FSI notification is the
            // guaranteed fallback and will bring the activity up.
            Log.w(TAG, "Direct activity start refused, relying on full-screen intent: " + e.getMessage());
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
