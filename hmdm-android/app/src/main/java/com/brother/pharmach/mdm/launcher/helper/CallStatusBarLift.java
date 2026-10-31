/*
 * Brother Pharmamach MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.brother.pharmach.mdm.launcher.helper;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.AdminReceiver;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;

/**
 * Single authority over the kiosk status-bar (notification shade) lockdown, and the ONE place that
 * temporarily lifts it so an arriving call can present itself.
 *
 * <h3>Why a lift is needed at all</h3>
 * The kiosk keeps the shade locked through three independent levers (all applied here so they can
 * never drift apart):
 * <ol>
 *   <li><b>Primary</b> — {@link DevicePolicyManager#setStatusBarDisabled} (API 24+, device owner).
 *       Despite the name it does not blank the bar: the platform applies only
 *       {@code DISABLE_EXPAND}, {@code DISABLE_NOTIFICATION_ICONS},
 *       {@code DISABLE_NOTIFICATION_ALERTS}, {@code DISABLE_SEARCH} and
 *       {@code DISABLE2_QUICK_SETTINGS}, so the clock / battery / signal stay visible while the
 *       shade and quick settings become unreachable. Note the framework <b>defers</b> this lever
 *       while lock-task is running — which is one reason this app no longer uses lock-task at all
 *       (see {@code ProUtils.startCosuKioskMode}); with pinning gone it applies immediately.</li>
 *   <li>Our own transparent touch-blocking bar over the status bar strip
 *       ({@code ProUtils.preventStatusBarExpansion}). This is what covers Android 6 (API 23), where
 *       lever 1 does not exist.</li>
 *   <li>{@link DevicePolicyManager#setLockTaskFeatures} <b>without</b>
 *       {@code LOCK_TASK_FEATURE_NOTIFICATIONS} (API 28+). Now vestigial, since we never enter
 *       lock-task; kept applied so that re-enabling pinning would need no change here, and so a
 *       device still pinned from an older build behaves correctly until it drops out.</li>
 * </ol>
 * With alerts blocked, an incoming cellular call on Android 10+ rings but its heads-up
 * accept/decline notification is suppressed — the user never sees who is calling. So the moment a
 * call starts <b>ringing</b> we drop all three levers, and the moment it stops ringing (answered,
 * declined, missed, ended, or any other state) we put them straight back.
 *
 * <h3>State model — lifted ONLY while ringing</h3>
 * RINGING → lifted. Everything else (in-call/OFFHOOK, IDLE, call ended, unknown) → locked. The
 * shade is therefore never available to the user during a conversation, only for the few seconds
 * the phone is ringing.
 *
 * <h3>Fail-safe design</h3>
 * A lift that is never undone would silently defeat the kiosk, so it can be undone from four
 * independent directions:
 * <ul>
 *   <li>every ring-detection source also reports the non-ringing transition (see callers);</li>
 *   <li>a {@value #VERIFY_INTERVAL_MS}ms watchdog re-locks as soon as telephony is no longer
 *       ringing;</li>
 *   <li>a hard {@value #MAX_LIFT_MS}ms cap re-locks regardless of what telephony reports;</li>
 *   <li>the lift is persisted, so {@link #reconcile(Context)} (called from the launcher, the status
 *       control service and boot) re-locks after a process death that skipped the restore.</li>
 * </ul>
 *
 * <p>API-DIFF / coverage, now that lock-task is never used:
 * <ul>
 *   <li>Android 7 → 16 (API 24+): lever 1 does the work, so the shade and heads-up alerts return
 *       for the ring and are blocked again immediately afterwards.</li>
 *   <li>Android 6 (API 23): {@code setStatusBarDisabled} does not exist, so blocking is lever 2
 *       (the touch blocker) alone and the lift simply removes it. Alerts are never suppressed on
 *       API 23 in the first place, so the incoming-call notification shows regardless.</li>
 * </ul>
 */
public final class CallStatusBarLift {

    private static final String TAG = "CallStatusBarLift";

    private static final String PREFS = "CallStatusBarLift";
    private static final String PREF_LIFTED = "lifted";

    /** Re-check cadence while lifted: confirms the phone is still ringing, else re-locks. */
    private static final long VERIFY_INTERVAL_MS = 3_000L;
    /**
     * Hard cap on a single lift. No ring lasts this long, so if we are still lifted the state is
     * stale (a missed transition) and the kiosk lockdown must win.
     */
    private static final long MAX_LIFT_MS = 90_000L;

    private CallStatusBarLift() {}

    private static final Object LOCK = new Object();
    private static volatile boolean sLifted;
    private static volatile long sLiftedAtElapsed;
    private static Handler sHandler;
    private static Runnable sWatchdog;

    // ---------------------------------------------------------------------------------------------
    // Lock-task feature sets. Callers that (re)configure kiosk mode MUST use
    // currentLockTaskFeatures() so re-applying kiosk options mid-ring does not clobber the lift.
    // ---------------------------------------------------------------------------------------------

    /**
     * Feature set for the normal, locked state: HOME (kiosk home), KEYGUARD, SYSTEM_INFO (keeps the
     * clock / battery / signal icons visible) and OVERVIEW (Recents stays usable). NOTIFICATIONS is
     * deliberately absent — that is what blocks shade expansion and heads-up alerts.
     */
    public static int lockedLockTaskFeatures() {
        return DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                | DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
                | DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
    }

    /**
     * The locked set PLUS notifications, used only while a call is ringing.
     * NOTIFICATIONS requires HOME to also be set (the framework rejects it otherwise) — it is.
     */
    public static int liftedLockTaskFeatures() {
        return lockedLockTaskFeatures() | DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
    }

    /** The feature set matching the CURRENT lift state. Use this when (re)applying kiosk options. */
    public static int currentLockTaskFeatures() {
        return sLifted ? liftedLockTaskFeatures() : lockedLockTaskFeatures();
    }

    public static boolean isLifted() {
        return sLifted;
    }

    // ---------------------------------------------------------------------------------------------
    // Entry points for the ring-detection sources
    // ---------------------------------------------------------------------------------------------

    /**
     * A call is RINGING — unlock the shade immediately so the accept/decline notification can be
     * posted and pulled down. Idempotent and safe to call from any thread; the DPM calls are direct
     * binder calls (no thread hop, no post) precisely so this lands as fast as possible.
     */
    public static void liftForRingingCall(Context context, String source) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            boolean first = !sLifted;
            sLifted = true;
            sLiftedAtElapsed = SystemClock.elapsedRealtime();
            // Unlock FIRST, persist second: the DPM calls are what the ringing call is waiting on,
            // and the persisted flag only matters later (for reconcile() after a process death).
            applyStatusBarLock(app, false);
            showInCallScreen(app);
            if (first) {
                persist(app, true);
            }
            armWatchdog(app);
            if (first) {
                Log.i(TAG, "Status bar shade LIFTED for ringing call (" + source + ")");
                RemoteLogger.log(app, Const.LOG_INFO,
                        "Status bar unlocked for ringing call (" + source + ")");
            }
        }
    }

    private static void showInCallScreen(final Context app) {
        try {
            TelecomManager tm = (TelecomManager) app.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) {
                tm.showInCallScreen(false);
            }
        } catch (Throwable e) {
            Log.w(TAG, "showInCallScreen failed: " + e.getMessage());
        }
        Handler h = handler();
        if (h != null) {
            h.postDelayed(() -> {
                try {
                    TelecomManager tm = (TelecomManager) app.getSystemService(Context.TELECOM_SERVICE);
                    if (tm != null && sLifted) {
                        tm.showInCallScreen(false);
                    }
                } catch (Throwable ignored) {
                }
            }, 150);
        }
    }

    /**
     * The phone is not ringing any more — answered / in call, declined, missed, ended or any other
     * state. Puts the kiosk lockdown straight back. Cheap no-op when already locked.
     */
    public static void relock(Context context, String source) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            boolean wasLifted = sLifted || readPersisted(app);
            sLifted = false;
            cancelWatchdog();
            if (!wasLifted) {
                return;
            }
            applyStatusBarLock(app, true);
            persist(app, false);
            collapseShade(app);
            Log.i(TAG, "Status bar shade RE-LOCKED (" + source + ")");
            RemoteLogger.log(app, Const.LOG_INFO, "Status bar re-locked (" + source + ")");
        }
    }

    /**
     * Aligns the lockdown with what telephony actually reports right now. This is the recovery path:
     * it re-locks a lift that was left behind by a process death (the flag is persisted) and lifts
     * again if a call started ringing while we were not listening. Safe to call on any cadence.
     */
    public static void reconcile(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        boolean ringing = isRinging(app);
        if (ringing) {
            liftForRingingCall(app, "reconcile");
        } else if (sLifted || readPersisted(app)) {
            // Grace window: if sLifted was set within the last 5 seconds (e.g. by notification listener or call state),
            // do not re-lock immediately on reconcile in case TelephonyManager state lagged or call notification was posted first.
            if (sLifted && (SystemClock.elapsedRealtime() - sLiftedAtElapsed < 5000L)) {
                return;
            }
            relock(app, "reconcile/not-ringing");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The three levers
    // ---------------------------------------------------------------------------------------------

    private static void applyStatusBarLock(Context app, boolean locked) {
        DevicePolicyManager dpm =
                (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(app, AdminReceiver.class);
        boolean deviceOwner = false;
        try {
            deviceOwner = dpm != null && dpm.isDeviceOwnerApp(app.getPackageName());
        } catch (Exception ignored) {
        }

        if (deviceOwner) {
            // Lever 1 (PRIMARY) — the actual shade gate now that lock-task is gone. Applied FIRST
            // because it is what unblocks the incoming-call heads-up.
            // API-DIFF: Android 7.0 (API 24).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    dpm.setStatusBarDisabled(admin, locked);
                } catch (Exception e) {
                    Log.w(TAG, "setStatusBarDisabled(" + locked + ") failed: " + e.getMessage());
                }
            }
            // Lever 3 — vestigial: only has any effect while lock-task is running, which this build
            // never starts. Kept in sync so a device still pinned from an older build behaves.
            // API-DIFF: Android 9.0 (API 28).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    dpm.setLockTaskFeatures(admin,
                            locked ? lockedLockTaskFeatures() : liftedLockTaskFeatures());
                } catch (Exception e) {
                    Log.w(TAG, "setLockTaskFeatures(" + (locked ? "locked" : "lifted") + ") failed: "
                            + e.getMessage());
                }
            }
        }

        // Lever 2 — our own transparent blocking bar. Present on every API level and the ONLY
        // blocker on Android 6, so it must be dropped too or the user still cannot pull the shade
        // down during a ring.
        try {
            com.brother.pharmach.mdm.launcher.pro.ProUtils.setStatusBarBlockerEnabled(app, locked);
        } catch (Exception e) {
            Log.w(TAG, "status bar blocker toggle failed: " + e.getMessage());
        }
    }

    /**
     * Best-effort close of a shade the user left open, so re-locking cannot strand a half-open
     * panel over the kiosk. {@code collapsePanels()} is not public SDK; EXPAND_STATUS_BAR (a
     * normal permission) is declared in the manifest for it.
     */
    private static void collapseShade(Context app) {
        try {
            Object sbm = app.getSystemService("statusbar");
            if (sbm == null) {
                return;
            }
            Class<?> cls = Class.forName("android.app.StatusBarManager");
            String method = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                    ? "collapsePanels" : "collapse";
            cls.getMethod(method).invoke(sbm);
        } catch (Throwable ignored) {
            // Hidden API removed / blocked on this build — the lockdown itself already applied.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Watchdog + persistence
    // ---------------------------------------------------------------------------------------------

    private static Handler handler() {
        if (sHandler == null) {
            Looper main = Looper.getMainLooper();
            if (main == null) {
                return null;
            }
            sHandler = new Handler(main);
        }
        return sHandler;
    }

    private static void armWatchdog(final Context app) {
        Handler h = handler();
        if (h == null) {
            return;
        }
        if (sWatchdog == null) {
            sWatchdog = new Runnable() {
                @Override
                public void run() {
                    if (!sLifted) {
                        return;
                    }
                    long liftedFor = SystemClock.elapsedRealtime() - sLiftedAtElapsed;
                    if (liftedFor >= MAX_LIFT_MS) {
                        relock(app, "watchdog/max-lift-exceeded");
                        return;
                    }
                    if (!isRinging(app)) {
                        relock(app, "watchdog/not-ringing");
                        return;
                    }
                    Handler h = handler();
                    if (h != null) {
                        h.postDelayed(this, VERIFY_INTERVAL_MS);
                    }
                }
            };
        }
        h.removeCallbacks(sWatchdog);
        h.postDelayed(sWatchdog, VERIFY_INTERVAL_MS);
    }

    private static void cancelWatchdog() {
        Handler h = handler();
        if (h != null && sWatchdog != null) {
            h.removeCallbacks(sWatchdog);
        }
    }

    /** True while a cellular call is ringing (not yet answered), from either available source. */
    private static boolean isRinging(Context app) {
        // Our own InCallService, when this app happens to hold the dialer role.
        try {
            if (com.brother.pharmach.mdm.launcher.phone.CallManager.getInstance()
                    .isIncomingRinging()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        // Platform call state — the authoritative source when the stock dialer owns the call.
        try {
            TelephonyManager tm = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                return tm.getCallState() == TelephonyManager.CALL_STATE_RINGING;
            }
        } catch (Exception ignored) {
            // READ_PHONE_STATE missing on an OEM build — fall through; the max-lift cap still bounds
            // the lift, and every ring source also reports the non-ringing transition explicitly.
        }
        return false;
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void persist(Context app, boolean lifted) {
        try {
            // commit(), not apply(): the caller may be a short-lived manifest receiver whose
            // process can be killed before an async write lands, which would lose the flag that
            // reconcile() relies on to undo a stale lift.
            prefs(app).edit().putBoolean(PREF_LIFTED, lifted).commit();
        } catch (Exception ignored) {
        }
    }

    private static boolean readPersisted(Context app) {
        try {
            return prefs(app).getBoolean(PREF_LIFTED, false);
        } catch (Exception e) {
            return false;
        }
    }
}
