package com.intel.realsense.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.intel.realsense.librealsense.CameraInfo;
import com.intel.realsense.librealsense.Config;
import com.intel.realsense.librealsense.Device;
import com.intel.realsense.librealsense.DeviceList;
import com.intel.realsense.librealsense.Extension;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.MotionStreamProfile;
import com.intel.realsense.librealsense.Option;
import com.intel.realsense.librealsense.Pipeline;
import com.intel.realsense.librealsense.PipelineProfile;
import com.intel.realsense.librealsense.ProductLine;
import com.intel.realsense.librealsense.RsContext;
import com.intel.realsense.librealsense.Sensor;
import com.intel.realsense.camera.RecordingActivity;
import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamProfile;
import com.intel.realsense.librealsense.VideoStreamProfile;

import java.util.List;
import java.util.Map;

public class Streamer {
    private static final String TAG = "librs camera streamer";
    static final String STABLE_CAPTURE_MODE = "stable_capture_mode";
    // D435i can take longer than three seconds to produce its first frames after
    // USB negotiation or a warm reconnect, especially on Android phones.
    private static final int DEFAULT_TIMEOUT = 10000;
    private static final int L500_TIMEOUT = 15000;

    interface Listener{
        void config(Config config);
        void onFrameset(FrameSet frameSet);
        default void onError(Exception error) { }
    }

    private final Context mContext;
    private final boolean mLoadConfig;

    private boolean mIsStreaming = false;
    private final Handler mHandler = new Handler();
    private final Listener mListener;

    private Pipeline mPipeline;
    private String mRecordFilePath;
    private volatile long mLastFramesetAtMs;

    public Streamer(Context context, boolean loadConfig, Listener listener){
        mContext = context;
        mListener = listener;
        mLoadConfig = loadConfig;
    }

    private Runnable mStreaming = new Runnable() {
        @Override
        public void run() {
            if (!mIsStreaming) return;
            try {
                try(FrameSet frames = mPipeline.waitForFrames()) {
                    mLastFramesetAtMs = SystemClock.elapsedRealtime();
                    mListener.onFrameset(frames);
                }
                if (mIsStreaming) mHandler.post(mStreaming);
            }
            catch (Exception e) {
                Log.e(TAG, "streaming, error: " + e.getMessage());
                stop();
                mListener.onError(e);
            }
        }
    };

    private int getFirstFrameTimeout() {
        try(RsContext ctx = new RsContext()){
            try(DeviceList devices = ctx.queryDevices()) {
                if (devices.getDeviceCount() == 0) {
                    return DEFAULT_TIMEOUT;
                }
                try (Device device = devices.createDevice(0)) {
                    if(device == null)
                        return DEFAULT_TIMEOUT;
                    ProductLine pl = ProductLine.valueOf(device.getInfo(CameraInfo.PRODUCT_LINE));
                    return pl == ProductLine.L500 ? L500_TIMEOUT : DEFAULT_TIMEOUT;
                }
            }
        }
    }

    private int configStream(Config config) {
        int numStreams = 0;
        config.disableAllStreams();

        try(RsContext ctx = new RsContext()){
            String pid;
            Map<Integer, List<StreamProfile>> profilesMap;
            try(DeviceList devices = ctx.queryDevices()) {
                if (devices.getDeviceCount() == 0) {
                    return 0;
                }
                try (Device device = devices.createDevice(0)) {
                    if(device == null){
                        Log.e(TAG, "failed to create device");
                        return 0;
                    }
                    pid = device.getInfo(CameraInfo.PRODUCT_ID);
                    profilesMap = SettingsActivity.createProfilesMap(device);

                    SharedPreferences sharedPref = mContext.getSharedPreferences(mContext.getString(R.string.app_settings), Context.MODE_PRIVATE);
                    boolean stableMode = sharedPref.getBoolean(STABLE_CAPTURE_MODE, true);
                    SharedPreferences.Editor stableEdits = sharedPref.edit();
                    boolean changedStableSettings = false;

                    for(Map.Entry e : profilesMap.entrySet()){
                        List<StreamProfile> profiles = (List<StreamProfile>) e.getValue();
                        StreamProfile p = profiles.get(0);
                        String enabledKey = SettingsActivity.getEnabledDeviceConfigString(pid, p.getType(), p.getIndex());
                        String indexKey = SettingsActivity.getIndexdDeviceConfigString(pid, p.getType(), p.getIndex());
                        boolean wasConfigured = sharedPref.contains(enabledKey);
                        boolean enabled = sharedPref.getBoolean(enabledKey, false);
                        int index = sharedPref.getInt(indexKey, 0);
                        if (stableMode) {
                            boolean required = CaptureStreamPolicy.includesStream(p.getType(), true);
                            if (!required) {
                                if (enabled) {
                                    stableEdits.putBoolean(enabledKey, false);
                                    changedStableSettings = true;
                                }
                                continue;
                            }
                            if (!enabled || !wasConfigured) {
                                enabled = true;
                                stableEdits.putBoolean(enabledKey, true);
                                changedStableSettings = true;
                            }
                            int stableIndex = findStableProfileIndex(profiles, p.getType(), index);
                            if (stableIndex >= 0 && stableIndex != index) {
                                index = stableIndex;
                                stableEdits.putInt(indexKey, index);
                                changedStableSettings = true;
                                Log.i(TAG, "stable capture selected " + p.getType() + " profile index " + index);
                            } else if (stableIndex < 0) {
                                Log.w(TAG, "no <=640x480@15 stable profile for " + p.getType()
                                        + "; using configured profile index " + index);
                            }
                        }
                        if(!enabled)
                            continue;
                        if(index == -1 || index >= profiles.size())
                            throw new IllegalArgumentException("Failed to resolve config");
                        StreamProfile sp = profiles.get(index);
                        if(p.is(Extension.VIDEO_PROFILE)){
                            VideoStreamProfile vsp = sp.as(Extension.VIDEO_PROFILE);
                            config.enableStream(vsp.getType(), vsp.getIndex(), vsp.getWidth(), vsp.getHeight(), vsp.getFormat(), vsp.getFrameRate());
                            numStreams++;
                        }
                        if(p.is(Extension.MOTION_PROFILE)){
                            MotionStreamProfile msp = sp.as(Extension.MOTION_PROFILE);
                            config.enableStream(msp.getType(), msp.getIndex(), 0, 0, msp.getFormat(), msp.getFrameRate());
                            numStreams++;
                        }
                    }
                    if (changedStableSettings) stableEdits.apply();
                }
            }
        }

        return numStreams;
    }

    private int findStableProfileIndex(List<StreamProfile> profiles,
                                       com.intel.realsense.librealsense.StreamType type,
                                       int preferredIndex) {
        if (isStableVideoProfile(profiles.get(Math.max(0, Math.min(preferredIndex, profiles.size() - 1))), type))
            return Math.max(0, Math.min(preferredIndex, profiles.size() - 1));

        int bestIndex = -1;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < profiles.size(); i++) {
            StreamProfile profile = profiles.get(i);
            if (!isStableVideoProfile(profile, type)) continue;
            try (VideoStreamProfile video = profile.as(Extension.VIDEO_PROFILE)) {
                int formatScore = type == com.intel.realsense.librealsense.StreamType.COLOR
                        ? (video.getFormat() == StreamFormat.YUYV ? 2 : 1) : 0;
                long score = (long) video.getFrameRate() * 1_000_000L
                        + (long) video.getWidth() * video.getHeight() * 10L + formatScore;
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }
        }
        return bestIndex;
    }

    private boolean isStableVideoProfile(StreamProfile profile,
                                         com.intel.realsense.librealsense.StreamType type) {
        if (!profile.is(Extension.VIDEO_PROFILE)) return false;
        try (VideoStreamProfile video = profile.as(Extension.VIDEO_PROFILE)) {
            return CaptureStreamPolicy.isStableProfile(type, video.getFormat(),
                    video.getWidth(), video.getHeight(), video.getFrameRate());
        }
    }

    long getLastFramesetAtMs() { return mLastFramesetAtMs; }

    void configAndStart() throws Exception {
        try(Config config = new Config()){
            boolean defaultConfig = true;

            //Enable recording to file if a path is provided
            if(mRecordFilePath != null)
                config.enableRecordToFile(mRecordFilePath);
            if(mLoadConfig) {
                if (configStream(config) > 0)
                    defaultConfig = false;

            }
            if(mListener != null)
                mListener.config(config);
            // try statement needed here to release resources allocated by the Pipeline:start() method
            try (PipelineProfile pp = mPipeline.start(config)){

                // if device runs on default configuration, get active stream profiles and record in settings
                if (defaultConfig) {
                    List<StreamProfile> activeProfiles = mPipeline.getActiveStreams();
                    SharedPreferences sharedPref = mContext.getSharedPreferences(mContext.getString(R.string.app_settings), Context.MODE_PRIVATE);
                    Device device = pp.getDevice();

                    for (StreamProfile sp : activeProfiles) {
                        String msg = "active profile: " + sp.getType() + "," + sp.getIndex() + "," + sp.getFormat().toString() + "," + sp.getFrameRate() + " fps";

                        if (sp.is(Extension.VIDEO_PROFILE)) {
                            VideoStreamProfile vp = sp.as(Extension.VIDEO_PROFILE);

                            if (vp != null) {
                                msg += "," + vp.getWidth() + "x" + vp.getHeight();
                            }
                        }

                        Log.d(TAG, msg);

                        // turn on the active profile in settings
                        SettingsActivity.setProfileSetting(sharedPref, device, sp, true);
                    }
                }
            }
        }
    }
    public void setRecordFilePath(String path){
        Log.d(TAG, "setRecordFilePath: entry, path: " + path);
        mRecordFilePath = path;
    }

    public synchronized void start() throws Exception {
        if(mIsStreaming)
            return;
        try{
            mPipeline = new Pipeline();
            Log.d(TAG, "try start streaming");
            configAndStart();
            int firstFrameTimeOut = getFirstFrameTimeout();
            try(FrameSet frames = mPipeline.waitForFrames(firstFrameTimeOut)){} // w/a for l500
            mIsStreaming = true;
            mHandler.post(mStreaming);
            Log.d(TAG, "streaming started successfully");
        } catch (Exception e) {
            Log.e(TAG, "failed to start streaming: " + e.getMessage());
            mPipeline.close();
            throw e;
        }
    }

    public synchronized void stop() {
        if(!mIsStreaming)
            return;
        try {
            Log.d(TAG, "try stop streaming");
            mIsStreaming = false;
            mHandler.removeCallbacks(mStreaming);
            mPipeline.stop();
            Log.d(TAG, "streaming stopped successfully");
        }  catch (Exception e) {
            Log.w(TAG, "failed to stop streaming", e);
        } finally {
            if (mPipeline != null) {
                mPipeline.close();
                mPipeline = null;
            }
        }
    }
}
