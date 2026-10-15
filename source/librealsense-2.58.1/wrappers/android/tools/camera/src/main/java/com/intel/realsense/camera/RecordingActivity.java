package com.intel.realsense.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.intel.realsense.librealsense.FrameSet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RecordingActivity extends AppCompatActivity {
    private static final int REQUEST_RECORDING_PERMISSIONS = 20;
    private volatile RgbPreviewSurfaceView preview;
    private RecordingSession session;
    private TextView status;
    private MaterialButton stop;
    private long elapsedSeconds;
    private long recordedBytes;
    private float micLevel;
    private boolean resumed;
    private boolean started;
    private boolean stopping;
    private boolean destroyed;
    private boolean permissionRequestPending;
    private boolean resultReady;
    private File savedBag;
    private File savedMp4;
    private String error;
    private String statusText = "准备录制深度 BAG + 有声 MP4…";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setupLayout();
    }

    private void setupLayout() {
        preview = new RgbPreviewSurfaceView(this);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        CameraUi.install(this, CameraUi.capture(this, preview, true, landscape));
        findViewById(R.id.uiBack).setOnClickListener(view -> onBackPressed());
        status = findViewById(R.id.recordingStatus);
        status.setText(statusText);
        stop = findViewById(R.id.recordButton);
        stop.setEnabled(!stopping);
        stop.setOnClickListener(view -> requestStop());
        updateProgress();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (preview != null) preview.close();
        setupLayout();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (resultReady) { showResult(); return; }
        if (started || permissionRequestPending || stopping) return;
        List<String> missing = new ArrayList<>();
        for (String permission : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
                missing.add(permission);
        }
        if (!missing.isEmpty()) {
            permissionRequestPending = true;
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQUEST_RECORDING_PERMISSIONS);
        } else { startRecording(); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != REQUEST_RECORDING_PERMISSIONS) return;
        permissionRequestPending = false;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "录像并录音需要相机和麦克风权限。请在应用权限设置中允许后重新录制。";
            resultReady = true;
            if (resumed) showResult();
            return;
        }
        if (resumed && !started && !stopping) startRecording();
    }

    private void startRecording() {
        started = true;
        session = new RecordingSession(this, new RecordingSession.Listener() {
            @Override public void onFrames(FrameSet frames) {
                RgbPreviewSurfaceView surface = preview;
                if (surface != null) surface.upload(frames);
            }
            @Override public void onStatus(String message) {
                if (destroyed) return;
                statusText = message;
                status.setText(message);
            }
            @Override public void onLevel(float level) {
                if (destroyed || stopping) return;
                micLevel = level;
                ((MicLevelView) findViewById(R.id.micMeter)).setLevel(level);
                ((TextView) findViewById(R.id.micStatus)).setText("手机麦克风 · 正在采集");
            }
            @Override public void onProgress(long seconds, long bytes) {
                if (destroyed || stopping) return;
                elapsedSeconds = seconds;
                recordedBytes = bytes;
                updateProgress();
            }
            @Override public void onFinished(File bag, File mp4, String problem) {
                session = null;
                savedBag = bag;
                savedMp4 = mp4;
                error = problem;
                resultReady = true;
                if (!destroyed && resumed) showResult();
            }
        });
        session.start();
    }

    private void updateProgress() {
        ((TextView) findViewById(R.id.recordingTimer)).setText(String.format(java.util.Locale.CHINA,
                "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60));
        ((MicLevelView) findViewById(R.id.micMeter)).setLevel(stopping ? 0 : micLevel);
        if (recordedBytes > 0) {
            ((TextView) findViewById(R.id.recordingSize)).setText("已写入 " + RecordingItem.size(recordedBytes) + "  ·  视频 + 深度");
            ((TextView) findViewById(R.id.cameraDetail)).setText("手机收音 · 深度数据保留");
            ((TextView) findViewById(R.id.micStatus)).setText(stopping ? "麦克风已停止采集" : "手机麦克风 · 正在采集");
            TextView badge = findViewById(R.id.cameraStatus);
            badge.setText(stopping ? "保存中" : "● REC");
            badge.setTextColor(stopping ? CameraUi.AMBER : CameraUi.RED);
            findViewById(R.id.previewHint).setVisibility(android.view.View.GONE);
        }
    }

    private void requestStop() {
        if (stopping) return;
        stopping = true;
        stop.setEnabled(false);
        statusText = "正在保存录制文件…";
        status.setText(statusText);
        ((TextView) findViewById(R.id.shutterLabel)).setText("正在保存…");
        ((TextView) findViewById(R.id.cameraStatus)).setText("保存中");
        updateProgress();
        if (session != null) session.stop();
        else if (!resultReady) returnToPreview();
    }

    private void showResult() {
        resultReady = false;
        stopping = true;
        stop.setEnabled(false);
        LinearLayout contents = CameraUi.column(this);
        TextView summary = CameraUi.text(this, savedMp4 != null ? "有声视频和深度数据已保存在录像库。"
                : "未生成完整的视频，已有文件可到录像库查看和导出。", 15, CameraUi.TEXT);
        contents.addView(summary);
        if (error != null) {
            CameraUi.gap(contents, 12);
            contents.addView(CameraUi.text(this, error, 13, CameraUi.AMBER));
        }
        CameraUi.gap(contents, 22);
        BottomSheetDialog dialog = CameraUi.sheet(this, error == null ? "这段记录，已保存" : "录制已停止",
                String.format(java.util.Locale.CHINA, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
                        + "  ·  " + RecordingItem.size(recordedBytes), contents);
        if (savedMp4 != null) {
            MaterialButton play = CameraUi.button(this, "播放这段录像", R.drawable.ic_av_play, true);
            contents.addView(play, new LinearLayout.LayoutParams(-1, CameraUi.dp(this, 54)));
            play.setOnClickListener(view -> {
                dialog.dismiss();
                Intent intent = new Intent(this, VideoPlaybackActivity.class);
                intent.putExtra(getString(R.string.intent_extra_file_path), savedMp4.getAbsolutePath());
                startActivity(intent);
                finish();
            });
            CameraUi.gap(contents, 10);
        }
        MaterialButton again = CameraUi.button(this, "继续拍摄", R.drawable.ic_av_camera, false);
        contents.addView(again, new LinearLayout.LayoutParams(-1, CameraUi.dp(this, 54)));
        again.setOnClickListener(view -> { dialog.dismiss(); returnToPreview(); });
        CameraUi.gap(contents, 10);
        MaterialButton library = CameraUi.button(this, "打开录像库 / 导出", R.drawable.ic_av_library, false);
        contents.addView(library, new LinearLayout.LayoutParams(-1, CameraUi.dp(this, 54)));
        library.setOnClickListener(view -> { dialog.dismiss(); startActivity(new Intent(this, RecordingLibraryActivity.class)); finish(); });
        dialog.setCancelable(false);
        dialog.show();
    }

    private void returnToPreview() {
        Intent intent = new Intent(this, PreviewActivity.class);
        startActivity(intent);
        finish();
    }

    @Override public void onBackPressed() {
        if (session == null || stopping) { requestStop(); return; }
        new AlertDialog.Builder(this).setTitle("结束这次录制？")
                .setMessage("当前片段会保存到录像库。")
                .setNegativeButton("继续录制", null)
                .setPositiveButton("停止并保存", (dialog, which) -> requestStop()).show();
    }

    @Override protected void onPause() {
        resumed = false;
        if (session != null) requestStop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (session != null) session.stop();
        if (preview != null) preview.close();
        preview = null;
        super.onDestroy();
    }
}
