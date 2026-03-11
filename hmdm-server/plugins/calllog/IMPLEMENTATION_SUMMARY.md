# Call Log Plugin - Implementation Summary

## Overview
Successfully implemented a comprehensive Call Log plugin for Headwind MDM that allows viewing call history from managed Android devices.

## What Was Implemented

### 1. **Plugin Structure**
- Created a new plugin module following the MDM plugin architecture
- Location: `/plugins/calllog/`
- Structure:
  - `core/` - Database-agnostic code (models, REST endpoints, DAO interfaces)
  - `postgres/` - PostgreSQL-specific implementation (DAO impl, MyBatis mappers)
  - `src/main/webapp/` - Frontend (AngularJS module, views, localization)

### 2. **Database Schema**
Created two main tables via Liquibase migrations:

#### `plugin_calllog_data`
Stores call log records with:
- Device ID
- Phone number
- Contact name
- Call type (incoming=1, outgoing=2, missed=3, rejected=4, blocked=5)
- Duration (in seconds)
- Timestamp
- Customer ID

#### `plugin_calllog_settings`
Plugin settings per customer:
- Enabled/disabled status
- Data retention period (days)

### 3. **Backend APIs**

#### Admin Panel APIs (`/rest/plugins/calllog/private/`)
- `GET /device/{deviceId}` - Get call logs for a device (with pagination)
- `DELETE /device/{deviceId}` - Delete all call logs for a device
- `GET /settings` - Get plugin settings
- `POST /settings` - Save plugin settings (admin only)

#### Android Device APIs (`/rest/plugins/calllog/public/`)
- `POST /submit/{deviceNumber}` - Submit call logs from Android device
- `GET /enabled/{deviceNumber}` - Check if call log collection is enabled

### 4. **User Interface**

#### Devices Page Integration
- Added "View Call Logs" option to the actions dropdown for each device
- Located in: `/server/src/main/webapp/app/components/main/view/devices.html`

#### Call Logs Modal
Features:
- Device information display
- Call log table with:
  - Date/Time
  - Call type (color-coded badges)
  - Phone number
  - Contact name
  - Duration (formatted as hours/minutes/seconds)
- Pagination support
- Delete all logs button
- Localized in English and Russian

#### Settings Page
- Enable/disable call log collection
- Configure data retention period
- Accessible from plugin settings menu

### 5. **Localization**
Added translations for:
- English (`plugin_calllog_localization_en.json`)
- Russian (`plugin_calllog_localization_ru.json`)

Added main localization keys:
- `button.view.calllogs` in both `en_US.js` and `ru_RU.js`

### 6. **Security**
- All admin endpoints protected with JWT authentication
- Permission-based access control
- Customer isolation (users can only see their own data)
- Admin-only settings modification

## Android Call Log Fields Captured

The plugin captures all available Android call log information:

1. **Phone Number** - The number called/calling
2. **Contact Name** - Resolved contact name (if available)
3. **Call Type** - Type of call:
   - Incoming (1)
   - Outgoing (2)
   - Missed (3)
   - Rejected (4)
   - Blocked (5)
4. **Duration** - Call duration in seconds
5. **Timestamp** - When the call occurred (epoch milliseconds)
6. **Date** - Human-readable date string

## Android Integration Requirements

To use this plugin, the Android MDM client needs to:

1. **Read Call Log Permission**
   ```xml
   <uses-permission android:name="android.permission.READ_CALL_LOG" />
   ```

2. **Submit Call Logs**
   - Query Android's CallLog.Calls ContentProvider
   - Format data as JSON array of CallLogRecord objects
   - POST to: `/rest/plugins/calllog/public/submit/{deviceNumber}`

3. **Example Android Code Pattern**
   ```java
   ContentResolver cr = context.getContentResolver();
   Cursor cursor = cr.query(CallLog.Calls.CONTENT_URI, null, null, null, null);
   
   List<CallLogRecord> logs = new ArrayList<>();
   while (cursor.moveToNext()) {
       CallLogRecord log = new CallLogRecord();
       log.setPhoneNumber(cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER)));
       log.setContactName(cursor.getString(cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)));
       log.setCallType(cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE)));
       log.setDuration(cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DURATION)));
       log.setCallTimestamp(cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DATE)));
       logs.add(log);
   }
   cursor.close();
   
   // Submit to server
   httpPost("/rest/plugins/calllog/public/submit/" + deviceNumber, logs);
   ```

## Files Created/Modified

### New Plugin Files
- `/plugins/calllog/pom.xml`
- `/plugins/calllog/core/pom.xml`
- `/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/`
  - `CallLogPluginConfigurationImpl.java`
  - `model/CallLogRecord.java`
  - `model/CallLogSettings.java`
  - `persistence/CallLogDAO.java`
  - `persistence/CallLogPersistenceConfiguration.java`
  - `rest/resource/CallLogResource.java`
  - `rest/resource/CallLogPublicResource.java`
  - `guice/module/CallLogLiquibaseModule.java`
  - `guice/module/CallLogRestModule.java`
- `/plugins/calllog/core/src/main/resources/`
  - `liquibase/calllog.changelog.xml`
  - `META-INF/services/com.hmdm.plugin.PluginConfiguration`
- `/plugins/calllog/postgres/pom.xml`
- `/plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/`
  - `CallLogPostgresDAO.java`
  - `CallLogPostgresPersistenceConfiguration.java`
  - `mapper/CallLogMapper.java`
  - `guice/module/CallLogPostgresPersistenceModule.java`
- `/plugins/calllog/postgres/src/main/resources/`
  - `liquibase/calllog.postgres.changelog.xml`
  - `META-INF/services/com.hmdm.plugins.calllog.persistence.CallLogPersistenceConfiguration`
- `/plugins/calllog/src/main/webapp/app/components/plugins/calllog/`
  - `calllog.module.js`
  - `views/settings.html`
  - `views/modal.html`
  - `localization/plugin_calllog_localization_en.json`
  - `localization/plugin_calllog_localization_ru.json`

### Modified Files
- `/plugins/pom.xml` - Added calllog module
- `/server/src/main/webapp/app/components/main/view/devices.html` - Added "View Call Logs" dropdown option
- `/server/src/main/webapp/app/components/main/controller/devices.controller.js` - Added viewCallLogs function
- `/server/src/main/webapp/localization/en_US.js` - Added button.view.calllogs
- `/server/src/main/webapp/localization/ru_RU.js` - Added button.view.calllogs

## Deployment Status
✅ **Successfully built and deployed to Tomcat**

The plugin is now active and ready to use. Access it by:
1. Navigate to the Devices page
2. Click the three-dot menu (⋮) for any device
3. Select "View Call Logs"

## Next Steps for Full Integration

1. **Configure Android MDM Client**
   - Add READ_CALL_LOG permission
   - Implement call log reading from Android CallLog provider
   - Implement periodic sync to server

2. **Testing**
   - Test with real Android devices
   - Verify call log capture
   - Test pagination with large datasets
   - Test data retention cleanup

3. **Optional Enhancements**
   - Add filtering by date range
   - Add search functionality
   - Export to CSV
   - Call statistics/analytics
   - Real-time sync notifications

## Database Migration
The database tables will be automatically created on first application startup via Liquibase migrations. The plugin will also be registered in the plugins table with appropriate permissions.
