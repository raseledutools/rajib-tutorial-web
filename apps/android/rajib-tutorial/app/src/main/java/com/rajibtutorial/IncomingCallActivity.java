package com.rajibtutorial;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WhatsApp-style full-screen incoming call. Started by the call notification's full-screen intent
 * (FCM push, app closed / phone locked) – it shows over the lock screen, rings and vibrates.
 * Accept → opens the WebRTC call page in the caller's private room; Decline → tells the caller.
 */
public class IncomingCallActivity extends Activity {
    static final String EXTRA_DOC = "doc", EXTRA_FROM = "from", EXTRA_NAME = "name", EXTRA_ROOM = "room",
        EXTRA_VIDEO = "video", EXTRA_TO = "to", EXTRA_ACTION = "action", EXTRA_NID = "nid";
    static final String ACT_ACCEPT = "accept", ACT_DECLINE = "decline";
    private static final long RING_MS = 40000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private String doc = "", from = "", name = "", room = "", to = "";
    private boolean video = false, done = false;
    private int nid = 0;
    private Ringtone ringtone;
    private Vibrator vib;

    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (done) return;
            Store.async(() -> {
                Fs.Doc d = Fs.get(doc);
                return d == null ? 0L : Fs.num(d.data, "ans");
            }, (Long ans, Exception e) -> {
                if (done || e != null || ans == null) return;
                if (ans != 0) { closeQuietly(); return; }      // caller hung up (3), or answered on another device (1/2)
                h.postDelayed(watch, 2000);
            });
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.init(this);
        // show over the lock screen and wake the display
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().setStatusBarColor(0xFF0F1B21);
        getWindow().setNavigationBarColor(0xFF0F1B21);

        Intent i = getIntent();
        doc = nz(i.getStringExtra(EXTRA_DOC));
        from = nz(i.getStringExtra(EXTRA_FROM));
        name = nz(i.getStringExtra(EXTRA_NAME));
        room = nz(i.getStringExtra(EXTRA_ROOM));
        to = nz(i.getStringExtra(EXTRA_TO));
        video = i.getBooleanExtra(EXTRA_VIDEO, false);
        nid = i.getIntExtra(EXTRA_NID, 0);
        if (name.isEmpty()) name = from.isEmpty() ? "Unknown" : from;
        if (room.isEmpty()) { finish(); return; }

        String act = i.getStringExtra(EXTRA_ACTION);
        if (ACT_ACCEPT.equals(act)) { accept(); return; }
        if (ACT_DECLINE.equals(act)) { decline(); return; }

        setContentView(buildUi());
        startRing();
        h.postDelayed(this::timeout, RING_MS);
        h.postDelayed(watch, 2000);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private int dp(float v) { return Ui.dp(this, v); }

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(0xFF0F1B21);
        col.setPadding(dp(24), dp(96), dp(24), dp(64));

        col.addView(Ui.tv(this, video ? "Telephone video call" : "Telephone voice call", 14, 0xFF8696A0, false));
        TextView nm = Ui.tv(this, name, 30, 0xFFE9EDEF, true);
        nm.setGravity(Gravity.CENTER);
        col.addView(nm, Ui.lpm(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        col.addView(Ui.tv(this, "Incoming call…", 15, 0xFF8696A0, false),
            Ui.lpm(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        String ini = name.trim().isEmpty() ? "?" : name.trim().substring(0, 1).toUpperCase(Locale.getDefault());
        TextView av = Ui.tv(this, ini, 56, Color.WHITE, true);
        av.setGravity(Gravity.CENTER);
        av.setBackground(Ui.circle(0xFF6A7175));
        col.addView(av, Ui.lpm(this, dp(140), dp(140), 0, 50, 0, 0));

        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        TextView dec = round("✕", 0xFFF15C6D);
        TextView acc = round(video ? "🎥" : "📞", 0xFF00A884);
        dec.setOnClickListener(v -> decline());
        acc.setOnClickListener(v -> accept());
        row.addView(labelled(dec, "Decline"), Ui.lpm(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 56, 0));
        row.addView(labelled(acc, "Accept"));
        col.addView(row);
        return col;
    }

    private TextView round(String glyph, int color) {
        TextView t = Ui.tv(this, glyph, 26, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.circle(color));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(68), dp(68)));
        Ui.pressFx(t);
        return t;
    }

    private View labelled(View v, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(v);
        c.addView(Ui.tv(this, label, 13, 0xFF8696A0, false),
            Ui.lpm(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        return c;
    }

    // ── actions ──────────────────────────────────────────────
    private void answer(final long ans) {
        if (doc.isEmpty()) return;
        Store.async(() -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("ans", Long.valueOf(ans));
            d.put("up", Long.valueOf(System.currentTimeMillis()));
            Fs.update(doc, d);
            return Boolean.TRUE;
        }, null);
    }

    private void accept() {
        if (done) return;
        done = true;
        stopRing();
        cancelNotification();
        answer(1);
        String myName = PushManager.dmName(this);
        String myId = to.isEmpty() ? PushManager.dmMe(this) : to;
        Intent call = new Intent(this, GroupCallActivity.class)
            .putExtra(GroupCallActivity.EXTRA_UID, myId)
            .putExtra(GroupCallActivity.EXTRA_NAME, myName.isEmpty() ? "Member" : myName)
            .putExtra(GroupCallActivity.EXTRA_VIDEO, video)
            .putExtra(GroupCallActivity.EXTRA_ROOM, room);
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && Build.VERSION.SDK_INT >= 26 && km.isKeyguardLocked()) km.requestDismissKeyguard(this, null);
        } catch (Throwable ignored) { }
        startActivity(call);
        finish();
    }

    private void decline() {
        if (done) return;
        done = true;
        stopRing();
        cancelNotification();
        answer(2);
        finish();
    }

    private void timeout() {
        if (done) return;
        done = true;
        stopRing();
        cancelNotification();
        PushService.notifyMissed(this, from, name, video);
        finish();
    }

    private void closeQuietly() {
        if (done) return;
        done = true;
        stopRing();
        cancelNotification();
        finish();
    }

    private void cancelNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nid != 0) nm.cancel(nid);
        } catch (Throwable ignored) { }
    }

    private void startRing() {
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= 28) ringtone.setLooping(true);
                ringtone.play();
            }
        } catch (Throwable ignored) { }
        try {
            vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            long[] pat = {0, 800, 600};
            if (vib != null) {
                if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pat, 0));
                else vib.vibrate(pat, 0);
            }
        } catch (Throwable ignored) { }
    }

    private void stopRing() {
        try { if (ringtone != null) ringtone.stop(); } catch (Throwable ignored) { }
        ringtone = null;
        try { if (vib != null) vib.cancel(); } catch (Throwable ignored) { }
    }

    @Override
    public void onBackPressed() { /* ignore: use Accept / Decline */ }

    @Override
    protected void onDestroy() {
        done = true;
        h.removeCallbacksAndMessages(null);
        stopRing();
        super.onDestroy();
    }
}
