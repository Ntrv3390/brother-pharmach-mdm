Silent Location & Push — Remove All User-Visible Notifications
Background & Problem
The app currently runs two foreground services that post a persistent notification the user sees:

Service Notification ID Current behaviour
LocationService 112 startForeground() with location icon — fires every service start/restart
PushLongPollingService 113 startForeground() with MQTT/push icon — fires whenever push polling is active
Android requires a visible notification for any startForeground() call on API 26+. The text was already disguised ("This device belongs to your organization") but the notification itself cannot be removed while the service is a foreground service.

Additionally, DetailedInfoWorker.requestConfigUpdate() calls startForegroundService(LocationService) on every config refresh, re-raising the notification even if briefly dismissed.

Goals
No location notification — admin still tracks location, but at a ~15 min cadence via silent WorkManager.
No push-polling notification — push messages still arrive, service runs silently.
No crashes — every API-level guard, null check, and lifecycle edge-case handled.
Backward compatible — API 21–35, with conditional paths per major Android version.
Root-Cause Analysis
Why the notifications appear
LocationService.startAsForeground() (line 250–276): calls startForeground(NOTIFICATION_ID, notification, ...). Android mandates the notification be kept visible as long as the service is running.
PushLongPollingService.startAsForeground() (line 190–208): same, gated by BuildConfig.MQTT_SERVICE_FOREGROUND flag (currently true).
DetailedInfoWorker.requestConfigUpdate() (line 53–63): calls startForegroundService(LocationService.ACTION_UPDATE_GPS) on every config refresh — re-raises the notification.
Why we cannot simply hide the notification
Android 8+ (API 26): you must call startForeground() within 5 seconds of startForegroundService(), or the system crashes the app with ForegroundServiceStartNotAllowedException. There is no way to call startForegroundService() and skip posting a notification.

Proposed Solution
Part 1 — Location: Replace foreground service with WorkManager periodic job
Strategy: Stop calling startForegroundService() for location. Instead use a PeriodicWorkRequest (every 15 minutes — WorkManager minimum) that:

Gets last known location via LocationManager.getLastKnownLocation() (no active GPS listener, no foreground needed).
Saves to LocationTable DB.
Uploads via existing sendDetailedInfo() server endpoint.
Runs entirely in the background without any notification, doze-aware via WorkManager. The 15-min cadence trade-off is explicitly accepted.

Part 2 — Push Long-Polling: Convert to background service (no foreground)
Strategy: Remove the startAsForeground() call from PushLongPollingService. On API 26+, the OS will kill the background service quickly. Mitigate by extending PushNotificationWorker (WorkManager, already scheduled every 15 min) to also perform a push poll with a short timeout.

Detailed File-by-File Changes
Component 1: Location — New LocationWorker (WorkManager background job)
[NEW] worker/LocationWorker.java
Extends Worker (synchronous, no foreground needed).
doWork():
Check ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION — return Result.failure() if missing.
Try LocationManager.getLastKnownLocation(GPS_PROVIDER) → NETWORK_PROVIDER → PASSIVE_PROVIDER (null-safe chain).
If all null, return Result.success() (no location to upload, don't penalise retry backoff).
Insert into LocationTable.
Call sendLocations() (extracted static helper, same logic as in LocationService).
Return Result.success().
static schedule(Context) — PeriodicWorkRequest.Builder(15, MINUTES) + ExistingPeriodicWorkPolicy.KEEP.
static scheduleOneShot(Context) — OneTimeWorkRequest + ExistingWorkPolicy.REPLACE for immediate one-shot after config update.
No startForeground() anywhere.
Edge cases:

Scenario Risk Mitigation
No location permission SecurityException checkSelfPermission() guard; catch SecurityException; return Result.failure()
All providers return null Nothing to upload Return Result.success() — do not retry
Network unavailable Upload fails Skip upload, return Result.success() — DB buffers for next run
DB null / not initialized NullPointerException Null-check DatabaseHelper.instance() result before use
[MODIFY] service/LocationService.java
Convert from a foreground service to a safe no-op stub that immediately stops itself.

Why keep the class? DetailedInfoWorker still references LocationService class constants (ACTION_UPDATE_GPS). Keeping the class as a stub prevents compile errors from any remaining references.

Safe stub pattern (API 26+ crash prevention):

On API ≥ 26, if someone still calls startForegroundService(LocationService), we MUST call startForeground() within 5 seconds or the OS crashes us. The safe pattern:

java
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
// Required to avoid ForegroundServiceStartNotAllowedException
startForeground(NOTIFICATION_ID, buildSilentNotification());
if (Build.VERSION.SDK_INT >= 33) {
stopForeground(STOP_FOREGROUND_REMOVE);
} else {
stopForeground(true);
}
}
stopSelf();
return START_NOT_STICKY;
}
buildSilentNotification() creates a channel with IMPORTANCE_NONE and a PRIORITY_MIN / VISIBILITY_SECRET notification — no sound, no status-bar icon, invisible on most ROMs, and it disappears in milliseconds.

Changes summary:

Remove startAsForeground() active call from onStartCommand().
Add buildSilentNotification() helper for the safety startForeground().
Keep NOTIFICATION*ID, CHANNEL_ID, ACTION*\* constants.
requestLocationUpdates() and listeners are kept but never called (safety net).
[MODIFY] pro/worker/DetailedInfoWorker.java
Replace the startForegroundService(LocationService) call in requestConfigUpdate() with:

java
LocationWorker.scheduleOneShot(context); // silent WorkManager one-shot
Remove the Intent / startForegroundService block entirely. The uploadLatestKnownLocation() private method is unchanged.

Component 2: Push Long-Polling — Eliminate foreground notification
[MODIFY] service/PushLongPollingService.java
Remove the startAsForeground() call in onStartCommand():

java
// REMOVE this block entirely:
if (BuildConfig.MQTT_SERVICE_FOREGROUND && !started) {
startAsForeground();
started = true;
}
The polling thread continues to run. On API 26+, the OS may kill the service after ~1 min if the app is not in the foreground. This is mitigated by PushNotificationWorker (see below).

Note: Do NOT call startForeground() at all — since we are not calling startForegroundService() any more (the intent is started via startService() which does not require a foreground call).

Manifest change (see AndroidManifest section): Remove foregroundServiceType attribute from this service declaration.

[MODIFY] worker/PushNotificationWorker.java
Extend doLongPollingWork() to actually poll for push messages (not just force a config update):

java
private Result doLongPollingWork() {
doPushPoll(); // NEW: poll for pending push messages
return forceConfigUpdateWork();
}
private void doPushPoll() {
// Uses a SHORT-timeout server service (CONNECTION_TIMEOUT = 10 sec)
// NOT the LONG_POLLING_READ_TIMEOUT (5 min) — workers have limited runtime
ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
// ... same logic as doPollingWork() using queryPushNotifications()
// Guard: null settingsHelper, null config, empty deviceId → return early
// Catch all exceptions, log, don't rethrow
// HTTP 500 (long-poll server timeout) = no messages, not an error
}
Key difference from the existing doPollingWork():

Uses queryPushNotifications() (short-poll endpoint) not queryPushLongPolling() (long-poll).
Wrapped in full null + exception guards.
No Result.failure() on network error — push is best-effort.
Component 3: AndroidManifest.xml
[MODIFY] AndroidManifest.xml
Remove foregroundServiceType and the <property> child from PushLongPollingService:

xml

<!-- BEFORE -->

<service android:name=".service.PushLongPollingService"
    android:foregroundServiceType="specialUse|systemExempted"
    android:exported="false">
<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Push notification service"/>
</service>

<!-- AFTER -->

<service android:name=".service.PushLongPollingService"
    android:exported="false"/>
Keep LocationService declaration with foregroundServiceType="location" unchanged — the stub still calls startForeground() briefly, so the attribute is still needed.

Component 4: Initializer.java
[MODIFY] helper/Initializer.java
In init() inside the ConnectionWaiter.waitForConnect callback, add:

java
LocationWorker.schedule(context); // silent periodic location (no notification)
alongside the existing DetailedInfoWorker.schedule(context) (which is a stub — we still call it for safety).

In startServicesAndLoadConfig(), inside the onConfigUpdateComplete() callback, add:

java
LocationWorker.scheduleOneShot(context); // upload location immediately after config sync
Silent Notification (Crash Prevention Detail)
For LocationService stub — IMPORTANCE_NONE channel + PRIORITY_MIN + VISIBILITY_SECRET:

java
private Notification buildSilentNotification() {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
NotificationChannel ch = new NotificationChannel(
CHANNEL_ID, "System", NotificationManager.IMPORTANCE_NONE);
ch.setShowBadge(false);
NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
if (nm != null) nm.createNotificationChannel(ch);
}
return new NotificationCompat.Builder(this, CHANNEL_ID)
.setSmallIcon(R.drawable.ic_location_service) // existing drawable, no new asset needed
.setPriority(NotificationCompat.PRIORITY_MIN)
.setVisibility(NotificationCompat.VISIBILITY_SECRET)
.build();
}
Result: no heads-up, no sound, no status-bar icon, not shown in shade on most ROMs. Disappears in milliseconds.

Full Edge-Case & Crash Prevention Matrix
Scenario Risk Mitigation
startForegroundService() still called after stub conversion ForegroundServiceStartNotAllowedException Stub immediately calls startForeground() then stopForeground() + stopSelf()
stopForeground(boolean) vs stopForeground(int) API split at API 33 Compile warning / lint Branch on Build.VERSION.SDK_INT >= 33 to use correct overload
LocationWorker runs without location permission SecurityException checkSelfPermission() before any getLastKnownLocation() call
All location providers return null Nothing to upload Return Result.success(), skip silently
DatabaseHelper.instance() returns null or throws NPE / crash Null-check; wrap in try/catch; return Result.success()
Network down during location upload Upload fails Return Result.success() (DB buffers locations for next run)
Multiple schedule() calls on boot + config update Duplicate periodic workers ExistingPeriodicWorkPolicy.KEEP prevents duplicates
scheduleOneShot() called repeatedly during config updates Queuing up redundant workers ExistingWorkPolicy.REPLACE cancels and replaces pending one-shot
PushLongPollingService killed by OS on API 26+ Push messages missed PushNotificationWorker (WorkManager 15-min) provides guaranteed fallback
doPushPoll() uses long-poll 5-min timeout in a Worker Worker ANR / killed by OS Use short connection timeout (10 sec) via getServerServiceInstance() not createServerService(LONG_POLLING_READ_TIMEOUT)
NotificationChannel cached with wrong importance (pre-existing install) Channel importance cannot be programmatically lowered First run creates the channel with IMPORTANCE_NONE; if user upgraded from older build, the channel is re-created under a new ID (suffix -silent)
LocationTable grows unbounded on persistent network failure DB bloat Existing LocationTable.select(db, 50) + delete() on success caps growth
BootReceiver → startServicesAndLoadConfig() → startForegroundService(PushLongPollingService) on API 26+ Foreground notification on reboot PushLongPollingService no longer calls startForeground(), so startService() is used from Initializer instead
What Stays the Same (No Changes)
WorkTimeNotificationListenerService — unrelated, blocks third-party app notifications.
StatusControlService — plain background service, not involved.
MQTT push path (MqttService, PushNotificationMqttWrapper) — already runs without a custom notification; MqttService has its own foreground notification that can be addressed separately if needed.
PushNotificationProcessor — processes messages, does not post notifications.
Server side — no server changes; same endpoint, same data format, just less frequent location updates.
Files Changed Summary
File Type What changes
worker/LocationWorker.java NEW Silent WorkManager periodic location uploader, no foreground
service/LocationService.java MODIFY Safe no-op stub: brief startForeground() → immediate stopForeground() + stopSelf()
pro/worker/DetailedInfoWorker.java MODIFY Replace startForegroundService(LocationService) with LocationWorker.scheduleOneShot()
service/PushLongPollingService.java MODIFY Remove startAsForeground() call entirely
worker/PushNotificationWorker.java MODIFY Add doPushPoll() with short timeout in doLongPollingWork()
helper/Initializer.java MODIFY Schedule LocationWorker on startup and after config sync
AndroidManifest.xml MODIFY Remove foregroundServiceType from PushLongPollingService
Verification Plan
Build
Clean build — zero compile errors.
Device testing
Install APK → confirm zero notifications in status bar and shade.
Wait 15 min → confirm location updated in admin panel.
Send push from admin panel → confirm it arrives within 15 min.
Reboot device → confirm no notification on boot.
adb logcat | grep HeadwindMDM → no startForeground errors, no ANR traces.
Regression
WorkTime enforcement (app blocking) — unaffected, verify policy still enforces.
OTA app install via push — verify push arrives and APK installs.
Open Questions
IMPORTANT

Push latency: With PushLongPollingService no longer persistent, push on the long-polling path has up to 15-min delay. Is that acceptable? If near-real-time push is needed, the MQTT path (already in BuildConfig) is the right solution — it runs silently and can also be fully de-notified using the same silent-channel technique.

IMPORTANT

Location freshness: Location will now use the OS-cached last-known fix. If GPS was off for hours, the cached location may be stale. Should we request a fresh one-shot GPS fix before each upload (adds ~5–30 sec latency per run)?

NOTE

MQTT foreground service: If MQTT push is in use, MqttService (Eclipse Paho) also has a foreground notification. That can be silenced the same way. Confirm if you want that covered in this implementation.
