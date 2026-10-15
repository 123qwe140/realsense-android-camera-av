package com.intel.realsense.camera;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.android.material.button.MaterialButton;
import java.io.File;

public class VideoPlaybackActivity extends AppCompatActivity {
    private VideoView video;
    private int position;
    private boolean resumed;
    private boolean prepared;
    private boolean playWhenReady = true;
    private RecordingExport exporter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra(getString(R.string.intent_extra_file_path));
        if (path == null) { finish(); return; }
        File file = new File(path);
        if (!file.isFile()) { Toast.makeText(this, "录像文件不存在", Toast.LENGTH_LONG).show(); finish(); return; }
        RecordingItem item = new RecordingItem(file.getParentFile(), file.getName().substring(0, file.getName().length() - 4));
        exporter = new RecordingExport(this, state);
        if (state != null) { position = state.getInt("position"); playWhenReady = state.getBoolean("playing", true); }
        LinearLayout root = CameraUi.column(this);
        root.setBackgroundColor(CameraUi.BG);
        LinearLayout header = CameraUi.row(this);
        header.setPadding(CameraUi.dp(this, 14), CameraUi.dp(this, 10), CameraUi.dp(this, 18), CameraUi.dp(this, 10));
        MaterialButton back = CameraUi.back(this);
        back.setOnClickListener(view -> onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(CameraUi.dp(this, 48), CameraUi.dp(this, 48)));
        CameraUi.horizontalGap(header, 14);
        LinearLayout name = CameraUi.column(this);
        TextView title = CameraUi.text(this, "录像回放", 20, CameraUi.TEXT);
        CameraUi.bold(title);
        name.addView(title);
        name.addView(CameraUi.text(this, item.title(), 12, CameraUi.MUTED));
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        screen.addView(video, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        root.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = CameraUi.column(this);
        footer.setPadding(CameraUi.dp(this, 20), CameraUi.dp(this, 16), CameraUi.dp(this, 20), CameraUi.dp(this, 20));
        footer.addView(CameraUi.text(this, "点击画面可显示播放控制", 12, CameraUi.MUTED));
        CameraUi.gap(footer, 14);
        LinearLayout buttons = CameraUi.row(this);
        MaterialButton export = CameraUi.button(this, "导出文件", R.drawable.ic_av_export, true);
        export.setOnClickListener(view -> exporter.choose(item));
        buttons.addView(export, new LinearLayout.LayoutParams(0, CameraUi.dp(this, 54), 1));
        CameraUi.horizontalGap(buttons, 12);
        MaterialButton share = CameraUi.button(this, "分享视频", R.drawable.ic_av_share, false);
        share.setOnClickListener(view -> {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent intent = new Intent(Intent.ACTION_SEND).setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "分享这段录像"));
        });
        buttons.addView(share, new LinearLayout.LayoutParams(0, CameraUi.dp(this, 54), 1));
        footer.addView(buttons);
        root.addView(footer);
        CameraUi.install(this, root);
        MediaController controls = new MediaController(this);
        controls.setAnchorView(video);
        video.setMediaController(controls);
        video.setOnPreparedListener(player -> {
            prepared = true;
            video.seekTo(position);
            if (resumed && playWhenReady) video.start();
        });
        video.setOnErrorListener((player, what, extra) -> {
            Toast.makeText(this, "视频暂时无法播放，可导出文件到电脑检查。", Toast.LENGTH_LONG).show();
            return true;
        });
        video.setVideoPath(file.getPath());
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (prepared && video != null) { video.seekTo(position); if (playWhenReady) video.start(); }
    }
    @Override protected void onPause() {
        resumed = false;
        if (video != null && prepared) { position = video.getCurrentPosition(); playWhenReady = video.isPlaying(); video.pause(); }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("position", video != null ? video.getCurrentPosition() : position);
        state.putBoolean("playing", playWhenReady);
        if (exporter != null) exporter.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (exporter != null) exporter.onResult(request, result, data);
    }
    @Override public void onBackPressed() {
        startActivity(new Intent(this, RecordingLibraryActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
    @Override protected void onDestroy() { if (video != null) video.stopPlayback(); super.onDestroy(); }
}