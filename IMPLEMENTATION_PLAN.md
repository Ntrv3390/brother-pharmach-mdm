Fix Recurring Crashes in Location and Push Services
The app is experiencing recurring crashes ("moves to recent screen") because of how foreground services were handled in the recent "Silent Location & Push" implementation. Specifically, services are being started as foreground services but failing to post a valid visible notification, or being started as foreground services while the app is in the background, leading to ForegroundServiceStartNotAllowedException or ForegroundServiceDidNotStartInTimeException.

User Review Required
IMPORTANT

This change will completely disable the legacy LocationService as a running process. All location tracking is now handled by LocationWorker (WorkManager) at 15-minute intervals. The LocationService class is kept only as a utility for the sendLocations method.

Proposed Changes
[Component: Location & Push Service Stability]
[MODIFY]
MainActivity.java
Remove startLocationServiceWithRetry() and startLocationService() methods.
Remove calls to startLocationServiceWithRetry() from onPoliciesUpdated() and onActivityResult().
Rationale: Since location is now handled by LocationWorker, starting the LocationService stub on every policy update is redundant and causes crashes on Android 12+.
[MODIFY]
ConfigUpdater.java
In startLongPollingService(), use context.startService(serviceStartIntent) instead of context.startForegroundService().
Rationale: PushLongPollingService no longer calls startForeground(). Starting it with startForegroundService() without a subsequent startForeground() call causes the app to crash.
[MODIFY]
LocationService.java
Change NotificationManager.IMPORTANCE_NONE to NotificationManager.IMPORTANCE_MIN.
Add a safety check in onStartCommand to ensure stopSelf() is called even if an exception occurs.
Rationale: IMPORTANCE_NONE is often rejected for foreground services on modern Android versions (Target SDK 35). IMPORTANCE_MIN is the safest "silent" level.
Verification Plan
Automated Tests
Build the APK and ensure it compiles without errors.
Monitor adb logcat for any ForegroundService related exceptions.
Manual Verification
Install the app and confirm it no longer crashes and exits the launcher.
Verify that the "Location icon" does not appear in the status bar.
Verify that location is still being sent to the server (check admin panel after 15 minutes).
Send a push notification and verify it is processed (even if delayed by up to 15 minutes if polling).
