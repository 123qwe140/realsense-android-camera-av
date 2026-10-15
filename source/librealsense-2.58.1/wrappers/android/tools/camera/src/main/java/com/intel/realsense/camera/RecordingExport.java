package com.intel.realsense.camera;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared explicit SAF export, retaining the selection across the system picker/rotation. */
final class RecordingExport {
    private static final int REQUEST = 71;
    private final AppCompatActivity activity;
    private String[] paths;
    private boolean zip;
    private boolean busy;

    RecordingExport(AppCompatActivity activity, Bundle state) {
        this.activity = activity;
        if (state != null) { paths = state.getStringArray("exportFiles"); zip = state.getBoolean("exportZip"); }
    }

    void choose(RecordingItem item) {
        if (busy) { Toast.makeText(activity, "上一份文件正在导出，请稍候", Toast.LENGTH_SHORT).show(); return; }
        List<File> files = item.files();
        List<String> names = new ArrayList<>();
        names.add("全部文件 · ZIP 打包");
        for (File file : files) names.add(label(file) + "  ·  " + RecordingItem.size(file.length()));
        new AlertDialog.Builder(activity).setTitle("导出这次拍摄")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    zip = which == 0;
                    List<File> selected = zip ? files : java.util.Collections.singletonList(files.get(which - 1));
                    paths = new String[selected.size()];
                    for (int i = 0; i < selected.size(); i++) paths[i] = selected.get(i).getPath();
                    String name = zip ? item.base + ".zip" : selected.get(0).getName();
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(zip ? "application/zip" : name.endsWith(".mp4") ? "video/mp4" : "application/octet-stream")
                            .putExtra(Intent.EXTRA_TITLE, name);
                    activity.startActivityForResult(intent, REQUEST);
                }).show();
    }

    void saveState(Bundle state) { state.putStringArray("exportFiles", paths); state.putBoolean("exportZip", zip); }

    boolean onResult(int request, int result, Intent data) {
        if (request != REQUEST) return false;
        if (result != AppCompatActivity.RESULT_OK || data == null || data.getData() == null || paths == null) return true;
        String[] selected = paths.clone();
        Uri destination = data.getData();
        boolean bundle = zip;
        busy = true;
        Toast.makeText(activity, "正在导出，完成后会提示。深度文件较大时请稍候。", Toast.LENGTH_LONG).show();
        android.content.ContentResolver resolver = activity.getApplicationContext().getContentResolver();
        new Thread(() -> {
            String message;
            try (OutputStream output = resolver.openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("目标文件不可写");
                if (bundle) {
                    try (ZipOutputStream archive = new ZipOutputStream(output)) {
                        archive.setLevel(0);
                        for (String path : selected) {
                            File file = new File(path);
                            archive.putNextEntry(new ZipEntry(file.getName()));
                            copy(file, archive);
                            archive.closeEntry();
                        }
                    }
                } else { copy(new File(selected[0]), output); }
                message = "文件已导出到你选择的位置";
            } catch (Exception error) { message = "导出失败：" + error.getMessage(); }
            String text = message;
            activity.runOnUiThread(() -> {
                busy = false;
                Toast.makeText(activity.getApplicationContext(), text, Toast.LENGTH_LONG).show();
            });
        }, "RS-export").start();
        return true;
    }

    private static String label(File file) {
        String name = file.getName();
        if (name.endsWith(".mp4")) return "视频 MP4";
        if (name.endsWith(".bag")) return "深度数据 BAG";
        if (name.endsWith(".sync.json")) return "同步信息 JSON";
        if (name.endsWith(".frames.csv")) return "帧对应表 CSV";
        return "未完成视频（用于恢复）";
    }

    private static void copy(File file, OutputStream output) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536];
            int size;
            while ((size = input.read(bytes)) != -1) output.write(bytes, 0, size);
        }
    }
}
