package com.smarttv.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Setup Activity — first-time configuration screen.
 * User enters server IP, port, and API key, or pairs automatically.
 * Tests connection before proceeding.
 */
public class SetupActivity extends Activity {

    // Views for Manual Config
    private EditText inputIp;
    private EditText inputPort;
    private EditText inputApiKey;
    private Button btnConnect;
    private Button btnTest;
    private ProgressBar progressBar;
    private TextView statusText;

    // Views for Auto Pairing
    private View layoutPairing;
    private View layoutManual;
    private ProgressBar pairingProgress;
    private TextView pairingStatus;
    private View layoutCodeDisplay;
    private TextView txtPairingCode;
    private TextView txtPairingInstructions;
    private Button btnSwitchToManual;

    private OkHttpClient httpClient;

    // Pairing fields
    private static final int DISCOVERY_PORT = 5000;
    private ExecutorService discoveryExecutor;
    private boolean isDiscoveryRunning = false;
    private String discoveredIp = null;
    private Handler pollHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;
    private String activePairingCode = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build();

        // Bind manual config views
        inputIp = findViewById(R.id.input_ip);
        inputPort = findViewById(R.id.input_port);
        inputApiKey = findViewById(R.id.input_api_key);
        btnConnect = findViewById(R.id.btn_connect);
        btnTest = findViewById(R.id.btn_test);
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);

        // Bind auto pairing views
        layoutPairing = findViewById(R.id.layout_pairing);
        layoutManual = findViewById(R.id.layout_manual);
        pairingProgress = findViewById(R.id.pairing_progress);
        pairingStatus = findViewById(R.id.pairing_status);
        layoutCodeDisplay = findViewById(R.id.layout_code_display);
        txtPairingCode = findViewById(R.id.txt_pairing_code);
        txtPairingInstructions = findViewById(R.id.txt_pairing_instructions);
        btnSwitchToManual = findViewById(R.id.btn_switch_to_manual);

        // If already configured, go straight to playback
        MediaPlayerApp app = MediaPlayerApp.getInstance();
        if (app.isConfigured()) {
            startPlayback();
            return;
        }

        // Pre-fill port
        inputPort.setText("5000");

        btnTest.setOnClickListener(v -> testConnection());
        btnConnect.setOnClickListener(v -> saveAndConnect());
        btnSwitchToManual.setOnClickListener(v -> switchToManualMode());

        // Start server discovery auto flow
        startServerDiscovery();
    }

    // ── Local Discovery Flow ──────────────────────────────────────────

    private void startServerDiscovery() {
        if (isDiscoveryRunning) return;
        isDiscoveryRunning = true;
        discoveredIp = null;
        activePairingCode = null;

        runOnUiThread(() -> {
            layoutPairing.setVisibility(View.VISIBLE);
            layoutManual.setVisibility(View.GONE);
            pairingStatus.setText("Поиск сервера в локальной сети...");
            pairingProgress.setVisibility(View.VISIBLE);
            layoutCodeDisplay.setVisibility(View.GONE);
            btnSwitchToManual.setText("Настроить вручную");
            btnSwitchToManual.setOnClickListener(v -> switchToManualMode());
        });

        String localIp = getLocalIpAddress();
        if (TextUtils.isEmpty(localIp)) {
            Log.w("SetupActivity", "Failed to get local IP");
            onDiscoveryFailed("Не удалось получить локальный IP устройства");
            return;
        }

        Log.d("SetupActivity", "Local IP: " + localIp);

        int lastDot = localIp.lastIndexOf('.');
        if (lastDot <= 0) {
            onDiscoveryFailed("Некорректный локальный IP");
            return;
        }

        final String subnet = localIp.substring(0, lastDot + 1);
        discoveryExecutor = Executors.newFixedThreadPool(40);

        for (int i = 1; i <= 254; i++) {
            final int host = i;
            discoveryExecutor.submit(() -> {
                if (!isDiscoveryRunning || discoveredIp != null) return;
                String targetIp = subnet + host;
                if (targetIp.equals(localIp)) return;
                checkServerAtIp(targetIp);
            });
        }

        new Thread(() -> {
            discoveryExecutor.shutdown();
            try {
                discoveryExecutor.awaitTermination(6, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (isDiscoveryRunning && discoveredIp == null) {
                runOnUiThread(() -> onDiscoveryFailed("Сервер не найден в локальной сети"));
            }
        }).start();
    }

    private void checkServerAtIp(String targetIp) {
        String url = "http://" + targetIp + ":" + DISCOVERY_PORT + "/api/tv/discover";
        Request request = new Request.Builder().url(url).get().build();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(800, TimeUnit.MILLISECONDS)
                .readTimeout(800, TimeUnit.MILLISECONDS)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String body = response.body().string();
                if (body.contains("smart_tv_media_player")) {
                    discoveredIp = targetIp;
                    isDiscoveryRunning = false;
                    if (discoveryExecutor != null) {
                        discoveryExecutor.shutdownNow();
                    }
                    Log.d("SetupActivity", "Discovered server at: " + targetIp);
                    requestPairingCode(targetIp, String.valueOf(DISCOVERY_PORT));
                }
            }
        } catch (IOException e) {
            // Ignore connection errors
        }
    }

    private void requestPairingCode(String ip, String port) {
        String url = "http://" + ip + ":" + port + "/api/tv/pair/request";
        okhttp3.RequestBody emptyBody = okhttp3.RequestBody.create(null, new byte[0]);
        Request request = new Request.Builder().url(url).post(emptyBody).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> onDiscoveryFailed("Ошибка запроса кода: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> onDiscoveryFailed("Сервер вернул ошибку при запросе кода"));
                    response.close();
                    return;
                }
                
                String body = response.body().string();
                response.close();
                
                try {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                    final String code = json.get("pairing_code").getAsString();
                    
                    runOnUiThread(() -> {
                        pairingProgress.setVisibility(View.GONE);
                        layoutCodeDisplay.setVisibility(View.VISIBLE);
                        
                        String formattedCode = code.substring(0, 3) + " " + code.substring(3);
                        txtPairingCode.setText(formattedCode);
                        
                        pairingStatus.setText("Сервер найден: " + ip);
                        txtPairingInstructions.setText("Панель управления: http://" + ip + ":" + port + "\nОткройте Настройки и введите код сопряжения.");
                        
                        activePairingCode = code;
                        startPolling(ip, port, code);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> onDiscoveryFailed("Ошибка разбора кода: " + e.getMessage()));
                }
            }
        });
    }

    private void startPolling(String ip, String port, String code) {
        if (pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
        
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isDiscoveryRunning && activePairingCode != null && activePairingCode.equals(code)) {
                    checkPairingStatus(ip, port, code);
                    pollHandler.postDelayed(this, 2000);
                }
            }
        };
        pollHandler.postDelayed(pollRunnable, 2000);
    }

    private void checkPairingStatus(String ip, String port, String code) {
        String url = "http://" + ip + ":" + port + "/api/tv/pair/poll?code=" + code;
        Request request = new Request.Builder().url(url).get().build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Ignore transient errors
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.code() == 404) {
                    response.close();
                    runOnUiThread(() -> {
                        stopDiscoveryAndPolling();
                        onDiscoveryFailed("Срок действия кода сопряжения истёк.");
                        btnSwitchToManual.setText("Повторить поиск");
                        btnSwitchToManual.setOnClickListener(v -> restartDiscoveryFlow());
                    });
                    return;
                }
                
                if (!response.isSuccessful()) {
                    response.close();
                    return;
                }
                
                String body = response.body().string();
                response.close();
                
                try {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                    String status = json.get("status").getAsString();
                    
                    if ("paired".equals(status)) {
                        final String apiKey = json.get("api_key").getAsString();
                        runOnUiThread(() -> {
                            stopDiscoveryAndPolling();
                            Toast.makeText(SetupActivity.this, "✅ ТВ успешно сопряжен!", Toast.LENGTH_SHORT).show();
                            MediaPlayerApp.getInstance().setServerConfig(ip, port, apiKey);
                            startPlayback();
                        });
                    }
                } catch (Exception e) {
                    // Ignore parsing error
                }
            }
        });
    }

    private void onDiscoveryFailed(String message) {
        pairingProgress.setVisibility(View.GONE);
        layoutCodeDisplay.setVisibility(View.GONE);
        pairingStatus.setText(message);
        btnSwitchToManual.setText("Настроить вручную");
        btnSwitchToManual.setOnClickListener(v -> switchToManualMode());
    }

    private void stopDiscoveryAndPolling() {
        isDiscoveryRunning = false;
        activePairingCode = null;
        if (discoveryExecutor != null) {
            discoveryExecutor.shutdownNow();
        }
        if (pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    private void switchToManualMode() {
        stopDiscoveryAndPolling();
        layoutPairing.setVisibility(View.GONE);
        layoutManual.setVisibility(View.VISIBLE);
    }

    private void restartDiscoveryFlow() {
        stopDiscoveryAndPolling();
        btnSwitchToManual.setText("Настроить вручную");
        btnSwitchToManual.setOnClickListener(v -> switchToManualMode());
        startServerDiscovery();
    }

    private String getLocalIpAddress() {
        try {
            java.util.List<java.net.NetworkInterface> interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces());
            for (java.net.NetworkInterface intf : interfaces) {
                java.util.List<java.net.InetAddress> addrs = java.util.Collections.list(intf.getInetAddresses());
                for (java.net.InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;
                        if (isIPv4) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }

    // ── Original Manual Connection Logic ──────────────────────────────

    private void testConnection() {
        String ip = inputIp.getText().toString().trim();
        String port = inputPort.getText().toString().trim();
        String apiKey = inputApiKey.getText().toString().trim();

        if (TextUtils.isEmpty(ip) || TextUtils.isEmpty(apiKey)) {
            statusText.setText("⚠ Заполните IP и API-ключ");
            statusText.setTextColor(0xFFFF6B8A);
            return;
        }

        if (TextUtils.isEmpty(port)) port = "5000";

        String url = "http://" + ip + ":" + port + "/api/tv/playlist?api_key=" + apiKey;

        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("Подключение...");
        statusText.setTextColor(0xFF8A87A0);

        Request request = new Request.Builder().url(url).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("❌ Ошибка подключения: " + e.getMessage());
                    statusText.setTextColor(0xFFFF6B8A);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final int code = response.code();
                response.close();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (code == 200) {
                        statusText.setText("✅ Подключение успешно!");
                        statusText.setTextColor(0xFF5CFC7C);
                        btnConnect.setEnabled(true);
                    } else if (code == 403) {
                        statusText.setText("❌ Неверный API-ключ");
                        statusText.setTextColor(0xFFFF6B8A);
                    } else {
                        statusText.setText("⚠ Сервер вернул код: " + code);
                        statusText.setTextColor(0xFFFCB95C);
                    }
                });
            }
        });
    }

    private void saveAndConnect() {
        String ip = inputIp.getText().toString().trim();
        String port = inputPort.getText().toString().trim();
        String apiKey = inputApiKey.getText().toString().trim();

        if (TextUtils.isEmpty(ip) || TextUtils.isEmpty(apiKey)) {
            Toast.makeText(this, "Заполните IP и API-ключ", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(port)) port = "5000";

        MediaPlayerApp.getInstance().setServerConfig(ip, port, apiKey);
        startPlayback();
    }

    private void startPlayback() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDiscoveryAndPolling();
    }
}
