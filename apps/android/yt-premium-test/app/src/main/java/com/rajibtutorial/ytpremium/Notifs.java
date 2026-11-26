package com.rajibtutorial.ytpremium;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** All notifications of the app: update available, and save progress / done / failed. */
final class Notifs {
    private Notifs() {}

    static final String CH_UPDATE = "ytp_update";
    static final String CH_SAVE = "ytp_save";
    static final String EXTRA_APK_URL = "apk_url";
    static final String EXTRA_VERSION = "version";
    private static final int ID_UPDATE = 2001;

    static void init(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel u = new NotificationChannel(CH_UPDATE, "App updates", NotificationManager.IMPORTANCE_HIGH);
        u.setDescription("New versions of YouTube Premium Test");
        nm.createNotificationChannel(u);
        nm.createNotificationChannel(new NotificationChannel(CH_SAVE, "Saved videos", NotificationManager.IMPORTANCE_LOW));
    }

    private static void post(Context c, int id, Notification n) {
        try { NotificationManagerCompat.from(c).notify(id, n); } catch (SecurityException ignored) { }
    }

    private static PendingIntent open(Context c, Class<?> cls, int req) {
        Intent i = new Intent(c, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ── update available (tap -> app downloads it in-app with a progress bar, then the install prompt) ──
    static void update(Context c, String tag, int code, String apkUrl) {
        init(c);
        String vName = "1." + code;
        Intent i = new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_APK_URL, apkUrl)
            .putExtra(EXTRA_VERSION, vName);
        PendingIntent pi = PendingIntent.getActivity(c, 10, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(c, CH_UPDATE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("YouTube Premium Test " + vName + " is available")
            .setContentText("Tap to download and install the new version")
            .setStyle(new NotificationCompat.BigTextStyle().bigText("A new version (" + vName + ") is ready.\nTap to download and install it."))
            .addAction(android.R.drawable.stat_sys_download, "Update now", pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();
        post(c, ID_UPDATE, n);
    }

    // ── save progress ──
    private static int saveId(String videoId) { return 3000 + (videoId.hashCode() & 0xFFFF); }

    static void save(Context c, String videoId, String title, int pct) {
        if (c == null || videoId == null) return;
        init(c);
        String t = title == null || title.isEmpty() ? "video" : title;
        int id = saveId(videoId);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_SAVE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(t);
        if (pct >= 0 && pct < 100) {
            b.setContentText(pct <= 0 ? "Preparing…" : "Saving " + pct + "%")
             .setProgress(100, pct, pct <= 0)
             .setOngoing(true)
             .setOnlyAlertOnce(true)
             .setContentIntent(open(c, MainActivity.class, 20));
            post(c, id, b.build());
        } else if (pct == 100) {
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
             .setContentText("Saved ✓  Tap to open")
             .setProgress(0, 0, false)
             .setAutoCancel(true)
             .setContentIntent(open(c, SavedActivity.class, 21));
            post(c, id, b.build());
        } else if (pct == -1) {
            b.setSmallIcon(android.R.drawable.stat_notify_error)
             .setContentText(YtSaver.lastError(videoId))
             .setProgress(0, 0, false)
             .setAutoCancel(true)
             .setContentIntent(open(c, MainActivity.class, 22));
            post(c, id, b.build());
        } else {
            try { NotificationManagerCompat.from(c).cancel(id); } catch (Exception ignored) { }
        }
    }
}
