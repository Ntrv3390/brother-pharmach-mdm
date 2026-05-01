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

package com.brother.pharmach.mdm.launcher.pro.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.util.WorkTimeManager;

/**
 * Accessibility service that intercepts every foreground window change and blocks
 * restricted apps during enforced WorkTime windows. Covers all launch vectors:
 * push notifications, recents, Play Store intents, deep links, etc.
 */
public class CheckForegroundAppAccessibilityService extends AccessibilityService {

    private static final String TAG = "WorkTimeAccessibility";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "WorkTime accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName == null) {
            return;
        }
        String pkg = packageName.toString();
        if (pkg.isEmpty() || pkg.equals(getPackageName())) {
            return;
        }
        if (!WorkTimeManager.getInstance().isAppAllowed(pkg)) {
            Log.d(TAG, "Blocking restricted app: " + pkg);
            Intent blockIntent = new Intent(Const.ACTION_HIDE_SCREEN);
            blockIntent.putExtra(Const.PACKAGE_NAME, pkg);
            LocalBroadcastManager.getInstance(this).sendBroadcast(blockIntent);
        }
    }

    @Override
    public void onInterrupt() {
        // Required by AccessibilityService — no-op
    }
}
