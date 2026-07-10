/*
 * Brother Pharmamach MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.brother.pharmach.mdm.launcher.util;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.Const;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Closes the "already-open app" / "internal navigation" loophole in the mobile-data policy
 * enforcement: while StatusControlService's mobile-data violation is active, this suspends every
 * installed launchable app except a small allowlist via DevicePolicyManager — the same mechanism
 * WorkTimeManager.enforceWorkTimeRestrictions() already uses for WorkTime — so no launch vector
 * (home screen, recents, deep link, notification tap) can reach a blocked app, not just the
 * reactive accessibility-event bounce that CheckForegroundAppAccessibilityService performs.
 *
 * Fully independent from WorkTimeManager: never modifies its policy or state. On restore
 * (blockingActive == false) it hands control back to WorkTime by only unsuspending packages
 * WorkTimeManager.isAppAllowed() currently also allows, instead of unsuspending everything.
 */
public final class MobileDataAppBlocker {
    private static final String TAG = "MobileDataAppBlocker";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    // Packages never suspended, in addition to Utils.isAllowedDuringMobileDataViolation()
    // (Settings/phone/systemui/permissioncontroller).
    private static final Set<String> STATIC_ALLOWLIST = new HashSet<>(Arrays.asList(
            "android",
            Const.GSF_PACKAGE_NAME
    ));

    private MobileDataAppBlocker() {
    }

    /** Fire-and-forget: runs off the caller's thread, never throws back to it. */
    public static void enforceAsync(Context context, boolean blockingActive) {
        final Context appContext = context.getApplicationContext();
        try {
            EXECUTOR.execute(() -> {
                try {
                    enforce(appContext, blockingActive);
                } catch (Throwable t) {
                    Log.e(TAG, "enforce failed", t);
                }
            });
        } catch (Exception e) {
            // Executor rejected the task (shutting down) — the next watchdog tick or
            // reassertion will retry, safe to ignore.
        }
    }

    private static void enforce(Context context, boolean blockingActive) {
        Log.i(TAG, "enforce called, blockingActive=" + blockingActive);

        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = LegacyUtils.getAdminComponentName(context);
        boolean isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = context.getPackageManager();

        List<ApplicationInfo> installedApps;
        try {
            // getInstalledApplications() marshals the full app list across a Binder transaction
            // and can throw wrapping TransactionTooLargeException/DeadObjectException. Guard it
            // so a transient failure doesn't crash the caller — the next tick retries.
            installedApps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            Log.e(TAG, "getInstalledApplications failed, skipping this pass", t);
            return;
        }
        if (installedApps == null) {
            return;
        }

        ArrayList<String> pkgsToSuspend = new ArrayList<>();
        ArrayList<String> pkgsToUnsuspend = new ArrayList<>();

        for (ApplicationInfo appInfo : installedApps) {
            try {
                String pkg = appInfo.packageName;
                if (pkg == null || pkg.equals(context.getPackageName())) {
                    continue;
                }
                if (STATIC_ALLOWLIST.contains(pkg)
                        || Utils.isAllowedDuringMobileDataViolation(context, pkg)) {
                    continue;
                }
                // Only enforce on apps that are launchable (have an icon, or are already
                // suspended/hidden by us) to avoid touching core system services.
                if (!Utils.isAppLaunchable(context, pkg)) {
                    continue;
                }

                // blockingActive: absolute override — suspend everything not on the allowlist,
                // even apps WorkTime's own schedule would currently allow. Restoring: hand
                // control back to WorkTime instead of unsuspending everything, so a package
                // WorkTime independently wants blocked right now doesn't get let back in.
                boolean allowed = !blockingActive && WorkTimeManager.getInstance().isAppAllowed(pkg);

                if (allowed) {
                    pkgsToUnsuspend.add(pkg);
                } else if (blockingActive) {
                    pkgsToSuspend.add(pkg);
                    // Fallback for non-device-owner builds, and belt-and-suspenders for an
                    // already-running instance even when device-owner suspension applies.
                    if (am != null) {
                        try {
                            Method forceStopMethod = am.getClass().getMethod("forceStopPackage", String.class);
                            forceStopMethod.invoke(am, pkg);
                        } catch (Exception e) {
                            try {
                                am.killBackgroundProcesses(pkg);
                            } catch (Exception ex) {
                                Log.e(TAG, "Failed to kill background processes for " + pkg, ex);
                            }
                        }
                    }
                }
                // !blockingActive && !allowed: leave it exactly as-is (WorkTime owns it).
            } catch (Throwable t) {
                // Never let a single problematic package abort the whole pass.
                Log.w(TAG, "enforce: skipping package due to error", t);
            }
        }

        if (isDeviceOwner && dpm != null && adminComponent != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    if (!pkgsToSuspend.isEmpty()) {
                        dpm.setPackagesSuspended(adminComponent, pkgsToSuspend.toArray(new String[0]), true);
                    }
                    if (!pkgsToUnsuspend.isEmpty()) {
                        dpm.setPackagesSuspended(adminComponent, pkgsToUnsuspend.toArray(new String[0]), false);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update package suspension states", e);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (String pkg : pkgsToSuspend) {
                    try {
                        dpm.setApplicationHidden(adminComponent, pkg, true);
                    } catch (Exception e) {
                    }
                }
                for (String pkg : pkgsToUnsuspend) {
                    try {
                        dpm.setApplicationHidden(adminComponent, pkg, false);
                    } catch (Exception e) {
                    }
                }
            }
        }

        Log.i(TAG, "enforce completed, suspended=" + pkgsToSuspend.size()
                + ", unsuspended=" + pkgsToUnsuspend.size());

        if (blockingActive) {
            closeDisallowedRunningTasks(am, pkgsToSuspend);
        }
    }

    /**
     * Closes the specific loophole this feature targets: an app already open (foreground or in
     * recents) when the violation starts would otherwise keep running until backgrounded/reopened
     * even after being suspended. Mirrors WorkTimeManager.removeRestrictedFromRecents()'s
     * mechanics, scoped to this pass's own suspend list.
     */
    private static void closeDisallowedRunningTasks(ActivityManager am, List<String> disallowedPkgs) {
        if (am == null || disallowedPkgs.isEmpty()) {
            return;
        }
        Set<String> disallowedSet = new HashSet<>(disallowedPkgs);
        try {
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                try {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (info == null) continue;
                    String pkg = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.topActivity != null) {
                        pkg = info.topActivity.getPackageName();
                    } else if (info.baseIntent != null && info.baseIntent.getComponent() != null) {
                        pkg = info.baseIntent.getComponent().getPackageName();
                    }
                    if (pkg != null && disallowedSet.contains(pkg)) {
                        task.finishAndRemoveTask();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "closeDisallowedRunningTasks: skipping task due to error", t);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "closeDisallowedRunningTasks unavailable on this device", e);
        }
    }
}
