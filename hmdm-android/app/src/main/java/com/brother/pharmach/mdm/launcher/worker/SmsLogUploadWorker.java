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
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Telephony;
import android.util.Log;

import androidx.annotation.NonNull;
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
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.SmsLogRecord;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public SmsLogUploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(SmsLogUploadWorker.class, FIRE_PERIOD_MINS, TimeUnit.MINUTES)
                        .addTag(Const.WORK_TAG_COMMON)
                        .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(WORK_TAG_SMSLOG, ExistingPeriodicWorkPolicy.REPLACE, request);

        // Run once immediately so freshly granted permissions do not wait for the periodic window.
        OneTimeWorkRequest oneTimeRequest =
            new OneTimeWorkRequest.Builder(SmsLogUploadWorker.class)
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
            return Result.success();
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing READ_SMS permission");
            return Result.success();
        }

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        String deviceId = settingsHelper.getDeviceId();
        String serverProject = settingsHelper.getServerProject();
        if (deviceId == null || serverProject == null) {
            return Result.failure();
        }

        ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
        if (serverService == null) {
            return Result.retry();
        }

        try {
            Response<ResponseBody> enabledResponse = serverService.isSmsLogEnabled(serverProject, deviceId).execute();
            if (enabledResponse == null || !enabledResponse.isSuccessful() || enabledResponse.body() == null) {
                ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
                enabledResponse = secondaryServerService.isSmsLogEnabled(serverProject, deviceId).execute();
            }

            if (enabledResponse == null || !enabledResponse.isSuccessful() || enabledResponse.body() == null) {
                return Result.retry();
            }

            String enabledPayload = enabledResponse.body().string();
            Boolean enabled = parseSmsLogEnabled(enabledPayload);
            if (enabled == null) {
                Log.w(TAG, "SMS log enabled endpoint returned invalid payload, scheduling retry");
                return Result.retry();
            }
            if (!enabled) {
                return Result.success();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to check smslog enabled status", e);
            return Result.retry();
        }

        SharedPreferences prefs = context.getSharedPreferences("SmsLogPrefs", Context.MODE_PRIVATE);
        long lastTimestamp = prefs.getLong(PREF_LAST_SMS_TIMESTAMP, 0);

        List<SmsLogRecord> records = new ArrayList<>();
        long maxTimestamp = lastTimestamp;

        String[] projection = {
                Telephony.Sms.ADDRESS,
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE,
                Telephony.Sms.BODY,
                Telephony.Sms.PERSON,
                "sub_id",
                "subscription_id",
                "sim_id"
        };

        String selection = Telephony.Sms.DATE + " > ?";
        String[] selectionArgs = {String.valueOf(lastTimestamp)};
        String sortOrder = Telephony.Sms.DATE + " ASC";

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Telephony.Sms.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder);

            if (cursor != null && cursor.moveToFirst()) {
                int addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
                int typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE);
                int dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE);
                int bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY);
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
                    record.setPhoneNumber(addressIdx != -1 ? cursor.getString(addressIdx) : null);
                    record.setContactName(null);
                    record.setMessageType(messageType);
                    record.setMessageBody(bodyIdx != -1 ? cursor.getString(bodyIdx) : null);
                    record.setSmsTimestamp(timestamp);
                    record.setSmsDate(DATE_FORMAT.format(new Date(timestamp)));
                    record.setSimSlot(resolveSimSlot(cursor, subIdIdx, subscriptionIdIdx, simIdIdx));

                    records.add(record);

                    if (timestamp > maxTimestamp) {
                        maxTimestamp = timestamp;
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading sms log", e);
            return Result.failure();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        Log.i(TAG, "SMS log scan complete: newRows=" + records.size() +
                ", sinceTimestamp=" + lastTimestamp +
                ", maxTimestamp=" + maxTimestamp);

        if (records.isEmpty()) {
            return Result.success();
        }

        ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);

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
                return Result.retry();
            }
        }

        prefs.edit().putLong(PREF_LAST_SMS_TIMESTAMP, uploadedMaxTimestamp).apply();
        Log.i(TAG, "Uploaded " + uploadedCount + " sms log records successfully in batches");
        return Result.success();
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

    private Integer resolveSimSlot(Cursor cursor, int subIdIdx, int subscriptionIdIdx, int simIdIdx) {
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

        if (raw == 0 || raw == 1) {
            return raw + 1;
        }

        return raw;
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