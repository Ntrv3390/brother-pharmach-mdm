package com.brother.pharmach.mdm.launcher.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.work.BackoffPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.brother.pharmach.mdm.launcher.worker.SendDeviceInfoWorker;

import java.util.concurrent.TimeUnit;

/**
 * Receiver for package install/uninstall events to trigger immediate server sync.
 */
public class PackageChangeReceiver extends BroadcastReceiver {

    private static final String TAG = "PackageChangeReceiver";
    private static final String WORK_NAME = "manual_device_info_sync";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "Received package change intent: " + action);

        if (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
            Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
            Intent.ACTION_PACKAGE_REPLACED.equals(action)) {

            // Trigger an immediate sync with a small debounce delay
            triggerImmediateSync(context);
        }
    }

    private void triggerImmediateSync(Context context) {
        // We use WorkManager to ensure the task runs even if the receiver process is killed
        // and to handle retries if the network is unavailable.
        OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(SendDeviceInfoWorker.class)
                .setInitialDelay(5, TimeUnit.SECONDS) // Debounce for multiple events
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE, // Restart timer if another event occurs
                syncRequest
        );

        Log.i(TAG, "Scheduled immediate device info sync due to package change");
    }
}
