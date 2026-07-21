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

package com.brother.pharmach.mdm.launcher.receiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.phone.CallManager;

/**
 * Handles the answer/decline/hang-up actions attached to the incoming-call notification so the
 * user can control the call straight from the heads-up banner or lock screen without opening the
 * full-screen UI. Delegates to {@link CallManager}, which owns the live {@code android.telecom.Call}.
 */
public class CallActionReceiver extends BroadcastReceiver {

    private static final String TAG = "CallActionReceiver";

    public static final String ACTION_ANSWER = "com.brother.pharmach.mdm.launcher.CALL_ANSWER";
    public static final String ACTION_REJECT = "com.brother.pharmach.mdm.launcher.CALL_REJECT";
    public static final String ACTION_END    = "com.brother.pharmach.mdm.launcher.CALL_END";

    public static PendingIntent actionPendingIntent(Context context, String action) {
        Intent intent = new Intent(context, CallActionReceiver.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        // Distinct request codes so the three actions don't collapse into one PendingIntent.
        int requestCode = action.hashCode();
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return; // Telecom Call control is API 23+
        }
        String action = intent.getAction();
        Log.i(TAG, "Call action: " + action);
        CallManager cm = CallManager.getInstance();
        switch (action) {
            case ACTION_ANSWER:
                cm.answer();
                break;
            case ACTION_REJECT:
                cm.reject();
                break;
            case ACTION_END:
                cm.endOrReject();
                break;
            default:
                break;
        }
    }
}
