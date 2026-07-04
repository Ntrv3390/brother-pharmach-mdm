package com.brother.pharmach.mdm.launcher.ui;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.Constants;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

/**
 * Blocking gatekeeper shown when the device is not exempt from battery optimization.
 * Back button is disabled. Home button re-launches this screen.
 * The user must open Settings and grant the exemption; the screen dismisses automatically
 * once BatteryOptimizationMonitor detects compliance or the user returns from Settings.
 */
public class ComplianceGatekeeperActivity extends AppCompatActivity {

    private static final String TAG = "ComplianceGatekeeper";

    // True while the user is in the Settings app via the button — suppresses home-button defense.
    private boolean mOpeningSettings = false;

    // Elapsed-realtime timestamp of when the user left for Settings via the button; 0 when not
    // exploring. Static so BatteryOptimizationMonitor can suppress gatekeeper re-launches while
    // the user is navigating Settings (the exemption is often buried deep in OEM battery menus).
    private static volatile long sSettingsExplorationStartMs = 0;

    /** True while the user is inside the grace window for exploring Settings. */
    public static boolean isUserExploringSettings() {
        long start = sSettingsExplorationStartMs;
        return start != 0 && android.os.SystemClock.elapsedRealtime() - start
                < Constants.SETTINGS_EXPLORATION_GRACE_MS;
    }

    private final BroadcastReceiver mComplianceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_COMPLIANCE_RESTORED.equals(intent.getAction())) {
                Log.i(TAG, "Compliance restored broadcast received — finishing gatekeeper");
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compliance_gatekeeper);

        // OnBackPressedCallback is from androidx.activity:activity — NOT a platform API.
        // It works on API 23+ via AndroidX; no API level check needed.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // no-op — gatekeeper cannot be dismissed by the user
            }
        });

        Button btnAllowNow = findViewById(R.id.btn_allow_now);
        btnAllowNow.setOnClickListener(v -> requestExemptionPopup());

        Button btnSettings = findViewById(R.id.btn_open_settings);
        btnSettings.setOnClickListener(v -> openBatterySettings());

        Button btnFullSettings = findViewById(R.id.btn_open_full_settings);
        btnFullSettings.setOnClickListener(v -> openFullSettings());

        // Show the one-tap system popup immediately so most users never have to
        // navigate Settings at all. The buttons remain as fallback if it is denied
        // or unavailable on this ROM.
        if (savedInstanceState == null) {
            requestExemptionPopup();
        }

        RemoteLogger.log(this, Const.LOG_WARN,
                "Battery optimization compliance gatekeeper displayed — " +
                "device is not exempt from battery optimization. " +
                "User interaction required to grant exemption.");
    }

    @Override
    protected void onResume() {
        super.onResume();

        LocalBroadcastManager.getInstance(this).registerReceiver(
                mComplianceReceiver,
                new IntentFilter(Constants.ACTION_COMPLIANCE_RESTORED)
        );

        boolean returningFromSettings = mOpeningSettings;
        mOpeningSettings = false;
        // User is back on the gatekeeper — the Settings exploration window is over.
        sSettingsExplorationStartMs = 0;

        if (isBatteryCompliant()) {
            if (returningFromSettings) {
                RemoteLogger.log(this, Const.LOG_INFO,
                        "Battery optimization exemption granted by user via Settings — " +
                        "device is now exempt. MDM service will resume full monitoring.");
            }
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mComplianceReceiver);
    }

    // Safety net for OEM gesture nav implementations that bypass the OnBackPressedDispatcher
    @Override
    @SuppressWarnings("MissingSuperCall")
    public void onBackPressed() {
        // no-op — intentionally suppressed
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Allow navigation when the user deliberately opened Settings via the button.
        // BatteryOptimizationMonitor re-launches this screen in ≤30s if they don't fix it.
        if (mOpeningSettings) return;
        // Home button pressed without using the Settings button — bring gatekeeper back to front.
        Intent reopen = new Intent(this, ComplianceGatekeeperActivity.class);
        reopen.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(reopen);
    }

    /**
     * One-tap path: shows the system "Allow app to run in background?" dialog directly,
     * so the user does not have to find the setting. Requires the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS manifest permission. Some OEM ROMs strip or
     * reject this dialog — in that case we fall back to the manual Settings flow.
     */
    private void requestExemptionPopup() {
        mOpeningSettings = true;
        sSettingsExplorationStartMs = android.os.SystemClock.elapsedRealtime();
        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        request.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(request);
            RemoteLogger.log(this, Const.LOG_INFO,
                    "Battery exemption popup shown — waiting for the user to tap Allow.");
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Direct exemption popup unavailable on this ROM — " +
                    "falling back to app settings: " + e.getMessage());
            openBatterySettings();
        }
    }

    private void openBatterySettings() {
        mOpeningSettings = true;
        sSettingsExplorationStartMs = android.os.SystemClock.elapsedRealtime();
        RemoteLogger.log(this, Const.LOG_INFO,
                "User tapped 'Open Battery Settings' — navigating to app battery settings.");

        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS requires the
        // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission which is intentionally absent from this
        // manifest (it breaks installation on Realme/ColorOS). Go directly to the app details page
        // where the user can tap Battery → Unrestricted. This works with no special permissions.
        Intent appDetails = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        appDetails.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(appDetails);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "App details page unavailable — trying battery saver settings: " + e.getMessage());
        }

        // OEM-QUIRK: Last resort on EMUI/HarmonyOS builds where app details is restricted.
        try {
            startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "All settings paths unavailable on this ROM: " + e.getMessage());
            mOpeningSettings = false;
            sSettingsExplorationStartMs = 0;
        }
    }

    private void openFullSettings() {
        mOpeningSettings = true;
        sSettingsExplorationStartMs = android.os.SystemClock.elapsedRealtime();
        RemoteLogger.log(this, Const.LOG_INFO,
                "User tapped 'Open Phone Settings' — opening the Settings app root so the " +
                "device's own battery menus can be reached.");
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "Settings root unavailable on this ROM: " + e.getMessage());
            mOpeningSettings = false;
            sSettingsExplorationStartMs = 0;
        }
    }

    private boolean isBatteryCompliant() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }
}
