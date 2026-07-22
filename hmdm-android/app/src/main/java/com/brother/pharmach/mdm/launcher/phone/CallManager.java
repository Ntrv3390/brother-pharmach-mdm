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

package com.brother.pharmach.mdm.launcher.phone;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.brother.pharmach.mdm.launcher.Const;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for the call currently owned by {@code CustomInCallService}.
 *
 * <p>As the default dialer we host the system's in-call UI. The {@link InCallService} receives
 * the live {@link Call} objects; this singleton bridges them to the UI ({@code IncomingCallActivity})
 * and to the notification action receiver ({@code CallActionReceiver}) so that answer/reject/end
 * work from any surface (full-screen activity, heads-up notification actions, lock screen).
 *
 * <p>API-DIFF: Everything here is Android 6.0 (API 23, {@code android.telecom.Call}) and up. The
 * class is never touched below API 23 because {@code InCallService} is not bound and
 * {@code IncomingCallActivity} is only ever launched from the service.
 */
@RequiresApi(api = Build.VERSION_CODES.M)
public final class CallManager {

    private static final String TAG = "CallManager";

    /** Observes the primary call so any attached surface repaints on state changes. */
    public interface Listener {
        void onCallStateChanged(int state);
        void onCallRemoved();
    }

    private static final CallManager INSTANCE = new CallManager();

    public static CallManager getInstance() {
        return INSTANCE;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private InCallService inCallService;
    private Context appContext;
    private Call call;
    private boolean speakerOn;
    private boolean muted;

    private final Call.Callback callCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call c, int state) {
            Log.i(TAG, "Call state changed -> " + stateName(state));
            for (Listener l : listeners) {
                try {
                    l.onCallStateChanged(state);
                } catch (Exception e) {
                    Log.w(TAG, "listener onCallStateChanged failed: " + e.getMessage());
                }
            }
        }
    };

    private CallManager() {
    }

    // ---------------------------------------------------------------------------------------------
    // Wiring from CustomInCallService
    // ---------------------------------------------------------------------------------------------

    public void attachService(InCallService service) {
        this.inCallService = service;
        if (service != null) {
            this.appContext = service.getApplicationContext();
        }
    }

    public void detachService(InCallService service) {
        if (this.inCallService == service) {
            this.inCallService = null;
        }
    }

    public void setActiveCall(Call newCall) {
        if (this.call == newCall) {
            return;
        }
        if (this.call != null) {
            try {
                this.call.unregisterCallback(callCallback);
            } catch (Exception ignored) {
            }
        }
        this.call = newCall;
        this.muted = false;
        this.speakerOn = false;
        if (newCall != null) {
            try {
                newCall.registerCallback(callCallback);
            } catch (Exception e) {
                Log.w(TAG, "registerCallback failed: " + e.getMessage());
            }
        }
    }

    public void clearCall(Call removed) {
        if (this.call == removed) {
            try {
                if (this.call != null) {
                    this.call.unregisterCallback(callCallback);
                }
            } catch (Exception ignored) {
            }
            this.call = null;
        }
        for (Listener l : listeners) {
            try {
                l.onCallRemoved();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // UI / receiver observers
    // ---------------------------------------------------------------------------------------------

    public void register(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    /**
     * Force a state re-broadcast to attached surfaces. Used by the service when it promotes a
     * remaining call (e.g. a call-waiting leg) into the primary slot so the UI repaints without
     * waiting for the next framework state change.
     */
    public void notifyStateChanged() {
        int state = getState();
        for (Listener l : listeners) {
            try {
                l.onCallStateChanged(state);
            } catch (Exception ignored) {
            }
        }
    }

    public void unregister(Listener l) {
        listeners.remove(l);
    }

    // ---------------------------------------------------------------------------------------------
    // Call control
    // ---------------------------------------------------------------------------------------------

    public boolean hasCall() {
        return call != null;
    }

    public int getState() {
        if (call == null) {
            return Call.STATE_DISCONNECTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return call.getDetails().getState();
        }
        return call.getState();
    }

    /** True while the call is ringing and the user has not answered yet. */
    public boolean isIncomingRinging() {
        return getState() == Call.STATE_RINGING;
    }

    /** True for an outgoing call that has not yet connected. */
    public boolean isOutgoing() {
        int s = getState();
        return s == Call.STATE_DIALING || s == Call.STATE_CONNECTING
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && s == Call.STATE_SELECT_PHONE_ACCOUNT);
    }

    public String getCallerNumber() {
        if (call == null) {
            return null;
        }
        try {
            Uri handle = call.getDetails().getHandle();
            if (handle != null) {
                return Uri.decode(handle.getSchemeSpecificPart());
            }
        } catch (Exception e) {
            Log.w(TAG, "getCallerNumber failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Human-readable label of the SIM / phone account carrying this call, e.g. the carrier or
     * "SIM 1" / "SIM 2" on a dual-SIM device. Works for calls arriving on either SIM because, as
     * the default dialer, Telecom hands us every call regardless of slot. Returns null when the
     * account cannot be resolved (single-SIM, missing permission, or OEM quirk).
     */
    public String getSimLabel() {
        if (call == null || appContext == null) {
            return null;
        }
        try {
            PhoneAccountHandle handle = call.getDetails().getAccountHandle();
            if (handle == null) {
                return null;
            }
            TelecomManager tm =
                    (TelecomManager) appContext.getSystemService(Context.TELECOM_SERVICE);
            if (tm == null) {
                return null;
            }
            PhoneAccount account = tm.getPhoneAccount(handle);
            if (account != null && account.getLabel() != null) {
                String label = account.getLabel().toString().trim();
                return label.isEmpty() ? null : label;
            }
        } catch (SecurityException se) {
            // READ_PHONE_STATE not yet granted — resolve silently.
        } catch (Exception e) {
            Log.w(TAG, "getSimLabel failed: " + e.getMessage());
        }
        return null;
    }

    /** Contact display name if Telecom resolved one (requires READ_CONTACTS, granted to dialer). */
    public String getCallerName() {
        if (call == null) {
            return null;
        }
        try {
            return call.getDetails().getCallerDisplayName();
        } catch (Exception ignored) {
            return null;
        }
    }

    public void answer() {
        if (call == null) {
            return;
        }
        try {
            call.answer(VideoProfile.STATE_AUDIO_ONLY);
            Log.i(TAG, "Call answered");
        } catch (Exception e) {
            Log.w(TAG, "answer() failed: " + e.getMessage());
        }
    }

    /** Reject a ringing call. For a connected/dialing call use {@link #hangup()}. */
    public void reject() {
        if (call == null) {
            return;
        }
        try {
            call.reject(false, null);
            Log.i(TAG, "Call rejected");
        } catch (Exception e) {
            Log.w(TAG, "reject() failed: " + e.getMessage());
        }
    }

    public void hangup() {
        if (call == null) {
            return;
        }
        try {
            call.disconnect();
            Log.i(TAG, "Call disconnected");
        } catch (Exception e) {
            Log.w(TAG, "hangup() failed: " + e.getMessage());
        }
    }

    /**
     * Reject if still ringing, otherwise disconnect. Used by the single "end/decline" notification
     * action which must do the right thing regardless of the current state.
     */
    public void endOrReject() {
        if (isIncomingRinging()) {
            reject();
        } else {
            hangup();
        }
    }

    public boolean isMuted() {
        return muted;
    }

    public void toggleMute() {
        setMuted(!muted);
    }

    public void setMuted(boolean value) {
        if (inCallService == null) {
            return;
        }
        try {
            inCallService.setMuted(value);
            muted = value;
        } catch (Exception e) {
            Log.w(TAG, "setMuted failed: " + e.getMessage());
        }
    }

    public boolean isSpeakerOn() {
        return speakerOn;
    }

    public void toggleSpeaker() {
        setSpeaker(!speakerOn);
    }

    public void setSpeaker(boolean value) {
        if (inCallService == null) {
            return;
        }
        try {
            inCallService.setAudioRoute(value
                    ? CallAudioState.ROUTE_SPEAKER
                    : CallAudioState.ROUTE_EARPIECE);
            speakerOn = value;
        } catch (Exception e) {
            Log.w(TAG, "setAudioRoute failed: " + e.getMessage());
        }
    }

    public static String stateName(int state) {
        switch (state) {
            case Call.STATE_NEW: return "NEW";
            case Call.STATE_RINGING: return "RINGING";
            case Call.STATE_DIALING: return "DIALING";
            case Call.STATE_ACTIVE: return "ACTIVE";
            case Call.STATE_HOLDING: return "HOLDING";
            case Call.STATE_DISCONNECTED: return "DISCONNECTED";
            case Call.STATE_CONNECTING: return "CONNECTING";
            case Call.STATE_DISCONNECTING: return "DISCONNECTING";
            default: return "STATE_" + state;
        }
    }

    static {
        // Touch Const so the log tag prefix stays consistent with the rest of the app when this
        // class is class-loaded during the first incoming call.
        Log.d(Const.LOG_TAG, TAG + " loaded");
    }
}
