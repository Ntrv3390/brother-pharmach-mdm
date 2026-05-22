package com.brother.pharmach.mdm.launcher.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.brother.pharmach.mdm.launcher.BuildConfig;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.helper.CryptoHelper;
import com.brother.pharmach.mdm.launcher.helper.SettingsHelper;
import com.brother.pharmach.mdm.launcher.json.PushMessage;
import com.brother.pharmach.mdm.launcher.json.PushResponse;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;
import com.brother.pharmach.mdm.launcher.util.RemoteLogger;
import com.brother.pharmach.mdm.launcher.worker.PushNotificationProcessor;

import org.eclipse.paho.android.service.MqttService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import retrofit2.Response;

public class PushLongPollingService extends Service {

    private boolean enabled = true;
    private boolean threadActive = false;
    private Thread pollingThread;
    // If we get an exception, we have to delay, otherwise there would be a looping
    private final long DELAY_AFTER_EXCEPTION_MS = 60000;
    // Delay between polling requests to avoid looping if the server would respond instantly
    private final long DELAY_AFTER_REQUEST_MS = 5000;
    private ServerService serverService;
    private ServerService secondaryServerService;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive( Context context, Intent intent ) {
            if (intent != null && intent.getAction() != null &&
                    intent.getAction().equals(Const.ACTION_SERVICE_STOP)) {
                enabled = false;
                stopSelf();
            }
        }
    };

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance( this ).unregisterReceiver(receiver);
        Log.i(Const.LOG_TAG, "PushLongPollingService: service stopped");
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        enabled = true;

        Log.i(Const.LOG_TAG, "PushLongPolling: service started. ");

        IntentFilter intentFilter = new IntentFilter(Const.ACTION_SERVICE_STOP);
        LocalBroadcastManager.getInstance( this ).registerReceiver( receiver, intentFilter );

        if (!threadActive) {
            pollingThread = new Thread(pollingRunnable);
            pollingThread.start();
        }

        return Service.START_STICKY;
    }

    private Runnable pollingRunnable = () -> {
        Context context = PushLongPollingService.this;
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        if (serverService == null) {
            serverService = ServerServiceKeeper.createServerService(settingsHelper.getBaseUrl(), Const.LONG_POLLING_READ_TIMEOUT);
        }
        if (secondaryServerService == null) {
            secondaryServerService = ServerServiceKeeper.createServerService(settingsHelper.getSecondaryBaseUrl(), Const.LONG_POLLING_READ_TIMEOUT);
        }

        // Calculate request signature
        String encodedDeviceId = settingsHelper.getDeviceId();
        try {
            encodedDeviceId = URLEncoder.encode(encodedDeviceId, "utf8");
        } catch (UnsupportedEncodingException e) {
        }
        String path = settingsHelper.getServerProject() + "/rest/notification/polling/" + encodedDeviceId;
        String signature = null;
        try {
            signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + path);
        } catch (Exception e) {
        }

        threadActive = true;
        while (enabled) {
            Response<PushResponse> response = null;

            RemoteLogger.log(context, Const.LOG_VERBOSE, "Push long polling inquiry");
            try {
                // This is the long operation
                response = serverService.
                        queryPushLongPolling(settingsHelper.getServerProject(), settingsHelper.getDeviceId(), signature).execute();
            } catch (Exception e) {
                RemoteLogger.log(context, Const.LOG_WARN, "Failed to query push notifications from "
                        + settingsHelper.getBaseUrl() + " : " + e.getMessage());
                e.printStackTrace();
            }

            try {
                if (response == null) {
                    response = secondaryServerService.
                            queryPushLongPolling(settingsHelper.getServerProject(), settingsHelper.getDeviceId(), signature).execute();
                }

                if ( response.isSuccessful() ) {
                    if ( Const.STATUS_OK.equals( response.body().getStatus() ) && response.body().getData() != null ) {
                        boolean configProcessed = false;
                        for (PushMessage message : response.body().getData()) {
                            // Keep all urgent messages; only collapse duplicate config updates.
                            if (PushMessage.TYPE_CONFIG_UPDATED.equals(message.getMessageType())) {
                                if (configProcessed) {
                                    continue;
                                }
                                configProcessed = true;
                            }
                            PushNotificationProcessor.process(message, context);
                        }
                    }
                } else if (response.code() >= 400 && response.code() < 500) {
                    // Response code 500 is fine (Timeout), so here we log only 4xx requests (403 Forbidden in particular)
                    RemoteLogger.log(context, Const.LOG_WARN, "Wrong response while querying push notifications from "
                            + settingsHelper.getSecondaryBaseUrl() + " : HTTP status " + response.code());
                    try {
                        // On exception, we need to wait to avoid looping
                        Thread.sleep(DELAY_AFTER_EXCEPTION_MS);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                }
                // Avoid looping by adding some pause
                Thread.sleep(DELAY_AFTER_REQUEST_MS);

            } catch ( Exception e ) {
                RemoteLogger.log(context, Const.LOG_WARN, "Failed to query push notifications from "
                        + settingsHelper.getSecondaryBaseUrl() + " : " + e.getMessage());
                e.printStackTrace();
                try {
                    // On exception, we need to wait to avoid looping
                    Thread.sleep(DELAY_AFTER_EXCEPTION_MS);
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        }
        threadActive = false;
    };
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
