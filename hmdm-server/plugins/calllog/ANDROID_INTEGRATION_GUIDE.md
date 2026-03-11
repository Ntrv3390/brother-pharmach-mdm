# Call Log Plugin - Android Integration Guide

## Overview
This guide shows how to integrate call log collection into the Android MDM client application.

## Prerequisites

### 1. Add Permission to AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.READ_CALL_LOG" />
```

### 2. Request Runtime Permission (Android 6.0+)
```java
if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
        != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(activity,
            new String[]{Manifest.permission.READ_CALL_LOG},
            REQUEST_CALL_LOG_PERMISSION);
}
```

## Implementation

### 1. Create CallLogRecord Model Class

```java
package com.hmdm.launcher.model;

public class CallLogRecord {
    private String phoneNumber;
    private String contactName;
    private int callType; // 1=incoming, 2=outgoing, 3=missed, 4=rejected, 5=blocked
    private long duration; // in seconds
    private long callTimestamp; // epoch milliseconds
    private String callDate;

    // Constructors
    public CallLogRecord() {}

    // Getters and Setters
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public int getCallType() { return callType; }
    public void setCallType(int callType) { this.callType = callType; }

    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }

    public long getCallTimestamp() { return callTimestamp; }
    public void setCallTimestamp(long callTimestamp) { this.callTimestamp = callTimestamp; }

    public String getCallDate() { return callDate; }
    public void setCallDate(String callDate) { this.callDate = callDate; }
}
```

### 2. Read Call Logs from Android System

```java
package com.hmdm.launcher.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.util.Log;

import com.hmdm.launcher.model.CallLogRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CallLogHelper {
    private static final String TAG = "CallLogHelper";
    
    /**
     * Get all call logs from the device
     * @param context Application context
     * @param lastSyncTime Only get logs after this timestamp (0 for all)
     * @return List of call log records
     */
    public static List<CallLogRecord> getCallLogs(Context context, long lastSyncTime) {
        List<CallLogRecord> callLogs = new ArrayList<>();
        
        ContentResolver cr = context.getContentResolver();
        
        // Define columns to query
        String[] projection = new String[] {
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE
        };
        
        // Query only calls after last sync
        String selection = null;
        String[] selectionArgs = null;
        if (lastSyncTime > 0) {
            selection = CallLog.Calls.DATE + " > ?";
            selectionArgs = new String[] { String.valueOf(lastSyncTime) };
        }
        
        // Sort by date descending (newest first)
        String sortOrder = CallLog.Calls.DATE + " DESC";
        
        Cursor cursor = null;
        try {
            cursor = cr.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                int numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
                int typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION);
                int dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE);
                
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                
                do {
                    CallLogRecord record = new CallLogRecord();
                    
                    // Phone number
                    String number = cursor.getString(numberIndex);
                    record.setPhoneNumber(number != null ? number : "Unknown");
                    
                    // Contact name (may be null)
                    String name = cursor.getString(nameIndex);
                    record.setContactName(name);
                    
                    // Call type - map Android types to our types
                    int androidType = cursor.getInt(typeIndex);
                    record.setCallType(mapCallType(androidType));
                    
                    // Duration in seconds
                    long duration = cursor.getLong(durationIndex);
                    record.setDuration(duration);
                    
                    // Timestamp
                    long timestamp = cursor.getLong(dateIndex);
                    record.setCallTimestamp(timestamp);
                    
                    // Formatted date
                    String dateStr = dateFormat.format(new Date(timestamp));
                    record.setCallDate(dateStr);
                    
                    callLogs.add(record);
                    
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading call logs", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        Log.d(TAG, "Retrieved " + callLogs.size() + " call log records");
        return callLogs;
    }
    
    /**
     * Map Android call types to our system types
     * @param androidType Android CallLog.Calls.TYPE_* constant
     * @return Our call type (1-5)
     */
    private static int mapCallType(int androidType) {
        switch (androidType) {
            case CallLog.Calls.INCOMING_TYPE:
                return 1; // Incoming
            case CallLog.Calls.OUTGOING_TYPE:
                return 2; // Outgoing
            case CallLog.Calls.MISSED_TYPE:
                return 3; // Missed
            case CallLog.Calls.REJECTED_TYPE:
                return 4; // Rejected
            case CallLog.Calls.BLOCKED_TYPE:
                return 5; // Blocked
            default:
                return 1; // Default to incoming
        }
    }
}
```

### 3. Submit Call Logs to Server

```java
package com.hmdm.launcher.helper;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.hmdm.launcher.model.CallLogRecord;
import com.hmdm.launcher.util.DeviceIdentifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class CallLogSyncHelper {
    private static final String TAG = "CallLogSync";
    
    /**
     * Submit call logs to server
     * @param context Application context
     * @param serverUrl Base server URL (e.g., "https://mdm.example.com")
     * @param callLogs List of call logs to submit
     * @return true if successful, false otherwise
     */
    public static boolean submitCallLogs(Context context, String serverUrl, List<CallLogRecord> callLogs) {
        if (callLogs == null || callLogs.isEmpty()) {
            Log.d(TAG, "No call logs to submit");
            return true;
        }
        
        HttpURLConnection connection = null;
        try {
            // Get device number
            String deviceNumber = DeviceIdentifier.getDeviceNumber(context);
            
            // Build URL
            String endpoint = serverUrl + "/rest/plugins/calllog/public/submit/" + deviceNumber;
            URL url = new URL(endpoint);
            
            // Convert call logs to JSON
            Gson gson = new Gson();
            String jsonData = gson.toJson(callLogs);
            
            Log.d(TAG, "Submitting " + callLogs.size() + " call logs to " + endpoint);
            
            // Setup connection
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            
            // Send data
            OutputStream os = connection.getOutputStream();
            os.write(jsonData.getBytes("UTF-8"));
            os.flush();
            os.close();
            
            // Check response
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Server response code: " + responseCode);
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Read response
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();
                
                Log.d(TAG, "Call logs submitted successfully: " + response.toString());
                return true;
            } else {
                Log.e(TAG, "Failed to submit call logs. Response code: " + responseCode);
                return false;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error submitting call logs", e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * Check if call log collection is enabled on the server
     * @param context Application context
     * @param serverUrl Base server URL
     * @return true if enabled, false otherwise
     */
    public static boolean isCallLogEnabled(Context context, String serverUrl) {
        HttpURLConnection connection = null;
        try {
            String deviceNumber = DeviceIdentifier.getDeviceNumber(context);
            String endpoint = serverUrl + "/rest/plugins/calllog/public/enabled/" + deviceNumber;
            URL url = new URL(endpoint);
            
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();
                
                // Parse response to check if enabled
                JSONObject json = new JSONObject(response.toString());
                if (json.has("data")) {
                    return json.getBoolean("data");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking call log status", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return false;
    }
}
```

### 4. Schedule Periodic Sync

```java
package com.hmdm.launcher.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.hmdm.launcher.helper.CallLogHelper;
import com.hmdm.launcher.helper.CallLogSyncHelper;
import com.hmdm.launcher.model.CallLogRecord;

import java.util.List;

public class CallLogSyncService {
    private static final String TAG = "CallLogSyncService";
    private static final String PREF_NAME = "CallLogSync";
    private static final String KEY_LAST_SYNC = "lastSyncTime";
    
    private Context context;
    private String serverUrl;
    
    public CallLogSyncService(Context context, String serverUrl) {
        this.context = context;
        this.serverUrl = serverUrl;
    }
    
    /**
     * Sync call logs with server
     */
    public void syncCallLogs() {
        Log.d(TAG, "Starting call log sync");
        
        try {
            // Check if call log collection is enabled
            if (!CallLogSyncHelper.isCallLogEnabled(context, serverUrl)) {
                Log.d(TAG, "Call log collection is disabled on server");
                return;
            }
            
            // Get last sync time
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            long lastSyncTime = prefs.getLong(KEY_LAST_SYNC, 0);
            
            // Get call logs since last sync
            List<CallLogRecord> callLogs = CallLogHelper.getCallLogs(context, lastSyncTime);
            
            if (callLogs.isEmpty()) {
                Log.d(TAG, "No new call logs to sync");
                return;
            }
            
            // Submit to server
            boolean success = CallLogSyncHelper.submitCallLogs(context, serverUrl, callLogs);
            
            if (success) {
                // Update last sync time
                long currentTime = System.currentTimeMillis();
                prefs.edit().putLong(KEY_LAST_SYNC, currentTime).apply();
                Log.d(TAG, "Call log sync completed successfully");
            } else {
                Log.e(TAG, "Call log sync failed");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error during call log sync", e);
        }
    }
}
```

### 5. Integrate into Main Service

Add to your existing MDM sync service:

```java
// In your periodic sync task (e.g., every 1 hour)
CallLogSyncService callLogSync = new CallLogSyncService(context, serverUrl);
callLogSync.syncCallLogs();
```

## Usage Example

```java
// In your MainActivity or main service
public void initCallLogSync() {
    // Check permission
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            == PackageManager.PERMISSION_GRANTED) {
        
        // Schedule periodic sync (every hour)
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, CallLogSyncReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        
        // Trigger every hour
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis(),
            AlarmManager.INTERVAL_HOUR,
            pendingIntent
        );
    } else {
        // Request permission
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.READ_CALL_LOG},
            REQUEST_CALL_LOG_PERMISSION);
    }
}
```

## Testing

1. **Test Call Log Reading**
   ```java
   List<CallLogRecord> logs = CallLogHelper.getCallLogs(context, 0);
   Log.d(TAG, "Found " + logs.size() + " call logs");
   for (CallLogRecord log : logs) {
       Log.d(TAG, log.getPhoneNumber() + " - " + log.getCallType());
   }
   ```

2. **Test Server Submission**
   ```java
   boolean success = CallLogSyncHelper.submitCallLogs(context, serverUrl, logs);
   Log.d(TAG, "Submission " + (success ? "successful" : "failed"));
   ```

## Best Practices

1. **Incremental Sync** - Only sync new call logs since last sync
2. **Batch Size** - If you have many logs, consider batching (e.g., 100 at a time)
3. **Error Handling** - Retry failed syncs
4. **Battery Optimization** - Sync during existing network operations
5. **Privacy** - Respect user privacy settings and permissions
6. **Data Usage** - Only sync on Wi-Fi if configured

## Notes

- Call logs are read-only; MDM cannot modify them
- Android 9+ requires READ_CALL_LOG permission (previously included in READ_PHONE_STATE)
- Some manufacturers may restrict call log access
- Consider user privacy and compliance requirements (GDPR, etc.)
