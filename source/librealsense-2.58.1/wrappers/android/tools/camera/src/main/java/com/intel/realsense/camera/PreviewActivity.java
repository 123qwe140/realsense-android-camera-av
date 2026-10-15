package com.intel.realsense.camera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
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

public class PreviewActivity extends AppCompatActivity {
    private RgbPreviewSurfaceView preview;
    private Streamer streamer;
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
    private FwLogsThread fwLogs;
    private long nextStatsNs;

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
        hasFrames = false;
        canRecord = false;
        problem = null;
        detail = "准备相机画面…";
        depthText = "深度检测中";
        statistics = new StreamingStats();
        updateReadiness();
        streamer = new Streamer(this, true, new Streamer.Listener() {
            @Override public void config(Config config) { }
            @Override public void onFrameset(FrameSet frames) {
                if (!hasFrames) readFrameInfo(frames);
                preview.upload(frames);
                if (statisticsSheet != null && statisticsSheet.isShowing()) {
                    statistics.onFrameset(frames);
                    long now = System.nanoTime();
                    if (now >= nextStatsNs) {
                        statisticsText.setText(statistics.prettyPrint());
                        nextStatsNs = now + 500_000_000;
                    }
                }
            }
            @Override public void onError(Exception error) { showProblem("相机连接已中断"); }
        });
        try {
            preview.clear();
            streamer.start();
            startFwLogging();
        } catch (Exception error) {
            Log.e("RS preview", "Cannot start preview", error);
            if (streamer != null) streamer.stop();
            showProblem("暂时无法打开相机画面");
        }
    }

    private void readFrameInfo(FrameSet frames) {
        hasFrames = true;
        try (Frame color = frames.first(StreamType.COLOR); Frame depth = frames.first(StreamType.DEPTH)) {
            depthText = depth == null ? "深度未开启" : "深度已就绪";
            if (color == null) {
                problem = "未开启彩色画面，请在录制设置中开启彩色和深度";
            } else {
                VideoFrame video = color.as(Extension.VIDEO_FRAME);
                try (VideoStreamProfile profile = video.getProfile()) {
                    detail = video.getWidth() + " × " + video.getHeight() + "  ·  " + profile.getFrameRate() + " fps";
                    StreamFormat format = profile.getFormat();
                    canRecord = depth != null && (format == StreamFormat.RGB8 || format == StreamFormat.RGBA8 || format == StreamFormat.YUYV);
                    if (!canRecord) detail += depth == null ? "  ·  请开启深度" : "  ·  请使用 RGB8 / YUYV";
                }
            }
        }
        updateReadiness();
    }

    private void showProblem(String message) {
        problem = message;
        canRecord = false;
        updateReadiness();
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
            statisticsSheet.show();
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
        if (streamer != null) streamer.stop();
        if (preview != null) preview.clear();
        if (settingsSheet != null) settingsSheet.dismiss();
        if (statisticsSheet != null) statisticsSheet.dismiss();
        if (fwLogs != null) { fwLogs.stopLogging(); fwLogs.interrupt(); fwLogs = null; }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (preview != null) preview.close();
        super.onDestroy();
    }
}
