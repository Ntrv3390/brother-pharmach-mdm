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
import com.brother.pharmach.mdm.launcher.service.StatusControlService;
import com.brother.pharmach.mdm.launcher.util.Utils;
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
        // TYPE_WINDOWS_CHANGED is intentionally excluded: it fires for every window in the
        // recents stack (thumbnails of all recent apps), which caused recents to auto-close
        // when restricted apps appeared in the recents overview. TYPE_WINDOW_STATE_CHANGED
        // fires only when a window gains focus, which is sufficient for blocking.
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

        // Mobile-data enforcement: while the policy requires data ON, a SIM is present and data is
        // OFF, the only apps the user may use are the launcher itself, the system Settings (to reach
        // the mobile-network toggle) and the phone/emergency UIs (never block calls). Any other app
        // is bounced immediately: return home (the launcher is the kiosk home) and raise the
        // persistent "turn on mobile data" prompt.
        if (StatusControlService.isMobileDataViolationActive()
                && !Utils.isAllowedDuringMobileDataViolation(this, pkg)) {
            Log.d(TAG, "Mobile data off — bouncing out of " + pkg);
            performGlobalAction(GLOBAL_ACTION_HOME);
            Intent violation = new Intent(Const.ACTION_POLICY_VIOLATION);
            violation.putExtra(Const.POLICY_VIOLATION_CAUSE, Const.MOBILE_DATA_ON_REQUIRED);
            LocalBroadcastManager.getInstance(this).sendBroadcast(violation);
            return;
        }

        if (WorkTimeManager.getInstance().isWithinUserLaunchGrace(pkg)) {
            // The user just tapped this app in the launcher — don't fight their intent.
            return;
        }
        if (!WorkTimeManager.getInstance().isAppAllowed(this, pkg)) {
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
