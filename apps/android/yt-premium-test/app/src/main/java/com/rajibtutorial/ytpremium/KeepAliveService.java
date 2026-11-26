package com.rajibtutorial.ytpremium;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

/** Foreground "media playback" service: keeps the process (and the WebView's audio) alive with the screen off or the app in the background. */
public class KeepAliveService extends Service {
    private static final String CH = "ytp_bg";
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wfl;
    static final String ACT_TOGGLE = "com.rajibtutorial.ytpremium.TOGGLE";

    static void start(Context c) {
        Intent i = new Intent(c, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, KeepAliveService.class)); } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_TOGGLE.equals(intent.getAction())) MainActivity.togglePlayback();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Background playback", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
         .setContentTitle("YouTube Premium Test")
         .setContentText("Playing in the background")
         .setContentIntent(pi)
         .setOngoing(true);
        Intent tg = new Intent(this, KeepAliveService.class).setAction(ACT_TOGGLE);
        PendingIntent tpi = PendingIntent.getService(this, 1, tg, pf | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Play / Pause", tpi).build());
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(1, n);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytpremium:bg");
                wl.setReferenceCounted(false);
                wl.acquire(6 * 60 * 60 * 1000L);
            }
        }
        if (wfl == null) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    wfl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ytpremium:wifi");
                    wfl.setReferenceCounted(false);
                    wfl.acquire();
                }
            } catch (Exception ignored) { }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { if (wfl != null && wfl.isHeld()) wfl.release(); } catch (Exception ignored) { }
        wfl = null;
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
