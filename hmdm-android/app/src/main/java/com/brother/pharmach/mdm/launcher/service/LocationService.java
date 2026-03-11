/*
 * Brother Pharmach MDM: Open Source Android MDM Software
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

package com.brother.pharmach.mdm.launcher.service;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.db.DatabaseHelper;
import com.brother.pharmach.mdm.launcher.db.LocationTable;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.DetailedInfo;
import com.brother.pharmach.mdm.launcher.pro.ProUtils;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class LocationService extends Service {

    private boolean isNetworkConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null)
            return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private LocationManager locationManager;

    private static final int NOTIFICATION_ID = 112;
    public static String CHANNEL_ID = LocationService.class.getName();

    public static final String ACTION_UPDATE_GPS = "gps";
    public static final String ACTION_UPDATE_NETWORK = "network";
    public static final String ACTION_STOP = "stop";

    boolean updateViaGps = false;
    boolean started = false;

    private static final int LOCATION_UPDATE_INTERVAL = 60000;

    // Use different location listeners for GPS and Network
    // Not sure what happens if we share the same listener for both providers
    private LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Do nothing here: we use getLastKnownLocation() to determine the location!
            // Toast.makeText(LocationService.this, "Location updated from GPS",
            // Toast.LENGTH_SHORT).show();
            RemoteLogger.log(LocationService.this, Const.LOG_VERBOSE, "GPS location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            ProUtils.processLocation(LocationService.this, location, LocationManager.GPS_PROVIDER);
            processLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private LocationListener networkLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Do nothing here: we use getLastKnownLocation() to determine the location!
            // Toast.makeText(LocationService.this, "Location updated from Network",
            // Toast.LENGTH_SHORT).show();
            RemoteLogger.log(LocationService.this, Const.LOG_VERBOSE, "Network location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            ProUtils.processLocation(LocationService.this, location, LocationManager.NETWORK_PROVIDER);
            processLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private Handler handler = new Handler();
    private GnssStatus.Callback gnssStatusCallback = null;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) this.getSystemService(LOCATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onStarted() {
                    super.onStarted();
                }

                @Override
                public void onStopped() {
                    super.onStopped();
                }

                @Override
                public void onFirstFix(int ttffMillis) {
                    super.onFirstFix(ttffMillis);
                }

                @Override
                public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                    super.onSatelliteStatusChanged(status);
                }
            };
        }
    }

    private void processLocation(Location location) {
        if (location == null)
            return;

        new Thread(() -> {
            // 1. Save to DB
            LocationTable.Location dbLocation = new LocationTable.Location(location);
            LocationTable.insert(DatabaseHelper.instance(this).getWritableDatabase(), dbLocation);

            // 2. Try to upload immediately
            sendLocations();
        }).start();
    }

    private synchronized void sendLocations() {
        Context context = this;
        // Check if we have internet
        if (!isNetworkConnected()) {
            return;
        }

        List<LocationTable.Location> locations = LocationTable
                .select(DatabaseHelper.instance(context).getReadableDatabase(), 50);
        if (locations.isEmpty()) {
            return;
        }

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        String deviceId = settingsHelper.getDeviceId();
        String project = settingsHelper.getServerProject();

        if (deviceId == null || project == null)
            return;

        ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
        try {
            // Convert LocationTable.Location to DetailedInfo (DeviceDynamicInfo) for Open
            // Source server compatibility
            List<DetailedInfo> detailedInfos = new java.util.LinkedList<>();
            for (LocationTable.Location loc : locations) {
                DetailedInfo detailedInfo = new DetailedInfo();
                detailedInfo.setTs(loc.getTs());

                DetailedInfo.Gps gps = new DetailedInfo.Gps();
                gps.setLat(loc.getLat());
                gps.setLon(loc.getLon());
                // gps.setAlt(loc.getAlt());
                // gps.setSpeed(loc.getSpeed());
                // gps.setCourse(loc.getCourse());
                // gps.setState(loc.getState());
                // gps.setProvider(loc.getProvider());

                detailedInfo.setGps(gps);
                detailedInfos.add(detailedInfo);
            }

            // Use sendDetailedInfo instead of sendLocations
            Response<ResponseBody> response = serverService.sendDetailedInfo(project, deviceId, detailedInfos)
                    .execute();
            if (response.isSuccessful()) {
                // Remove uploaded items
                LocationTable.delete(DatabaseHelper.instance(context).getWritableDatabase(), locations);

                // If there are more, send recursively (or next loop)
                if (locations.size() == 50) {
                    sendLocations();
                }
            } else {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "Failed to send locations: " + response.code() + " " + response.message());
            }
        } catch (Exception e) {
            e.printStackTrace();
            RemoteLogger.log(context, Const.LOG_WARN, "Exception sending locations: " + e.getMessage());
        }
    }

    @SuppressLint("WrongConstant")
    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Notification Channel",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = (NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder(this);
        }
        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setTicker(ProUtils.getAppName(this))
                .setContentText(getString(R.string.location_service_text))
                .setSmallIcon(R.drawable.ic_location_service).build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private boolean requestLocationUpdates() {
        if (updateViaGps && (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))) {
            updateViaGps = false;
        }
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // No permission, so give up!
            return false;
        }

        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        boolean passiveEnabled = locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);
        RemoteLogger.log(this, Const.LOG_VERBOSE,
                "Request location updates. gps=" + gpsEnabled + ", network=" + networkEnabled + ", passive="
                        + passiveEnabled);

        locationManager.removeUpdates(networkLocationListener);
        locationManager.removeUpdates(gpsLocationListener);
        try {
            if (networkEnabled) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_UPDATE_INTERVAL, 0,
                        networkLocationListener);
            }
            if (gpsEnabled) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_UPDATE_INTERVAL, 0,
                        gpsLocationListener);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                    locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
                }
            }
        } catch (Exception e) {
            // Provider may not exist, so process it friendly
            e.printStackTrace();
            RemoteLogger.log(this, Const.LOG_WARN, "Failed to request location updates: " + e.getMessage());
            return false;
        }

        return true;
    }

    @Override
    public void onDestroy() {
        if (locationManager != null) {
            locationManager.removeUpdates(networkLocationListener);
            locationManager.removeUpdates(gpsLocationListener);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            }
        }
        started = false;

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent inputIntent, int flags, int startId) {
        boolean legacyGpsFlag = updateViaGps;
        if (inputIntent != null && inputIntent.getAction() != null) {
            if (inputIntent.getAction().equals(ACTION_STOP)) {
                // Stop service
                started = false;
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            } else if (inputIntent.getAction().equals(ACTION_UPDATE_GPS)) {
                updateViaGps = true;
            } else {
                updateViaGps = false;
            }
        } else {
            updateViaGps = false;
        }
        boolean needRequestUpdates = !started || legacyGpsFlag != updateViaGps;
        if (!started) {
            startAsForeground();
            started = true;
        }
        if (needRequestUpdates) {
            if (!requestLocationUpdates()) {
                // No permissions!
                started = false;
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }
}
