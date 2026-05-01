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

package com.brother.pharmach.mdm.launcher.pro.service;

import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.util.WorkTimeManager;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fallback service that uses UsageStatsManager to poll the foreground app every 1.5 s
 * and blocks restricted apps during WorkTime. Used when the Accessibility service is
 * unavailable or not yet enabled.
 */
public class CheckForegroundApplicationService extends Service {

    private static final String TAG = "WorkTimeUsageStats";
    private static final long POLL_INTERVAL_MS = 1500;

    private ScheduledExecutorService executor;
    private String lastForegroundPkg = "";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::checkForeground, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        Log.d(TAG, "WorkTime UsageStats polling service started");
        return START_STICKY;
    }

    private void checkForeground() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return;

            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 5000, now);
            if (stats == null || stats.isEmpty()) return;

            UsageStats recentStat = null;
            for (UsageStats stat : stats) {
                if (recentStat == null || stat.getLastTimeUsed() > recentStat.getLastTimeUsed()) {
                    recentStat = stat;
                }
            }
            if (recentStat == null) return;

            String pkg = recentStat.getPackageName();
            if (pkg.equals(lastForegroundPkg)) return;
            lastForegroundPkg = pkg;
            if (pkg.equals(getPackageName())) return;

            if (!WorkTimeManager.getInstance().isAppAllowed(pkg)) {
                Log.d(TAG, "Blocking restricted app via UsageStats: " + pkg);
                Intent blockIntent = new Intent(Const.ACTION_HIDE_SCREEN);
                blockIntent.putExtra(Const.PACKAGE_NAME, pkg);
                LocalBroadcastManager.getInstance(this).sendBroadcast(blockIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking foreground app", e);
        }
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
