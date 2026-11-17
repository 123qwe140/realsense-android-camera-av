package com.intel.realsense.camera;

import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamType;

/** Stream selection rules for capturing RGB and depth reliably on phone USB hosts. */
final class CaptureStreamPolicy {
    static final int MAX_STABLE_WIDTH = 640;
    static final int MAX_STABLE_HEIGHT = 480;
    static final int MAX_STABLE_FPS = 15;

    private CaptureStreamPolicy() { }

    static boolean includesStream(StreamType type, boolean stableMode) {
        if (!stableMode) return true;
        return type == StreamType.COLOR || type == StreamType.DEPTH;
    }

    static boolean isStableProfile(StreamType type, StreamFormat format,
                                   int width, int height, int fps) {
        if (width <= 0 || height <= 0 || fps <= 0
                || width > MAX_STABLE_WIDTH || height > MAX_STABLE_HEIGHT
                || fps > MAX_STABLE_FPS) return false;
        if (type == StreamType.COLOR) {
            return format == StreamFormat.YUYV || format == StreamFormat.RGB8
                    || format == StreamFormat.RGBA8;
        }
        return type == StreamType.DEPTH && format == StreamFormat.Z16;
    }
}
