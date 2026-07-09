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
import com.brother.pharmach.mdm.launcher.util.WorkTimeManager;

/**
 * Accessibility service that intercepts every foreground window change and blocks
 * restricted apps during enforced WorkTime windows. Covers all launch vectors:
 * push notifications, recents, Play Store intents, deep links, etc.
 */
public class CheckForegroundAppAccessibilityService extends AccessibilityService {

    private static final String TAG = "WorkTimeAccessibility";

    // Live instance + last foreground package, so the mobile-data watchdog can force a user who
    // is sitting still in a blocked app back to the launcher (no window-change event fires then).
    private static volatile CheckForegroundAppAccessibilityService instance;
    private static volatile String lastForegroundPkg;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
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
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
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
        if (pkg.isEmpty()) {
            return;
        }
        lastForegroundPkg = pkg;
        if (pkg.equals(getPackageName())) {
            return;
        }

        // Mobile-data enforcement: while the policy requires data ON, a SIM is present and data is
        // OFF, the only place the user may go is the launcher itself and the system Settings (to
        // reach the mobile-network toggle) — plus the phone/emergency UIs, which must never be
        // blocked. Any other app is bounced immediately back to the launcher with the persistent
        // "turn on mobile data" prompt.
        if (StatusControlService.isMobileDataViolationActive() && !isAllowedDuringDataViolation(pkg)) {
            Log.d(TAG, "Mobile data off — bouncing out of " + pkg);
            bounceHome();
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

    private void bounceHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
        Intent violation = new Intent(Const.ACTION_POLICY_VIOLATION);
        violation.putExtra(Const.POLICY_VIOLATION_CAUSE, Const.MOBILE_DATA_ON_REQUIRED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(violation);
    }

    /**
     * Called from the mobile-data watchdog every tick while data is off. If the user is currently
     * parked in a blocked app (a case that fires no window-change event), force them back to the
     * launcher. No-op if the foreground is already the launcher or an allowed screen (Settings /
     * call), so it never fights the user while they are on the mobile-network settings page.
     */
    public static void reassertIfViolating() {
        CheckForegroundAppAccessibilityService self = instance;
        if (self == null) {
            return;
        }
        if (!StatusControlService.isMobileDataViolationActive()) {
            return;
        }
        String pkg = lastForegroundPkg;
        if (pkg == null || pkg.equals(self.getPackageName())) {
            return;
        }
        if (self.isAllowedDuringDataViolation(pkg)) {
            return;
        }
        Log.d(TAG, "Mobile data off — re-asserting launcher over parked app " + pkg);
        self.bounceHome();
    }

    private String settingsPkg;

    /**
     * The ONLY thing the user is allowed to reach while mobile data is off (besides the launcher):
     * the system Settings, so they can turn data back on. Everything else — including the phone
     * dialer — is bounced back to the launcher.
     */
    private boolean isAllowedDuringDataViolation(String pkg) {
        if (settingsPkg == null) {
            try {
                android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(
                        new Intent(android.provider.Settings.ACTION_SETTINGS), 0);
                if (ri != null && ri.activityInfo != null) {
                    settingsPkg = ri.activityInfo.packageName;
                }
            } catch (Exception ignored) {
            }
            if (settingsPkg == null) {
                settingsPkg = "com.android.settings";
            }
        }
        return pkg.equals(settingsPkg) || pkg.contains("settings");
    }

    @Override
    public void onInterrupt() {
        // Required by AccessibilityService — no-op
    }
}
