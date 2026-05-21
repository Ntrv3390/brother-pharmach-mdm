package com.brother.pharmach.mdm.launcher.util;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.TelephonyManager;
import android.text.format.Formatter;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.db.LocationTable;
import com.brother.pharmach.mdm.launcher.json.DetailedInfo;
import com.brother.pharmach.mdm.launcher.json.DeviceInfo;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;

public final class DynamicInfoHelper {

    private DynamicInfoHelper() {
    }

    private static final String PREFS_NAME = "traffic_stats_cache";
    private static final String LAST_TOTAL_TX = "last_total_tx";
    private static final String LAST_TOTAL_RX = "last_total_rx";
    private static final String LAST_MOBILE_TX = "last_mobile_tx";
    private static final String LAST_MOBILE_RX = "last_mobile_rx";

    public static DetailedInfo buildDetailedInfo(Context context, LocationTable.Location location) {
        return buildDetailedInfo(context, location, false);
    }

    public static DetailedInfo buildDetailedInfo(
            Context context,
            LocationTable.Location location,
            boolean isUrgent) {
        DetailedInfo detailedInfo = new DetailedInfo();
        detailedInfo.setTs(isUrgent ? System.currentTimeMillis() : location.getTs());

        DetailedInfo.Gps gps = new DetailedInfo.Gps();
        gps.setState(location.getTs() > System.currentTimeMillis() - 60_000L ? Const.GPS_STATE_ACTIVE : Const.GPS_STATE_LOST);
        gps.setLat(location.getLat());
        gps.setLon(location.getLon());
        detailedInfo.setGps(gps);

        DetailedInfo.Device device = new DetailedInfo.Device();
        populateDevice(context, device);
        detailedInfo.setDevice(device);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        DetailedInfo.Wifi wifi = new DetailedInfo.Wifi();
        populateWifi(context, wifi, device, prefs);
        detailedInfo.setWifi(wifi);

        DetailedInfo.Mobile mobile = new DetailedInfo.Mobile();
        populateMobile(context, mobile, device, prefs);
        detailedInfo.setMobile(mobile);

        // Populate device IP with the active one
        if (device.getIp() == null) {
            if (wifi.getIp() != null) {
                device.setIp(wifi.getIp());
            } else if (mobile.getIp() != null) {
                device.setIp(mobile.getIp());
            }
        }

        // Keep second SIM payload optional for compatibility and to avoid fragile slot-specific APIs.
        detailedInfo.setMobile2(null);

        return detailedInfo;
    }

    private static void populateDevice(Context context, DetailedInfo.Device device) {
        try {
            DeviceInfo info = DeviceInfoProvider.getDeviceInfo(context, true, false);
            device.setBatteryLevel(info.getBatteryLevel());
            device.setBatteryCharging(info.isBatteryCharging());
        } catch (Exception ignored) {
        }

        try {
            Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus != null && device.getBatteryLevel() == null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    device.setBatteryLevel((level * 100) / scale);
                }
            }
        } catch (Exception ignored) {
        }

        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memoryInfo);
                device.setMemoryTotal((int) (memoryInfo.totalMem / 1024L / 1024L));
                device.setMemoryAvailable((int) (memoryInfo.availMem / 1024L / 1024L));
            }
        } catch (Exception ignored) {
            try {
                Runtime runtime = Runtime.getRuntime();
                long total = runtime.totalMemory();
                long free = runtime.freeMemory();
                device.setMemoryTotal((int) (total / 1024L / 1024L));
                device.setMemoryAvailable((int) (free / 1024L / 1024L));
            } catch (Exception secondIgnored) {
            }
        }

        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                device.setRingVolume(audioManager.getStreamVolume(AudioManager.STREAM_RING));
            }
        } catch (Exception ignored) {
        }

        try {
            KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                device.setKeyguard(keyguardManager.isDeviceLocked());
            }
        } catch (Exception ignored) {
        }

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            device.setBluetooth(adapter != null && adapter.isEnabled());
        } catch (Exception ignored) {
        }

        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            device.setWifi(wifiManager != null && wifiManager.isWifiEnabled());
        } catch (Exception ignored) {
        }

        try {
            LocationManager locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            boolean gpsEnabled = locationManager != null
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            device.setGps(gpsEnabled);
        } catch (Exception ignored) {
        }

        try {
            device.setMobileData(isCellularConnected(context));
        } catch (Exception ignored) {
        }

        try {
            IntentFilter filter = new IntentFilter("android.hardware.usb.action.USB_STATE");
            Intent intent = context.registerReceiver(null, filter);
            if (intent != null) {
                device.setUsbStorage(intent.getBooleanExtra("connected", false));
            }
        } catch (Exception ignored) {
        }
    }

    private static void populateWifi(Context context, DetailedInfo.Wifi wifi, DetailedInfo.Device device, SharedPreferences prefs) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                wifi.setState(Const.WIFI_STATE_INACTIVE);
                return;
            }

            if (!wifiManager.isWifiEnabled()) {
                wifi.setState(Const.WIFI_STATE_INACTIVE);
                return;
            }

            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                int rssi = wifiInfo.getRssi();
                if (rssi > -127) {
                    wifi.setRssi(rssi);
                }

                String ssid = wifiInfo.getSSID();
                if (ssid != null && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                    wifi.setSsid(ssid.replace("\"", ""));
                }

                wifi.setSecurity(getWifiSecurity(wifiManager, wifiInfo));
            }

            wifi.setState(device.getWifi() != null && device.getWifi()
                    ? Const.WIFI_STATE_CONNECTED
                    : Const.WIFI_STATE_DISCONNECTED);

            if (Const.WIFI_STATE_CONNECTED.equals(wifi.getState())) {
                wifi.setIp(Formatter.formatIpAddress(wifiInfo.getIpAddress()));
                
                long currentTotalTx = TrafficStats.getTotalTxBytes();
                long currentTotalRx = TrafficStats.getTotalRxBytes();
                long lastTotalTx = prefs.getLong(LAST_TOTAL_TX, 0);
                long lastTotalRx = prefs.getLong(LAST_TOTAL_RX, 0);
                
                // Approximate WiFi as Total - Mobile
                long currentMobileTx = TrafficStats.getMobileTxBytes();
                long currentMobileRx = TrafficStats.getMobileRxBytes();
                
                long wifiTx = currentTotalTx - currentMobileTx;
                long wifiRx = currentTotalRx - currentMobileRx;
                
                wifi.setTx(wifiTx >= lastTotalTx ? wifiTx - lastTotalTx : wifiTx);
                wifi.setRx(wifiRx >= lastTotalRx ? wifiRx - lastTotalRx : wifiRx);
                
                prefs.edit().putLong(LAST_TOTAL_TX, wifiTx).putLong(LAST_TOTAL_RX, wifiRx).apply();
            }
        } catch (Exception ignored) {
            wifi.setState(Const.WIFI_STATE_FAILED);
        }
    }

    private static String getWifiSecurity(WifiManager wifiManager, WifiInfo wifiInfo) {
        try {
            String ssid = wifiInfo.getSSID();
            if (ssid == null) return null;
            List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
            if (configs != null) {
                for (WifiConfiguration config : configs) {
                    if (config.SSID != null && config.SSID.equals(ssid)) {
                        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) return "WPA-PSK";
                        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)) return "WPA-EAP";
                        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) return "OPEN";
                        return "Protected";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void populateMobile(Context context, DetailedInfo.Mobile mobile, DetailedInfo.Device device, SharedPreferences prefs) {
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                String operator = telephonyManager.getNetworkOperatorName();
                if (operator != null && !operator.trim().isEmpty()) {
                    mobile.setCarrier(operator);
                }
                mobile.setSimState(mapSimState(telephonyManager.getSimState()));
                mobile.setNumber(DeviceInfoProvider.getPhoneNumber(context));
                mobile.setImsi(DeviceInfoProvider.getImsi(context));
            }
        } catch (Exception ignored) {
        }

        mobile.setState(device.getMobileData() != null && device.getMobileData()
                ? Const.MOBILE_STATE_CONNECTED
                : Const.MOBILE_STATE_DISCONNECTED);

        if (Const.MOBILE_STATE_CONNECTED.equals(mobile.getState())) {
            mobile.setIp(getMobileIpAddress());
            
            long currentMobileTx = TrafficStats.getMobileTxBytes();
            long currentMobileRx = TrafficStats.getMobileRxBytes();
            long lastMobileTx = prefs.getLong(LAST_MOBILE_TX, 0);
            long lastMobileRx = prefs.getLong(LAST_MOBILE_RX, 0);

            mobile.setTx(currentMobileTx >= lastMobileTx ? currentMobileTx - lastMobileTx : currentMobileTx);
            mobile.setRx(currentMobileRx >= lastMobileRx ? currentMobileRx - lastMobileRx : currentMobileRx);
            
            prefs.edit().putLong(LAST_MOBILE_TX, currentMobileTx).putLong(LAST_MOBILE_RX, currentMobileRx).apply();
            
            mobile.setRssi(getMobileRssi(context));
        }
    }

    private static String getMobileIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Integer getMobileRssi(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return null;

            List<CellInfo> cellInfos = tm.getAllCellInfo();
            if (cellInfos != null) {
                for (CellInfo cellInfo : cellInfos) {
                    if (cellInfo.isRegistered()) {
                        if (cellInfo instanceof CellInfoGsm) {
                            return ((CellInfoGsm) cellInfo).getCellSignalStrength().getDbm();
                        } else if (cellInfo instanceof CellInfoLte) {
                            return ((CellInfoLte) cellInfo).getCellSignalStrength().getDbm();
                        } else if (cellInfo instanceof CellInfoWcdma) {
                            return ((CellInfoWcdma) cellInfo).getCellSignalStrength().getDbm();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isCellularConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }

            NetworkInfo mobile = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            return mobile != null && mobile.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String mapSimState(int simState) {
        switch (simState) {
            case TelephonyManager.SIM_STATE_ABSENT:
                return Const.MOBILE_SIMSTATE_ABSENT;
            case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                return Const.MOBILE_SIMSTATE_PIN_REQUIRED;
            case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                return Const.MOBILE_SIMSTATE_PUK_REQUIRED;
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                return Const.MOBILE_SIMSTATE_LOCKED;
            case TelephonyManager.SIM_STATE_READY:
                return Const.MOBILE_SIMSTATE_READY;
            case TelephonyManager.SIM_STATE_NOT_READY:
                return Const.MOBILE_SIMSTATE_NOT_READY;
            default:
                return Const.MOBILE_SIMSTATE_UNKNOWN;
        }
    }
}
