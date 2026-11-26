package com.rajibtutorial;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * JS ↔ Java bridge for the flashlight.
 * JS side:  window.AndroidFlash.toggle() / on() / off() / isOn() / isAvailable()
 * Java→JS:  window.onNativeFlashState(boolean)
 * CameraManager.setTorchMode() needs no CAMERA runtime permission.
 */
/** Needs API 23+ (setTorchMode). MainActivity only creates it on M and above. */
@TargetApi(Build.VERSION_CODES.M)
public class FlashBridge {

    private final Context context;
    private final WebView webView;
    private final CameraManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String cameraId = null;
    private volatile boolean on = false;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String id, boolean enabled) {
            if (id.equals(cameraId)) { on = enabled; notifyJs(); }
        }
    };

    public FlashBridge(Context context, WebView webView) {
        this.context = context.getApplicationContext();
        this.webView = webView;
        this.cm = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
        findFlashCamera();
        if (cm != null && cameraId != null) {
            cm.registerTorchCallback(torchCallback, main);
        }
    }

    private void findFlashCamera() {
        if (cm == null) return;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash  = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (flash != null && flash && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    return;
                }
            }
        } catch (CameraAccessException | RuntimeException ignored) { }
    }

    @JavascriptInterface
    public boolean isAvailable() { return cameraId != null; }

    @JavascriptInterface
    public boolean isOn() { return on; }

    @JavascriptInterface
    public boolean toggle() { return set(!on); }

    @JavascriptInterface
    public boolean on() { return set(true); }

    @JavascriptInterface
    public boolean off() { return set(false); }

    private boolean set(boolean enable) {
        if (cm == null || cameraId == null) return false;
        try {
            cm.setTorchMode(cameraId, enable);
            on = enable;
            return true;
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            return false;   // camera in use by another app, etc.
        }
    }

    private void notifyJs() {
        final String js = "window.onNativeFlashState&&window.onNativeFlashState(" + on + ")";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    /** Call from Activity.onDestroy(). */
    public void release() {
        if (cm != null) {
            try { cm.unregisterTorchCallback(torchCallback); } catch (RuntimeException ignored) { }
        }
        if (on) set(false);
    }
}
