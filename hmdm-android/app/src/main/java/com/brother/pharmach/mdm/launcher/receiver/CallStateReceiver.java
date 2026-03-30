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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.brother.pharmach.mdm.launcher.worker.CallLogUploadWorker;

import java.util.concurrent.TimeUnit;

public class CallStateReceiver extends BroadcastReceiver {
    private static final String TAG = "CallStateReceiver";
    private static int lastState = TelephonyManager.CALL_STATE_IDLE;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            int state = TelephonyManager.CALL_STATE_IDLE;
            if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(stateStr)) {
                state = TelephonyManager.CALL_STATE_OFFHOOK;
            } else if (TelephonyManager.EXTRA_STATE_RINGING.equals(stateStr)) {
                state = TelephonyManager.CALL_STATE_RINGING;
            }

            onCallStateChanged(context, state);
        }
    }

    private void onCallStateChanged(Context context, int state) {
        Log.d(TAG, "Phone state changed: " + lastState + " -> " + state);
        if ((lastState == TelephonyManager.CALL_STATE_OFFHOOK || lastState == TelephonyManager.CALL_STATE_RINGING)
                && state == TelephonyManager.CALL_STATE_IDLE) {
            // Call ended or missed call
            Log.i(TAG, "Call end/miss detected, scheduling call log upload");
            scheduleUpload(context);
        }
        lastState = state;
    }

    private void scheduleUpload(Context context) {
        // Schedule worker with a 5-second delay to ensure logs are written
        OneTimeWorkRequest uploadWork = new OneTimeWorkRequest.Builder(CallLogUploadWorker.class)
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build();
        WorkManager.getInstance(context).enqueue(uploadWork);
    }
}
