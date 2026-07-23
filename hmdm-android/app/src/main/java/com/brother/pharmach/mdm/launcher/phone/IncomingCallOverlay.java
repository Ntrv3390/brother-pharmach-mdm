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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.Call;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.util.InsetsUtils;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

/**
 * Full-screen incoming / in-call UI drawn as a {@code SYSTEM_ALERT_WINDOW} overlay.
 *
 * <p>Why this exists: on Android 14/15 a full-screen-intent notification only launches its activity
 * when the device is LOCKED or the screen is OFF. When the screen is ON and unlocked (the user is
 * in another app), the system shows the FSI as a heads-up banner only and does not bring the
 * activity forward; a direct {@code startActivity} from the InCallService is also BAL-blocked. An
 * overlay window bypasses all of that and reliably paints on top — the same technique caller-ID
 * apps use on aggressive OEMs (ColorOS/Realme, MIUI, One UI …).
 *
 * <p>Used only for the screen-on + unlocked case; the {@code IncomingCallActivity} + FSI still
 * handles screen-off / locked (an overlay cannot cover a secure keyguard). Requires
 * {@code Settings.canDrawOverlays()} (granted for this MDM).
 */
@RequiresApi(api = Build.VERSION_CODES.M)
public final class IncomingCallOverlay implements CallManager.Listener {

    private static final String TAG = "IncomingCallOverlay";

    private static IncomingCallOverlay instance;

    public static synchronized IncomingCallOverlay getInstance(Context appContext) {
        if (instance == null) {
            instance = new IncomingCallOverlay(appContext.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private View root;
    private boolean shown;
    private boolean finishing;

    private TextView statusView, nameView, numberView, declineLabel, dtmfDisplay;
    private LinearLayout answerContainer, declineContainer, inCallControls;
    private ImageButton muteButton, speakerButton;
    private View dtmfDialpad;
    private final StringBuilder dtmfDigits = new StringBuilder();

    private IncomingCallOverlay(Context appContext) {
        this.context = appContext;
    }

    public static boolean canShow(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context);
    }

    /** True while the overlay window is currently attached (read by the watchdog). */
    public boolean isShown() {
        return shown;
    }

    @SuppressLint("InflateParams")
    public void show() {
        handler.post(() -> {
            if (shown) {
                refresh();
                return;
            }
            if (!canShow(context)) {
                Log.w(TAG, "Cannot draw overlays — overlay path unavailable");
                return;
            }
            try {
                windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                root = LayoutInflater.from(context).inflate(R.layout.activity_incoming_call, null);
                bindViews();
                InsetsUtils.applySystemBarPadding(root.findViewById(R.id.incoming_root));

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;

                finishing = false;
                CallManager.getInstance().register(this);
                windowManager.addView(root, lp);
                shown = true;
                refresh();
                Log.i(TAG, "Overlay shown");
                RemoteLogger.log(context, Const.LOG_INFO, "IncomingCallOverlay added OK");
            } catch (Exception e) {
                Log.w(TAG, "show() failed: " + e.getMessage());
                RemoteLogger.log(context, Const.LOG_ERROR,
                        "IncomingCallOverlay FAILED to add: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
                shown = false;
            }
        });
    }

    public void dismiss() {
        handler.post(() -> {
            CallManager.getInstance().unregister(this);
            handler.removeCallbacksAndMessages(null);
            if (shown && windowManager != null && root != null) {
                try {
                    windowManager.removeView(root);
                } catch (Exception ignored) {
                }
            }
            shown = false;
            root = null;
        });
    }

    // ---------------------------------------------------------------------------------------------

    private void bindViews() {
        statusView = root.findViewById(R.id.call_status);
        nameView = root.findViewById(R.id.caller_name);
        numberView = root.findViewById(R.id.caller_number);
        answerContainer = root.findViewById(R.id.answer_container);
        declineContainer = root.findViewById(R.id.decline_container);
        inCallControls = root.findViewById(R.id.in_call_controls);
        declineLabel = root.findViewById(R.id.decline_label);
        muteButton = root.findViewById(R.id.btn_mute);
        speakerButton = root.findViewById(R.id.btn_speaker);
        dtmfDialpad = root.findViewById(R.id.dtmf_dialpad);
        dtmfDisplay = root.findViewById(R.id.dtmf_display);

        root.findViewById(R.id.btn_answer).setOnClickListener(v -> {
            CallManager.getInstance().answer();
            refresh();
        });
        root.findViewById(R.id.btn_decline).setOnClickListener(v ->
                CallManager.getInstance().endOrReject());
        muteButton.setOnClickListener(v -> {
            CallManager.getInstance().toggleMute();
            updateToggleStates();
        });
        speakerButton.setOnClickListener(v -> {
            CallManager.getInstance().toggleSpeaker();
            updateToggleStates();
        });
        root.findViewById(R.id.btn_keypad).setOnClickListener(v -> showDtmf(true));
        root.findViewById(R.id.dtmf_hide).setOnClickListener(v -> showDtmf(false));

        bindDtmf(R.id.dtmf_1, '1'); bindDtmf(R.id.dtmf_2, '2'); bindDtmf(R.id.dtmf_3, '3');
        bindDtmf(R.id.dtmf_4, '4'); bindDtmf(R.id.dtmf_5, '5'); bindDtmf(R.id.dtmf_6, '6');
        bindDtmf(R.id.dtmf_7, '7'); bindDtmf(R.id.dtmf_8, '8'); bindDtmf(R.id.dtmf_9, '9');
        bindDtmf(R.id.dtmf_star, '*'); bindDtmf(R.id.dtmf_0, '0'); bindDtmf(R.id.dtmf_hash, '#');
    }

    private void bindDtmf(int id, char c) {
        View v = root.findViewById(id);
        if (v != null) {
            v.setOnClickListener(view -> {
                CallManager.getInstance().sendDtmf(c);
                dtmfDigits.append(c);
                if (dtmfDisplay != null) {
                    dtmfDisplay.setText(dtmfDigits.toString());
                }
            });
        }
    }

    private void showDtmf(boolean show) {
        if (dtmfDialpad == null) {
            return;
        }
        if (show) {
            dtmfDigits.setLength(0);
            if (dtmfDisplay != null) {
                dtmfDisplay.setText("");
            }
            dtmfDialpad.setVisibility(View.VISIBLE);
        } else {
            dtmfDialpad.setVisibility(View.GONE);
        }
    }

    private void refresh() {
        if (root == null) {
            return;
        }
        CallManager cm = CallManager.getInstance();
        if (!cm.hasCall()) {
            dismiss();
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
                if (!finishing) {
                    finishing = true;
                    handler.postDelayed(this::dismiss, 600L);
                }
                break;
            default:
                break;
        }
    }

    private void setStatus(int baseRes, String sim) {
        String base = context.getString(baseRes);
        statusView.setText(sim != null && !sim.isEmpty() ? base + "  ·  " + sim : base);
    }

    private void updateToggleStates() {
        CallManager cm = CallManager.getInstance();
        muteButton.setActivated(cm.isMuted());
        speakerButton.setActivated(cm.isSpeakerOn());
    }

    @Override
    public void onCallStateChanged(int state) {
        handler.post(this::refresh);
    }

    @Override
    public void onCallRemoved() {
        handler.post(this::dismiss);
    }

    static {
        Log.d(Const.LOG_TAG, TAG + " loaded");
    }
}
