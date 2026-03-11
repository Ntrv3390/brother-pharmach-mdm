/*
 * Brother Pharmach MDM: Open Source Android MDM Software
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

package com.brother.pharmach.mdm.launcher.pro.worker;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.content.Context;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.DetailedInfo;
import com.brother.pharmach.mdm.launcher.json.DeviceInfo;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;
import com.brother.pharmach.mdm.launcher.service.LocationService;
import com.brother.pharmach.mdm.launcher.util.DeviceInfoProvider;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import java.util.Collections;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * These functions are available in Pro-version only
 * In a free version, the class contains stubs
 */
public class DetailedInfoWorker {
    public static void schedule(Context context) {
        // stub
    }

    public static void requestConfigUpdate(Context context) {
        try {
            Intent intent = new Intent(context, LocationService.class);
            intent.setAction(LocationService.ACTION_UPDATE_GPS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "Failed to start location service for DeviceInfo refresh: " + e.getMessage());
        }

        AsyncTask.execute(() -> uploadLatestKnownLocation(context));
    }

    private static void uploadLatestKnownLocation(Context context) {
        try {
            SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
            if (settingsHelper == null || settingsHelper.getConfig() == null) {
                return;
            }

            DeviceInfo.Location location = DeviceInfoProvider.getLocation(context);
            if (location == null) {
                return;
            }

            DetailedInfo detailedInfo = new DetailedInfo();
            detailedInfo.setTs(location.getTs() > 0 ? location.getTs() : System.currentTimeMillis());

            DetailedInfo.Gps gps = new DetailedInfo.Gps();
            gps.setLat(location.getLat());
            gps.setLon(location.getLon());
            detailedInfo.setGps(gps);

            ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
            ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);

            Response<ResponseBody> response = null;
            try {
                response = serverService.sendDetailedInfo(settingsHelper.getServerProject(),
                        settingsHelper.getDeviceId(), Collections.singletonList(detailedInfo)).execute();
            } catch (Exception ignored) {
            }

            if (response == null || !response.isSuccessful()) {
                response = secondaryServerService.sendDetailedInfo(settingsHelper.getServerProject(),
                        settingsHelper.getDeviceId(), Collections.singletonList(detailedInfo)).execute();
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "Failed to upload latest DeviceInfo location: " + e.getMessage());
        }
    }
}
