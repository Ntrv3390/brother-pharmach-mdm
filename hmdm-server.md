# hmdm-server Documentation

## 1. Overview

`hmdm-server` is a Java-based, multi-module backend and web control panel for Headwind MDM (Android device management). It combines:

- REST API services for enrollment, sync, provisioning, configuration, updates, and administration.
- A web control panel (AngularJS frontend served from the `server` WAR module).
- A plugin platform with multiple optional features (audit, messaging, call logs, device telemetry, work-time policies, etc.).
- PostgreSQL persistence with Liquibase schema migration and MyBatis data mapping.

Primary tech stack:

- Java 8
- Maven multi-module build
- Jersey (JAX-RS)
- Google Guice DI
- MyBatis
- Liquibase
- PostgreSQL
- Apache Tomcat
- AngularJS (web panel)

---

## 2. Repository Scope and Scan Notes

A full repository scan was performed over `hmdm-server/`.

Approximate file statistics in this workspace snapshot:

- Total files: **19188**
- Java files: **391**
- XML files: **115**
- JavaScript files: **12276**
- HTML files: **366**
- Markdown/TXT files: **1100**
- SQL files: **5**
- Shell scripts: **19**

These counts include generated/vendor/static assets (for example web libraries, test artifacts, and other non-core files). The documentation below focuses on meaningful source, config, migration, build, and operations files.

---

## 3. Top-Level Structure

`hmdm-server/` contains:

- `common/` — shared domain, DAO interfaces/implementations, utilities, security primitives.
- `jwt/` — JWT token generation/filtering/auth resources.
- `notification/` — notification transport engine (MQTT/ActiveMQ/long polling).
- `plugins/` — plugin platform and plugin implementations.
- `server/` — primary WAR application (REST + UI + startup wiring).
- `swagger/ui/` — standalone Swagger UI WAR.
- `install/` and root scripts — deployment templates, install and ops automation.
- Root documentation (`README.md`, `BUILD.txt`, `INSTALL.txt`, plugin docs, troubleshooting docs).

---

## 4. Build and Runtime Architecture

### 4.1 Maven structure

Root descriptor: `hmdm-server/pom.xml`

Declared root modules:

- `common`
- `jwt`
- `notification`
- `plugins`
- `swagger/ui`
- `server`

### 4.2 Runtime boot flow

1. WAR module `server` is deployed in Tomcat (final WAR name: `launcher`).
2. Servlet and filters are wired via `server/src/main/webapp/WEB-INF/web.xml`.
3. `com.hmdm.HMDMApplication` configures Jersey/Swagger REST context.
4. `com.hmdm.guice.Initializer` builds Guice injector and registers modules.
5. DB migrations are applied via Liquibase modules/changelogs.
6. REST resources become available under `.../rest/...` paths.

### 4.3 Core wiring files

- `server/src/main/java/com/hmdm/HMDMApplication.java`
- `server/src/main/java/com/hmdm/guice/Initializer.java`
- `server/src/main/webapp/WEB-INF/web.xml`
- `server/src/main/java/com/hmdm/guice/module/*.java`
- `server/src/main/resources/liquibase/db.changelog.xml`

### 4.4 Configuration model

Key runtime config sources:

- `server/build.properties.example` (template for local/dev values)
- `server/conf/context.xml` (Tomcat context params, plugin persistence class references)
- `install/context_template.xml` (production installer context template)
- `install/log4j_template.xml` and `server/src/main/resources/log4j.xml` (logging)

---

## 5. Module-by-Module Documentation

## 5.1 common

Location: `hmdm-server/common/`

Purpose: shared core library consumed by `server`, `jwt`, `notification`, and plugins.

Key package responsibilities:

- `com.hmdm.persistence` — DAOs and persistence services for core entities (users, devices, groups, configurations, applications, files, etc.).
- `com.hmdm.persistence.domain` — domain models and enums.
- `com.hmdm.persistence.mapper` — MyBatis mapper interfaces/XML.
- `com.hmdm.rest.filter` — auth/origin/IP/HSTS filters.
- `com.hmdm.security` — security context and exceptions.
- `com.hmdm.service` — business helpers (email, device status, file upload, RSA key, etc.).
- `com.hmdm.util` — utilities (APK analysis, crypto, background tasks, file/string helpers).
- `com.hmdm.event` — event model and listener/service infrastructure.

Representative files:

- `common/pom.xml`
- `common/src/main/java/com/hmdm/persistence/CommonDAO.java`
- `common/src/main/java/com/hmdm/persistence/DeviceDAO.java`
- `common/src/main/java/com/hmdm/rest/filter/AuthFilter.java`
- `common/src/main/java/com/hmdm/service/EmailService.java`
- `common/src/main/java/com/hmdm/util/APKFileAnalyzer.java`

## 5.2 jwt

Location: `hmdm-server/jwt/`

Purpose: JWT auth module for issuing and validating tokens.

Representative files:

- `jwt/pom.xml`
- `jwt/src/main/java/com/hmdm/security/jwt/TokenProvider.java`
- `jwt/src/main/java/com/hmdm/security/jwt/JWTFilter.java`
- `jwt/src/main/java/com/hmdm/security/jwt/rest/JWTAuthResource.java`
- `jwt/src/main/java/com/hmdm/security/jwt/rest/JWTToken.java`

## 5.3 notification

Location: `hmdm-server/notification/`

Purpose: push/notification pipeline for device communication.

Main components:

- Push sender strategies (polling + MQTT).
- Notification REST endpoints and long-polling servlet.
- DAO + mappers for pending push records.
- Guice modules for engine selection and persistence.
- Liquibase changelog for notification tables.

Representative files:

- `notification/pom.xml`
- `notification/src/main/java/com/hmdm/notification/PushService.java`
- `notification/src/main/java/com/hmdm/notification/PushSenderMqtt.java`
- `notification/src/main/java/com/hmdm/notification/rest/NotificationResource.java`
- `notification/src/main/java/com/hmdm/notification/rest/LongPollingServlet.java`
- `notification/src/main/resources/liquibase/notification.changelog.xml`

## 5.4 plugins

Location: `hmdm-server/plugins/`

Purpose: extension system. Includes plugin platform and plugin modules.

Top-level plugin parent descriptor:

- `plugins/pom.xml`

Contained plugin modules (workspace snapshot):

- `platform`
- `devicelog`
- `audit`
- `deviceinfo`
- `messaging`
- `push`
- `xtra`
- `worktime`
- `calllog`

Plugin structure pattern:

- Many plugins use `core/` + `postgres/` split.
- UI assets typically under `src/main/webapp/`.
- DB changes in plugin-specific Liquibase changelog files.

## 5.5 server

Location: `hmdm-server/server/`

Purpose: main web panel and REST backend WAR.

Responsibilities:

- Registers private/public REST resources.
- Orchestrates DI modules and startup tasks.
- Hosts AngularJS web app, localization files, and static assets.
- Aggregates plugin backend/frontend artifacts during build.

Important files:

- `server/pom.xml`
- `server/src/main/java/com/hmdm/HMDMApplication.java`
- `server/src/main/java/com/hmdm/guice/Initializer.java`
- `server/src/main/java/com/hmdm/guice/module/*.java`
- `server/src/main/java/com/hmdm/rest/resource/*.java`
- `server/src/main/resources/liquibase/db.changelog.xml`
- `server/src/main/webapp/app/**`

## 5.6 swagger/ui

Location: `hmdm-server/swagger/ui/`

Purpose: separate WAR for Swagger UI static documentation assets.

Files:

- `swagger/ui/pom.xml`
- `swagger/ui/index.html`
- `swagger/ui/src/main/webapp/WEB-INF/web.xml`
- bundled swagger JS/CSS assets.

---

## 6. Plugin Documentation

## 6.1 platform plugin

Location: `plugins/platform/`

Purpose: plugin framework backbone.

Provides:

- plugin metadata model and list management,
- plugin access/resource endpoints,
- plugin persistence integration,
- plugin-related DB schema (`plugins`, `pluginsDisabled`, plugin permissions).

Key files:

- `plugins/platform/src/main/java/com/hmdm/plugin/PluginConfiguration.java`
- `plugins/platform/src/main/java/com/hmdm/plugin/PluginList.java`
- `plugins/platform/src/main/java/com/hmdm/plugin/rest/PluginResource.java`
- `plugins/platform/src/main/resources/liquibase/plugin.changelog.xml`

## 6.2 devicelog plugin

Location: `plugins/devicelog/` (`core` + `postgres`)

Purpose: ingest/store/query device logs and log rules/settings.

Key files:

- `plugins/devicelog/core/src/main/java/com/hmdm/plugins/devicelog/rest/resource/DeviceLogResource.java`
- `plugins/devicelog/core/src/main/java/com/hmdm/plugins/devicelog/task/InsertDeviceLogRecordsTask.java`
- `plugins/devicelog/postgres/src/main/java/com/hmdm/plugins/devicelog/persistence/postgres/dao/PostgresDeviceLogDAO.java`
- `plugins/devicelog/postgres/src/main/resources/liquibase/devicelog.postgres.changelog.xml`

## 6.3 deviceinfo plugin

Location: `plugins/deviceinfo/`

Purpose: collect and expose dynamic telemetry (GPS/network/memory/app/etc. records), with admin UI and exports.

Key files:

- `plugins/deviceinfo/src/main/java/com/hmdm/plugins/deviceinfo/rest/DeviceInfoResource.java`
- `plugins/deviceinfo/src/main/java/com/hmdm/plugins/deviceinfo/service/DeviceInfoExportService.java`
- `plugins/deviceinfo/src/main/java/com/hmdm/plugins/deviceinfo/persistence/DeviceInfoDAO.java`
- `plugins/deviceinfo/src/main/resources/liquibase/deviceinfo.changelog.xml`
- `DEVICEINFO_PLUGIN.md`

## 6.4 push plugin

Location: `plugins/push/`

Purpose: push messaging history and scheduler management.

Key files:

- `plugins/push/src/main/java/com/hmdm/plugins/push/rest/PushResource.java`
- `plugins/push/src/main/java/com/hmdm/plugins/push/persistence/PushDAO.java`
- `plugins/push/src/main/java/com/hmdm/plugins/push/persistence/PushScheduleDAO.java`
- `plugins/push/src/main/resources/liquibase/push.changelog.xml`

## 6.5 xtra plugin

Location: `plugins/xtra/`

Purpose: additional plugin module with minimal schema and frontend integration.

Key files:

- `plugins/xtra/src/main/java/com/hmdm/plugins/xtra/XtraPluginConfigurationImpl.java`
- `plugins/xtra/src/main/resources/liquibase/xtra.changelog.xml`
- `plugins/xtra/src/main/webapp/xtra.module.js`

## 6.6 worktime plugin

Location: `plugins/worktime/` (`core` + `postgres`)

Purpose: work-time policy management with policy/device overrides and synchronization hooks.

Notes:

- Core changelog is placeholder (`worktime.changelog.xml`).
- Active DB schema and migrations are in postgres changelog.

Key files:

- `plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/service/WorkTimeService.java`
- `plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/rest/resource/WorkTimeResource.java`
- `plugins/worktime/postgres/src/main/java/com/hmdm/plugins/worktime/persistence/postgres/dao/PostgresWorkTimeDAO.java`
- `plugins/worktime/postgres/src/main/resources/liquibase/worktime.postgres.changelog.xml`
- `WORKTIME_PLUGIN.md`

## 6.7 audit plugin

Location: `plugins/audit/`

Purpose: request/action audit trail with filters and persistence.

Key files:

- `plugins/audit/src/main/java/com/hmdm/plugins/audit/rest/AuditResource.java`
- `plugins/audit/src/main/java/com/hmdm/plugins/audit/rest/filter/AuditFilter.java`
- `plugins/audit/src/main/java/com/hmdm/plugins/audit/persistence/AuditDAO.java`
- `plugins/audit/src/main/resources/liquibase/audit.changelog.xml`

## 6.8 messaging plugin

Location: `plugins/messaging/`

Purpose: send/store/retrieve device messages.

Key files:

- `plugins/messaging/src/main/java/com/hmdm/plugins/messaging/rest/MessagingResource.java`
- `plugins/messaging/src/main/java/com/hmdm/plugins/messaging/persistence/MessagingDAO.java`
- `plugins/messaging/src/main/resources/liquibase/messaging.changelog.xml`

## 6.9 calllog plugin

Location: `plugins/calllog/` (`core` + `postgres`)

Purpose: call log ingestion and reporting features.

Notes:

- Core changelog is placeholder.
- Postgres changelog contains schema and plugin registration/permissions.

Key files:

- `plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/rest/resource/CallLogResource.java`
- `plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/rest/resource/CallLogPublicResource.java`
- `plugins/calllog/postgres/src/main/java/com/hmdm/plugins/calllog/persistence/postgres/CallLogPostgresDAO.java`
- `plugins/calllog/postgres/src/main/resources/liquibase/calllog.postgres.changelog.xml`
- `CALLLOG_PLUGIN.md`

---

## 7. Server REST API Surface (Main Resources)

Directory: `server/src/main/java/com/hmdm/rest/resource/`

Key resource classes and base paths:

- `ApplicationResource` → `/private/applications`
- `AuthResource` → `/public/auth`
- `ConfigurationResource` → `/private/configurations`
- `ConfigurationFileResource` → `/private/config-files`
- `CustomerResource` → `/private/customers`
- `DeviceResource` → `/private/devices`
- `FilesResource` → `/private/web-ui-files`
- `GroupResource` → `/private/groups`
- `HintResource` → `/private/hints`
- `IconResource` → `/private/icons`
- `IconFileResource` → `/private/icon-files`
- `PublicResource` → `/public`
- `PublicFilesResource` → `/public/files`
- `QRCodeResource` → `/public/qr`
- `SignupResource` → `/public/signup`
- `PasswordResetResource` → `/public/passwordReset`
- `SyncResource` → `/public/sync`
- `StatsResource` → `/public/stats`
- `SettingsResource` → `/private/settings`
- `SummaryResource` → `/private/summary`
- `PushApiResource` → `/private/push`
- `UpdateResource` → `/private/update`
- `UserResource` → `/private/users`
- `UserRoleResource` → `/private/roles`
- `VideosResource` → `/videos`
- `DownloadFilesServlet` → servlet helper (download stream handling)

---

## 8. Database and Liquibase Documentation

## 8.1 Main schema

- `server/src/main/resources/liquibase/db.changelog.xml` is the primary schema evolution history (users, customers, devices, groups, applications, configurations, roles/permissions, settings, and related linking tables).

## 8.2 Additional module changelog

- `notification/src/main/resources/liquibase/notification.changelog.xml` adds notification queue/message tables.

## 8.3 Plugin changelogs

- `plugins/platform/src/main/resources/liquibase/plugin.changelog.xml`
- `plugins/devicelog/core/src/main/resources/liquibase/devicelog.changelog.xml`
- `plugins/devicelog/postgres/src/main/resources/liquibase/devicelog.postgres.changelog.xml`
- `plugins/deviceinfo/src/main/resources/liquibase/deviceinfo.changelog.xml`
- `plugins/push/src/main/resources/liquibase/push.changelog.xml`
- `plugins/xtra/src/main/resources/liquibase/xtra.changelog.xml`
- `plugins/worktime/core/src/main/resources/liquibase/worktime.changelog.xml` (placeholder)
- `plugins/worktime/postgres/src/main/resources/liquibase/worktime.postgres.changelog.xml`
- `plugins/audit/src/main/resources/liquibase/audit.changelog.xml`
- `plugins/messaging/src/main/resources/liquibase/messaging.changelog.xml`
- `plugins/calllog/core/src/main/resources/liquibase/calllog.changelog.xml` (placeholder)
- `plugins/calllog/postgres/src/main/resources/liquibase/calllog.postgres.changelog.xml`

---

## 9. Operations, Installation, and Maintenance

Core docs:

- `README.md` — feature and quick-start overview.
- `BUILD.txt` — build prerequisites and `mvn install` flow.
- `INSTALL.txt` — deployment steps for Ubuntu/Tomcat/PostgreSQL.
- `TROUBLESHOOTING.txt` — known issue/fix example (loopback port redirect).

Main operational scripts:

- `hmdm_install.sh` — main installer/provisioning script.
- `update-web-app.sh` — update workflow for web app deployment.
- `init-plugin.sh` / `init-plugin.bat` — plugin initialization helpers.
- `letsencrypt-ssl.sh` — certificate/JKS refresh and Tomcat restart.
- `iptables-tomcat.sh` — port redirect rules for HTTPS/Tomcat.
- `cpu_monitor.sh` — Tomcat CPU watchdog and recovery flow.

SQL helper/demo data files:

- `insert_demo_data.sql`
- `insert_demo_data_h0001.sql`
- `insert_simple.sql`

Installer templates and resources:

- `install/context_template.xml`
- `install/log4j_template.xml`
- `install/sql/hmdm_init.en.sql`
- `install/sql/hmdm_init.ru.sql`
- `install/emails/*`

---

## 10. Frontend (Web Control Panel) Notes

Main frontend root:

- `server/src/main/webapp/`

Structure highlights:

- `app/app.js` — AngularJS app bootstrap.
- `app/components/main/controller/*.controller.js` — feature controllers (devices, applications, groups, users, settings, files, updates, etc.).
- `app/components/main/view/*.html` — corresponding views/modal templates.
- `app/shared/service/*.service.js` — shared services (auth, locale, pagination, alerts, utilities).
- `localization/*.js` — language packs.
- `css/main.css`, `images/*`, `ext/*` — static web assets.

Plugin frontend assets are typically inside `plugins/*/src/main/webapp/` and are copied into server webapp during build.

---

## 11. Security and Access Control Notes

Observed security-related implementation areas:

- Authentication and token workflow in `jwt` module (`TokenProvider`, `JWTFilter`, `JWTAuthResource`).
- Server/client auth filters in `common/src/main/java/com/hmdm/rest/filter/`.
- API origin and IP filtering (`ApiOriginFilter`, `PublicIPFilter`, `PrivateIPFilter`).
- HSTS support via `HstsFilter`.
- Role/permission model embedded in core DB schema and plugin-specific changelog inserts.

---

## 12. File-by-File Index (Key Files)

This index lists high-value files by directory with one-line responsibilities.

### 12.1 Root-level key files

- `pom.xml` — parent Maven descriptor, shared dependency/version policy.
- `README.md` — product overview and quick start.
- `BUILD.txt` — build prerequisites and workflow.
- `INSTALL.txt` — installation/deployment guide.
- `TROUBLESHOOTING.txt` — troubleshooting notes.
- `createPlugin.md` — extensive plugin development guide.
- `WORKTIME_PLUGIN.md` — worktime plugin technical doc.
- `DEVICEINFO_PLUGIN.md` — deviceinfo plugin technical doc.
- `CALLLOG_PLUGIN.md` — calllog plugin technical doc.
- `hmdm_install.sh` — installation automation.
- `update-web-app.sh` — update automation.
- `init-plugin.sh` — plugin scaffolding/integration helper.
- `letsencrypt-ssl.sh` — SSL/TLS maintenance automation.
- `iptables-tomcat.sh` — networking redirect helper.
- `cpu_monitor.sh` — process watchdog utility.

### 12.2 common module

- `common/pom.xml` — shared module dependencies.
- `common/src/main/java/com/hmdm/persistence/CommonDAO.java` — central persistence operations.
- `common/src/main/java/com/hmdm/persistence/DeviceDAO.java` — device persistence methods.
- `common/src/main/java/com/hmdm/persistence/ApplicationDAO.java` — application persistence methods.
- `common/src/main/java/com/hmdm/persistence/CustomerDAO.java` — customer data access.
- `common/src/main/java/com/hmdm/persistence/UserDAO.java` — user data access.
- `common/src/main/java/com/hmdm/rest/filter/AuthFilter.java` — request authentication filter.
- `common/src/main/java/com/hmdm/rest/filter/ApiOriginFilter.java` — origin restriction.
- `common/src/main/java/com/hmdm/rest/filter/HstsFilter.java` — strict transport security header handling.
- `common/src/main/java/com/hmdm/security/SecurityContext.java` — request/user security context.
- `common/src/main/java/com/hmdm/service/EmailService.java` — outbound email integration.
- `common/src/main/java/com/hmdm/util/APKFileAnalyzer.java` — APK metadata analysis utility.

### 12.3 jwt module

- `jwt/pom.xml` — JWT module dependencies.
- `jwt/src/main/java/com/hmdm/security/jwt/TokenProvider.java` — JWT issue/parse logic.
- `jwt/src/main/java/com/hmdm/security/jwt/JWTFilter.java` — JWT request filter.
- `jwt/src/main/java/com/hmdm/security/jwt/rest/JWTAuthResource.java` — auth endpoint.

### 12.4 notification module

- `notification/pom.xml` — notification module dependencies.
- `notification/src/main/java/com/hmdm/notification/PushService.java` — notification orchestration.
- `notification/src/main/java/com/hmdm/notification/PushSenderPolling.java` — polling transport sender.
- `notification/src/main/java/com/hmdm/notification/PushSenderMqtt.java` — MQTT transport sender.
- `notification/src/main/java/com/hmdm/notification/rest/NotificationResource.java` — notification REST API.
- `notification/src/main/resources/liquibase/notification.changelog.xml` — notification DB migration.

### 12.5 server module

- `server/pom.xml` — core WAR packaging and plugin aggregation.
- `server/build.properties.example` — runtime/env placeholders.
- `server/conf/context.xml` — Tomcat context parameters.
- `server/src/main/java/com/hmdm/HMDMApplication.java` — Jersey app configuration.
- `server/src/main/java/com/hmdm/guice/Initializer.java` — Guice startup/wiring.
- `server/src/main/java/com/hmdm/guice/module/MainRestModule.java` — main REST bindings.
- `server/src/main/java/com/hmdm/guice/module/PrivateRestModule.java` — private API bindings.
- `server/src/main/java/com/hmdm/guice/module/PublicRestModule.java` — public API bindings.
- `server/src/main/java/com/hmdm/guice/module/PersistenceModule.java` — persistence bindings.
- `server/src/main/java/com/hmdm/guice/module/LiquibaseModule.java` — DB migration wiring.
- `server/src/main/java/com/hmdm/task/FileCheckTask.java` — startup/maintenance file checks.
- `server/src/main/java/com/hmdm/task/FileMigrateTask.java` — file migration task.
- `server/src/main/java/com/hmdm/task/CustomerStatusTask.java` — customer status background task.
- `server/src/main/resources/liquibase/db.changelog.xml` — primary schema evolution.
- `server/src/main/resources/log4j.xml` — logging defaults.
- `server/src/main/webapp/WEB-INF/web.xml` — servlet/filter definitions.
- `server/src/main/webapp/app/app.js` — AngularJS app module root.
- `server/src/main/webapp/index.html` — web UI entry page.

### 12.6 plugins platform and business plugins

- `plugins/pom.xml` — plugin parent module list.
- `plugins/platform/pom.xml` — plugin framework package.
- `plugins/platform/src/main/java/com/hmdm/plugin/PluginList.java` — plugin registry/list.
- `plugins/platform/src/main/java/com/hmdm/plugin/rest/PluginResource.java` — plugin management endpoint.
- `plugins/platform/src/main/resources/liquibase/plugin.changelog.xml` — plugin registry schema.

- `plugins/devicelog/pom.xml` — devicelog plugin parent.
- `plugins/devicelog/core/src/main/java/com/hmdm/plugins/devicelog/rest/resource/DeviceLogResource.java` — devicelog API.
- `plugins/devicelog/postgres/src/main/resources/liquibase/devicelog.postgres.changelog.xml` — devicelog schema.

- `plugins/deviceinfo/pom.xml` — deviceinfo plugin package.
- `plugins/deviceinfo/src/main/java/com/hmdm/plugins/deviceinfo/rest/DeviceInfoResource.java` — device telemetry API.
- `plugins/deviceinfo/src/main/resources/liquibase/deviceinfo.changelog.xml` — deviceinfo schema.

- `plugins/push/pom.xml` — push plugin package.
- `plugins/push/src/main/java/com/hmdm/plugins/push/rest/PushResource.java` — push API.
- `plugins/push/src/main/resources/liquibase/push.changelog.xml` — push schema.

- `plugins/xtra/pom.xml` — xtra plugin package.
- `plugins/xtra/src/main/resources/liquibase/xtra.changelog.xml` — xtra schema.

- `plugins/worktime/pom.xml` — worktime plugin parent.
- `plugins/worktime/core/src/main/java/com/hmdm/plugins/worktime/rest/resource/WorkTimeResource.java` — worktime API.
- `plugins/worktime/postgres/src/main/resources/liquibase/worktime.postgres.changelog.xml` — worktime schema/migrations.

- `plugins/audit/pom.xml` — audit plugin package.
- `plugins/audit/src/main/java/com/hmdm/plugins/audit/rest/AuditResource.java` — audit API.
- `plugins/audit/src/main/resources/liquibase/audit.changelog.xml` — audit schema.

- `plugins/messaging/pom.xml` — messaging plugin package.
- `plugins/messaging/src/main/java/com/hmdm/plugins/messaging/rest/MessagingResource.java` — messaging API.
- `plugins/messaging/src/main/resources/liquibase/messaging.changelog.xml` — messaging schema.

- `plugins/calllog/pom.xml` — calllog plugin parent.
- `plugins/calllog/core/src/main/java/com/hmdm/plugins/calllog/rest/resource/CallLogResource.java` — calllog API.
- `plugins/calllog/postgres/src/main/resources/liquibase/calllog.postgres.changelog.xml` — calllog schema.

### 12.7 install and swagger

- `install/context_template.xml` — installer context template.
- `install/log4j_template.xml` — installer logging template.
- `install/sql/hmdm_init.en.sql` — initial seed SQL (EN).
- `install/sql/hmdm_init.ru.sql` — initial seed SQL (RU).
- `swagger/ui/pom.xml` — swagger-ui WAR packaging.
- `swagger/ui/index.html` — swagger UI shell page.

---

## 13. Build/Deploy Quick Commands

From `hmdm-server/`:

- Build all modules: `mvn install`
- For fresh local config setup: copy `server/build.properties.example` to `server/build.properties` and update values.
- Install/provision server (root privileges often required): `sudo ./hmdm_install.sh`

---

## 14. Observations and Notes

- The project follows a stable modular design where plugin backend + DB + frontend are tightly integrated into the main server WAR.
- There are placeholder Liquibase changelogs in some plugin core modules (`worktime/core`, `calllog/core`), while active DB evolution exists in corresponding postgres modules.
- The repository includes operational artifacts and ad-hoc files/scripts; for production hardening, standardizing script naming and reducing environment-specific leftovers can improve maintainability.

---

## 15. Suggested Next Documentation Additions (Optional)

If needed, this document can be extended with:

- endpoint-by-endpoint API contract table (method, path, auth, payload, response),
- entity relationship map generated from Liquibase changelogs,
- plugin installation matrix (required context params, module dependencies, DB objects),
- environment profile templates (dev/stage/prod).
