package com.smarttv.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.VideoView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Main fullscreen playback Activity.
 * Plays media items in sequence with time-based sync.
 * 
 * Sync approach: server provides server_time and cumulative_start per item.
 * TV calculates: elapsed = (System.currentTimeMillis()/1000 - serverTimeDelta) % totalCycleDuration
 * Then finds the item whose cumulative_start window contains that elapsed time.
 */
public class MainActivity extends Activity {

    private static final String TAG = "SmartTVPlayer";
    private static final long SYNC_INTERVAL_MS = 30_000; // Poll server every 30s
    private static final long TRANSITION_DURATION_MS = 800;

    private ImageView imageView;
    private VideoView videoView;
    private TextView statusOverlay;
    private View fadeOverlay;

    private Handler handler;
    private OkHttpClient httpClient;
    private Gson gson;

    private List<PlaylistItem> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private String currentVersion = "";
    private double serverTimeDelta = 0; // localTime - serverTime
    private double totalCycleDuration = 0;

    private Runnable advanceRunnable;
    private Runnable syncRunnable;
    private boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersive
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.image_view);
        videoView = findViewById(R.id.video_view);
        statusOverlay = findViewById(R.id.status_overlay);
        fadeOverlay = findViewById(R.id.fade_overlay);

        handler = new Handler(Looper.getMainLooper());
        gson = new Gson();
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

        hideSystemUI();
        showStatus("Загрузка плейлиста...");

        // Start periodic sync
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                fetchPlaylist();
                handler.postDelayed(this, SYNC_INTERVAL_MS);
            }
        };
        handler.post(syncRunnable);
    }

    // ── Playlist Fetching ──────────────────────────────────────────────

    private void fetchPlaylist() {
        MediaPlayerApp app = MediaPlayerApp.getInstance();
        String baseUrl = app.getServerUrl();
        String apiKey = app.getApiKey();

        if (baseUrl.isEmpty()) {
            showStatus("Сервер не настроен");
            return;
        }

        String url = baseUrl + "/api/tv/playlist?api_key=" + apiKey;

        Request request = new Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey)
            .get()
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch playlist", e);
                runOnUiThread(() -> {
                    if (playlist.isEmpty()) {
                        showStatus("Ошибка подключения к серверу\n" + e.getMessage());
                    }
                    // Keep playing existing playlist if we have one
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Server error: " + response.code());
                    response.close();
                    return;
                }

                String body = response.body().string();
                response.close();

                try {
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String version = json.has("version") ? json.get("version").getAsString() : "";
                    double serverTime = json.has("server_time") ? json.get("server_time").getAsDouble() : 0;
                    double cycleDuration = json.has("total_cycle_duration") ? json.get("total_cycle_duration").getAsDouble() : 0;

                    // Calculate time delta for sync
                    double localTime = System.currentTimeMillis() / 1000.0;
                    serverTimeDelta = localTime - serverTime;
                    totalCycleDuration = cycleDuration;

                    JsonArray items = json.getAsJsonArray("playlist");
                    List<PlaylistItem> newPlaylist = new ArrayList<>();

                    for (JsonElement element : items) {
                        JsonObject item = element.getAsJsonObject();
                        PlaylistItem pi = new PlaylistItem();
                        pi.id = item.get("id").getAsInt();
                        pi.filename = item.has("display_file") ? item.get("display_file").getAsString() : item.get("filename").getAsString();
                        pi.originalName = item.get("original_name").getAsString();
                        pi.displayType = item.get("display_type").getAsString();
                        pi.displayDuration = item.get("display_duration").getAsInt();
                        pi.priority = item.get("priority").getAsInt();
                        pi.cumulativeStart = item.has("cumulative_start") ? item.get("cumulative_start").getAsDouble() : 0;
                        newPlaylist.add(pi);
                    }

                    runOnUiThread(() -> {
                        if (newPlaylist.isEmpty()) {
                            showStatus("Плейлист пуст\nДобавьте медиа файлы через панель управления");
                            stopPlayback();
                            return;
                        }

                        boolean playlistChanged = !version.equals(currentVersion);
                        playlist = newPlaylist;
                        currentVersion = version;

                        if (!isPlaying || playlistChanged) {
                            hideStatus();
                            startSyncedPlayback();
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse playlist", e);
                }
            }
        });
    }

    // ── Playback Control ───────────────────────────────────────────────

    private void startSyncedPlayback() {
        if (playlist.isEmpty()) return;

        // Cancel any pending advance
        if (advanceRunnable != null) {
            handler.removeCallbacks(advanceRunnable);
        }

        isPlaying = true;

        // Calculate which item should be playing right now (for sync)
        int targetIndex = 0;
        long remainingMs = 0;

        if (totalCycleDuration > 0) {
            double localTime = System.currentTimeMillis() / 1000.0;
            double serverNow = localTime - serverTimeDelta;
            double elapsed = serverNow % totalCycleDuration;

            // Find the item that should be playing at this elapsed time
            for (int i = 0; i < playlist.size(); i++) {
                PlaylistItem item = playlist.get(i);
                double itemEnd = item.cumulativeStart + item.displayDuration;
                if (elapsed >= item.cumulativeStart && elapsed < itemEnd) {
                    targetIndex = i;
                    // How many ms remain for this item
                    remainingMs = (long) ((itemEnd - elapsed) * 1000);
                    break;
                }
            }
        } else {
            targetIndex = 0;
            remainingMs = playlist.get(0).displayDuration * 1000L;
        }

        showItem(targetIndex, remainingMs);
    }

    private void showItem(int index, long durationMs) {
        if (index < 0 || index >= playlist.size()) {
            index = 0;
        }

        currentIndex = index;
        PlaylistItem item = playlist.get(currentIndex);
        MediaPlayerApp app = MediaPlayerApp.getInstance();
        String fileUrl = app.getServerUrl() + "/api/tv/file/" + item.filename + "?api_key=" + app.getApiKey();

        long effectiveDuration = durationMs > 0 ? durationMs : item.displayDuration * 1000L;

        // Fade transition
        fadeIn(() -> {
            if ("video".equals(item.displayType)) {
                showVideo(fileUrl, effectiveDuration);
            } else {
                showImage(fileUrl, effectiveDuration);
            }
        });
    }

    private void showImage(String url, long durationMs) {
        videoView.setVisibility(View.GONE);
        videoView.stopPlayback();
        imageView.setVisibility(View.VISIBLE);

        Glide.with(this)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(imageView);

        scheduleNext(durationMs);
    }

    private void showVideo(String url, long durationMs) {
        imageView.setVisibility(View.GONE);
        videoView.setVisibility(View.VISIBLE);

        try {
            Uri uri = Uri.parse(url);
            videoView.setVideoURI(uri);

            videoView.setOnPreparedListener(mp -> {
                mp.setLooping(false);
                mp.start();
            });

            videoView.setOnCompletionListener(mp -> {
                // Video finished — advance to next
                advanceToNext();
            });

            videoView.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Video error: what=" + what + " extra=" + extra);
                // On error, skip to next
                advanceToNext();
                return true;
            });

            videoView.start();
        } catch (Exception e) {
            Log.e(TAG, "Error playing video", e);
            advanceToNext();
        }

        // For videos, use video duration but also set a max timeout
        // The OnCompletionListener will trigger next item when video ends
        scheduleNext(Math.max(durationMs, 300_000)); // 5 min max for videos
    }

    private void scheduleNext(long delayMs) {
        if (advanceRunnable != null) {
            handler.removeCallbacks(advanceRunnable);
        }

        advanceRunnable = this::advanceToNext;
        handler.postDelayed(advanceRunnable, delayMs);
    }

    private void advanceToNext() {
        if (advanceRunnable != null) {
            handler.removeCallbacks(advanceRunnable);
        }

        if (playlist.isEmpty()) return;

        int nextIndex = (currentIndex + 1) % playlist.size();
        long duration = playlist.get(nextIndex).displayDuration * 1000L;
        showItem(nextIndex, duration);
    }

    private void stopPlayback() {
        isPlaying = false;
        if (advanceRunnable != null) {
            handler.removeCallbacks(advanceRunnable);
        }
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE);
        imageView.setVisibility(View.GONE);
    }

    // ── Transitions ─────────────────────────────────────────────────────

    private void fadeIn(Runnable onComplete) {
        fadeOverlay.setVisibility(View.VISIBLE);
        AlphaAnimation fadeInAnim = new AlphaAnimation(0f, 1f);
        fadeInAnim.setDuration(TRANSITION_DURATION_MS / 2);
        fadeInAnim.setFillAfter(true);
        fadeInAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) {}
            @Override public void onAnimationRepeat(Animation animation) {}
            @Override
            public void onAnimationEnd(Animation animation) {
                onComplete.run();
                // Fade out
                AlphaAnimation fadeOutAnim = new AlphaAnimation(1f, 0f);
                fadeOutAnim.setDuration(TRANSITION_DURATION_MS / 2);
                fadeOutAnim.setFillAfter(true);
                fadeOutAnim.setAnimationListener(new Animation.AnimationListener() {
                    @Override public void onAnimationStart(Animation animation) {}
                    @Override public void onAnimationRepeat(Animation animation) {}
                    @Override
                    public void onAnimationEnd(Animation animation) {
                        fadeOverlay.setVisibility(View.GONE);
                    }
                });
                fadeOverlay.startAnimation(fadeOutAnim);
            }
        });
        fadeOverlay.startAnimation(fadeInAnim);
    }

    // ── UI Helpers ──────────────────────────────────────────────────────

    private void showStatus(String message) {
        statusOverlay.setText(message);
        statusOverlay.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusOverlay.setVisibility(View.GONE);
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    // ── Key Events (TV Remote) ──────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                // Long press back to go to settings
                if (event.isLongPress()) {
                    goToSetup();
                    return true;
                }
                return true; // Block single back press

            case KeyEvent.KEYCODE_MENU:
                goToSetup();
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                advanceToNext();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void goToSetup() {
        stopPlayback();
        MediaPlayerApp.getInstance().getPrefs().edit()
            .putBoolean("configured", false).apply();
        Intent intent = new Intent(this, SetupActivity.class);
        startActivity(intent);
        finish();
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (!isPlaying && !playlist.isEmpty()) {
            startSyncedPlayback();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (advanceRunnable != null) {
            handler.removeCallbacks(advanceRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(syncRunnable);
        if (advanceRunnable != null) {
            handler.removeCallbacks(advanceRunnable);
        }
        videoView.stopPlayback();
    }

    // ── Playlist Item Model ────────────────────────────────────────────

    static class PlaylistItem {
        int id;
        String filename;
        String originalName;
        String displayType; // "image" or "video"
        int displayDuration; // seconds
        int priority;
        double cumulativeStart; // seconds from cycle start (for sync)
    }
}
