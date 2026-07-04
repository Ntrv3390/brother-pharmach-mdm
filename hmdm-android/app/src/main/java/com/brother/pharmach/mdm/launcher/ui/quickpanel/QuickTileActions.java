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

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.util.LegacyUtils;
import com.brother.pharmach.mdm.launcher.util.Utils;

import java.lang.reflect.Method;

/**
 * System toggles behind the quick panel tiles and sliders.
 *
 * Every method is a best-effort cascade over the APIs available on Android 6..16
 * (API 23..36): the modern/public API first, then the Device Owner DPM path, then
 * legacy reflection where it still works, and finally a SystemActionResult that
 * tells the UI why nothing could be done. No method throws.
 */
public class QuickTileActions {

    // ------------------------------------------------------------------ helpers

    private static DevicePolicyManager dpm(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private static ComponentName admin(Context context) {
        return LegacyUtils.getAdminComponentName(context);
    }

    /**
     * Runtime permission check with a silent Device Owner self-grant fallback
     * (same mechanism as Utils.autoGrantRequestedPermissions, but for one permission).
     */
    private static boolean ensurePermission(Context context, String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (Utils.isDeviceOwner(context)) {
            try {
                dpm(context).setPermissionGrantState(admin(context), context.getPackageName(),
                        permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "QuickPanel: failed to self-grant " + permission + ": " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Writes a Settings.System key, preferring the plain WRITE_SETTINGS path and
     * falling back to the Device Owner setSystemSetting API (Android 9+).
     */
    private static SystemActionResult putSystemSetting(Context context, String key, int value) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
                if (Settings.System.putInt(context.getContentResolver(), key, value)) {
                    return SystemActionResult.SUCCESS;
                }
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "QuickPanel: Settings.System.putInt(" + key + ") failed: " + e.getMessage());
        }
        if (Utils.isDeviceOwner(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dpm(context).setSystemSetting(admin(context), key, String.valueOf(value));
                return SystemActionResult.SUCCESS;
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "QuickPanel: setSystemSetting(" + key + ") failed: " + e.getMessage());
            }
        }
        return SystemActionResult.PERMISSION_DENIED;
    }

    // ------------------------------------------------------------------ Wi-Fi

    public static boolean isWifiEnabled(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return wifiManager != null && wifiManager.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * setWifiEnabled() keeps working on Android 10+ for Device Owner and system
     * apps (the deprecation only no-ops it for regular apps), which is exactly the
     * environment this launcher runs in. Same call StatusControlService relies on.
     */
    public static SystemActionResult setWifiEnabled(Context context, boolean enable) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.setWifiEnabled(enable)) {
                return SystemActionResult.SUCCESS;
            }
        } catch (Exception e) {
            // MediaTek devices may require com.mediatek.permission.CTA_ENABLE_WIFI (see StatusControlService)
            Log.w(Const.LOG_TAG, "QuickPanel: setWifiEnabled failed: " + e.getMessage());
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                SystemActionResult.PERMISSION_DENIED : SystemActionResult.FAILURE;
    }

    // ------------------------------------------------------------------ Bluetooth

    public static boolean isBluetoothEnabled(Context context) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public static SystemActionResult setBluetoothEnabled(Context context, boolean enable) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return SystemActionResult.FAILURE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !ensurePermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            return SystemActionResult.PERMISSION_DENIED;
        }
        try {
            // Deprecated since Android 13 but still honored for Device Owner apps
            boolean invoked = enable ? adapter.enable() : adapter.disable();
            return invoked ? SystemActionResult.SUCCESS : SystemActionResult.PERMISSION_DENIED;
        } catch (SecurityException e) {
            return SystemActionResult.PERMISSION_DENIED;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "QuickPanel: setBluetoothEnabled failed: " + e.getMessage());
            return SystemActionResult.FAILURE;
        }
    }

    // ------------------------------------------------------------------ Rotation lock

    /** True when auto-rotation is OFF, i.e. the screen is locked to portrait. */
    public static boolean isRotationLocked(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static SystemActionResult setRotationLocked(Context context, boolean locked) {
        return putSystemSetting(context, Settings.System.ACCELEROMETER_ROTATION, locked ? 0 : 1);
    }

    // ------------------------------------------------------------------ Flight mode

    public static boolean isAirplaneModeOn(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * AIRPLANE_MODE_ON was removed from the Device Owner setGlobalSetting()
     * allow-list in Android 7 (API 24), and no replacement API exists up to
     * Android 16 — DPC apps simply cannot flip airplane mode any more. We support
     * it on 6.x and report UNSUPPORTED_ON_OS_VERSION above that so the UI shows
     * an explanatory toast instead of a dead tile.
     */
    public static SystemActionResult setAirplaneModeOn(Context context, boolean enable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return SystemActionResult.UNSUPPORTED_ON_OS_VERSION;
        }
        if (!Utils.isDeviceOwner(context)) {
            return SystemActionResult.PERMISSION_DENIED;
        }
        try {
            dpm(context).setGlobalSetting(admin(context),
                    Settings.Global.AIRPLANE_MODE_ON, enable ? "1" : "0");
            try {
                Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                intent.putExtra("state", enable);
                context.sendBroadcast(intent);
            } catch (Exception e) {
                // Protected broadcast on most builds; the setting itself is applied anyway
            }
            return SystemActionResult.SUCCESS;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "QuickPanel: airplane mode toggle failed: " + e.getMessage());
            return SystemActionResult.FAILURE;
        }
    }

    // ------------------------------------------------------------------ Torch

    private static String torchCameraId;
    private static boolean torchOn;
    private static boolean torchCallbackRegistered;

    /**
     * There is no public torch state getter; track it via TorchCallback (API 23+)
     * so the tile also reflects torch changes made outside the panel.
     */
    public static void ensureTorchCallback(Context context, final Runnable onChanged) {
        if (torchCallbackRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            CameraManager cm = (CameraManager) context.getApplicationContext()
                    .getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                return;
            }
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(findTorchCameraId(context))) {
                        torchOn = enabled;
                        if (onChanged != null) {
                            onChanged.run();
                        }
                    }
                }
            }, null);
            torchCallbackRegistered = true;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "QuickPanel: torch callback registration failed: " + e.getMessage());
        }
    }

    private static String findTorchCameraId(Context context) {
        if (torchCameraId != null) {
            return torchCameraId;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        try {
            CameraManager cm = (CameraManager) context.getApplicationContext()
                    .getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean hasFlash = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    torchCameraId = id;
                    return id;
                }
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "QuickPanel: no torch camera found: " + e.getMessage());
        }
        return null;
    }

    public static boolean isTorchOn(Context context) {
        return torchOn;
    }

    public static boolean isTorchAvailable(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && findTorchCameraId(context) != null;
    }

    public static SystemActionResult setTorchOn(Context context, boolean enable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return SystemActionResult.UNSUPPORTED_ON_OS_VERSION;
        }
        String cameraId = findTorchCameraId(context);
        if (cameraId == null) {
            return SystemActionResult.FAILURE;
        }
        try {
            CameraManager cm = (CameraManager) context.getApplicationContext()
                    .getSystemService(Context.CAMERA_SERVICE);
            cm.setTorchMode(cameraId, enable);
            torchOn = enable;
            return SystemActionResult.SUCCESS;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "QuickPanel: setTorchMode failed: " + e.getMessage());
            return SystemActionResult.FAILURE;
        }
    }

    // ------------------------------------------------------------------ Mobile data

    public static boolean isMobileDataEnabled(Context context) {
        return !Utils.isSimAbsent(context) && Utils.isMobileDataEnabled(context);
    }

    public static SystemActionResult setMobileDataEnabled(Context context, boolean enable) {
        if (Utils.isSimAbsent(context)) {
            return SystemActionResult.FAILURE;
        }
        if (!Utils.setMobileDataEnabled(context, enable)) {
            return SystemActionResult.UNSUPPORTED_ON_OS_VERSION;
        }
        // An accepted call can still be a no-op (see Utils.setMobileDataEnabled javadoc)
        return Utils.isMobileDataEnabled(context) == enable ?
                SystemActionResult.SUCCESS : SystemActionResult.UNSUPPORTED_ON_OS_VERSION;
    }

    // ------------------------------------------------------------------ Mobile hotspot

    public static boolean isHotspotEnabled(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            Method method = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(wifiManager);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * There is no public/DO hotspot toggle. Reflection on the hidden
     * ConnectivityManager.startTethering / WifiManager.setWifiApEnabled entry
     * points still works on many OEM builds up to Android 9-10 (same pattern as
     * the OemCompat GPS quirks); newer builds report UNSUPPORTED so the UI can
     * explain instead of pretending.
     */
    public static SystemActionResult setHotspotEnabled(Context context, boolean enable) {
        Context app = context.getApplicationContext();
        WifiManager wifiManager = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            return SystemActionResult.FAILURE;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (enable) {
                    Class<?> callbackClass = Class.forName("android.net.ConnectivityManager$OnStartTetheringCallback");
                    Method start = ConnectivityManager.class.getDeclaredMethod("startTethering",
                            int.class, boolean.class, callbackClass);
                    start.setAccessible(true);
                    // Callback may be rejected as null on some builds — caught below
                    start.invoke(cm, 0 /* TETHERING_WIFI */, false, null);
                } else {
                    Method stop = ConnectivityManager.class.getDeclaredMethod("stopTethering", int.class);
                    stop.setAccessible(true);
                    stop.invoke(cm, 0 /* TETHERING_WIFI */);
                }
                return SystemActionResult.SUCCESS;
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "QuickPanel: startTethering reflection failed: " + e.getMessage());
            }
        } else {
            try {
                if (enable) {
                    // Wi-Fi must be off before the legacy AP can start
                    wifiManager.setWifiEnabled(false);
                }
                Method method = wifiManager.getClass()
                        .getDeclaredMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration.class, boolean.class);
                method.setAccessible(true);
                if ((Boolean) method.invoke(wifiManager, null, enable)) {
                    return SystemActionResult.SUCCESS;
                }
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "QuickPanel: setWifiApEnabled reflection failed: " + e.getMessage());
            }
        }
        return SystemActionResult.UNSUPPORTED_ON_OS_VERSION;
    }

    // ------------------------------------------------------------------ Location

    public static boolean isLocationEnabled(Context context) {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return lm.isLocationEnabled();
            }
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    /** Same DPM cascade StatusControlService uses to force GPS on. */
    public static SystemActionResult setLocationEnabled(Context context, boolean enable) {
        if (!Utils.isDeviceOwner(context)) {
            return SystemActionResult.PERMISSION_DENIED;
        }
        try {
            DevicePolicyManager dpm = dpm(context);
            ComponentName admin = admin(context);
            if (dpm == null || admin == null) {
                return SystemActionResult.FAILURE;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setLocationEnabled(admin, enable);
            } else {
                dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE,
                        String.valueOf(enable ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                                : Settings.Secure.LOCATION_MODE_OFF));
            }
            return SystemActionResult.SUCCESS;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "QuickPanel: setLocationEnabled failed: " + e.getMessage());
            return SystemActionResult.FAILURE;
        }
    }

    // ------------------------------------------------------------------ Brightness

    /** Current brightness as 0..1 fraction of the 0..255 settings range. */
    public static float getBrightnessFraction(Context context) {
        try {
            int value = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            return Math.max(0f, Math.min(1f, value / 255f));
        } catch (Exception e) {
            return 0.5f;
        }
    }

    public static boolean isAutoBrightness(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            return false;
        }
    }

    public static SystemActionResult setAutoBrightness(Context context, boolean auto) {
        return putSystemSetting(context, Settings.System.SCREEN_BRIGHTNESS_MODE,
                auto ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
    }

    /**
     * Zero-latency preview while scrubbing: only touches the activity window,
     * not the (comparatively expensive) system setting.
     */
    public static void setWindowBrightness(Window window, float fraction) {
        if (window == null) {
            return;
        }
        try {
            WindowManager.LayoutParams lp = window.getAttributes();
            // Window brightness 0.0 turns the backlight fully off; keep a readable floor
            lp.screenBrightness = Math.max(0.01f, Math.min(1f, fraction));
            window.setAttributes(lp);
        } catch (Exception ignored) {
        }
    }

    /**
     * Persists system brightness and mirrors it on the activity window for
     * zero-latency feedback while the user is scrubbing the slider.
     */
    public static SystemActionResult setBrightnessFraction(Context context, Window window, float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        setWindowBrightness(window, fraction);
        // Manual mode, otherwise auto-brightness immediately overrides the value
        putSystemSetting(context, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        int value = Math.max(1, Math.round(fraction * 255f));
        return putSystemSetting(context, Settings.System.SCREEN_BRIGHTNESS, value);
    }

    // ------------------------------------------------------------------ Volume

    public static float getVolumeFraction(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return max > 0 ? (float) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max : 0f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    public static SystemActionResult setVolumeFraction(Context context, float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(fraction * max), 0);
            return SystemActionResult.SUCCESS;
        } catch (SecurityException e) {
            // DISALLOW_ADJUST_VOLUME set by the volume lock policy
            return SystemActionResult.PERMISSION_DENIED;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "QuickPanel: setStreamVolume failed: " + e.getMessage());
            return SystemActionResult.FAILURE;
        }
    }
}
