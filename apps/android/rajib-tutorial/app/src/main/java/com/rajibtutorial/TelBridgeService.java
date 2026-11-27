package com.rajibtutorial;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Telephone call bridge" accessibility service (Phone B). Android lets an app keep using the microphone
 * during a SIM call only while it has an enabled accessibility service. This one also presses the Speaker
 * button of the phone's own call screen when a bridge starts. It reads nothing else.
 */
public class TelBridgeService extends AccessibilityService {
    private static volatile TelBridgeService self;
    private final Handler h = new Handler(Looper.getMainLooper());

    static TelBridgeService get() { return self; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        self = this;
        RelayCall.updateNotice(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { /* nothing is monitored */ }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent i) {
        self = null;
        return super.onUnbind(i);
    }

    @Override
    public void onDestroy() {
        self = null;
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Switches the running SIM call to loudspeaker (a few attempts while the call screen is appearing). */
    void speakerOn() { trySpeaker(0); }

    @SuppressWarnings("deprecation")
    private void trySpeaker(final int attempt) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isSpeakerphoneOn()) return;
        if (pressSpeaker()) return;
        if (attempt < 3) { h.postDelayed(() -> trySpeaker(attempt + 1), 500); return; }
        try { if (am != null) am.setSpeakerphoneOn(true); } catch (Throwable ignored) { }
        Toast.makeText(this, "Speaker button not found. Tap Speaker on the call screen.", Toast.LENGTH_LONG).show();
    }

    private boolean isCallScreen(CharSequence pkgName) {
        if (pkgName == null) return false;
        String pkg = pkgName.toString().toLowerCase(Locale.US);
        if (pkg.contains("dialer") || pkg.contains("incallui") || pkg.contains("telecom") || pkg.contains("phone")) return true;
        if (Build.VERSION.SDK_INT >= 23) {
            TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            return tm != null && pkg.equalsIgnoreCase(tm.getDefaultDialerPackage());
        }
        return false;
    }

    private boolean pressSpeaker() {
        try {
            List<AccessibilityNodeInfo> roots = new ArrayList<>();
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) roots.add(active);
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) roots.add(r);
            }
            for (AccessibilityNodeInfo r : roots) {
                if (isCallScreen(r.getPackageName()) && find(r, 0)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private boolean find(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 25) return false;
        String label = (String.valueOf(n.getText()) + " " + n.getContentDescription()).toLowerCase(Locale.US);
        if (label.contains("speaker") || label.contains("স্পিকার")) {
            AccessibilityNodeInfo t = n;
            for (int up = 0; t != null && up < 3; up++, t = t.getParent()) {
                if (t.isChecked() || t.isSelected()) return true;          // already on
                if (t.isClickable()) return t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) if (find(n.getChild(i), depth + 1)) return true;
        return false;
    }
}
