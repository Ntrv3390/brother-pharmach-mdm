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

package com.brother.pharmach.mdm.launcher.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.db.DatabaseHelper;
import com.brother.pharmach.mdm.launcher.db.LocationTable;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.DetailedInfo;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;
import com.brother.pharmach.mdm.launcher.util.DynamicInfoHelper;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;
import com.brother.pharmach.mdm.launcher.worker.LocationWorker;

import java.util.LinkedList;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class LocationService extends Service {

    private static final int NOTIFICATION_ID = 112;
    public static String CHANNEL_ID = LocationService.class.getName();

    public static final String ACTION_UPDATE_GPS = "gps";
    public static final String ACTION_UPDATE_NETWORK = "network";
    public static final String ACTION_STOP = "stop";

    private static boolean isNetworkConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private static Response<ResponseBody> sendDetailedInfo(Context context, String project, String deviceId,
                                                           List<DetailedInfo> detailedInfos) {
        ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
        ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);

        Response<ResponseBody> response = null;
        try {
            response = serverService.sendDetailedInfo(project, deviceId, detailedInfos).execute();
        } catch (Exception ignored) {
        }

        if ((response == null || !response.isSuccessful()) && secondaryServerService != null) {
            try {
                response = secondaryServerService.sendDetailedInfo(project, deviceId, detailedInfos).execute();
            } catch (Exception ignored) {
            }
        }

        return response;
    }

    public static synchronized boolean sendUrgentLocation(Context context, LocationTable.Location location) {
        if (!isNetworkConnected(context) || location == null) {
            return false;
        }

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        if (settingsHelper == null) {
            return false;
        }

        String deviceId = settingsHelper.getDeviceId();
        String project = settingsHelper.getServerProject();
        if (deviceId == null || project == null) {
            return false;
        }

        try {
            List<DetailedInfo> detailedInfos = new LinkedList<>();
            detailedInfos.add(DynamicInfoHelper.buildDetailedInfo(context, location));

            Response<ResponseBody> response = sendDetailedInfo(context, project, deviceId, detailedInfos);
            if (response != null && response.isSuccessful()) {
                settingsHelper.setExternalIp(response.headers().get(Const.HEADER_IP_ADDRESS));
                return true;
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Exception sending urgent location: " + e.getMessage());
        }

        return false;
    }

    public static synchronized void sendLocations(Context context) {
        if (!isNetworkConnected(context)) {
            return;
        }

        DatabaseHelper db = DatabaseHelper.instance(context);
        if (db == null) {
            return;
        }

        List<LocationTable.Location> locations = LocationTable.select(db.getReadableDatabase(), 50);
        if (locations.isEmpty()) {
            return;
        }

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        if (settingsHelper == null) {
            return;
        }

        String deviceId = settingsHelper.getDeviceId();
        String project = settingsHelper.getServerProject();
        if (deviceId == null || project == null) {
            return;
        }

        try {
            List<DetailedInfo> detailedInfos = new LinkedList<>();
            for (LocationTable.Location loc : locations) {
                detailedInfos.add(DynamicInfoHelper.buildDetailedInfo(context, loc));
            }

            Response<ResponseBody> response = sendDetailedInfo(context, project, deviceId, detailedInfos);
            if (response != null && response.isSuccessful()) {
                settingsHelper.setExternalIp(response.headers().get(Const.HEADER_IP_ADDRESS));
                LocationTable.delete(db.getWritableDatabase(), locations);
                if (locations.size() == 50) {
                    sendLocations(context);
                }
            } else {
                int code = response != null ? response.code() : -1;
                String message = response != null ? response.message() : "no response";
                RemoteLogger.log(context, Const.LOG_WARN,
                        "Failed to send locations: " + code + " " + message);
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Exception sending locations: " + e.getMessage());
        }
    }

    private Notification buildSilentNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "System",
                NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_location_service)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent inputIntent, int flags, int startId) {
        String action = inputIntent != null ? inputIntent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean foregroundStarted = startForegroundSafely();
        if (ACTION_UPDATE_GPS.equals(action)) {
            new Thread(() -> {
                try {
                    LocationWorker.runUrgentNow(getApplicationContext());
                } catch (Exception e) {
                    RemoteLogger.log(getApplicationContext(), Const.LOG_WARN,
                            "Urgent GPS refresh in LocationService failed: " + e.getMessage());
                } finally {
                    stopForegroundSafely(foregroundStarted);
                    stopSelf();
                }
            }, "urgent-gps-refresh").start();
            return START_NOT_STICKY;
        }

        stopForegroundSafely(foregroundStarted);
        stopSelf();
        return START_NOT_STICKY;
    }

    private boolean startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(NOTIFICATION_ID, buildSilentNotification());
                return true;
            }
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "LocationService startForeground failed: " + e.getMessage());
        }
        return false;
    }

    private void stopForegroundSafely(boolean foregroundStarted) {
        if (!foregroundStarted || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
        }
    }
}
