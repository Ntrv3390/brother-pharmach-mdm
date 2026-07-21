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

package com.brother.pharmach.mdm.launcher.ui;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.phone.CallManager;

/**
 * Full-screen incoming / in-call UI. Launched from {@code CustomInCallService} both directly and
 * via a full-screen-intent notification, satisfying all three required scenarios:
 * screen off + Doze, our app foreground, another app foreground.
 *
 * <p>Wake / keyguard handling is version-split (§4): declarative attrs + {@code setShowWhenLocked}/
 * {@code setTurnScreenOn} on API 27+, window flags on API 23-26.
 *
 * <p>API-DIFF: {@code android.telecom.Call} is API 23+. This activity is only ever started by the
 * InCallService, which never binds below API 23, so it is never shown on 21/22.
 */
@RequiresApi(api = Build.VERSION_CODES.M)
public class IncomingCallActivity extends Activity implements CallManager.Listener {

    private static final String TAG = "IncomingCallActivity";
    private static final String EXTRA_RINGING = "ringing";

    private TextView statusView;
    private TextView nameView;
    private TextView numberView;
    private LinearLayout answerContainer;
    private LinearLayout declineContainer;
    private LinearLayout inCallControls;
    private TextView declineLabel;
    private ImageButton muteButton;
    private ImageButton speakerButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean finishing;

    public static Intent newIntent(Context context, boolean ringing) {
        Intent i = new Intent(context, IncomingCallActivity.class);
        i.putExtra(EXTRA_RINGING, ringing);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyWakeAndKeyguardFlags();
        setContentView(R.layout.activity_incoming_call);

        statusView = findViewById(R.id.call_status);
        nameView = findViewById(R.id.caller_name);
        numberView = findViewById(R.id.caller_number);
        answerContainer = findViewById(R.id.answer_container);
        declineContainer = findViewById(R.id.decline_container);
        inCallControls = findViewById(R.id.in_call_controls);
        declineLabel = findViewById(R.id.decline_label);
        muteButton = findViewById(R.id.btn_mute);
        speakerButton = findViewById(R.id.btn_speaker);

        findViewById(R.id.btn_answer).setOnClickListener(v -> {
            CallManager.getInstance().answer();
            refresh();
        });
        findViewById(R.id.btn_decline).setOnClickListener(v -> {
            CallManager.getInstance().endOrReject();
        });
        muteButton.setOnClickListener(v -> {
            CallManager.getInstance().toggleMute();
            updateToggleStates();
        });
        speakerButton.setOnClickListener(v -> {
            CallManager.getInstance().toggleSpeaker();
            updateToggleStates();
        });

        CallManager.getInstance().register(this);
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        CallManager.getInstance().unregister(this);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // Block accidental dismissal with Back while a call is live.
    @Override
    public void onBackPressed() {
        // no-op
    }

    // ---------------------------------------------------------------------------------------------
    // Version-split screen-on / keyguard bypass (§4)
    // ---------------------------------------------------------------------------------------------

    private void applyWakeAndKeyguardFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            enableShowWhenLockedApi27();
        } else {
            // API 23-26: the newer attrs are ignored; use window flags.
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O_MR1)
    private void enableShowWhenLockedApi27() {
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null) {
            try {
                km.requestDismissKeyguard(this, null);
            } catch (Exception e) {
                Log.w(TAG, "requestDismissKeyguard failed: " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // UI state
    // ---------------------------------------------------------------------------------------------

    private void refresh() {
        CallManager cm = CallManager.getInstance();
        if (!cm.hasCall()) {
            finishSafely();
            return;
        }

        String name = cm.getCallerName();
        String number = cm.getCallerNumber();
        if (name != null && !name.isEmpty()) {
            nameView.setText(name);
            numberView.setVisibility(number != null && !number.isEmpty() ? View.VISIBLE : View.GONE);
            numberView.setText(number);
        } else if (number != null && !number.isEmpty()) {
            nameView.setText(number);
            numberView.setVisibility(View.GONE);
        } else {
            nameView.setText(R.string.unknown_caller);
            numberView.setVisibility(View.GONE);
        }

        String sim = cm.getSimLabel();
        int state = cm.getState();
        switch (state) {
            case Call.STATE_RINGING:
                setStatus(R.string.incoming_call_title, sim);
                answerContainer.setVisibility(View.VISIBLE);
                declineContainer.setVisibility(View.VISIBLE);
                declineLabel.setText(R.string.decline);
                inCallControls.setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                break;

            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
                setStatus(R.string.calling, sim);
                answerContainer.setVisibility(View.GONE);
                declineContainer.setVisibility(View.VISIBLE);
                declineLabel.setText(R.string.hang_up);
                inCallControls.setVisibility(View.VISIBLE);
                updateToggleStates();
                break;

            case Call.STATE_ACTIVE:
            case Call.STATE_HOLDING:
                setStatus(R.string.ongoing_call_title, sim);
                answerContainer.setVisibility(View.GONE);
                declineContainer.setVisibility(View.VISIBLE);
                declineLabel.setText(R.string.hang_up);
                inCallControls.setVisibility(View.VISIBLE);
                updateToggleStates();
                break;

            case Call.STATE_DISCONNECTING:
            case Call.STATE_DISCONNECTED:
                statusView.setText(R.string.call_ended);
                finishSafely();
                break;

            default:
                break;
        }
    }

    /** Status line, suffixed with the SIM/carrier label on dual-SIM devices when available. */
    private void setStatus(int baseRes, String sim) {
        String base = getString(baseRes);
        statusView.setText(sim != null && !sim.isEmpty() ? base + "  ·  " + sim : base);
    }

    private void updateToggleStates() {
        CallManager cm = CallManager.getInstance();
        muteButton.setSelected(cm.isMuted());
        speakerButton.setSelected(cm.isSpeakerOn());
        muteButton.setActivated(cm.isMuted());
        speakerButton.setActivated(cm.isSpeakerOn());
    }

    private void finishSafely() {
        if (finishing) {
            return;
        }
        finishing = true;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Brief delay so "Call ended" is perceivable, then dismiss.
        handler.postDelayed(() -> {
            if (!isFinishing()) {
                finish();
            }
        }, 600L);
    }

    // ---------------------------------------------------------------------------------------------
    // CallManager.Listener
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onCallStateChanged(int state) {
        runOnUiThread(this::refresh);
    }

    @Override
    public void onCallRemoved() {
        runOnUiThread(this::finishSafely);
    }
}
