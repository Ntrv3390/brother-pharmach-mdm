package com.brother.pharmach.mdm.launcher.ui;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.AdminReceiver;
import com.brother.pharmach.mdm.launcher.Constants;
import com.brother.pharmach.mdm.launcher.R;

/**
 * Blocking gatekeeper activity shown when the device is not exempt from battery optimization.
 * The user cannot dismiss this screen until the exemption is granted.
 *
 * Manifest attributes (declared in AndroidManifest.xml):
 *   launchMode="singleTask"
 *   excludeFromRecents="true"
 *   showOnLockScreen="true"
 *   turnScreenOn="true"
 *   screenOrientation="portrait"
 *   exported="false"
 */
public class ComplianceGatekeeperActivity extends AppCompatActivity {

    private static final String TAG = "ComplianceGatekeeper";

    // Registered in onResume, unregistered in onPause to avoid leaks
    private final BroadcastReceiver mComplianceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_COMPLIANCE_RESTORED.equals(intent.getAction())) {
                Log.i(TAG, "Compliance restored — stopping LockTask and finishing");
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                    stopLockTask();
                }
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compliance_gatekeeper);

        // OnBackPressedCallback is from androidx.activity:activity — NOT a platform API.
        // It works on API 23+ via AndroidX and does not require an API level check.
        // Add dependency: implementation 'androidx.activity:activity:1.8.0'
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // no-op — this gatekeeper cannot be dismissed by the user
            }
        });

        Button btnSettings = findViewById(R.id.btn_open_settings);
        btnSettings.setOnClickListener(v -> openBatterySettings());

        tryStartLockTask();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mComplianceReceiver,
                new IntentFilter(Constants.ACTION_COMPLIANCE_RESTORED)
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mComplianceReceiver);
    }

    // Retain onBackPressed() override ONLY as a safety net for edge cases
    // where AppCompatActivity's dispatcher is bypassed (e.g. some OEM gesture nav implementations)
    @Override
    @SuppressWarnings("MissingSuperCall")
    public void onBackPressed() {
        // no-op — intentionally suppressed; enforced by OnBackPressedCallback above
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Fires when the Home button is pressed or the app is sent to background.
        // Re-bring the gatekeeper to front so the user cannot escape.
        Intent reopen = new Intent(this, ComplianceGatekeeperActivity.class);
        reopen.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(reopen);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && !isFinishing()) {
            // Covers: gesture navigation pulling down notification shade,
            // Recents on non-LockTask path, assistant overlay activation
            Intent reopen = new Intent(this, ComplianceGatekeeperActivity.class);
            reopen.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(reopen);
        }
    }

    private void tryStartLockTask() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not Device Owner — LockTask unavailable, using soft blocking only");
            return;
        }
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;

        // getLockTaskModeState() available from API 23.
        // Calling startLockTask() when already in LockTask mode throws IllegalStateException.
        if (am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                startLockTask();
            } catch (IllegalStateException e) {
                // setLockTaskPackages() was not called yet — policy may still be propagating.
                // OEM-QUIRK: On some Samsung One UI builds, there is a race between
                // DevicePolicyManager applying policies and Activity startup. Retry after 500ms.
                Log.e(TAG, "startLockTask() failed: " + e.getMessage());
                new Handler(Looper.getMainLooper()).postDelayed(this::tryStartLockTask, 500);
            }
        }
        // If getLockTaskModeState() != LOCK_TASK_MODE_NONE, LockTask is already active — no-op
    }

    private void openBatterySettings() {
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + package URI opens the per-app
        // exemption dialog directly — available from API 23.
        // Do NOT use ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (opens full list).
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // OEM-QUIRK: Some heavily modified ROMs (certain EMUI builds) do not
            // expose this settings screen. Fall back to general battery settings.
            Log.w(TAG, "Battery exemption dialog not available on this ROM — falling back");
            try {
                startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
            } catch (ActivityNotFoundException e2) {
                Log.e(TAG, "Battery saver settings also unavailable on this ROM");
            }
        }
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }
}
