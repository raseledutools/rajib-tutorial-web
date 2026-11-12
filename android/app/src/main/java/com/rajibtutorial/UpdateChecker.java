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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private static final String CHANNEL_ID   = "rt_update_v2";
    private static final String CHANNEL_NAME = "আপডেট বিজ্ঞপ্তি";
    private static final int    NOTIF_ID     = 2001;
    private static final String PREFS_NAME   = "UpdatePrefs";
    private static final String KEY_LAST_TAG = "last_tag";

    private static final String API_URL =
        "https://api.github.com/repos/" + BuildConfig.GITHUB_OWNER +
        "/" + BuildConfig.GITHUB_REPO + "/releases/latest";

    // In-app banner এর জন্য callback
    public interface UpdateListener {
        void onUpdateAvailable(String version, String apkUrl);
    }

    private final Context        context;
    private       UpdateListener listener;

    public UpdateChecker(Context context) {
        this.context = context;
        createNotificationChannel();
    }

    public void setUpdateListener(UpdateListener l) {
        this.listener = l;
    }

    // ── Notification channel ───────────────────────────────────────────────
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Rajib Tutorial নতুন সংস্করণের বিজ্ঞপ্তি");
            ch.enableVibration(true);
            ch.setShowBadge(true);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ── Background thread এ update check ──────────────────────────────────
    public void checkForUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                if (conn.getResponseCode() != 200) return;

                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json    = new JSONObject(sb.toString());
                String tagName     = json.getString("tag_name");
                String releaseBody = json.optString("body", "");
                String apkUrl      = "";

                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.getString("name").endsWith(".apk")) {
                            apkUrl = a.getString("browser_download_url");
                            break;
                        }
                    }
                }

                int remoteCode = 0;
                try { remoteCode = Integer.parseInt(tagName.replace("v","").trim()); }
                catch (Exception ignored) {}

                if (remoteCode <= BuildConfig.VERSION_CODE || apkUrl.isEmpty()) return;

                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String lastTag = prefs.getString(KEY_LAST_TAG, "");

                final String fApkUrl = apkUrl;
                final String fTag    = tagName;
                final String fBody   = releaseBody;

                new Handler(Looper.getMainLooper()).post(() -> {
                    // In-app banner
                    if (listener != null) listener.onUpdateAvailable(fTag, fApkUrl);

                    // Notification — শুধু প্রথমবার
                    if (!fTag.equals(lastTag)) {
                        showUpdateNotification(fTag, fBody, fApkUrl);
                        prefs.edit().putString(KEY_LAST_TAG, fTag).apply();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ── Professional notification ──────────────────────────────────────────
    private void showUpdateNotification(String version, String body, String apkUrl) {

        // Download action intent
        Intent dlIntent = new Intent(context, DownloadReceiver.class);
        dlIntent.putExtra("apk_url", apkUrl);
        dlIntent.putExtra("version", version);
        PendingIntent dlPi = PendingIntent.getBroadcast(
            context, 0, dlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // "পরে দেখব" dismiss intent
        Intent dismissIntent = new Intent(context, DownloadReceiver.class);
        dismissIntent.putExtra("dismiss", true);
        PendingIntent dismissPi = PendingIntent.getBroadcast(
            context, 1, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Version number parse: "v7" → "1.7"
        String vNum = version.replace("v", "");
        String vName = "1." + vNum;

        // Release note থেকে মূল অংশ বের করি (মার্কডাউন ছাড়া)
        String cleanBody = body
            .replaceAll("##\\s*", "")
            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
            .replaceAll(">\\s*", "")
            .replaceAll("-\\s*", "• ")
            .trim();
        if (cleanBody.length() > 180) cleanBody = cleanBody.substring(0, 180) + "…";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            // Icon
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(0xE8365D)

            // Content
            .setContentTitle("Rajib Tutorial " + vName + " পাওয়া গেছে")
            .setContentText("নতুন সংস্করণ install করতে এখানে চাপুন")

            // Expanded view
            .setStyle(new NotificationCompat.BigTextStyle()
                .setBigContentTitle("🎉 Rajib Tutorial " + vName + " এসেছে!")
                .bigText(
                    cleanBody.isEmpty()
                        ? "নতুন সংস্করণে bug fix ও উন্নতি করা হয়েছে।\nনিচের বাটনে চাপলে download ও install হবে।"
                        : cleanBody + "\n\nনিচের বাটনে চাপলে download ও install হবে।"
                )
                .setSummaryText("সংস্করণ " + vName)
            )

            // Actions
            .addAction(
                android.R.drawable.stat_sys_download,
                "⬇️  এখনই আপডেট করুন",
                dlPi
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "পরে দেখব",
                dismissPi
            )

            // Behavior
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(dlPi);   // notification body তেও tap করলে download শুরু

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    // ── BroadcastReceiver — notification এ tap → download শুরু ───────────
    public static class DownloadReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent.getBooleanExtra("dismiss", false)) return;  // "পরে দেখব"

            String apkUrl  = intent.getStringExtra("apk_url");
            String version = intent.getStringExtra("version");
            if (apkUrl == null || apkUrl.isEmpty()) return;

            String fileName = "RajibTutorial-" + version + ".apk";

            // পুরনো ফাইল মুছো
            File old = new File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
            if (old.exists()) old.delete();

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Rajib Tutorial " + version + " download হচ্ছে")
                .setDescription("সম্পন্ন হলে install এর জন্য tap করুন")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    ctx, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setMimeType("application/vnd.android.package-archive");

            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            long downloadId = dm.enqueue(req);

            // Download complete হলে install prompt
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(
                    new InstallReceiver(ctx, fileName, downloadId), filter,
                    Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(
                    new InstallReceiver(ctx, fileName, downloadId), filter);
            }
        }
    }

    // ── Download complete → install trigger ───────────────────────────────
    public static class InstallReceiver extends BroadcastReceiver {
        private final Context ctx;
        private final String  fileName;
        private final long    downloadId;

        public InstallReceiver(Context ctx, String fileName, long downloadId) {
            this.ctx        = ctx;
            this.fileName   = fileName;
            this.downloadId = downloadId;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != downloadId) return;  // অন্য কোনো download এর event ignore করো

            File apkFile = new File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
            if (!apkFile.exists() || apkFile.length() < 1000) return;

            Uri apkUri = FileProvider.getUriForFile(
                ctx, ctx.getPackageName() + ".provider", apkFile);

            Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            ctx.startActivity(install);
        }
    }
}
