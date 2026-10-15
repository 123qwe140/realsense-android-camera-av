package com.intel.realsense.camera;

import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A recording is a group of sibling files, not one entry per extension. */
final class RecordingItem {
    final File directory;
    final String base;
    final File video;
    final File bag;
    final File partial;
    final long timestamp;
    final long bytes;
    final boolean interrupted;
    final String error;

    RecordingItem(File directory, String base) {
        this.directory = directory;
        this.base = base;
        video = new File(directory, base + ".mp4");
        bag = new File(directory, base + ".bag");
        partial = new File(directory, base + ".mp4.part");
        JSONObject metadata = readMetadata(new File(directory, base + ".sync.json"));
        long modified = Math.max(video.lastModified(), Math.max(bag.lastModified(), partial.lastModified()));
        Date parsed = null;
        for (String pattern : new String[]{"yyyyMMdd_HHmmss_SSS", "yyyyMMdd_HHmmss"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(base, position);
            if (date != null && position.getIndex() == base.length()) { parsed = date; break; }
        }
        timestamp = metadata.optLong("started_utc_ms", parsed != null ? parsed.getTime() : modified);
        interrupted = partial.isFile() || (metadata.has("complete") && !metadata.optBoolean("complete"));
        error = metadata.optString("error", "");
        long total = 0;
        for (File file : files()) total += file.length();
        bytes = total;
    }

    static List<RecordingItem> load(File directory) {
        Map<String, RecordingItem> items = new LinkedHashMap<>();
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return new ArrayList<>();
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();
            String suffix = name.endsWith(".mp4.part") ? ".mp4.part"
                    : name.endsWith(".mp4") ? ".mp4" : name.endsWith(".bag") ? ".bag" : null;
            if (suffix == null) continue;
            String base = name.substring(0, name.length() - suffix.length());
            if (!items.containsKey(base)) items.put(base, new RecordingItem(directory, base));
        }
        List<RecordingItem> result = new ArrayList<>(items.values());
        result.sort((left, right) -> Long.compare(right.timestamp, left.timestamp));
        return result;
    }

    List<File> files() {
        List<File> files = new ArrayList<>();
        for (String suffix : new String[]{".mp4", ".bag", ".sync.json", ".frames.csv", ".mp4.part"}) {
            File file = new File(directory, base + suffix);
            if (file.isFile()) files.add(file);
        }
        return files;
    }

    String title() { return new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(timestamp)); }
    String formats() {
        if (video.isFile() && bag.isFile()) return "视频 MP4  +  深度数据";
        if (video.isFile()) return "视频 MP4";
        if (bag.isFile()) return "深度数据 BAG";
        return "未完成的录像";
    }
    String status() { return interrupted ? "录制中断 · 可导出已有文件" : formats(); }
    static String size(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.CHINA, "%.1f GB", bytes / (1024d * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format(Locale.CHINA, "%.1f MB", bytes / (1024d * 1024));
        return String.format(Locale.CHINA, "%.0f KB", bytes / 1024d);
    }

    private static JSONObject readMetadata(File file) {
        if (!file.isFile() || file.length() > 65536) return new JSONObject();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0, count;
            while (offset < bytes.length && (count = input.read(bytes, offset, bytes.length - offset)) > 0) offset += count;
            return new JSONObject(new String(bytes, 0, offset, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { return new JSONObject(); }
    }
}
