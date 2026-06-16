package com.brother.pharmach.mdm.launcher.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.R;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;
import com.brother.pharmach.mdm.launcher.util.Utils;
import com.brother.pharmach.mdm.launcher.worker.LocationWorker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LocationForegroundService extends Service {

    public static final String ACTION_URGENT_GPS = "com.brother.pharmach.mdm.launcher.ACTION_URGENT_GPS";

    private static final String CHANNEL_ID = "location_service_channel";
    private static final int NOTIFICATION_ID = 1002;

    // Periodic scheduler — daemon thread, runs every 15 min like the old WorkManager job.
    private ScheduledExecutorService periodicScheduler;

    // Urgent executor — non-daemon, MAX_PRIORITY so the OS treats it as the highest
    // priority user-space thread in the process when "Get Latest Location" fires.
    private ExecutorService urgentExecutor;

    // Track in-flight urgent task so a second push cancels the previous one.
    private volatile Future<?> currentUrgentTask;

    // ---------------------------------------------------------------------------
    // Static helpers — call these instead of managing intents manually
    // ---------------------------------------------------------------------------

    public static void start(Context context) {
        Intent intent = new Intent(context, LocationForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void triggerUrgent(Context context) {
        Intent intent = new Intent(context, LocationForegroundService.class);
        intent.setAction(ACTION_URGENT_GPS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    // ---------------------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();

        urgentExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "urgent-gps-fg");
            t.setPriority(Thread.MAX_PRIORITY);
            t.setDaemon(false);
            return t;
        });

        periodicScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "periodic-gps-fg");
            t.setDaemon(true);
            return t;
        });

        startForegroundWithNotification();

        // First capture fires immediately (delay=0), then every 15 minutes.
        periodicScheduler.scheduleAtFixedRate(
                this::runPeriodicCapture,
                0,
                LocationWorker.FIRE_PERIOD_MINS,
                TimeUnit.MINUTES);

        RemoteLogger.log(this, Const.LOG_INFO, "LocationForegroundService started — GPS is warm");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_URGENT_GPS.equals(intent.getAction())) {
            handleUrgentRequest();
        }
        // START_STICKY: OS restarts the service automatically if killed.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        periodicScheduler.shutdownNow();
        urgentExecutor.shutdownNow();
        RemoteLogger.log(this, Const.LOG_INFO, "LocationForegroundService stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------------------
    // Internal work
    // ---------------------------------------------------------------------------

    private void handleUrgentRequest() {
        RemoteLogger.log(this, Const.LOG_INFO,
                "LocationForegroundService: urgent GPS request — running at MAX_PRIORITY");

        // Cancel any already-queued urgent task so the latest request always wins.
        Future<?> prev = currentUrgentTask;
        if (prev != null && !prev.isDone()) {
            prev.cancel(true);
        }

        final Context appContext = getApplicationContext();
        currentUrgentTask = urgentExecutor.submit(() -> {
            // Redundant but explicit: the thread factory already sets MAX_PRIORITY,
            // and re-setting it here ensures it survives any executor internals.
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            try {
                LocationWorker.captureAndUpload(appContext, true);
            } catch (Exception e) {
                RemoteLogger.log(appContext, Const.LOG_WARN,
                        "LocationForegroundService: urgent capture failed: " + e.getMessage());
            }
        });
    }

    private void runPeriodicCapture() {
        try {
            LocationWorker.captureAndUpload(getApplicationContext(), false);
        } catch (Exception e) {
            RemoteLogger.log(getApplicationContext(), Const.LOG_WARN,
                    "LocationForegroundService: periodic capture failed: " + e.getMessage());
        }
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Location Service",
                    // IMPORTANCE_MIN: no sound, no status-bar icon, collapsed by default.
                    // Disabling app notifications from Settings suppresses it entirely.
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.white_app_name))
                .setContentText(getString(R.string.location_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        Utils.startStableForegroundService(this, NOTIFICATION_ID, notification);
    }
}
