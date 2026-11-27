package com.rajibtutorial;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phone A: full-screen "a SIM call is ringing on Phone B". Receive (join the loudspeaker bridge),
 * Cut, or send an SMS to the caller. Every command is signed with the pairing code.
 */
public class SimCallActivity extends Activity {
    static final String ACT_ANSWER = "answer", ACT_CUT = "cut";
    private static final String EXTRA_B = "b", EXTRA_CALL = "call", EXTRA_NUMBER = "number",
        EXTRA_NID = "nid", EXTRA_ACTION = "action";
    private static final long RING_MS = 60000;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static Intent intent(Context c, String b, String call, String number, int nid, String action) {
        Intent i = new Intent(c, SimCallActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_B, b).putExtra(EXTRA_CALL, call).putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_NID, nid);
        if (action != null) i.putExtra(EXTRA_ACTION, action);
        return i;
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private String b = "", call = "", number = "", code = "";
    private int nid = 0;
    private boolean done = false, answering = false, typing = false;
    private TextView statusTv;
    private Ringtone ringtone;
    private Vibrator vib;
    private final Runnable giveUp = this::close;

    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (done) return;
            final String id = call;
            Store.async(() -> Fs.get(RelayCall.STATE_PREFIX + id), (Fs.Doc d, Exception e) -> {
                if (done || !id.equals(call)) return;
                if (d != null) {
                    String state = Fs.text(d.data, "state"), note = Fs.text(d.data, "note"), ts = Fs.text(d.data, "ts");
                    if (RelayAuth.verify(code, Fs.text(d.data, "sig"), "state", id, state, note, ts)) {
                        if ("ended".equals(state) && !typing) { toast("The call ended"); close(); return; }
                        if ("active".equals(state)) { stopRing(); statusTv.setText("Answered on Phone B"); }
                        else if (!note.isEmpty()) statusTv.setText(note);
                    }
                }
                h.postDelayed(watch, 2000);
            });
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Store.init(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF0F1B21);
        getWindow().setNavigationBarColor(0xFF0F1B21);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        show(i);
    }

    private void show(Intent i) {
        String newCall = nz(i.getStringExtra(EXTRA_CALL));
        String action = i.getStringExtra(EXTRA_ACTION);
        i.removeExtra(EXTRA_ACTION);
        boolean same = newCall.equals(call);
        if (!same) {
            b = nz(i.getStringExtra(EXTRA_B));
            call = newCall;
            number = nz(i.getStringExtra(EXTRA_NUMBER));
            nid = i.getIntExtra(EXTRA_NID, 0);
            code = RelayAuth.pairedCode(this, b);
            if (call.isEmpty() || code.isEmpty()) { finish(); return; }
            done = false;
            answering = false;
            setContentView(buildUi());
            h.removeCallbacksAndMessages(null);
            h.postDelayed(giveUp, RING_MS);
            h.post(watch);
            if (action == null) startRing();
        }
        if (ACT_ANSWER.equals(action)) receive();
        else if (ACT_CUT.equals(action)) cut();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private int dp(float v) { return Ui.dp(this, v); }

    private View buildUi() {
        int w = WRAP;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(0xFF0F1B21);
        col.setPadding(dp(24), dp(96), dp(24), dp(56));
        col.addView(Ui.tv(this, "SIM call on Phone " + b, 14, 0xFF8696A0, false));
        TextView nm = Ui.tv(this, number.isEmpty() ? "Unknown number" : number, 30, 0xFFE9EDEF, true);
        nm.setGravity(Gravity.CENTER);
        col.addView(nm, Ui.lpm(this, w, w, 0, 10, 0, 0));
        statusTv = Ui.tv(this, "Incoming call…", 15, 0xFF8696A0, false);
        statusTv.setGravity(Gravity.CENTER);
        col.addView(statusTv, Ui.lpm(this, w, w, 0, 8, 0, 0));
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(button("✕", "Cut", 0xFFF15C6D, v -> cut()), Ui.lpm(this, w, w, 0, 0, 36, 0));
        row.addView(button("✉", "SMS", 0xFF2A3942, v -> smsDialog()), Ui.lpm(this, w, w, 0, 0, 36, 0));
        row.addView(button("📞", "Receive", 0xFF00A884, v -> receive()));
        col.addView(row);
        return col;
    }

    private View button(String glyph, String label, int color, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = Ui.tv(this, glyph, 26, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.circle(color));
        Ui.pressFx(t);
        t.setOnClickListener(l);
        c.addView(t, new LinearLayout.LayoutParams(dp(68), dp(68)));
        c.addView(Ui.tv(this, label, 13, 0xFF8696A0, false), Ui.lpm(this, WRAP, WRAP, 0, 8, 0, 0));
        return c;
    }

    // ── commands ─────────────────────────────────────────────
    private void command(final String cmd, final String text, final Runnable ok) {
        final String id = RelayCall.CMD_PREFIX + call + "_" + String.format(Locale.US, "%013d", System.currentTimeMillis())
            + "_" + RelayAuth.random(4);
        final String ts = String.valueOf(System.currentTimeMillis());
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("appId", DiscussionScreen.SYNC_APP_ID);
        d.put("cmd", cmd);
        d.put("text", text);
        d.put("ts", ts);
        d.put("sig", RelayAuth.sign(code, "cmd", id, call, cmd, text, ts));
        Store.async(() -> { Fs.set(id, d); return Boolean.TRUE; }, (Boolean r, Exception e) -> {
            if (e != null) { answering = false; toast("Not sent. Check your internet."); }
            else if (ok != null) ok.run();
        });
    }

    private void receive() {
        if (done || answering) return;
        answering = true;
        stopRing();
        cancelNotification();
        statusTv.setText("Connecting…");
        final String room = RelayCall.room(call), key = code, me = PushManager.dmMe(this);
        command("answer", "", () -> {
            if (done) return;
            startActivity(new Intent(this, GroupCallActivity.class)
                .putExtra(GroupCallActivity.EXTRA_UID, me)
                .putExtra(GroupCallActivity.EXTRA_NAME, "Phone A")
                .putExtra(GroupCallActivity.EXTRA_VIDEO, false)
                .putExtra(GroupCallActivity.EXTRA_ROOM, room)
                .putExtra(GroupCallActivity.EXTRA_KEY, key));
            close();
        });
    }

    private void cut() {
        if (done) return;
        stopRing();
        cancelNotification();
        command("cut", "", () -> { toast("Asked Phone B to cut the call"); close(); });
    }

    private void smsDialog() {
        stopRing();
        typing = true;
        h.removeCallbacks(giveUp);                          // do not close while typing
        final EditText et = new EditText(this);
        et.setText("I can't talk now. I will call you back.");
        et.setMaxLines(4);
        new AlertDialog.Builder(this)
            .setTitle("SMS from Phone B to the caller")
            .setView(et)
            .setPositiveButton("Send", (d, w) -> {
                String t = et.getText().toString().trim();
                if (!t.isEmpty()) command("sms", t, () -> toast("SMS request sent to Phone B"));
            })
            .setNegativeButton("Cancel", null)
            .setOnDismissListener(d -> { typing = false; h.postDelayed(giveUp, RING_MS); })
            .show();
    }

    private void toast(String s) { Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show(); }

    private void close() {
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
        stopRing();
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
    protected void onDestroy() {
        done = true;
        h.removeCallbacksAndMessages(null);
        stopRing();
        super.onDestroy();
    }
}
