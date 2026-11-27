package com.rajibtutorial;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * "Phone B" relay: when a normal SIM call rings on this phone, tell the owner (Phone A) who is calling.
 * The alert is a Telephone chat message from this phone's number to the alert number, plus a push, so it
 * shows up on Phone A's lock screen with the app closed. Optionally answers the SIM call automatically.
 * (Android does not let apps pass SIM-call audio to an internet call, so this is an alert, not a voice bridge.)
 */
public class CallRelayReceiver extends BroadcastReceiver {
    static final String PREFS = "rt_tel";
    static final String K_ON = "relay_on", K_TO = "relay_to", K_AUTO = "relay_auto";

    private static String lastNum = "";
    private static long lastAt = 0;

    @Override
    public void onReceive(final Context ctx, Intent i) {
        if (i == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(i.getAction())) return;
        if (!TelephonyManager.EXTRA_STATE_RINGING.equals(i.getStringExtra(TelephonyManager.EXTRA_STATE))) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(K_ON, false)) return;
        final String me = sp.getString("num", "");
        final String to = PushManager.normMobile(sp.getString(K_TO, ""));
        final boolean auto = sp.getBoolean(K_AUTO, false);
        if (me.isEmpty() || to.isEmpty() || to.equals(me)) return;

        String raw = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        if (raw == null || raw.trim().isEmpty()) return;          // the broadcast without a number; the one with it follows
        final String number = raw.trim();
        long now = System.currentTimeMillis();
        synchronized (CallRelayReceiver.class) {
            if (number.equals(lastNum) && now - lastAt < 20000) return;   // same ring, duplicate broadcast
            lastNum = number;
            lastAt = now;
        }

        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                long ts = System.currentTimeMillis();
                String time = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(ts));
                String text = "📞 Incoming call from " + number + " (" + time + ")";
                String key = me.compareTo(to) < 0 ? me + "-" + to : to + "-" + me;
                String id = "_tm_" + key + "_" + String.format(Locale.US, "%013d", ts) + "_"
                    + Integer.toString(new Random().nextInt(1679616) + 1679616, 36).substring(1);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("appId", DiscussionScreen.SYNC_APP_ID);
                d.put("from", me);
                d.put("fromName", me);
                d.put("to", to);
                d.put("toName", to);
                d.put("type", "text");
                d.put("text", text);
                d.put("url", "");
                d.put("fileName", "");
                d.put("ts", Long.valueOf(ts));
                try { Fs.set(id, d); } catch (Exception ignored) { }
                new AdminBridge(app, null).sendDmPush(to, "dm", me, "📲 Phone " + me, text, id, "", false);

                if (auto && Build.VERSION.SDK_INT >= 26
                    && app.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        Thread.sleep(1500);
                        TelecomManager tm = (TelecomManager) app.getSystemService(Context.TELECOM_SERVICE);
                        if (tm != null) tm.acceptRingingCall();
                    } catch (Throwable ignored) { }
                }
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }   // let the push thread finish
            } finally {
                pr.finish();
            }
        }).start();
    }
}
