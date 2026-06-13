# Plugin Audit Report — 2026-06-13

Full end-to-end audit of all 5 plugin areas: correctness, edge cases, race conditions.

---

## 1. Call Logs

### Working correctly
- Call-end trigger (`CallStateReceiver` OFFHOOK→IDLE transition with 5s delay)
- Incremental watermark upload with retry on HTTP failure
- Boot survival via manifest-registered static receiver
- Customer-scoped authorization on all admin endpoints
- Retention cleanup runs daily

### Issues found

| Sev | Issue |
|-----|-------|
| **HIGH** | **No UNIQUE constraint on `plugin_calllog_data`**. Batch insert is a plain `INSERT`. Two concurrent upload workers (or a timeout-retry) will insert the same call records twice. No `ON CONFLICT DO NOTHING` guard. |
| **MEDIUM** | **`CallStateReceiver` enqueues a new `OneTimeWorkRequest` per call with no deduplication tag and no network constraint**. Two calls in quick succession spawn two workers that both read the same watermark and upload the same records → duplicates hit the DB. Should use `enqueueUniqueWork` with `ExistingWorkPolicy.KEEP`. |
| **MEDIUM** | **`lastState` is `static` — resets on process kill**. If the app process is killed while a call is in progress, the next IDLE transition is `IDLE → IDLE` and the upload is not triggered, silently losing that call record. Should be persisted to `SharedPreferences`. |
| **MEDIUM** | **`checkPermission()` ignores the registered `plugin_calllog_access` role**. Any logged-in user can read all call logs regardless of their role. |
| **LOW** | Server error responses (`status: ERROR`) are treated as success (not HTTP 4xx/5xx), causing infinite WorkManager retry instead of `Result.failure()`. |

---

## 2. SMS Logs

### Working correctly
- ContentObserver trigger + 15-min periodic fallback
- Boot survival
- Server-side UNIQUE constraint + `ON CONFLICT DO NOTHING` (dedup solid)
- Watermark advanced per-batch (partial upload safety)
- Clock-skew guard (reset if timestamp > 5 min in future)
- Dual-server failover

### Issues found

| Sev | Issue |
|-----|-------|
| **HIGH** | **`static final SimpleDateFormat DATE_FORMAT` is not thread-safe**. The periodic worker and the ContentObserver one-time worker can run concurrently and will corrupt each other's date formatting, producing malformed timestamps. Must switch to `DateTimeFormatter` or instantiate per `doWork()` call. |
| **HIGH** | **Server returns HTTP 200 even on errors** (`error.device.not.found`, DB failure). Android treats it as success, advances the watermark, and the batch is permanently lost — the `ON CONFLICT DO NOTHING` safety net is never reached. Server must return HTTP 4xx/5xx for errors. |
| **MEDIUM** | **Unique index does not protect `phonenumber = NULL`**. In PostgreSQL, `NULL ≠ NULL` in unique indexes, so two SMS from unknown senders at the same timestamp produce duplicate rows. |
| **LOW** | `checkPermission()` in `SmsLogResource` does not check `plugin_smslog_access` role — same issue as call logs. |
| **LOW** | `smsDate` string uses device JVM timezone (no explicit UTC), making the column unreliable across devices with different locales. |

---

## 3. WorkTime

### Working correctly
- Overnight window crossing midnight — handled correctly
- Exception (holiday) grants access — short-circuits enforcement correctly
- UPSERT covers all columns
- Exception cleanup task with per-customer isolation
- Frontend modal auto-refresh guard
- Overlapping exceptions are merged correctly
- Sync hook gracefully degrades if reflection fails

### Issues found

| Sev | Issue |
|-----|-------|
| **CRITICAL** | **`WorkTimePublicResource` hard-codes `ZoneId.of("Asia/Kolkata")`** instead of `WorkTimeZone.ZONE`. All other components use the configurable `WorkTimeZone.ZONE`. Any deployment not in IST will get boundary evaluations wrong by up to 5:30 hours on the *device-facing* API. File: `WorkTimePublicResource.java:35` |
| **HIGH** | **`parseTimestamp` fallback uses `ZoneId.systemDefault()`** when parsing ISO datetime strings without timezone offset (which is what the frontend sends). If JVM default timezone ≠ `WorkTimeZone.ZONE`, stored `start_datetime`/`end_datetime` are silently wrong. File: `WorkTimeDeviceOverride.java:172` |
| **HIGH** | **`markExceptionStartPushSentById`/`markExceptionEndPushSentById` have no `customer_id` guard**. Filter is only on `id`. With sequential SERIAL IDs, a bug with a wrong ID could mark another customer's exception. Should add `AND customer_id = #{customerId}`. |
| **MEDIUM** | **Short exception that starts and ends between cleanup task ticks never sends a start-boundary push** — only end-boundary push fires. Device never receives the "exception started" signal. |
| **MEDIUM** | **`PUSH_RETRY_EXECUTOR` is `static` in a Guice singleton**. On hot-redeploy (WAR reload) the old executor is never shut down, leaking threads. The shutdown hook only fires at JVM exit. |
| **MEDIUM** | **Wildcard `"*"` in per-device app policy breaks on round-trip**. When the admin sets "allow all apps", `buildAppsString` replaces `"*"` with an explicit enumeration of currently-installed apps. A newly-installed app won't be allowed until the admin manually re-saves the policy. File: `worktime.module.js:237-247` |
| **LOW** | No overlap validation for exception windows — overlapping exceptions accepted silently, causing double boundary push notifications. |

---

## 4. GPS / Location Tracking & Map

### Working correctly
- No foreground service, no notification — confirmed
- Static device: uploads every cycle (correct, server needs time-series)
- GPS → network/cell tower fallback
- `ACCESS_BACKGROUND_LOCATION` requested at runtime correctly
- `sendLocations()` synchronized correctly across threads
- Stale-fix fallback for urgent capture
- Queue survives reboot (SQLite)

### Issues found

| Sev | Issue |
|-----|-------|
| **HIGH** | **`DynamicInfoHelper.buildDetailedInfo(context, location, isUrgent=true)` forces `ts = System.currentTimeMillis()`**, overriding the carefully-preserved original fix timestamp. This makes stale-fallback detection on the server meaningless — all urgent uploads appear as if the fix is current. File: `DynamicInfoHelper.java:59` |
| **MEDIUM** | **No automatic queue flush on network reconnect**. After going offline and reconnecting, the queue is flushed only on the next periodic WorkManager cycle (up to 15 minutes). The `CONNECTIVITY_ACTION` receiver in `MainActivity` exists but doesn't call `LocationUploader.sendLocations()`. |
| **MEDIUM** | **Urgent GPS fails silently if push channel (MQTT/long-poll) is down**. Server fires push and forgets; browser polls for 120s then shows failure with no indication the fix will arrive on the next periodic cycle. No retry or fallback on server side. |
| **LOW** | **`INSERT OR IGNORE` in `LocationTable` has no UNIQUE constraint to trigger on** — effectively dead code. Dedup protection is not implemented. |
| **LOW** | **Queue is drained oldest-first** (`ORDER BY ts ASC`). During a large backlog flush the server map shows old positions before new ones. |
| **LOW** | **Double location capture per admin "Get latest" click**: both `TYPE_FETCH_GPS_URGENT` AND `notifyDeviceOnSettingUpdate` are sent, triggering two uploads. Second is harmless but noisy. |
| **LOW** | `GPS_STATE_LOST` can fire for a valid fix if HTTP upload is delayed >60s (freshness window mismatch with 15-min capture interval). |

---

## 5. Internet Connection

### Working correctly
- `NET_CAPABILITY_VALIDATED` used on API ≥ M (no captive portal false positive)
- DeviceView server-side stale override (forces offline if device unseen > 4h)
- Push-triggered urgent refresh path works
- Transport detection covers WiFi/LTE/5G/VPN/Ethernet

### Issues found

| Sev | Issue |
|-----|-------|
| **MEDIUM** | **`CONNECTIVITY_ACTION` broadcast is not delivered to background apps on Android 7+ (targetSdk 35)**. Receiver in `MainActivity` only fires when foregrounded. No `NetworkCallback` registered for background connectivity-change detection. Connectivity state uploaded to server is only as fresh as the last 15-min `SendDeviceInfoWorker` cycle. |
| **MEDIUM** | **`waitForConnectivityUpdate` on the server blocks a JAX-RS thread for up to 12 seconds** per admin refresh click. Under concurrent requests this can exhaust the servlet thread pool. Needs async handling. |
| **LOW** | VPN transport masks the physical transport — admin can't distinguish "VPN over LTE" vs "VPN over WiFi". |
| **LOW** | Pre-API-M path uses `isConnected()` without `NET_CAPABILITY_VALIDATED` — captive portals report as online. |
| **LOW** | Bulk `refreshConnectivityStateBulk` fires for every device on every controller recreation (route change resets `initialLoadDone`), sending a burst of push messages. |
| **INFO** | Historical internet connectivity data is not stored in the deviceinfo plugin table — only the latest snapshot is kept. |

---

## Fix Priority

### Must fix — bugs that cause wrong data or data loss
1. `WorkTimePublicResource` hardcoded `Asia/Kolkata` timezone — worktime broken on any non-IST server
2. `WorkTimeDeviceOverride.parseTimestamp` uses `systemDefault()` — wrong exception windows stored
3. Call log duplicate inserts — missing UNIQUE constraint + no unique-work dedup on Android
4. SMS `SimpleDateFormat` thread-safety — data corruption on concurrent workers
5. SMS server returns HTTP 200 on error — data permanently lost on upload failure
6. GPS `DynamicInfoHelper` forces `ts=now` on urgent path — timestamp corruption

### Should fix — reliability gaps
7. `CallStateReceiver.lastState` static field lost on process kill — missed call records
8. WorkTime `markExceptionStartPushSentById` missing `customer_id` guard
9. WorkTime wildcard `"*"` app policy breaks on round-trip in JS
10. GPS queue flush on network reconnect (up to 15-min delay)

### Nice to fix — minor/cosmetic
- Role-based access check missing in call log and SMS log resources
- SMS `NULL phonenumber` dedup gap in unique index
- `INSERT OR IGNORE` dead code in `LocationTable`
- WorkTime `PUSH_RETRY_EXECUTOR` static leak on hot-redeploy
