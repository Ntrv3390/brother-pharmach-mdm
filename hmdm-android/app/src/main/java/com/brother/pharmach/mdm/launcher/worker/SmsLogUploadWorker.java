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

package com.brother.pharmach.mdm.launcher.worker;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.brother.pharmach.mdm.launcher.BuildConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.SmsLogRecord;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class SmsLogUploadWorker extends Worker {

    private static final String TAG = "SmsLogUploadWorker";
    private static final int FIRE_PERIOD_MINS = 15;
    private static final String WORK_TAG_SMSLOG = "com.brother.pharmach.mdm.launcher.WORK_TAG_SMSLOG";
    private static final String WORK_TAG_SMSLOG_NOW = "com.brother.pharmach.mdm.launcher.WORK_TAG_SMSLOG_NOW";
    private static final int UPLOAD_BATCH_SIZE = 100;
    private static final String PREF_LAST_SMS_TIMESTAMP = "last_sms_log_timestamp";
    private static final String DEBUG_CHANNEL_ID = "smslog_debug_channel";
    private static final int DEBUG_NOTIFICATION_ID = 9031;
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
        private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        private static final String[] SMS_PROJECTION_WITH_SUB_ID = {
            Telephony.Sms.ADDRESS,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.PERSON,
            "sub_id"
        };
        private static final String[] SMS_PROJECTION_WITH_SUBSCRIPTION_ID = {
            Telephony.Sms.ADDRESS,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.PERSON,
            "subscription_id"
        };
        private static final String[] SMS_PROJECTION_WITH_SIM_ID = {
            Telephony.Sms.ADDRESS,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.PERSON,
            "sim_id"
        };
        private static final String[] SMS_PROJECTION_BASIC = {
            Telephony.Sms.ADDRESS,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.PERSON
        };

    public SmsLogUploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    public static void schedule(Context context) {
        RemoteLogger.log(context, Const.LOG_DEBUG, "SmsLogUploadWorker: scheduling periodic and immediate workers");
        showDebugAlert(context, "SMSLog: worker scheduled");
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(SmsLogUploadWorker.class, FIRE_PERIOD_MINS, TimeUnit.MINUTES)
                        .addTag(Const.WORK_TAG_COMMON)
                        .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(WORK_TAG_SMSLOG, ExistingPeriodicWorkPolicy.REPLACE, request);
        triggerNow(context, 0, "schedule");
        }

        public static void triggerNow(Context context, long delaySeconds, String source) {
        long safeDelay = Math.max(0, delaySeconds);
        RemoteLogger.log(context, Const.LOG_DEBUG,
            "SmsLogUploadWorker: triggerNow source='" + source + "', delaySeconds=" + safeDelay);
        OneTimeWorkRequest oneTimeRequest =
            new OneTimeWorkRequest.Builder(SmsLogUploadWorker.class)
                .setInitialDelay(safeDelay, TimeUnit.SECONDS)
                .addTag(Const.WORK_TAG_COMMON)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
            .enqueueUniqueWork(WORK_TAG_SMSLOG_NOW, ExistingWorkPolicy.REPLACE, oneTimeRequest);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        if (!BuildConfig.ENABLE_SMS_LOG) {
            RemoteLogger.log(context, Const.LOG_DEBUG, "SmsLogUploadWorker: feature disabled by flavor, skipping");
            showDebugAlert(context, "SMSLog: disabled by flavor");
            return Result.success();
        }

        RemoteLogger.log(context, Const.LOG_DEBUG, "SmsLogUploadWorker: started");
        showDebugAlert(context, "SMSLog: worker started");

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing READ_SMS permission");
            RemoteLogger.log(context, Const.LOG_WARN, "SmsLogUploadWorker: READ_SMS is not granted, skipping upload");
            return Result.success();
        }

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        String deviceId = settingsHelper.getDeviceId();
        String serverProject = settingsHelper.getServerProject();
        if (serverProject == null) {
            serverProject = "";
        }

        if (deviceId == null || deviceId.trim().isEmpty()) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "SmsLogUploadWorker: deviceId is empty, skipping upload until enrollment completes");
            return Result.success();
        }

        ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
        ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
        if (serverService == null && secondaryServerService == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "SmsLogUploadWorker: both server instances unavailable, retrying");
            showDebugAlert(context, "SMSLog: server unavailable, retry");
            return Result.retry();
        }

        RemoteLogger.log(context, Const.LOG_DEBUG,
                "SmsLogUploadWorker: checking enabled status for deviceId='" + deviceId + "', project='" + serverProject + "'");

        try {
            Response<ResponseBody> enabledResponse = null;
            if (serverService != null) {
                enabledResponse = serverService.isSmsLogEnabled(serverProject, deviceId).execute();
            }
            if (enabledResponse == null || !enabledResponse.isSuccessful() || enabledResponse.body() == null) {
                if (secondaryServerService != null) {
                    enabledResponse = secondaryServerService.isSmsLogEnabled(serverProject, deviceId).execute();
                }
            }

            if (enabledResponse == null || !enabledResponse.isSuccessful() || enabledResponse.body() == null) {
                RemoteLogger.log(context, Const.LOG_WARN, "SmsLogUploadWorker: enabled endpoint unavailable on both servers, retrying");
                showDebugAlert(context, "SMSLog: enabled endpoint unavailable");
                return Result.retry();
            }

            String enabledPayload = enabledResponse.body().string();
            Boolean enabled = parseSmsLogEnabled(enabledPayload);
            if (enabled == null) {
                Log.w(TAG, "SMS log enabled endpoint returned invalid payload, scheduling retry");
                RemoteLogger.log(context, Const.LOG_WARN,
                        "SmsLogUploadWorker: invalid enabled payload='" + enabledPayload + "', retrying");
                showDebugAlert(context, "SMSLog: invalid enabled payload");
                return Result.retry();
            }
            if (!enabled) {
                RemoteLogger.log(context, Const.LOG_INFO, "SmsLogUploadWorker: smslog disabled on server, skipping");
                showDebugAlert(context, "SMSLog: disabled in server settings");
                return Result.success();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to check smslog enabled status", e);
            RemoteLogger.log(context, Const.LOG_WARN,
                    "SmsLogUploadWorker: network error on enabled check: " + e.getMessage());
            showDebugAlert(context, "SMSLog: enabled check network error");
            return Result.retry();
        }

        SharedPreferences prefs = context.getSharedPreferences("SmsLogPrefs", Context.MODE_PRIVATE);
        long lastTimestamp = prefs.getLong(PREF_LAST_SMS_TIMESTAMP, 0);
        long now = System.currentTimeMillis();
        if (lastTimestamp > now + TimeUnit.DAYS.toMillis(1)) {
            // Guard against corrupted/future timestamp which would make all scans permanently empty.
            RemoteLogger.log(context, Const.LOG_WARN,
                "SmsLogUploadWorker: last timestamp is in future (" + lastTimestamp + "), resetting to 0");
            lastTimestamp = 0;
            prefs.edit().putLong(PREF_LAST_SMS_TIMESTAMP, 0).apply();
        }

        List<SmsLogRecord> records = new ArrayList<>();
        Map<String, String> contactNameCache = new HashMap<>();
        long maxTimestamp = lastTimestamp;

        String selection = Telephony.Sms.DATE + " > ?";
        String[] selectionArgs = {String.valueOf(lastTimestamp)};
        String sortOrder = Telephony.Sms.DATE + " ASC";

        Cursor cursor = null;
        try {
            cursor = querySmsCursor(context, selection, selectionArgs, sortOrder);

            if (cursor != null && cursor.moveToFirst()) {
                int addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
                int typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE);
                int dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE);
                int subIdIdx = cursor.getColumnIndex("sub_id");
                int subscriptionIdIdx = cursor.getColumnIndex("subscription_id");
                int simIdIdx = cursor.getColumnIndex("sim_id");

                do {
                    int androidType = typeIdx != -1 ? cursor.getInt(typeIdx) : -1;
                    Integer messageType = mapSmsType(androidType);
                    if (messageType == null) {
                        continue;
                    }

                    long timestamp = dateIdx != -1 ? cursor.getLong(dateIdx) : 0;

                    SmsLogRecord record = new SmsLogRecord();
                    String phoneNumber = addressIdx != -1 ? cursor.getString(addressIdx) : null;
                    record.setPhoneNumber(phoneNumber);
                    record.setContactName(resolveContactName(context, phoneNumber, contactNameCache));
                    record.setMessageType(messageType);
                    record.setSmsTimestamp(timestamp);
                    record.setSmsDate(DATE_FORMAT.format(new Date(timestamp)));
                    record.setSimSlot(resolveSimSlot(context, cursor, subIdIdx, subscriptionIdIdx, simIdIdx));

                    records.add(record);

                    if (timestamp > maxTimestamp) {
                        maxTimestamp = timestamp;
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading sms log", e);
            RemoteLogger.log(context, Const.LOG_WARN, "SmsLogUploadWorker: SMS scan failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            showDebugAlert(context, "SMSLog: scan failed - " + e.getClass().getSimpleName());
            return Result.failure();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        Log.i(TAG, "SMS log scan complete: newRows=" + records.size() +
                ", sinceTimestamp=" + lastTimestamp +
                ", maxTimestamp=" + maxTimestamp);
        RemoteLogger.log(context, Const.LOG_DEBUG,
            "SmsLogUploadWorker: scan complete, newRows=" + records.size() +
                ", sinceTimestamp=" + lastTimestamp +
                ", maxTimestamp=" + maxTimestamp);

        if (records.isEmpty()) {
            RemoteLogger.log(context, Const.LOG_DEBUG, "SmsLogUploadWorker: no new SMS records to upload");
            showDebugAlert(context, "SMSLog: no new SMS found");
            return Result.success();
        }

        long uploadedMaxTimestamp = lastTimestamp;
        int uploadedCount = 0;

        for (int start = 0; start < records.size(); start += UPLOAD_BATCH_SIZE) {
            int end = Math.min(start + UPLOAD_BATCH_SIZE, records.size());
            List<SmsLogRecord> batch = records.subList(start, end);

            try {
                Response<ResponseBody> response = uploadToAvailableServer(
                        serverService,
                        secondaryServerService,
                        serverProject,
                        deviceId,
                        batch);

                if (response == null || !response.isSuccessful()) {
                    int code = response != null ? response.code() : -1;
                    Log.w(TAG, "SMS log upload failed for batch [" + start + "," + end + "), HTTP status=" + code);
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "SmsLogUploadWorker: upload failed for batch [" + start + "," + end + "), status=" + code);
                    showDebugAlert(context, "SMSLog: upload failed HTTP " + code);
                    return Result.retry();
                }

                uploadedCount += batch.size();
                for (SmsLogRecord record : batch) {
                    if (record.getSmsTimestamp() > uploadedMaxTimestamp) {
                        uploadedMaxTimestamp = record.getSmsTimestamp();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "SMS log upload failed for batch [" + start + "," + end + ")", e);
                RemoteLogger.log(context, Const.LOG_WARN,
                        "SmsLogUploadWorker: network error while uploading batch [" + start + "," + end + "): " + e.getMessage());
                showDebugAlert(context, "SMSLog: upload network error");
                return Result.retry();
            }
        }

        prefs.edit().putLong(PREF_LAST_SMS_TIMESTAMP, uploadedMaxTimestamp).apply();
        Log.i(TAG, "Uploaded " + uploadedCount + " sms log records successfully in batches");
        RemoteLogger.log(context, Const.LOG_INFO,
                "SmsLogUploadWorker: uploaded " + uploadedCount + " SMS records, new timestamp=" + uploadedMaxTimestamp);
        showDebugAlert(context, "SMSLog: uploaded " + uploadedCount + " records");
        return Result.success();
    }

    private static void showDebugAlert(Context context, String text) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        DEBUG_CHANNEL_ID,
                        "SMS Log Debug",
                        NotificationManager.IMPORTANCE_HIGH);
                notificationManager.createNotificationChannel(channel);
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, DEBUG_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("SMS Log Debug")
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);

            notificationManager.notify(DEBUG_NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.w(TAG, "Failed to show debug alert", e);
        }
    }

    private Cursor querySmsCursor(Context context, String selection, String[] selectionArgs, String sortOrder) {
        Cursor cursor = tryQuerySmsCursor(context, SMS_PROJECTION_WITH_SUB_ID, selection, selectionArgs, sortOrder, "sub_id");
        if (cursor != null) {
            return cursor;
        }

        cursor = tryQuerySmsCursor(context, SMS_PROJECTION_WITH_SUBSCRIPTION_ID, selection, selectionArgs, sortOrder, "subscription_id");
        if (cursor != null) {
            return cursor;
        }

        cursor = tryQuerySmsCursor(context, SMS_PROJECTION_WITH_SIM_ID, selection, selectionArgs, sortOrder, "sim_id");
        if (cursor != null) {
            return cursor;
        }

        Log.w(TAG, "All SIM-aware SMS projections failed, retrying with basic projection");
        return context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI,
                SMS_PROJECTION_BASIC,
                selection,
                selectionArgs,
                sortOrder);
    }

    private Cursor tryQuerySmsCursor(
            Context context,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder,
            String projectionName
    ) {
        try {
            return context.getContentResolver().query(
                    Telephony.Sms.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder);
        } catch (Exception e) {
            Log.w(TAG, "SMS projection with " + projectionName + " failed", e);
            return null;
        }
    }

    private Response<ResponseBody> uploadToAvailableServer(
            ServerService primary,
            ServerService secondary,
            String project,
            String deviceId,
            List<SmsLogRecord> batch
    ) throws IOException {
        Response<ResponseBody> response = null;
        if (primary != null) {
            response = primary.uploadSmsLogs(project, deviceId, batch).execute();
            if (response != null && response.isSuccessful()) {
                return response;
            }
        }

        if (secondary != null) {
            response = secondary.uploadSmsLogs(project, deviceId, batch).execute();
        }

        return response;
    }

    private Integer resolveSimSlot(Context context, Cursor cursor, int subIdIdx, int subscriptionIdIdx, int simIdIdx) {
        Integer raw = null;
        if (subIdIdx != -1 && !cursor.isNull(subIdIdx)) {
            raw = cursor.getInt(subIdIdx);
        } else if (subscriptionIdIdx != -1 && !cursor.isNull(subscriptionIdIdx)) {
            raw = cursor.getInt(subscriptionIdIdx);
        } else if (simIdIdx != -1 && !cursor.isNull(simIdIdx)) {
            raw = cursor.getInt(simIdIdx);
        }

        if (raw == null || raw < 0) {
            return null;
        }

        Integer mapped = mapSubscriptionIdToSimSlot(context, raw);
        if (mapped != null) {
            return mapped;
        }

        // Common vendor conventions when no subscription mapping is available
        if (raw == 0) {
            return 1;
        }
        if (raw == 1 || raw == 2) {
            return raw;
        }

        // For unsupported/raw subscription IDs, return null instead of a misleading value.
        return null;
    }

    private Integer mapSubscriptionIdToSimSlot(Context context, int subscriptionId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return null;
        }

        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }

            SubscriptionManager subscriptionManager = context.getSystemService(SubscriptionManager.class);
            if (subscriptionManager == null) {
                return null;
            }

            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptions == null || subscriptions.isEmpty()) {
                return null;
            }

            for (SubscriptionInfo info : subscriptions) {
                if (info != null && info.getSubscriptionId() == subscriptionId) {
                    int slotIndex = info.getSimSlotIndex();
                    if (slotIndex >= 0) {
                        return slotIndex + 1;
                    }
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to map subscription ID to SIM slot: permission denied", e);
        } catch (Exception e) {
            Log.w(TAG, "Unable to map subscription ID to SIM slot", e);
        }

        return null;
    }

    private String resolveContactName(Context context, String phoneNumber, Map<String, String> cache) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return null;
        }

        String key = phoneNumber.trim();
        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        String name = null;
        Cursor cursor = null;
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(key));
            cursor = context.getContentResolver().query(
                    uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null,
                    null,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
                if (nameIdx != -1) {
                    name = cursor.getString(nameIdx);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve SMS contact name for number " + key, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        cache.put(key, name);
        return name;
    }

    private Integer mapSmsType(int androidType) {
        if (androidType == Telephony.Sms.MESSAGE_TYPE_INBOX) {
            return 1;
        }
        if (androidType == Telephony.Sms.MESSAGE_TYPE_SENT) {
            return 2;
        }
        return null;
    }

    private Boolean parseSmsLogEnabled(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return null;
        }

        String trimmed = payload.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(trimmed);
            String status = root.path("status").asText();
            if (!"OK".equalsIgnoreCase(status)) {
                return null;
            }

            JsonNode dataNode = root.path("data");
            if (dataNode.isBoolean()) {
                return dataNode.asBoolean(false);
            }
            if (dataNode.isTextual()) {
                return "true".equalsIgnoreCase(dataNode.asText());
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse smslog enabled payload: " + trimmed, e);
            return null;
        }

        return null;
    }
}