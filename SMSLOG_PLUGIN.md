# SMS Log Plugin - Complete Technical Documentation

## Table of Contents

1. Overview
2. Purpose and Features
3. Architecture
4. Directory Structure
5. Core Backend Components
6. Database Schema
7. REST API Endpoints
8. Frontend Integration
9. Build and Module Wiring
10. Security and Tenant Isolation
11. Android Integration Notes
12. Known Gaps and Recommendations

---

## 1) Overview

The SMS Log Plugin is a Headwind MDM server plugin that collects SMS metadata/content from managed Android devices and exposes it in the admin panel.

Plugin metadata:

- Plugin ID: `smslog`
- Package root: `com.hmdm.plugins.smslog`
- Plugin version (module): `0.1.0`
- Server root path: `hmdm-server/plugins/smslog/`

Primary capabilities:

- Device-side SMS batch upload API
- Admin-side SMS browsing with filters and pagination
- Per-customer settings (`enabled`, `retentionDays`)
- Per-device bulk deletion
- Multi-tenant data isolation by `customerId`

---

## 2) Purpose and Features

The plugin is designed for environments where communication visibility is required on managed devices.

Main features:

- SMS ingestion endpoint for Android clients
- Read-only reporting for administrators in device modal
- Server-side filtering by:
  - Message type (`incoming`/`outgoing`)
  - SIM slot
  - Free text search on phone/contact/message body
- Configurable retention window (days)
- Plugin enable/disable per customer

Message type mapping in current backend model:

- `1`: incoming
- `2`: outgoing

---

## 3) Architecture

The plugin follows the standard Headwind plugin split:

- `core`: DB-agnostic models, DAO contract, REST resources, Guice wiring
- `postgres`: concrete persistence implementation and Liquibase schema
- `webapp`: AngularJS module, modal/settings templates, i18n bundles

Request flow:

1. Android device submits logs to public REST endpoint.
2. Server resolves device by `deviceNumber`.
3. Server stamps each record with `deviceId`, `customerId`, `createTime`.
4. DAO performs batch insert into `plugin_smslog_data`.
5. Admin UI requests filtered rows from private endpoints.

---

## 4) Directory Structure

```text
hmdm-server/
├── SMSLOG_PLUGIN.md                                  # this document
└── plugins/
    └── smslog/
        ├── pom.xml
        ├── ANDROID_INTEGRATION_GUIDE.md
        ├── API_TESTING_GUIDE.md
        ├── IMPLEMENTATION_SUMMARY.md
        ├── STATUS_AND_NEXT_STEPS.md
        ├── core/
        │   ├── pom.xml
        │   └── src/main/
        │       ├── java/com/hmdm/plugins/smslog/
        │       │   ├── SmsLogPluginConfigurationImpl.java
        │       │   ├── guice/module/
        │       │   │   ├── SmsLogLiquibaseModule.java
        │       │   │   └── SmsLogRestModule.java
        │       │   ├── model/
        │       │   │   ├── SmsLogRecord.java
        │       │   │   └── SmsLogSettings.java
        │       │   ├── persistence/
        │       │   │   ├── SmsLogDAO.java
        │       │   │   └── SmsLogPersistenceConfiguration.java
        │       │   └── rest/resource/
        │       │       ├── SmsLogPublicResource.java
        │       │       └── SmsLogResource.java
        │       └── resources/
        │           ├── META-INF/services/com.hmdm.plugin.PluginConfiguration
        │           └── liquibase/smslog.changelog.xml
        ├── postgres/
        │   ├── pom.xml
        │   └── src/main/
        │       ├── java/com/hmdm/plugins/smslog/persistence/postgres/
        │       │   ├── SmsLogPostgresDAO.java
        │       │   ├── SmsLogPostgresPersistenceConfiguration.java
        │       │   ├── guice/module/
        │       │   │   ├── SmsLogPostgresLiquibaseModule.java
        │       │   │   ├── SmsLogPostgresPersistenceModule.java
        │       │   │   └── SmsLogPostgresServiceModule.java
        │       │   └── mapper/SmsLogMapper.java
        │       └── resources/
        │           ├── META-INF/services/com.hmdm.plugins.smslog.persistence.SmsLogPersistenceConfiguration
        │           └── liquibase/smslog.postgres.changelog.xml
        └── src/main/webapp/
            ├── smslog.module.js
            ├── i18n/*.json (12 locales)
            └── views/
                ├── modal.html
                └── settings.html
```

Also integrated into server web app under:

- `hmdm-server/server/src/main/webapp/app/components/plugins/smslog/`
- `hmdm-server/server/src/main/webapp/app/components/main/controller/devices.controller.js`
- `hmdm-server/server/src/main/webapp/app/components/main/view/devices.html`

---

## 5) Core Backend Components

### 5.1 Plugin Bootstrap

File: `plugins/smslog/core/src/main/java/com/hmdm/plugins/smslog/SmsLogPluginConfigurationImpl.java`

Responsibilities:

- exposes plugin ID `smslog`
- installs core Liquibase module
- dynamically loads persistence configuration from servlet init parameter:
  - `plugin.smslog.persistence.config.class`
- installs REST module

### 5.2 Models

#### `SmsLogRecord`

File: `plugins/smslog/core/src/main/java/com/hmdm/plugins/smslog/model/SmsLogRecord.java`

Fields:

- `id` (Integer)
- `deviceId` (int)
- `phoneNumber` (String)
- `contactName` (String)
- `messageType` (int)
- `messageBody` (String)
- `simSlot` (Integer)
- `smsTimestamp` (long)
- `smsDate` (String)
- `createTime` (Long)
- `customerId` (int)

#### `SmsLogSettings`

File: `plugins/smslog/core/src/main/java/com/hmdm/plugins/smslog/model/SmsLogSettings.java`

Fields:

- `enabled` (boolean, default `true`)
- `customerId` (int)
- `retentionDays` (int, default `90`, `0` means keep forever)

Note: JavaDoc text still says "call log" in this class; behavior/fields are for SMS settings.

### 5.3 DAO Contract and Implementation

Contract file:

- `plugins/smslog/core/src/main/java/com/hmdm/plugins/smslog/persistence/SmsLogDAO.java`

Implementation file:

- `plugins/smslog/postgres/src/main/java/com/hmdm/plugins/smslog/persistence/postgres/SmsLogPostgresDAO.java`

Key methods:

- insert:
  - `insertSmsLogRecord(...)`
  - `insertSmsLogRecordsBatch(...)`
- reads:
  - `getSmsLogsByDevice(...)`
  - `getSmsLogsByDevicePaged(...)`
  - `getSmsLogsByDevicePagedFiltered(...)`
  - `getSmsLogsCountByDevice(...)`
  - `getSmsLogsCountByDeviceFiltered(...)`
- deletes:
  - `deleteOldSmsLogs(customerId, retentionDays)`
  - `deleteSmsLogsByDevice(deviceId, customerId)`
- settings:
  - `getSettings(customerId)`
  - `saveSettings(settings)` (insert/update)

Retention behavior:

- if `retentionDays <= 0`, DAO returns `0` and skips cleanup
- otherwise deletes by `createtime` cutoff generated in SQL

### 5.4 SQL Mapper

File: `plugins/smslog/postgres/src/main/java/com/hmdm/plugins/smslog/persistence/postgres/mapper/SmsLogMapper.java`

Highlights:

- batch insert using MyBatis `<foreach>`
- filters are dynamic (`messageType`, `simSlot`, `search`)
- search applies case-insensitive `LIKE` over:
  - `phonenumber`
  - `contactname`
  - `messagebody`
- pagination via `LIMIT/OFFSET`

---

## 6) Database Schema

Schema is defined in:

- `plugins/smslog/postgres/src/main/resources/liquibase/smslog.postgres.changelog.xml`

Core changelog entry point (empty by design):

- `plugins/smslog/core/src/main/resources/liquibase/smslog.changelog.xml`

### 6.1 Table `plugin_smslog_data`

Columns:

- `id SERIAL PRIMARY KEY`
- `deviceid INT NOT NULL`
- `phonenumber VARCHAR(50)`
- `contactname VARCHAR(255)`
- `messagetype INT NOT NULL`
- `messagebody TEXT`
- `messagesimslot INT`
- `smstimestamp BIGINT NOT NULL`
- `smsdate VARCHAR(50)`
- `createtime BIGINT`
- `customerid INT NOT NULL`

Indexes:

- `idx_smslog_device(deviceid, customerid)`
- `idx_smslog_timestamp(smstimestamp)`
- `idx_smslog_simslot(messagesimslot)`
- `idx_smslog_customer(customerid)`

### 6.2 Table `plugin_smslog_settings`

Columns:

- `id SERIAL PRIMARY KEY`
- `customerid INT NOT NULL UNIQUE`
- `enabled BOOLEAN NOT NULL DEFAULT TRUE`
- `retentiondays INT NOT NULL DEFAULT 90`

### 6.3 Plugin Registration Records

The changelog also inserts rows into existing platform tables:

- `plugins`
- `permissions`
- `userrolepermissions`

Registered permission:

- `plugin_smslog_access`

UI templates and module registered in `plugins` row:

- JS module: `app/components/plugins/smslog/smslog.module.js`
- functions view: `app/components/plugins/smslog/views/modal.html`
- settings view: `app/components/plugins/smslog/views/settings.html`

---

## 7) REST API Endpoints

### 7.1 Public (Device) API

Base path: `/rest/plugins/smslog/public`

File:

- `plugins/smslog/core/src/main/java/com/hmdm/plugins/smslog/rest/resource/SmsLogPublicResource.java`

Endpoints:

1. `POST /submit/{deviceNumber}`
   - body: `List<SmsLogRecord>`
   - behavior:
     - resolve device by number
     - check customer settings (`enabled`)
     - stamp `deviceId`, `customerId`, `createTime`
     - batch insert logs
   - returns `Response.OK()` or `Response.ERROR(...)`

2. `GET /enabled/{deviceNumber}`
   - returns `Response.OK(boolean)`
   - `true` when no settings row exists or when `enabled=true`

### 7.2 Private (Admin) API

Base path: `/rest/plugins/smslog/private`

File:

- `plugins/smslog/core/src/main/java/com/hmdm/plugins/smslog/rest/resource/SmsLogResource.java`

Endpoints:

1. `GET /device/{deviceId}`
   - query params:
     - `page` (default `0`)
     - `pageSize` (default `50`)
     - `messageType` (optional)
     - `simSlot` (optional)
     - `search` (optional)
   - validates device ownership by customer
   - returns:
     - `items`
     - `total`
     - `page`
     - `pageSize`

2. `GET /settings`
   - returns customer settings
   - if absent, returns defaults (`enabled=true`, `retentionDays=90`)

3. `POST /settings`
   - admin-only write operation
   - allowed for super admin or org admin
   - upserts settings for current customer

4. `DELETE /device/{deviceId}`
   - deletes all SMS logs for a device for current customer
   - returns deleted row count

---

## 8) Frontend Integration

### 8.1 Plugin Angular Module

File:

- `plugins/smslog/src/main/webapp/smslog.module.js`

Core pieces:

- module name: `plugin-smslog`
- resource service: `pluginSmsLogService`
- settings controller: `PluginSmsLogSettingsController`
- device modal controller: `PluginSmsLogModalController`
- i18n bundle load: `localization.loadPluginResourceBundles("smslog")`

Modal behavior:

- loads logs for selected device
- supports dynamic filter dropdowns for message type and SIM slot
- debounced text search (`400ms`)
- pagination model with next/previous
- allows "Delete All" operation

### 8.2 Plugin Views

Files:

- `plugins/smslog/src/main/webapp/views/modal.html`
- `plugins/smslog/src/main/webapp/views/settings.html`

UI features:

- modal shows device summary and SMS table
- visual labels for incoming/outgoing messages
- filter controls and pagination controls
- settings panel for enable flag and retention days (0..365)

### 8.3 Main Devices Page Hook

Files:

- `server/src/main/webapp/app/components/main/view/devices.html`
- `server/src/main/webapp/app/components/main/controller/devices.controller.js`

Integration points:

- devices row dropdown includes "View SMS Logs"
- click handler opens plugin modal (`PluginSmsLogModalController`)

### 8.4 Runtime Webapp Copy

The plugin web assets are available at runtime under:

- `server/src/main/webapp/app/components/plugins/smslog/`

This path matches the value stored in plugin registration changelog.

---

## 9) Build and Module Wiring

### Maven Modules

- Parent plugin list includes `smslog` in:
  - `plugins/pom.xml`
- Plugin aggregator:
  - `plugins/smslog/pom.xml`
- Submodules:
  - `plugins/smslog/core`
  - `plugins/smslog/postgres`

### ServiceLoader Registration

Core plugin configuration service file:

- `plugins/smslog/core/src/main/resources/META-INF/services/com.hmdm.plugin.PluginConfiguration`
  - value: `com.hmdm.plugins.smslog.SmsLogPluginConfigurationImpl`

Persistence configuration service file:

- `plugins/smslog/postgres/src/main/resources/META-INF/services/com.hmdm.plugins.smslog.persistence.SmsLogPersistenceConfiguration`
  - value: `com.hmdm.plugins.smslog.persistence.postgres.SmsLogPostgresPersistenceConfiguration`

---

## 10) Security and Tenant Isolation

File:

- `plugins/smslog/core/src/main/java/com/hmdm/plugins/smslog/guice/module/SmsLogRestModule.java`

Private endpoints are protected by:

- `JWTFilter`
- `AuthFilter`
- `PluginAccessFilter`
- `PrivateIPFilter`

Tenant boundary enforcement:

- private API checks `device.customerId == currentUser.customerId`
- mapper queries include `customerid` in predicates
- settings are keyed by unique `customerid`

Role check for settings update:

- allowed only for super admin or organization admin

Public endpoints:

- no JWT (intended for device uploads)
- still resolve device and derive customer from server-side device mapping

---

## 11) Android Integration Notes

There is already an Android integration guide in plugin folder:

- `plugins/smslog/ANDROID_INTEGRATION_GUIDE.md`

Android implementation is now added in `hmdm-android`.

Implemented files:

- `hmdm-android/app/src/main/java/com/brother/pharmach/mdm/launcher/json/SmsLogRecord.java`
- `hmdm-android/app/src/main/java/com/brother/pharmach/mdm/launcher/worker/SmsLogUploadWorker.java`
- `hmdm-android/app/src/main/java/com/brother/pharmach/mdm/launcher/server/ServerService.java`
- `hmdm-android/app/src/main/java/com/brother/pharmach/mdm/launcher/helper/Initializer.java`
- `hmdm-android/app/src/main/java/com/brother/pharmach/mdm/launcher/ui/MainActivity.java`
- `hmdm-android/app/src/main/AndroidManifest.xml`

Current Android flow:

1. Periodic worker `SmsLogUploadWorker` is scheduled at startup from `Initializer` (15-minute interval).
2. Worker checks `READ_SMS` permission.
3. Worker checks plugin enabled status using `GET /rest/plugins/smslog/public/enabled/{deviceNumber}`.
4. Worker reads new rows from `Telephony.Sms.CONTENT_URI` using last synced timestamp from `SharedPreferences`.
5. Worker maps SMS types to server format (`inbox -> 1`, `sent -> 2`) and uploads batch to `POST /rest/plugins/smslog/public/submit/{deviceNumber}`.
6. On successful upload, last synced timestamp is updated.

Current endpoint contract for Android implementation should align with:

- `GET /rest/plugins/smslog/public/enabled/{deviceNumber}`
- `POST /rest/plugins/smslog/public/submit/{deviceNumber}` with `List<SmsLogRecord>` payload

Recommended client sync sequence:

1. Check `enabled` endpoint.
2. Query device SMS provider since last successful timestamp.
3. Transform rows to server JSON fields (`phoneNumber`, `messageType`, `messageBody`, `simSlot`, `smsTimestamp`, `smsDate`).
4. Submit as batch.

---

## 12) Known Gaps and Recommendations

Observed from source review:

1. Copy/paste naming artifacts exist in comments/docs.
   - Example: Some comments still say "call log" in SMS classes/modules.

2. `plugins/smslog/ANDROID_INTEGRATION_GUIDE.md` currently contains call-log API examples (`READ_CALL_LOG`, `CallLog`-style model/flow), which do not match SMS collection behavior.

3. Frontend modal uses very large default page size (`1000`) with server-side filtering; this is acceptable for small/medium datasets but may need tuning for very high-volume tenants.

Recommended follow-up:

1. Correct Android guide to actual SMS APIs (`READ_SMS`, Telephony SMS provider fields).
2. Clean comment text in Java classes/modules to reduce confusion.
3. Optionally lower default page size and rely on pagination for large deployments.
4. Evaluate Play Protect/compliance impact of `READ_SMS` for your distribution channel and target regions.

---

## Calllog Android Documentation Check

Per request, calllog Android-only docs were checked before creating new files.

Already present:

- `plugins/calllog/ANDROID_INTEGRATION_GUIDE.md`
- root technical study in `CALLLOG_PLUGIN.md` includes Android integration section

Therefore, no additional calllog Android documentation file was created.
