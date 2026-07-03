# "Get Latest GPS" diagnostics — Phase 1b log-reading guide

Phase 1b fixed the correlation-ID bug from the first capture (every `TIMELINE` line read
`elapsedSinceRequestStart=n/a`) and added the logging needed to directly prove or disprove
H1 (Doze suppression) and H2 (orphaned/concurrent requests), plus two independently-toggleable
experimental fixes in `ExperimentalFlags`. Both flags default to **false** — flip exactly one
per test round so the result is attributable.

## Before the next test

- `ExperimentalFlags.ABORT_ON_INTERRUPT_ENABLED` — H2 fix (abort-on-interrupt + single-flight gate).
- `ExperimentalFlags.DOZE_VIBRATION_PULSE_ENABLED` — H1 experiment (vibration pulse before capture).

Run at least 3 rounds: both off (baseline, directly comparable to the first capture), H2 on,
H1 on. Trigger "Get Latest GPS" once per round and pull the full log export for that window.

## What to grep for

### Proving/disproving H2 (orphaned or concurrent requests)

- `CONCURRENT_REQUESTS` — every line includes `activeCount=`. **`activeCount > 1` is direct,
  non-inferred proof that two logical requests were in flight at the same time.** The `origin=`
  field on each `event=begin` line tells you which of the (now four known) trigger paths caused
  it: `pushMessage:fetchGpsUrgent`, `pushMessage:configUpdatedSideEffect`,
  `initializerConfigComplete`, or `workManagerPeriodic`/`workManagerOneShot`. If you see
  `pushMessage:fetchGpsUrgent` and `pushMessage:configUpdatedSideEffect` both begin within
  milliseconds of each other, that confirms the server-side redundant trigger already traced in
  `DeviceInfoResource.refreshDevice()` (sends `TYPE_FETCH_GPS_URGENT` **and** calls
  `notifyDeviceOnSettingUpdate()`, which independently triggers a second capture).
- `PROCEEDING_DESPITE_INTERRUPT` — with both flags off, this is the current (baseline) behavior:
  a fallback chain kept running after being cancelled. Every occurrence names the `stage=` it was
  about to enter (`beforeFusedFallback:gps`, `beforeMainLooperRescue:network`, etc.) and the
  `reqId=` of the *cancelled* request — cross-reference that reqId's own `CONCURRENT_REQUESTS`
  line to see what superseded it.
- `ABORTED_ON_INTERRUPT` — only appears with `ABORT_ON_INTERRUPT_ENABLED=true`; replaces the
  line above and means the fix actually stopped the fallback chain instead of letting it run on.
- `SKIPPED_CONCURRENT_REQUEST` — only appears with the same flag on; means the single-flight
  gate blocked a second concurrent provider request outright.

**Verdict for H2**: if the baseline round shows `CONCURRENT_REQUESTS activeCount>1` and/or
`PROCEEDING_DESPITE_INTERRUPT`, and the H2-flag round for the *same trigger scenario* shows
`SKIPPED_CONCURRENT_REQUEST`/`ABORTED_ON_INTERRUPT` instead and the capture succeeds faster or
more reliably, H2 is confirmed as a real contributor.

### Proving/disproving H1 (Doze suppression)

- `DOZE_TRANSITION` — logged for the whole FGS lifetime, not just during requests. Shows
  `enteredIdle=` and `previousStateHeldMs=` on every transition. Use this to see how long the
  device was actually in Doze before/during a failed capture, not just a single boolean snapshot.
- `PROCESS_STATE` lines now include `dozeStateHeldMs=NNNN(currentlyIdle=true/false)` and
  `screenInteractive=`. Compare a failed urgent capture's `dozeStateHeldMs` against a
  successful one (e.g. right after screen-on) to see if Doze duration correlates with failure.
- `GNSS_CONTINUOUS_SUMMARY` — logged every 30s for the FGS's whole life, tagged with
  `concurrentUrgentRequestsInFlight=`. If this shows `statusUpdatesTotal=0` / growing
  `msSinceLastCallback` for long stretches while `currentlyIdle=true`, that's ground truth that
  the chip isn't engaging during Doze at all — independent of any urgent request.
- `DOZE_PULSE` — only appears with `DOZE_VIBRATION_PULSE_ENABLED=true`. Shows Doze state
  immediately before and ~400ms after the vibration pulse. `(Doze EXITED shortly after pulse)`
  plus a capture that then succeeds is direct evidence for H1.

  **Caveat on `(no change)`**: this only means *the vibration nudge specifically* didn't trigger
  a Doze exit in that ~400ms window. It does **not** mean Doze can't be exited some other way
  (screen-on, other motion, a charging-state change all also exit Doze), and it does **not** mean
  H1 is false — it's easy to over-read `(no change)` as "H1 disproven," which it isn't. Use the
  `GNSS_CONTINUOUS_SUMMARY`/`DOZE_TRANSITION` evidence below as the actual H1 verdict; treat
  `DOZE_PULSE` results only as "does this specific mitigation help," a narrower question.

**Verdict for H1**: compare `GNSS_STATUS`/`GNSS_SUMMARY` (per-request) and
`GNSS_CONTINUOUS_SUMMARY` (background) satellite counts against `dozeStateHeldMs` and
`DOZE_TRANSITION` timestamps for the same window. If GNSS engagement reliably drops to zero
only while `currentlyIdle=true` and recovers immediately after a `DOZE_TRANSITION
enteredIdle=false`, H1 is confirmed independent of the vibration experiment.

**If H1 is confirmed, do not reach for `dumpsys deviceidle disable`.** That was considered and
is already blocked on this channel — it requires the `DUMP` permission, which this Device Owner
app does not hold and cannot grant itself, and disabling Doze fleet-wide device-side is a much
bigger trade-off than this investigation should sign up for anyway. If H1 comes back positive,
the only viable mitigations are the ones already in scope: the vibration-pulse approach (if it
turns out to work reliably), or handling actual Doze-exit events (`DOZE_TRANSITION
enteredIdle=false`) to time captures around them, or the Device-Owner `setApplicationExemptions`/
`cmd deviceidle whitelist` path already implemented in `ensureBatteryOptimizationExempted()` —
not a shell-based global Doze disable. Whoever picks this up next should not re-propose
`dumpsys deviceidle disable` as "the real fix" without re-reading this paragraph.

**Verifying battery-optimization exemption without `dumpsys`**: `PROCESS_STATE.batteryOptExempt`
(and the older `FGS state check ... batteryOptExempt=` line) are both sourced directly from
`PowerManager.isIgnoringBatteryOptimizations(packageName)` called in-process — see
`LocationDiag.logProcessAndPowerState()`. This is the live, authoritative answer from the OS
itself at the moment of each capture, not a value assumed constant from a one-time check, and
never obtained via `dumpsys` (which isn't available on this channel either). If a capture fails
with `batteryOptExempt=false`, treat that as a real, current loss of exemption, not a stale read.

### H3 — weak/indoor GNSS signal (added after a 2026-07-03 capture raised it)

Satellite *count* alone can't tell you this: `GnssStatus` reports every satellite the chip is
tracking at any signal level, so `satellitesVisible=43` can occur even indoors. The actual
ground truth is carrier-to-noise density (C/N0, dB-Hz) per satellite, now logged on every
`GNSS_STATUS`/`GNSS_SUMMARY`/`GNSS_CONTINUOUS_SUMMARY` line:

- `cn0DbHz(min=... max=... avg=... quality=...)` on each `GNSS_STATUS` line (per status change).
- `bestMaxCn0DbHzSeen=` + `signalQuality=` on `GNSS_SUMMARY` (best seen during one capture window).
- `lastMaxCn0DbHz=` + `signalQuality=` on `GNSS_CONTINUOUS_SUMMARY` (background, every 30s).

`signalQuality` buckets (rough field reference, not a hard spec):
`STRONG(open-sky range)` ≥30 dB-Hz, `MODERATE(partial obstruction likely)` 20-30 dB-Hz,
`WEAK(consistent with indoor/heavy obstruction)` <20 dB-Hz, `NONE` if no satellites at all.
Most chips need roughly 18-20+ dB-Hz on enough satellites to compute a fix, so `WEAK` readings
that coincide with `satellitesUsedInFix=0` are the direct confirmation of an indoor/obstructed
read — not an assumption from satellite count.

**How to read it**: if a failed "Get Latest GPS" window shows `WEAK` quality throughout, that's
real evidence for an indoor/obstructed location, independent of Doze or concurrency. If it shows
`STRONG`/`MODERATE` quality but still fails (`satellitesUsedInFix=0` or the request times out
anyway), the signal itself is fine and the failure is H1/H2/architecture-related, not physical
placement. A `GNSS_FIRST_FIX` completing at all (even slowly) with `WEAK` quality leading up to
it is consistent with a marginal spot (near a door/window/skylight) rather than a true dead zone,
where a fix would likely never complete.

### Push-to-action latency

- `UPSTREAM_LATENCY` — logged once per request that has a known upstream timestamp (currently
  only `pushMessage:fetchGpsUrgent`, tagged with `upstreamEvent=pushMessage:fetchGpsUrgent`).
  `latencyMs=` is the gap between push receipt on-device and `triggerUrgent()` actually running.
  If this is large, check for `MQTT connection timeout` / `Scheduling MQTT reconnection` lines
  in the same window — the first capture showed both a ~40s gap and MQTT churn nearby.

### Timeline reconstruction

- `TIMELINE reqId=<id> stage=<x> elapsedSinceRequestStart=<ms>` — now genuinely per-request
  (via `LocationDiag.beginRequest`/`endRequest`), not a shared global that reads `n/a`. Filter
  the log to one `reqId=` at a time to get a clean single-request timeline; compare multiple
  `reqId`s side by side to see real overlap (this is corroborating evidence for H2 alongside
  `CONCURRENT_REQUESTS`, which is the authoritative signal).

## What did NOT change

With both `ExperimentalFlags` off, every code path that existed before this phase behaves
identically — the gate is never acquired, no fallback chain is ever aborted early, no vibration
fires. The only unconditional additions are logging, the continuous Doze/GNSS trackers (which
register a receiver and a GnssStatus callback but never alter location-capture control flow),
and the `reqId` plumbing itself (a parameter, not a behavior change).

## Fix this first, independent of the H1/H2 experiment results

Unlike H1 and H2, this one is not a hypothesis — it's a confirmed root cause already traced
through both the server and client source:

Every "Get Latest GPS" server click sends **two** independent pushes:
`TYPE_FETCH_GPS_URGENT` (dedicated) and, via the same `refreshDevice()` call,
`notifyDeviceOnSettingUpdate()` → `TYPE_CONFIG_UPDATED`, which separately triggers
`DetailedInfoWorker.requestConfigUpdate()` → `LocationForegroundService.triggerUrgent()` again
(throttled to once per 30s, but not deduplicated against the first).

**Fix this before running the H1/H2 comparison rounds, not after.** It is the single largest
likely contributor to `CONCURRENT_REQUESTS`/`PROCEEDING_DESPITE_INTERRUPT` evidence, and it will
fire on *every* test round regardless of which `ExperimentalFlags` are set — baseline, H1, and H2
alike. Left in place, it muddies the comparison: a round that looks like "H2 fixed it" might
really just be "the H2 gate happened to absorb this same self-inflicted double-trigger," and a
round that looks like "H1 is the dominant cause" might be conflating genuine Doze suppression
with contention from this redundant second request. Fixing it first (server-side: drop the
`notifyDeviceOnSettingUpdate()` call from `refreshDevice()`; or client-side: dedupe
near-simultaneous `triggerUrgent()` origins) isolates the H1/H2 rounds to what they're actually
meant to measure. It was left unfixed in Phase 1b only because it wasn't one of the two scoped
experimental fixes — that scoping call should be revisited before the next test round.
