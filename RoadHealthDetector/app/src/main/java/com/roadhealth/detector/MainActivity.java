package com.roadhealth.detector;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "RoadHealth";
    private static final int CAM_PERM = 100;
    private static final int FRAME_GAP_MS = 1000; // send 1 frame per second

    // Views
    private PreviewView previewView;
    private ImageView   ivAnnotated;   // shows annotated frame from server
    private EditText    etIp;
    private Button      btnConnect, btnDetect;
    private TextView    tvStatus, tvLabel, tvDetail, tvFps;
    private ProgressBar progressBar;
    private View        dotView;

    // State
    private boolean     connected  = false;
    private boolean     detecting  = false;
    private String      serverUrl  = "";
    private long        lastSentMs = 0;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    // Frame counter for FPS display
    private int  fpsCount = 0;
    private long fpsTime  = 0;

    // Camera
    private ExecutorService       camExecutor;
    private ProcessCameraProvider camProvider;

    // Network
    private OkHttpClient http;
    private final Gson   gson = new Gson();
    private final Handler ui  = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.cameraPreview);
        ivAnnotated = findViewById(R.id.ivAnnotated);
        etIp        = findViewById(R.id.etServerIp);
        btnConnect  = findViewById(R.id.btnConnect);
        btnDetect   = findViewById(R.id.btnStartStop);
        tvStatus    = findViewById(R.id.tvStatus);
        tvLabel     = findViewById(R.id.tvLabel);
        tvDetail    = findViewById(R.id.tvConfidence);
        progressBar = findViewById(R.id.progressConfidence);
        dotView     = findViewById(R.id.statusDot);
        tvFps       = findViewById(R.id.tvFps);

        btnDetect.setEnabled(false);
        btnConnect.setOnClickListener(v -> connectServer());
        btnDetect.setOnClickListener(v -> toggleDetect());

        http = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();

        camExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAM_PERM);
        }
    }

    // ── Connect to Python server ──────────────────────────────────────────────

    private void connectServer() {
        String ip = etIp.getText().toString().trim();
        if (ip.isEmpty()) {
            toast("Enter your PC IP like: 10.14.233.242:5000");
            return;
        }
        serverUrl = ip.startsWith("http") ? ip : "http://" + ip;
        dot("#FFC107");
        tvStatus.setText("Connecting...");
        btnConnect.setEnabled(false);

        Request req = new Request.Builder().url(serverUrl + "/health").get().build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {
                ui.post(() -> {
                    dot("#F44336");
                    tvStatus.setText("Cannot reach server");
                    btnConnect.setEnabled(true);
                    toast("Failed: " + e.getMessage());
                });
            }
            @Override public void onResponse(@NonNull Call c, @NonNull Response r) {
                boolean ok = r.isSuccessful();
                r.close();
                ui.post(() -> {
                    if (ok) {
                        connected = true;
                        dot("#4CAF50");
                        tvStatus.setText("Connected to server");
                        btnDetect.setEnabled(true);
                        toast("Connected! Tap START DETECTION");
                    } else {
                        dot("#F44336");
                        tvStatus.setText("Server error " + r.code());
                    }
                    btnConnect.setEnabled(true);
                });
            }
        });
    }

    // ── Toggle detection ──────────────────────────────────────────────────────

    private void toggleDetect() {
        if (!connected) { toast("Connect to server first!"); return; }
        detecting = !detecting;

        if (detecting) {
            btnDetect.setText("STOP DETECTION");
            btnDetect.setBackgroundTintList(colorList("#F44336"));
            tvLabel.setText("Scanning...");
            tvLabel.setTextColor(Color.WHITE);
            fpsCount = 0;
            fpsTime  = System.currentTimeMillis();
            // Hide raw camera, will show annotated frames
        } else {
            btnDetect.setText("START DETECTION");
            btnDetect.setBackgroundTintList(colorList("#4CAF50"));
            tvLabel.setText("Stopped");
            tvLabel.setTextColor(Color.WHITE);
            tvDetail.setText("");
            tvFps.setText("");
            progressBar.setProgress(0);
            // Hide annotated overlay, show live camera again
            ivAnnotated.setVisibility(View.GONE);
            previewView.setVisibility(View.VISIBLE);
        }
    }

    // ── Camera setup ──────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                camProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(camExecutor, this::onFrame);

                camProvider.unbindAll();
                camProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis);
            } catch (Exception e) {
                Log.e(TAG, "Camera start failed: " + e.getMessage());
                ui.post(() -> toast("Camera error: " + e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Capture & send frame ──────────────────────────────────────────────────

    private void onFrame(@NonNull ImageProxy img) {
        long now = System.currentTimeMillis();
        if (!detecting || !connected || busy.get() || (now - lastSentMs < FRAME_GAP_MS)) {
            img.close();
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            img.close();
            return;
        }
        lastSentMs = now;

        String b64 = toBase64(img);
        img.close();

        if (b64 == null) { busy.set(false); return; }

        sendFrame(b64);
    }

    private String toBase64(ImageProxy img) {
        try {
            java.nio.ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] raw = new byte[buf.remaining()];
            buf.get(raw);

            Bitmap bmp = Bitmap.createBitmap(
                    img.getWidth(), img.getHeight(), Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(raw));

            // Scale down to keep payload manageable
            Bitmap small = Bitmap.createScaledBitmap(bmp, 640, 480, true);
            bmp.recycle();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 80, out);
            small.recycle();

            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "toBase64 error: " + e.getMessage());
            return null;
        }
    }

    // ── HTTP POST frame to server ─────────────────────────────────────────────

    private void sendFrame(String b64) {
        JsonObject body = new JsonObject();
        body.addProperty("frame", b64);

        RequestBody rb = RequestBody.create(
                gson.toJson(body),
                MediaType.parse("application/json; charset=utf-8"));

        Request req = new Request.Builder()
                .url(serverUrl + "/detect")
                .post(rb)
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {
                busy.set(false);
                ui.post(() -> {
                    dot("#F44336");
                    tvStatus.setText("Send failed - check WiFi");
                });
            }
            @Override public void onResponse(@NonNull Call c, @NonNull Response r) {
                busy.set(false);
                try {
                    if (r.isSuccessful() && r.body() != null) {
                        showResult(r.body().string());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Response error: " + e.getMessage());
                } finally {
                    r.close();
                }
            }
        });
    }

    // ── Parse response: show annotated image + stats ──────────────────────────

    private void showResult(String json) {
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (!obj.has("success") || !obj.get("success").getAsBoolean()) {
                String err = obj.has("error") ? obj.get("error").getAsString() : "Error";
                ui.post(() -> tvLabel.setText("Server error: " + err));
                return;
            }

            // ── Decode annotated image from server ────────────────────────────
            Bitmap annotatedBitmap = null;
            if (obj.has("annotated_frame")) {
                String imgB64 = obj.get("annotated_frame").getAsString();
                byte[] imgBytes = Base64.decode(imgB64, Base64.NO_WRAP);
                annotatedBitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
            }

            // ── Parse stats ───────────────────────────────────────────────────
            JsonObject res   = obj.getAsJsonObject("result");
            String  label    = res.has("label")             ? res.get("label").getAsString()          : "Unknown";
            int     dmgPct   = res.has("damage_percentage") ? res.get("damage_percentage").getAsInt() : 0;
            int     dets     = res.has("detections")        ? res.get("detections").getAsInt()        : 0;
            boolean bad      = res.has("damaged")           && res.get("damaged").getAsBoolean();
            String  color    = bad ? "#F44336" : "#4CAF50";

            // FPS
            fpsCount++;
            long elapsed = System.currentTimeMillis() - fpsTime;
            String fpsText = "";
            if (elapsed >= 2000) {
                fpsText  = String.format("%.1f FPS", fpsCount / (elapsed / 1000f));
                fpsCount = 0;
                fpsTime  = System.currentTimeMillis();
            }

            final Bitmap finalBitmap = annotatedBitmap;
            final String finalFps    = fpsText;

            ui.post(() -> {
                // Show annotated frame on top of camera
                if (finalBitmap != null) {
                    ivAnnotated.setImageBitmap(finalBitmap);
                    ivAnnotated.setVisibility(View.VISIBLE);
                    previewView.setVisibility(View.GONE); // hide raw feed
                }

                // Update stats
                tvLabel.setText(label);
                tvLabel.setTextColor(Color.parseColor(color));
                progressBar.setProgress(dmgPct);
                progressBar.setProgressTintList(colorList(color));
                tvDetail.setText("Potholes detected: " + dets + "   |   Damage: " + dmgPct + "%");
                dot("#4CAF50");
                tvStatus.setText("Live detection running");
                if (!finalFps.isEmpty()) tvFps.setText(finalFps);
            });

        } catch (Exception e) {
            Log.e(TAG, "showResult error: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void dot(String hex) {
        dotView.setBackgroundTintList(colorList(hex));
    }

    private android.content.res.ColorStateList colorList(String hex) {
        return android.content.res.ColorStateList.valueOf(Color.parseColor(hex));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == CAM_PERM && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            toast("Camera permission needed!");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (camExecutor != null) camExecutor.shutdown();
    }
}
