package com.brother.pharmach.mdm.launcher.helper;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;

import com.brother.pharmach.mdm.launcher.BuildConfig;
import com.brother.pharmach.mdm.launcher.Const;
import com.brother.pharmach.mdm.launcher.json.ServerConfigResponse;
import com.brother.pharmach.mdm.launcher.server.ServerService;
import com.brother.pharmach.mdm.launcher.server.ServerServiceKeeper;
import com.brother.pharmach.mdm.launcher.server.ServerUrl;

import java.net.MalformedURLException;

import retrofit2.Response;

public class MigrationHelper {

    public interface CompletionHandler {
        public void onSuccess();
        public void onError(String cause);
    }

    private ServerUrl serverUrl;

    public MigrationHelper(String url) throws MalformedURLException {
        serverUrl = new ServerUrl(url);
    }

    public String getBaseUrl() {
        return serverUrl.baseUrl;
    }

    public String getServerProject() {
        return serverUrl.serverProject;
    }

    public boolean needMigrating(Context context) {
        return !serverUrl.baseUrl.equalsIgnoreCase(SettingsHelper.getInstance(context).getBaseUrl()) ||
               !serverUrl.serverProject.equalsIgnoreCase(SettingsHelper.getInstance(context).getServerProject());
    }

    // Before migration, test that URL is working well
    public void tryNewServer(final Context context, final CompletionHandler completionHandler) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                final ServerService newServer;
                try {
                    newServer = ServerServiceKeeper.createServerService(serverUrl.baseUrl);
                } catch (Exception e) {
                    return e.getMessage();
                }

                Response<ServerConfigResponse> response = null;
                SettingsHelper settingsHelper = SettingsHelper.getInstance(context);

                String deviceId = settingsHelper.getDeviceId();
                String signature = "";
                try {
                    signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + deviceId);
                } catch (Exception e) {
                }

                try {
                    response = newServer.getServerConfig(serverUrl.serverProject, deviceId, signature, Build.CPU_ABI).execute();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (response == null) {
                    return "Network error";
                }

                if (!response.isSuccessful()) {
                    return "Bad server response: " + response.message();
                }

                if (!Const.STATUS_OK.equals(response.body().getStatus())) {
                    return "Bad server status: " + response.body().getStatus();
                }

                if (response.body().getData() == null) {
                    return "Failed to parse server response";
                }

                // We get something JSON-like, let's conclude the request has been successful!
                return null;
            }

            @Override
            protected void onPostExecute(String error) {
                if (error == null) {
                    completionHandler.onSuccess();
                } else {
                    completionHandler.onError(error);
                }
            }
        }.execute();
    }
}
