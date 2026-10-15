package com.intel.realsense.camera;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** AVC + AAC, with capture timestamps in one System.nanoTime / MONOTONIC domain.
 * Video methods run on the recording worker; audio runs on its own bounded loop.
 */
final class AvRecorder {
    static final int SAMPLE_RATE = 48000;
    private MediaCodec video;
    private MediaCodec audio;
    private AudioRecord microphone;
    private Surface inputSurface;
    private EncoderSurface renderer;
    private MuxerWriter writer;
    private Thread audioThread;
    private volatile boolean recordingAudio;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private long epochNs;
    private long lastVideoPtsUs = -1;
    private volatile long firstAudioPtsUs = -1;
    private volatile boolean timestampAvailable;
    private volatile float audioLevel;
    private long videoFrames;
    private boolean closed;

    AvRecorder(Context context, File output, int width, int height, int fps) throws IOException {
        try {
            MediaFormat videoFormat = MediaFormat.createVideoFormat("video/avc", width, height);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE,
                    Math.max(2_000_000, Math.min(12_000_000, width * height * 6)));
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            video = MediaCodec.createEncoderByType("video/avc");
            if (!video.getCodecInfo().getCapabilitiesForType("video/avc").isFormatSupported(videoFormat))
                throw new IOException("手机不支持此录像分辨率/帧率，请在设置中降低 COLOR 配置");
            video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = video.createInputSurface();
            renderer = new EncoderSurface(inputSurface, width, height);
            video.start();

            MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 1);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            audio = MediaCodec.createEncoderByType("audio/mp4a-latm");
            audio.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audio.start();
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("手机不支持 48kHz 麦克风采集");
            microphone = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum * 2, SAMPLE_RATE / 5 * 2));
            if (microphone.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IOException("无法初始化麦克风，请检查权限和麦克风占用");
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo builtIn = null;
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { builtIn = device; break; }
            }
            if (builtIn == null || !microphone.setPreferredDevice(builtIn))
                throw new IOException("无法选择手机自带麦克风");
            writer = new MuxerWriter(output);
            epochNs = System.nanoTime();
            microphone.startRecording();
            if (microphone.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IOException("麦克风未开始录音");
            recordingAudio = true;
            audioThread = new Thread(this::recordAudio, "RS-phone-microphone");
            audioThread.start();
        } catch (IOException | RuntimeException error) {
            release();
            throw error;
        }
    }

    long epochNs() { return epochNs; }
    long firstAudioPtsUs() { return firstAudioPtsUs; }
    boolean hasAudioTimestamp() { return timestampAvailable; }
    float audioLevel() { return audioLevel; }
    long videoFrames() { return videoFrames; }

    void checkFailure() throws IOException {
        Throwable error = failure.get();
        if (error != null) throw new IOException("录音或编码失败: " + error.getMessage(), error);
    }

    long writeVideo(byte[] data, int stride, int channels, long arrivalNs) throws IOException {
        checkFailure();
        long ptsUs = Math.max(lastVideoPtsUs + 1, Math.max(0, (arrivalNs - epochNs) / 1000));
        // Drain before swap so a slow encoder does not accumulate unconsumed output.
        drain(video, false, false);
        renderer.draw(data, stride, channels, ptsUs * 1000);
        drain(video, false, false);
        lastVideoPtsUs = ptsUs;
        videoFrames++;
        return ptsUs;
    }

    byte[] sourceBuffer(int size) { return renderer.sourceBuffer(size); }

    private void recordAudio() {
        long framesRead = 0;
        long fallbackFirstNs = -1;
        long lastPtsUs = -1;
        long nextPtsUs = 0;
        short[] pcm = new short[1024];
        AudioTimestamp timestamp = new AudioTimestamp();
        try {
            while (recordingAudio) {
                drain(audio, true, false);
                int index = audio.dequeueInputBuffer(10000);
                if (index < 0) continue;
                ByteBuffer input = audio.getInputBuffer(index);
                if (input == null) throw new IOException("AAC input buffer unavailable");
                input.clear();
                int read = microphone.read(pcm, 0, Math.min(pcm.length, input.remaining() / 2),
                        AudioRecord.READ_BLOCKING);
                if (read <= 0) {
                    audio.queueInputBuffer(index, 0, 0, Math.max(0, lastPtsUs + 1), 0);
                    if (!recordingAudio) break;
                    throw new IOException("麦克风读取失败: " + read);
                }
                AudioDeviceInfo routed = microphone.getRoutedDevice();
                if (routed != null && routed.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC)
                    throw new IOException("录音输入已切换，请重新选择手机麦克风");
                if (Build.VERSION.SDK_INT >= 29) {
                    AudioRecordingConfiguration configuration = microphone.getActiveRecordingConfiguration();
                    if (configuration != null && configuration.isClientSilenced())
                        throw new IOException("系统已静音麦克风，请检查麦克风隐私开关或其他应用");
                }
                if (fallbackFirstNs < 0)
                    fallbackFirstNs = System.nanoTime() - read * 1_000_000_000L / SAMPLE_RATE;
                long captureNs = fallbackFirstNs + framesRead * 1_000_000_000L / SAMPLE_RATE;
                if (microphone.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
                        == AudioRecord.SUCCESS) {
                    captureNs = timestamp.nanoTime
                            + (framesRead - timestamp.framePosition) * 1_000_000_000L / SAMPLE_RATE;
                    timestampAvailable = true;
                }
                long ptsUs = Math.max(lastPtsUs + 1, Math.max(0, (captureNs - epochNs) / 1000));
                if (firstAudioPtsUs < 0) firstAudioPtsUs = ptsUs;
                input.order(ByteOrder.LITTLE_ENDIAN);
                int peak = 0;
                for (int i = 0; i < read; i++) {
                    input.putShort(pcm[i]);
                    peak = Math.max(peak, Math.abs((int) pcm[i]));
                }
                audioLevel = peak == 0 ? 0 : (float) Math.max(0, Math.min(1,
                        (20 * Math.log10(peak / 32768d) + 60) / 60));
                audio.queueInputBuffer(index, 0, read * 2, ptsUs, 0);
                framesRead += read;
                lastPtsUs = ptsUs;
                nextPtsUs = ptsUs + read * 1_000_000L / SAMPLE_RATE;
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            boolean eosQueued = false;
            while (!eosQueued && System.nanoTime() < deadline) {
                drain(audio, true, false);
                int index = audio.dequeueInputBuffer(10000);
                if (index >= 0) {
                    audio.queueInputBuffer(index, 0, 0,
                            nextPtsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eosQueued = true;
                }
            }
            if (!eosQueued) throw new IOException("AAC encoder did not accept end of stream");
            drain(audio, true, true);
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    private void drain(MediaCodec codec, boolean isAudio, boolean endOfStream) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            int index = codec.dequeueOutputBuffer(info, endOfStream ? 10000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
                if (System.nanoTime() >= deadline) throw new IOException("Encoder EOS timeout");
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                writer.format(isAudio, codec.getOutputFormat());
            } else if (index >= 0) {
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        ByteBuffer data = codec.getOutputBuffer(index);
                        if (data == null) throw new IOException("Encoder output unavailable");
                        writer.sample(isAudio, data, info);
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    void finish() throws IOException {
        if (closed) return;
        Throwable error = null;
        try {
            recordingAudio = false;
            if (microphone != null) microphone.stop();
            if (audioThread != null) {
                audioThread.join(11000);
                if (audioThread.isAlive()) throw new IOException("麦克风停止超时");
            }
            video.signalEndOfInputStream();
            drain(video, false, true);
            checkFailure();
            writer.finish();
        } catch (Throwable problem) {
            error = problem;
        } finally {
            release();
        }
        if (error != null) throw new IOException("MP4 保存失败: " + error.getMessage(), error);
    }

    private void release() {
        if (closed) return;
        closed = true;
        recordingAudio = false;
        if (microphone != null) {
            try { microphone.stop(); } catch (RuntimeException ignored) { }
            microphone.release();
        }
        if (renderer != null) renderer.close();
        if (inputSurface != null) inputSurface.release();
        if (video != null) { try { video.release(); } catch (RuntimeException ignored) { } }
        if (audio != null) { try { audio.release(); } catch (RuntimeException ignored) { } }
        if (writer != null) writer.close();
    }

    /** Serialize muxer access; hold a bounded number of samples until BOTH tracks exist. */
    private static final class MuxerWriter {
        private final MediaMuxer muxer;
        private int audioTrack = -1;
        private int videoTrack = -1;
        private boolean started;
        private boolean released;
        private boolean stopped;
        private int pendingBytes;
        private int audioSamples;
        private int videoSamples;
        private final List<Sample> pending = new ArrayList<>();

        MuxerWriter(File file) throws IOException {
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        synchronized void format(boolean isAudio, MediaFormat format) throws IOException {
            if (released) throw new IOException("Muxer closed");
            if ((isAudio ? audioTrack : videoTrack) >= 0) throw new IOException("Encoder format changed twice");
            int track = muxer.addTrack(format);
            if (isAudio) audioTrack = track; else videoTrack = track;
            if (audioTrack >= 0 && videoTrack >= 0) {
                muxer.start();
                started = true;
                for (Sample sample : pending) write(sample.audio, sample.data, sample.info);
                pending.clear();
                pendingBytes = 0;
            }
        }

        synchronized void sample(boolean isAudio, ByteBuffer data, MediaCodec.BufferInfo info)
                throws IOException {
            if (released) throw new IOException("Muxer closed");
            if (started) {
                write(isAudio, data, info);
            } else {
                if (pendingBytes + info.size > 8 * 1024 * 1024)
                    throw new IOException("等待音视频轨道超时，编码缓存已满");
                ByteBuffer copy = ByteBuffer.allocateDirect(info.size);
                ByteBuffer range = data.duplicate();
                range.position(info.offset).limit(info.offset + info.size);
                copy.put(range).flip();
                MediaCodec.BufferInfo saved = new MediaCodec.BufferInfo();
                saved.set(0, info.size, info.presentationTimeUs, info.flags);
                pending.add(new Sample(isAudio, copy, saved));
                pendingBytes += info.size;
            }
        }

        private void write(boolean isAudio, ByteBuffer data, MediaCodec.BufferInfo info) {
            muxer.writeSampleData(isAudio ? audioTrack : videoTrack, data, info);
            if (isAudio) audioSamples++; else videoSamples++;
        }

        synchronized void finish() throws IOException {
            if (!started || audioSamples == 0 || videoSamples == 0)
                throw new IOException("录制过短，未生成完整音视频轨道");
            muxer.stop();
            stopped = true;
        }

        synchronized void close() {
            if (released) return;
            released = true;
            if (started && !stopped) { try { muxer.stop(); } catch (RuntimeException ignored) { } }
            muxer.release();
            pending.clear();
        }
    }

    private static final class Sample {
        final boolean audio;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;
        Sample(boolean audio, ByteBuffer data, MediaCodec.BufferInfo info) {
            this.audio = audio;
            this.data = data;
            this.info = info;
        }
    }
}
