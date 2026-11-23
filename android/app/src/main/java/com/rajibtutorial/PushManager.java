package com.rajibtutorial;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Push notification manager.
 * Token is saved directly to Firestore (push_tokens collection) — no backend needed.
 */
final class PushManager {
    private PushManager() {}

    static final String CHANNEL_ID  = "rt_push_v3";
    /** Explicit vibration pattern (same for the channel on API 26+ and the builder below it). */
    static final long[] VIBE = {0, 350, 200, 350};
    static final String TOPIC_ALL   = "all";
    /** CI sends an "app_update" data push to this topic when a new APK release is published. */
    static final String TOPIC_UPDATE = "app_update";
    static final String TOPIC_DISCUSSION = "discussion";
    static final String TOPIC_ADMINS = "admins";
    static final String CHAT_CHANNEL = "rt_chat_v1";
    private static final String K_DISC_ME = "disc_me";

    private static final String PREFS    = "PushPrefs";
    private static final String K_TOKEN  = "token";
    private static final String K_MOBILE = "mobile";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }


    /** Canonical form: digits only; Bangladesh numbers (+880 / 880 / 1xxxxxxxxx) -> 01xxxxxxxxx */
    static String normMobile(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.startsWith("880") && d.length() == 13) d = "0" + d.substring(3);
        else if (d.length() == 10 && d.startsWith("1")) d = "0" + d;
        if (d.length() > 15) d = d.substring(0, 15);
        return d;
    }

    /** All formats an old token document might have been saved with. */
    static java.util.List<String> mobileVariants(String raw) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        String n = normMobile(raw);
        if (!d.isEmpty()) out.add(d);
        if (!n.isEmpty()) {
            out.add(n);
            if (n.startsWith("0")) { out.add(n.substring(1)); out.add("880" + n.substring(1)); out.add("+880" + n.substring(1)); }
        }
        return new java.util.ArrayList<>(out);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            // Channel sound/vibration are frozen once created -> new id + drop the old ones
            try { nm.deleteNotificationChannel("rt_push_v1"); } catch (Exception ignored) {}
            try { nm.deleteNotificationChannel("rt_push_v2"); } catch (Exception ignored) {}
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Admin messages", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Important announcements from the admin");
            // Sound + vibration follow the phone's own system settings (default notification tone / pattern)
            ch.enableVibration(true);
            ch.setVibrationPattern(VIBE);
            ch.enableLights(true);
            ch.setShowBadge(true);
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ch.setSound(snd, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(ch);

            NotificationChannel chat = new NotificationChannel(
                CHAT_CHANNEL, "Discussion messages", NotificationManager.IMPORTANCE_HIGH);
            chat.setDescription("New messages and calls in the Discussion group");
            chat.enableVibration(true);
            chat.setVibrationPattern(VIBE);
            chat.setShowBadge(true);
            chat.setSound(snd, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(chat);
        } catch (Exception ignored) {}
    }

    /** Call on every app start: channel + topic subscription + token fetch */
    static void init(Context c) {
        final Context app = c.getApplicationContext();
        createChannel(app);
        UpdateWorker.schedule(app);   // 30-min fallback
        UpdatePollService.start(app); // 5-second check while the app is closed
        try {
            if (FirebaseApp.getApps(app).isEmpty()) return;
            FirebaseMessaging fm = FirebaseMessaging.getInstance();
            fm.subscribeToTopic(TOPIC_ALL);
            fm.subscribeToTopic(TOPIC_UPDATE);
            fm.getToken().addOnSuccessListener(t -> onToken(app, t));
        } catch (Throwable ignored) {}
    }

    static void onToken(Context c, String token) {
        if (token == null || token.isEmpty()) return;
        p(c).edit().putString(K_TOKEN, token).apply();
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_ALL);
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_UPDATE);
        } catch (Throwable ignored) {}
        saveTokenToFirestore(c, token, p(c).getString(K_MOBILE, ""));
    }

    /** Admin devices join the "admins" topic so new payment (TrxID) requests ring on the admin phone. */
    static void setAdmin(Context c, boolean on) {
        try {
            if (FirebaseApp.getApps(c).isEmpty()) return;
            if (on) FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_ADMINS);
            else FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC_ADMINS);
        } catch (Throwable ignored) { }
    }

    /** Join / leave the Discussion push topic (premium members + admin). */
    static void setDiscussion(Context c, boolean on, String myId) {
        p(c).edit().putString(K_DISC_ME, on ? myId : "").apply();
        try {
            if (FirebaseApp.getApps(c).isEmpty()) return;
            if (on) FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_DISCUSSION);
            else FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC_DISCUSSION);
        } catch (Throwable ignored) { }
    }

    // ── Local copy of received admin messages (fallback for the notification inbox) ──
    private static final String K_INBOX = "inbox";
    private static final String K_SEEN  = "inbox_seen";

    /** Stores a received admin message (newest first, max 100, same id only once). */
    static synchronized void inboxAdd(Context c, String id, String title, String body, long at) {
        try {
            org.json.JSONArray old = new org.json.JSONArray(p(c).getString(K_INBOX, "[]"));
            org.json.JSONArray out = new org.json.JSONArray();
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", id == null ? "" : id);
            o.put("title", title == null ? "" : title);
            o.put("body", body == null ? "" : body);
            o.put("at", at);
            out.put(o);
            for (int i = 0; i < old.length() && out.length() < 100; i++) {
                org.json.JSONObject x = old.getJSONObject(i);
                if (id != null && !id.isEmpty() && id.equals(x.optString("id"))) continue;
                out.put(x);
            }
            p(c).edit().putString(K_INBOX, out.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Locally stored admin messages, newest first. */
    static synchronized org.json.JSONArray inboxLoad(Context c) {
        try { return new org.json.JSONArray(p(c).getString(K_INBOX, "[]")); }
        catch (Throwable t) { return new org.json.JSONArray(); }
    }

    /** Time of the newest message the user has seen in the inbox (0 = never). */
    static long inboxSeen(Context c) { return p(c).getLong(K_SEEN, 0L); }
    static void inboxMarkSeen(Context c, long at) {
        if (at > inboxSeen(c)) p(c).edit().putLong(K_SEEN, at).apply();
    }

    /** Id of the signed-in discussion user on this device ("" = not subscribed). */
    static String discussionMe(Context c) { return p(c).getString(K_DISC_ME, ""); }

    /** Called from JS bridge when a member logs in / out */
    static void setUser(Context c, String mobile) {
        String m = normMobile(mobile);
        p(c).edit().putString(K_MOBILE, m).apply();
        String token = p(c).getString(K_TOKEN, "");
        if (!token.isEmpty()) saveTokenToFirestore(c, token, m);
    }

    /** Save FCM token to Firestore push_tokens/{token} */
    static void saveTokenToFirestore(Context c, String token, String mobile) {
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            Map<String, Object> data = new HashMap<>();
            data.put("token",     token);
            data.put("mobile",    mobile);
            data.put("device",    Build.MANUFACTURER + " " + Build.MODEL);
            data.put("updatedAt", System.currentTimeMillis());
            db.collection("push_tokens").document(token).set(data);
        } catch (Throwable ignored) {}
    }
}
