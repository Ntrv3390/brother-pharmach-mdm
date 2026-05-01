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

package com.brother.pharmach.mdm.launcher.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.BuildConfig;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.db.DatabaseHelper;
import com.brother.pharmach.mdm.launcher.db.RemoteFileTable;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.Application;
import com.brother.pharmach.mdm.launcher.json.DeviceInfo;
import com.brother.pharmach.mdm.launcher.json.RemoteFile;
import com.brother.pharmach.mdm.launcher.pro.ProUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DeviceInfoProvider {
    public static DeviceInfo getDeviceInfo(Context context, boolean queryPermissions, boolean queryApps) {
        DeviceInfo deviceInfo = new DeviceInfo();
        List<Integer> permissions = deviceInfo.getPermissions();
        List<Application> applications = deviceInfo.getApplications();
        List<RemoteFile> files = deviceInfo.getFiles();

        deviceInfo.setModel(Build.MODEL);

        if (queryPermissions) {
            permissions.add(Utils.checkAdminMode(context) ? 1 : 0);
            permissions.add(Utils.canDrawOverlays(context) ? 1 : 0);
            permissions.add(ProUtils.checkUsageStatistics(context) ? 1 : 0);
            permissions.add(!BuildConfig.USE_ACCESSIBILITY || !ProUtils.checkAccessibilityService(context) ? 0 : 1);
        }

        SettingsHelper config = SettingsHelper.getInstance(context);
        if (queryApps) {
            PackageManager packageManager = context.getPackageManager();
            Set<String> seenPackages = new HashSet<>();

            // Report all launchable installed apps so server-side selectors can use the actual device inventory.
            List<ApplicationInfo> installedApps = packageManager.getInstalledApplications(0);
            for (ApplicationInfo appInfo : installedApps) {
                try {
                    if (packageManager.getLaunchIntentForPackage(appInfo.packageName) == null) {
                        continue;
                    }

                    PackageInfo packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0);
                    if (!seenPackages.add(packageInfo.packageName.toLowerCase())) {
                        continue;
                    }

                    Application installedApp = new Application();
                    installedApp.setName(appInfo.loadLabel(packageManager).toString());
                    installedApp.setPkg(packageInfo.packageName);
                    installedApp.setVersion(packageInfo.versionName);
                    applications.add(installedApp);
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "Failed to read installed app info for " + appInfo.packageName, e);
                }
            }

            if (config.getConfig() != null) {
                List<Application> requiredApps = SettingsHelper.getInstance(context).getConfig().getApplications();
                for (Application application : requiredApps) {
                    if (application.isRemove()) {
                        continue;
                    }
                    try {
                        PackageInfo packageInfo = packageManager.getPackageInfo(application.getPkg(), 0);
                        if (!seenPackages.add(packageInfo.packageName.toLowerCase())) {
                            continue;
                        }

                        Application installedApp = new Application();
                        installedApp.setName(application.getName());
                        installedApp.setPkg(packageInfo.packageName);
                        installedApp.setVersion(packageInfo.versionName);
                        applications.add(installedApp);
                    } catch (PackageManager.NameNotFoundException e) {
                        // Application not installed
                    }
                }

                List<RemoteFile> requiredFiles = SettingsHelper.getInstance(context).getConfig().getFiles();
                for (RemoteFile remoteFile : requiredFiles) {
                    File file = new File(Environment.getExternalStorageDirectory(), remoteFile.getPath());
                    if (file.exists()) {
                        RemoteFile remoteFileDb = RemoteFileTable.selectByPath(DatabaseHelper.instance(context).getReadableDatabase(),
                                remoteFile.getPath());
                        if (remoteFileDb != null) {
                            files.add(remoteFileDb);
                        } else {
                            // How could that happen? The database entry should exist for each file
                            // Let's recalculate the checksum to check if the file matches
                            try {
                                RemoteFile copy = new RemoteFile(remoteFile);
                                copy.setChecksum(CryptoUtils.calculateChecksum(new FileInputStream(file)));
                                files.add(copy);
                            } catch (FileNotFoundException e) {
                            }
                        }
                    }
                }
            }
        }

        deviceInfo.setDeviceId( SettingsHelper.getInstance( context ).getDeviceId() );

        String phone = DeviceInfoProvider.getPhoneNumber(context, 0);
        if (phone == null || phone.equals("")) {
            phone = config.getConfig().getPhone();
        }
        deviceInfo.setPhone(phone);

        String imei = DeviceInfoProvider.getImei(context, 0);
        if (imei == null || imei.equals("")) {
            imei = config.getConfig().getImei();
        }
        deviceInfo.setImei(imei);

        // Battery
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

        Intent batteryStatus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                context.registerReceiver(null, ifilter, Context.RECEIVER_EXPORTED) :
                context.registerReceiver(null, ifilter);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL) {
            int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            switch (chargePlug) {
                case BatteryManager.BATTERY_PLUGGED_USB:
                    deviceInfo.setBatteryCharging(Const.DEVICE_CHARGING_USB);
                    break;
                case BatteryManager.BATTERY_PLUGGED_AC:
                    deviceInfo.setBatteryCharging(Const.DEVICE_CHARGING_AC);
                    break;
            }
        } else {
            deviceInfo.setBatteryCharging("");
        }

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        deviceInfo.setBatteryLevel(level * 100 / scale);

        deviceInfo.setAndroidVersion(Build.VERSION.RELEASE);
        deviceInfo.setLocation(getLocation(context));
        deviceInfo.setMdmMode(Utils.isDeviceOwner(context));
        deviceInfo.setKioskMode(ProUtils.isKioskModeRunning(context));
        deviceInfo.setLauncherType(Utils.getLauncherVariant());
        deviceInfo.setCpu(Build.CPU_ABI);
        deviceInfo.setSerial(getSerialNumber());

        deviceInfo.setImsi(getImsi(context, 0));
        deviceInfo.setIccid(getIccid(context, 0));
        deviceInfo.setImei2(getImei(context, 1));
        deviceInfo.setImsi2(getImsi(context, 1));
        deviceInfo.setPhone2(getPhoneNumber(context, 1));
        deviceInfo.setIccid2(getIccid(context, 1));

        String launcherPackage = Utils.getDefaultLauncher(context);
        deviceInfo.setLauncherPackage(launcherPackage != null ? launcherPackage : "");
        deviceInfo.setDefaultLauncher(context.getPackageName().equals(launcherPackage));

        deviceInfo.setCustom1(config.getUserCustom1());
        deviceInfo.setCustom2(config.getUserCustom2());
        deviceInfo.setCustom3(config.getUserCustom3());

        return deviceInfo;
    }

    @SuppressWarnings({"MissingPermission"})
    public static DeviceInfo.Location getLocation(Context context) {
        try {
            LocationManager locationManager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
            Location lastLocationGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location lastLocationNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            if (lastLocationGps != null || lastLocationNetwork != null) {

                DeviceInfo.Location location = new DeviceInfo.Location();

                Location lastLocation;
                if (lastLocationGps == null || (lastLocationGps.getLatitude() == 0 && lastLocationGps.getLongitude() == 0)) {
                    lastLocation = lastLocationNetwork;
                } else if (lastLocationNetwork == null || (lastLocationNetwork.getLatitude() == 0 && lastLocationNetwork.getLongitude() == 0)) {
                    lastLocation = lastLocationGps;
                } else {
                    // Get the latest location as the best one
                    if (lastLocationGps.getTime() >= lastLocationNetwork.getTime()) {
                        lastLocation = lastLocationGps;
                    } else {
                        lastLocation = lastLocationNetwork;
                    }
                }

                if (lastLocation.getLatitude() == 0 && lastLocation.getLongitude() == 0) {
                    return null;
                }

                location.setLat(lastLocation.getLatitude());
                location.setLon(lastLocation.getLongitude());
                location.setTs(lastLocation.getTime());
                return location;
            }
        } catch (Exception e) {
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    public static String getSerialNumber() {
        String serialNumber = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                String s = Build.getSerial();
                Log.d(Const.LOG_TAG, "Serial number: " + s);
                return s;
            } catch (SecurityException e) {
                Log.w(Const.LOG_TAG, "Failed to get serial number from Build.getSerial()");
                e.printStackTrace();
            }
        }
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class);
            serialNumber = (String) get.invoke(c, "ril.serialnumber");
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to get serial number from ril.serialnumber");
            e.printStackTrace();
        }
        if (serialNumber != null && !serialNumber.equals("")) {
            return serialNumber;
        }
        Log.d(Const.LOG_TAG, "Build.SERIAL=" + Build.SERIAL);
        return Build.SERIAL;
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getPhoneNumber(Context context) {
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getLine1Number();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getIccid(Context context) {
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getSimSerialNumber();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getImsi(Context context) {
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getSubscriberId();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getImsi(Context context, int slot) {
        String imsi = null;
        try {
            TelephonyManager telephonyManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
            // This method is hidden, use reflection
            // Thanks to https://stackoverflow.com/questions/36902916/subscriptionmanager-to-read-imsi-for-dual-sim-devices-ruuning-android-5-1
            Class c = Class.forName("android.telephony.TelephonyManager");
            Method m = c.getMethod("getSubscriberId", new Class[] {int.class});
            Object o = m.invoke(telephonyManager, new Object[]{slot});
            imsi = (String)o;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return imsi;
    }

    private static final String PHONE_CACHE_PREFS = "phone_number_cache";

    @SuppressLint( { "MissingPermission" } )
    public static String getPhoneNumber(Context context, int slot) {
        try {
            Utils.autoGrantPhonePermission(context);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (slot == 0) {
                    return getPhoneNumber(context);
                }
                return null;
            }
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptionList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptionList == null || slot >= subscriptionList.size()) {
                return null;
            }
            SubscriptionInfo subInfo = subscriptionList.get(slot);

            // Priority 1: SubscriptionInfo.getNumber()
            String phone = subInfo.getNumber();

            // Priority 2: TelephonyManager.createForSubscriptionId() — more reliable on API 24+
            if (phone == null || phone.isEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                        if (tm != null) {
                            phone = tm.createForSubscriptionId(subInfo.getSubscriptionId()).getLine1Number();
                        }
                    } catch (Exception e) {
                        Log.w(Const.LOG_TAG, "createForSubscriptionId getLine1Number failed: " + e.getMessage());
                    }
                }
            }

            // Priority 3: plain TelephonyManager.getLine1Number() (slot 0 only)
            if ((phone == null || phone.isEmpty()) && slot == 0) {
                phone = getPhoneNumber(context);
            }

            // Priority 4: SharedPreferences cache keyed by ICCID — persist any fresh value,
            // restore last-known value if all live queries returned empty
            String iccid = subInfo.getIccId();
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PHONE_CACHE_PREFS, Context.MODE_PRIVATE);
            if (phone != null && !phone.isEmpty()) {
                if (iccid != null && !iccid.isEmpty()) {
                    prefs.edit().putString("phone_" + iccid, phone).apply();
                }
            } else if (iccid != null && !iccid.isEmpty()) {
                String cached = prefs.getString("phone_" + iccid, null);
                if (cached != null && !cached.isEmpty()) {
                    Log.d(Const.LOG_TAG, "Phone number from ICCID cache (slot " + slot + "): " + cached);
                    phone = cached;
                }
            }

            return (phone != null && !phone.isEmpty()) ? phone : null;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getIccid(Context context, int slot) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (slot == 0) {
                    return getPhoneNumber(context);
                }
                return null;
            }
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptionList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptionList == null || slot >= subscriptionList.size()) {
                // No mobile info at all
                return null;
            }
            return subscriptionList.get(slot).getIccId();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getImei(Context context) {
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getDeviceId();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getImei(Context context, int slot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (slot == 0) {
                return getImei(context);
            }
            return null;
        }
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getDeviceId(slot);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the STB MacAddress
     */
    public static String getMacAddress() {
        try {
            return Utils.loadFileAsString("/sys/class/net/eth0/address")
                    .toUpperCase().substring(0, 17);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
