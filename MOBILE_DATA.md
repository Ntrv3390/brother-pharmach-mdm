# Mobile Data Enforcement Engine — Architectural Analysis & Implementation Plan

**Project:** Brother Pharmamach MDM launcher (`hmdm-android`, package `com.brother.pharmach.mdm.launcher`)
**Scope:** Force mobile data ON whenever a valid SIM is present; lock out the user-facing toggles; Android 6.0 (API 23) → Android 16+ (API 36).
**Status:** PLAN ONLY — no production code in this document. Awaiting architectural sign-off.

> **Headline finding:** A first-generation implementation of this feature already exists in the codebase
> (server flag `mobileData`, a 1-second enforcement watchdog, a `ContentObserver`, per-subscription
> telephony callbacks, a DPM toggle-lock, and a user-facing escalation dialog). This plan is therefore a
> **gap analysis and hardening plan of an existing feature**, not a greenfield design. Existing code is
> cited by file and line throughout.

---

## 0. Deployment Model & Privileged Access Confirmation

### Deployment model: COBO/COSU — CONFIRMED by codebase evidence

The app is architected exclusively as a **fully-managed Device Owner** deployment:

* Every enforcement path is gated on `Utils.isDeviceOwner()` (e.g. `Utils.setMobileDataLocked()`, `Utils.java:715`).
* COSU/kiosk machinery is present and used: `setLockTaskPackages` / `setLockTaskFeatures` (`helper/DevicePolicyBootstrapper.java:80,135`) and `setStatusBarDisabled(admin, true)` (`DevicePolicyBootstrapper.java:148`, `ProUtils.java:119`).
* There is **no work-profile (Profile Owner) code path** for connectivity policy anywhere in the client.

The prompt's assumption is correct: this is COBO/COSU, not BYOD. A work profile could not implement any part of this feature (device-wide data toggle and `DISALLOW_CONFIG_MOBILE_NETWORKS` are DO-only), so nothing in this plan is applicable to a PO deployment.

### Privileged access path: **(d) — None of (a)/(b)/(c) is currently in place**

This must be stated bluntly because it determines whether the "silent force-ON" tier of this plan works at all:

| Evidence | Location | Implication |
| --- | --- | --- |
| `android:sharedUserId="com.brother.pharmach.mdm"` — a custom UID, **not** `android.uid.system` | `AndroidManifest.xml:24` | Not a system-UID app |
| Signing key is a self-managed release keystore (`brother-pharmach-release.jks`), not an OEM platform key | repo root / `app/build.gradle` | No platform-signature permissions |
| `SYSTEM_PRIVILEGES` build flag defaults to `false` | `app/build.gradle:92` | Priv-app build variant exists but is not the shipped configuration |
| `MODIFY_PHONE_STATE` and `READ_PRIVILEGED_PHONE_STATE` are declared, with an in-manifest comment conceding they are "silently ignored on regular device-owner installs" | `AndroidManifest.xml:41–46` | Declared but **not granted** at runtime |
| No `privapp-permissions-*.xml` whitelist artifacts, no carrier-certificate provisioning, no `hasCarrierPrivileges()` checks anywhere in the code | repo-wide search | Neither priv-app whitelisting nor carrier privileges is wired up |

**Consequence (this is the single most important sentence in this document):** On the current deployment, `TelephonyManager.setDataEnabled()` / `setDataEnabledForReason()` are **no-ops** — Device Owner status does not grant `MODIFY_PHONE_STATE` on any Android version 6–16, and stock Android gives a DO **no direct API to programmatically flip mobile data on**. The existing code already acknowledges this and compensates with a blocking full-screen escalation dialog that forces the *user* to re-enable data (`StatusControlService.enforceMobileDataAndBringToFront()`, line 575).

**Blocked until resolved:** the *silent, zero-user-interaction* force-ON requirement (§1 of the objective) is **not achievable** on the current signing/installation path. The achievable architecture on the current path is:

1. **Prevention-first:** lock every UI path that could turn data off (this works fully with DO alone), so the "force ON" case almost never arises; plus
2. **Correction-with-escalation:** attempt the programmatic re-flip (harmless no-op today, becomes functional the day privileged status lands), and escalate to the blocking dialog when the OS refuses.

**Decision required from stakeholders before implementation:** pursue path (a) (preinstall as `/system/priv-app` with a `privapp-permissions` whitelist granting `MODIFY_PHONE_STATE` — requires OEM/factory-image cooperation, realistic for a fixed fleet purchase) or path (b) (carrier privileges via certificate hash in the SIM's carrier rules — requires MNO/MVNO cooperation, attractive because it survives OTA updates and needs no custom firmware). Until one of these is signed off, the plan below treats the silent tier as *dormant but implemented*, with the prevention + escalation tiers as the operative guarantee.

---

## 1. Architectural Gap Analysis

### What is natively possible with plain Device Owner (works today, all versions 6–16)

| Capability | API | Status in codebase |
| --- | --- | --- |
| Block the Settings app's Mobile network screen (incl. its data toggle) | `UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS` | ✅ Implemented — `Utils.setMobileDataLocked()`, `Utils.java:710–736` |
| Block the airplane-mode bypass (API 28+) | `UserManager.DISALLOW_AIRPLANE_MODE` | ✅ Implemented in same method, P+ only |
| Kill the entire status bar / QS pull-down in kiosk contexts | `DevicePolicyManager.setStatusBarDisabled()` (API 23+), LockTask mode | ✅ Implemented — `DevicePolicyBootstrapper.java:148` |
| Detect data-state changes near-instantly | `TelephonyCallback.UserMobileDataStateListener` (31+), `PhoneStateListener` (28–30), `ContentObserver` on `Settings.Global` | ✅ Implemented — `StatusControlService.java:175–360` |
| Read data-enabled state | `TelephonyManager.isDataEnabled()` (26+), `Settings.Global` `mobile_data<subId>` fallback | ✅ Implemented — `Utils.isMobileDataEnabled()`, `Utils.java:559–606` |
| Persist across reboot / OTA | `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` receivers, `START_STICKY` FGS | ✅ Implemented — `BootReceiver`, `StatusControlService` |
| Force data-roaming ON (continuity abroad) | `dpm.setGlobalSetting(Settings.Global.DATA_ROAMING, "1")` — one of the few globals a DO may write | ❌ Not implemented (gap G6) |

### What requires privileged status (dormant on current deployment)

| Capability | API | Privilege required |
| --- | --- | --- |
| Silently flip mobile data ON | `TelephonyManager.setDataEnabledForReason(DATA_ENABLED_REASON_USER)` (31+), `setDataEnabled()` (26–30), reflection `setDataEnabled` (23–25) | `MODIFY_PHONE_STATE` (priv-app/platform) or carrier privileges — **DO is not sufficient on any version** |
| Per-slot privileged SIM broadcasts | `ACTION_SIM_CARD_STATE_CHANGED` / `ACTION_SIM_APPLICATION_STATE_CHANGED` (26+) | `READ_PRIVILEGED_PHONE_STATE` — use non-privileged equivalents instead |

All three call tiers are already implemented in `Utils.setMobileDataEnabled()` (`Utils.java:626–690`), correctly ordered (public-API-first, reflection last), and correctly documented as requiring verification after the call. **No changes needed to the call chain itself** — it is exactly what would run if privileged status is obtained.

### Identified gaps (the actual work)

* **G1 — SIM presence does not drive enforcement.** `SimChangedReceiver` only logs and re-uploads device info (`receiver/SimChangedReceiver.java:40–65`); it never kicks `enforceMobileDataPolicy()` or re-registers the per-subscription telephony callbacks. A SIM hot-swap is currently only caught by the 1 s watchdog, and the callbacks registered at service start go stale (they're bound to the *old* subscription IDs).
* **G2 — No `OnSubscriptionsChangedListener`.** eSIM profile switches and dual-SIM changes do not fire `SIM_STATE_CHANGED` reliably; the modern, non-privileged signal is `SubscriptionManager.addOnSubscriptionsChangedListener`, which is absent from the codebase.
* **G3 — "Valid SIM" definition is too weak.** `Utils.isSimAbsent()` (`Utils.java:692–698`) checks only the *default* SIM's state against `SIM_STATE_ABSENT`. It misses: SIM in slot 2 only, PIN-locked SIMs, `NOT_READY`/`PERM_DISABLED` states, and eSIM profiles that are downloaded but not enabled.
* **G4 — Verification is default-sub only.** `Utils.setMobileDataEnabled()` loops all active subscriptions, but the post-call verification (`isMobileDataEnabled()`) checks only the default data subscription; a violation on the non-default SIM goes undetected.
* **G5 — Enforcement is gated solely on the server flag.** `enforceMobileDataPolicy()` requires `config.getMobileData() == TRUE` (`StatusControlService.java:371`). The stated requirement ("whenever a valid SIM is detected... at any given time") implies SIM-presence-driven enforcement. Recommendation: **keep the server flag as the master switch** (it is the rollback/kill-switch — see §4) and make SIM validity the *inner* condition, which is already the structure; document this as deliberate.
* **G6 — No roaming policy.** `DISALLOW_DATA_ROAMING` / `Settings.Global.DATA_ROAMING` untouched; abroad, data dies even with the toggle ON.
* **G7 — `minSdkVersion 21`** (`app/build.gradle`) is below the stated floor of API 23; API 21–22 devices would run enforcement paths never tested. Either raise minSdk to 23 or explicitly no-op below 23.
* **G8 — QS race window undocumented/untuned.** See §3 Component B for the latency budget.

---

## 2. API Compatibility Matrix (Android 6 – 16)

| Android Version Range | SIM Detection API | Data Control API | Required Privilege Level | Toggle Restriction Method | QS Tile Enforcement |
| --- | --- | --- | --- | --- | --- |
| **Android 6.0 – 8.1** (API 23–27) | `SubscriptionManager.addOnSubscriptionsChangedListener` (23+, needs `READ_PHONE_STATE`); manifest `SIM_STATE_CHANGED` broadcast (`"ss"` extra: `LOADED`/`ABSENT`/`LOCKED`); `getSimState(int slot)` only from API 26 — per-slot state on 23–25 via `SubscriptionManager` active-sub list | 23–25: reflection `TelephonyManager.setDataEnabled(boolean)` (hidden `@SystemApi`; `ConnectivityManager.setMobileDataEnabled` was removed in 5.x — reflection on it only helps vendor ROMs that kept it). 26–27: public `TelephonyManager.setDataEnabled()` | `MODIFY_PHONE_STATE` (system/priv-app or platform signature) or carrier privileges. **DO alone: no write path** | `DISALLOW_CONFIG_MOBILE_NETWORKS` (Settings app). `DISALLOW_AIRPLANE_MODE` **not available** (<28) — airplane-mode hole on this tier; mitigate with `setStatusBarDisabled` (API 23+) in kiosk contexts | **No public API to remove/disable the Mobile Data QS tile.** Restriction honored inconsistently by OEM SystemUI on this tier — assume the tile still flips data. Primary control: ContentObserver re-flip / escalation; `setStatusBarDisabled` blocks QS entirely where kiosk UX permits |
| **Android 9.0 – 11.0** (API 28–30) | Same listener; hidden-API greylist begins (28) but `addOnSubscriptionsChangedListener` is public; `PhoneStateListener.onUserMobileDataStateChanged` (28+) for instant data-state events; eSIM: `SubscriptionInfo.isEmbedded()` (28+) | Public `TelephonyManager.setDataEnabled()`; per-sub via `createForSubscriptionId()`. Reflection fallbacks now hit non-SDK greylist (blocked for `setDataEnabled` at "max-target" levels; priv-app/platform apps exempt) | Unchanged: `MODIFY_PHONE_STATE` or carrier privileges | `DISALLOW_CONFIG_MOBILE_NETWORKS` + `DISALLOW_AIRPLANE_MODE` (28+) — airplane-mode hole closed | Still no tile API. AOSP SystemUI increasingly checks the restriction on tap (shows "disabled by admin"), **but OEM skins diverge** — treat observer re-flip as primary, verify per OEM (§4) |
| **Android 12.0 – 14.0** (API 31–34) | `TelephonyCallback.UserMobileDataStateListener` (31+, replaces `PhoneStateListener`); `OnSubscriptionsChangedListener` unchanged; FGS-type rules (34: `specialUse`/`systemExempted` — already declared, `AndroidManifest.xml:336–341`) | `setDataEnabledForReason(DATA_ENABLED_REASON_USER)` (31+) — the correct reason code, as it overwrites the user's own toggle state; `setDataEnabled()` deprecated | Unchanged. Note: Android 14's `MANAGE_DEVICE_POLICY_MOBILE_NETWORK` (declared at `AndroidManifest.xml:112`) governs the *restriction* policy for delegated policy-managers — it does **not** grant a data toggle | Same two restrictions; combined "Internet" QS tile (12+) opens a SystemUI internet panel whose mobile-data switch honors `DISALLOW_CONFIG_MOBILE_NETWORKS` on AOSP (shows blocked-by-admin) — still not guaranteed on OEM skins | Same posture: no removal API; AOSP internet panel respects the restriction, OEM panels must be validated; observer + `TelephonyCallback` re-flip as backstop |
| **Android 15.0 – 16.0+** (API 35–36) | Same as 31–34; eSIM multi-enabled-profiles (MEP) devices report one subscription per enabled profile — handle N active subs generically | Same as 31–34. Confirmed by in-repo testing note (`Utils.java:559–563`): reflection fallbacks are dead on 15; the public-API + `Settings.Global` per-sub read path is the working one | Unchanged — **still no DO API for the data toggle as of Android 16** | Same two restrictions; `setStatusBarDisabled` behavior in COSU stable | Same posture. MEP: enforce and verify per enabled profile |

**`DevicePolicyManager.setStatusBarDisabled()` — its own compatibility row, as requested:**

| Version | What it actually suppresses | Notes |
| --- | --- | --- |
| 6.0–7.1 | Expansion of the status bar (shade + QS) for the fully-managed user; icons/clock remain | DO only; some OEMs still allow heads-up notifications |
| 8.0–11 | Same; interaction with LockTask: `LOCK_TASK_FEATURE_*` flags (9+) can independently re-enable SYSTEM_INFO / notifications inside kiosk | Codebase already sets `HOME|KEYGUARD|SYSTEM_INFO|OVERVIEW` (`DevicePolicyBootstrapper.java:135`) — **QS stays blocked** because `LOCK_TASK_FEATURE_NOTIFICATIONS` is not granted |
| 12–16 | Same; on some OEM skins the pull-down is possible but immediately collapses | Applies only while device is fully managed (COSU); no effect in PO mode. Blocks QS wholesale — usable only where the kiosk UX tolerates losing the shade |

---

## 3. Detailed Component Plan

### Component A — Global Lifecycle / SIM Listener ("SimStateCoordinator")

A new coordinator owned by the existing `StatusControlService` (no new service — the FGS with `specialUse|systemExempted` type, `START_STICKY`, boot receiver, and battery-optimization monitor already solves persistence and Oreo+ background-execution limits).

**A.1 Signal sources (all registered from the running FGS, so background broadcast limits are irrelevant):**

1. **Primary (API 23–36):** `SubscriptionManager.addOnSubscriptionsChangedListener` — fires on physical SIM insert/remove, eSIM profile enable/disable/switch, and default-data-sub changes. This is the missing modern signal (gap G2).
2. **Wake-up path:** existing manifest-registered `SimChangedReceiver` (`SIM_STATE_CHANGED` is on the implicit-broadcast exemption list, so it still wakes the app on 8.0+ if the process was killed). Extend it to (a) ping `StatusControlService` and (b) trigger an immediate enforcement pass — closing gap G1. Keep its current logging/upload behavior.
3. **Data-state events:** existing `TelephonyCallback`/`PhoneStateListener` registrations (`StatusControlService.java:175–289`) — but **re-register them from the subscriptions-changed callback**, because they are bound to subscription IDs that go stale on SIM swap (gap G1).
4. **Fallback heartbeat:** the existing 1 s watchdog remains the guarantee of last resort for OEMs with broken broadcasts.

**A.2 "Valid SIM" definition (replaces `Utils.isSimAbsent`, gap G3).** A subscription counts as valid iff **all** of:

* It appears in `SubscriptionManager.getActiveSubscriptionInfoList()` (active = present *and* enabled; ignores empty slots and downloaded-but-disabled eSIM profiles);
* Its slot's `getSimState(slotIndex)` (API 26+; default-slot `getSimState()` below 26) is `SIM_STATE_READY`/`LOADED` — this excludes `PIN_REQUIRED`/`PUK_REQUIRED` (locked), `NOT_READY`, `CARD_IO_ERROR`, `PERM_DISABLED`;
* On API 24+, `TelephonyManager.createForSubscriptionId(subId).getSimCarrierId() != UNKNOWN_CARRIER_ID` *or* a non-empty operator — a cheap "actually provisioned for cellular service" screen (data-less M2M profiles are still treated as valid; we enforce ON and let the network deny — safer than guessing).

Enforcement predicate: **at least one valid subscription exists** → policy engine armed. Zero valid subscriptions → enforcement idles (no dialog spam on Wi-Fi-only benches) **but the UI locks stay applied** (see §4).

**A.3 eSIM specifics (separate from hot-swap, as required):** an eSIM profile *switch* appears as one subscription vanishing and another appearing, often with a 5–30 s gap where zero subs are active, and the new sub can surface before its `SIM_STATE` is `LOADED`. The coordinator therefore: debounces subscription-list changes (~3 s settle window), re-evaluates validity on each change rather than caching slot→sub mappings, and never treats a transient zero-sub window as "unlock the UI". MEP devices (Android 15+) simply produce N concurrent valid subs — all code paths iterate the full list (the existing `setMobileDataEnabled` already does; verification must too, gap G4).

**A.4 Reboot/OTA survival:** unchanged — `BootReceiver` (`BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`) → `Initializer.startServicesAndLoadConfig` → `StatusControlService`. DPM user restrictions persist across reboot at the system level, so the lockout has no boot-time gap even before the service starts.

### Component B — Toggle Interceptor & Policy Enforcer

Three concentric layers, ordered by strength:

**B.1 Prevention (authoritative, DO-native, works today).** At provisioning time (`DevicePolicyBootstrapper.applyPolicies()`) and re-asserted by the existing 10 s `controlStatus()` loop:

* `DISALLOW_CONFIG_MOBILE_NETWORKS` — blocks the Settings › Network & Internet › SIMs/Mobile network screens, hence the Settings-app data toggle, on every version 6–16. **Confirmed: this restricts the Settings entry point; it does *not* reliably disable the QS tile on all AOSP/OEM versions** — AOSP honors it in the 12+ internet panel and in most 9–11 SystemUI builds, but this must be treated as unverified per OEM (§4).
* `DISALLOW_AIRPLANE_MODE` (28+) — closes the airplane-mode bypass. On 23–27 this restriction does not exist; the residual hole is accepted and mitigated by kiosk `setStatusBarDisabled` where applicable, plus detection (airplane-mode flips are already surfaced by the observer, which watches `airplane_mode` URIs — `StatusControlService.java:340`).
* **New (G6):** `dpm.setGlobalSetting(DATA_ROAMING, "1")` + `DISALLOW_DATA_ROAMING`, behind a server-config flag, for fleet continuity across borders.
* Kiosk devices additionally get `setStatusBarDisabled(true)` (already in place), which removes the QS attack surface entirely.

**B.2 Correction (event-driven re-flip; silent only with privileged status).** Already implemented and structurally sound — keep as-is with two fixes:

* Triggers: `ContentObserver` on `Settings.Global` root with `notifyForDescendants=true` matching `mobile_data*` and `airplane_mode*` URIs (`StatusControlService.java:326–360`; correctly handles the per-sub `mobile_data<subId>` keys that broke the original implementation on Android 15) + `TelephonyCallback`/`PhoneStateListener` + 1 s watchdog.
* Action: `Utils.setMobileDataEnabled(context, true)` across **all** active subscriptions (existing), then **verify per-subscription** (fix for G4), swap `isSimAbsent()` for the Component A validity predicate (fix for G3).
* **QS race-condition window — this observer loop is the *primary* QS mechanism, and the window is:**

| Path | Detection latency | Re-flip latency (privileged) | Worst-case user-visible OFF window |
| --- | --- | --- | --- |
| `TelephonyCallback` (28+) | ~10–100 ms | ~50–200 ms | **< 0.5 s** toggle-state; radio data session may take 1–3 s to re-attach — a brief connectivity blip is physically unavoidable |
| `ContentObserver` (all versions) | ~50–300 ms | same | < 1 s |
| 1 s watchdog (backstop) | ≤ 1 s | same | ~1.5 s |
| **Unprivileged (current deployment)** | same detection | re-flip is a no-op | data stays OFF until the user complies with the escalation dialog: ≥ 3 s (3 ticks) to first dialog, 30 s re-prompt throttle (`MOBILE_DATA_ESCALATE_AFTER_TICKS`, `MOBILE_DATA_ESCALATE_INTERVAL_MS`) |

This window must be stated in the compliance/ops documentation: on the current deployment path the engine **detects within ~1 s but cannot silently correct**; correction latency is bounded by user compliance with the blocking dialog.

**B.3 Escalation (user-coercive fallback — the operative tier until §0 is resolved).** Existing: after 3 failed ticks, `enforceMobileDataAndBringToFront()` posts a full-screen-intent notification (31+) or direct activity launch (< 31) driving `MainActivity`'s policy-violation dialog (`MOBILE_DATA_ON_REQUIRED`, `Const.java:57`), which in kiosk mode the user cannot dismiss without complying. Planned refinements: deep-link the dialog's action button to `ACTION_WIRELESS_SETTINGS` on devices where `DISALLOW_CONFIG_MOBILE_NETWORKS` would block the user from *re-enabling* data themselves — the enforcer must **temporarily lift the restriction while the escalation dialog is active** (restriction blocks OFF→ON just as it blocks ON→OFF), then re-apply it once `isDataEnabled()` verifies. This lift-and-relock sequence is a new, subtle piece of Component B and needs explicit state-machine treatment (states: `LOCKED_COMPLIANT` → `VIOLATION_DETECTED` → `UNLOCKED_AWAITING_USER` → `LOCKED_COMPLIANT`), with a timeout that re-locks even if the user never complies.

**B.4 Policy source of truth.** Keep `ServerConfig.mobileData` (`json/ServerConfig.java:45,266`) tri-state as the master switch: `TRUE` = enforce ON (this feature), `FALSE` = warn-if-on (existing), `null` = disarmed. SIM validity (Component A) is the runtime gate *inside* the `TRUE` branch. This preserves the server-side kill-switch required by §4.

---

## 4. Edge Cases & Risks

* **OEM SystemUI divergence (the #1 ship risk).** Samsung (One UI/Knox), Xiaomi (MIUI/HyperOS), Oppo/Vivo/Transsion skins reimplement QS and the Settings app; several are documented to ignore `DISALLOW_CONFIG_MOBILE_NETWORKS` in their QS tile, and MIUI's aggressive task-killing can kill even FGS-based watchdogs unless the app is whitelisted ("Autostart" + battery exemptions — partially handled by the existing `BatteryOptimizationMonitor`). Samsung offers Knox Service Plugin / KSP policies that *can* gray the QS tile properly, but that is a separate enrollment/licensing track. **Requirement: a per-OEM validation matrix (device × Android version × {Settings toggle blocked?, QS tile blocked?, observer re-flip works?, dialog fires?, watchdog survives 24 h idle?}) executed on actual fleet hardware before rollout.** Do not extrapolate from Pixels.
* **Airplane mode.** On 28+ blocked by `DISALLOW_AIRPLANE_MODE`. On 23–27 there is no restriction; a user can still cut data via airplane mode from QS unless `setStatusBarDisabled`/kiosk is active. The observer already watches `airplane_mode` and the escalation dialog covers it; DO cannot programmatically clear airplane mode on 7.0+ (`setGlobalSetting(AIRPLANE_MODE_ON)` unsupported since N — see the guard at `QuickTileActions.java:199–213`). Accept + document on the legacy tier.
* **No SIM present.** UI locks (user restrictions) **stay applied** even with zero valid SIMs — removing them would open a window where a user inserts a SIM and disables data before the coordinator re-locks. The restrictions are invisible dead weight when no SIM is present; there is no UX cost. Only the watchdog/dialog idles (as `enforceMobileDataPolicy` already does via the SIM check).
* **SIM PIN-locked at boot.** A PIN-locked SIM is *invalid* per A.2 — no enforcement, no dialog. When the user unlocks it, state transitions to `LOADED` and the coordinator arms. (Fleet recommendation: provision SIMs without PIN, or the escalation dialog will never be reachable behind the PIN prompt.)
* **Carrier outage / bad OTA / rollback.** If enforcement misbehaves (e.g., an OTA changes toggle semantics, or forced-data + broken APN traps devices offline), the recovery path is the server flag: set `mobileData = null` → next config sync disarms the watchdog and `controlStatus()` clears the restrictions (`StatusControlService.java:552–573`). Because the device may be data-dead, config sync must be reachable via Wi-Fi too — verify the launcher's sync path does not itself require mobile data (it does not; it uses whatever network is up). `DISALLOW_SAFE_BOOT` is already applied (`Utils.java:763`), closing the safe-mode bypass; document that a factory-reset-protected, fully-bricked-connectivity device requires physical recovery — hence the flag-first rollout below.
* **Escalation-dialog lift-and-relock race (new in B.3).** While the restriction is temporarily lifted for user remediation, a hostile user could navigate to a different toggle. Mitigation: keep the lift window short (timeout ~60 s), keep kiosk/lock-task active during it, and re-verify + re-lock on every tick.
* **Deprecated/removed APIs.** `PhoneStateListener` deprecated (31+) — already dual-pathed; `setDataEnabled` deprecated (31+) — already ordered behind `setDataEnabledForReason`; reflection tier dead on 9+ for non-exempt apps — already last-resort. `minSdk 21` vs API 23 floor (G7): raise `minSdkVersion` to 23 or gate the feature.
* **Rollout sequencing.** Ship dark behind `mobileData=null` → enable for a 5-device canary per OEM model → fleet-wide only after the OEM matrix passes.

---

## 5. Compliance Note

This plan is scoped to **company-owned, fully-managed (COBO/COSU) devices** provisioned as Device Owner, with the management relationship disclosed to end users. The codebase supports exactly this model (Device Owner provisioning via `AdminReceiver` + kiosk lock-task; no BYOD/work-profile path), so the deployment model supports the stated scope. Two disclosure-relevant facts to carry into the enterprise policy documentation:

1. The enforcement is *visible by design* on the current deployment path — users see a persistent "Device security policy is active" foreground notification and, on violation, an explicit blocking dialog naming the policy. This is a compliance asset, not a defect.
2. If/when privileged status (§0) lands and correction becomes silent, the disclosed device-use policy should be updated to state that mobile data is enforced ON at all times on these devices, since the user-visible dialog disappears.

Nothing in this plan applies to, and nothing should ever be deployed on, personally-owned or BYOD devices.

---

## Sign-off checklist (blocking items before implementation starts)

1. ☐ **§0 decision:** priv-app preinstall (a), carrier privileges (b), or accept prevention+escalation-only (d, status quo).
2. ☐ Confirm server flag semantics stay tri-state with `null` as kill-switch (B.4).
3. ☐ Approve lift-and-relock state machine for the escalation flow (B.3).
4. ☐ Approve `minSdkVersion` 21 → 23 (G7).
5. ☐ Fund/schedule the per-OEM validation matrix on fleet hardware (§4).
