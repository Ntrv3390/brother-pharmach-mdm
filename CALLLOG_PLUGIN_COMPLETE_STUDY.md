# Calllog Plugin Complete Study (Brother Pharmamach MDM)

Date: 2026-04-16
Scope: Full source-level study of calllog in hmdm-server, including database schema/migrations, backend APIs, frontend wiring, and cross-project integration files.

## 1) What The Plugin Does

The calllog plugin collects phone call history from Android devices and exposes it to administrators in the web console.

Main capabilities:
- Public endpoint for device upload of call log batches.
- Public endpoint for device check if calllog collection is enabled.
- Private/admin endpoints to list logs, delete per-device logs, and manage settings.
- PostgreSQL persistence with Liquibase migrations.
- AngularJS modal UI from devices page with filters, search, pagination, and delete action.

---

## 2) Complete File Inventory (Every Source File Under `plugins/calllog`)

This list excludes generated build output (`target/`) and includes every source/documentation file present in the plugin tree.

1. hmdm-server/plugins/calllog/ANDROID_INTEGRATION_GUIDE.md
2. hmdm-server/plugins/calllog/API_TESTING_GUIDE.md
3. hmdm-server/plugins/calllog/IMPLEMENTATION_SUMMARY.md
4. hmdm-server/plugins/calllog/STATUS_AND_NEXT_STEPS.md
5. hmdm-server/plugins/calllog/pom.xml
6. hmdm-server/plugins/calllog/core/pom.xml
7. hmdm-server/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/CallLogPluginConfigurationImpl.java
8. hmdm-server/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/guice/module/CallLogLiquibaseModule.java
9. hmdm-server/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/guice/module/CallLogRestModule.java
10. hmdm-server/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/model/CallLogRecord.java
11. hmdm-server/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/model/CallLogSettings.java
12. hmdm-server/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/persistence/CallLogDAO.java
13. hmdm-server/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/persistence/CallLogPersistenceConfiguration.java
14. hmdm-server/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/rest/resource/CallLogPublicResource.java
15. hmdm-server/plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/rest/resource/CallLogResource.java
16. hmdm-server/plugins/calllog/core/src/main/resources/META-INF/services/com.hmdm.plugin.PluginConfiguration
17. hmdm-server/plugins/calllog/core/src/main/resources/liquibase/calllog.changelog.xml
18. hmdm-server/plugins/calllog/postgres/pom.xml
19. hmdm-server/plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/CallLogPostgresDAO.java
20. hmdm-server/plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/CallLogPostgresPersistenceConfiguration.java
21. hmdm-server/plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/guice/module/CallLogPostgresLiquibaseModule.java
22. hmdm-server/plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/guice/module/CallLogPostgresPersistenceModule.java
23. hmdm-server/plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/guice/module/CallLogPostgresServiceModule.java
24. hmdm-server/plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/mapper/CallLogMapper.java
25. hmdm-server/plugins/calllog/postgres/src/main/resources/META-INF/services/com.hmdm.plugins.calllog.persistence.CallLogPersistenceConfiguration
26. hmdm-server/plugins/calllog/postgres/src/main/resources/liquibase/calllog.postgres.changelog.xml
27. hmdm-server/plugins/calllog/src/main/webapp/calllog.module.js
28. hmdm-server/plugins/calllog/src/main/webapp/views/modal.html
29. hmdm-server/plugins/calllog/src/main/webapp/views/settings.html
30. hmdm-server/plugins/calllog/src/main/webapp/i18n/de_DE.json
31. hmdm-server/plugins/calllog/src/main/webapp/i18n/en_US.json
32. hmdm-server/plugins/calllog/src/main/webapp/i18n/es_ES.json
33. hmdm-server/plugins/calllog/src/main/webapp/i18n/fr_FR.json
34. hmdm-server/plugins/calllog/src/main/webapp/i18n/it_IT.json
35. hmdm-server/plugins/calllog/src/main/webapp/i18n/ja_JP.json
36. hmdm-server/plugins/calllog/src/main/webapp/i18n/pt_PT.json
37. hmdm-server/plugins/calllog/src/main/webapp/i18n/ru_RU.json
38. hmdm-server/plugins/calllog/src/main/webapp/i18n/tr_TR.json
39. hmdm-server/plugins/calllog/src/main/webapp/i18n/vi_VN.json
40. hmdm-server/plugins/calllog/src/main/webapp/i18n/zh_CN.json
41. hmdm-server/plugins/calllog/src/main/webapp/i18n/zh_TW.json

---

## 3) Cross-Project Integration Files (Outside `plugins/calllog`)

Every file in hmdm-server that references/wires calllog:

1. hmdm-server/CALLLOG_PLUGIN.md
2. hmdm-server/Dockerfile
3. hmdm-server/docker/entrypoint.sh
4. hmdm-server/hmdm_install.sh
5. hmdm-server/install/context_template.xml
6. hmdm-server/install/sql/hmdm_init.en.sql
7. hmdm-server/install/sql/hmdm_init.ru.sql
8. hmdm-server/plugins/pom.xml
9. hmdm-server/server/build.properties
10. hmdm-server/server/build.properties.example
11. hmdm-server/server/conf/context.xml
12. hmdm-server/server/pom.xml
13. hmdm-server/server/src/main/webapp/WEB-INF/web.xml
14. hmdm-server/server/src/main/webapp/app/components/main/controller/devices.controller.js
15. hmdm-server/server/src/main/webapp/app/components/main/view/devices.html
16. hmdm-server/server/src/main/webapp/app/components/plugins/calllog/views/modal.html
17. hmdm-server/server/src/main/webapp/app/components/plugins/calllog/views/settings.html
18. hmdm-server/server/src/main/webapp/localization/en_US.js
19. hmdm-server/server/src/main/webapp/localization/ru_RU.js

What each group does:
- Build/module inclusion: `plugins/pom.xml`, `server/pom.xml`, `Dockerfile`.
- Runtime DI/persistence config: `server/conf/context.xml`, `server/build.properties`, `server/build.properties.example`, `install/context_template.xml`, `server/src/main/webapp/WEB-INF/web.xml`, `docker/entrypoint.sh`.
- UI entry points: `devices.controller.js`, `devices.html`, localization files.
- Runtime web assets path copy/availability: `server/src/main/webapp/app/components/plugins/calllog/views/*`.
- Operational lifecycle (install/reset): `hmdm_install.sh`, `install/sql/*.sql` (table drop/reset references).

---

## 4) Backend Design (From Source)

### 4.1 Plugin Bootstrap and Dependency Injection

- `CallLogPluginConfigurationImpl` is the plugin entry point (`PLUGIN_ID = "calllog"`).
- It loads:
  - `CallLogLiquibaseModule` (core changelog accessor).
  - Persistence configuration class from servlet context parameter `plugin.calllog.persistence.config.class`.
  - `CallLogRestModule` (binds REST resources + private path auth filters).
- ServiceLoader registration files:
  - `core/src/main/resources/META-INF/services/com.hmdm.plugin.PluginConfiguration`
  - `postgres/src/main/resources/META-INF/services/com.hmdm.plugins.calllog.persistence.CallLogPersistenceConfiguration`

### 4.2 Core Models

- `CallLogRecord`: id, deviceId, phoneNumber, contactName, callType, duration, callTimestamp, callDate, createTime, customerId.
- `CallLogSettings`: enabled, customerId, retentionDays (default 90).

### 4.3 DAO Contract and PostgreSQL Implementation

- Interface: `CallLogDAO` defines all operations.
- Implementation: `CallLogPostgresDAO` delegates to MyBatis mapper and handles insert/update settings logic.
- Mapper package is registered by `CallLogPostgresPersistenceModule`.
- Guice binding `CallLogDAO -> CallLogPostgresDAO` is in `CallLogPostgresServiceModule`.

### 4.4 REST Resources and Security

`CallLogRestModule` applies filters for private routes (`/rest/plugins/calllog/private/*`):
- JWTFilter
- AuthFilter
- PluginAccessFilter
- PrivateIPFilter

Resources bound:
- `CallLogResource` (private/admin)
- `CallLogPublicResource` (public/device)

---

## 5) API Map (All Calllog Endpoints)

Base public path: `/rest/plugins/calllog/public`
Base private path: `/rest/plugins/calllog/private`

### Public APIs (Device-side)

1. `POST /submit/{deviceNumber}`
- Source: `CallLogPublicResource.submitCallLogs`
- Body: JSON array of `CallLogRecord`.
- Behavior:
  - Resolves device by number through `UnsecureDAO`.
  - Checks customer settings from `plugin_calllog_settings`.
  - If disabled: returns OK without insert.
  - If enabled: enriches each record with `deviceId`, `customerId`, `createTime` and inserts batch.
- Errors:
  - `error.device.not.found`
  - `error.internal`

2. `GET /enabled/{deviceNumber}`
- Source: `CallLogPublicResource.isEnabled`
- Returns `Response.OK(boolean)` where boolean defaults to true if settings row does not exist.

### Private APIs (Admin-side)

1. `GET /device/{deviceId}`
- Source: `CallLogResource.getDeviceCallLogs`
- Query params: `page` (default 0), `pageSize` (default 50), optional `callType`, optional `search`.
- Checks device ownership by customer.
- Returns object:
  - `items`
  - `total`
  - `page`
  - `pageSize`

2. `DELETE /device/{deviceId}`
- Source: `CallLogResource.deleteDeviceCallLogs`
- Deletes all rows for that device and customer.
- Returns `{ deletedCount: <int> }`.

3. `GET /settings`
- Source: `CallLogResource.getSettings`
- Returns customer settings.
- If absent, returns defaults (`enabled=true`, `retentionDays=90`).

4. `POST /settings`
- Source: `CallLogResource.saveSettings`
- Admin-only (`super admin` or `org admin`).
- Forces customerId from auth context and upserts row.

---

## 6) Database Study

### 6.1 Migrations

- Core changelog (`core/src/main/resources/liquibase/calllog.changelog.xml`) is currently empty (comment-only marker).
- Actual schema/plugin registration lives in postgres changelog:
  - `postgres/src/main/resources/liquibase/calllog.postgres.changelog.xml`

### 6.2 Tables and Indexes

1. `plugin_calllog_data`
- Columns:
  - `id SERIAL PRIMARY KEY`
  - `deviceid INT NOT NULL`
  - `phonenumber VARCHAR(50)`
  - `contactname VARCHAR(255)`
  - `calltype INT NOT NULL`
  - `duration BIGINT NOT NULL DEFAULT 0`
  - `calltimestamp BIGINT NOT NULL`
  - `calldate VARCHAR(50)`
  - `createtime BIGINT`
  - `customerid INT NOT NULL`
- Indexes:
  - `idx_calllog_device (deviceid, customerid)`
  - `idx_calllog_timestamp (calltimestamp)`
  - `idx_calllog_customer (customerid)`

2. `plugin_calllog_settings`
- Columns:
  - `id SERIAL PRIMARY KEY`
  - `customerid INT NOT NULL UNIQUE`
  - `enabled BOOLEAN NOT NULL DEFAULT TRUE`
  - `retentiondays INT NOT NULL DEFAULT 90`

### 6.3 SQL Access Paths (Mapper)

From `CallLogMapper`:
- Inserts:
  - `insertCallLogRecord`
  - `insertCallLogRecordsBatch`
- Reads:
  - `getCallLogsByDevice`
  - `getCallLogsByDevicePaged`
  - `getCallLogsByDevicePagedFiltered` (optional `callType`, `search` over phone/contact)
  - `getCallLogsCountByDevice`
  - `getCallLogsCountByDeviceFiltered`
- Deletes:
  - `deleteOldCallLogs(customerId, retentionDays)`
  - `deleteCallLogsByDevice(deviceId, customerId)`
- Settings:
  - `getSettings`
  - `insertSettings`
  - `updateSettings`

### 6.4 Plugin Metadata and Permission Registration

In postgres changelog changesets:
- Registers plugin in `plugins` table with these template/module paths:
  - `javascriptmodulefile = app/components/plugins/calllog/calllog.module.js`
  - `functionsviewtemplate = app/components/plugins/calllog/views/modal.html`
  - `settingsviewtemplate = app/components/plugins/calllog/views/settings.html`
- Creates `plugin_calllog_access` permission.
- Grants it to `Super-Admin` role.

---

## 7) Frontend Wiring Study

### 7.1 Plugin Frontend Module

File: `plugins/calllog/src/main/webapp/calllog.module.js`

Contains:
- Angular module `plugin-calllog`.
- `pluginCallLogService` as `$resource` wrapper for four private APIs.
- `PluginCallLogSettingsController` for settings page.
- A modal controller registration on `headwind-kiosk`.

Notable behavior observed:
- Modal currently loads with `pageSize: 1000` in plugin controller implementation.
- Type labels map includes possible type 6 as blocked.

### 7.2 Modal and Settings Views

- `plugins/calllog/src/main/webapp/views/modal.html`
- `plugins/calllog/src/main/webapp/views/settings.html`

Also present in server webapp path (used by devices page template URL):
- `server/src/main/webapp/app/components/plugins/calllog/views/modal.html`
- `server/src/main/webapp/app/components/plugins/calllog/views/settings.html`

### 7.3 Main App Entry Point (Devices Page)

- `server/src/main/webapp/app/components/main/view/devices.html`
  - Adds menu action: `View Call Logs`.
- `server/src/main/webapp/app/components/main/controller/devices.controller.js`
  - Defines `$scope.viewCallLogs(device)`.
  - Opens modal with template path `app/components/plugins/calllog/views/modal.html`.
  - Dynamically resolves `pluginCallLogService` via `$injector`.
  - Includes fallback/mock data branch if service injection fails.
  - Uses backend filtering params (`callType`, `search`) with debounce for search.

### 7.4 Localization Wiring

- Plugin i18n bundle files (12):
  - `de_DE.json`, `en_US.json`, `es_ES.json`, `fr_FR.json`, `it_IT.json`, `ja_JP.json`, `pt_PT.json`, `ru_RU.json`, `tr_TR.json`, `vi_VN.json`, `zh_CN.json`, `zh_TW.json`
- Main UI labels in base app localizations:
  - `server/src/main/webapp/localization/en_US.js`
  - `server/src/main/webapp/localization/ru_RU.js`
  - Key present: `button.view.calllogs`

---

## 8) Build, Deploy, and Runtime Wiring

### 8.1 Maven Module Inclusion

- `plugins/pom.xml` includes `<module>calllog</module>`.
- `server/pom.xml` includes runtime deps:
  - `calllog-core`
  - `calllog-postgres`

### 8.2 Runtime Persistence Class Injection

Persistence class configured via context/init params:
- `server/conf/context.xml`
- `install/context_template.xml`
- `server/src/main/webapp/WEB-INF/web.xml`
- `server/build.properties`
- `server/build.properties.example`
- `docker/entrypoint.sh`

Expected class value:
- `com.hmdm.plugins.calllog.persistence.postgres.CallLogPostgresPersistenceConfiguration`

### 8.3 Docker Build and Runtime

- `Dockerfile` explicitly copies calllog module POMs for build caching/layered dependency resolution.
- `docker/entrypoint.sh` defines `PLUGIN_CALLLOG_CLASS` and writes context parameter at runtime.

### 8.4 Install/Reset Scripts and SQL

- `hmdm_install.sh` includes drop statements for:
  - `plugin_calllog_data`
  - `plugin_calllog_settings`
- `install/sql/hmdm_init.en.sql` and `install/sql/hmdm_init.ru.sql` include reset/drop references that mention calllog tables.

---

## 9) End-to-End Flow (Device -> DB -> API -> UI)

1. Android launcher checks: `GET /rest/plugins/calllog/public/enabled/{deviceNumber}`.
2. Android sends logs: `POST /rest/plugins/calllog/public/submit/{deviceNumber}`.
3. Public resource resolves device, checks customer setting, enriches records, batch inserts.
4. Data persists in `plugin_calllog_data`.
5. Admin opens Devices page and clicks View Call Logs.
6. Modal loads and calls private endpoint `GET /private/device/{deviceId}` with optional filters.
7. Private resource validates auth + customer isolation, returns paged payload.
8. UI renders rows, supports type/search filters, pagination, and delete-all action.
9. Settings page uses `GET/POST /private/settings` to manage enable/retention policy.

---

## 10) Important Observations Found During Study

1. Call type mapping is not fully consistent across comments/UI branches:
- Model comment says `5=blocked`.
- Liquibase and common expectation often include rejected/blocked split.
- Some frontend logic handles type `6=blocked` and type `5=rejected`.

2. Core changelog file is empty; real migration logic is in postgres changelog.

3. Devices controller has a fallback branch and mock data behavior if `pluginCallLogService` injection fails.

4. There are duplicate template files under plugin webapp and server webapp paths; server-side path is used by devices modal template URL.

5. `deleteOldCallLogs` DAO path exists, but this study found no scheduler wiring in calllog module itself for automatic retention execution.

---

## 11) File-to-Responsibility Map (Quick Lookup)

### Database
- Schema and plugin registration: `postgres/src/main/resources/liquibase/calllog.postgres.changelog.xml`
- SQL operations: `postgres/src/main/java/.../mapper/CallLogMapper.java`

### Backend APIs
- Public endpoints: `core/src/main/java/.../rest/resource/CallLogPublicResource.java`
- Private endpoints: `core/src/main/java/.../rest/resource/CallLogResource.java`
- Security filter wiring: `core/src/main/java/.../guice/module/CallLogRestModule.java`

### Persistence and DI
- DAO interface: `core/src/main/java/.../persistence/CallLogDAO.java`
- Postgres DAO: `postgres/src/main/java/.../CallLogPostgresDAO.java`
- DAO binding: `postgres/src/main/java/.../CallLogPostgresServiceModule.java`
- Persistence module: `postgres/src/main/java/.../CallLogPostgresPersistenceModule.java`
- Plugin bootstrap: `core/src/main/java/.../CallLogPluginConfigurationImpl.java`

### Frontend and UI Wiring
- Plugin JS module/service/controllers: `src/main/webapp/calllog.module.js`
- Modal template: `src/main/webapp/views/modal.html`
- Settings template: `src/main/webapp/views/settings.html`
- Devices menu action + modal opening: `server/src/main/webapp/app/components/main/view/devices.html` and `server/src/main/webapp/app/components/main/controller/devices.controller.js`
- Runtime templates under server webapp: `server/src/main/webapp/app/components/plugins/calllog/views/*`

### Build/Deploy Integration
- Plugin module aggregation: `plugins/pom.xml`, `plugins/calllog/pom.xml`, `plugins/calllog/core/pom.xml`, `plugins/calllog/postgres/pom.xml`
- Server runtime dependencies: `server/pom.xml`
- Runtime config class params: `server/conf/context.xml`, `server/build.properties`, `server/build.properties.example`, `install/context_template.xml`, `server/src/main/webapp/WEB-INF/web.xml`, `docker/entrypoint.sh`
- Docker build wiring: `Dockerfile`

---

## 12) Conclusion

This study covered the entire calllog implementation footprint in this workspace:
- Every plugin source/documentation file under `plugins/calllog` is enumerated.
- All known integration and wiring files outside plugin are listed.
- Database schema, SQL operations, backend APIs, security filters, and frontend entry/rendering flow are documented end-to-end.
