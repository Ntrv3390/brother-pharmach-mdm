Here's the fully refined prompt incorporating all corrections:

---

**AI Agent Prompt — Android MDM Battery Optimization Gatekeeper (API 23–35+, Java)**

**Role & Context**

You are a senior Android engineer specializing in Enterprise/MDM solutions with deep expertise in Android API fragmentation across a decade of OS versions. I am building a production-grade Android **Device Owner** application in **Java** that must run correctly on every device from **Android 6.0 (API 23) through Android 15+ (API 35+)**. The goal is a tamper-resistant "Blocking Gatekeeper" that enforces battery optimization exemption as a hard prerequisite, surviving reboots, OS kills, manufacturer ROM quirks, and user bypass attempts across all target API levels.

My package name is: `com.brother.pharmach.mdm`

**Language requirement: All code must be Java. Do not generate any Kotlin. Do not suggest migrating to Kotlin.**

---

**Critical Corrections — Read Before Writing Any Code**

These are known pitfalls that must be reflected accurately throughout every deliverable:

**1. `OnBackPressedCallback` is an AndroidX construct, not a platform API.**
It is provided by the `androidx.activity:activity` library, not by the Android SDK at any specific API level. It works on API 23+ as long as the AndroidX dependency is present. Do not frame it as "API 33+ behavior." The correct framing is: use `OnBackPressedCallback` from AndroidX on all API levels where `AppCompatActivity` is used. The deprecated `onBackPressed()` override is only needed as a fallback if the project does not use `AppCompatActivity`.

Required dependency — include this in `build.gradle`:
```groovy
dependencies {
    implementation 'androidx.activity:activity:1.8.0' // or latest stable
}
```

**2. `START_STICKY` does not guarantee service survival on aggressive OEM ROMs.**
While `START_STICKY` instructs the OS to restart the service after it is killed, this guarantee only holds on stock Android. On MIUI, ColorOS, One UI, and EMUI, the OS-level battery manager can and will kill services regardless of `START_STICKY`. The only reliable mitigation on these ROMs is a combination of: Device Owner `setAlwaysOnVpnPackage` exemptions, ADB shell whitelisting (see Deliverable 5), and user-facing instructions to enable manufacturer-specific auto-start settings. Document this limitation with `// OEM-QUIRK:` comments everywhere `START_STICKY` is used.

**3. `setLockTaskPackages()` is a persistent Device Owner policy — not a per-launch call.**
Once set, it persists across reboots until explicitly changed. It must be called once during initial MDM provisioning, or when the policy changes. It does not need to be re-called on every app launch. `DevicePolicyBootstrapper.applyPolicies()` must include a guard that checks whether the policy is already applied before calling it, to avoid unnecessary DPC round-trips.

**4. Check `getLockTaskModeState()` before calling `startLockTask()`.**
Calling `startLockTask()` when LockTask mode is already active throws `IllegalStateException`. Always query `ActivityManager.getLockTaskModeState()` first and only call `startLockTask()` if the result is `LOCK_TASK_MODE_NONE`.

**5. The `<property>` tag inside `<service>` is mandatory for API 34+ Play Store submission.**
The `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property must be declared inside the `<service>` block — not alongside it. Generating the permission alone is insufficient. Generate both.

---

**API Compatibility Matrix — Consult Before Every Code Block**

| Feature | API 23–25 | API 26–28 | API 29–30 | API 31–33 | API 34–35+ |
|---|---|---|---|---|---|
| `isIgnoringBatteryOptimizations` | Available | Available | Available | Available | Available |
| `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Available | Available | Available | Available | Available |
| `ForegroundService` declaration required | No | **Yes** | Yes | Yes | Yes |
| `foregroundServiceType` attribute | No | No | No | Yes (optional) | **Yes (required)** |
| `FOREGROUND_SERVICE_SPECIAL_USE` permission | No | No | No | No | **Yes (required)** |
| `<property>` tag inside `<service>` | No | No | No | No | **Yes (Play Store required)** |
| `OnBackPressedCallback` (AndroidX) | **Yes (via androidx.activity)** | Yes | Yes | Yes | Yes |
| `onBackPressed()` override (platform) | OK | OK | OK | OK | Deprecated |
| `getLockTaskModeState()` before `startLockTask()` | **Always required** | Always required | Always required | Always required | Always required |
| `setLockTaskPackages()` persistence | Persistent | Persistent | Persistent | Persistent | Persistent |
| `exported` on receivers | Not required | Not required | Not required | **Required** | **Required** |
| `START_STICKY` reliability | OEM-dependent | OEM-dependent | OEM-dependent | OEM-dependent | OEM-dependent |
| `SCHEDULE_EXACT_ALARM` for exact timers | Not required | Not required | Not required | **Required** | **Required** |

---

**Deliverable 1 — Detection Service (`BatteryOptimizationMonitor.java`)**

Create `BatteryOptimizationMonitor` extending `Service` with:

- `android:foregroundServiceType="specialUse"` declared in the manifest with `tools:targetApi="34"`, plus the mandatory `<property>` tag nested inside the `<service>` declaration
- A `Handler` attached explicitly to `Looper.getMainLooper()` — never rely on the implicit looper assumption — driving a `Runnable` polling loop at a configurable interval (default 30 seconds)
- The polling `Runnable` calls `PowerManager.isIgnoringBatteryOptimizations("com.brother.pharmach.mdm")` on every tick and broadcasts the result via `LocalBroadcastManager`
- On non-compliance detected: call `startComplianceEnforcement()` which starts `ComplianceGatekeeperActivity` with `FLAG_ACTIVITY_NEW_TASK`
- On compliance restored: broadcast `Constants.ACTION_COMPLIANCE_RESTORED`
- `NotificationChannel` creation inside `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)` before `startForeground()` — annotate with `// API-DIFF: Android 8.0 (API 26)`
- `START_STICKY` returned from `onStartCommand()` with this comment block:

```java
// START_STICKY: instructs the OS to restart this service after it is killed,
// passing null as the intent. This is correct for a polling loop that does not
// depend on the triggering intent.
// OEM-QUIRK: On MIUI, ColorOS, One UI, and EMUI, START_STICKY alone is
// insufficient. These ROMs kill services via their own battery managers
// independently of the Android OS service lifecycle. See README.md for
// ADB-based whitelisting commands that are required on these devices.
return START_STICKY;
```

- `stopSelf()` called if the service detects it has been explicitly stopped by the DPC (check via a `boolean mStoppedByDpc` flag set before calling `stopSelf()` in a public method the DPC calls)
- The `Handler` loop cancelled in `onDestroy()` via `mHandler.removeCallbacks(mPollingRunnable)` to prevent leaks
- `ContextCompat.startForegroundService()` used in `BootReceiver` — annotate with `// API-DIFF: Android 8.0 (API 26) — background service start restriction`

**Boot and kill recovery — two receivers in the manifest:**
- `ACTION_BOOT_COMPLETED` → start service
- `ACTION_MY_PACKAGE_REPLACED` → restart service after self-update

---

**Deliverable 2 — Blocking Activity (`ComplianceGatekeeperActivity.java`)**

Create `ComplianceGatekeeperActivity` extending `AppCompatActivity` with:

**Manifest attributes:**
```xml
android:launchMode="singleTask"
android:excludeFromRecents="true"
android:showOnLockScreen="true"
android:turnScreenOn="true"
android:screenOrientation="portrait"
android:exported="false"
```

**Back button — AndroidX `OnBackPressedCallback` on all API levels (not API-gated):**
```java
// OnBackPressedCallback is from androidx.activity:activity — NOT a platform API.
// It works on API 23+ via AndroidX and does not require an API level check.
// Add dependency: implementation 'androidx.activity:activity:1.8.0'
getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
    @Override
    public void handleOnBackPressed() {
        // no-op — this gatekeeper cannot be dismissed by the user
    }
});

// Retain onBackPressed() override ONLY as a safety net for edge cases
// where AppCompatActivity's dispatcher is bypassed (e.g. some OEM gesture nav implementations)
@Override
@SuppressWarnings("MissingSuperCall")
public void onBackPressed() {
    // no-op — intentionally suppressed; enforced by OnBackPressedCallback above
}
```

**LockTask — always check state before calling:**
```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_compliance_gatekeeper);
    tryStartLockTask();
}

private void tryStartLockTask() {
    if (!isDeviceOwner()) {
        Log.w(TAG, "Not Device Owner — LockTask unavailable, using soft blocking only");
        return;
    }
    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
    // getLockTaskModeState() available from API 23
    if (am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
        try {
            startLockTask();
        } catch (IllegalStateException e) {
            // setLockTaskPackages() was not called yet — policy may still be propagating
            // OEM-QUIRK: On some Samsung One UI builds, there is a race between
            // DevicePolicyManager applying policies and Activity startup. Retry after 500ms.
            Log.e(TAG, "startLockTask() failed: " + e.getMessage());
            new Handler(Looper.getMainLooper()).postDelayed(this::tryStartLockTask, 500);
        }
    }
    // If getLockTaskModeState() != LOCK_TASK_MODE_NONE, LockTask is already active — no-op
}
```

**Home button defense:**
```java
@Override
protected void onUserLeaveHint() {
    super.onUserLeaveHint();
    // Fires when the Home button is pressed or the app is sent to background
    Intent reopen = new Intent(this, ComplianceGatekeeperActivity.class);
    reopen.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    startActivity(reopen);
}
```

**Window focus loss defense:**
```java
@Override
public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (!hasFocus && !isFinishing()) {
        // Covers: gesture navigation pulling down notification shade,
        // Recents on non-LockTask path, assistant overlay activation
        Intent reopen = new Intent(this, ComplianceGatekeeperActivity.class);
        reopen.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(reopen);
    }
}
```

**Settings navigation:**
```java
private void openBatterySettings() {
    // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + package URI opens the per-app
    // exemption dialog directly — available from API 23
    // Do NOT use ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (opens full list)
    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
    intent.setData(Uri.parse("package:com.brother.pharmach.mdm"));
    try {
        startActivity(intent);
    } catch (ActivityNotFoundException e) {
        // OEM-QUIRK: Some heavily modified ROMs (certain EMUI builds) do not
        // expose this settings screen. Fall back to general battery settings.
        startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
    }
}
```

**Compliance receiver — register in `onResume`, unregister in `onPause`:**
```java
private final BroadcastReceiver mComplianceReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Constants.ACTION_COMPLIANCE_RESTORED.equals(intent.getAction())) {
            ActivityManager am =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                stopLockTask();
            }
            finish();
        }
    }
};
```

---

**Deliverable 3 — Complete `AndroidManifest.xml`**

```xml
<!-- Permissions — each annotated with minimum API and purpose -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<!-- API 26+: required to call startForeground() -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<!-- API 34+: required for foregroundServiceType="specialUse" -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<!-- All APIs: required to receive boot broadcast -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>

<application ...>

    <!-- foregroundServiceType only enforced by OS on API 34+; tools:targetApi avoids lint errors -->
    <service
        android:name=".BatteryOptimizationMonitor"
        android:exported="false"
        android:foregroundServiceType="specialUse"
        tools:targetApi="34">
        <!-- MANDATORY for Play Store on API 34+: must be nested inside <service>, not alongside it -->
        <property
            android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="MDM compliance enforcement — ensures battery optimization exemption
                           is active so the device management service can monitor device state
                           continuously without OS-imposed restrictions."
            tools:targetApi="34"/>
    </service>

    <!-- exported=true required on API 31+ for implicit broadcast receivers -->
    <receiver
        android:name=".BootReceiver"
        android:exported="true">
        <intent-filter android:priority="999">
            <action android:name="android.intent.action.BOOT_COMPLETED"/>
            <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
        </intent-filter>
    </receiver>

    <!-- showOnLockScreen + turnScreenOn: ensures gatekeeper appears even on locked devices -->
    <activity
        android:name=".ComplianceGatekeeperActivity"
        android:launchMode="singleTask"
        android:excludeFromRecents="true"
        android:showOnLockScreen="true"
        android:turnScreenOn="true"
        android:screenOrientation="portrait"
        android:exported="false"/>

    <!-- MainActivity: exported=true required for launcher + MDM remote trigger -->
    <activity
        android:name=".MainActivity"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LAUNCHER"/>
        </intent-filter>
        <!-- Remote trigger from MDM server push or ADB: -->
        <!-- adb shell am start -a com.brother.pharmach.mdm.LAUNCH_BATTERY_SETTINGS -->
        <intent-filter>
            <action android:name="com.brother.pharmach.mdm.LAUNCH_BATTERY_SETTINGS"/>
            <category android:name="android.intent.category.DEFAULT"/>
        </intent-filter>
    </activity>

</application>
```

---

**Deliverable 4 — Device Owner Policy Initialization (`DevicePolicyBootstrapper.java`)**

```java
public static void applyPolicies(Context context) {
    DevicePolicyManager dpm =
        (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    ComponentName adminComponent =
        new ComponentName(context, MyDeviceAdminReceiver.class);

    if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
        Log.e(TAG, "Not Device Owner — policy application skipped");
        return;
    }

    // setLockTaskPackages() is PERSISTENT — it survives reboots and does not need
    // to be called on every launch. Call only during provisioning or policy changes.
    // Guard against redundant calls by checking the current policy first.
    String[] currentPackages = dpm.getLockTaskPackages(adminComponent);
    if (!Arrays.asList(currentPackages).contains(context.getPackageName())) {
        dpm.setLockTaskPackages(adminComponent,
            new String[]{ context.getPackageName() });
        Log.i(TAG, "LockTask policy applied");
    } else {
        Log.i(TAG, "LockTask policy already set — skipping");
    }

    // Auto-grant location permissions — API 23+ // API-DIFF: Android 6.0 (API 23)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        dpm.setPermissionGrantState(adminComponent, context.getPackageName(),
            Manifest.permission.ACCESS_FINE_LOCATION,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
    }

    // Background location — API-DIFF: Android 10.0 (API 29)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        dpm.setPermissionGrantState(adminComponent, context.getPackageName(),
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
    }

    // Disable keyguard for kiosk operation
    dpm.setKeyguardDisabled(adminComponent, true);

    // LockTask feature flags — API-DIFF: Android 11.0 (API 30)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        dpm.setLockTaskFeatures(adminComponent,
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS |
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD);
    }
}
```

---

**Deliverable 5 — Reboot & Kill Resilience + OEM README Block**

**Recovery chain:**
```
Device boots / app updated
        ↓
BootReceiver.onReceive()
  └─ ContextCompat.startForegroundService()   // API-DIFF: API 26 background restriction
        ↓
BatteryOptimizationMonitor.onStartCommand()
  └─ Immediate compliance check on first tick (do not wait for Handler delay)
        ↓
Non-compliant → startComplianceEnforcement()
                  → startActivity(ComplianceGatekeeperActivity, FLAG_ACTIVITY_NEW_TASK)
                  → tryStartLockTask() [checks getLockTaskModeState() first]
Compliant     → LocalBroadcast(ACTION_COMPLIANCE_RESTORED)
                  → ComplianceGatekeeperActivity: stopLockTask() → finish()
        ↓
MainActivity.onResume()
  └─ Independent synchronous compliance check (second safety net)
  └─ Redirect to ComplianceGatekeeperActivity if non-compliant
```

**Generate a `README.md` block with the following ADB commands.** Shell-based whitelisting is more reliable than UI-based settings navigation on restricted MDM environments and must be documented for field technicians:

```markdown
## ADB Whitelisting Commands for OEM Battery Manager Bypass

These commands must be run once per device after MDM enrollment, or pushed
via your MDM server's ADB/shell execution capability.

### Universal — Android Doze whitelist (all API levels)
adb shell cmd deviceidle whitelist +com.brother.pharmach.mdm

### Universal — Verify whitelist entry was accepted
adb shell cmd deviceidle whitelist

### Universal — Remote MDM trigger (test the intent filter)
adb shell am start -a com.brother.pharmach.mdm.LAUNCH_BATTERY_SETTINGS

### MIUI (Xiaomi) — Disable MIUI battery optimization for the package
adb shell dumpsys deviceidle whitelist +com.brother.pharmach.mdm
# Also push via MDM: Settings > Apps > Manage Apps > [App] > Battery Saver > No restrictions

### One UI (Samsung) — Add to never-sleeping apps
adb shell settings put global settings_never_sleeping_apps com.brother.pharmach.mdm

### ColorOS (OPPO/Realme) — Disable abnormal app detection
adb shell settings put secure oplus_smart_assistant_enable 0

### EMUI/HarmonyOS (Huawei) — Protected apps whitelist
adb shell pm enable com.brother.pharmach.mdm
# UI path: Phone Manager > Battery > App Launch > [App] > Manage manually > enable all

### Verify LockTask policy is active (Device Owner check)
adb shell dpm list-owners
```

---

**Code Quality Requirements — Java**

- **Min SDK 23, Target SDK 35** — every API-gated call wrapped in `Build.VERSION.SDK_INT >= Build.VERSION_CODES.X`; no `@RequiresApi` suppressions without a paired runtime guard
- **AndroidX dependency required:** `implementation 'androidx.activity:activity:1.8.0'` — document this in `build.gradle` comments
- All constants as `public static final String` in `Constants.java` — zero magic strings anywhere
- Use `WeakReference<Activity>` in the service when holding an activity reference to prevent memory leaks
- All `Handler` instances must explicitly pass `Looper.getMainLooper()` — never rely on implicit looper
- Wrap every `startActivity()` in `try-catch (ActivityNotFoundException)` — some restricted OEM builds suppress settings screens
- Annotate every API-level branch with `// API-DIFF: Android X.X (API NN)`
- Annotate every manufacturer-specific workaround with `// OEM-QUIRK: [Manufacturer / ROM]`
- Include `// POLICY-NOTE:` comments on every `DevicePolicyManager` call explaining persistence behavior and whether the call is idempotent