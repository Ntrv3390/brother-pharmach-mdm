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
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.AdminReceiver;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.Constants;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

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

    // Set true before opening Settings so focus/leave-hint defenses don't fight it.
    // Reset to false in onResume() when we return.
    private boolean mOpeningSettings = false;

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

        RemoteLogger.log(this, Const.LOG_WARN,
                "Battery optimization compliance gatekeeper displayed — " +
                "device is not exempt from battery optimization. " +
                "User interaction required to grant exemption.");

        tryStartLockTask();
    }

    @Override
    protected void onResume() {
        super.onResume();

        LocalBroadcastManager.getInstance(this).registerReceiver(
                mComplianceReceiver,
                new IntentFilter(Constants.ACTION_COMPLIANCE_RESTORED)
        );

        boolean returningFromSettings = mOpeningSettings;
        // Clear the flag now so defenses re-arm for the rest of this resume.
        mOpeningSettings = false;

        if (!isBatteryCompliant()) {
            // Re-enter LockTask if the user came back from Settings without granting exemption.
            tryStartLockTask();
        } else if (returningFromSettings) {
            // User came back from Settings and the device is now compliant — log the grant.
            RemoteLogger.log(this, Const.LOG_INFO,
                    "Battery optimization exemption granted by user via Settings — " +
                    "device is now exempt. MDM service will resume full monitoring.");
        }
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
        // Skip the reopen logic when we intentionally navigated to Settings.
        if (mOpeningSettings) return;
        // Fires when the Home button is pressed or the app is sent to background.
        // Re-bring the gatekeeper to front so the user cannot escape.
        Intent reopen = new Intent(this, ComplianceGatekeeperActivity.class);
        reopen.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(reopen);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Skip the reopen logic when we intentionally navigated to Settings —
        // this callback fires the instant startActivity() is called, which would
        // immediately cancel the Settings navigation.
        if (!hasFocus && !isFinishing() && !mOpeningSettings) {
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
        // Signal defenses to stand down — we are intentionally leaving to open Settings.
        mOpeningSettings = true;
        RemoteLogger.log(this, Const.LOG_INFO,
                "User tapped 'Open Battery Settings' on the compliance gatekeeper — " +
                "navigating to battery optimization settings.");

        // LockTask mode blocks ALL external apps from launching, including Settings.
        // Exit LockTask first so the intent can succeed. We re-enter in onResume() if
        // the user comes back without granting the exemption.
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
            stopLockTask();
        }

        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + package URI opens the per-app
        // exemption dialog directly — available from API 23.
        // Do NOT use ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (opens full list).
        // Note: if REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is absent from the manifest this
        // throws SecurityException (not ActivityNotFoundException) on some devices.
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            // OEM-QUIRK: Some heavily modified ROMs (certain EMUI builds) do not
            // expose this settings screen. Fall through to next option.
            Log.w(TAG, "Battery exemption dialog unavailable (" + e.getClass().getSimpleName() + ") — trying app details");
        }

        // Fallback 1: app details page — the user can tap Battery and set to Unrestricted
        try {
            Intent appDetails = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            appDetails.setData(Uri.parse("package:" + getPackageName()));
            startActivity(appDetails);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "App details settings unavailable — trying battery saver settings");
        }

        // Fallback 2: general battery saver settings page
        // OEM-QUIRK: Last resort on EMUI/HarmonyOS builds where both above are restricted.
        try {
            startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "All battery settings paths unavailable on this ROM");
            // Nothing opened — reset the flag so defenses re-arm immediately.
            mOpeningSettings = false;
        }
    }

    private boolean isBatteryCompliant() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }
}
