package com.intel.realsense.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Meter driven by real PCM peak amplitude; no decorative/random animation. */
public final class MicLevelView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float level;
    public MicLevelView(Context context) { super(context); setContentDescription("麦克风音量，尚未采集"); }
    public void setLevel(float value) {
        level = Math.max(0, Math.min(1, value));
        setContentDescription("麦克风音量 " + Math.round(level * 100) + "%");
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        int bars = 24;
        float gap = CameraUi.dp(getContext(), 3);
        float width = (getWidth() - (bars - 1) * gap) / bars;
        int active = Math.round(level * bars);
        for (int i = 0; i < bars; i++) {
            paint.setColor(i >= active ? CameraUi.BORDER : i > 20 ? CameraUi.RED : i > 17 ? CameraUi.AMBER : CameraUi.MINT);
            canvas.drawRoundRect(i * (width + gap), 0, i * (width + gap) + width, getHeight(), 2, 2, paint);
        }
    }
}
