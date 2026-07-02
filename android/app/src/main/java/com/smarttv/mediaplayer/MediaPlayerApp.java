package com.smarttv.mediaplayer;

import android.app.Application;
import android.content.SharedPreferences;

/**
 * Application class for Smart TV Media Player.
 * Manages global state and preferences.
 */
public class MediaPlayerApp extends Application {

    private static final String PREFS_NAME = "SmartTVMediaPlayer";
    private static MediaPlayerApp instance;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    public static MediaPlayerApp getInstance() {
        return instance;
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }

    // ── Server Configuration ──────────────────────────────────────────

    public String getServerUrl() {
        String ip = prefs.getString("server_ip", "");
        String port = prefs.getString("server_port", "5000");
        if (ip.isEmpty()) return "";
        return "http://" + ip + ":" + port;
    }

    public void setServerConfig(String ip, String port, String apiKey) {
        prefs.edit()
            .putString("server_ip", ip)
            .putString("server_port", port)
            .putString("api_key", apiKey)
            .putBoolean("configured", true)
            .apply();
    }

    public String getApiKey() {
        return prefs.getString("api_key", "");
    }

    public boolean isConfigured() {
        return prefs.getBoolean("configured", false);
    }
}
