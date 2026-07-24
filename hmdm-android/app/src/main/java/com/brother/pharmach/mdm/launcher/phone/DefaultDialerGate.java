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
    private static final long TICK_MS = 2000L;
    // While the system role picker is on screen we must NOT cover it with our overlay.
    private static final long PICKER_GRACE_MS = 30_000L;
    private static volatile long sPickerGraceUntil = 0;

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
        // A picker is up — keep the overlay hidden so it doesn't cover the picker.
        if (SystemClock.elapsedRealtime() < sPickerGraceUntil) {
            if (shown) {
                dismiss();
            }
            return true;
        }
        if (Settings.canDrawOverlays(context)) {
            if (!shown) {
                show();
            }
        } else {
            // No overlay permission on this device — fall back to the blocking Activity.
            DefaultDialerGatekeeperActivity.enforce(context);
        }
        return true;
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
        // visible, grant a window during which we won't re-cover it, then launch the picker.
        sPickerGraceUntil = SystemClock.elapsedRealtime() + PICKER_GRACE_MS;
        dismiss();
        DefaultDialerHelper.requestDefaultDialerFromContext(context);
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

    static {
        Log.d(Const.LOG_TAG, TAG + " loaded");
    }
}
