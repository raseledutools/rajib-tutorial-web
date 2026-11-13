package com.rajibtutorial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.Set;

/** Watches the foreground app (usage stats) and shows the password screen for locked apps. */
public class AppLockService extends Service {
    private static final String CH_ID = "apps_lock_ch";
    private static final int NOTIF_ID = 3003;

    private Handler handler;
    private Runnable task;
    private long lastQuery = 0;
    private String curFg = null;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Apps Lock", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        if (task == null) {
            task = new Runnable() {
                @Override public void run() {
                    Set<String> locked = AppLock.locked(AppLockService.this);
                    if (locked.isEmpty()) { stopSelf(); return; }
                    check(locked);
                    handler.postDelayed(this, 400);
                }
            };
            handler.post(task);
        }
        return START_STICKY;
    }

    private void check(Set<String> locked) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) { AppLock.unlockedPkg = null; return; }

        String fg = foreground(System.currentTimeMillis());
        if (fg == null) return;
        if (fg.equals(getPackageName())) return;                 // our own lock screen / app
        if (AppLock.unlockedPkg != null && !fg.equals(AppLock.unlockedPkg)) AppLock.unlockedPkg = null; // left it → lock again
        if (!locked.contains(fg) || fg.equals(AppLock.unlockedPkg)) return;
        try {
            startActivity(new Intent(this, AppLockScreenActivity.class)
                .putExtra("pkg", fg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private String foreground(long now) {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long from = lastQuery > 0 ? lastQuery - 1000 : now - 10_000;
            UsageEvents ev = usm.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) curFg = e.getPackageName();
                else if (e.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND && e.getPackageName().equals(curFg)) curFg = null;
            }
            lastQuery = now;
        } catch (Exception ignored) { }
        return curFg;
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, AppsLockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Apps Lock on")
            .setContentText("Selected apps need a password")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Override
    public void onDestroy() {
        if (task != null) handler.removeCallbacks(task);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
