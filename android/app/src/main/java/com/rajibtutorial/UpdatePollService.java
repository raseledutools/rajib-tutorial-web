package com.rajibtutorial;

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

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Keeps checking GitHub for a new APK release every few seconds while the app is closed
 * (foreground service, so Android does not kill it). The 30-minute UpdateWorker stays as a fallback.
 */
public class UpdatePollService extends Service {
    private static final String CH = "rt_poll_v1";
    private static final int NID = 2002;
    private static final long POLL_MS = 5_000;
    private static final long BACKOFF_MS = 60_000;   // after a failed lookup (offline / rate limit)

    private volatile boolean running;
    private Thread loop;

    static void start(Context c) {
        try {
            Intent i = new Intent(c.getApplicationContext(), UpdatePollService.class);
            if (Build.VERSION.SDK_INT >= 26) c.getApplicationContext().startForegroundService(i);
            else c.getApplicationContext().startService(i);
        } catch (Throwable ignored) { }   // background-start restrictions: the WorkManager fallback still runs
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 34)
                ServiceCompat.startForeground(this, NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NID, n);
        } catch (Throwable t) { stopSelf(); return START_NOT_STICKY; }

        if (!running) {
            running = true;
            loop = new Thread(() -> {
                UpdateChecker uc = new UpdateChecker(getApplicationContext());
                while (running) {
                    boolean ok = uc.checkAndNotifyBlocking();
                    try { Thread.sleep(ok ? POLL_MS : BACKOFF_MS); }
                    catch (InterruptedException e) { return; }
                }
            }, "rt-update-poll");
            loop.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        super.onDestroy();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CH, "Update watcher", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setContentTitle("Rajib Tutorial")
            .setContentText("Watching for updates")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }
}
