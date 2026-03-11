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

package com.brother.pharmach.mdm.launcher.worker;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.CallLogRecord;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class CallLogUploadWorker extends Worker {

    private static final String TAG = "CallLogUploadWorker";
    private static final String PREF_LAST_CALL_TIMESTAMP = "last_call_log_timestamp";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public CallLogUploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing READ_CALL_LOG permission");
            return Result.failure();
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

        // 1. Check if enabled
        try {
            Response<ResponseBody> enabledResponse = serverService.isCallLogEnabled(serverProject, deviceId).execute();
            if (enabledResponse == null || !enabledResponse.isSuccessful() || enabledResponse.body() == null) {
                ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
                enabledResponse = secondaryServerService.isCallLogEnabled(serverProject, deviceId).execute();
            }

            if (enabledResponse == null || !enabledResponse.isSuccessful() || enabledResponse.body() == null) {
                return Result.retry();
            }

            String enabledPayload = enabledResponse.body().string();
            Boolean enabled = parseCallLogEnabled(enabledPayload);
            if (enabled == null) {
                Log.w(TAG, "Call log enabled endpoint returned invalid payload, scheduling retry");
                return Result.retry();
            }

            if (!enabled) {
                return Result.success();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to check enabled status", e);
            return Result.retry();
        }

        // 2. Read new logs
        SharedPreferences prefs = context.getSharedPreferences("CallLogPrefs", Context.MODE_PRIVATE);
        long lastTimestamp = prefs.getLong(PREF_LAST_CALL_TIMESTAMP, 0);

        List<CallLogRecord> records = new ArrayList<>();
        long maxTimestamp = lastTimestamp;

        String selection = CallLog.Calls.DATE + " > ?";
        String[] selectionArgs = {String.valueOf(lastTimestamp)};
        String sortOrder = CallLog.Calls.DATE + " ASC";

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    null,
                    selection,
                    selectionArgs,
                    sortOrder);

            if (cursor != null && cursor.moveToFirst()) {
                int numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE);
                int durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);
                int nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);

                do {
                    String number = cursor.getString(numberIdx);
                    int type = cursor.getInt(typeIdx);
                    long date = cursor.getLong(dateIdx);
                    long duration = cursor.getLong(durationIdx);
                    String name = (nameIdx != -1) ? cursor.getString(nameIdx) : null;

                    CallLogRecord record = new CallLogRecord();
                    record.setPhoneNumber(number);
                    record.setCallType(type);
                    record.setCallTimestamp(date);
                    record.setDuration(duration);
                    record.setContactName(name);

                    records.add(record);

                    if (date > maxTimestamp) {
                        maxTimestamp = date;
                    }

                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading call log", e);
            return Result.failure();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        Log.i(TAG, "Call log scan complete: newRows=" + records.size() +
                ", sinceTimestamp=" + lastTimestamp +
                ", maxTimestamp=" + maxTimestamp);

        if (records.isEmpty()) {
            Log.i(TAG, "No new call log records to upload");
            return Result.success();
        }

        // 3. Upload
        try {
            Response<ResponseBody> response = serverService.uploadCallLogs(serverProject, deviceId, records).execute();
            if (response.isSuccessful()) {
                prefs.edit().putLong(PREF_LAST_CALL_TIMESTAMP, maxTimestamp).apply();
                Log.i(TAG, "Uploaded " + records.size() + " call log records successfully");
                return Result.success();
            } else {
                Log.w(TAG, "Call log upload failed, HTTP status=" + response.code());
                return Result.retry();
            }
        } catch (IOException e) {
            Log.e(TAG, "Upload failed", e);
            return Result.retry();
        }
    }

    private Boolean parseCallLogEnabled(String payload) {
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
            Log.w(TAG, "Failed to parse calllog enabled payload: " + trimmed, e);
            return null;
        }

        return null;
    }
}
