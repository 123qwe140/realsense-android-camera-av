package com.intel.realsense.camera;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class RecordingItemTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private void write(String name, String value) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(temporary.getRoot(), name))) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
    @Test public void groupsVideoDepthAndSynchronizationAsOneRecording() throws Exception {
        String base = "20261015_120000_123";
        write(base + ".mp4", "video"); write(base + ".bag", "depth");
        write(base + ".sync.json", "{\"complete\":true}"); write(base + ".frames.csv", "frames");
        List<RecordingItem> items = RecordingItem.load(temporary.getRoot());
        assertEquals(1, items.size());
        assertEquals(4, items.get(0).files().size());
        assertFalse(items.get(0).interrupted);
        assertEquals("视频 MP4  +  深度数据", items.get(0).formats());
    }
    @Test public void partialVideoIsNeverPresentedAsPlayable() throws Exception {
        String base = "20261015_130000_000";
        write(base + ".mp4.part", "incomplete"); write(base + ".bag", "saved depth");
        RecordingItem item = RecordingItem.load(temporary.getRoot()).get(0);
        assertFalse(item.video.isFile());
        assertTrue(item.interrupted);
        assertEquals(2, item.files().size());
    }
    @Test public void legacyBagIsListedAndNewerRecordingsComeFirst() throws Exception {
        write("20261014_100000.bag", "legacy"); write("20261015_100000_001.mp4", "new");
        write("unrelated.txt", "ignore"); write("orphan.sync.json", "{}");
        List<RecordingItem> items = RecordingItem.load(temporary.getRoot());
        assertEquals(2, items.size());
        assertEquals("20261015_100000_001", items.get(0).base);
        assertFalse(items.get(1).interrupted);
    }
    @Test public void corruptMetadataDoesNotHideRecoverableFiles() throws Exception {
        write("20261015_100000_001.bag", "depth"); write("20261015_100000_001.sync.json", "broken");
        assertEquals(1, RecordingItem.load(temporary.getRoot()).size());
    }
}
