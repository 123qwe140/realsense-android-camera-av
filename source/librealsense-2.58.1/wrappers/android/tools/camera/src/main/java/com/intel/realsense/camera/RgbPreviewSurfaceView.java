package com.intel.realsense.camera;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;

import com.intel.realsense.librealsense.Frame;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.GLRsSurfaceView;
import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamProfile;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.YuyDecoder;

/** Display only color; retain at most one waiting frame and render on new frames. */
public class RgbPreviewSurfaceView extends GLRsSurfaceView {
    private final Object mFrameLock = new Object();
    private Frame mPendingFrame;
    private boolean mUploadQueued;
    private boolean mClosed;
    private YuyDecoder mDecoder;
    private boolean mLoggedFirstFrame;

    public RgbPreviewSurfaceView(Context context) {
        super(context);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public RgbPreviewSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void upload(FrameSet frames) {
        try (Frame color = frames.first(StreamType.COLOR)) {
            if (color == null) return;
            synchronized (mFrameLock) {
                if (mClosed) return;
                if (mPendingFrame != null) mPendingFrame.close();
                mPendingFrame = color.clone();
                if (!mUploadQueued) {
                    mUploadQueued = true;
                    queueEvent(this::uploadPendingFrame);
                }
            }
        }
    }

    private void uploadPendingFrame() {
        Frame color;
        synchronized (mFrameLock) {
            color = mPendingFrame;
            mPendingFrame = null;
            mUploadQueued = false;
        }
        if (color == null) return;
        try (Frame retained = color; StreamProfile profile = color.getProfile()) {
            if (profile.getFormat() == StreamFormat.YUYV) {
                if (mDecoder == null) mDecoder = new YuyDecoder();
                Frame decoded = mDecoder.process(color);
                try {
                    super.upload(decoded);
                } finally {
                    // The filter may return its input when no output is available.
                    if (decoded != color) decoded.close();
                }
            } else {
                super.upload(color);
            }
            requestRender();
            if (!mLoggedFirstFrame) {
                Log.i("librs RGB preview", "Color-only preview: " + profile.getFormat()
                        + "; on-demand rendering; latest-frame queue");
                mLoggedFirstFrame = true;
            }
        } catch (RuntimeException error) {
            Log.e("librs RGB preview", "Cannot display color frame", error);
        }
    }

    @Override
    public void clear() {
        synchronized (mFrameLock) {
            if (mPendingFrame != null) mPendingFrame.close();
            mPendingFrame = null;
        }
        queueEvent(() -> {
            RgbPreviewSurfaceView.super.clear();
            requestRender();
        });
    }

    @Override
    public void close() {
        synchronized (mFrameLock) {
            if (mClosed) return;
            mClosed = true;
            if (mPendingFrame != null) mPendingFrame.close();
            mPendingFrame = null;
        }
        queueEvent(() -> {
            if (mDecoder != null) {
                mDecoder.close();
                mDecoder = null;
            }
            RgbPreviewSurfaceView.super.close();
        });
    }
}
