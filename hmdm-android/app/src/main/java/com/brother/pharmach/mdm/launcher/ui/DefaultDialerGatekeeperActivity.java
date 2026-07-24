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

package com.brother.pharmach.mdm.launcher.ui;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.helper.DefaultDialerHelper;
import com.brother.pharmach.mdm.launcher.util.OemCompat;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import java.util.List;

/**
 * Blocking gatekeeper shown until the app is the system default phone app (default dialer).
 *
 * <p>Behaviour requested for the custom call receiver: the moment the app is installed / updated,
 * if it is not already the default dialer the user is pushed to set it and <b>cannot do anything
 * else</b> until they do — Back is disabled, pressing Home re-launches this screen
 * ({@link #onUserLeaveHint()}), and {@code BatteryOptimizationMonitor} re-launches it if the user
 * manages to switch to another app. It dismisses itself automatically once the role is held.
 *
 * <p>On Device Owner devices this screen is normally never seen because the role is set silently;
 * it is the enforcement fallback for non-DO / silent-set-refused deployments.
 */
public class DefaultDialerGatekeeperActivity extends AppCompatActivity {

    private static final String TAG = "DialerGatekeeper";

    /** Grace window during which the monitor must NOT re-launch us (a system dialog is on top). */
    private static final long REQUEST_GRACE_MS = 60_000L;
    private static volatile long sRequestInProgressUntil = 0;

    // True while a system dialog we launched (role picker / permission / settings) is on top, so
    // onUserLeaveHint does not fight it.
    private boolean mRequesting = false;

    /** True while a request dialog is expected on screen; the poll monitor suppresses re-launch. */
    public static boolean isRequestInProgress() {
        return SystemClock.elapsedRealtime() < sRequestInProgressUntil;
    }

    private static void beginRequestGrace() {
        sRequestInProgressUntil = SystemClock.elapsedRealtime() + REQUEST_GRACE_MS;
    }

    private static void endRequestGrace() {
        sRequestInProgressUntil = 0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_default_dialer_gatekeeper);
        com.brother.pharmach.mdm.launcher.util.InsetsUtils.applySystemBarPadding(
                findViewById(R.id.gatekeeper_root));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // no-op — cannot be dismissed until the app is the default dialer
            }
        });

        Button btnSetDefault = findViewById(R.id.btn_set_default_dialer);
        btnSetDefault.setOnClickListener(v -> startRequestChain());

        Button btnSettings = findViewById(R.id.btn_open_default_apps_settings);
        btnSettings.setOnClickListener(v -> openDefaultAppsSettings());

        RemoteLogger.log(this, Const.LOG_WARN,
                "Default-dialer gatekeeper displayed — app is not the default phone app. "
                        + "User must set it before continuing.");

        if (savedInstanceState == null) {
            // Auto-fire the request immediately so most users only see the system picker.
            startRequestChain();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // This activity is singleTask, so re-launches (Home press, poll, launcher re-enforce) come
        // here — NOT onCreate. Re-fire the picker so returning to the gatekeeper always re-presents
        // the "Set as default phone app" system dialog instead of a static screen.
        if (!mRequesting && !DefaultDialerHelper.isDefaultDialer(this)) {
            startRequestChain();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returned to us — any dialog we launched is gone.
        mRequesting = false;
        endRequestGrace();
        if (DefaultDialerHelper.isDefaultDialer(this)) {
            RemoteLogger.log(this, Const.LOG_INFO,
                    "App is now the default phone app — dismissing gatekeeper.");
            // Ensure privileges / FSI are in order now that we hold the role.
            DefaultDialerHelper.grantCallPermissions(this);
            DefaultDialerHelper.ensureFullScreenIntentPermission(this);
            finish();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (mRequesting) {
            return; // user is interacting with the system role/permission dialog we launched
        }
        // Home / recents pressed without resolving — pull the gatekeeper straight back to front.
        Intent reopen = new Intent(this, DefaultDialerGatekeeperActivity.class);
        reopen.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(reopen);
    }

    @Override
    @SuppressWarnings("MissingSuperCall")
    public void onBackPressed() {
        // no-op — safety net for OEM gesture-nav that bypasses the dispatcher
    }

    /**
     * Drives setup in order: Device-Owner silent set → runtime permissions → system role picker.
     * Each step returns; the next resume/result continues so at most one dialog is on screen.
     */
    private void startRequestChain() {
        if (DefaultDialerHelper.ensureCallSetup(this)) {
            // Device Owner silent set (or already default) — onResume will finish.
            return;
        }

        List<String> missing = DefaultDialerHelper.missingRuntimeCallPermissions(this);
        if (!missing.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mRequesting = true;
            beginRequestGrace();
            try {
                requestPermissions(missing.toArray(new String[0]),
                        DefaultDialerHelper.REQUEST_CALL_PERMISSIONS);
            } catch (Exception e) {
                mRequesting = false;
                endRequestGrace();
                Log.w(TAG, "requestPermissions failed: " + e.getMessage());
            }
            return;
        }

        mRequesting = true;
        beginRequestGrace();
        DefaultDialerHelper.requestDefaultDialer(this, DefaultDialerHelper.REQUEST_DEFAULT_DIALER);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == DefaultDialerHelper.REQUEST_CALL_PERMISSIONS) {
            mRequesting = false;
            endRequestGrace();
            // Continue to the role picker (or finish if we somehow already hold it).
            if (!DefaultDialerHelper.isDefaultDialer(this)) {
                mRequesting = true;
                beginRequestGrace();
                DefaultDialerHelper.requestDefaultDialer(this,
                        DefaultDialerHelper.REQUEST_DEFAULT_DIALER);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DefaultDialerHelper.REQUEST_DEFAULT_DIALER) {
            mRequesting = false;
            endRequestGrace();
            // Log the outcome so the server shows whether the user accepted the role or dismissed it.
            RemoteLogger.log(this, Const.LOG_INFO,
                    "Default-dialer picker returned: resultCode=" + resultCode
                            + " isDefaultDialerNow=" + DefaultDialerHelper.isDefaultDialer(this));
            // onResume re-checks and finishes if the role was granted; otherwise we stay blocking.
        }
    }

    /** Manual fallback: open the system "Default apps" screen where the phone app can be chosen. */
    private void openDefaultAppsSettings() {
        mRequesting = true;
        beginRequestGrace();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Default apps settings unavailable: " + e.getMessage());
        }
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(details);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "No settings path available on this ROM: " + e.getMessage());
            mRequesting = false;
            endRequestGrace();
        }
    }

    /**
     * Launch the gatekeeper from any context if enforcement is required. Safe to call repeatedly;
     * a no-op when the app is already the default dialer or the device cannot hold the role.
     */
    public static void enforce(Context context) {
        try {
            if (!DefaultDialerHelper.shouldEnforceDefaultDialer(context)) {
                return;
            }
            if (isRequestInProgress()) {
                return; // a system dialog is up; don't yank it away
            }
            if (isSettingsOrPickerForeground(context)) {
                // The user is in the Settings app / default-apps picker / role dialog — exactly
                // where they change the default phone app. Do NOT pull them out; let them finish.
                return;
            }
            Intent i = new Intent(context, DefaultDialerGatekeeperActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "enforce() failed: " + e.getMessage());
        }
    }

    /**
     * True when the current foreground app is the Settings app, an OEM settings/default-apps
     * screen, or the system role/permission picker — the places a user legitimately needs to visit
     * to change the default phone app. Uses UsageStats (same source as the WorkTime foreground
     * poller); if usage-access is not granted the query is empty and this returns false, so
     * enforcement still proceeds (fail-safe toward enforcing).
     */
    private static boolean isSettingsOrPickerForeground(Context context) {
        String pkg = foregroundPackage(context);
        if (pkg == null) {
            return false;
        }
        if (OemCompat.isSettingsFamilyPackage(pkg)) {
            return true;
        }
        switch (pkg) {
            case "com.android.settings":
            case "com.android.permissioncontroller":          // AOSP role/permission picker
            case "com.google.android.permissioncontroller":   // GMS role/permission picker
            case "com.android.packageinstaller":              // legacy default-app / role picker
                return true;
            default:
                return false;
        }
    }

    /** Most-recently-foregrounded package via UsageStats, or null when unavailable. */
    private static String foregroundPackage(Context context) {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            List<UsageStats> stats =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000, now);
            if (stats == null || stats.isEmpty()) {
                return null;
            }
            UsageStats recent = null;
            for (UsageStats s : stats) {
                if (recent == null || s.getLastTimeUsed() > recent.getLastTimeUsed()) {
                    recent = s;
                }
            }
            return recent != null ? recent.getPackageName() : null;
        } catch (Exception e) {
            Log.w(TAG, "foregroundPackage query failed: " + e.getMessage());
            return null;
        }
    }
}
