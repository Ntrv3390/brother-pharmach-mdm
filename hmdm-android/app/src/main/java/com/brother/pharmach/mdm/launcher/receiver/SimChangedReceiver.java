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

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.DeviceInfo;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;
import com.brother.pharmach.mdm.launcher.service.StatusControlService;
import com.brother.pharmach.mdm.launcher.util.DeviceInfoProvider;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class SimChangedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, final Intent intent) {
        // A SIM was inserted, removed, locked or (on eSIM) switched. Kick the enforcement
        // engine so it re-binds its per-subscription telephony callbacks (which are pinned to
        // subscription IDs that go stale on a swap) and re-evaluates the mobile-data policy
        // immediately, instead of waiting up to a second for the watchdog to notice.
        try {
            StatusControlService.start(context);
            LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(new Intent(Const.ACTION_SIM_STATE_CHANGED));
        } catch (Exception ignored) {
        }

        // SIM card changed, log the new IMSI and number
        String phoneNumber = null;
        try {
            phoneNumber = DeviceInfoProvider.getPhoneNumber(context);
        } catch (Exception e) {
        }

        // Some OEM/AOSP builds broadcast SIM_STATE_CHANGED with null extras or without the "ss"
        // string. Read defensively — an NPE here would be uncaught on the main thread and kill
        // the process (dropping the user back to the system launcher).
        android.os.Bundle extras = intent.getExtras();
        String simState = extras != null ? extras.getString("ss") : null;

        String message = null;
        if ("LOADED".equals(simState)) {
            message = "SIM card loaded";
            if (phoneNumber != null && phoneNumber.length() > 0) {
                message += ". New phone number: " + phoneNumber;
            }
            // Upload fresh device info so the server gets the phone number
            uploadDeviceInfoAsync(context);
        } else if ("ABSENT".equals(simState)) {
            message = "SIM card removed";
        }

        if (message != null) {
            RemoteLogger.log(context, Const.LOG_INFO, message);
        }
    }

    /**
     * Sends device info (including phone number) to the server in a background
     * thread. A short delay lets the SIM finish registering before we read the
     * number from TelephonyManager.
     */
    private static void uploadDeviceInfoAsync(final Context context) {
        new Thread(() -> {
            try {
                // Give SIM a few seconds to fully register
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            try {
                SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
                if (settingsHelper == null || settingsHelper.getConfig() == null) {
                    // Not enrolled yet — the regular enrollment flow will capture the number
                    return;
                }

                DeviceInfo deviceInfo = DeviceInfoProvider.getDeviceInfo(context, true, true);

                ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
                ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);

                Response<ResponseBody> response = null;
                try {
                    response = serverService.sendDevice(settingsHelper.getServerProject(), deviceInfo).execute();
                } catch (Exception ignored) {
                }

                if (response == null || !response.isSuccessful()) {
                    try {
                        response = secondaryServerService.sendDevice(settingsHelper.getServerProject(), deviceInfo).execute();
                    } catch (Exception ignored) {
                    }
                }

                if (response != null && response.isSuccessful()) {
                    RemoteLogger.log(context, Const.LOG_INFO, "Device info (phone number) uploaded after SIM load");
                } else {
                    RemoteLogger.log(context, Const.LOG_WARN, "Failed to upload device info after SIM load");
                }
            } catch (Exception e) {
                RemoteLogger.log(context, Const.LOG_WARN, "Exception uploading device info after SIM load: " + e.getMessage());
            }
        }).start();
    }
}
