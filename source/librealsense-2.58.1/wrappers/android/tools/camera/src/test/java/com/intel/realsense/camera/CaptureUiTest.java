package com.intel.realsense.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ListView;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

/** Actual Android Views; only the USB/GL camera image is replaced by a black placeholder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w393dp-h873dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CaptureUiTest {
    private org.robolectric.android.controller.ActivityController<androidx.appcompat.app.AppCompatActivity> host;
    private Context context() {
        host = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class).setup();
        host.get().setTheme(R.style.AppTheme);
        return host.get();
    }
    @After public void closeHost() { if (host != null) host.pause().stop().destroy(); }

    @Test public void homeRendersChineseGuidanceAndAccessiblePrimaryAction() throws Exception {
        Context context = context();
        View root = CameraUi.home(context);
        layout(root, context, 393, 830);
        assertEquals("连接你的相机", ((TextView) root.findViewById(R.id.homeTitle)).getText().toString());
        assertVisibleTouchTarget(root, R.id.homeAction, context);
        assertVisibleTouchTarget(root, R.id.homeLibrary, context);
        screenshot(root, "home");
    }

    @Test public void portraitCaptureKeepsControlsOutsidePreview() throws Exception {
        Context context = context();
        FrameLayout image = new FrameLayout(context);
        View root = CameraUi.capture(context, image, false, false);
        layout(root, context, 393, 830);
        assertVisibleTouchTarget(root, R.id.recordButton, context);
        assertVisibleTouchTarget(root, R.id.libraryButton, context);
        assertVisibleTouchTarget(root, R.id.settingsButton, context);
        assertFalse(Rect.intersects(bounds(image), bounds(root.findViewById(R.id.recordButton))));
        screenshot(root, "capture-portrait");
    }

    @Test @Config(qualifiers = "w873dp-h393dp-land-xhdpi")
    public void landscapeCaptureHasVisibleShutterAndNavigation() throws Exception {
        Context context = context();
        View root = CameraUi.capture(context, new FrameLayout(context), false, true);
        layout(root, context, 830, 369);
        assertVisibleTouchTarget(root, R.id.recordButton, context);
        assertVisibleTouchTarget(root, R.id.libraryButton, context);
        assertVisibleTouchTarget(root, R.id.settingsButton, context);
        screenshot(root, "capture-landscape");
    }

    @Test public void recordingDisplaysTimerMeterAndUnambiguousStopAction() throws Exception {
        Context context = context();
        View root = CameraUi.capture(context, new FrameLayout(context), true, false);
        ((TextView) root.findViewById(R.id.recordingTimer)).setText("01:28");
        ((TextView) root.findViewById(R.id.recordingStatus)).setText("正在录制 · 视频与深度同时保存");
        ((MicLevelView) root.findViewById(R.id.micMeter)).setLevel(.6f);
        layout(root, context, 393, 830);
        assertVisibleTouchTarget(root, R.id.recordButton, context);
        assertEquals("停止并保存录制", root.findViewById(R.id.recordButton).getContentDescription().toString());
        screenshot(root, "recording");
    }

    @Test public void emptyLibraryShowsCaptureAction() throws Exception {
        Context context = context();
        View root = CameraUi.library(context);
        root.findViewById(R.id.libraryEmpty).setVisibility(View.VISIBLE);
        root.findViewById(R.id.libraryList).setVisibility(View.GONE);
        layout(root, context, 393, 830);
        assertVisibleTouchTarget(root, R.id.emptyCapture, context);
        screenshot(root, "library-empty");
    }

    @Test @Config(qualifiers = "w873dp-h393dp-land-xhdpi")
    public void landscapeRecordingStopRemainsVisible() throws Exception {
        Context context = context();
        View root = CameraUi.capture(context, new FrameLayout(context), true, true);
        layout(root, context, 830, 369);
        assertVisibleTouchTarget(root, R.id.recordButton, context);
        screenshot(root, "recording-landscape");
    }

    @Test public void libraryGroupsActualSiblingFilesIntoCards() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File folder = new File(app.getExternalFilesDir(null), "realsense/video");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        File first = new File(folder, "20261015_120000_001.bag");
        File second = new File(folder, "20261014_093000_002.bag");
        assertTrue(first.createNewFile());
        assertTrue(second.createNewFile());
        org.robolectric.android.controller.ActivityController<RecordingLibraryActivity> controller = null;
        try {
            controller = Robolectric.buildActivity(RecordingLibraryActivity.class).setup();
            RecordingLibraryActivity activity = controller.get();
            ListView list = activity.findViewById(R.id.libraryList);
            for (int attempt = 0; attempt < 100 && list.getAdapter().getCount() < 4; attempt++) {
                Thread.sleep(20);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            }
            assertEquals(4, list.getAdapter().getCount());
            View root = ((FrameLayout) activity.findViewById(android.R.id.content)).getChildAt(0);
            layout(root, activity, 393, 830);
            screenshot(root, "library-recordings");
            View card = ((android.view.ViewGroup) list.getChildAt(1)).getChildAt(0);
            card.performClick();
            android.app.Dialog details = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(details.isShowing());
            View depthAction = findText(details.getWindow().getDecorView(), "查看深度数据");
            assertNotNull(depthAction);
            depthAction.performClick();
            android.content.Intent depthIntent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(PlaybackActivity.class.getName(), depthIntent.getComponent().getClassName());
            assertEquals(first.getPath(), depthIntent.getStringExtra(activity.getString(R.string.intent_extra_file_path)));
            card.performClick();
            details = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            findText(details.getWindow().getDecorView(), "导出文件 / 全部打包").performClick();
            androidx.appcompat.app.AlertDialog choice = (androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals(2, choice.getListView().getAdapter().getCount());
            choice.getListView().performItemClick(null, 0, 0);
            android.content.Intent exportIntent = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, exportIntent.getAction());
            assertEquals("application/zip", exportIntent.getType());
            assertEquals("20261015_120000_001.zip", exportIntent.getStringExtra(android.content.Intent.EXTRA_TITLE));
        } finally {
            if (controller != null) controller.pause().stop().destroy();
            assertTrue(first.delete());
            assertTrue(second.delete());
        }
    }

    @Test public void largeFontSmallPhoneKeepsPrimaryActionReachable() throws Exception {
        Context context = context();
        android.content.res.Configuration configuration = new android.content.res.Configuration(context.getResources().getConfiguration());
        configuration.fontScale = 1.3f;
        Context enlarged = new ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.AppTheme);
        View root = CameraUi.capture(enlarged, new FrameLayout(enlarged), false, false);
        layout(root, enlarged, 360, 720);
        assertVisibleTouchTarget(root, R.id.recordButton, enlarged);
        screenshot(root, "capture-large-font");
    }

    private void layout(View root, Context context, int width, int height) {
        if (host != null && root.getParent() == null) {
            host.get().setContentView(root);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        int w = CameraUi.dp(context, width), h = CameraUi.dp(context, height);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
    }
    private static Rect bounds(View view) {
        int x = 0, y = 0;
        View current = view;
        while (current != null) {
            x += current.getLeft() - current.getScrollX();
            y += current.getTop() - current.getScrollY();
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return new Rect(x, y, x + view.getWidth(), y + view.getHeight());
    }
    private static void assertVisibleTouchTarget(View root, int id, Context context) {
        View view = root.findViewById(id);
        assertNotNull(view);
        Rect rect = bounds(view);
        assertTrue("Touch target too small", rect.width() >= CameraUi.dp(context, 48) && rect.height() >= CameraUi.dp(context, 48));
        assertTrue("Control extends outside screen: " + rect, rect.left >= 0 && rect.top >= 0
                && rect.right <= root.getWidth() && rect.bottom <= root.getHeight());
    }
    private static View findText(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().equals(text)) return root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findText(group.getChildAt(i), text);
                if (match != null) return match;
            }
        }
        return null;
    }
    private static void screenshot(View root, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        View shutter = root.findViewById(R.id.recordButton);
        if (shutter != null) {
            Rect center = bounds(shutter);
            int pixel = bitmap.getPixel(center.centerX(), center.centerY());
            assertTrue("Record/stop glyph must be visible", android.graphics.Color.red(pixel) > 220
                    && android.graphics.Color.green(pixel) > 220 && android.graphics.Color.blue(pixel) > 220);
        }
        File directory = new File(System.getProperty("ui.screenshots.dir", "build/ui-screenshots"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }
}
