package com.intel.realsense.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Shared native UI. Capture accepts a preview View so layouts can be rendered without USB/GL. */
final class CameraUi {
    static final int BG = Color.rgb(16, 19, 25);
    static final int SURFACE = Color.rgb(27, 32, 41);
    static final int BORDER = Color.rgb(46, 54, 65);
    static final int TEXT = Color.rgb(246, 248, 251);
    static final int MUTED = Color.rgb(159, 169, 184);
    static final int MINT = Color.rgb(108, 224, 190);
    static final int RED = Color.rgb(255, 96, 108);
    static final int AMBER = Color.rgb(244, 194, 108);

    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable shape(Context c, int color, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(c, radius));
        if (border) drawable.setStroke(dp(c, 1), BORDER);
        return drawable;
    }

    static LinearLayout column(Context c) {
        LinearLayout view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    static LinearLayout row(Context c) {
        LinearLayout view = new LinearLayout(c);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static TextView text(Context c, String value, float size, int color) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(c, 3), 1);
        return view;
    }

    static void bold(TextView view) { view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); }

    static MaterialButton button(Context c, String label, int icon, boolean primary) {
        MaterialButton button = new MaterialButton(c);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(c, 48));
        button.setMinimumHeight(dp(c, 48));
        button.setCornerRadius(dp(c, 16));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        int foreground = primary ? BG : TEXT;
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{foreground, MUTED}));
        button.setBackgroundTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{primary ? MINT : SURFACE, BORDER}));
        button.setIconTint(android.content.res.ColorStateList.valueOf(foreground));
        button.setIconSize(dp(c, 22));
        button.setIconPadding(dp(c, 8));
        if (icon != 0) button.setIconResource(icon);
        return button;
    }

    static MaterialButton back(Context c) {
        MaterialButton back = button(c, "", R.drawable.ic_av_back, false);
        back.setId(R.id.uiBack);
        back.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        back.setContentDescription("返回");
        back.setPadding(0, 0, 0, 0);
        return back;
    }

    static TextView pill(Context c, String label, int color) {
        TextView view = text(c, label, 12, color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(c, 11), dp(c, 7), dp(c, 11), dp(c, 7));
        view.setBackground(shape(c, SURFACE, 20, true));
        return view;
    }

    static void gap(LinearLayout parent, int height) {
        parent.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(1, dp(parent.getContext(), height)));
    }

    static void horizontalGap(LinearLayout parent, int width) {
        parent.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(dp(parent.getContext(), width), 1));
    }

    static void install(Activity activity, View root) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        activity.getWindow().setStatusBarColor(BG);
        activity.getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 26) activity.getWindow().getDecorView().setSystemUiVisibility(0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        activity.setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    static View home(Context c) {
        LinearLayout root = column(c);
        root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        LinearLayout page = column(c);
        page.setPadding(dp(c, 24), dp(c, 24), dp(c, 24), dp(c, 20));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        TextView brand = text(c, "REALSENSE  /  CAPTURE", 11, MINT);
        brand.setLetterSpacing(.16f);
        bold(brand);
        page.addView(brand);
        gap(page, 12);
        LinearLayout heading = row(c);
        TextView title = text(c, "让记录更立体", 28, TEXT);
        bold(title);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(heading);
        gap(page, 9);
        page.addView(text(c, "画面、声音与深度，一起留住。", 14, MUTED));
        gap(page, 26);
        LinearLayout card = column(c);
        card.setPadding(dp(c, 22), dp(c, 22), dp(c, 22), dp(c, 24));
        card.setBackground(shape(c, SURFACE, 24, true));
        LinearLayout badge = row(c);
        ImageView camera = new ImageView(c);
        camera.setImageResource(R.drawable.ic_av_camera);
        camera.setColorFilter(MINT);
        camera.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        camera.setBackground(shape(c, Color.rgb(32, 54, 51), 18, false));
        badge.addView(camera, new LinearLayout.LayoutParams(dp(c, 64), dp(c, 64)));
        badge.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        TextView status = pill(c, "未连接", AMBER);
        status.setId(R.id.homeStatus);
        badge.addView(status);
        card.addView(badge);
        gap(card, 26);
        TextView device = text(c, "连接你的相机", 23, TEXT);
        device.setId(R.id.homeTitle);
        bold(device);
        card.addView(device);
        gap(card, 10);
        TextView description = text(c, "通过 OTG 数据线连接 D435i，\n在手机弹窗中允许访问 USB 设备。", 14, MUTED);
        description.setId(R.id.homeDescription);
        card.addView(description);
        gap(card, 24);
        MaterialButton action = button(c, "检测相机", R.drawable.ic_av_usb, true);
        action.setId(R.id.homeAction);
        card.addView(action, new LinearLayout.LayoutParams(-1, dp(c, 56)));
        page.addView(card);
        gap(page, 24);
        String[] steps = {"接入相机", "允许 USB 访问", "开始拍摄"};
        LinearLayout flow = row(c);
        for (int i = 0; i < steps.length; i++) {
            LinearLayout step = column(c);
            TextView number = text(c, "0" + (i + 1), 11, MINT);
            number.setLetterSpacing(.12f);
            step.addView(number);
            gap(step, 6);
            step.addView(text(c, steps[i], 12, MUTED));
            flow.addView(step, new LinearLayout.LayoutParams(0, -2, 1));
        }
        page.addView(flow);
        gap(page, 20);
        MaterialButton help = button(c, "连接遇到问题？", R.drawable.ic_av_help, false);
        help.setId(R.id.homeHelp);
        page.addView(help, new LinearLayout.LayoutParams(-1, dp(c, 50)));
        gap(page, 22);
        MaterialButton library = button(c, "打开录像库", R.drawable.ic_av_library, false);
        library.setId(R.id.homeLibrary);
        page.addView(library, new LinearLayout.LayoutParams(-1, dp(c, 56)));
        gap(page, 10);
        TextView note = text(c, "查看、播放与导出之前的拍摄记录", 12, MUTED);
        note.setGravity(Gravity.CENTER);
        page.addView(note);
        page.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 1));
        gap(page, 24);
        TextView version = text(c, "RS Camera AV", 11, MUTED);
        version.setId(R.id.homeVersion);
        version.setGravity(Gravity.CENTER);
        page.addView(version);
        return root;
    }

    static View capture(Context c, View preview, boolean recording, boolean landscape) {
        LinearLayout root = column(c);
        root.setBackgroundColor(BG);
        LinearLayout header = row(c);
        header.setPadding(dp(c, 14), dp(c, 8), dp(c, 18), dp(c, 8));
        header.addView(back(c), new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        horizontalGap(header, 12);
        LinearLayout titles = column(c);
        TextView title = text(c, recording ? "正在录制" : "拍摄", 20, TEXT);
        bold(title);
        titles.addView(title);
        TextView info = text(c, recording ? "准备启动相机与麦克风" : "准备相机画面…", 11, MUTED);
        info.setId(R.id.cameraDetail);
        titles.addView(info);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = pill(c, recording ? "准备中" : "连接中", recording ? AMBER : MUTED);
        state.setId(R.id.cameraStatus);
        header.addView(state);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout finder = new FrameLayout(c);
        finder.setBackgroundColor(Color.BLACK);
        finder.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        finder.addView(new Viewfinder(c), new FrameLayout.LayoutParams(-1, -1));
        TextView hint = text(c, "正在等待相机画面…", 14, MUTED);
        hint.setId(R.id.previewHint);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 20));
        finder.addView(hint, new FrameLayout.LayoutParams(-1, -1));
        TextView rgb = pill(c, "RGB 预览", TEXT);
        FrameLayout.LayoutParams rgbPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        rgbPosition.setMargins(dp(c, 16), dp(c, 16), 0, 0);
        finder.addView(rgb, rgbPosition);
        body.addView(finder, landscape ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView controlScroll = new ScrollView(c);
        controlScroll.setFillViewport(true);
        LinearLayout controls = column(c);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setPadding(dp(c, 20), dp(c, landscape ? 14 : 18), dp(c, 20), dp(c, 18));
        controls.setBackgroundColor(BG);
        controlScroll.addView(controls);
        body.addView(controlScroll, landscape ? new LinearLayout.LayoutParams(dp(c, 228), -1)
                : new LinearLayout.LayoutParams(-1, -2));
        if (recording) {
            TextView timer = text(c, "00:00", landscape ? 26 : 36, TEXT);
            timer.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            timer.setId(R.id.recordingTimer);
            controls.addView(timer);
            gap(controls, 6);
            TextView description = text(c, "正在准备录制…", 12, MUTED);
            description.setId(R.id.recordingStatus);
            description.setGravity(Gravity.CENTER);
            if (landscape) description.setVisibility(View.GONE);
            controls.addView(description);
            gap(controls, landscape ? 2 : 14);
            MicLevelView meter = new MicLevelView(c);
            meter.setId(R.id.micMeter);
            controls.addView(meter, new LinearLayout.LayoutParams(-1, dp(c, 16)));
            gap(controls, landscape ? 4 : 8);
            TextView microphone = text(c, "手机麦克风 · 等待采集", 11, MUTED);
            microphone.setId(R.id.micStatus);
            controls.addView(microphone);
            gap(controls, landscape ? 8 : 16);
        } else {
            LinearLayout chips = row(c);
            chips.setGravity(Gravity.CENTER);
            TextView depth = pill(c, "深度检测中", MUTED);
            depth.setId(R.id.depthStatus);
            chips.addView(depth);
            horizontalGap(chips, 8);
            TextView microphone = pill(c, "手机麦克风", MUTED);
            microphone.setId(R.id.micStatus);
            chips.addView(microphone);
            controls.addView(chips);
            if (landscape) chips.setVisibility(View.GONE);
            if (!landscape) gap(controls, 18);
        }
        LinearLayout actions = row(c);
        actions.setGravity(Gravity.CENTER);
        if (!recording && !landscape) {
            actions.addView(sideAction(c, "录像库", R.drawable.ic_av_library, R.id.libraryButton),
                    new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout record = column(c);
        record.setGravity(Gravity.CENTER);
        MaterialButton shutter = button(c, "", recording ? R.drawable.ic_av_stop : R.drawable.ic_av_record, false);
        shutter.setId(R.id.recordButton);
        shutter.setContentDescription(recording ? "停止并保存录制" : "开始录制视频、声音和深度");
        shutter.setBackgroundTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}}, new int[]{RED, BORDER}));
        shutter.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE));
        shutter.setIconSize(dp(c, 32));
        shutter.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        shutter.setIconPadding(0);
        shutter.setPadding(0, 0, 0, 0);
        shutter.setCornerRadius(dp(c, 44));
        record.addView(shutter, new LinearLayout.LayoutParams(dp(c, 80), dp(c, 80)));
        gap(record, 8);
        TextView actionLabel = text(c, recording ? "停止并保存" : "开始录制", 12, TEXT);
        actionLabel.setId(R.id.shutterLabel);
        record.addView(actionLabel);
        actions.addView(record, new LinearLayout.LayoutParams(recording || landscape ? -2 : 0, -2,
                recording || landscape ? 0 : 1));
        if (!recording && !landscape) {
            actions.addView(sideAction(c, "设置", R.drawable.ic_av_settings, R.id.settingsButton),
                    new LinearLayout.LayoutParams(0, -2, 1));
        }
        controls.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        if (!recording && landscape) {
            gap(controls, 14);
            LinearLayout side = row(c);
            side.addView(sideAction(c, "录像库", R.drawable.ic_av_library, R.id.libraryButton), new LinearLayout.LayoutParams(0, -2, 1));
            side.addView(sideAction(c, "设置", R.drawable.ic_av_settings, R.id.settingsButton), new LinearLayout.LayoutParams(0, -2, 1));
            controls.addView(side);
        }
        gap(controls, 12);
        TextView storage = text(c, recording ? "视频 + 深度数据同时保存" : "保存有声 MP4 与深度数据", 11, MUTED);
        storage.setId(R.id.recordingSize);
        storage.setGravity(Gravity.CENTER);
        controls.addView(storage);
        return root;
    }

    private static View sideAction(Context c, String label, int icon, int id) {
        LinearLayout root = column(c);
        root.setGravity(Gravity.CENTER);
        MaterialButton button = button(c, "", icon, false);
        button.setId(id);
        button.setContentDescription(label);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setPadding(0, 0, 0, 0);
        root.addView(button, new LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)));
        gap(root, 8);
        root.addView(text(c, label, 12, MUTED));
        return root;
    }

    static View library(Context c) {
        LinearLayout root = column(c);
        root.setBackgroundColor(BG);
        LinearLayout header = row(c);
        header.setPadding(dp(c, 16), dp(c, 14), dp(c, 24), dp(c, 14));
        header.addView(back(c), new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        horizontalGap(header, 14);
        LinearLayout heading = column(c);
        TextView title = text(c, "录像库", 24, TEXT);
        bold(title);
        heading.addView(title);
        TextView count = text(c, "正在读取记录…", 12, MUTED);
        count.setId(R.id.libraryCount);
        heading.addView(count);
        header.addView(heading);
        root.addView(header);
        FrameLayout body = new FrameLayout(c);
        ListView list = new ListView(c);
        list.setId(R.id.libraryList);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(dp(c, 20), 0, dp(c, 20), dp(c, 20));
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout empty = column(c);
        empty.setId(R.id.libraryEmpty);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(c, 32), dp(c, 24), dp(c, 32), dp(c, 24));
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ic_av_library);
        icon.setColorFilter(MINT);
        icon.setPadding(dp(c, 24), dp(c, 24), dp(c, 24), dp(c, 24));
        icon.setBackground(shape(c, SURFACE, 28, true));
        empty.addView(icon, new LinearLayout.LayoutParams(dp(c, 96), dp(c, 96)));
        gap(empty, 24);
        TextView emptyTitle = text(c, "留住你的第一段记录", 21, TEXT);
        bold(emptyTitle);
        empty.addView(emptyTitle);
        gap(empty, 10);
        TextView note = text(c, "拍摄完成后，视频和深度文件\n会一起出现在这里。", 14, MUTED);
        note.setGravity(Gravity.CENTER);
        empty.addView(note);
        gap(empty, 24);
        MaterialButton go = button(c, "去拍摄", R.drawable.ic_av_camera, true);
        go.setId(R.id.emptyCapture);
        empty.addView(go, new LinearLayout.LayoutParams(dp(c, 168), dp(c, 52)));
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView footerNote = text(c, "视频与深度数据，按每次拍摄归组", 11, MUTED);
        footerNote.setGravity(Gravity.CENTER);
        footerNote.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 16));
        root.addView(footerNote);
        return root;
    }

    static BottomSheetDialog sheet(Context c, String title, String subtitle, LinearLayout contents) {
        BottomSheetDialog dialog = new BottomSheetDialog(c);
        LinearLayout root = column(c);
        root.setPadding(dp(c, 24), dp(c, 14), dp(c, 24), dp(c, 28));
        root.setBackground(shape(c, SURFACE, 24, false));
        View handle = new View(c);
        handle.setBackground(shape(c, BORDER, 4, false));
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(dp(c, 36), dp(c, 4));
        handleSize.gravity = Gravity.CENTER;
        root.addView(handle, handleSize);
        gap(root, 22);
        TextView heading = text(c, title, 23, TEXT);
        bold(heading);
        root.addView(heading);
        if (subtitle != null) { gap(root, 8); root.addView(text(c, subtitle, 13, MUTED)); }
        gap(root, 20);
        root.addView(contents);
        ScrollView scroll = new ScrollView(c);
        scroll.addView(root);
        dialog.setContentView(scroll);
        return dialog;
    }

    static View settings(Context c) {
        LinearLayout root = column(c);
        root.setBackgroundColor(BG);
        LinearLayout header = row(c);
        header.setPadding(dp(c, 14), dp(c, 10), dp(c, 20), dp(c, 10));
        header.addView(back(c), new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        horizontalGap(header, 14);
        TextView title = text(c, "录制设置", 23, TEXT);
        bold(title);
        header.addView(title);
        root.addView(header);
        ScrollView scroll = new ScrollView(c);
        LinearLayout page = column(c);
        page.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 24));
        TextView tip = text(c, "同时开启「彩色 COLOR」与「深度 DEPTH」。\n画面不稳定时，可先降低分辨率或帧率。", 13, MINT);
        tip.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        tip.setBackground(shape(c, SURFACE, 18, true));
        page.addView(tip);
        gap(page, 18);
        for (int id : new int[]{R.id.configuration_ex_list_view, R.id.settings_ex_list_view, R.id.info_ex_list_view}) {
            ExpandedSettingsList list = new ExpandedSettingsList(c);
            list.setId(id);
            list.setGroupIndicator(null);
            list.setDivider(null);
            list.setChildDivider(null);
            page.addView(list, new LinearLayout.LayoutParams(-1, -2));
            gap(page, 12);
        }
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private static final class ExpandedSettingsList extends android.widget.ExpandableListView {
        ExpandedSettingsList(Context c) { super(c); setNestedScrollingEnabled(false); }
        @Override protected void onMeasure(int width, int height) {
            super.onMeasure(width, MeasureSpec.makeMeasureSpec(0x1fffffff, MeasureSpec.AT_MOST));
        }
    }

    static void connectionHelp(Context c) {
        LinearLayout content = column(c);
        String[] lines = {"01  使用支持数据传输的 OTG 线连接相机。", "02  如果手机提供 OTG 开关，请先开启。",
                "03  在手机弹窗中允许相机和 USB 访问。", "04  连接不稳定时，检查供电和数据线；必要时使用外供电 Hub。"};
        for (String line : lines) { content.addView(text(c, line, 15, TEXT)); gap(content, 18); }
        sheet(c, "连接帮助", "适用于 D435i · 手机需要支持 USB Host / OTG", content).show();
    }

    private static final class Viewfinder extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Viewfinder(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(0x55FFFFFF);
            paint.setStrokeWidth(dp(getContext(), 1));
            float inset = dp(getContext(), 16), length = dp(getContext(), 18);
            float right = getWidth() - inset, bottom = getHeight() - inset;
            for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) {
                float px = x == 0 ? inset : right, py = y == 0 ? inset : bottom;
                canvas.drawLine(px, py, px + (x == 0 ? length : -length), py, paint);
                canvas.drawLine(px, py, px, py + (y == 0 ? length : -length), paint);
            }
        }
    }
}
