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

package com.brother.pharmach.mdm.launcher.phone;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import androidx.annotation.RequiresApi;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.helper.DefaultDialerHelper;
import com.brother.pharmach.mdm.launcher.ui.DefaultDialerGatekeeperActivity;
import com.brother.pharmach.mdm.launcher.util.InsetsUtils;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

/**
 * Overlay-based "you cannot use the device until this app is the default phone app" gate.
 *
 * <p>Why an overlay instead of an Activity: on non-kiosk devices the OS won't stop the user leaving
 * an Activity, and on Android 14/15 + ColorOS a background {@code startActivity} to re-assert an
 * Activity gate is dropped — so the user escapes into another app. A {@code SYSTEM_ALERT_WINDOW}
 * overlay floats above <b>every</b> app (and the home screen), so switching apps via Home/Recents
 * can't get behind it; it truly blocks interaction until the role is granted, then auto-dismisses.
 *
 * <p>Robustness for all devices: it self-heals on a 2s loop, hides itself briefly while the system
 * role picker is shown (an app overlay would otherwise cover the picker), re-appears if the user
 * cancels, and falls back to the blocking {@code DefaultDialerGatekeeperActivity} on the rare device
 * that grants no overlay permission.
 */
@RequiresApi(api = Build.VERSION_CODES.M)
public final class DefaultDialerGate {

    private static final String TAG = "DefaultDialerGate";
    // Fast tick while blocking so a foreign app is re-covered / bounced Home within ~0.7s (like the
    // WorkTime foreground enforcer). The loop only runs while the app is NOT the default dialer and
    // stops entirely once the role is granted, so this is not a permanent background cost.
    private static final long TICK_MS = 700L;
    // Short bridge after we launch Settings / the picker, only long enough for that screen to come
    // to the foreground (after which isSettingsOrPickerForeground() takes over the "stay hidden"
    // decision). Kept small so that if the user backs/Home out WITHOUT setting the default, the gate
    // re-blocks within ~1 tick instead of leaving a long free window.
    private static final long PICKER_GRACE_MS = 3_500L;
    private static volatile long sPickerGraceUntil = 0;

    /**
     * Called by RoleRequestActivity when the picker returns (granted or cancelled). Ends the launch
     * grace and re-evaluates immediately: if the role was granted the gate dismisses, otherwise it
     * re-blocks right away — no disabled buttons, since the picker path works.
     */
    public static void onRoleRequestFinished(Context ctx) {
        sPickerGraceUntil = 0;
        update(ctx);
    }

    private static DefaultDialerGate instance;

    public static synchronized DefaultDialerGate getInstance(Context ctx) {
        if (instance == null) {
            instance = new DefaultDialerGate(ctx.getApplicationContext());
        }
        return instance;
    }

    /** Entry point: evaluate and show/hide the gate. Safe to call from any thread/context. */
    public static void update(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            getInstance(ctx).kick();
        } catch (Exception e) {
            Log.w(TAG, "update failed: " + e.getMessage());
        }
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View root;
    private boolean shown;
    private boolean looping;

    private DefaultDialerGate(Context appContext) {
        this.context = appContext;
    }

    private void kick() {
        handler.post(() -> {
            evaluate();
            if (!looping) {
                looping = true;
                handler.postDelayed(tick, TICK_MS);
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean keepGoing = evaluate();
            if (keepGoing) {
                handler.postDelayed(this, TICK_MS);
            } else {
                looping = false;
            }
        }
    };

    /** @return true while enforcement is still needed (loop keeps running). */
    private boolean evaluate() {
        // Role satisfied (or device can't hold it) → tear the gate down and stop.
        if (!DefaultDialerHelper.shouldEnforceDefaultDialer(context)) {
            if (shown) {
                dismiss();
                RemoteLogger.log(context, Const.LOG_INFO,
                        "Default-dialer gate dismissed — app is now the default phone app");
            }
            return false;
        }
        // A picker is up (grace window) OR the user is actually in Settings / the system picker —
        // keep the overlay hidden so it never covers the one screen where they can fix it. Without
        // this, slow navigation in Settings would get re-blocked mid-way and deadlock the user.
        if (SystemClock.elapsedRealtime() < sPickerGraceUntil || isSettingsOrPickerForeground()) {
            if (shown) {
                dismiss();
            }
            return true;
        }
        boolean canOverlay = Settings.canDrawOverlays(context);
        if (!canOverlay) {
            // Try to (re)grant "Display over other apps" silently — works on device-owner
            // platform/privileged builds; on ordinary builds MainActivity.enforceOverlayPermission()
            // shows the mandatory grant dialog. Rate-limited so we don't reflect every 0.7s tick.
            canOverlay = ensureOverlayPermission();
        }
        if (canOverlay) {
            if (!shown) {
                show();
            }
        } else {
            // Overlay still unavailable — fall back to the blocking Activity gate so enforcement
            // never silently disappears on a device that refuses the overlay.
            DefaultDialerGatekeeperActivity.enforce(context);
        }
        // Worktime-style hard enforcement: if any OTHER app is in the foreground (i.e. not us and
        // not Settings/the picker, already excluded above), bounce it to Home immediately so the
        // user lands back on our default screen instead of glimpsing the app list. This closes the
        // ~1-tick window after backing out of Settings, and covers the case where the overlay was
        // momentarily suppressed by an OEM.
        kickForeignAppToHome();
        return true;
    }

    private static volatile long sLastOverlayGrantAttempt = 0;

    /**
     * Ensure "Display over other apps" (SYSTEM_ALERT_WINDOW) is granted so the overlay gate can
     * render. Cross-version: pre-M it is install-granted; M+ needs the appop. On a device-owner
     * platform/privileged build we grant it silently via AppOps (SystemUtils.autoSetOverlayPermission,
     * reflection); on an ordinary build that call fails harmlessly and the mandatory dialog in
     * MainActivity handles it. Rate-limited to once per 15s to avoid reflecting on every tick.
     *
     * @return whether overlays can be drawn after the attempt.
     */
    private boolean ensureOverlayPermission() {
        if (Settings.canDrawOverlays(context)) {
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - sLastOverlayGrantAttempt < 15_000L) {
            return false; // recently tried; wait
        }
        sLastOverlayGrantAttempt = now;
        try {
            if (com.brother.pharmach.mdm.launcher.util.Utils.isDeviceOwner(context)) {
                com.brother.pharmach.mdm.launcher.util.SystemUtils
                        .autoSetOverlayPermission(context, context.getPackageName());
            }
        } catch (Exception e) {
            Log.w(TAG, "silent overlay grant failed: " + e.getMessage());
        }
        boolean granted = Settings.canDrawOverlays(context);
        RemoteLogger.log(context, granted ? Const.LOG_INFO : Const.LOG_WARN,
                "Gate overlay permission " + (granted ? "granted" : "NOT granted — using activity fallback"));
        return granted;
    }

    /** If a foreign app is foreground, send the device Home (our launcher) so the gate is shown. */
    private void kickForeignAppToHome() {
        String fg = foregroundPackage();
        if (fg == null || fg.equals(context.getPackageName())) {
            return; // already on our app/launcher — nothing to bounce
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
        } catch (Exception e) {
            Log.w(TAG, "kickForeignAppToHome failed: " + e.getMessage());
        }
    }

    @SuppressLint("InflateParams")
    private void show() {
        try {
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            root = LayoutInflater.from(context).inflate(
                    R.layout.activity_default_dialer_gatekeeper, null);
            InsetsUtils.applySystemBarPadding(root.findViewById(R.id.gatekeeper_root));

            Button setDefault = root.findViewById(R.id.btn_set_default_dialer);
            if (setDefault != null) {
                setDefault.setOnClickListener(v -> onSetDefaultTapped());
            }
            Button openSettings = root.findViewById(R.id.btn_open_default_apps_settings);
            if (openSettings != null) {
                openSettings.setOnClickListener(v -> onOpenSettingsTapped());
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    // Full-screen, consumes all touches (blocks the app behind), does not grab key
                    // focus (so it never wedges system input), and shows over the lock screen.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;

            windowManager.addView(root, lp);
            shown = true;
            RemoteLogger.log(context, Const.LOG_WARN,
                    "Default-dialer gate overlay shown — app is not the default phone app");
        } catch (Exception e) {
            Log.w(TAG, "gate show failed: " + e.getMessage());
            RemoteLogger.log(context, Const.LOG_ERROR,
                    "Default-dialer gate overlay FAILED: " + e.getMessage()
                            + " — falling back to activity");
            shown = false;
            // Overlay refused by the OEM — use the Activity gate instead.
            DefaultDialerGatekeeperActivity.enforce(context);
        }
    }

    private void dismiss() {
        if (shown && windowManager != null && root != null) {
            try {
                windowManager.removeView(root);
            } catch (Exception ignored) {
            }
        }
        shown = false;
        root = null;
    }

    private void onSetDefaultTapped() {
        // Hide the overlay so the system picker (a normal activity, drawn below app overlays) is
        // visible, grant a window during which we won't re-cover it, then present the picker via a
        // real Activity (startActivityForResult) — the only way it works on Android 14/15/16.
        sPickerGraceUntil = SystemClock.elapsedRealtime() + PICKER_GRACE_MS;
        dismiss();
        try {
            context.startActivity(com.brother.pharmach.mdm.launcher.ui.RoleRequestActivity
                    .newIntent(context));
        } catch (Exception e) {
            Log.w(TAG, "launch RoleRequestActivity failed: " + e.getMessage());
            // Couldn't launch the request activity — fall straight to manual settings.
            onOpenSettingsTapped();
        }
    }

    private void onOpenSettingsTapped() {
        sPickerGraceUntil = SystemClock.elapsedRealtime() + PICKER_GRACE_MS;
        dismiss();
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(details);
        } catch (Exception e) {
            Log.w(TAG, "open settings failed: " + e.getMessage());
        }
    }

    /**
     * True when the foreground app is Settings, an OEM settings/default-apps screen, or the system
     * role/permission picker — the places the user goes to set the default phone app. While one of
     * these is up we must NOT re-cover it with the gate. Uses UsageStats (same source as the
     * WorkTime foreground poller); if usage-access is unavailable this returns false, so the gate
     * still enforces (fail toward blocking).
     */
    private boolean isSettingsOrPickerForeground() {
        String pkg = foregroundPackage();
        if (pkg == null) {
            return false;
        }
        if (com.brother.pharmach.mdm.launcher.util.OemCompat.isSettingsFamilyPackage(pkg)) {
            return true;
        }
        switch (pkg) {
            case "com.android.settings":
            case "com.android.permissioncontroller":
            case "com.google.android.permissioncontroller":
            case "com.android.packageinstaller":
                return true;
            default:
                return false;
        }
    }

    private String foregroundPackage() {
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager)
                            context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> stats = usm.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 10_000, now);
            if (stats == null || stats.isEmpty()) {
                return null;
            }
            android.app.usage.UsageStats recent = null;
            for (android.app.usage.UsageStats s : stats) {
                if (recent == null || s.getLastTimeUsed() > recent.getLastTimeUsed()) {
                    recent = s;
                }
            }
            return recent != null ? recent.getPackageName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static {
        Log.d(Const.LOG_TAG, TAG + " loaded");
    }
}
