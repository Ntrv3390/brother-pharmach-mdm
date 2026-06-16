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

package com.brother.pharmach.mdm.launcher.pro.worker;

import android.os.AsyncTask;
import android.content.Context;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.service.LocationForegroundService;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

/**
 * These functions are available in Pro-version only
 * In a free version, the class contains stubs
 */
public class DetailedInfoWorker {
    private static final long MIN_REQUEST_INTERVAL_MS = 30000;
    private static volatile long lastRequestMs = 0;

    public static void schedule(Context context) {
        // stub
    }

    public static void requestConfigUpdate(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < MIN_REQUEST_INTERVAL_MS) {
            return;
        }
        lastRequestMs = now;

        try {
            LocationForegroundService.triggerUrgent(context);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "Failed to trigger location service for DeviceInfo refresh: " + e.getMessage());
        }
    }
}
