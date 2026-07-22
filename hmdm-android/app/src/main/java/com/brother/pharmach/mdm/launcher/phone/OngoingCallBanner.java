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

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.ui.IncomingCallActivity;

/**
 * Drives the launcher's "ongoing call" banner. Shown whenever a call is live (ringing / dialing /
 * active / on hold) so the user can always jump back to the call screen — which matters because,
 * with no keyguard, the call UI can end up behind the launcher and there is otherwise no way back.
 *
 * <p>Isolated in the {@code phone} package and gated at API 23 (its callers guard on SDK_INT) so it
 * is never class-loaded below API 23, where {@code android.telecom.Call} does not exist.
 */
@RequiresApi(api = Build.VERSION_CODES.M)
public class OngoingCallBanner implements CallManager.Listener {

    private final Activity activity;
    private final View banner;
    private final TextView text;

    public OngoingCallBanner(Activity activity, View banner, TextView text) {
        this.activity = activity;
        this.banner = banner;
        this.text = text;
        if (banner != null) {
            banner.setOnClickListener(v -> openCallScreen());
        }
    }

    public void start() {
        CallManager.getInstance().register(this);
        refresh();
    }

    public void stop() {
        CallManager.getInstance().unregister(this);
    }

    /**
     * If a call is currently ringing, bring the call screen back to the front. Called when the
     * launcher resumes so an unanswered incoming call is never left stranded behind the launcher
     * (which could otherwise cause a missed call). An already-connected call is left alone — the
     * user may be deliberately using the launcher during it — and can be reopened via the banner.
     */
    public void returnIfRinging() {
        if (CallManager.getInstance().isIncomingRinging()) {
            openCallScreen();
        }
    }

    private void openCallScreen() {
        CallManager cm = CallManager.getInstance();
        if (!cm.hasCall()) {
            return;
        }
        try {
            Intent i = IncomingCallActivity.newIntent(activity, cm.isIncomingRinging());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private void refresh() {
        if (banner == null) {
            return;
        }
        CallManager cm = CallManager.getInstance();
        if (!cm.hasCall()) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setVisibility(View.VISIBLE);
        if (text != null) {
            String base = activity.getString(cm.isIncomingRinging()
                    ? R.string.ongoing_call_incoming_tap
                    : R.string.ongoing_call_tap_return);
            String name = cm.getCallerName();
            String number = cm.getCallerNumber();
            String who = (name != null && !name.isEmpty()) ? name
                    : (number != null && !number.isEmpty() ? number : null);
            text.setText(who != null ? who + "  ·  " + base : base);
        }
    }

    @Override
    public void onCallStateChanged(int state) {
        activity.runOnUiThread(this::refresh);
    }

    @Override
    public void onCallRemoved() {
        activity.runOnUiThread(this::refresh);
    }
}
