package com.intel.realsense.camera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.intel.realsense.librealsense.CameraInfo;
import com.intel.realsense.librealsense.Device;
import com.intel.realsense.librealsense.DeviceList;
import com.intel.realsense.librealsense.DeviceListener;
import com.intel.realsense.librealsense.Extension;
import com.intel.realsense.librealsense.ProductLine;
import com.intel.realsense.librealsense.RsContext;

import java.util.HashMap;
import java.util.Map;

public class DetachedActivity extends AppCompatActivity {
    private static final int REQUEST_CAMERA = 1;
    private final Map<ProductLine, String> mMinimalFirmwares = new HashMap<>();
    private RsContext rsContext;
    private boolean resumed;
    private boolean cameraReady;
    private boolean denied;
    private boolean listening;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mUpdating;
    private MaterialButton action;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        CameraUi.install(this, CameraUi.home(this));
        mMinimalFirmwares.put(ProductLine.D400, "5.10.0.0");
        denied = state != null && state.getBoolean("permissionDenied");
        action = findViewById(R.id.homeAction);
        action.setOnClickListener(view -> primaryAction());
        findViewById(R.id.homeHelp).setOnClickListener(view -> CameraUi.connectionHelp(this));
        findViewById(R.id.homeLibrary).setOnClickListener(view -> startActivity(new Intent(this, RecordingLibraryActivity.class)));
        try {
            ((TextView) findViewById(R.id.homeVersion)).setText("RS Camera AV  ·  "
                    + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void primaryAction() {
        if (!hasCameraPermission()) {
            if (denied && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } else ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        refresh();
        if (!cameraReady || rsContext == null) return;
        try (DeviceList devices = rsContext.queryDevices()) {
            if (devices.getDeviceCount() == 0) { refresh(); return; }
            try (Device device = devices.createDevice(0)) {
                if (!validateFwVersion(device)) return;
            }
            Intent preview = new Intent(this, PreviewActivity.class);
            preview.putExtra("keepalive", true);
            startActivity(preview);
        } catch (Exception error) { showState("连接异常", "暂时无法访问相机", "请重新插拔相机后重试，或查看连接帮助。", CameraUi.AMBER); }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
    }

    private void refresh() {
        if (!resumed || isFinishing() || mUpdating) return;
        cameraReady = false;
        if (!hasCameraPermission()) {
            showState("待授权", "允许访问相机", "应用需要相机权限来连接 D435i。\n录音权限会在首次拍摄时申请。", CameraUi.AMBER);
            action.setText(denied && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
                    ? "前往权限设置" : "允许相机访问");
            return;
        }
        try {
            RsContext.init(getApplicationContext());
            if (rsContext == null) rsContext = new RsContext();
            if (!listening) { rsContext.setDevicesChangedCallback(deviceListener); listening = true; }
            try (DeviceList devices = rsContext.queryDevices()) {
                if (devices.getDeviceCount() == 0) {
                    boolean waiting = false;
                    UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
                    for (UsbDevice device : usb.getDeviceList().values())
                        if (device.getVendorId() == 0x8086 && !usb.hasPermission(device)) waiting = true;
                    showState(waiting ? "等待授权" : "未连接", waiting ? "请允许 USB 访问" : "连接你的相机",
                            waiting ? "请在手机弹窗中允许访问相机。\n没有看到弹窗时，可重新插拔数据线。"
                                    : "通过 OTG 数据线连接 D435i，\n在手机弹窗中允许访问 USB 设备。", CameraUi.AMBER);
                    action.setText("重新检测相机");
                    return;
                }
                try (Device device = devices.createDevice(0)) {
                    if (device.is(Extension.UPDATE_DEVICE)) {
                        showState("更新模式", "相机正在更新模式", "请完成固件更新后再开始拍摄。", CameraUi.AMBER);
                        FirmwareUpdateProgressDialog progress = new FirmwareUpdateProgressDialog();
                        progress.setCancelable(false);
                        progress.show(getFragmentManager(), "firmwareProgress");
                        mUpdating = true;
                        return;
                    }
                    String name = device.getInfo(CameraInfo.NAME).replace("Intel RealSense ", "").replace("Intel(R) RealSense(TM) ", "");
                    String usbMode = device.supportsInfo(CameraInfo.USB_TYPE_DESCRIPTOR)
                            ? "USB " + device.getInfo(CameraInfo.USB_TYPE_DESCRIPTOR) : "USB 已连接";
                    cameraReady = true;
                    showState("已连接", name, usbMode + "\n准备好后，开始记录画面、声音与深度。", CameraUi.MINT);
                    action.setText("进入拍摄");
                }
            }
        } catch (Exception | LinkageError error) {
            Log.e("RS connection", "Cannot initialize camera", error);
            showState("连接异常", "暂时无法连接相机", "请确认 USB 授权、OTG 和供电状态，\n然后重新检测。", CameraUi.AMBER);
            action.setText("重新检测相机");
        }
    }

    private void showState(String status, String title, String description, int color) {
        TextView badge = findViewById(R.id.homeStatus);
        badge.setText(status);
        badge.setTextColor(color);
        ((TextView) findViewById(R.id.homeTitle)).setText(title);
        ((TextView) findViewById(R.id.homeDescription)).setText(description);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == REQUEST_CAMERA) { denied = !hasCameraPermission(); refresh(); }
    }

    @Override protected void onPause() {
        resumed = false;
        if (rsContext != null) rsContext.removeDevicesChangedCallback();
        listening = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (rsContext != null) rsContext.close();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("permissionDenied", denied);
        super.onSaveInstanceState(state);
    }

    private final DeviceListener deviceListener = new DeviceListener() {
        @Override public void onDeviceAttach() { main.post(() -> refresh()); }
        @Override public void onDeviceDetach() { main.post(() -> refresh()); }
    };

    public void onFwUpdateStatus(boolean success) {
        mUpdating = false;
        runOnUiThread(this::refresh);
    }

    private boolean validateFwVersion(Device device){
        final String currFw = device.getInfo(CameraInfo.FIRMWARE_VERSION).split("\n")[0];
        final ProductLine pl = ProductLine.valueOf(device.getInfo(CameraInfo.PRODUCT_LINE));
        if(mMinimalFirmwares.containsKey(pl)){
            final String minimalFw = mMinimalFirmwares.get(pl);
            if(!compareFwVersion(currFw, minimalFw)){
                FirmwareUpdateDialog fud = new FirmwareUpdateDialog();
                Bundle bundle = new Bundle();
                bundle.putBoolean(getString(R.string.firmware_update_required), true);
                fud.setArguments(bundle);
                fud.show(getFragmentManager(), null);
                return false;
            }
        }

        SharedPreferences sharedPref = getSharedPreferences(getString(R.string.app_settings), Context.MODE_PRIVATE);
        boolean showUpdateMessage = sharedPref.getBoolean(getString(R.string.show_update_firmware), true);
        if (!showUpdateMessage || !device.supportsInfo(CameraInfo.RECOMMENDED_FIRMWARE_VERSION))
            return true;

        final String recommendedFw = device.getInfo(CameraInfo.RECOMMENDED_FIRMWARE_VERSION);
        if(!compareFwVersion(currFw, recommendedFw)){
            FirmwareUpdateDialog fud = new FirmwareUpdateDialog();
            fud.show(getFragmentManager(), null);
            return false;
        }
        return true;
    }

    private boolean compareFwVersion(String currFw, String otherFw){
        String[] sFw = currFw.split("\\.");
        String[] sRecFw = otherFw.split("\\.");
        for (int i = 0; i < sRecFw.length; i++) {
            if (Integer.parseInt(sFw[i]) > Integer.parseInt(sRecFw[i]))
                break;
            if (Integer.parseInt(sFw[i]) < Integer.parseInt(sRecFw[i])) {
                return false;
            }
        }
        return true;
    }

}
