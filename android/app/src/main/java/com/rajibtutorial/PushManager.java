package com.rajibtutorial;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Admin -> user push notifications (Firebase Cloud Messaging).
 * Everything here is a no-op until android/app/google-services.json is added to the repo,
 * so the app keeps working (and building) without Firebase configured.
 */
final class PushManager {
    private PushManager() {}

    static final String CHANNEL_ID = "rt_push_v1";
    static final String TOPIC_ALL = "all";

    private static final String PREFS = "PushPrefs";
    private static final String K_TOKEN = "token", K_MOBILE = "mobile", K_SYNCED = "synced";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Admin বার্তা", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Admin এর পাঠানো গুরুত্বপূর্ণ বার্তা");
            ch.enableVibration(true);
            ch.setShowBadge(true);
            Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ch.setSound(snd, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        } catch (Exception ignored) { }
    }

    /** Call on every app start: channel + topic subscription + token registration. */
    static void init(Context c) {
        final Context app = c.getApplicationContext();
        createChannel(app);
        try {
            if (FirebaseApp.getApps(app).isEmpty()) return;      // google-services.json missing
            FirebaseMessaging fm = FirebaseMessaging.getInstance();
            fm.subscribeToTopic(TOPIC_ALL);
            fm.getToken().addOnSuccessListener(t -> onToken(app, t));
        } catch (Throwable ignored) { }
    }

    static void onToken(Context c, String token) {
        if (token == null || token.isEmpty()) return;
        p(c).edit().putString(K_TOKEN, token).apply();
        try { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_ALL); } catch (Throwable ignored) { }
        sync(c);
    }

    /** Called from the web page (JS bridge) when a member logs in / out. */
    static void setUser(Context c, String mobile) {
        String m = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (m.length() > 15) m = m.substring(0, 15);
        p(c).edit().putString(K_MOBILE, m).apply();
        sync(c);
    }

    // ── Backend (same server as the call backend; adds /register) ──
    private static String baseUrl() {
        String u = BuildConfig.PUSH_BACKEND_URL;
        if (u == null || u.isEmpty()) {
            try {
                Uri t = Uri.parse(BuildConfig.TWILIO_TOKEN_URL);
                if (t.getScheme() != null && t.getAuthority() != null) u = t.getScheme() + "://" + t.getAuthority();
            } catch (Exception ignored) { }
        }
        if (u == null) return "";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String appKey() {
        try {
            String k = Uri.parse(BuildConfig.TWILIO_TOKEN_URL).getQueryParameter("key");
            return k == null ? "" : k;
        } catch (Exception e) { return ""; }
    }

    /** Tell the backend which device token belongs to which mobile (empty = not logged in). */
    static void sync(final Context c) {
        final Context app = c.getApplicationContext();
        final String token = p(app).getString(K_TOKEN, "");
        final String mobile = p(app).getString(K_MOBILE, "");
        final String base = baseUrl();
        if (token.isEmpty() || base.isEmpty()) return;
        final String stamp = token + "|" + mobile;
        if (stamp.equals(p(app).getString(K_SYNCED, ""))) return;

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("token", token);
                body.put("mobile", mobile);
                body.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                body.put("key", appKey());
                conn = (HttpURLConnection) new URL(base + "/register").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(20_000);          // free hosting can take a while to wake up
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes("UTF-8")); }
                if (conn.getResponseCode() == 200) p(app).edit().putString(K_SYNCED, stamp).apply();
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
