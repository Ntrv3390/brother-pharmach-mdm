Implement Robust WorkTime App Suspension
This plan addresses the bugs in the WorkTime plugin where restricted apps "flash" when opened from the recent apps screen, can be accessed via notifications, or remain running in the background during transitions.

User Review Required
IMPORTANT

This plan will change the enforcement mechanism from manually closing apps (which causes flashing) to fully suspending the apps at the OS level (when the app has Device Owner privileges).

Behavior Changes:

Suspended apps cannot be opened from notifications or widgets.
If a suspended app is tapped from the Recents screen (before it gets removed), the Android OS will show a native toast like "App isn't available right now" instead of opening and flashing.
Active apps will be immediately closed when their worktime window ends.
Please confirm if using OS-level App Suspension is acceptable.

Open Questions
None at this time.

Proposed Changes
WorkTime plugin (hmdm-android)
[MODIFY] WorkTimeManager.java
(Path: app/src/main/java/com/brother/pharmach/mdm/launcher/util/WorkTimeManager.java)

We will rewrite enforceWorkTimeRestrictions(Context) to efficiently suspend restricted apps and unsuspend allowed apps, rather than only unsuspending apps when enforcement is entirely disabled.

Segregate Apps: Iterate over all installed, launchable, non-infrastructure packages. For each package, determine if it is currently allowed (using isAppAllowed(pkg) which natively handles both during worktime and outside worktime lists).
Apply Device Policy:
If isDeviceOwner is true, use dpm.setPackagesSuspended() (Android N+) to batch-suspend all restricted packages, and batch-unsuspend all allowed packages.
If Android version is Lollipop/Marshmallow (API 21-23), use dpm.setApplicationHidden() individually.
Fallback Mechanism: For devices without Device Owner privileges, fallback to ActivityManager.killBackgroundProcesses() and forceStopPackage() for restricted apps.
Clean Recents: Call removeRestrictedFromRecents(context) to actively remove the suspended/restricted apps from the Android Recents UI, ensuring the Recents screen remains clean and accessible.
Verification Plan
Automated/Code Verification
Ensure dpm.setPackagesSuspended uses batch arrays to minimize IPC overhead and prevent crashes.
Ensure try/catch blocks surround all reflection and IPC calls to guarantee it never crashes.
Manual Verification
Recent Screen Test: Open a restricted app (prior to the worktime window). Wait for worktime to start. Open Recents and verify the app cannot be launched and does not flash.
Notification Test: Trigger a notification for an app. Transition into worktime where the app is restricted. Tap the notification and verify the app does not open.
Transition Test: Leave an app open in the foreground. Wait for the worktime window to change such that the app becomes restricted. Verify the app is immediately killed and forced to the background.
During/After Worktime Test: Ensure that policies accurately respect both allowedDuring and allowedOutside lists.