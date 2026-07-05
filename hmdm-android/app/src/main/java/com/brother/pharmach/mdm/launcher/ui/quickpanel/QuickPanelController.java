/*
 * Brother Pharmamach MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.brother.pharmach.mdm.launcher.ui.quickpanel;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.PorterDuff;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.widget.ScrollView;
import androidx.core.graphics.ColorUtils;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.ServerConfig;
import com.brother.pharmach.mdm.launcher.pro.ProUtils;
import com.brother.pharmach.mdm.launcher.util.LegacyUtils;
import com.brother.pharmach.mdm.launcher.util.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Owns the swipe-down quick panel: Wi-Fi / Bluetooth / Torch pills plus
 * brightness and volume sliders. Values pinned by the MDM server policy are
 * shown read-only (with a "managed by administrator" toast) so the user never
 * fights the StatusControlService enforcement loop.
 *
 * The opening gesture is detected here, from MainActivity.dispatchTouchEvent —
 * not inside the view hierarchy — so a swipe down that starts anywhere along
 * the top band of the screen opens the panel, even when it begins over app
 * icons. Once the swipe commits, the in-flight gesture is stolen (children get
 * ACTION_CANCEL) and the finger drives the panel via its external-drag API.
 *
 * Instantiated by MainActivity and attached to its root layout — deliberately
 * NOT a separate window/Activity, so lock task mode treats it as launcher UI.
 * It never touches the existing status-bar/lock-task restriction code.
 */
public class QuickPanelController implements QuickPanelView.Listener {

    // Verdicts for onActivityTouch()
    public static final int TOUCH_NONE = 0;      // not ours; dispatch normally
    public static final int TOUCH_STEAL = 1;     // swipe committed: cancel children, then consume
    public static final int TOUCH_CONSUMED = 2;  // mid-drag: consume silently

    private static final int GESTURE_IDLE = 0;
    private static final int GESTURE_TRACKING = 1;
    private static final int GESTURE_DRAGGING = 2;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private QuickPanelView panelView;
    private QuickPanelEdgeOverlay edgeOverlay;
    private View blurTarget;
    private ScrollView panelScrollView;

    private TextView carrierView;
    private View pillWifi, pillBluetooth, pillTorch, torchRow;
    private ImageView pillWifiIcon, pillBluetoothIcon, pillTorchIcon;
    private TextView pillWifiLabel, pillBluetoothLabel, pillTorchLabel;
    private PillSliderView brightnessSlider;
    private PillSliderView volumeSlider;
    private ImageView autoBrightnessButton;
    private ImageView muteButton;

    private boolean receiversRegistered = false;
    private float lastNonZeroVolume = 0.5f;

    // Top-edge gesture state (fed from MainActivity.dispatchTouchEvent)
    private final int edgeCaptureHeight;
    private final int touchSlop;
    private int gestureState = GESTURE_IDLE;
    private float gestureDownX, gestureDownY;
    private VelocityTracker gestureVelocity;

    public QuickPanelController(Activity activity) {
        this.activity = activity;
        edgeCaptureHeight = activity.getResources()
                .getDimensionPixelSize(R.dimen.qp_edge_capture_height);
        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
    }

    // ------------------------------------------------------------------ attach

    /**
     * Idempotent: creates the panel and adds it as the LAST child of the given
     * root so it z-orders above the launcher content. Safe to call from
     * onResume — re-attaches if the binding/root was recreated.
     */
    public void attach(ViewGroup root) {
        if (panelView != null && panelView.getParent() == root) {
            return;
        }
        if (panelView != null && panelView.getParent() instanceof ViewGroup) {
            ((ViewGroup) panelView.getParent()).removeView(panelView);
        }
        if (panelView == null) {
            panelView = new QuickPanelView(activity);
            panelView.setListener(this);
            bindViews();
        }
        // Match the parent's LayoutParams type (MainActivity root is a RelativeLayout)
        ViewGroup.LayoutParams lp;
        if (root instanceof RelativeLayout) {
            lp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        } else if (root instanceof FrameLayout) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        root.addView(panelView, lp);
        blurTarget = root.findViewById(R.id.activity_main_content_wrapper);
        QuickTileActions.ensureTorchCallback(activity, this::postRefresh);

        // Overlay strip over the system status bar so a pull-down that starts on
        // the status info area (carrier/time/battery) also opens the panel.
        if (edgeOverlay == null) {
            edgeOverlay = new QuickPanelEdgeOverlay(activity, overlayDragTarget);
        }
        edgeOverlay.show();
        edgeOverlay.setCaptureEnabled(!isOpen());

        // Re-apply server theme every time we attach (covers config refreshes on resume).
        applyServerTheme();
    }

    /** Bridges the status-bar overlay's swipe to the panel's external-drag API. */
    private final QuickPanelEdgeOverlay.DragTarget overlayDragTarget =
            new QuickPanelEdgeOverlay.DragTarget() {
        @Override
        public boolean canStartReveal() {
            return panelView != null && !panelView.isOpen() && !panelView.isAnimating();
        }

        @Override
        public void onRevealStart() {
            if (panelView != null) {
                panelView.startExternalDrag();
            }
        }

        @Override
        public void onRevealBy(float totalDy) {
            if (panelView != null) {
                panelView.externalDragBy(totalDy);
            }
        }

        @Override
        public void onRevealEnd(float velocityY) {
            if (panelView != null) {
                panelView.externalDragEnd(velocityY);
            }
        }
    };

    public boolean isOpen() {
        return panelView != null && panelView.isOpen();
    }

    /** Close from onPause / screen-off / back press. Returns true if it was open. */
    public boolean close() {
        if (panelView != null && panelView.isOpen()) {
            panelView.close(false);
            return true;
        }
        return false;
    }

    public void onPause() {
        close();
        if (edgeOverlay != null) {
            edgeOverlay.remove();
        }
    }

    // ------------------------------------------------------------------ top-edge gesture
    // Called from MainActivity.dispatchTouchEvent for EVERY touch event, before
    // the normal view hierarchy sees it.

    public int onActivityTouch(MotionEvent ev) {
        if (panelView == null || panelView.isOpen()) {
            // Open panel handles its own touches through the view hierarchy
            gestureState = GESTURE_IDLE;
            return TOUCH_NONE;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (ev.getY() <= edgeCaptureHeight && !panelView.isAnimating()) {
                    gestureState = GESTURE_TRACKING;
                    gestureDownX = ev.getX();
                    gestureDownY = ev.getY();
                    obtainGestureVelocity();
                    gestureVelocity.addMovement(ev);
                } else {
                    gestureState = GESTURE_IDLE;
                }
                return TOUCH_NONE;

            case MotionEvent.ACTION_MOVE:
                if (gestureState == GESTURE_TRACKING) {
                    if (gestureVelocity != null) {
                        gestureVelocity.addMovement(ev);
                    }
                    float dy = ev.getY() - gestureDownY;
                    float absDx = Math.abs(ev.getX() - gestureDownX);
                    // Commit on downward intent, allowing generous diagonals — a thumb
                    // swipe from a screen edge naturally curves sideways, which is why a
                    // stricter check used to only trigger near the center.
                    if (dy >= touchSlop && dy >= absDx * 0.6f) {
                        gestureState = GESTURE_DRAGGING;
                        panelView.startExternalDrag();
                        panelView.externalDragBy(dy);
                        return TOUCH_STEAL;
                    }
                    // Hand the gesture back only when it's clearly upward, or clearly a
                    // horizontal swipe (sideways travel more than twice the downward).
                    if (dy <= -touchSlop || (absDx > touchSlop && absDx > dy * 2f)) {
                        endGesture();
                    }
                    return TOUCH_NONE;
                }
                if (gestureState == GESTURE_DRAGGING) {
                    if (gestureVelocity != null) {
                        gestureVelocity.addMovement(ev);
                    }
                    panelView.externalDragBy(ev.getY() - gestureDownY);
                    return TOUCH_CONSUMED;
                }
                return TOUCH_NONE;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gestureState == GESTURE_DRAGGING) {
                    float velocityY = 0;
                    if (gestureVelocity != null) {
                        gestureVelocity.addMovement(ev);
                        gestureVelocity.computeCurrentVelocity(1000);
                        velocityY = gestureVelocity.getYVelocity();
                    }
                    panelView.externalDragEnd(velocityY);
                    endGesture();
                    return TOUCH_CONSUMED;
                }
                endGesture();
                return TOUCH_NONE;
        }
        return TOUCH_NONE;
    }

    private void obtainGestureVelocity() {
        if (gestureVelocity == null) {
            gestureVelocity = VelocityTracker.obtain();
        } else {
            gestureVelocity.clear();
        }
    }

    private void endGesture() {
        gestureState = GESTURE_IDLE;
        if (gestureVelocity != null) {
            gestureVelocity.recycle();
            gestureVelocity = null;
        }
    }

    // ------------------------------------------------------------------ server policy helpers

    private ServerConfig config() {
        SettingsHelper settingsHelper = SettingsHelper.getInstance(activity);
        return settingsHelper != null ? settingsHelper.getConfig() : null;
    }

    /** Non-null when the server pins Wi-Fi on/off ("Any" comes through as null). */
    private Boolean pinnedWifi() {
        ServerConfig c = config();
        return c != null ? c.getWifi() : null;
    }

    private Boolean pinnedBluetooth() {
        ServerConfig c = config();
        return c != null ? c.getBluetooth() : null;
    }

    /** True when the server manages brightness (auto flag or fixed value). */
    private boolean isBrightnessManaged() {
        ServerConfig c = config();
        return c != null && c.getAutoBrightness() != null;
    }

    private boolean isVolumeLocked() {
        ServerConfig c = config();
        return c != null && Boolean.TRUE.equals(c.getLockVolume());
    }

    private void toastManaged() {
        Toast.makeText(activity, R.string.qp_managed_by_admin, Toast.LENGTH_SHORT).show();
    }

    /** Settings gear: open the system Settings app directly, no password prompt. */
    private void openDeviceSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Under lock task (kiosk) a non-allowlisted app is silently blocked from
            // launching — which is why the gear appeared to "do nothing". Temporarily
            // add the Settings package to the lock task allowlist so it can open.
            allowSettingsInLockTask(intent);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.qp_action_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Appends the resolved Settings package to the lock task allowlist (preserving
     * the launcher's own package). Device owner only; no-op otherwise. Does not
     * touch setLockTaskFeatures / setStatusBarDisabled — the status-bar lockdown
     * is unchanged.
     */
    private void allowSettingsInLockTask(Intent settingsIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                || !Utils.isDeviceOwner(activity)
                || !ProUtils.isKioskModeRunning(activity)) {
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = LegacyUtils.getAdminComponentName(activity);
            if (dpm == null || admin == null) {
                return;
            }
            ResolveInfo ri = activity.getPackageManager().resolveActivity(settingsIntent, 0);
            String settingsPkg = (ri != null && ri.activityInfo != null)
                    ? ri.activityInfo.packageName : "com.android.settings";

            List<String> packages = new ArrayList<>();
            try {
                String[] existing = dpm.getLockTaskPackages(admin);
                if (existing != null) {
                    packages.addAll(Arrays.asList(existing));
                }
            } catch (Exception ignored) {
            }
            if (!packages.contains(activity.getPackageName())) {
                packages.add(activity.getPackageName());
            }
            if (!packages.contains(settingsPkg)) {
                packages.add(settingsPkg);
            }
            dpm.setLockTaskPackages(admin, packages.toArray(new String[0]));
        } catch (Exception e) {
            // Best effort; the launch below may still work on non-locked builds
        }
    }

    // ------------------------------------------------------------------ view wiring

    private void bindViews() {
        carrierView = panelView.findViewById(R.id.qp_carrier);
        panelScrollView = panelView.findViewById(R.id.qp_panel);
        pillWifi = panelView.findViewById(R.id.qp_pill_wifi);
        pillBluetooth = panelView.findViewById(R.id.qp_pill_bluetooth);
        pillTorch = panelView.findViewById(R.id.qp_pill_torch);
        torchRow = panelView.findViewById(R.id.qp_torch_row);
        pillWifiIcon = panelView.findViewById(R.id.qp_pill_wifi_icon);
        pillBluetoothIcon = panelView.findViewById(R.id.qp_pill_bluetooth_icon);
        pillTorchIcon = panelView.findViewById(R.id.qp_pill_torch_icon);
        pillWifiLabel = panelView.findViewById(R.id.qp_pill_wifi_label);
        pillBluetoothLabel = panelView.findViewById(R.id.qp_pill_bluetooth_label);
        pillTorchLabel = panelView.findViewById(R.id.qp_pill_torch_label);
        brightnessSlider = panelView.findViewById(R.id.qp_brightness_slider);
        volumeSlider = panelView.findViewById(R.id.qp_volume_slider);
        autoBrightnessButton = panelView.findViewById(R.id.qp_auto_brightness);
        muteButton = panelView.findViewById(R.id.qp_mute);

        panelView.findViewById(R.id.qp_settings_button).setOnClickListener(v -> {
            panelView.close(true);
            openDeviceSettings();
        });

        pillWifi.setOnClickListener(v -> {
            if (pinnedWifi() != null) {
                toastManaged();
                return;
            }
            boolean target = !QuickTileActions.isWifiEnabled(activity);
            stylePill(pillWifi, pillWifiIcon, pillWifiLabel, target); // optimistic
            handleResult(QuickTileActions.setWifiEnabled(activity, target));
            scheduleRefresh();
        });

        pillBluetooth.setOnClickListener(v -> {
            if (pinnedBluetooth() != null) {
                toastManaged();
                return;
            }
            boolean target = !QuickTileActions.isBluetoothEnabled(activity);
            stylePill(pillBluetooth, pillBluetoothIcon, pillBluetoothLabel, target); // optimistic
            handleResult(QuickTileActions.setBluetoothEnabled(activity, target));
            scheduleRefresh();
        });

        pillTorch.setOnClickListener(v -> {
            boolean target = !QuickTileActions.isTorchOn(activity);
            stylePill(pillTorch, pillTorchIcon, pillTorchLabel, target); // optimistic
            handleResult(QuickTileActions.setTorchOn(activity, target));
            scheduleRefresh();
        });

        brightnessSlider.setIcon(R.drawable.ic_qp_sun);
        brightnessSlider.setOnValueChangeListener((fraction, fromUser, dragging) -> {
            if (!fromUser) {
                return;
            }
            if (dragging) {
                // Live preview on the window only; the system setting is written once on release
                QuickTileActions.setWindowBrightness(activity.getWindow(), fraction);
            } else {
                handleResult(QuickTileActions.setBrightnessFraction(
                        activity, activity.getWindow(), fraction));
                refreshAuxButtons();
            }
        });

        volumeSlider.setIcon(R.drawable.ic_qp_music);
        volumeSlider.setOnValueChangeListener((fraction, fromUser, dragging) -> {
            if (fromUser) {
                if (fraction > 0f) {
                    lastNonZeroVolume = fraction;
                }
                handleResultQuiet(QuickTileActions.setVolumeFraction(activity, fraction), !dragging);
                if (!dragging) {
                    refreshAuxButtons();
                }
            }
        });

        autoBrightnessButton.setOnClickListener(v -> {
            if (isBrightnessManaged()) {
                toastManaged();
                return;
            }
            boolean target = !QuickTileActions.isAutoBrightness(activity);
            handleResult(QuickTileActions.setAutoBrightness(activity, target));
            refreshAuxButtons();
        });

        muteButton.setOnClickListener(v -> {
            if (isVolumeLocked()) {
                toastManaged();
                return;
            }
            float current = QuickTileActions.getVolumeFraction(activity);
            if (current > 0f) {
                lastNonZeroVolume = current;
                handleResult(QuickTileActions.setVolumeFraction(activity, 0f));
            } else {
                handleResult(QuickTileActions.setVolumeFraction(activity, lastNonZeroVolume));
            }
            refreshSliders();
            refreshAuxButtons();
        });

        applyServerTheme();
    }

    /**
     * Reads the server-configured background color and applies a tinted,
     * semi-opaque version of it as the quick panel background.
     * Falls back to the static XML drawable if no color is configured.
     */
    private void applyServerTheme() {
        if (panelScrollView == null) {
            return;
        }
        ServerConfig c = config();
        if (c == null || c.getBackgroundColor() == null || c.getBackgroundColor().isEmpty()) {
            // No server color — leave the XML drawable in place.
            return;
        }
        try {
            int serverColor = Color.parseColor(c.getBackgroundColor());
            // Blend the server color heavily toward black (90% black, 10% server hue)
            // so the panel always reads as a dark surface regardless of server choice,
            // then apply ~91% opacity (0xE6 alpha) to match the original style.
            int panelBg = ColorUtils.blendARGB(serverColor, Color.BLACK, 0.80f);
            panelBg = ColorUtils.setAlphaComponent(panelBg, 0xE6);

            float cornerPx = activity.getResources()
                    .getDimension(R.dimen.qp_panel_corner_radius);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(panelBg);
            bg.setCornerRadii(new float[]{0, 0, 0, 0,
                    cornerPx, cornerPx, cornerPx, cornerPx});
            panelScrollView.setBackground(bg);
        } catch (Exception ignored) {
            // Bad color string from server — leave the static drawable.
        }
    }

    // ------------------------------------------------------------------ state -> UI

    private void refreshAll() {
        refreshStatusRow();
        refreshPills();
        refreshSliders();
        refreshAuxButtons();
        applyPolicyLocks();
    }

    /** Reflects server pinning: read-only styling for managed controls. */
    private void applyPolicyLocks() {
        pillWifi.setAlpha(pinnedWifi() != null ? 0.55f : 1f);
        pillBluetooth.setAlpha(pinnedBluetooth() != null ? 0.55f : 1f);

        boolean brightnessManaged = isBrightnessManaged();
        brightnessSlider.setUserInteractionEnabled(!brightnessManaged, this::toastManaged);
        autoBrightnessButton.setAlpha(brightnessManaged ? 0.55f : 1f);

        boolean volumeLocked = isVolumeLocked();
        volumeSlider.setUserInteractionEnabled(!volumeLocked, this::toastManaged);
        muteButton.setAlpha(volumeLocked ? 0.55f : 1f);

        torchRow.setVisibility(QuickTileActions.isTorchAvailable(activity) ?
                View.VISIBLE : View.GONE);
    }

    private void refreshPills() {
        // A pinned radio displays the server-enforced state, not a transient local one
        Boolean wifiPinned = pinnedWifi();
        stylePill(pillWifi, pillWifiIcon, pillWifiLabel,
                wifiPinned != null ? wifiPinned : QuickTileActions.isWifiEnabled(activity));
        Boolean btPinned = pinnedBluetooth();
        stylePill(pillBluetooth, pillBluetoothIcon, pillBluetoothLabel,
                btPinned != null ? btPinned : QuickTileActions.isBluetoothEnabled(activity));
        stylePill(pillTorch, pillTorchIcon, pillTorchLabel, QuickTileActions.isTorchOn(activity));
    }

    private void stylePill(View pill, ImageView icon, TextView label, boolean active) {
        pill.setBackgroundResource(active ?
                R.drawable.bg_qp_pill_active : R.drawable.bg_qp_pill_inactive);
        int color = ContextCompat.getColor(activity,
                active ? R.color.qpIconOnActive : R.color.qpTextPrimary);
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        label.setTextColor(color);
    }

    private void refreshSliders() {
        brightnessSlider.setFraction(QuickTileActions.getBrightnessFraction(activity));
        volumeSlider.setFraction(QuickTileActions.getVolumeFraction(activity));
    }

    private void refreshAuxButtons() {
        boolean auto = QuickTileActions.isAutoBrightness(activity);
        autoBrightnessButton.setBackgroundResource(auto ?
                R.drawable.bg_qp_tile_active : R.drawable.bg_qp_tile_inactive);
        autoBrightnessButton.setColorFilter(ContextCompat.getColor(activity,
                auto ? R.color.qpIconOnActive : R.color.qpTextPrimary), PorterDuff.Mode.SRC_IN);

        boolean audible = QuickTileActions.getVolumeFraction(activity) > 0f;
        muteButton.setImageResource(audible ? R.drawable.ic_qp_volume : R.drawable.ic_qp_mute);
        muteButton.setBackgroundResource(audible ?
                R.drawable.bg_qp_tile_active : R.drawable.bg_qp_tile_inactive);
        muteButton.setColorFilter(ContextCompat.getColor(activity,
                audible ? R.color.qpIconOnActive : R.color.qpTextPrimary), PorterDuff.Mode.SRC_IN);
    }

    private void refreshStatusRow() {
        String carrier = "";
        try {
            TelephonyManager tm = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                carrier = tm.getSimOperatorName();
                if (carrier == null || carrier.trim().isEmpty()) {
                    carrier = tm.getNetworkOperatorName();
                }
            }
        } catch (Exception ignored) {
        }
        carrierView.setText(carrier != null ? carrier : "");
    }

    // ------------------------------------------------------------------ result handling

    private void handleResult(SystemActionResult result) {
        handleResultQuiet(result, true);
    }

    private void handleResultQuiet(SystemActionResult result, boolean showToast) {
        if (result == SystemActionResult.SUCCESS || !showToast) {
            return;
        }
        int messageRes;
        switch (result) {
            case PERMISSION_DENIED:
                messageRes = R.string.qp_action_permission_denied;
                break;
            case UNSUPPORTED_ON_OS_VERSION:
                messageRes = R.string.qp_action_unsupported;
                break;
            default:
                messageRes = R.string.qp_action_failed;
                break;
        }
        Toast.makeText(activity, messageRes, Toast.LENGTH_SHORT).show();
    }

    private void postRefresh() {
        handler.post(() -> {
            if (isOpen()) {
                refreshAll();
            }
        });
    }

    /** Radios settle asynchronously; refresh shortly after a toggle, then again. */
    private void scheduleRefresh() {
        handler.postDelayed(this::postRefresh, 400);
        handler.postDelayed(this::postRefresh, 1500);
    }

    // ------------------------------------------------------------------ open/close lifecycle

    @Override
    public void onPanelOpened() {
        refreshAll();
        registerReceivers();
        // Panel is up: make the status-bar strip pass-through so it never covers
        // the panel header (settings gear); the panel handles its own touches.
        if (edgeOverlay != null) {
            edgeOverlay.setCaptureEnabled(false);
        }
    }

    @Override
    public void onPanelClosed() {
        unregisterReceivers();
        // Re-arm the status-bar strip to catch the next pull-down.
        if (edgeOverlay != null) {
            edgeOverlay.setCaptureEnabled(true);
        }
        // Drop the temporary window brightness override; the persisted system value rules
        try {
            android.view.WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            activity.getWindow().setAttributes(lp);
        } catch (Exception ignored) {
        }
        applyBlur(0f);
    }

    @Override
    public void onOpenFractionChanged(float fraction) {
        applyBlur(fraction);
    }

    private void applyBlur(float fraction) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || blurTarget == null) {
            return; // pre-Android-12: the dim scrim alone is the fallback
        }
        try {
            if (fraction <= 0.01f) {
                blurTarget.setRenderEffect(null);
            } else {
                float radius = 4f + 20f * fraction;
                blurTarget.setRenderEffect(
                        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ receivers/observers

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                close();
                return;
            }
            postRefresh();
        }
    };

    private final ContentObserver settingsObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            postRefresh();
        }
    };

    private void registerReceivers() {
        if (receiversRegistered) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                activity.registerReceiver(stateReceiver, filter);
            }
            activity.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false, settingsObserver);
            activity.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, settingsObserver);
            receiversRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterReceivers() {
        if (!receiversRegistered) {
            return;
        }
        try {
            activity.unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        try {
            activity.getContentResolver().unregisterContentObserver(settingsObserver);
        } catch (Exception ignored) {
        }
        receiversRegistered = false;
    }

    /** Full teardown, e.g. from onDestroy. */
    public void destroy() {
        close();
        endGesture();
        unregisterReceivers();
        handler.removeCallbacksAndMessages(null);
        if (edgeOverlay != null) {
            edgeOverlay.remove();
            edgeOverlay = null;
        }
        if (panelView != null && panelView.getParent() instanceof ViewGroup) {
            ((ViewGroup) panelView.getParent()).removeView(panelView);
        }
        panelView = null;
    }
}
