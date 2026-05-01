# MDM Bug Fix Implementation Plan

---

## Issue 1 — Apps Accessible via Notifications During Work Time

**Root Cause:**  
`CheckForegroundApplicationService` and `CheckForegroundAppAccessibilityService` are both **stubs** (open-source version). They do nothing. When a user taps a push notification, the OS launches the target app directly and nothing intercepts it.

**Fix Plan:**

### A. Implement a real `AccessibilityService`-based foreground app monitor
File: `pro/service/CheckForegroundAppAccessibilityService.java`  
- Extend `AccessibilityService` instead of `Service`
- Override `onAccessibilityEvent()` — on `TYPE_WINDOW_STATE_CHANGED`, get the current package name
- Check `WorkTimeManager.getInstance().isAppAllowed(packageName)`
- If blocked: broadcast `Const.ACTION_HIDE_SCREEN` with the package name → the existing `MainActivity` receiver already handles this by showing the "not allowed" overlay and bringing the launcher forward
- Register in manifest with proper `<accessibility-service>` meta-data and auto-start

### B. Implement `UsageStats`-based polling service as fallback
File: `pro/service/CheckForegroundApplicationService.java`  
- Extend `Service` with a `ScheduledExecutorService` that polls `UsageStatsManager` every ~1–2 seconds
- When foreground package changes: check `WorkTimeManager.getInstance().isAppAllowed(packageName)`
- If blocked: same broadcast as above
- This handles devices where Accessibility permission is not granted

### C. Cancel notification intents from restricted apps (best effort)
File: New `service/WorkTimeNotificationListenerService.java`  
- Implement `NotificationListenerService`
- In `onNotificationPosted()`: if `WorkTimeManager.getInstance().isAppAllowed(sbn.getPackageName())` returns false → call `cancelNotification(sbn.getKey())` to suppress the notification entirely during work time
- Add to manifest with `BIND_NOTIFICATION_LISTENER_SERVICE` permission

---

## Issue 2 — Apps Accessible via Recents Screen

**Root Cause:** Same as Issue 1 — no foreground monitoring.

**Fix Plan:**  
The `AccessibilityService` from Issue 1 already intercepts apps launched from recents. Additionally:
- When work time becomes active (`shouldRefreshUI()` returns `true` and transitions to work time in `stateChangeReceiver`), use `ActivityManager.getAppTasks()` and `ActivityManager.moveTaskToBack()` or `killBackgroundProcesses()` on restricted packages that are already in the task stack.
- Use `DevicePolicyManager.setPackagesSuspended()` (available to Device Owner) to hard-suspend restricted packages for the duration of work time.

---

## Issue 3 — Apps Continue Running Across Work Time Boundary

**Root Cause:**  
The `stateChangeReceiver` listens to `ACTION_TIME_TICK` and calls `WorkTimeManager.shouldRefreshUI()`. When this transitions to work time, it calls `showContent()` which redraws the launcher UI — but it does **not** close already-running apps.

**Fix Plan:**

### A. Close/suspend apps at work-time boundary
File: `util/WorkTimeManager.java` — add a new method `closeRestrictedApps(Context context)`  
File: `ui/MainActivity.java` — in `stateChangeReceiver`, after `showContent()` on work-time transition, call `WorkTimeManager.getInstance().closeRestrictedApps(context)`

Inside `closeRestrictedApps()`:
1. If Device Owner: use `DevicePolicyManager.setPackagesSuspended()` to suspend all restricted packages at once. This freezes them immediately.
2. Fallback: use `ActivityManager.killBackgroundProcesses(pkg)` for each restricted app (requires `KILL_BACKGROUND_PROCESSES` permission, add to manifest)
3. Use `ActivityManager.getRunningTasks()` / `getRunningAppProcesses()` to find what's actually in the foreground, and if it's restricted, bring the MDM launcher to the front with `FLAG_ACTIVITY_NEW_TASK`

### B. Register `ACTION_USER_PRESENT` receiver
File: `ui/MainActivity.java`  
- When the screen is unlocked after work time has started, trigger `closeRestrictedApps()` and refresh the UI.

---

## Issue 4 — Apps Accessible via Play Store or External Links (Deep Links)

**Root Cause:** Same as Issues 1 & 2 — no foreground monitoring intercepts intent launches from Play Store or external apps.

**Fix Plan:**

The `AccessibilityService` from Issue 1 handles this generically — it fires on every `TYPE_WINDOW_STATE_CHANGED` event regardless of launch origin.

Additionally, for Device Owner deployments:  
**Use `DevicePolicyManager.setPackagesSuspended()`** at the start of each work-time enforcement window. A suspended package cannot be launched from anywhere — Play Store, deep links, or any other source. Unsuspend them when work time ends.

File: `util/WorkTimeManager.java` — add `applySuspension(Context context)` / `removeSuspension(Context context)` called from `closeRestrictedApps()` and in `stateChangeReceiver` when transitioning out of work time.

---

## Issue 5 — Favorites Page Allows Access to Restricted Apps After Unlock

**Root Cause:**  
After screen unlock, `MainActivity.onResume()` runs. The `stateChangeReceiver` only listens to `ACTION_TIME_TICK` (every minute). If `WorkTimeManager.policy` hasn't been refreshed yet (e.g., first start or SIM/policy re-fetch pending), `isAppAllowed()` may incorrectly return `true`.

**Fix Plan:**

### A. Register `ACTION_USER_PRESENT` broadcast receiver
File: `ui/MainActivity.java` — add a new `userPresentReceiver` that registers/unregisters properly:
```java
intentFilter.addAction(Intent.ACTION_USER_PRESENT);
```
On receipt: call `WorkTimeManager.getInstance().updatePolicy(context, true)` then force-redraw: `showContent(settingsHelper.getConfig())`

### B. Force policy refresh on `onResume()`
File: `ui/MainActivity.java` — in `onResume()`, after existing setup, add:
```java
WorkTimeManager.getInstance().updatePolicy(this);
```

### C. Ensure `AppShortcutManager` uses the most up-to-date policy
Already calls `WorkTimeManager.getInstance().updatePolicy(context)` in `getConfiguredApps()` — this is correct but may be reading from cache. Ensure the policy is freshly evaluated each time `getInstalledApps()` is called.

---

## Issue 6 — App Icons Shuffle / Change Position Frequently

**Root Cause:**  
In `AppShortcutManager.getInstalledApps()`, apps are sorted by `screenOrder` (from server config). Apps from `PackageManager.getInstalledApplications()` without a configured `screenOrder` get `null` — and `AppInfosComparator` sorts `null` to the end but **does not apply a stable secondary sort**. `PackageManager.getInstalledApplications()` returns apps in an arbitrary, non-deterministic order that changes between calls.

**Fix Plan:**

File: `ui/AppShortcutManager.java` — update `AppInfosComparator.compare()`:
- When both `screenOrder` are null: use `o1.name.compareToIgnoreCase(o2.name)` as stable secondary sort
- When one is null: keep null at the end

This makes the order deterministic and alphabetical for any app that doesn't have an explicit `screenOrder`.

Additionally, for admin-configured apps (from `requiredPackages` map) where `screenOrder` is explicitly set, the sort already works. The shuffling only affects the "all other installed apps" case.

**Bonus:** Persist and lock the resolved order in `SharedPreferences` so it only changes when the app list changes (optional, lower priority).

---

## Issue 7 — Frequent "Brother MDM Not Responding" ANR

**Root Cause (multi-factor):**

1. **`WorkTimeManager.updatePolicy()` called on main thread via `AppShortcutManager.getConfiguredApps()`** — `getConfiguredApps()` calls `WorkTimeManager.getInstance().updatePolicy(context)` which can trigger `maybeFetchPolicyFromServer()` → `NETWORK_EXECUTOR.execute(...)`. This is async but the method itself may block briefly.

2. **`showContent()` called excessively** — In `stateChangeReceiver.onReceive()` for `ACTION_TIME_TICK` (every minute on main thread), it calls `applyEarlyPolicies()` which calls `Initializer.applyEarlyNonInteractivePolicies()`. This may do heavy work synchronously.

3. **`stateChangeReceiver` doing synchronous work on main thread for every connectivity change** — Each `CONNECTIVITY_ACTION` calls `applyEarlyPolicies()` on the main thread.

4. **`GetServerConfigTask` and heavy `AsyncTask` chains** — While `AsyncTask` uses a background thread, callbacks (`onPostExecute`) run on the main thread and can cause cascading UI updates.

5. **ANRWatchDog is disabled** (commented out in `MainActivity.onCreate()`) so ANRs come from the real Android OS ANR detection (main thread blocked > 5 seconds).

**Fix Plan:**

### A. Move `applyEarlyPolicies` off the hot path in `stateChangeReceiver`
File: `ui/MainActivity.java` — wrap the `applyEarlyPolicies()` call in `stateChangeReceiver` in `handler.post(() -> ...)` to yield to the main loop, or better: move heavy initialization logic to a background thread via `AsyncTask`/`ExecutorService`.

### B. Debounce `showContent()` calls
File: `ui/MainActivity.java` — add a debounce guard: if the last `showContent()` call was less than 500ms ago (from `ACTION_TIME_TICK`), skip it.

### C. Off-load `Initializer.applyEarlyNonInteractivePolicies()` where possible
File: `helper/Initializer.java` — audit for synchronous I/O or policy checks that can be async.

### D. Fix `AppShortcutManager.getConfiguredApps()` — do not trigger network calls from main thread
File: `ui/AppShortcutManager.java` — change `WorkTimeManager.getInstance().updatePolicy(context)` call to `updatePolicy(context, false)` with the rate limiter already in place, and ensure it never blocks the thread.

### E. Re-enable `ANRWatchDog` with a custom reporter (optional but valuable for debugging)
File: `ui/MainActivity.java` — uncomment `anrWatchDog = new ANRWatchDog()` and set a custom `ANRListener` that logs to `RemoteLogger` instead of crashing, to help diagnose remaining ANRs.

---

## Issue 8 — Phone Numbers Not Displayed in Admin Panel

**Root Cause:**  
`DeviceInfoProvider.getPhoneNumber(context, 0)` uses `SubscriptionManager.getActiveSubscriptionInfoList().get(0).getNumber()`. On most Android devices — especially modern ones — the phone number is **not stored on the SIM card** (it's carrier-provisioned). `SubscriptionInfo.getNumber()` returns `""` or `null` in those cases.

On Android 11+, `TelephonyManager.getLine1Number()` requires `READ_PHONE_NUMBERS` permission (already declared in manifest) but the method must be called through `TelephonyManager.createForSubscriptionId(subId)` to work reliably on multi-SIM devices.

**Fix Plan:**

File: `util/DeviceInfoProvider.java` — enhance `getPhoneNumber(Context context, int slot)`:

```
Priority 1: SubscriptionInfo.getNumber()  (current approach)
Priority 2: TelephonyManager.createForSubscriptionId(subId).getLine1Number()  (Android 5.1+)
Priority 3: TelephonyManager.getLine1Number() for slot 0 as last resort
Priority 4: Persist last known non-empty number in SharedPreferences keyed by ICCID and fall back to it
```

Concretely:
1. After getting `subscriptionList.get(slot)`, use `subInfo.getSubscriptionId()` to get `subId`
2. Call `telephonyManager.createForSubscriptionId(subId).getLine1Number()` (API 24+) — more reliable than `SubscriptionInfo.getNumber()`
3. If still empty, use reflection to call the hidden `ITelephony.getLine1NumberForDisplay()` (available on some OEMs)
4. Persist any non-empty phone number to `SharedPreferences` keyed by ICCID; on subsequent calls, return the cached value if the live query returns empty

**Server side** (if phone number display is broken in admin panel independently):  
Check `DeviceResource.java` (or equivalent) in the server module — ensure the `phone` field from `DeviceInfo` is not filtered/overwritten before being stored in the DB.

---

## Summary Table

| # | Issue | Files Changed | Approach |
|---|-------|---------------|----------|
| 1 | Notifications bypass work time | `CheckForegroundAppAccessibilityService.java`, `WorkTimeNotificationListenerService.java` (new), `AndroidManifest.xml` | Real AccessibilityService + NotificationListenerService |
| 2 | Recents bypass work time | Same as #1 + `WorkTimeManager.java` | Foreground monitoring + DPM suspension |
| 3 | App runs across work-time boundary | `WorkTimeManager.java`, `MainActivity.java` | `setPackagesSuspended()` + `closeRestrictedApps()` at transition |
| 4 | Play Store / deep links bypass | Same as #1 + `WorkTimeManager.java` | AccessibilityService + DPM suspension covers all entry points |
| 5 | Favorites show restricted apps | `MainActivity.java` | `ACTION_USER_PRESENT` receiver + force policy refresh on resume |
| 6 | App icons shuffle | `AppShortcutManager.java` | Stable secondary alphabetical sort in `AppInfosComparator` |
| 7 | ANR dialogs | `MainActivity.java`, `AppShortcutManager.java`, `Initializer.java` | Debounce `showContent()`, move heavy work off main thread, re-enable ANRWatchDog with remote logger |
| 8 | Phone number missing | `DeviceInfoProvider.java` | Multi-method fallback chain + SharedPreferences caching by ICCID |
