package com.brother.pharmach.mdm.launcher.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.db.DatabaseHelper;
import com.brother.pharmach.mdm.launcher.db.LocationTable;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.DetailedInfo;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;

import java.util.LinkedList;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Static helpers for uploading device location data to the server.
 * Uses the local SQLite queue for retry — locations are stored when offline
 * and flushed on the next successful network connection.
 */
public final class LocationUploader {

    private LocationUploader() {}

    public static boolean isNetworkConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private static Response<ResponseBody> sendDetailedInfo(Context context, String project, String deviceId,
                                                           List<DetailedInfo> infos) {
        ServerService primary = ServerServiceKeeper.getServerServiceInstance(context);
        ServerService secondary = ServerServiceKeeper.getSecondaryServerServiceInstance(context);

        Response<ResponseBody> response = null;
        try {
            response = primary.sendDetailedInfo(project, deviceId, infos).execute();
        } catch (Exception ignored) {}

        if ((response == null || !response.isSuccessful()) && secondary != null) {
            try {
                response = secondary.sendDetailedInfo(project, deviceId, infos).execute();
            } catch (Exception ignored) {}
        }
        return response;
    }

    /**
     * Upload a single location immediately (urgent path).
     * Returns true on success; caller should persist or queue on false.
     */
    public static synchronized boolean sendUrgentLocation(Context context, LocationTable.Location location) {
        return sendUrgentLocation(context, location, true);
    }

    public static synchronized boolean sendUrgentLocation(Context context,
                                                          LocationTable.Location location,
                                                          boolean isUrgent) {
        if (!isNetworkConnected(context) || location == null) return false;

        SettingsHelper sh = SettingsHelper.getInstance(context);
        if (sh == null) return false;

        String deviceId = sh.getDeviceId();
        String project = sh.getServerProject();
        if (deviceId == null || project == null) return false;

        try {
            List<DetailedInfo> infos = new LinkedList<>();
            infos.add(DynamicInfoHelper.buildDetailedInfo(context, location, isUrgent));
            Response<ResponseBody> response = sendDetailedInfo(context, project, deviceId, infos);
            if (response != null && response.isSuccessful()) {
                sh.setExternalIp(response.headers().get(Const.HEADER_IP_ADDRESS));
                return true;
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "LocationUploader: urgent upload failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Flush queued locations from the SQLite buffer to the server.
     * Silently does nothing when offline — locations remain queued.
     */
    public static synchronized void sendLocations(Context context) {
        if (!isNetworkConnected(context)) return;

        DatabaseHelper db = DatabaseHelper.instance(context);
        if (db == null) return;

        List<LocationTable.Location> locations = LocationTable.select(db.getReadableDatabase(), 50);
        if (locations.isEmpty()) return;

        SettingsHelper sh = SettingsHelper.getInstance(context);
        if (sh == null) return;

        String deviceId = sh.getDeviceId();
        String project = sh.getServerProject();
        if (deviceId == null || project == null) return;

        try {
            List<DetailedInfo> infos = new LinkedList<>();
            for (LocationTable.Location loc : locations) {
                infos.add(DynamicInfoHelper.buildDetailedInfo(context, loc));
            }
            Response<ResponseBody> response = sendDetailedInfo(context, project, deviceId, infos);
            if (response != null && response.isSuccessful()) {
                sh.setExternalIp(response.headers().get(Const.HEADER_IP_ADDRESS));
                LocationTable.delete(db.getWritableDatabase(), locations);
                if (locations.size() == 50) {
                    // There may be more — recurse until queue is empty.
                    sendLocations(context);
                }
            } else {
                int code = response != null ? response.code() : -1;
                String msg = response != null ? response.message() : "no response";
                RemoteLogger.log(context, Const.LOG_WARN,
                        "LocationUploader: batch upload failed: " + code + " " + msg
                        + " — will retry on next cycle");
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "LocationUploader: sendLocations threw: " + e.getMessage());
        }
    }
}
