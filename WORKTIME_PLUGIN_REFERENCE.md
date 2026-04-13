# Worktime Plugin Reference (Brother Pharmamach MDM)

## Scope
This document summarizes how the Worktime plugin is implemented across backend, frontend, and database layers, and provides a complete related file inventory for this repository snapshot.

Base plugin path:
- hmdm-server/plugins/worktime

## Functional Summary
The Worktime plugin enforces app access policies by time window and day-of-week.

Current behavior is centered on:
- One global customer policy.
- Per-device overrides (including temporary exception windows).
- Policy delivery to devices through sync hook and public REST endpoints.
- Admin UI for global policy and device exceptions.

## Backend Implementation

### Plugin bootstrap and wiring
- Plugin identifier: worktime
- Root package: com.hmdm.plugins.worktime
- Main plugin config class loads:
  - Liquibase module (core + postgres changelogs).
  - Persistence configuration class from servlet context parameter plugin.worktime.persistence.config.class.
  - REST module and background task module(s).

Key classes:
- WorkTimePluginConfigurationImpl: plugin startup and module assembly.
- WorkTimeRestModule: binds protected/private and public resources, service, sync hook.
- WorkTimePostgresPersistenceConfiguration: assembles postgres persistence + liquibase + task modules.

### Service layer logic
WorkTimeService resolves effective policy using this flow:
1. Load global policy for customer.
2. If global policy absent or disabled, enforcement is off.
3. Load device override.
4. If override is disabled with active exception window, enforcement is off during that window.
5. If exception is expired, override is removed.
6. If enabled override exists, merge override fields with global fallback.
7. Evaluate app allow/deny based on:
   - Time window (supports overnight windows).
   - Day-of-week bitmask (1=Mon, 2=Tue, ..., 64=Sun).
   - Allowed-app sets for during/outside work.

### REST APIs
Private admin APIs (JWT/auth/plugin access guarded):
- GET /rest/plugins/worktime/private/policy
- POST /rest/plugins/worktime/private/policy
- GET /rest/plugins/worktime/private/devices
- POST /rest/plugins/worktime/private/device
- DELETE /rest/plugins/worktime/private/device/{id}

Public device APIs:
- GET /rest/plugins/worktime/public/device/{deviceNumber}/policy
- GET /rest/plugins/worktime/public/device/{deviceNumber}/allowed?pkg=...
- GET /rest/plugins/worktime/public/device/{deviceNumber}/status

Also present (authenticated user-based endpoints):
- GET /rest/plugins/worktime/public/policy/effective/{userId}
- GET /rest/plugins/worktime/public/allowed?userId=...&pkg=...

### Sync integration
WorkTimeSyncResponseHook injects effective policy into sync response custom1 as JSON wrapper:
- pluginId
- timestamp
- policy payload

### Background task
ExpiredExceptionCleanupTask runs periodically (every minute via WorkTimePostgresTaskModule) to:
- Send boundary pushes when exception starts/ends.
- Mark boundary push flags persisted in DB.
- Delete expired exception overrides.

## Database Implementation

### Liquibase strategy
- Core changelog exists but is intentionally empty now.
- Postgres changelog carries actual schema evolution and plugin registration.

### Main tables used
- worktime_global_policy
- worktime_device_override

Legacy migration handled in changelog:
- Renames from worktime_user_override to worktime_device_override.
- Renames user_id to device_id (with truncation step before rename).
- Adds push boundary flags:
  - start_boundary_push_sent
  - end_boundary_push_sent

### Plugin registration in DB
Changelog inserts plugin metadata and permission:
- Plugin identifier: worktime
- JS module file and view template paths.
- Permission: plugin_worktime_access

### SQL mapping
MyBatis mapper XML defines:
- Global policy CRUD.
- Device override CRUD.
- Marker updates for boundary push flags.
- Query for all overrides (background cleanup).

## Frontend Implementation

### Angular module and routes
Defined in worktime.module.js:
- plugin-worktime (main tab route)
- plugin-worktime-policies (global policy view)
- plugin-worktime-devices (device exceptions view)

### UI resources
- worktime_policies.html:
  - Global policy editor (time, days, app allowlists).
  - Group-based selection behavior that applies/removes persistent group-disable overrides.
- worktime_devices.html:
  - Device exception management (create/edit/delete temporary disable windows).
- worktime_policy.html:
  - Legacy modal-style policy form template.

### i18n
- en_US.json and ru_RU.json include plugin labels/messages.
- Some keys still reference older user-override wording, while current implementation is device-override oriented.

## Integration Outside Plugin Folder
These files wire Worktime into server runtime, packaging, deployment, or UI host:

- hmdm-server/plugins/pom.xml
- hmdm-server/server/pom.xml
- hmdm-server/server/build.properties
- hmdm-server/server/build.properties.example
- hmdm-server/server/conf/context.xml
- hmdm-server/install/context_template.xml
- hmdm-server/server/src/main/webapp/WEB-INF/web.xml
- hmdm-server/server/src/main/webapp/app/components/main/controller/tabs.controller.js
- hmdm-server/hmdm_install.sh
- hmdm-server/docker/entrypoint.sh
- hmdm-server/Dockerfile
- hmdm-server/WORKTIME_PLUGIN.md

Additional references mentioning worktime string (not core implementation):
- hmdm-server/plugins/calllog/STATUS_AND_NEXT_STEPS.md

## Complete Worktime Plugin File Inventory
All files below are under hmdm-server/plugins/worktime (excluding generated target output):

- hmdm-server/plugins/worktime/IMPLEMENTATION_COMPLETE.md
- hmdm-server/plugins/worktime/TEST_API.sh
- hmdm-server/plugins/worktime/TEST_REPORT.md
- hmdm-server/plugins/worktime/TEST_SUMMARY.md
- hmdm-server/plugins/worktime/pom.xml
- hmdm-server/plugins/worktime/core/pom.xml
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/WorkTimePluginConfigurationImpl.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/guice/module/WorkTimeLiquibaseModule.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/guice/module/WorkTimeRestModule.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/GlobalWorkTimePolicy.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/WorkTimeDeviceOverride.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/WorkTimePolicy.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/model/WorkTimePolicyDeviceGroup.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/persistence/WorkTimeDAO.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/persistence/WorkTimePersistenceConfiguration.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/rest/resource/WorkTimePublicResource.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/rest/resource/WorkTimeResource.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/service/EffectiveWorkTimePolicy.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/service/WorkTimeService.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/sync/WorkTimeSyncResponseHook.java
- hmdm-server/plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/task/ExpiredExceptionCleanupTask.java
- hmdm-server/plugins/worktime/core/src/main/resources/liquibase/worktime.changelog.xml
- hmdm-server/plugins/worktime/postgres/pom.xml
- hmdm-server/plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/WorkTimePostgresPersistenceConfiguration.java
- hmdm-server/plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/PostgresWorkTimeDAO.java
- hmdm-server/plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/mapper/PostgresWorkTimeMapper.java
- hmdm-server/plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/mapper/PostgresWorkTimeMapper.xml
- hmdm-server/plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/guice/module/WorkTimePostgresLiquibaseModule.java
- hmdm-server/plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/guice/module/WorkTimePostgresPersistenceModule.java
- hmdm-server/plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/guice/module/WorkTimePostgresServiceModule.java
- hmdm-server/plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/guice/module/WorkTimePostgresTaskModule.java
- hmdm-server/plugins/worktime/postgres/src/main/resources/liquibase/worktime.postgres.changelog.xml
- hmdm-server/plugins/worktime/src/main/webapp/i18n/en_US.json
- hmdm-server/plugins/worktime/src/main/webapp/i18n/ru_RU.json
- hmdm-server/plugins/worktime/src/main/webapp/views/worktime_devices.html
- hmdm-server/plugins/worktime/src/main/webapp/views/worktime_policies.html
- hmdm-server/plugins/worktime/src/main/webapp/views/worktime_policy.html
- hmdm-server/plugins/worktime/src/main/webapp/worktime.module.js

## Notes on Build Artifacts
Generated files exist under:
- hmdm-server/plugins/worktime/core/target
- hmdm-server/plugins/worktime/postgres/target

These are build outputs (classes/jars/maven-status) and are not authoritative source files.
