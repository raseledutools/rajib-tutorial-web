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

    static final String CHANNEL_ID  = "rt_push_v1";
    static final String TOPIC_ALL   = "all";

    private static final String PREFS    = "PushPrefs";
    private static final String K_TOKEN  = "token";
    private static final String K_MOBILE = "mobile";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Admin বার্তা", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Admin এর পাঠানো গুরুত্বপূর্ণ বার্তা");
            ch.enableVibration(true);
            ch.setShowBadge(true);
            Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ch.setSound(snd, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        } catch (Exception ignored) {}
    }

    /** Call on every app start: channel + topic subscription + token fetch */
    static void init(Context c) {
        final Context app = c.getApplicationContext();
        createChannel(app);
        try {
            if (FirebaseApp.getApps(app).isEmpty()) return;
            FirebaseMessaging fm = FirebaseMessaging.getInstance();
            fm.subscribeToTopic(TOPIC_ALL);
            fm.getToken().addOnSuccessListener(t -> onToken(app, t));
        } catch (Throwable ignored) {}
    }

    static void onToken(Context c, String token) {
        if (token == null || token.isEmpty()) return;
        p(c).edit().putString(K_TOKEN, token).apply();
        try { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_ALL); }
        catch (Throwable ignored) {}
        saveTokenToFirestore(c, token, p(c).getString(K_MOBILE, ""));
    }

    /** Called from JS bridge when a member logs in / out */
    static void setUser(Context c, String mobile) {
        String m = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (m.length() > 15) m = m.substring(0, 15);
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
