package com.rajibtutorial;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private static final String CHANNEL_ID    = "update_channel";
    private static final String CHANNEL_NAME  = "App Updates";
    private static final int    NOTIF_ID      = 1001;
    private static final String PREFS_NAME    = "UpdateCheckerPrefs";
    private static final String KEY_LAST_TAG  = "last_notified_tag";

    private static final String API_URL =
        "https://api.github.com/repos/" + BuildConfig.GITHUB_OWNER +
        "/" + BuildConfig.GITHUB_REPO + "/releases/latest";

    private final Context context;

    public UpdateChecker(Context context) {
        this.context = context;
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Rajib Tutorial app update notifications");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ── Background thread এ update check ──────────────────────────────────
    public void checkForUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                if (conn.getResponseCode() != 200) return;

                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json    = new JSONObject(sb.toString());
                String tagName     = json.getString("tag_name");   // e.g. "v42"
                String releaseNote = json.optString("body", "নতুন আপডেট পাওয়া গেছে।");
                String apkUrl      = "";

                if (json.has("assets")) {
                    var assets = json.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.getString("name").endsWith(".apk")) {
                            apkUrl = asset.getString("browser_download_url");
                            break;
                        }
                    }
                }

                // tag থেকে version code বের করি (v2, v42...)
                int remoteCode = 0;
                try {
                    remoteCode = Integer.parseInt(tagName.replace("v", "").trim());
                } catch (Exception ignored) {}

                int localCode = BuildConfig.VERSION_CODE;
                if (remoteCode <= localCode || apkUrl.isEmpty()) return;

                // ── Duplicate notification check ──────────────────────────
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String lastNotifiedTag  = prefs.getString(KEY_LAST_TAG, "");
                if (tagName.equals(lastNotifiedTag)) return;  // already notified

                final String finalApkUrl = apkUrl;
                final String finalNote   = releaseNote;
                final String finalTag    = tagName;

                new Handler(Looper.getMainLooper()).post(() -> {
                    showUpdateNotification(finalTag, finalNote, finalApkUrl);
                    // Save যে এই version এর notification পাঠানো হয়েছে
                    prefs.edit().putString(KEY_LAST_TAG, finalTag).apply();
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ── Notification দেখাই ────────────────────────────────────────────────
    private void showUpdateNotification(String version, String note, String apkUrl) {
        Intent downloadIntent = new Intent(context, DownloadReceiver.class);
        downloadIntent.putExtra("apk_url", apkUrl);
        downloadIntent.putExtra("version", version);

        PendingIntent pi = PendingIntent.getBroadcast(
            context, 0, downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String shortNote = note.length() > 200 ? note.substring(0, 200) + "…" : note;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("🆕 আপডেট পাওয়া গেছে! (" + version + ")")
            .setContentText("ক্লিক করুন — ডাউনলোড করে ইনস্টল হবে।")
            .setStyle(new NotificationCompat.BigTextStyle().bigText(shortNote))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.stat_sys_download, "এখনই আপডেট করুন", pi);

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    // ── BroadcastReceiver — notification click হলে download শুরু ──────────
    public static class DownloadReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context ctx, Intent intent) {
            String apkUrl  = intent.getStringExtra("apk_url");
            String version = intent.getStringExtra("version");
            if (apkUrl == null || apkUrl.isEmpty()) return;

            String fileName = "RajibTutorial-" + version + ".apk";

            // পুরনো ফাইল থাকলে মুছে দাও
            File old = new File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
            if (old.exists()) old.delete();

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Rajib Tutorial " + version)
                .setDescription("আপডেট ডাউনলোড হচ্ছে...")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    ctx, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setMimeType("application/vnd.android.package-archive");

            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            dm.enqueue(req);

            // Download complete হলে install prompt
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(new InstallReceiver(ctx, fileName), filter,
                    Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(new InstallReceiver(ctx, fileName), filter);
            }
        }
    }

    // ── Download complete → install trigger ───────────────────────────────
    public static class InstallReceiver extends BroadcastReceiver {
        private final Context ctx;
        private final String fileName;

        public InstallReceiver(Context ctx, String fileName) {
            this.ctx      = ctx;
            this.fileName = fileName;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            File apkFile = new File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
            if (!apkFile.exists() || apkFile.length() == 0) return;

            Uri apkUri = FileProvider.getUriForFile(
                ctx, ctx.getPackageName() + ".provider", apkFile);

            Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            ctx.startActivity(installIntent);
        }
    }
}
