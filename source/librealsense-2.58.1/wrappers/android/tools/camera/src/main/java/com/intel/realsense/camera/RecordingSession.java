package com.intel.realsense.camera;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.intel.realsense.librealsense.Config;
import com.intel.realsense.librealsense.Extension;
import com.intel.realsense.librealsense.Frame;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.VideoFrame;
import com.intel.realsense.librealsense.VideoStreamProfile;
import com.intel.realsense.librealsense.YuyDecoder;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** One .bag and one MP4 per foreground recording. Owns all recording resources. */
final class RecordingSession {
    interface Listener {
        // upload() only: the preview itself retains the frame until its GL thread uses it.
        void onFrames(FrameSet frames);
        void onStatus(String status);
        void onFinished(File bag, File mp4, String error);
        default void onLevel(float level) { }
        default void onProgress(long seconds, long bytes) { }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("RS-recording");
    private final Handler worker;
    private Streamer streamer;
    private AvRecorder recorder;
    private YuyDecoder decoder;
    private BufferedWriter timestamps;
    private File bag;
    private File mp4;
    private File pendingMp4;
    private File metadata;
    private final JSONObject details = new JSONObject();
    private volatile boolean stopRequested;
    private boolean finished;
    private long nextStatusNs;
    private long nextLevelNs;
    private String failure;
    private int width;
    private int height;

    RecordingSession(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void start() { worker.post(this::startOnWorker); }

    void stop() {
        stopRequested = true;
        worker.post(this::finishOnWorker);
    }

    private void status(String message) { main.post(() -> listener.onStatus(message)); }

    private void startOnWorker() {
        if (stopRequested) { finishOnWorker(); return; }
        try {
            File external = context.getExternalFilesDir(null);
            if (external == null) throw new IOException("手机存储不可用");
            File directory = new File(external, context.getString(R.string.realsense_folder) + "/video");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建录制目录");
            if (directory.getUsableSpace() < 256L * 1024 * 1024) throw new IOException("可用存储不足 256MB");
            String base = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            bag = new File(directory, base + ".bag");
            mp4 = new File(directory, base + ".mp4");
            pendingMp4 = new File(directory, base + ".mp4.part");
            metadata = new File(directory, base + ".sync.json");
            File csv = new File(directory, base + ".frames.csv");
            timestamps = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csv), StandardCharsets.UTF_8));
            timestamps.write("color_frame,color_timestamp_ms,color_domain,depth_frame,depth_timestamp_ms,depth_domain,host_arrival_ns,mp4_pts_us\n");
            details.put("schema_version", 1);
            details.put("bag_file", bag.getName());
            details.put("mp4_file", mp4.getName());
            details.put("frame_map_file", csv.getName());
            details.put("started_utc_ms", System.currentTimeMillis());
            details.put("complete", false);
            details.put("audio_source", "phone_builtin_microphone");
            details.put("audio_sample_rate", AvRecorder.SAMPLE_RATE);
            details.put("synchronization", "video host arrival and AudioRecord MONOTONIC timestamps; not hardware synchronized");
            details.put("bag_note", "bag starts before the MP4; microphone audio is in the paired MP4, not embedded in bag");
            writeMetadata();
            streamer = new Streamer(context, true, new Streamer.Listener() {
                @Override public void config(Config config) { }
                @Override public void onFrameset(FrameSet frames) {
                    if (stopRequested) return;
                    try {
                        recordFrames(frames);
                        listener.onFrames(frames);
                    } catch (Exception error) { fail(error); }
                }
                @Override public void onError(Exception error) { fail(error); }
            });
            streamer.setRecordFilePath(bag.getAbsolutePath());
            status("正在连接摄像头…");
            streamer.start();
            // Stop may have been requested while Pipeline.start/waitForFrames was blocking.
            if (stopRequested) finishOnWorker();
        } catch (Exception error) { fail(error); }
    }

    private void recordFrames(FrameSet frames) throws Exception {
        long arrivalNs = System.nanoTime();
        try (Frame color = frames.first(StreamType.COLOR); Frame depth = frames.first(StreamType.DEPTH)) {
            if (color == null || depth == null)
                throw new IOException("双格式录制需要 COLOR 和 DEPTH，请在设置中同时开启");
            if (recorder == null) {
                VideoFrame vf = color.as(Extension.VIDEO_FRAME);
                width = vf.getWidth();
                height = vf.getHeight();
                try (VideoStreamProfile profile = vf.getProfile()) {
                    int fps = profile.getFrameRate();
                    StreamFormat format = profile.getFormat();
                    if (format != StreamFormat.RGB8 && format != StreamFormat.RGBA8 && format != StreamFormat.YUYV)
                        throw new IOException("COLOR 格式需要 RGB8、RGBA8 或 YUYV，请修改设置");
                    recorder = new AvRecorder(context, pendingMp4, width, height, fps);
                    details.put("color_width", width);
                    details.put("color_height", height);
                    details.put("color_fps", fps);
                    details.put("color_format", format.name());
                }
                details.put("mp4_epoch_monotonic_ns", recorder.epochNs());
                details.put("first_color_device_timestamp_ms", color.getTimestamp());
                details.put("first_color_timestamp_domain", color.getTimestampDomain().name());
                try (VideoStreamProfile depthProfile = ((VideoFrame) depth.as(Extension.VIDEO_FRAME)).getProfile()) {
                    details.put("depth_width", depthProfile.getWidth());
                    details.put("depth_height", depthProfile.getHeight());
                    details.put("depth_fps", depthProfile.getFrameRate());
                    details.put("depth_format", depthProfile.getFormat().name());
                }
                writeMetadata();
            }
            long ptsUs;
            try (VideoStreamProfile profile = ((VideoFrame) color.as(Extension.VIDEO_FRAME)).getProfile()) {
                if (profile.getFormat() == StreamFormat.YUYV) {
                    if (decoder == null) decoder = new YuyDecoder();
                    Frame decoded = decoder.process(color);
                    try { ptsUs = writeColor(decoded, arrivalNs); }
                    finally { if (decoded != color) decoded.close(); }
                } else { ptsUs = writeColor(color, arrivalNs); }
            }
            timestamps.write(color.getNumber() + "," + color.getTimestamp() + "," + color.getTimestampDomain().name()
                    + "," + depth.getNumber() + "," + depth.getTimestamp() + "," + depth.getTimestampDomain().name()
                    + "," + arrivalNs + "," + ptsUs + "\n");
            if (arrivalNs >= nextLevelNs) {
                float level = recorder.audioLevel();
                main.post(() -> listener.onLevel(level));
                nextLevelNs = arrivalNs + 100_000_000;
            }
            if (arrivalNs >= nextStatusNs) {
                timestamps.flush();
                if (bag.getParentFile().getUsableSpace() < 64L * 1024 * 1024)
                    throw new IOException("存储空间不足，录制已停止");
                long seconds = Math.max(0, (System.nanoTime() - recorder.epochNs()) / 1_000_000_000);
                status("正在录制 · 视频与深度同时保存");
                long bytes = bag.length() + pendingMp4.length();
                main.post(() -> listener.onProgress(seconds, bytes));
                nextStatusNs = arrivalNs + 1_000_000_000;
            }
        }
    }

    private long writeColor(Frame frame, long arrivalNs) throws Exception {
        VideoFrame vf = frame.as(Extension.VIDEO_FRAME);
        if (vf.getWidth() != width || vf.getHeight() != height) throw new IOException("录制期间图像尺寸发生变化");
        int channels;
        try (VideoStreamProfile profile = vf.getProfile()) {
            if (profile.getFormat() == StreamFormat.RGB8) channels = 3;
            else if (profile.getFormat() == StreamFormat.RGBA8) channels = 4;
            else throw new IOException("无法编码 COLOR 格式: " + profile.getFormat());
        }
        byte[] data = recorder.sourceBuffer(frame.getDataSize());
        frame.getData(data);
        return recorder.writeVideo(data, vf.getStride(), channels, arrivalNs);
    }

    private void fail(Exception error) {
        Log.e("RS dual recording", "Recording failed", error);
        if (failure == null) failure = error.getMessage() != null ? error.getMessage() : error.toString();
        stop();
    }

    private void finishOnWorker() {
        if (finished) return;
        finished = true;
        stopRequested = true;
        status("正在保存录制文件…");
        File savedMp4 = null;
        try {
            if (streamer != null) streamer.stop();
        } catch (Exception error) { addFailure(error); }
        try {
            if (recorder != null) {
                recorder.finish();
                if (!pendingMp4.renameTo(mp4)) throw new IOException("无法重命名 MP4 文件");
                savedMp4 = mp4;
            } else if (bag != null && failure == null) {
                failure = "录制过短，尚未取得音视频，请重新录制";
            }
        } catch (Exception error) { addFailure(error); }
        try { if (timestamps != null) timestamps.close(); }
        catch (Exception error) { addFailure(error); }
        try { if (decoder != null) decoder.close(); }
        catch (Exception error) { addFailure(error); }
        try {
            if (metadata != null) {
                details.put("finished_utc_ms", System.currentTimeMillis());
                details.put("complete", failure == null && savedMp4 != null);
                details.put("mp4_saved", savedMp4 != null);
                if (recorder != null) {
                    details.put("video_frames_submitted", recorder.videoFrames());
                    details.put("first_audio_pts_us", recorder.firstAudioPtsUs());
                    details.put("audio_timestamp_available", recorder.hasAudioTimestamp());
                }
                if (failure != null) details.put("error", failure);
                writeMetadata();
            }
        } catch (Exception error) { addFailure(error); }
        final File result = savedMp4;
        final String error = failure;
        main.post(() -> listener.onFinished(bag, result, error));
        thread.quitSafely();
    }

    private void addFailure(Exception error) {
        Log.e("RS dual recording", "Finalizing failed", error);
        if (failure == null) failure = error.getMessage();
    }

    private void writeMetadata() throws Exception {
        File temporary = new File(metadata.getPath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(details.toString(2).getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        if (!temporary.renameTo(metadata)) throw new IOException("无法保存同步信息");
    }
}
