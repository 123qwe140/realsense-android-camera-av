package com.intel.realsense.camera;

import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamType;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CaptureStreamPolicyTest {
    @Test public void stableModeKeepsOnlyColorAndDepth() {
        assertTrue(CaptureStreamPolicy.includesStream(StreamType.COLOR, true));
        assertTrue(CaptureStreamPolicy.includesStream(StreamType.DEPTH, true));
        assertFalse(CaptureStreamPolicy.includesStream(StreamType.ACCEL, true));
        assertFalse(CaptureStreamPolicy.includesStream(StreamType.GYRO, true));
        assertFalse(CaptureStreamPolicy.includesStream(StreamType.INFRARED, true));
        assertTrue(CaptureStreamPolicy.includesStream(StreamType.GYRO, false));
    }

    @Test public void stableModeAcceptsLowLoadRgbAndDepthProfiles() {
        assertTrue(CaptureStreamPolicy.isStableProfile(StreamType.COLOR, StreamFormat.YUYV, 640, 480, 15));
        assertTrue(CaptureStreamPolicy.isStableProfile(StreamType.COLOR, StreamFormat.RGB8, 640, 480, 15));
        assertTrue(CaptureStreamPolicy.isStableProfile(StreamType.DEPTH, StreamFormat.Z16, 640, 480, 15));
        assertFalse(CaptureStreamPolicy.isStableProfile(StreamType.COLOR, StreamFormat.YUYV, 1280, 720, 15));
        assertFalse(CaptureStreamPolicy.isStableProfile(StreamType.DEPTH, StreamFormat.Z16, 640, 480, 30));
        assertFalse(CaptureStreamPolicy.isStableProfile(StreamType.COLOR, StreamFormat.Z16, 640, 480, 15));
    }
}
