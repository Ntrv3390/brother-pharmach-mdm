# Mobile Data ON Enforcement — Current Implementation

**Project:** Brother Pharmamach MDM launcher (`hmdm-android`, package `com.brother.pharmach.mdm.launcher`)
**Goal:** When the server policy requires it and a valid SIM is present, keep **mobile data ON at all times**, lock the user out of turning it off, and — if it is off — lock the device down to force the user to turn it back on.
**Audience:** engineering, for further research. This documents *what exists today*, its behavior, and its known limits.

> This is a living description of the code as it currently stands. Where a mechanism is a
> no-op or unreliable on our deployment, it is called out explicitly so research effort goes to
> the right place.

---

## 0. Deployment & privilege context (read this first — it explains every limitation)

The app runs as a **plain Device Owner (DO)** on company-owned, fully-managed (COBO/COSU) kiosk devices. It is **not**:

- a preinstalled system/priv-app (`/system/priv-app` with a `privapp-permissions` whitelist),
- platform-signed,
- carrier-privileged (no certificate in the SIM's carrier rules).

Two permissions that the feature *wants* are therefore **declared but not granted at runtime**:

| Permission | Needed for | Granted on plain DO? |
| --- | --- | --- |
| `MODIFY_PHONE_STATE` | `TelephonyManager.setDataEnabled(...)` to silently flip data ON | **No** — only priv-app / platform / carrier-privileged |
| `WRITE_SECURE_SETTINGS` | writing `ENABLED_ACCESSIBILITY_SERVICES` to silently enable our accessibility service | **No** — only priv-app / platform |

**Consequences that shape the whole design:**

1. We **cannot silently turn mobile data on**. `Utils.setMobileDataEnabled()` executes but is a no-op (the OS ignores it). So the model is **prevent + coerce**, not "force programmatically".
2. We **cannot silently enable our own accessibility service** on a plain DO. Hence the mandatory user prompt gate (§5).
3. `DISALLOW_CONFIG_MOBILE_NETWORKS` (the toggle lock) works, but whether it actually greys out the toggle in Settings/Quick-Settings **varies by OEM/Android version** (AOSP honors it; some skins ignore it).

**If/when the deployment becomes a priv-app or gains carrier privileges, the silent tiers "wake up" automatically** — the code paths already exist and are attempted; they just currently no-op.

---

## 1. The three enforcement tiers

The feature is layered. Each tier is best-effort and backed by the next.

```
Tier 1 — PREVENT (works today, DO-native)
  DISALLOW_CONFIG_MOBILE_NETWORKS + DISALLOW_AIRPLANE_MODE
  → user can't turn data off via Settings/QS (where the OEM honors it)

Tier 2 — CORRECT (silent; no-op today, wakes up with privilege)
  1s watchdog + ContentObserver + TelephonyCallback detect "data went off"
  → Utils.setMobileDataEnabled(true)  (no-op on plain DO)

Tier 3 — COERCE (works today; the operative mechanism)
  Data still off → device lockdown:
    • constant non-cancelable popup in our app (button → mobile-network settings)
    • accessibility service bounces the user out of every app except Settings
    • toggle lock lifted so the user CAN turn it on; re-locked the instant they do
```

On our current (plain-DO) deployment, **Tier 3 is what actually enforces the policy**. Tiers 1–2 reduce how often Tier 3 has to fire.

---

## 2. Server policy (master switch)

- Field: `ServerConfig.mobileData` — tri-state `Boolean` (`json/ServerConfig.java`).
  - `TRUE`  → enforce ON: lock the toggle + watchdog + lockdown when off.
  - `FALSE` → warn-if-on: unlock, and warn the user if data is on (legacy "must be off" policy).
  - `null`  → disarmed: unlock, no enforcement. **This is the kill-switch / rollback.**
- **Nothing happens unless `mobileData == true` for the device.** If enforcement "doesn't work", check this first (see the diagnostic log in §7).

---

## 3. "Valid SIM" definition

`Utils.hasValidSim(context)` (`util/Utils.java`):

- Iterates each physical SIM slot via `TelephonyManager.getSimState(slotIndex)` (API 26+; `getPhoneCount()` for the slot count), or the aggregate `getSimState()` on pre-Oreo.
- A slot counts as valid only if it is `SIM_STATE_READY` — this **excludes** empty slots and PIN/PUK-locked / NOT_READY / PERM_DISABLED SIMs.
- Deliberately uses `getSimState(slot)` (needs **no** permission) instead of `getActiveSubscriptionInfoList()` (needs `READ_PHONE_STATE`, and returns empty on real devices even with a ready SIM — that earlier caused enforcement to silently idle).

Enforcement (watchdog + popup) only runs when at least one slot is `READY`. The **toggle lock**, however, is applied regardless of SIM presence (see §4.1).

---

## 4. Components (files, responsibilities, current behavior)

### 4.1 `service/StatusControlService.java` — the enforcement engine

An always-on foreground service (`START_STICKY`, FGS type `specialUse|systemExempted`), started at boot and app launch.

**Loops (via a `ScheduledThreadPoolExecutor`, i.e. off the main thread):**
- `controlStatus()` every **10 s** — applies device policy (Wi-Fi/BT/GPS + the mobile-data toggle lock).
- `enforceMobileDataPolicy()` every **1 s** — the mobile-data watchdog.

**Event-driven triggers (in addition to the 1s poll):**
- `ContentObserver` on `Settings.Global` (notifyForDescendants) matching URIs containing `mobile_data` / `airplane_mode` — catches per-subscription keys `mobile_data<subId>` (the plain `mobile_data` observer missed these on Android 15).
- `TelephonyCallback.UserMobileDataStateListener` (API 31+) / `PhoneStateListener` (API 28–30), registered **per active subscription**.
- `SubscriptionManager.OnSubscriptionsChangedListener` — fires on physical SIM insert/remove and eSIM profile switches; **debounced ~300 ms** (`onSimStateChanged` posts a delayed runnable) so a dual-SIM/eSIM "switch storm" collapses into one re-registration. Registration stays on the main thread on purpose (the pre-API-31 `PhoneStateListener` must be built on a Looper thread); only the debounce prevents overload.
- `Const.ACTION_SIM_STATE_CHANGED` local broadcast from `SimChangedReceiver`.

**`controlStatus()` — the toggle lock (Tier 1):**
- Applies the lock based purely on the **policy + DO status, independent of SIM presence**, so the toggle can't be flipped in a no-SIM window:
  - `mobileData == TRUE` → `Utils.setMobileDataLocked(true)` (unless the lock is currently lifted for remediation).
  - `mobileData == FALSE` → unlock + warn if data is on and a SIM is present.
  - `null` → unlock.

**`enforceMobileDataPolicy()` — the watchdog (Tiers 2 & 3):**
1. If policy not `TRUE` / control disabled / no valid SIM → clear violation flag, relock if lifted, throttled diagnostic log, return.
2. If data is ON → clear violation flag, relock the toggle if it was lifted, return.
3. **Violation** (data OFF): `setMobileDataEnabled(true)` (no-op on plain DO). Re-check; if now on → clear + relock.
4. Still off → **confirmed violation**:
   - set static `sMobileDataViolationActive = true` (read by the accessibility service),
   - **lift** the toggle lock (`liftMobileDataLockForRemediation`) so the user can actually turn data on from Settings — kept lifted for the whole violation; **re-locked the instant data comes back on** (there is no relock timeout anymore),
   - call `CheckForegroundAppAccessibilityService.reassertIfViolating()` — forces a user parked in a blocked app back to the launcher,
   - throttled (`MOBILE_DATA_ESCALATE_INTERVAL_MS = 4000` ms, after `MOBILE_DATA_ESCALATE_AFTER_TICKS = 1` tick) call `enforceMobileDataAndBringToFront()`.

**`enforceMobileDataAndBringToFront()`:**
- Android 12+ (API 31+): posts a **high-priority full-screen-intent notification** (background `startActivity` is blocked on 12+). Needs `POST_NOTIFICATIONS` (auto-granted to DO usually).
- Android < 12: direct `startActivity(MainActivity, REORDER_TO_FRONT)` with `POLICY_VIOLATION_CAUSE = MOBILE_DATA_ON_REQUIRED`.

**Key static API:** `StatusControlService.isMobileDataViolationActive()` — the single source of truth the accessibility service and UI consult.

**Constants (current):** watchdog 1 s, controlStatus 10 s, escalate after 1 tick, escalate throttle 4 s, diagnostic log throttle 60 s.

### 4.2 `util/Utils.java` — telephony + policy primitives

- `hasValidSim(context)` — §3.
- `isMobileDataEnabled(context)` — reads the default data subscription's `isDataEnabled()` (API 26+); when there is no default data sub (common mid-swap), checks **all active subs**; falls back to `Settings.Global` `mobile_data<subId>` / `mobile_data`, then legacy reflection.
- `setMobileDataEnabled(context, enabled)` — tries, in order: `setDataEnabledForReason(DATA_ENABLED_REASON_USER)` (API 31+), `setDataEnabled` (API 26+), reflection (legacy). **All require `MODIFY_PHONE_STATE` / carrier privileges → no-op on plain DO.** Applies to every active subscription.
- `setMobileDataLocked(locked, context)` — DO-only. Adds/clears `UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS` and (API 28+) `DISALLOW_AIRPLANE_MODE`.

### 4.3 `receiver/SimChangedReceiver.java`

- Registered for `SIM_STATE_CHANGED`. On any SIM change: starts `StatusControlService` and broadcasts `ACTION_SIM_STATE_CHANGED` (→ re-bind per-sub callbacks + immediate enforcement).
- Reads the `"ss"` extra **defensively** (null-guarded) — an NPE here was a crash-to-launcher source.
- Still logs SIM load/removal and re-uploads device info on a background thread.

### 4.4 `pro/service/CheckForegroundAppAccessibilityService.java` — the lockdown (Tier 3)

Accessibility service (also powers WorkTime blocking). Listens to `TYPE_WINDOW_STATE_CHANGED`.

- Tracks the current foreground package (`lastForegroundPkg`) and keeps a static `instance`.
- **On a window change while `isMobileDataViolationActive()`:** if the foreground app is **not** the launcher and **not** the Settings app → `performGlobalAction(GLOBAL_ACTION_HOME)` (returns to the kiosk launcher) + broadcast `ACTION_POLICY_VIOLATION` (MOBILE_DATA_ON_REQUIRED) → the popup appears. This catches the user *switching* to any other app.
- **`reassertIfViolating()` (static):** called by the watchdog every tick; if the user is *parked* in a blocked app (no window-change event fires), forces them home. No-op when the foreground is the launcher or Settings, so it never fights a user who is on the mobile-network settings page.
- **Allowed during a violation:** only the launcher and the **Settings** app (package match / name contains "settings"). **The phone dialer / in-call / emergency UI are blocked** (per current requirement — see §8 safety note).

### 4.5 `ui/MainActivity.java` — the popup + the accessibility gate

- **`checkMobileDataViolation()`** (called every `onResume`): if enforcing and data is off → show the mobile-data popup (constant until enabled); if data is back on (or not enforced) and the mobile-data prompt was up → dismiss it. A `mobileDataPromptShowing` flag ensures only the mobile-data dialog is auto-dismissed, not unrelated dialogs.
- **The popup** (`createAndShowSystemSettingDialog`): non-cancelable modal. Button opens **mobile-network settings** via `mobileNetworkSettingsIntent()` — tries `ACTION_DATA_ROAMING_SETTINGS` → `ACTION_NETWORK_OPERATOR_SETTINGS` → `ACTION_DATA_USAGE_SETTINGS` → `ACTION_WIRELESS_SETTINGS`. Because the toggle lock is lifted during a violation, the toggle is editable when the user lands there.
- **`enforceAccessibilityGate()`** (called every `onResume`, only once the device is enrolled): if the accessibility service is off, tries a silent enable, then shows a **mandatory, non-cancelable, no-skip** dialog that deep-links to `ACTION_ACCESSIBILITY_SETTINGS` (portable across OEMs). Re-shown on every resume until enabled — the user is stuck on it. The "skip" button is hidden.
- Handles `ACTION_POLICY_VIOLATION` broadcast: brings the app to front (if background) or shows the popup (if foreground).

### 4.6 Strings

- `message_turn_on_mobile_data` (default + all locale variants) — "Mobile data is disabled! Tap the button below to open mobile network settings and turn on mobile data." (The old "open the status bar" wording was removed; the custom status bar has no mobile-data toggle.)

### 4.7 Accessibility auto-enable helpers

- `helper/Initializer.java` (boot) and `MainActivity.tryAutoEnableAccessibility()` — append our service to `ENABLED_ACCESSIBILITY_SERVICES` + set `ACCESSIBILITY_ENABLED=1` + start the service. **Uses `Settings.Secure.putString`, which needs `WRITE_SECURE_SETTINGS` → throws on a plain DO** (caught/logged). Works only as priv-app. On a plain DO, the mandatory prompt gate (§4.5) is the reliable path.

---

## 5. End-to-end behavior (current, plain-DO deployment)

**Precondition:** `mobileData == true`, valid SIM present, app is DO, **accessibility service enabled** (forced via the gate).

1. **User turns mobile data off** (only possible if the OEM ignores `DISALLOW_CONFIG_MOBILE_NETWORKS`, or via a path around it).
2. Within ~1 s the watchdog (and the ContentObserver/TelephonyCallback, usually faster) detects it → `setMobileDataEnabled(true)` (no-op) → confirmed violation.
3. `sMobileDataViolationActive = true`; toggle lock lifted; accessibility bounce armed.
4. **The user is confined to our launcher + Settings:**
   - switching to any other app → bounced home immediately (accessibility),
   - sitting in a blocked app → forced home within ~1 s (watchdog `reassertIfViolating`),
   - in the launcher → a **non-cancelable popup** blocks everything except the "open mobile-network settings" button.
5. User opens mobile-network settings (allowed) and turns data on (toggle is editable because the lock is lifted).
6. Watchdog detects data ON → clears violation → **re-locks** the toggle → popup auto-dismisses → normal use restored.

**If accessibility is NOT enabled:** the bounce doesn't work; only the full-screen notification (12+) / `startActivity` (<12) fires — which nags but does not force-close other apps. Hence the mandatory accessibility gate.

---

## 6. Compatibility summary (API 23–36)

| Concern | Mechanism | Notes |
| --- | --- | --- |
| Detect data off | `TelephonyCallback` (31+) / `PhoneStateListener` (28–30) / `ContentObserver` (all) / 1 s poll | per-subscription; observer handles `mobile_data<subId>` |
| SIM/eSIM change | `OnSubscriptionsChangedListener` (debounced) + `SIM_STATE_CHANGED` | re-binds stale per-sub callbacks |
| Toggle lock | `DISALLOW_CONFIG_MOBILE_NETWORKS` + `DISALLOW_AIRPLANE_MODE` (28+) | DO-native; OEM honoring varies |
| Silent force-on | `setDataEnabledForReason` (31+) / `setDataEnabled` (26+) / reflection | **no-op without `MODIFY_PHONE_STATE`** |
| Force-to-front | accessibility `GLOBAL_ACTION_HOME` + FS notification (31+) / `startActivity` (<31) | HOME needs us to be default launcher (kiosk) |
| Force accessibility on | silent `Settings.Secure` write (priv-app only) → else mandatory prompt | prompt via `ACTION_ACCESSIBILITY_SETTINGS` (all OEMs) |

---

## 7. Diagnostics

Throttled (60 s) `RemoteLogger` lines from `enforceMobileDataPolicy()`:
- `mobile data enforcement idle — server policy mobileData=<value>` → the server flag isn't `true`.
- `mobile data policy ON but no READY SIM detected — enforcement idle` → policy on but SIM not `READY` (e.g., PIN-locked).
- `mobile data is back ON (policy enforced)` / `re-enabled automatically` → recovery.
- `mobile data lock lifted for user remediation` / `lock re-applied (...)` → lift/relock transitions.

---

## 8. Known limitations & risks (the research backlog)

1. **Silent force-on is impossible on a plain DO.** Needs deployment path (a) preinstalled priv-app with `MODIFY_PHONE_STATE` whitelisted, or (b) carrier privileges (cert hash in the SIM's ARF). Research: which is feasible with the OEM/carrier; carrier privileges survive OTA and need no custom firmware.
2. **`DISALLOW_CONFIG_MOBILE_NETWORKS` OEM honoring.** Some skins (Samsung/Xiaomi/etc.) still let the QS tile / Settings toggle flip data. Research: per-OEM validation matrix; Samsung Knox Service Plugin (KSP) can properly grey the tile (separate licensing/enrollment).
3. **Silent accessibility enable needs priv-app / `WRITE_SECURE_SETTINGS`.** On a plain DO we depend on the mandatory prompt. Research: does `dpm.setSecureSetting(admin, ENABLED_ACCESSIBILITY_SERVICES, ...)` work for a DO on the target OEMs/versions (it may, since the system performs the write) — currently NOT attempted in code.
4. **Emergency calls are currently blocked** (the dialer is bounced during a violation). This is a **safety/legal concern**. Research/decision: allow an emergency-only exemption vs. full block.
5. **Sub-page restriction inside Settings is not reliable.** Accessibility reports only the package (`com.android.settings`) with generic screen classes (`SubSettings`) that differ per OEM, so we allow the whole Settings app. Research: per-OEM activity/class heuristics, or accept whole-Settings.
6. **`GLOBAL_ACTION_HOME` assumes we are the default launcher.** True in COSU kiosk; verify per fleet.
7. **Accessibility service must stay enabled/alive.** Aggressive OEM task-killers (MIUI etc.) can disable/kill it; battery-optimization exemptions and autostart may be needed.
8. **eSIM / MEP (multi-enabled-profile, Android 15+)** — multiple concurrent active subs; verify `hasValidSim` and per-sub enforcement on such hardware.
9. **`minSdkVersion`** in `app/build.gradle` is below the stated API-23 floor for some paths; verify behavior or raise it.
10. **Rollback:** setting `mobileData = null` on the server disarms everything on the next config sync (unlocks + stops the watchdog). Config sync must be reachable over Wi-Fi (it is — uses whatever network is up).

---

## 9. File index (where to look)

| Concern | File |
| --- | --- |
| Watchdog, observers, callbacks, escalation, violation flag | `app/src/main/java/.../service/StatusControlService.java` |
| SIM validity, data read, data set, toggle lock | `app/src/main/java/.../util/Utils.java` |
| SIM change → enforcement | `app/src/main/java/.../receiver/SimChangedReceiver.java` |
| App-bounce lockdown, reassert, allowed set | `app/src/main/java/.../pro/service/CheckForegroundAppAccessibilityService.java` |
| Popup, mobile-network intent, accessibility gate | `app/src/main/java/.../ui/MainActivity.java` |
| Toggle-lock DPM primitive | `Utils.setMobileDataLocked` |
| Boot-time accessibility auto-enable | `app/src/main/java/.../helper/Initializer.java` |
| Server policy field | `app/src/main/java/.../json/ServerConfig.java` (`mobileData`) |
| Prompt strings | `app/src/main/res/values*/strings.xml` (`message_turn_on_mobile_data`) |
| Accessibility prompt dialog | `app/src/main/res/layout/dialog_accessibility_service.xml` |
| Original architecture plan | `MOBILE_DATA.md` (root) |

---

## 10. TL;DR

On the current **plain Device Owner** deployment we **cannot silently force mobile data on** and **cannot silently enable accessibility** — those need priv-app or carrier privileges. What works today is a **prevent + coerce** model: lock the toggle (where the OEM honors it), and when data is off, **lock the device down** (constant non-cancelable popup + accessibility bounce that confines the user to Settings) until they turn it back on, with a forced accessibility-permission gate to make the bounce reliable. The biggest levers for "more" are **priv-app/carrier privilege** (unlocks true silent enforcement) and a **per-OEM validation matrix**.
