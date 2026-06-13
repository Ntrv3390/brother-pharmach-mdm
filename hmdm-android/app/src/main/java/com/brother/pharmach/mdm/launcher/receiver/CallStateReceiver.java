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
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.brother.pharmach.mdm.launcher.worker.CallLogUploadWorker;

import java.util.concurrent.TimeUnit;

public class CallStateReceiver extends BroadcastReceiver {
    private static final String TAG = "CallStateReceiver";
    private static final String PREFS_NAME = "CallStatePrefs";
    private static final String PREF_LAST_STATE = "last_call_state";
    // Unique work name — prevents duplicate jobs when calls happen back-to-back
    private static final String WORK_UNIQUE_NAME = "call_log_upload";

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
        int lastState = readLastState(context);
        Log.d(TAG, "Phone state changed: " + lastState + " -> " + state);
        if ((lastState == TelephonyManager.CALL_STATE_OFFHOOK || lastState == TelephonyManager.CALL_STATE_RINGING)
                && state == TelephonyManager.CALL_STATE_IDLE) {
            Log.i(TAG, "Call end/miss detected, scheduling call log upload");
            scheduleUpload(context);
        }
        saveLastState(context, state);
    }

    private int readLastState(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_LAST_STATE, TelephonyManager.CALL_STATE_IDLE);
    }

    private void saveLastState(Context context, int state) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(PREF_LAST_STATE, state).apply();
    }

    private void scheduleUpload(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest uploadWork = new OneTimeWorkRequest.Builder(CallLogUploadWorker.class)
                .setInitialDelay(5, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build();
        // KEEP: if a previous call already queued an upload, don't replace — let the in-flight job finish.
        // Both calls' records share the same watermark scan so a single run captures all new calls.
        WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_UNIQUE_NAME, ExistingWorkPolicy.KEEP, uploadWork);
    }
}
