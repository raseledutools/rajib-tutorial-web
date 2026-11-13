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

import androidx.core.app.NotificationCompat;

import java.util.Set;

/** While Phone Mode is on: sends any non-allowed app back to the launcher and ends the timer. */
public class PhoneGuardService extends Service {
    private static final String CH_ID = "phone_mode_ch";
    private static final int NOTIF_ID = 3002;

    private Handler handler;
    private Runnable task;
    private Set<String> allowed;
    private long allowedAt = 0, lastQuery = 0;
    private String curFg = null;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Phone Mode", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        if (task == null) {
            task = new Runnable() {
                @Override public void run() {
                    if (!PhoneMode.isActive(PhoneGuardService.this)) { stopSelf(); return; }
                    if (PhoneMode.timeUp(PhoneGuardService.this)) {
                        PhoneMode.stop(PhoneGuardService.this);
                        stopSelf();
                        return;
                    }
                    guard();
                    handler.postDelayed(this, 200);
                }
            };
            handler.post(task);
        }
        return START_STICKY;
    }

    private void guard() {
        long now = System.currentTimeMillis();
        if (allowed == null || now - allowedAt > 30_000) {
            allowed = PhoneMode.allowedPackages(this);
            allowedAt = now;
        }
        String fg = foreground(now);
        if (fg == null || allowed.contains(fg)) return;
        // let the user reach Settings while the one-time setup is unfinished
        if (PhoneMode.isSettingsPkg(fg) && (PhoneMode.setupIncomplete(this)
                || System.currentTimeMillis() < PhoneMode.homeSetupUntil)) return;
        try {
            startActivity(new Intent(this, PhoneLauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
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
            new Intent(this, PhoneLauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Phone Mode on")
            .setContentText("Phone, Messages & offline apps only")
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
