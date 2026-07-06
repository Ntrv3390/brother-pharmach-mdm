package com.brother.pharmach.mdm.launcher.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.EffectiveWorkTimePolicy;
import com.brother.pharmach.mdm.launcher.json.ServerConfig;
import com.brother.pharmach.mdm.launcher.json.WorkTimePolicyWrapper;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class WorkTimeManager {
    private static final String TAG = "WorkTimeManager";
    public static final String ACTION_WORKTIME_POLICY_UPDATED = "com.brother.pharmach.mdm.launcher.action.WORKTIME_POLICY_UPDATED";
    private static final long MIN_FETCH_INTERVAL_MS = 60_000;
    private static final long FORCE_REFRESH_RETRY_DELAY_MS = 3_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService NETWORK_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ScheduledExecutorService RETRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    // Issue 2 grace window: when the user deliberately taps an app in the launcher, suppress the
    // enforcement watchers (accessibility service, UsageStats poller, ACTION_HIDE_SCREEN) for that
    // package for a few seconds. This prevents a transient policy/time disagreement (e.g. a minute
    // rollover or an in-flight policy refresh right after the tap) from yanking the launcher back
    // to the front and killing an app the user legitimately opened.
    private static final long USER_LAUNCH_GRACE_MS = 5_000;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> userLaunchGrace =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static WorkTimeManager instance;
    private volatile Context appContext;
    private volatile EffectiveWorkTimePolicy policy;
    private volatile String lastAppliedConfigPolicyRaw;
    private Boolean lastWorkTimeState = null;
    private volatile long lastFetchAttemptMs = 0;

    public static synchronized WorkTimeManager getInstance() {
        if (instance == null) {
            instance = new WorkTimeManager();
        }
        return instance;
    }
    
    public boolean shouldRefreshUI() {
        if (policy == null) {
            return false;
        }

        if (!isEnforcementActiveNow()) {
            if (lastWorkTimeState == null || lastWorkTimeState) {
                lastWorkTimeState = false;
                return true;
            }
            return false;
        }

        boolean currentWorkTimeState = isCurrentTimeWorkTime();
        if (lastWorkTimeState == null || lastWorkTimeState != currentWorkTimeState) {
            lastWorkTimeState = currentWorkTimeState;
            return true;
        }
        return false;
    }

    /** Records a deliberate user launch of {@code packageName} to open a short grace window. */
    public void markUserLaunched(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }
        userLaunchGrace.put(packageName, System.currentTimeMillis() + USER_LAUNCH_GRACE_MS);
    }

    /** True while a recent user-initiated launch of {@code packageName} should not be blocked. */
    public boolean isWithinUserLaunchGrace(String packageName) {
        if (packageName == null) {
            return false;
        }
        Long expiry = userLaunchGrace.get(packageName);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiry) {
            userLaunchGrace.remove(packageName);
            return false;
        }
        return true;
    }

    public void updatePolicy(Context context) {
        updatePolicy(context, false);
    }

    public void updatePolicy(Context context, boolean forceRefresh) {
        if (context != null) {
            this.appContext = context.getApplicationContext();
        }
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        if (settingsHelper == null) return;
        
        ServerConfig config = settingsHelper.getConfig();
        boolean parsedFromConfig = false;
        if (config != null && config.getCustom1() != null) {
            try {
                // Ensure the string looks like JSON before parsing to avoid unnecessary exceptions
                String custom1 = config.getCustom1();
                if (custom1.trim().startsWith("{")) {
                    WorkTimePolicyWrapper wrapper = MAPPER.readValue(custom1, WorkTimePolicyWrapper.class);
                    if ("worktime".equals(wrapper.getPluginId()) && wrapper.getPolicy() != null) {
                        // Keep server-fetched policy authoritative unless the config payload itself changed.
                        // This avoids stale custom1 values repeatedly overriding live per-device exceptions.
                        boolean configPolicyChanged = !custom1.equals(lastAppliedConfigPolicyRaw);
                        if (this.policy == null || configPolicyChanged) {
                            this.policy = wrapper.getPolicy();
                            this.lastAppliedConfigPolicyRaw = custom1;
                            parsedFromConfig = true;
                            notifyPolicyUpdated(context);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse WorkTime policy from custom1", e);
            }
        }

        if (!parsedFromConfig || this.policy == null || forceRefresh) {
            maybeFetchPolicyFromServer(context, forceRefresh);
        }
    }

    private void maybeFetchPolicyFromServer(Context context, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh && now - lastFetchAttemptMs < MIN_FETCH_INTERVAL_MS) {
            return;
        }
        lastFetchAttemptMs = now;

        final Context appContext = context.getApplicationContext();
        NETWORK_EXECUTOR.execute(() -> fetchPolicyFromServer(appContext));

        if (forceRefresh) {
            RETRY_EXECUTOR.schedule(
                    () -> NETWORK_EXECUTOR.execute(() -> fetchPolicyFromServer(appContext)),
                    FORCE_REFRESH_RETRY_DELAY_MS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void fetchPolicyFromServer(Context context) {
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        if (settingsHelper == null) {
            return;
        }

        String deviceId = settingsHelper.getDeviceId();
        String serverProject = settingsHelper.getServerProject();
        if (deviceId == null || deviceId.trim().isEmpty() || serverProject == null || serverProject.trim().isEmpty()) {
            return;
        }

        String payload = null;
        try {
            ServerService primary = ServerServiceKeeper.getServerServiceInstance(context);
            Response<ResponseBody> response = primary.getWorkTimePolicy(serverProject, deviceId).execute();
            if (response != null && response.isSuccessful() && response.body() != null) {
                payload = response.body().string();
            }
        } catch (Exception e) {
            Log.w(TAG, "Primary server WorkTime policy fetch failed", e);
        }

        if (payload == null) {
            try {
                ServerService secondary = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
                Response<ResponseBody> response = secondary.getWorkTimePolicy(serverProject, deviceId).execute();
                if (response != null && response.isSuccessful() && response.body() != null) {
                    payload = response.body().string();
                }
            } catch (Exception e) {
                Log.w(TAG, "Secondary server WorkTime policy fetch failed", e);
            }
        }

        if (payload == null || payload.trim().isEmpty()) {
            return;
        }

        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode policyNode = root;

            if (root.has("status")) {
                String status = root.path("status").asText();
                if (!"OK".equalsIgnoreCase(status)) {
                    return;
                }
                policyNode = root.path("data");
            }

            if (policyNode != null && !policyNode.isMissingNode() && !policyNode.isNull()) {
                EffectiveWorkTimePolicy serverPolicy = MAPPER.treeToValue(policyNode, EffectiveWorkTimePolicy.class);
                if (serverPolicy != null) {
                    this.policy = serverPolicy;
                    notifyPolicyUpdated(context);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse WorkTime policy payload", e);
        }
    }

    private void notifyPolicyUpdated(Context context) {
        try {
            LocalBroadcastManager.getInstance(context.getApplicationContext())
                    .sendBroadcast(new Intent(ACTION_WORKTIME_POLICY_UPDATED));
        } catch (Exception e) {
            Log.w(TAG, "Failed to notify policy update", e);
        }
    }

    /** Context-aware variant: remembers the app context for the battery-compliance override. */
    public boolean isAppAllowed(Context context, String packageName) {
        if (context != null && appContext == null) {
            this.appContext = context.getApplicationContext();
        }
        return isAppAllowed(packageName);
    }

    public boolean isAppAllowed(String packageName) {
        if (isInfrastructurePackage(packageName)) {
            return true;
        }

        if (policy == null || !isEnforcementActiveNow()) {
            return true;
        }

        // Check current time
        boolean isWorkTime = isCurrentTimeWorkTime();

        if (isWorkTime) {
            return isPackageAllowed(packageName, policy.getAllowedDuring());
        } else {
            return isPackageAllowed(packageName, policy.getAllowedOutside());
        }
    }

    /**
     * Returns true if enforcement is active and the current time is within the WorkTime window.
     * Use this to decide whether to bring the launcher to the foreground.
     */
    public boolean isWorkTimeActive() {
        return policy != null && isEnforcementActiveNow() && isCurrentTimeWorkTime();
    }

    /**
     * Returns true only while WorkTime enforcement is active now (no exception)
     * and current time is inside configured WorkTime window.
     */
    public boolean shouldLockSettingsNow() {
        if (policy == null) {
            return false;
        }
        return isEnforcementActiveNow() && isCurrentTimeWorkTime();
    }

    private boolean isPackageAllowed(String packageName, List<String> list) {
        if (list == null) return false;
        if (list.contains("*")) return true;
        return list.contains(packageName);
    }

    private boolean isInfrastructurePackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return true;
        }

        // Never block system shell/navigation surfaces (Recents/Home/System UI).
        if ("android".equals(packageName)
                || "com.android.systemui".equals(packageName)
                || "com.android.permissioncontroller".equals(packageName)
                || "com.google.android.permissioncontroller".equals(packageName)) {
            return true;
        }

        // OEM launchers often host the recents overview.
        return "com.miui.home".equals(packageName)
                || "com.huawei.android.launcher".equals(packageName)
                || "com.sec.android.app.launcher".equals(packageName)
                || "com.oneplus.launcher".equals(packageName)
                || "com.oppo.launcher".equals(packageName)
                || "com.vivo.launcher".equals(packageName)
                || "com.transsion.itel.launcher".equals(packageName)
                || "com.transsion.infinix.xlauncher".equals(packageName)
                || "com.transsion.tecno.launcher".equals(packageName);
    }

    private boolean isCurrentTimeWorkTime() {
        if (policy == null || policy.getStartTime() == null || policy.getEndTime() == null) {
            return false;
        }

        Calendar now = Calendar.getInstance();

        int currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startMinute = parseTime(policy.getStartTime());
        int endMinute = parseTime(policy.getEndTime());

        boolean withinWork;
        if (startMinute == endMinute) {
            // Keep client logic aligned with server plugin: equal start/end means full day.
            withinWork = true;
        } else if (startMinute < endMinute) {
            withinWork = currentMinute >= startMinute && currentMinute <= endMinute;
        } else {
            withinWork = currentMinute >= startMinute || currentMinute <= endMinute;
        }

        if (!withinWork) {
            return false;
        }

        Calendar checkDay = (Calendar) now.clone();
        if (startMinute > endMinute && currentMinute < endMinute) {
            checkDay.add(Calendar.DAY_OF_YEAR, -1);
        }

        int mask = getServerDayMask(checkDay);
        return (policy.getDaysOfWeek() & mask) != 0;
    }

    private boolean isEnforcementActiveNow() {
        if (policy == null || !policy.isEnforcementEnabled()) {
            return false;
        }
        return !isExceptionActiveNow();
    }

    private boolean isExceptionActiveNow() {
        if (policy == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        boolean isActive = false;

        List<EffectiveWorkTimePolicy.ExceptionWindow> windows = policy.getExceptionWindows();
        if (windows != null && !windows.isEmpty()) {
            for (EffectiveWorkTimePolicy.ExceptionWindow window : windows) {
                if (window == null || window.getStartDateTime() == null || window.getEndDateTime() == null) {
                    continue;
                }
                if (now >= window.getStartDateTime() && now <= window.getEndDateTime()) {
                    isActive = true;
                    break;
                }
            }
        }

        if (!isActive) {
            Long exceptionStart = policy.getExceptionStartDateTime();
            Long exceptionEnd = policy.getExceptionEndDateTime();
            if (exceptionStart != null && exceptionEnd != null) {
                if (now >= exceptionStart && now <= exceptionEnd) {
                    isActive = true;
                }
            }
        }

        return isActive;
    }

    private int getServerDayMask(Calendar calendar) {
        int dow = calendar.get(Calendar.DAY_OF_WEEK);
        int serverDayIndex = 0;
        switch (dow) {
            case Calendar.MONDAY:
                serverDayIndex = 0;
                break;
            case Calendar.TUESDAY:
                serverDayIndex = 1;
                break;
            case Calendar.WEDNESDAY:
                serverDayIndex = 2;
                break;
            case Calendar.THURSDAY:
                serverDayIndex = 3;
                break;
            case Calendar.FRIDAY:
                serverDayIndex = 4;
                break;
            case Calendar.SATURDAY:
                serverDayIndex = 5;
                break;
            case Calendar.SUNDAY:
                serverDayIndex = 6;
                break;
        }
        return 1 << serverDayIndex;
    }

    private int parseTime(String time) {
        try {
            String[] parts = time.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Removes apps disallowed for the current policy window from Recents (Overview).
     * This applies both during and outside work window while enforcement is active.
     */
    public void removeRestrictedFromRecents(Context context) {
        if (policy == null || !isEnforcementActiveNow()) {
            return;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;

            for (ActivityManager.AppTask task : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null) continue;
                String pkg = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                        && info.topActivity != null) {
                    pkg = info.topActivity.getPackageName();
                } else if (info.baseIntent != null && info.baseIntent.getComponent() != null) {
                    pkg = info.baseIntent.getComponent().getPackageName();
                }
                if (pkg == null || pkg.equals(context.getPackageName())) continue;
                if (!isAppAllowed(pkg)) {
                    Log.d(TAG, "Removing restricted app from recents: " + pkg);
                    task.finishAndRemoveTask();
                }
            }

            // OEM fallback: try global recents list when available.
            try {
                java.util.List<ActivityManager.RecentTaskInfo> recentTasks =
                        am.getRecentTasks(200, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
                if (recentTasks != null) {
                    for (ActivityManager.RecentTaskInfo info : recentTasks) {
                        if (info == null) continue;
                        String pkg = null;
                        if (info.baseIntent != null && info.baseIntent.getComponent() != null) {
                            pkg = info.baseIntent.getComponent().getPackageName();
                        }
                        if (pkg == null || pkg.equals(context.getPackageName())) continue;
                        if (!isAppAllowed(pkg)) {
                            Log.d(TAG, "Removing restricted app from global recents: " + pkg);
                            int taskId = info.persistentId >= 0 ? info.persistentId : info.id;
                            if (!removeTaskByReflection(taskId)) {
                                if (am != null) {
                                    try {
                                        am.killBackgroundProcesses(pkg);
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Global recents cleanup unavailable on this device", e);
            }
        } catch (Exception e) {
            Log.w(TAG, "removeRestrictedFromRecents failed", e);
        }
    }

    private boolean removeTaskByReflection(int taskId) {
        if (taskId < 0) {
            return false;
        }

        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            java.lang.reflect.Method getService = atmClass.getMethod("getService");
            Object service = getService.invoke(null);
            if (service == null) {
                return false;
            }
            java.lang.reflect.Method removeTask = service.getClass().getMethod("removeTask", int.class);
            Object result = removeTask.invoke(service, taskId);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void enforceWorkTimeRestrictions(Context context) {
        boolean enforcementActive = policy != null && isEnforcementActiveNow();
        Log.i(TAG, "enforceWorkTimeRestrictions called, enforcementActive=" + enforcementActive);

        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        android.content.ComponentName adminComponent = com.brother.pharmach.mdm.launcher.util.LegacyUtils.getAdminComponentName(context);
        boolean isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        android.content.pm.PackageManager pm = context.getPackageManager();

        java.util.List<android.content.pm.ApplicationInfo> installedApps;
        try {
            // getInstalledApplications() marshals the full app list across a Binder transaction and
            // can throw a RuntimeException wrapping TransactionTooLargeException/DeadObjectException
            // when the buffer overflows (many apps) or system_server is transiently busy. Guard it
            // so a transient failure doesn't reach the global uncaught handler (System.exit).
            installedApps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            Log.e(TAG, "enforceWorkTimeRestrictions: getInstalledApplications failed, skipping this pass", t);
            return;
        }
        if (installedApps == null) {
            return;
        }
        java.util.ArrayList<String> pkgsToSuspend = new java.util.ArrayList<>();
        java.util.ArrayList<String> pkgsToUnsuspend = new java.util.ArrayList<>();

        for (android.content.pm.ApplicationInfo appInfo : installedApps) {
            try {
                String pkg = appInfo.packageName;
                if (pkg == null || pkg.equals(context.getPackageName())) {
                    continue;
                }

                if (isInfrastructurePackage(pkg)) {
                    continue;
                }

                // Only enforce on apps that are launchable (have an icon) to avoid breaking core system services
                if (!Utils.isAppLaunchable(context, pkg)) {
                    continue;
                }

                boolean allowed = !enforcementActive || isAppAllowed(pkg);

                if (allowed) {
                    pkgsToUnsuspend.add(pkg);
                } else {
                    pkgsToSuspend.add(pkg);
                    // Fallback for non-device-owner
                    if (am != null) {
                        try {
                            java.lang.reflect.Method forceStopMethod = am.getClass().getMethod("forceStopPackage", String.class);
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
            } catch (Throwable t) {
                // Never let a single problematic package abort the whole enforcement pass.
                Log.w(TAG, "enforceWorkTimeRestrictions: skipping package due to error", t);
            }
        }

        if (isDeviceOwner && dpm != null && adminComponent != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
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
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
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

        Log.i(TAG, "WorkTime enforcement completed, suspended=" + pkgsToSuspend.size() + ", unsuspended=" + pkgsToUnsuspend.size());

        // Clear any pre-existing restricted-app notifications from the shade
        // (new ones are blocked by WorkTimeNotificationListenerService.onNotificationPosted,
        // but notifications already in the shade before the worktime transition started
        // must be swept here).
        try {
            com.brother.pharmach.mdm.launcher.service.WorkTimeNotificationListenerService.cancelAllRestricted();
        } catch (Exception e) {
            Log.w(TAG, "cancelAllRestricted failed", e);
        }

        // Issue 2: Always clear restricted apps from the recents task stack after enforcement
        removeRestrictedFromRecents(context);
    }
}
