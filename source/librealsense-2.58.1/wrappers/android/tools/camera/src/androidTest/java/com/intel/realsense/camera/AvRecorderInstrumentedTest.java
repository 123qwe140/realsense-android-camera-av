package com.intel.realsense.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.*;

/** Real-device smoke test: hardware AVC/AAC, real microphone, PTS and RGB orientation.
 * Does not validate RealSense USB or depth; those need the connected D435i.
 */
@RunWith(AndroidJUnit4.class)
public class AvRecorderInstrumentedTest {
    @Test public void recordsTwoTracksWithCorrectOrientationAndOverlappingTimestamps() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean permissionAlreadyGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!permissionAlreadyGranted)
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        File output = new File(context.getCacheDir(), "av-encoder-smoke.mp4");
        int width = 320, height = 240;
        byte[] rgb = new byte[width * height * 3];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int offset = (y * width + x) * 3;
            rgb[offset] = (byte) (y < height / 2 ? 255 : 0);
            rgb[offset + 2] = (byte) (y < height / 2 ? 0 : 255);
        }
        AvRecorder recorder = new AvRecorder(context, output, width, height, 30);
        try {
            long startNs = System.nanoTime();
            for (int frame = 0; frame < 90; frame++) {
                long dueNs = startNs + frame * 1_000_000_000L / 30;
                long delayNs = dueNs - System.nanoTime();
                if (delayNs > 0) Thread.sleep(delayNs / 1_000_000, (int) (delayNs % 1_000_000));
                recorder.writeVideo(rgb, width * 3, 3, System.nanoTime());
            }
        } finally { recorder.finish(); }
        assertTrue(output.length() > 1024);
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(output.getPath());
            assertEquals(2, extractor.getTrackCount());
            boolean audioFound = false, videoFound = false;
            long[] firstPts = new long[2];
            long[] lastPts = new long[2];
            for (int track = 0; track < 2; track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("audio/")) audioFound = true;
                if (mime.startsWith("video/")) videoFound = true;
                extractor.selectTrack(track);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long previous = -1;
                int samples = 0;
                while (extractor.getSampleTime() >= 0) {
                    long pts = extractor.getSampleTime();
                    assertTrue("PTS must increase within each track", pts >= previous);
                    if (samples == 0) firstPts[track] = pts;
                    previous = pts;
                    samples++;
                    extractor.advance();
                }
                lastPts[track] = previous;
                assertTrue("Each track must contain samples", samples > 0);
                extractor.unselectTrack(track);
            }
            assertTrue(audioFound && videoFound);
            long overlap = Math.min(lastPts[0], lastPts[1]) - Math.max(firstPts[0], firstPts[1]);
            assertTrue("Audio and video must overlap for at least 2 seconds", overlap > 2_000_000);
        } finally { extractor.release(); }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(output.getPath());
            Bitmap frame = retriever.getFrameAtTime(1_000_000);
            assertNotNull(frame);
            int top = frame.getPixel(frame.getWidth() / 2, frame.getHeight() / 4);
            int bottom = frame.getPixel(frame.getWidth() / 2, frame.getHeight() * 3 / 4);
            assertTrue("Top must be red", Color.red(top) > Color.blue(top) + 100);
            assertTrue("Bottom must be blue", Color.blue(bottom) > Color.red(bottom) + 100);
            frame.recycle();
        } finally { retriever.release(); }
        // Keep the clip for visual/audio inspection; app uninstall/cache cleanup removes it.
    }
}
