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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.db.DatabaseHelper;
import com.brother.pharmach.mdm.launcher.db.LogConfigTable;
import com.brother.pharmach.mdm.launcher.db.LogTable;
import com.brother.pharmach.mdm.launcher.json.RemoteLogConfig;
import com.brother.pharmach.mdm.launcher.json.RemoteLogItem;
import com.brother.pharmach.mdm.launcher.worker.RemoteLogWorker;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remote logging engine which uses SQLite for configuration
 * and storing unsent logs
 */
public class RemoteLogger {
    public static long lastLogRemoval = 0;

    // Single background thread for all log persistence. RemoteLogger.log() is called from many
    // main-thread paths (broadcast receivers, periodic ticks); doing the SQLite read/write there
    // blocked the UI thread under DB contention. A single-thread executor keeps insertion order
    // while moving the work off the main thread.
    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor();

    public static void updateConfig(Context context, List<RemoteLogConfig> rules) {
        LogConfigTable.replaceAll(DatabaseHelper.instance(context).getWritableDatabase(), rules);
    }

    public static void log(Context context, int level, String message) {
        switch (level) {
            case Const.LOG_VERBOSE:
                Log.v(Const.LOG_TAG, message);
                break;
            case Const.LOG_DEBUG:
                Log.d(Const.LOG_TAG, message);
                break;
            case Const.LOG_INFO:
                Log.i(Const.LOG_TAG, message);
                break;
            case Const.LOG_WARN:
                Log.w(Const.LOG_TAG, message);
                break;
            case Const.LOG_ERROR:
                Log.e(Const.LOG_TAG, message);
                break;
        }

        RemoteLogItem item = new RemoteLogItem();
        item.setTimestamp(System.currentTimeMillis());
        item.setLogLevel(level);
        item.setPackageId(context.getPackageName());
        item.setMessage(message);
        postLog(context, item);
    }

    public static void postLog(Context context, RemoteLogItem item) {
        // Persist on a background thread — see logExecutor. This method is invoked from many
        // periodic and broadcast paths that share a single SQLite database; doing the read/write
        // inline blocked the caller's thread (often the main thread) under DB contention.
        final Context appContext = context.getApplicationContext();
        try {
            logExecutor.execute(() -> persistLog(appContext, item));
        } catch (Exception e) {
            // Executor rejected (shutdown) — fall back to inline persistence so nothing is lost.
            persistLog(appContext, item);
        }
    }

    private static void persistLog(Context context, RemoteLogItem item) {
        // A transient SQLiteException (lock contention, disk-full, "database disk image is
        // malformed") must never propagate: on the main-thread callers it would reach the global
        // uncaught-exception handler, which calls System.exit(0). Swallow DB faults so logging can
        // never crash the process.
        try {
            DatabaseHelper dbHelper = DatabaseHelper.instance(context);
            if (dbHelper == null) {
                return;
            }
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            if (LogConfigTable.match(db, item)) {
                db = dbHelper.getWritableDatabase();
                LogTable.insert(db, item);
                sendLogsToServer(context);
            }

            // Remove old logs once per hour
            long now = System.currentTimeMillis();
            if (now > lastLogRemoval + 3600000L) {
                db = dbHelper.getWritableDatabase();
                LogTable.deleteOldItems(db);
                lastLogRemoval = now;
            }
        } catch (Throwable t) {
            Log.w(Const.LOG_TAG, "postLog: failed to persist remote log item", t);
        }
    }

    public static void resetState() {
        RemoteLogWorker.resetState();
    }

    public static void sendLogsToServer(Context context) {
        RemoteLogWorker.scheduleUpload(context);
    }
}
