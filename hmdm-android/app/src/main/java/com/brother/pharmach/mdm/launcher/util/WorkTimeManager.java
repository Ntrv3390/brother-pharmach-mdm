package com.brother.pharmach.mdm.launcher.util;

import android.util.Log;
import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fasterxml.jackson.databind.JsonNode;
import android.content.Context;
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

    private static WorkTimeManager instance;
    private volatile EffectiveWorkTimePolicy policy;
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

    public void updatePolicy(Context context) {
        updatePolicy(context, false);
    }

    public void updatePolicy(Context context, boolean forceRefresh) {
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
                        this.policy = wrapper.getPolicy();
                        parsedFromConfig = true;
                        notifyPolicyUpdated(context);
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

    public boolean isAppAllowed(String packageName) {
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

    private boolean isPackageAllowed(String packageName, List<String> list) {
        if (list == null) return false;
        if (list.contains("*")) return true;
        return list.contains(packageName);
    }

    private boolean isCurrentTimeWorkTime() {
        if (policy.getStartTime() == null || policy.getEndTime() == null) {
            return false;
        }

        Calendar now = Calendar.getInstance();

        int currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startMinute = parseTime(policy.getStartTime());
        int endMinute = parseTime(policy.getEndTime());

        boolean withinWork;
        if (startMinute <= endMinute) {
            withinWork = currentMinute >= startMinute && currentMinute < endMinute;
        } else {
            withinWork = currentMinute >= startMinute || currentMinute < endMinute;
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

        Long exceptionStart = policy.getExceptionStartDateTime();
        Long exceptionEnd = policy.getExceptionEndDateTime();
        if (exceptionStart == null || exceptionEnd == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        return now >= exceptionStart && now <= exceptionEnd;
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
}
