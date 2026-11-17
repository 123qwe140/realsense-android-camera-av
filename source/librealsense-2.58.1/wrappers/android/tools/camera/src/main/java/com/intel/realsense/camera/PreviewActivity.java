package com.intel.realsense.camera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.intel.realsense.librealsense.Config;
import com.intel.realsense.librealsense.Extension;
import com.intel.realsense.librealsense.Frame;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.VideoFrame;
import com.intel.realsense.librealsense.VideoStreamProfile;

import java.util.concurrent.atomic.AtomicBoolean;

public class PreviewActivity extends AppCompatActivity {
    private static final int MAX_PREVIEW_ATTEMPTS = 3;
    private static final long PREVIEW_RETRY_DELAY_MS = 1200;
    private static final long PREVIEW_STALE_AFTER_MS = 6000;
    private final Handler previewHealthHandler = new Handler(Looper.getMainLooper());
    private volatile RgbPreviewSurfaceView preview;
    private volatile Streamer streamer;
    private HandlerThread previewThread;
    private volatile Handler previewHandler;
    private volatile boolean resumed;
    private volatile long previewGeneration;
    private int previewStartAttempts;
    private AtomicBoolean firstFrameSeen = new AtomicBoolean();
    private MaterialButton record;
    private boolean hasFrames;
    private boolean canRecord;
    private String detail = "准备相机画面…";
    private String depthText = "深度检测中";
    private String problem;
    private StreamingStats statistics;
    private TextView statisticsText;
    private BottomSheetDialog settingsSheet;
    private BottomSheetDialog statisticsSheet;
    private volatile boolean statisticsVisible;
    private FwLogsThread fwLogs;
    private long nextStatsNs;
    private volatile boolean previewMarkedStale;
    private final Runnable previewHealthCheck = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            Streamer current = streamer;
            long lastFrame = current == null ? 0 : current.getLastFramesetAtMs();
            long now = SystemClock.elapsedRealtime();
            if (hasFrames && lastFrame > 0 && now - lastFrame > PREVIEW_STALE_AFTER_MS
                    && !previewMarkedStale) {
                previewMarkedStale = true;
                problem = "相机图像已停止更新超过 6 秒";
                canRecord = false;
                detail = "USB 图像流中断，请返回首页后重新连接相机";
                Log.e("RS preview", "frame watchdog: no frameset for " + (now - lastFrame) + " ms");
                updateReadiness();
            }
            previewHealthHandler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bindLayout();
    }

    private void bindLayout() {
        preview = new RgbPreviewSurfaceView(this);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        CameraUi.install(this, CameraUi.capture(this, preview, false, landscape));
        findViewById(R.id.uiBack).setOnClickListener(view -> finish());
        findViewById(R.id.libraryButton).setOnClickListener(view -> startActivity(new Intent(this, RecordingLibraryActivity.class)));
        findViewById(R.id.settingsButton).setOnClickListener(view -> showSettings());
        record = findViewById(R.id.recordButton);
        record.setEnabled(canRecord);
        record.setOnClickListener(view -> {
            if (!canRecord) return;
            startActivity(new Intent(this, RecordingActivity.class));
            finish();
        });
        updateReadiness();
    }

    private void updateReadiness() {
        ((TextView) findViewById(R.id.cameraDetail)).setText(detail);
        TextView badge = findViewById(R.id.cameraStatus);
        badge.setText(problem != null ? "需检查" : hasFrames ? "实时画面" : "连接中");
        badge.setTextColor(problem != null ? CameraUi.AMBER : hasFrames ? CameraUi.MINT : CameraUi.MUTED);
        TextView depth = findViewById(R.id.depthStatus);
        depth.setText(depthText);
        depth.setTextColor(canRecord ? CameraUi.MINT : CameraUi.MUTED);
        boolean microphone = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        TextView mic = findViewById(R.id.micStatus);
        mic.setText(microphone ? "录音已授权" : "录音待授权");
        mic.setTextColor(microphone ? CameraUi.MINT : CameraUi.AMBER);
        TextView hint = findViewById(R.id.previewHint);
        hint.setVisibility(hasFrames && problem == null ? View.GONE : View.VISIBLE);
        if (problem != null) hint.setText(problem + "\n可打开设置检查配置，或返回首页重新连接。");
        record.setEnabled(canRecord && problem == null);
        ((TextView) findViewById(R.id.shutterLabel)).setText(canRecord ? "开始录制" : "等待相机就绪");
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        preview.close();
        bindLayout();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        long generation = ++previewGeneration;
        hasFrames = false;
        canRecord = false;
        problem = null;
        detail = "准备相机画面…";
        depthText = "深度检测中";
        statistics = new StreamingStats();
        firstFrameSeen = new AtomicBoolean();
        previewStartAttempts = 0;
        previewMarkedStale = false;
        updateReadiness();
        previewHealthHandler.removeCallbacks(previewHealthCheck);
        previewHealthHandler.postDelayed(previewHealthCheck, PREVIEW_STALE_AFTER_MS);
        previewThread = new HandlerThread("RS-preview");
        previewThread.start();
        previewHandler = new Handler(previewThread.getLooper());
        previewHandler.post(() -> startPreview(generation));
    }

    private void startPreview(long generation) {
        if (!isCurrentPreview(generation)) return;
        ++previewStartAttempts;
        Streamer current = new Streamer(this, true, new Streamer.Listener() {
            @Override public void config(Config config) { }
            @Override public void onFrameset(FrameSet frames) {
                RgbPreviewSurfaceView surface = preview;
                if (surface == null || !isCurrentPreview(generation)) return;
                if (previewMarkedStale) {
                    previewMarkedStale = false;
                    Log.i("RS preview", "frame watchdog: frames resumed");
                    readFrameInfo(frames, generation);
                }
                if (firstFrameSeen.compareAndSet(false, true)) readFrameInfo(frames, generation);
                surface.upload(frames);
                if (statisticsVisible) {
                    statistics.onFrameset(frames);
                    long now = System.nanoTime();
                    if (now >= nextStatsNs) {
                        String snapshot = statistics.prettyPrint();
                        runOnUiThread(() -> {
                            if (isCurrentPreview(generation) && statisticsText != null)
                                statisticsText.setText(snapshot);
                        });
                        nextStatsNs = now + 500_000_000;
                    }
                }
            }
            @Override public void onError(Exception error) {
                retryPreview(generation, error);
            }
        });
        streamer = current;
        if (!isCurrentPreview(generation)) return;
        try {
            RgbPreviewSurfaceView surface = preview;
            if (surface != null) surface.clear();
            current.start();
            if (!isCurrentPreview(generation)) current.stop();
            else runOnUiThread(() -> {
                if (isCurrentPreview(generation)) startFwLogging();
            });
        } catch (Exception error) {
            Log.e("RS preview", "Cannot start preview", error);
            current.stop();
            retryPreview(generation, error);
        }
    }

    private void retryPreview(long generation, Exception error) {
        if (!isCurrentPreview(generation)) return;
        if (previewStartAttempts >= MAX_PREVIEW_ATTEMPTS) {
            showProblem(generation, describeCameraError("多次尝试后仍无法收到相机画面", error));
            return;
        }
        int nextAttempt = previewStartAttempts + 1;
        runOnUiThread(() -> {
            if (!isCurrentPreview(generation)) return;
            hasFrames = false;
            canRecord = false;
            problem = null;
            detail = "相机画面未就绪，正在自动重连（" + nextAttempt + "/" + MAX_PREVIEW_ATTEMPTS + "）…";
            firstFrameSeen.set(false);
            RgbPreviewSurfaceView surface = preview;
            if (surface != null) surface.clear();
            updateReadiness();
        });
        Handler handler = previewHandler;
        if (handler != null) handler.postDelayed(() -> startPreview(generation), PREVIEW_RETRY_DELAY_MS);
    }

    private boolean isCurrentPreview(long generation) {
        return resumed && generation == previewGeneration;
    }

    private String describeCameraError(String prefix, Exception error) {
        String detail = error == null ? null : error.getMessage();
        if (detail == null || detail.trim().isEmpty()) return prefix;
        detail = detail.trim().replace('\n', ' ');
        if (detail.length() > 160) detail = detail.substring(0, 160) + "…";
        return prefix + "：" + detail;
    }

    private void readFrameInfo(FrameSet frames, long generation) {
        String nextDepth = "深度未开启";
        String nextDetail = "未检测到彩色画面";
        boolean nextCanRecord = false;
        String nextProblem = null;
        try (Frame color = frames.first(StreamType.COLOR); Frame depth = frames.first(StreamType.DEPTH)) {
            nextDepth = depth == null ? "深度未开启" : "深度已就绪";
            if (color == null) {
                nextProblem = "未开启彩色画面，请在录制设置中开启彩色和深度";
            } else {
                VideoFrame video = color.as(Extension.VIDEO_FRAME);
                try (VideoStreamProfile profile = video.getProfile()) {
                    nextDetail = video.getWidth() + " × " + video.getHeight() + "  ·  " + profile.getFrameRate() + " fps";
                    StreamFormat format = profile.getFormat();
                    nextCanRecord = depth != null && (format == StreamFormat.RGB8 || format == StreamFormat.RGBA8 || format == StreamFormat.YUYV);
                    if (!nextCanRecord) nextDetail += depth == null ? "  ·  请开启深度" : "  ·  请使用 RGB8 / YUYV";
                }
            }
        } catch (Exception error) {
            nextProblem = describeCameraError("读取相机画面失败", error);
        }
        final String frameDetail = nextDetail;
        final String frameDepth = nextDepth;
        final boolean frameCanRecord = nextCanRecord;
        final String frameProblem = nextProblem;
        runOnUiThread(() -> {
            if (!isCurrentPreview(generation)) return;
            hasFrames = true;
            depthText = frameDepth;
            detail = frameDetail;
            canRecord = frameCanRecord;
            problem = frameProblem;
            updateReadiness();
        });
    }

    private void showProblem(long generation, String message) {
        runOnUiThread(() -> {
            if (!isCurrentPreview(generation)) return;
            problem = message;
            canRecord = false;
            updateReadiness();
        });
    }

    private void showSettings() {
        LinearLayout content = CameraUi.column(this);
        MaterialButton streams = CameraUi.button(this, "录制设置 · 分辨率与帧率", R.drawable.ic_av_settings, false);
        content.addView(streams, new LinearLayout.LayoutParams(-1, CameraUi.dp(this, 54)));
        CameraUi.gap(content, 10);
        MaterialButton camera = CameraUi.button(this, "相机参数 · 曝光与增益", R.drawable.ic_av_camera, false);
        camera.setEnabled(hasFrames && problem == null);
        content.addView(camera, new LinearLayout.LayoutParams(-1, CameraUi.dp(this, 54)));
        CameraUi.gap(content, 10);
        MaterialButton stats = CameraUi.button(this, "高级 · 查看采集统计", R.drawable.ic_av_layers, false);
        content.addView(stats, new LinearLayout.LayoutParams(-1, CameraUi.dp(this, 54)));
        settingsSheet = CameraUi.sheet(this, "拍摄设置", "有声视频与深度数据会同时保存", content);
        streams.setOnClickListener(view -> { settingsSheet.dismiss(); startActivity(new Intent(this, SettingsActivity.class)); });
        camera.setOnClickListener(view -> {
            settingsSheet.dismiss();
            ControlsDialog controls = new ControlsDialog();
            controls.setCancelable(true);
            controls.show(getFragmentManager(), "cameraControls");
        });
        stats.setOnClickListener(view -> {
            settingsSheet.dismiss();
            LinearLayout info = CameraUi.column(this);
            statisticsText = CameraUi.text(this, "正在收集采集统计…", 12, CameraUi.MUTED);
            info.addView(statisticsText);
            statisticsSheet = CameraUi.sheet(this, "采集统计", "供排查分辨率、帧率和丢帧使用", info);
            statisticsSheet.setOnDismissListener(dialog -> statisticsVisible = false);
            statisticsSheet.show();
            statisticsVisible = true;
        });
        settingsSheet.show();
    }

    private void startFwLogging() {
        SharedPreferences preferences = getSharedPreferences(getString(R.string.app_settings), Context.MODE_PRIVATE);
        if (!preferences.getBoolean(getString(R.string.fw_logging), false)) return;
        fwLogs = new FwLogsThread();
        String path = preferences.getString(getString(R.string.fw_logging_file_path), "");
        if (!path.isEmpty()) fwLogs.init(path);
        fwLogs.start();
    }

    @Override protected void onPause() {
        resumed = false;
        ++previewGeneration;
        Handler handler = previewHandler;
        previewHandler = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        previewHealthHandler.removeCallbacks(previewHealthCheck);
        Streamer current = streamer;
        streamer = null;
        // Pipeline.start() and its first-frame wait run on the preview thread.
        // Stopping synchronously here would block the UI while start() holds its lock.
        if (current != null) {
            if (handler != null) handler.post(current::stop);
            else current.stop();
        }
        if (previewThread != null) {
            previewThread.quitSafely();
            previewThread = null;
        }
        if (preview != null) preview.clear();
        if (settingsSheet != null) settingsSheet.dismiss();
        if (statisticsSheet != null) statisticsSheet.dismiss();
        statisticsVisible = false;
        if (fwLogs != null) { fwLogs.stopLogging(); fwLogs.interrupt(); fwLogs = null; }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (preview != null) preview.close();
        super.onDestroy();
    }
}
