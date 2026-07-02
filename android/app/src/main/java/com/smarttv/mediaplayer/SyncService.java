package com.smarttv.mediaplayer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Background sync service.
 * Sends heartbeat to server for device monitoring.
 */
public class SyncService extends Service {

    private static final String TAG = "SyncService";
    private OkHttpClient httpClient;

    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sendHeartbeat();
        return START_STICKY;
    }

    private void sendHeartbeat() {
        MediaPlayerApp app = MediaPlayerApp.getInstance();
        String baseUrl = app.getServerUrl();
        String apiKey = app.getApiKey();

        if (baseUrl.isEmpty()) return;

        String url = baseUrl + "/api/tv/heartbeat";
        String json = "{\"device_name\": \"" + android.os.Build.MODEL + "\"}";

        Request request = new Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey)
            .post(RequestBody.create(json, MediaType.parse("application/json")))
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Heartbeat failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
