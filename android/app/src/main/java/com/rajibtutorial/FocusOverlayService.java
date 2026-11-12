package com.rajibtutorial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import java.util.SortedMap;
import java.util.TreeMap;

public class FocusOverlayService extends Service {

    public static final String ACTION_START = "START";
    public static final String ACTION_STOP  = "STOP";

    private static final String CH_ID   = "focus_overlay_ch";
    private static final int    NOTIF_ID = 3001;
    private static final long   POLL_MS  = 1000; // প্রতি ১ সেকেন্ড

    private WindowManager   wm;
    private View            overlayView;
    private FocusManager    focusManager;
    private Handler         handler;
    private Runnable        pollTask;
    private String          lastBlockedPkg = "";
    private boolean         overlayShowing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        wm           = (WindowManager) getSystemService(WINDOW_SERVICE);
        focusManager = new FocusManager(this);
        handler      = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "";

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Foreground notification
        startForeground(NOTIF_ID, buildNotification());

        // Poll loop শুরু করো
        startPolling();
        return START_STICKY;
    }

    // ── Polling ───────────────────────────────────────────────────────────
    private void startPolling() {
        if (pollTask != null) handler.removeCallbacks(pollTask);
        pollTask = new Runnable() {
            @Override public void run() {
                if (!focusManager.isFocusEnabled()) {
                    hideOverlay();
                    handler.postDelayed(this, POLL_MS * 5);
                    return;
                }
                String fg = getForegroundApp();
                if (fg != null && focusManager.isAppOverLimit(fg)) {
                    showOverlay(fg);
                } else {
                    hideOverlay();
                    // Usage tracking
                    if (fg != null && !fg.equals(getPackageName())) {
                        // foreground app এর জন্য usage minute নিজেই হয় না,
                        // system UsageStats থেকে সেটা আসে।
                        // এখানে শুধু minute check করি।
                    }
                }
                handler.postDelayed(this, POLL_MS);
            }
        };
        handler.post(pollTask);
    }

    // ── Foreground app detect ─────────────────────────────────────────────
    private String getForegroundApp() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            SortedMap<Long, UsageStats> sorted = new TreeMap<>();
            for (UsageStats us : usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 10_000, now)) {
                sorted.put(us.getLastTimeUsed(), us);
            }
            if (!sorted.isEmpty()) return sorted.get(sorted.lastKey()).getPackageName();
        } catch (Exception ignored) {}
        return null;
    }

    // ── Overlay show/hide ─────────────────────────────────────────────────
    private void showOverlay(String pkg) {
        if (overlayShowing && pkg.equals(lastBlockedPkg)) return;
        hideOverlay();
        lastBlockedPkg = pkg;

        // App label বের করো
        String label = pkg;
        for (FocusManager.AppRule r : focusManager.getAppRules()) {
            if (r.packageName.equals(pkg)) { label = r.appLabel; break; }
        }

        // Overlay inflate
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_focus_block, null);
        TextView appName = overlayView.findViewById(R.id.overlayAppName);
        TextView subText = overlayView.findViewById(R.id.overlaySubText);
        TextView btnBack = overlayView.findViewById(R.id.overlayBtnBack);
        TextView btnUnlock = overlayView.findViewById(R.id.overlayBtnUnlock);
        TextView timerText = overlayView.findViewById(R.id.overlayTimer);

        appName.setText("\"" + label + "\" বন্ধ");
        subText.setText("এই app এর সময় শেষ হয়ে গেছে।\nFocus Mode চালু আছে।");

        // Remaining session timer
        updateTimer(timerText);
        Handler timerH = new Handler(Looper.getMainLooper());
        timerH.postDelayed(new Runnable(){
            @Override public void run() {
                if (overlayView != null && timerText != null) {
                    updateTimer(timerText);
                    timerH.postDelayed(this, 1000);
                }
            }
        }, 1000);

        // Back button — home screen এ যাও
        final String fLabel = label;
        btnBack.setOnClickListener(v -> {
            hideOverlay();
            Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        });

        // Unlock — PIN দিয়ে unlock
        btnUnlock.setOnClickListener(v -> {
            hideOverlay();
            Intent i = new Intent(this, FocusActivity.class)
                .putExtra("action", "unlock_app")
                .putExtra("pkg", pkg)
                .putExtra("label", fLabel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });

        // WindowManager params
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        // Touch enable করো
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        wm.addView(overlayView, params);
        overlayShowing = true;
    }

    private void updateTimer(TextView tv) {
        if (tv == null) return;
        long rem = focusManager.getRemainingMs();
        if (rem <= 0) { tv.setVisibility(View.GONE); return; }
        long mins = rem / 60000;
        long secs = (rem % 60000) / 1000;
        tv.setVisibility(View.VISIBLE);
        tv.setText(String.format("Session শেষ: %d:%02d", mins, secs));
    }

    private void hideOverlay() {
        if (overlayView != null && overlayShowing) {
            try { wm.removeView(overlayView); } catch (Exception ignored) {}
            overlayView  = null;
            overlayShowing = false;
            lastBlockedPkg = "";
        }
    }

    // ── Notification ──────────────────────────────────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CH_ID, "Focus Mode", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Focus Mode চালু আছে");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, FocusActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, FocusOverlayService.class)
            .setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Focus Mode চালু")
            .setContentText("নির্ধারিত app গুলো monitor হচ্ছে")
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "বন্ধ করুন", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (pollTask != null) handler.removeCallbacks(pollTask);
        hideOverlay();
        focusManager.setFocusEnabled(false);
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
