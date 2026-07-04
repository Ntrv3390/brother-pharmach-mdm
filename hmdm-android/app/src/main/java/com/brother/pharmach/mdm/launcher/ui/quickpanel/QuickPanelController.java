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
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.PorterDuff;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.RelativeLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.ServerConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the swipe-down quick panel: builds the (policy-driven) tile registry,
 * keeps tile/pill/slider state in sync with the system while the panel is open,
 * and routes the settings gear to the launcher's admin-password flow.
 *
 * Instantiated by MainActivity and attached to its root layout — deliberately
 * NOT a separate window/Activity, so lock task mode treats it as launcher UI.
 * It never touches the existing status-bar/lock-task restriction code.
 */
public class QuickPanelController implements QuickPanelView.Listener {

    /** Host callbacks implemented by MainActivity. */
    public interface Host {
        /** Settings gear tapped — open the admin-password-gated settings flow. */
        void onQuickPanelSettingsClicked();
    }

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private QuickPanelView panelView;
    private View blurTarget;

    private TextView carrierView;
    private TextView networkTypeView;
    private TextView batteryView;
    private View pillWifi, pillBluetooth;
    private ImageView pillWifiIcon, pillBluetoothIcon;
    private TextView pillWifiLabel, pillBluetoothLabel;
    private PillSliderView brightnessSlider;
    private PillSliderView volumeSlider;
    private ImageView autoBrightnessButton;
    private ImageView muteButton;

    private final List<QuickTile> tiles = new ArrayList<>();
    private final List<View> tileViews = new ArrayList<>();

    private boolean receiversRegistered = false;
    private float lastNonZeroVolume = 0.5f;

    public QuickPanelController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
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
            buildTiles();
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
    }

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

    // ------------------------------------------------------------------ view wiring

    private void bindViews() {
        carrierView = panelView.findViewById(R.id.qp_carrier);
        networkTypeView = panelView.findViewById(R.id.qp_network_type);
        batteryView = panelView.findViewById(R.id.qp_battery);
        pillWifi = panelView.findViewById(R.id.qp_pill_wifi);
        pillBluetooth = panelView.findViewById(R.id.qp_pill_bluetooth);
        pillWifiIcon = panelView.findViewById(R.id.qp_pill_wifi_icon);
        pillBluetoothIcon = panelView.findViewById(R.id.qp_pill_bluetooth_icon);
        pillWifiLabel = panelView.findViewById(R.id.qp_pill_wifi_label);
        pillBluetoothLabel = panelView.findViewById(R.id.qp_pill_bluetooth_label);
        brightnessSlider = panelView.findViewById(R.id.qp_brightness_slider);
        volumeSlider = panelView.findViewById(R.id.qp_volume_slider);
        autoBrightnessButton = panelView.findViewById(R.id.qp_auto_brightness);
        muteButton = panelView.findViewById(R.id.qp_mute);

        panelView.findViewById(R.id.qp_settings_button).setOnClickListener(v -> {
            panelView.close(true);
            host.onQuickPanelSettingsClicked();
        });
        // Edit and power are visual stubs for reference-design parity (alpha-dimmed in XML)

        pillWifi.setOnClickListener(v -> {
            if (isPolicyPinned(pinnedWifi())) {
                toast(SystemActionResult.PERMISSION_DENIED);
                return;
            }
            boolean target = !QuickTileActions.isWifiEnabled(activity);
            stylePill(pillWifi, pillWifiIcon, pillWifiLabel, target); // optimistic
            handleResult(QuickTileActions.setWifiEnabled(activity, target));
            scheduleRefresh();
        });

        pillBluetooth.setOnClickListener(v -> {
            if (isPolicyPinned(pinnedBluetooth())) {
                toast(SystemActionResult.PERMISSION_DENIED);
                return;
            }
            boolean target = !QuickTileActions.isBluetoothEnabled(activity);
            stylePill(pillBluetooth, pillBluetoothIcon, pillBluetoothLabel, target); // optimistic
            handleResult(QuickTileActions.setBluetoothEnabled(activity, target));
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
            boolean target = !QuickTileActions.isAutoBrightness(activity);
            handleResult(QuickTileActions.setAutoBrightness(activity, target));
            refreshAuxButtons();
        });

        muteButton.setOnClickListener(v -> {
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
    }

    // ------------------------------------------------------------------ tile registry

    private ServerConfig config() {
        SettingsHelper settingsHelper = SettingsHelper.getInstance(activity);
        return settingsHelper != null ? settingsHelper.getConfig() : null;
    }

    private Boolean pinnedWifi() {
        ServerConfig c = config();
        return c != null ? c.getWifi() : null;
    }

    private Boolean pinnedBluetooth() {
        ServerConfig c = config();
        return c != null ? c.getBluetooth() : null;
    }

    private static boolean isPolicyPinned(Boolean policyValue) {
        return policyValue != null;
    }

    /**
     * Registry of grid tiles. A tile whose radio is pinned by the MDM server
     * policy hides itself, so the user never fights StatusControlService.
     */
    private void buildTiles() {
        tiles.clear();

        tiles.add(new QuickTile("rotation", R.drawable.ic_qp_portrait, R.string.qp_portrait) {
            @Override
            public boolean isActive(Context context) {
                return QuickTileActions.isRotationLocked(context);
            }

            @Override
            public SystemActionResult toggle(Context context) {
                return QuickTileActions.setRotationLocked(context, !isActive(context));
            }
        });

        tiles.add(new QuickTile("flight", R.drawable.ic_qp_flight, R.string.qp_flight_mode) {
            @Override
            public boolean isActive(Context context) {
                return QuickTileActions.isAirplaneModeOn(context);
            }

            @Override
            public SystemActionResult toggle(Context context) {
                return QuickTileActions.setAirplaneModeOn(context, !isActive(context));
            }
        });

        tiles.add(new QuickTile("torch", R.drawable.ic_qp_torch, R.string.qp_torch) {
            @Override
            public boolean isVisible(Context context) {
                return QuickTileActions.isTorchAvailable(context);
            }

            @Override
            public boolean isActive(Context context) {
                return QuickTileActions.isTorchOn(context);
            }

            @Override
            public SystemActionResult toggle(Context context) {
                return QuickTileActions.setTorchOn(context, !isActive(context));
            }
        });

        tiles.add(new QuickTile("mobile_data", R.drawable.ic_qp_data, R.string.qp_mobile_data) {
            @Override
            public boolean isVisible(Context context) {
                ServerConfig c = config();
                return c == null || c.getMobileData() == null;
            }

            @Override
            public boolean isActive(Context context) {
                return QuickTileActions.isMobileDataEnabled(context);
            }

            @Override
            public SystemActionResult toggle(Context context) {
                return QuickTileActions.setMobileDataEnabled(context, !isActive(context));
            }
        });

        tiles.add(new QuickTile("hotspot", R.drawable.ic_qp_hotspot, R.string.qp_hotspot) {
            @Override
            public boolean isActive(Context context) {
                return QuickTileActions.isHotspotEnabled(context);
            }

            @Override
            public SystemActionResult toggle(Context context) {
                return QuickTileActions.setHotspotEnabled(context, !isActive(context));
            }
        });

        tiles.add(new QuickTile("location", R.drawable.ic_qp_location, R.string.qp_location) {
            @Override
            public boolean isVisible(Context context) {
                ServerConfig c = config();
                return c == null || c.getGps() == null;
            }

            @Override
            public boolean isActive(Context context) {
                return QuickTileActions.isLocationEnabled(context);
            }

            @Override
            public SystemActionResult toggle(Context context) {
                return QuickTileActions.setLocationEnabled(context, !isActive(context));
            }
        });

        populateGrid();
    }

    private void populateGrid() {
        GridLayout grid = panelView.findViewById(R.id.qp_tile_grid);
        grid.removeAllViews();
        tileViews.clear();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (final QuickTile tile : tiles) {
            if (!tile.isVisible(activity)) {
                tileViews.add(null);
                continue;
            }
            View item = inflater.inflate(R.layout.item_quick_tile, grid, false);
            ((ImageView) item.findViewById(R.id.qp_tile_icon)).setImageResource(tile.iconRes);
            ((TextView) item.findViewById(R.id.qp_tile_label)).setText(tile.labelRes);
            item.setOnClickListener(v -> {
                handleResult(tile.toggle(activity));
                styleTile(item, tile.isActive(activity)); // optimistic
                scheduleRefresh();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            grid.addView(item, lp);
            tileViews.add(item);
        }
    }

    // ------------------------------------------------------------------ state -> UI

    private void refreshAll() {
        refreshStatusRow();
        refreshPills();
        refreshTiles();
        refreshSliders();
        refreshAuxButtons();
    }

    private void refreshTiles() {
        for (int i = 0; i < tiles.size() && i < tileViews.size(); i++) {
            View item = tileViews.get(i);
            if (item != null) {
                styleTile(item, tiles.get(i).isActive(activity));
            }
        }
    }

    private void styleTile(View item, boolean active) {
        View circle = item.findViewById(R.id.qp_tile_circle);
        ImageView icon = item.findViewById(R.id.qp_tile_icon);
        circle.setBackgroundResource(active ?
                R.drawable.bg_qp_tile_active : R.drawable.bg_qp_tile_inactive);
        icon.setColorFilter(ContextCompat.getColor(activity,
                active ? R.color.qpIconOnActive : R.color.qpTextPrimary), PorterDuff.Mode.SRC_IN);
    }

    private void refreshPills() {
        stylePill(pillWifi, pillWifiIcon, pillWifiLabel, QuickTileActions.isWifiEnabled(activity));
        stylePill(pillBluetooth, pillBluetoothIcon, pillBluetoothLabel,
                QuickTileActions.isBluetoothEnabled(activity));
        pillWifi.setAlpha(isPolicyPinned(pinnedWifi()) ? 0.5f : 1f);
        pillBluetooth.setAlpha(isPolicyPinned(pinnedBluetooth()) ? 0.5f : 1f);
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
        String networkType = "";
        try {
            TelephonyManager tm = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                carrier = tm.getSimOperatorName();
                if (carrier == null || carrier.trim().isEmpty()) {
                    carrier = tm.getNetworkOperatorName();
                }
                networkType = networkTypeLabel(tm);
            }
        } catch (Exception ignored) {
        }
        carrierView.setText(carrier != null ? carrier : "");
        networkTypeView.setText(networkType);

        try {
            Intent battery = activity.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    batteryView.setText(String.valueOf(level * 100 / scale));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String networkTypeLabel(TelephonyManager tm) {
        try {
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                    tm.getDataNetworkType() : tm.getNetworkType();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == TelephonyManager.NETWORK_TYPE_NR) {
                return "5G";
            }
            switch (type) {
                case TelephonyManager.NETWORK_TYPE_LTE:
                    return "LTE";
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_UMTS:
                    return "3G";
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GPRS:
                    return "2G";
                default:
                    return "";
            }
        } catch (Exception e) {
            // READ_PHONE_STATE not granted yet
            return "";
        }
    }

    // ------------------------------------------------------------------ result handling

    private void handleResult(SystemActionResult result) {
        handleResultQuiet(result, true);
    }

    private void handleResultQuiet(SystemActionResult result, boolean showToast) {
        if (result == SystemActionResult.SUCCESS || !showToast) {
            return;
        }
        toast(result);
    }

    private void toast(SystemActionResult result) {
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
    }

    @Override
    public void onPanelClosed() {
        unregisterReceivers();
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
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
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
            activity.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
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
        unregisterReceivers();
        handler.removeCallbacksAndMessages(null);
        if (panelView != null && panelView.getParent() instanceof ViewGroup) {
            ((ViewGroup) panelView.getParent()).removeView(panelView);
        }
        panelView = null;
    }
}
