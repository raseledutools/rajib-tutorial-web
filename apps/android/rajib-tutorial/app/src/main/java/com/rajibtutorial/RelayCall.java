package com.rajibtutorial;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone B side of one relayed SIM call: alert Phone A, publish the call state, listen for A's signed
 * commands (answer + voice bridge, cut, SMS to this caller) and report a missed call.
 */
final class RelayCall {
    private RelayCall() { }

    // Firestore ids: inside the "_tm_" namespace (already hidden from courses), sorted away from chat messages
    static final String CMD_PREFIX = "_tm_-c_";
    static final String STATE_PREFIX = "_tm_-s_";
    private static final long CMD_MAX_AGE_MS = 5 * 60 * 1000L;       // tolerates small clock differences
    private static final long SMS_WINDOW_MS = 3 * 60 * 1000L;        // SMS commands accepted this long after the call
    private static final long MAX_SESSION_MS = 60 * 60 * 1000L;
    private static final int NOTICE_ID = 0x52454C59;
    private static final String NOTICE_CHANNEL = "rt_relay_v1";

    private static final class Session {
        String id, number, me, to, code;
        long startedAt;
        volatile boolean offhook = false;
        volatile long endedAt = 0;
        volatile boolean bridged = false;
        int sms = 0;
        final Set<String> done = new HashSet<>();
    }

    private static volatile Session cur;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ── settings ─────────────────────────────────────────────
    private static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences(CallRelayReceiver.PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        String me = sp(c).getString("num", ""), to = sp(c).getString(CallRelayReceiver.K_TO, "");
        return sp(c).getBoolean(CallRelayReceiver.K_ON, false) && !me.isEmpty() && !to.isEmpty() && !to.equals(me);
    }

    static boolean has(Context c, String perm) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    static boolean phoneRinging(Context c) {
        try {
            TelephonyManager t = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            return t != null && t.getCallState() == TelephonyManager.CALL_STATE_RINGING;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Permanent notice while the relay is on, so whoever holds this phone knows calls are being shared. */
    static void updateNotice(Context c) {
        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(c);
            if (!enabled(c)) { nm.cancel(NOTICE_ID); return; }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager m = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (m != null) m.createNotificationChannel(new NotificationChannel(NOTICE_CHANNEL,
                    "Call relay status", NotificationManager.IMPORTANCE_LOW));
            }
            String to = sp(c).getString(CallRelayReceiver.K_TO, "");
            String text = "Calls on this phone are shared with " + to + ", which can answer, end or reply to them. Tap to change.";
            PendingIntent pi = PendingIntent.getActivity(c, NOTICE_ID,
                new Intent(c, MessengerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new NotificationCompat.Builder(c, NOTICE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_push)
                .setContentTitle("Call relay is ON")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
            nm.notify(NOTICE_ID, n);
        } catch (Throwable ignored) { }
    }

    // ── phone state ──────────────────────────────────────────
    static synchronized void onRinging(Context c, String number) {
        Session s = cur;
        if (s != null && s.endedAt == 0 && System.currentTimeMillis() - s.startedAt < MAX_SESSION_MS) {
            if (s.number.equals(number) || number.isEmpty()) return;          // duplicate broadcast of the same ring
            if (s.offhook) {                                                   // second caller while busy: message only
                RelayWorker.send(c, "dm", "", s.me, s.to, number, "📞 Another call (busy) from " + shown(number) + " (" + now() + ")");
                return;
            }
            if (s.number.isEmpty()) { s.number = number; }                     // late number for this ring
            else return;
        } else {
            s = new Session();
            s.startedAt = System.currentTimeMillis();
            s.id = String.format(Locale.US, "%013d", s.startedAt) + RelayAuth.random(8);
            s.number = number;
            s.me = sp(c).getString("num", "");
            s.to = PushManager.normMobile(sp(c).getString(CallRelayReceiver.K_TO, ""));
            s.code = RelayAuth.myCode(c);
            cur = s;
            listen(c, s);
        }
        updateNotice(c);
        publish(s, "ringing", "");
        RelayWorker.send(c, "simcall", s.id, s.me, s.to, number, "📞 Incoming call from " + shown(number) + " (" + now() + ")");
    }

    static synchronized void onOffhook(Context c) {
        Session s = cur;
        if (s == null || s.endedAt != 0 || s.offhook) return;       // an outgoing call has no session
        s.offhook = true;
        publish(s, "active", "");
    }

    static synchronized void onIdle(Context c) {
        Session s = cur;
        if (s == null || s.endedAt != 0) return;
        s.endedAt = System.currentTimeMillis();
        publish(s, "ended", s.offhook ? "answered" : "missed");
        if (!s.offhook) {
            RelayWorker.send(c, "simmissed", s.id, s.me, s.to, s.number, "❌ Missed call from " + shown(s.number) + " (" + now() + ")");
        }
        GroupCallActivity.endBridge();
    }

    private static String shown(String number) { return number.isEmpty() ? "an unknown number" : number; }

    private static String now() { return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date()); }

    /** Writes the signed call state that Phone A watches. */
    private static void publish(final Session s, final String state, final String note) {
        IO.execute(() -> {
            try {
                String ts = String.valueOf(System.currentTimeMillis());
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("appId", DiscussionScreen.SYNC_APP_ID);
                d.put("state", state);
                d.put("note", note);
                d.put("ts", ts);
                d.put("sig", RelayAuth.sign(s.code, "state", s.id, state, note, ts));
                Fs.set(STATE_PREFIX + s.id, d);
            } catch (Exception ignored) { }
        });
    }

    // ── commands from Phone A ────────────────────────────────
    private static void listen(final Context c, final Session s) {
        new Thread(() -> {
            while (cur == s) {
                long now = System.currentTimeMillis();
                if (now - s.startedAt > MAX_SESSION_MS) break;
                if (s.endedAt != 0 && now - s.endedAt > SMS_WINDOW_MS) break;
                try {
                    for (Fs.Doc d : Fs.latestChat(CMD_PREFIX + s.id + "_", 10)) {
                        if (s.done.add(d.id)) handle(c, s, d);
                    }
                } catch (Exception ignored) { }
                try { Thread.sleep(s.endedAt != 0 ? 5000 : s.offhook ? 3000 : 1500); } catch (InterruptedException e) { break; }
            }
            // tidy up this call's documents
            for (String id : new ArrayList<>(s.done)) { try { Fs.delete(id); } catch (Exception ignored) { } }
            try { Fs.delete(STATE_PREFIX + s.id); } catch (Exception ignored) { }
        }, "relay-cmd").start();
    }

    private static void handle(Context c, Session s, Fs.Doc d) {
        String cmd = Fs.text(d.data, "cmd"), text = Fs.text(d.data, "text"), ts = Fs.text(d.data, "ts");
        if (!d.id.startsWith(CMD_PREFIX + s.id + "_")) return;
        if (!RelayAuth.verify(s.code, Fs.text(d.data, "sig"), "cmd", d.id, s.id, cmd, text, ts)) return;
        try { if (Math.abs(System.currentTimeMillis() - Long.parseLong(ts)) > CMD_MAX_AGE_MS) return; }
        catch (NumberFormatException e) { return; }
        if (!enabled(c)) return;
        if ("answer".equals(cmd)) MAIN.post(() -> answerAndBridge(c, s));
        else if ("cut".equals(cmd)) MAIN.post(() -> { if (s.endedAt == 0) endSimCall(c); });
        else if ("sms".equals(cmd)) sms(c, s, text);
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private static void answerAndBridge(final Context c, final Session s) {
        if (s != cur || s.endedAt != 0 || s.offhook || s.bridged) return;
        if (Build.VERSION.SDK_INT < 26 || !has(c, Manifest.permission.ANSWER_PHONE_CALLS)) {
            publish(s, "ringing", "Phone B has no permission to answer calls");
            return;
        }
        final TelBridgeService svc = TelBridgeService.get();
        if (svc == null) { publish(s, "ringing", "Turn on the accessibility service on Phone B"); return; }
        if (!has(c, Manifest.permission.RECORD_AUDIO)) { publish(s, "ringing", "Phone B has no microphone permission"); return; }
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            if (tm == null) return;
            s.bridged = true;
            tm.acceptRingingCall();
        } catch (Throwable e) {
            s.bridged = false;
            publish(s, "ringing", "Phone B could not answer the call");
            return;
        }
        // let the phone's call screen appear, switch it to loudspeaker, then join the internet call
        MAIN.postDelayed(() -> { if (s.endedAt == 0) svc.speakerOn(); }, 1200);
        MAIN.postDelayed(() -> {
            if (s.endedAt != 0) return;
            try {
                loud(c);
                svc.startActivity(new Intent(svc, GroupCallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(GroupCallActivity.EXTRA_UID, s.me)
                    .putExtra(GroupCallActivity.EXTRA_NAME, "Phone B")
                    .putExtra(GroupCallActivity.EXTRA_VIDEO, false)
                    .putExtra(GroupCallActivity.EXTRA_ROOM, room(s.id))
                    .putExtra(GroupCallActivity.EXTRA_KEY, s.code)
                    .putExtra(GroupCallActivity.EXTRA_BRIDGE, true));
            } catch (Throwable e) {
                publish(s, "active", "Phone B could not start the internet call");
                endSimCall(c);
            }
        }, 3500);
        // nobody must be left on a silent line: hang up if the bridge screen never appeared
        MAIN.postDelayed(() -> {
            if (s == cur && s.endedAt == 0 && !GroupCallActivity.bridgeOpen()) {
                publish(s, "active", "Phone B could not open the bridge screen");
                endSimCall(c);
            }
        }, 15000);
    }

    static String room(String callId) { return "sim_" + callId; }

    private static void loud(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            for (int stream : new int[]{AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC}) {
                am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0);
            }
        } catch (Throwable ignored) { }
    }

    /** Ends the SIM call (Android 9+). */
    @SuppressWarnings({"deprecation", "MissingPermission"})
    static void endSimCall(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 28 || !has(c, Manifest.permission.ANSWER_PHONE_CALLS)) return;
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) tm.endCall();
        } catch (Throwable ignored) { }
    }

    /** The bridge screen closed (Phone A hung up, connection lost, or closed on B): hang up the SIM call it answered. */
    static void bridgeClosed(Context c, String room) {
        Session s = cur;
        if (s != null && s.endedAt == 0 && s.bridged && room(s.id).equals(room)) endSimCall(c);
    }

    /** SMS goes only to the number that made this call, at most 3 per call. */
    @SuppressWarnings("deprecation")
    private static void sms(Context c, Session s, String text) {
        String state = s.endedAt != 0 ? "ended" : s.offhook ? "active" : "ringing";
        String msg = text.trim();
        if (msg.length() > 300) msg = msg.substring(0, 300);
        if (msg.isEmpty() || s.sms >= 3) return;
        if (s.number.replaceAll("[^0-9]", "").length() < 6) { publish(s, state, "No number to send the SMS to"); return; }
        if (!has(c, Manifest.permission.SEND_SMS)) { publish(s, state, "Phone B has no SMS permission"); return; }
        try {
            SmsManager sm = SmsManager.getDefault();
            List<String> parts = sm.divideMessage(msg);
            sm.sendMultipartTextMessage(s.number, null, new ArrayList<>(parts), null, null);
            s.sms++;
            publish(s, state, "SMS handed to Phone B for sending");
        } catch (Throwable e) {
            publish(s, state, "Phone B could not send the SMS");
        }
    }
}
