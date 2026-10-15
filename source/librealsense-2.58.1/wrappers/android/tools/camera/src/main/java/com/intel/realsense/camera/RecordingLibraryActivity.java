package com.intel.realsense.camera;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordingLibraryActivity extends AppCompatActivity {
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final List<Object> rows = new ArrayList<>();
    private final Set<String> pending = new HashSet<>();
    private final LruCache<String, MediaInfo> media = new LruCache<String, MediaInfo>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, MediaInfo value) {
            return value.image == null ? 1024 : value.image.getAllocationByteCount() + 1024;
        }
    };
    private final LibraryAdapter adapter = new LibraryAdapter();
    private RecordingExport exporter;
    private BottomSheetDialog details;
    private boolean destroyed;
    private long generation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        CameraUi.install(this, CameraUi.library(this));
        exporter = new RecordingExport(this, state);
        findViewById(R.id.uiBack).setOnClickListener(view -> finish());
        findViewById(R.id.emptyCapture).setOnClickListener(view -> {
            startActivity(new Intent(this, DetachedActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        ((ListView) findViewById(R.id.libraryList)).setAdapter(adapter);
    }

    @Override protected void onResume() {
        super.onResume();
        long request = ++generation;
        File external = getExternalFilesDir(null);
        File directory = external == null ? null : new File(external, getString(R.string.realsense_folder) + "/video");
        workers.execute(() -> {
            List<RecordingItem> items = RecordingItem.load(directory);
            long size = 0;
            for (RecordingItem item : items) size += item.bytes;
            long bytes = size;
            runOnUiThread(() -> {
                if (destroyed || request != generation) return;
                rows.clear();
                String previous = "";
                for (RecordingItem item : items) {
                    String day = new SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(new Date(item.timestamp));
                    if (!day.equals(previous)) { rows.add(day); previous = day; }
                    rows.add(item);
                }
                ((TextView) findViewById(R.id.libraryCount)).setText(items.isEmpty() ? "还没有拍摄记录"
                        : items.size() + " 次拍摄  ·  " + RecordingItem.size(bytes));
                findViewById(R.id.libraryEmpty).setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                findViewById(R.id.libraryList).setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                if (directory == null) ((TextView) findViewById(R.id.libraryCount)).setText("手机存储暂时不可用");
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void showDetails(RecordingItem item) {
        LinearLayout content = CameraUi.column(this);
        if (item.interrupted) {
            TextView warning = CameraUi.text(this, item.error.isEmpty() ? "这次录制未完整完成，可导出已经保存的文件。" : item.error, 13, CameraUi.AMBER);
            content.addView(warning);
            CameraUi.gap(content, 16);
        }
        if (item.video.isFile()) {
            MaterialButton play = CameraUi.button(this, "播放视频", R.drawable.ic_av_play, true);
            content.addView(play, new LinearLayout.LayoutParams(-1, CameraUi.dp(this, 54)));
            play.setOnClickListener(view -> {
                details.dismiss();
                startActivity(new Intent(this, VideoPlaybackActivity.class)
                        .putExtra(getString(R.string.intent_extra_file_path), item.video.getPath()));
            });
            CameraUi.gap(content, 10);
        }
        if (item.bag.isFile()) {
            MaterialButton depth = CameraUi.button(this, "查看深度数据", R.drawable.ic_av_layers, false);
            content.addView(depth, new LinearLayout.LayoutParams(-1, CameraUi.dp(this, 54)));
            depth.setOnClickListener(view -> {
                details.dismiss();
                startActivity(new Intent(this, PlaybackActivity.class)
                        .putExtra(getString(R.string.intent_extra_file_path), item.bag.getPath()));
            });
            CameraUi.gap(content, 10);
        }
        MaterialButton export = CameraUi.button(this, "导出文件 / 全部打包", R.drawable.ic_av_export, false);
        export.setOnClickListener(view -> { details.dismiss(); exporter.choose(item); });
        content.addView(export, new LinearLayout.LayoutParams(-1, CameraUi.dp(this, 54)));
        CameraUi.gap(content, 14);
        content.addView(CameraUi.text(this, "声音保存在 MP4 中；深度保存在同组 BAG 中。", 12, CameraUi.MUTED));
        details = CameraUi.sheet(this, item.title(), item.formats() + "  ·  " + RecordingItem.size(item.bytes), content);
        details.show();
    }

    private void requestThumbnail(RecordingItem item) {
        if (!item.video.isFile() || pending.contains(item.base) || destroyed) return;
        pending.add(item.base);
        workers.execute(() -> {
            Bitmap image = null;
            String duration = "";
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(item.video.getPath());
                String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (value != null) {
                    long seconds = Long.parseLong(value) / 1000;
                    duration = String.format(Locale.CHINA, "%02d:%02d", seconds / 60, seconds % 60);
                }
                if (Build.VERSION.SDK_INT >= 27) image = retriever.getScaledFrameAtTime(0,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
                else {
                    Bitmap original = retriever.getFrameAtTime(0);
                    if (original != null) {
                        image = Bitmap.createScaledBitmap(original, 320, Math.max(1, original.getHeight() * 320 / original.getWidth()), true);
                        if (image != original) original.recycle();
                    }
                }
            } catch (Exception ignored) { }
            finally { try { retriever.release(); } catch (Exception ignored) { } }
            MediaInfo result = new MediaInfo(image, duration);
            runOnUiThread(() -> {
                pending.remove(item.base);
                if (destroyed) return;
                media.put(item.base, result);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private final class LibraryAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return rows.get(position) instanceof String ? 0 : 1; }
        @Override public boolean isEnabled(int position) { return getItemViewType(position) == 1; }
        @Override public View getView(int position, View reused, ViewGroup parent) {
            if (rows.get(position) instanceof String) {
                TextView date = CameraUi.text(RecordingLibraryActivity.this, (String) rows.get(position), 12, CameraUi.MUTED);
                date.setPadding(0, CameraUi.dp(date.getContext(), 20), 0, CameraUi.dp(date.getContext(), 12));
                return date;
            }
            RecordingItem item = (RecordingItem) rows.get(position);
            MediaInfo info = media.get(item.base);
            LinearLayout container = CameraUi.column(RecordingLibraryActivity.this);
            container.setPadding(0, 0, 0, CameraUi.dp(container.getContext(), 12));
            LinearLayout card = CameraUi.row(RecordingLibraryActivity.this);
            card.setPadding(CameraUi.dp(card.getContext(), 12), CameraUi.dp(card.getContext(), 12),
                    CameraUi.dp(card.getContext(), 14), CameraUi.dp(card.getContext(), 12));
            card.setBackground(CameraUi.shape(card.getContext(), CameraUi.SURFACE, 20, true));
            ImageView thumbnail = new ImageView(card.getContext());
            thumbnail.setBackground(CameraUi.shape(card.getContext(), CameraUi.BORDER, 12, false));
            thumbnail.setClipToOutline(true);
            if (info != null && info.image != null) {
                thumbnail.setImageBitmap(info.image);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                thumbnail.setImageResource(item.video.isFile() ? R.drawable.ic_av_play : R.drawable.ic_av_layers);
                thumbnail.setColorFilter(CameraUi.MUTED);
                thumbnail.setPadding(CameraUi.dp(card.getContext(), 28), CameraUi.dp(card.getContext(), 24),
                        CameraUi.dp(card.getContext(), 28), CameraUi.dp(card.getContext(), 24));
                if (info == null) requestThumbnail(item);
            }
            card.addView(thumbnail, new LinearLayout.LayoutParams(CameraUi.dp(card.getContext(), 96), CameraUi.dp(card.getContext(), 82)));
            CameraUi.horizontalGap(card, 14);
            LinearLayout labels = CameraUi.column(card.getContext());
            TextView title = CameraUi.text(card.getContext(), new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(item.timestamp)) + " 的拍摄", 16, CameraUi.TEXT);
            CameraUi.bold(title);
            labels.addView(title);
            CameraUi.gap(labels, 8);
            labels.addView(CameraUi.text(card.getContext(), (info != null && !info.duration.isEmpty() ? info.duration + "  ·  " : "")
                    + RecordingItem.size(item.bytes), 12, CameraUi.MUTED));
            CameraUi.gap(labels, 8);
            labels.addView(CameraUi.text(card.getContext(), item.status(), 11, item.interrupted ? CameraUi.AMBER : CameraUi.MINT));
            card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            card.setContentDescription(item.title() + "，" + item.status() + "，" + RecordingItem.size(item.bytes));
            card.setOnClickListener(view -> showDetails(item));
            container.addView(card, new LinearLayout.LayoutParams(-1, -2));
            return container;
        }
    }

    private static final class MediaInfo {
        final Bitmap image;
        final String duration;
        MediaInfo(Bitmap image, String duration) { this.image = image; this.duration = duration; }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        exporter.onResult(request, result, data);
    }
    @Override protected void onSaveInstanceState(Bundle state) { exporter.saveState(state); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        destroyed = true;
        if (details != null) details.dismiss();
        workers.shutdownNow();
        super.onDestroy();
    }
}
