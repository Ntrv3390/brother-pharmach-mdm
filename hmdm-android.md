# hmdm-android Documentation

## 1. Overview

`hmdm-android` is the Android-side agent/launcher for Headwind MDM. It is a **device-side runtime** that:

- enrolls to an HMDM server,
- receives configuration and policy updates,
- enforces kiosk/launcher behavior,
- deploys apps/files and reports telemetry,
- maintains push channels (MQTT and long-polling),
- exposes an SDK/API (`lib` module) for other Android apps to integrate with Headwind MDM.

Project type: Android multi-module Gradle project (`app` + `lib`).

---

## 2. Repository Scope and Scan Notes

A complete scan was performed across `hmdm-android/` with focus on source, manifests, Gradle configs, API wiring, background execution, and persistence.

Approximate file statistics in this workspace snapshot:

- Total files: **4950**
- Java files: **229**
- Kotlin files: **0**
- XML files: **847**
- AIDL files: **2**
- Gradle/properties files: **21**
- Shell scripts: **0**

Counts include generated/IDE/third-party files under repository folders; detailed sections below focus on functional project files.

---

## 3. Top-Level Structure

`hmdm-android/` contains:

- `app/` — Android launcher/agent app module (`com.hmdm.launcher`).
- `lib/` — Android library module (`com.hmdm`) with SDK/AIDL integration API.
- `fastlane/` — app-store metadata and release texts for localized listings.
- `gradle/` + `gradlew` — Gradle wrapper/runtime.
- `build.gradle`, `settings.gradle`, `gradle.properties` — root build configuration.
- `README.md` — development/build quick-start.

---

## 4. Build System and Configuration

## 4.1 Root Gradle setup

Primary files:

- `hmdm-android/settings.gradle`
- `hmdm-android/build.gradle`
- `hmdm-android/gradle.properties`

Key points:

- Modules declared: `:app`, `:lib`.
- Android Gradle Plugin: `8.2.2` (root classpath).
- Repositories include `google()`, `jcenter()`, and `mavenCentral()` (module level).
- Gradle JVM configured with Java 17 in `gradle.properties`.

## 4.2 App module build (`app/build.gradle`)

- `compileSdkVersion 34`, `targetSdkVersion 34`, `minSdkVersion 16`.
- Application ID: `com.hmdm.launcher`.
- Flavor dimension `all` with `opensource` product flavor.
- Data binding + AIDL enabled.
- Java source/target compatibility set to Java 8.
- MultiDex enabled.

### BuildConfig operational flags

`app/build.gradle` defines many runtime constants that strongly shape behavior:

- Server connectivity: `BASE_URL`, `SECONDARY_BASE_URL`, `SERVER_PROJECT`.
- Enrollment/device ID behavior: `DEVICE_ID_CHOICE`.
- Push controls: `ENABLE_PUSH`, `MQTT_PORT`, `MQTT_SERVICE_FOREGROUND`.
- Security controls: `REQUEST_SIGNATURE`, `CHECK_SIGNATURE`, `TRUST_ANY_CERTIFICATE`.
- Device/admin behavior: `SYSTEM_PRIVILEGES`, `SET_DEFAULT_LAUNCHER_EARLY`, `USE_ACCESSIBILITY`, `ENABLE_KIOSK_WITHOUT_OVERLAYS`.
- Integration: `LIBRARY_API_KEY`.

These values are compile-time defaults and should be reviewed for each deployment.

## 4.3 Library module build (`lib/build.gradle`)

- Android library plugin.
- `compileSdkVersion 34`, `targetSdkVersion 34`, `minSdkVersion 14`.
- Namespace: `com.hmdm`.
- AIDL enabled to expose IPC contract.

---

## 5. Runtime Architecture

## 5.1 Core startup flow

1. Android launches `MainActivity` as HOME/LAUNCHER.
2. Initial provisioning/setup can route through setup activities (`InitialSetupActivity`, `MdmChoiceSetupActivity`).
3. App loads persisted config and may enroll/fetch config from server.
4. Push and background workers/services are initialized.
5. Policy, kiosk restrictions, app/file sync, and periodic telemetry continue during runtime.

## 5.2 Main orchestration classes

- `app/src/main/java/com/hmdm/launcher/ui/MainActivity.java`
- `app/src/main/java/com/hmdm/launcher/helper/Initializer.java`
- `app/src/main/java/com/hmdm/launcher/helper/ConfigUpdater.java`
- `app/src/main/java/com/hmdm/launcher/helper/SettingsHelper.java`
- `app/src/main/java/com/hmdm/launcher/util/WorkTimeManager.java`

`MainActivity` is a large central controller handling UI state, policy dialogs, kiosk behavior, push/config broadcasts, and service orchestration.

---

## 6. Android Manifest Documentation

Main manifest: `app/src/main/AndroidManifest.xml`

## 6.1 Permissions profile

The app requests broad management capabilities including:

- device/network/location and install-package controls,
- boot completion, foreground service, exact alarms,
- call log and telephony-related access,
- various device policy permissions (including newer `MANAGE_DEVICE_POLICY_*` capabilities),
- `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, and storage-related permissions.

This reflects a device-owner / kiosk-management use case.

## 6.2 Activities

- `MainActivity` (home/launcher + provisioning_successful action)
- `AdminActivity`
- `AdminModeRequestActivity`
- `InitialSetupActivity`
- `MdmChoiceSetupActivity`
- `ErrorDetailsActivity`
- `com.journeyapps.barcodescanner.CaptureActivity`

## 6.3 Receivers

- `AdminReceiver` (DeviceAdminReceiver)
- `BootReceiver`
- `ShutdownReceiver`
- `SimChangedReceiver`
- `CallStateReceiver`

## 6.4 Services

- `PluginApiService` (AIDL-facing service for external integrations)
- `PushLongPollingService`
- `LocationService`
- `StatusControlService`
- `CheckForegroundApplicationService`
- `CheckForegroundAppAccessibilityService` (stub/dependent behavior)
- `org.eclipse.paho.android.service.MqttService`

## 6.5 Provider

- `androidx.core.content.FileProvider` with authorities `${applicationId}.provider`.

---

## 7. Networking and Server Integration

## 7.1 Retrofit API interface

File: `app/src/main/java/com/hmdm/launcher/server/ServerService.java`

Provides strongly-typed calls for:

- enroll + fetch config (`/rest/public/sync/configuration/{number}`),
- periodic device info upload (`/rest/public/sync/info`),
- push polling/long-polling endpoints,
- plugin endpoints for device log, detailed info, locations, device reset/reboot/password reset, calllog submission.

## 7.2 Service keeper and URL failover

- `ServerServiceKeeper` builds/reuses Retrofit services.
- Primary/secondary server handling is modeled in app config/build constants.

## 7.3 Request signature and CPU headers

`ServerService` includes headers:

- `X-Request-Signature`
- `X-CPU-Arch`

Signing/checking logic and fallback behavior is integrated in config fetch tasks (for example `GetServerConfigTask` and helpers).

---

## 8. Push, Workers, Services, and Receivers

## 8.1 Push channels

The app supports both:

- MQTT (Paho service),
- HTTP long-polling (`PushLongPollingService`).

Push message handling is coordinated through worker/processor classes:

- `PushNotificationWorker`
- `PushNotificationProcessor`

## 8.2 WorkManager workers

Key workers:

- `PushNotificationWorker` — push maintenance + periodic refresh.
- `ScheduledAppUpdateWorker` — scheduled app update execution.
- `SendDeviceInfoWorker` — periodic telemetry upload.
- `RemoteLogWorker` — log delivery with retry policy.
- `CallLogUploadWorker` — call-log plugin upload trigger.
- `pro/worker/DetailedInfoWorker` — dynamic/detailed info submission.

## 8.3 Core foreground/background services

- `LocationService` — location sampling and queued upload.
- `StatusControlService` — policy/state control checks.
- `PluginApiService` — external app API binding.

## 8.4 Broadcast receivers

- `BootReceiver` — startup re-initialization after reboot.
- `CallStateReceiver` — monitors calls and triggers uploads for calllog flow.
- `SimChangedReceiver` — SIM-change handling.
- `ShutdownReceiver` — shutdown handling/logging.
- `ScreenOffReceiver` exists in source for runtime registration scenarios.

---

## 9. Local Data Layer (SQLite)

Database package: `app/src/main/java/com/hmdm/launcher/db/`

Main DB helper:

- `DatabaseHelper.java` (`hmdm.launcher.sqlite`, schema version 10)

Major tables/helpers:

- `LogTable` — queued remote logs.
- `LogConfigTable` — server-delivered log filtering rules.
- `InfoHistoryTable` — historical detailed telemetry snapshots.
- `RemoteFileTable` — managed remote files metadata.
- `LocationTable` — location queue for upload.
- `DownloadTable` — tracked downloads/install-retry state.

---

## 10. UI and UX Layer

Primary UI package: `app/src/main/java/com/hmdm/launcher/ui/`

Important components:

- `MainActivity` — launcher shell and policy-aware main surface.
- `InitialSetupActivity` / `MdmChoiceSetupActivity` — provisioning and setup wizard flows.
- `AdminActivity` / `AdminModeRequestActivity` — admin mode interactions.
- Adapters: `MainAppListAdapter`, `BottomAppListAdapter`, `BaseAppListAdapter`.
- Custom views: `ui/custom/*` (`StatusBarUpdater`, `BatteryStateView`, `BlockingBar`).

Resources:

- `app/src/main/res/layout/*` — activity/dialog templates.
- `app/src/main/res/xml/*` — device admin config, network security, provider paths.
- `app/src/main/res/values*/*` — localized strings and dimensions.

---

## 11. SDK / Integration Library (`lib` module)

`lib` provides an API for third-party companion apps to communicate with Headwind MDM agent.

Core files:

- `lib/src/main/java/com/hmdm/MDMService.java` — low-level service binding + command/query wrapper.
- `lib/src/main/java/com/hmdm/HeadwindMDM.java` — high-level facade with reconnect + event callbacks.
- `lib/src/main/java/com/hmdm/MDMPushHandler.java` and `MDMPushMessage.java` — push callbacks/message model.
- `lib/src/main/java/com/hmdm/MDMError.java` and `MDMException.java` — error model.
- `lib/src/main/aidl/com/hmdm/IMdmApi.aidl` — IPC contract.

Notable behavior:

- Connects to the launcher service action (`com.hmdm.action.Connect`).
- Exposes config querying, push sending, custom value updates, and config-update notifications.

---

## 12. Security and Operational Notes

Observed security/ops-relevant items from source/config:

- Build-time security flags can weaken transport trust when enabled (`TRUST_ANY_CERTIFICATE`, `CHECK_SIGNATURE=false`).
- API key and request signature secrets are embedded in BuildConfig defaults in `app/build.gradle` and should be treated as sensitive.
- `AndroidManifest.xml` currently marks `<application android:testOnly="true">`; this affects install/distribution behavior.
- Keystore and credentials appear present in repository (`keystore.jks` and signing fields in `app/build.gradle`), which is high risk for production signing hygiene.

---

## 13. Build and Run Quick Reference

From `hmdm-android/`:

- Build all modules: `./gradlew build`
- Build release APK (module): `./gradlew :app:assembleRelease`
- Build AAR library: `./gradlew :lib:assembleRelease`

Device-owner testing (from README flow):

- `adb shell`
- `dpm set-device-owner com.hmdm.launcher/.AdminReceiver`

---

## 14. Key File Index (Grouped)

## 14.1 Root

- `README.md` — developer quick-start and build notes.
- `settings.gradle` — module declaration (`app`, `lib`).
- `build.gradle` — root Gradle plugins/repositories.
- `gradle.properties` — Gradle JVM and AndroidX flags.
- `lint.xml` — lint configuration.

## 14.2 App module build and manifest

- `app/build.gradle` — app build parameters, signing, flavors, BuildConfig flags, dependencies.
- `app/src/main/AndroidManifest.xml` — permissions + components + service/provider declarations.
- `app/src/main/aidl/com/hmdm/IMdmApi.aidl` — app-side AIDL service contract.

## 14.3 Core app runtime

- `app/src/main/java/com/hmdm/launcher/App.java` — Application init (Picasso singleton setup).
- `app/src/main/java/com/hmdm/launcher/AdminReceiver.java` — device admin callbacks.
- `app/src/main/java/com/hmdm/launcher/Const.java` — shared constants/actions.

## 14.4 UI layer

- `app/src/main/java/com/hmdm/launcher/ui/MainActivity.java` — main launcher/controller.
- `app/src/main/java/com/hmdm/launcher/ui/InitialSetupActivity.java` — setup flow.
- `app/src/main/java/com/hmdm/launcher/ui/MdmChoiceSetupActivity.java` — provisioning mode selection.
- `app/src/main/java/com/hmdm/launcher/ui/AdminActivity.java` — admin panel.
- `app/src/main/java/com/hmdm/launcher/ui/ErrorDetailsActivity.java` — error detail view.

## 14.5 Helpers and configuration

- `app/src/main/java/com/hmdm/launcher/helper/Initializer.java` — startup initialization helpers.
- `app/src/main/java/com/hmdm/launcher/helper/ConfigUpdater.java` — config update orchestration.
- `app/src/main/java/com/hmdm/launcher/helper/SettingsHelper.java` — persistent settings/config accessor.
- `app/src/main/java/com/hmdm/launcher/helper/CryptoHelper.java` — crypto/signature helper functions.
- `app/src/main/java/com/hmdm/launcher/helper/MigrationHelper.java` — data/config migration support.

## 14.6 Server integration and tasks

- `app/src/main/java/com/hmdm/launcher/server/ServerService.java` — Retrofit endpoint interface.
- `app/src/main/java/com/hmdm/launcher/server/ServerServiceKeeper.java` — Retrofit setup/failover management.
- `app/src/main/java/com/hmdm/launcher/server/ServerUrl.java` — server URL abstraction.
- `app/src/main/java/com/hmdm/launcher/task/GetServerConfigTask.java` — enrollment/config retrieval task.
- `app/src/main/java/com/hmdm/launcher/task/SendDeviceInfoTask.java` — device info upload task.
- `app/src/main/java/com/hmdm/launcher/task/GetRemoteLogConfigTask.java` — remote log config fetch.

## 14.7 Services, workers, receivers

- `app/src/main/java/com/hmdm/launcher/service/PluginApiService.java` — AIDL service endpoint.
- `app/src/main/java/com/hmdm/launcher/service/PushLongPollingService.java` — long-poll push service.
- `app/src/main/java/com/hmdm/launcher/service/LocationService.java` — location foreground service.
- `app/src/main/java/com/hmdm/launcher/service/StatusControlService.java` — policy/service checks.
- `app/src/main/java/com/hmdm/launcher/worker/PushNotificationWorker.java` — scheduled push processing.
- `app/src/main/java/com/hmdm/launcher/worker/PushNotificationProcessor.java` — command processing.
- `app/src/main/java/com/hmdm/launcher/worker/SendDeviceInfoWorker.java` — periodic telemetry worker.
- `app/src/main/java/com/hmdm/launcher/worker/RemoteLogWorker.java` — remote log upload worker.
- `app/src/main/java/com/hmdm/launcher/worker/CallLogUploadWorker.java` — call log upload worker.
- `app/src/main/java/com/hmdm/launcher/receiver/BootReceiver.java` — boot completion receiver.
- `app/src/main/java/com/hmdm/launcher/receiver/CallStateReceiver.java` — telephony receiver.
- `app/src/main/java/com/hmdm/launcher/receiver/SimChangedReceiver.java` — SIM-state receiver.
- `app/src/main/java/com/hmdm/launcher/receiver/ShutdownReceiver.java` — shutdown receiver.

## 14.8 Database package

- `app/src/main/java/com/hmdm/launcher/db/DatabaseHelper.java` — SQLite helper lifecycle/versioning.
- `app/src/main/java/com/hmdm/launcher/db/LogTable.java` — logs table API.
- `app/src/main/java/com/hmdm/launcher/db/LogConfigTable.java` — log config rules table API.
- `app/src/main/java/com/hmdm/launcher/db/InfoHistoryTable.java` — device info history table API.
- `app/src/main/java/com/hmdm/launcher/db/RemoteFileTable.java` — remote file table API.
- `app/src/main/java/com/hmdm/launcher/db/LocationTable.java` — location queue table API.
- `app/src/main/java/com/hmdm/launcher/db/DownloadTable.java` — download queue/retry table API.

## 14.9 Library module

- `lib/build.gradle` — library module build config.
- `lib/src/main/AndroidManifest.xml` — minimal manifest.
- `lib/src/main/aidl/com/hmdm/IMdmApi.aidl` — shared AIDL contract.
- `lib/src/main/java/com/hmdm/HeadwindMDM.java` — high-level SDK facade.
- `lib/src/main/java/com/hmdm/MDMService.java` — binder/service client.
- `lib/src/main/java/com/hmdm/MDMPushHandler.java` — push callback interface.
- `lib/src/main/java/com/hmdm/MDMPushMessage.java` — push payload model.
- `lib/src/main/java/com/hmdm/MDMError.java` — SDK error codes.
- `lib/src/main/java/com/hmdm/MDMException.java` — SDK exception wrapper.

## 14.10 Fastlane metadata

- `fastlane/metadata/android/**` — localized store listing data (`title`, `short_description`, `full_description` by locale).

---

## 15. Suggested Follow-up Documentation (Optional)

Potential next docs that can be generated from this repo:

- endpoint contract table from `ServerService` (request/response model mapping),
- permission-to-feature matrix from `AndroidManifest.xml`,
- work manager/service lifecycle timeline,
- BuildConfig hardening guide for production variants.
