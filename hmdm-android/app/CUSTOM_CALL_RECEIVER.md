# Custom Call Receiver (Default-Dialer Incoming-Call UI)

Universal custom incoming-call receiver for Brother Pharmamach MDM. The app becomes the
**default dialer** (Decision A) and — provisioned as **Device Owner** (Decision B) — sets that
role and grants all call permissions with **zero user prompts**.

## Architecture (the one committed model)

Telecom delivers every cellular call to `CustomInCallService.onCallAdded()`. From there a single
mechanism drives all three scenarios:

1. A brief `PARTIAL_WAKE_LOCK` (Doze safety net) so the notification posts and the Activity can
   take the screen.
2. `startForeground(FOREGROUND_SERVICE_TYPE_PHONE_CALL)` with a high-importance notification that
   carries `setFullScreenIntent(pi, true)` to `IncomingCallActivity` (CATEGORY_CALL,
   IMPORTANCE_HIGH, bypass-DnD, answer/decline actions → `CallActionReceiver`).
3. A direct `startActivity()` fast-path (allowed because the dialer-role InCallService is
   BAL-exempt and the DO has disabled the keyguard) so foreground scenarios are instant.

`IncomingCallActivity` bypasses the keyguard / turns the screen on (version-split, §4) and shows
answer/decline while ringing, then mute/speaker/hang-up once active. All surfaces route through the
`CallManager` singleton, which owns the live `android.telecom.Call`.

## Files

| File | Role |
|---|---|
| `phone/CallManager.java` | Singleton owning the active Call; answer/reject/hangup/mute/speaker; listener fan-out |
| `service/CustomInCallService.java` | `InCallService`; FSI notification + FGS + direct launch |
| `ui/IncomingCallActivity.java` | Full-screen UI; version-split wake/keyguard; full call lifecycle |
| `ui/DialerActivity.java` | Minimal DIAL handler so the app qualifies for the dialer role |
| `receiver/CallActionReceiver.java` | Notification answer/decline/end actions |
| `helper/DefaultDialerHelper.java` | 3-branch dialer request + DO silent set + permission grants + verify |
| `res/layout/activity_incoming_call.xml` + `res/drawable/ic_call_*`, `bg_call_*` | UI |
| `helper/DevicePolicyBootstrapper.java` | Calls `ensureDefaultDialer()` last during provisioning |
| `receiver/BootReceiver.java` | Re-asserts dialer role + re-grants perms after boot/OTA |

## Version-split behavior

- **Default dialer** (`DefaultDialerHelper`): API 29+ → `RoleManager.createRequestRoleIntent(ROLE_DIALER)`;
  API 23–28 → `TelecomManager.ACTION_CHANGE_DEFAULT_DIALER`; Device Owner → reflective silent set
  (`RoleManager.addRoleHolderAsUser`, then legacy `TelecomManager.setDefaultDialer`), else falls
  back to the interactive request. Verified via `RoleManager.isRoleHeld` / `getDefaultDialerPackage`.
- **Screen-on / keyguard** (`IncomingCallActivity`): API 27+ → `setShowWhenLocked(true)` +
  `setTurnScreenOn(true)` + `requestDismissKeyguard`; API 23–26 → `FLAG_SHOW_WHEN_LOCKED |
  FLAG_TURN_SCREEN_ON | FLAG_DISMISS_KEYGUARD | FLAG_KEEP_SCREEN_ON`. Plus service wake lock.
- **FGS type**: `phoneCall` type passed to `startForeground` on API 29+; enforced by OS on 34+.
- **Call control**: `Call.answer/reject/disconnect` (API 23+); state read via
  `Call.getDetails().getState()` on API 31+, `Call.getState()` below.

## Multi-SIM (dual SIM)

Fully supported. As the default dialer we host `InCallService`, so Telecom hands us **every**
incoming call regardless of which SIM slot it arrives on — there is no per-SIM registration to do.
`CallManager.getSimLabel()` resolves the call's `PhoneAccountHandle` → carrier / "SIM 1" / "SIM 2"
label and it is shown on both the full-screen UI status line and the notification. Answer/decline
control the correct call because we act on the exact `android.telecom.Call` object Telecom delivered.

## Permissions — auto if possible, prompt if needed

`DefaultDialerHelper.ensureCallSetup(activity)` (called from `MainActivity.onResume`) does the right
thing per deployment:

- **Device Owner** → everything is granted **silently** (`setPermissionGrantState` for
  READ_PHONE_STATE, CALL_PHONE, ANSWER_PHONE_CALLS, READ_CONTACTS, READ_CALL_LOG,
  POST_NOTIFICATIONS) and the dialer role is set silently; USE_FULL_SCREEN_INTENT rides in with the
  role. **Zero popups.**
- **Not Device Owner** → the user is prompted, one stage per resume so only one system dialog is up
  at a time: (1) runtime permission popup for the missing dangerous perms, (2) the system
  default-dialer role dialog, (3) on API 34+ the `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` page if
  the OS still withholds FSI. Each prompt fires **at most once** (SharedPreferences-guarded) so the
  user is never nagged on every resume.

## Cross-OEM resilience (§6)

- Battery / autostart: reuses existing `OemCompatHelper.tryEnableAutostart()` (MIUI, ColorOS/OPPO,
  Vivo, EMUI, One UI) and `OemCompat.getBatterySettingsIntent()`. Device Owner exempts silently.
- POST_NOTIFICATIONS: `DefaultDialerHelper.grantCallPermissions()` **grants** it via DPM
  (overriding the kiosk-default deny) so the FSI notification is delivered on API 33+.
- Lock-task: our package (now the default dialer) is already in the `getPhoneCallPackages()`
  whitelist, so the incoming-call UI is not blocked in COSU mode.
- Boot/OTA: `BootReceiver` re-runs `ensureDefaultDialer()`.

## Honesty clause (residual limits when NOT Device Owner)

- Silent dialer set is only guaranteed on platform-signed / privileged builds (this app ships
  `sharedUserId` + privileged telephony perms). On a plain DO install the one-time system role
  dialog is required — call `DefaultDialerHelper.requestDefaultDialer(activity, requestCode)` from a
  setup screen and observe `onActivityResult`.
- On API 34+ a non-role app must be sent to `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`;
  not needed here because the dialer role auto-grants USE_FULL_SCREEN_INTENT.
- OEM autostart killers can still terminate a non-DO process; DO exemption is the only unconditional
  fix.
- Below API 23 (API 21/22) there is no `InCallService`; the components are inert and calls use the
  stock dialer. `minSdk` is 21, so the app installs, but this feature engages only from API 23.

## Build status

`./gradlew :app:assembleEnterpriseDebug` — **BUILD SUCCESSFUL**. All four components verified
present in the merged manifest.

## Per-API-level test log (§8) — to be filled on physical devices

| API | Device | 1. Off+Doze wake | 2. Our app fg | 3. Other app fg | 4. Answer/reject | 5. Reboot re-arm | 6. Zero prompts (DO) |
|-----|--------|------------------|---------------|-----------------|------------------|------------------|----------------------|
| 23  |        |                  |               |                 |                  |                  |                      |
| 26  |        |                  |               |                 |                  |                  |                      |
| 28  | Samsung|                  |               |                 |                  |                  |                      |
| 30  | Vivo   |                  |               |                 |                  |                  |                      |
| 33  | Realme |                  |               |                 |                  |                  |                      |
| 34  | Xiaomi |                  |               |                 |                  |                  |                      |
| 36  | Pixel  |                  |               |                 |                  |                  |                      |

Fill each cell PASS/FAIL with notes. Acceptance requires PASS across the row for every device.
