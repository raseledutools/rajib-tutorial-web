package com.rajibtutorial.ytpremium;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Update system (same idea as the Rajib Tutorial app's UpdateChecker): looks for a newer "ytp-v&lt;N&gt;" GitHub release,
 * notifies, downloads inside the app with progress and opens the system install prompt.
 * These releases are deliberately NOT the repo's "latest" release, so the releases list / feed is used instead.
 */
final class Updater {
    private Updater() {}

    private static final String REPO = "raseledutools/rajib-tutorial-web";
    private static final String ASSET = "YouTubePremiumTest.apk";
    private static final String APK_FILE = "YouTubePremiumTest-update.apk";
    private static final Pattern TAG = Pattern.compile("ytp-v(\\d+)");

    static final class Latest {
        final int code; final String tag, url;
        Latest(int code, String tag, String url) { this.code = code; this.tag = tag; this.url = url; }
        String versionName() { return "1." + code; }
    }

    interface DlListener {
        void onProgress(int percent);
        void onReady(File apk);
        void onError(String why);
    }

    static int installedCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Blocking (background thread only). Newest ytp release, or throws with the reason. */
    static Latest latest() throws Exception {
        // 1) fixed "ytp-latest" release: version.txt + APK are overwritten by every build, so it never scrolls out of
        //    GitHub's releases list (the main app creates a new release on every push) and has no API rate limit.
        try {
            String base = "https://github.com/" + REPO + "/releases/download/ytp-latest/";
            HttpURLConnection c = open(base + "version.txt");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() == 200) {
                int n = Integer.parseInt(read(c.getInputStream()).trim());
                c.disconnect();
                return new Latest(n, "ytp-v" + n, base + ASSET);
            }
            c.disconnect();
        } catch (Exception ignored) { }
        // 2) fallback: releases list / feed
        String why;
        try {
            HttpURLConnection c = open("https://api.github.com/repos/" + REPO + "/releases?per_page=30");
            c.setRequestProperty("Accept", "application/vnd.github.v3+json");
            int code = c.getResponseCode();
            if (code == 200) {
                JSONArray arr = new JSONArray(read(c.getInputStream()));
                Latest best = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    if (r.optBoolean("draft")) continue;
                    Matcher m = TAG.matcher(r.optString("tag_name"));
                    if (!m.matches()) continue;
                    int n = Integer.parseInt(m.group(1));
                    if (best != null && n <= best.code) continue;
                    JSONArray as = r.optJSONArray("assets");
                    if (as == null) continue;
                    for (int k = 0; k < as.length(); k++) {
                        JSONObject a = as.getJSONObject(k);
                        if (ASSET.equals(a.optString("name"))) {
                            best = new Latest(n, r.getString("tag_name"), a.getString("browser_download_url"));
                            break;
                        }
                    }
                }
                if (best != null) return best;
                why = "no release found";
            } else {
                why = "HTTP " + code + (code == 403 ? " (rate limit)" : "");
            }
            c.disconnect();
        } catch (Exception e) {
            why = e.getClass().getSimpleName();
        }
        // fallback: the public releases feed has no API rate limit
        try {
            HttpURLConnection c = open("https://github.com/" + REPO + "/releases.atom");
            if (c.getResponseCode() == 200) {
                Matcher m = Pattern.compile("/releases/tag/(ytp-v(\\d+))").matcher(read(c.getInputStream()));
                int best = -1; String tag = null;
                while (m.find()) {
                    int n = Integer.parseInt(m.group(2));
                    if (n > best) { best = n; tag = m.group(1); }
                }
                if (tag != null) return new Latest(best, tag, "https://github.com/" + REPO + "/releases/download/" + tag + "/" + ASSET);
            }
            c.disconnect();
        } catch (Exception ignored) { }
        throw new Exception(why);
    }

    /** Background check (UpdateWorker): notifies once per new release. false = network failed, retry later. */
    static boolean checkAndNotify(Context c) {
        try {
            Latest l = latest();
            if (l.code <= installedCode(c)) return true;
            SharedPreferences p = c.getSharedPreferences("ytp_update", Context.MODE_PRIVATE);
            if (p.getInt("notified", 0) >= l.code) return true;
            Notifs.update(c, l.tag, l.code, l.url);
            p.edit().putInt("notified", l.code).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static HttpURLConnection open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "YouTubePremiumTest-updater");
        return c;
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ── download + install ──────────────────────────────────────────────────

    static File apkFile(Context c) {
        File d = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (d == null) d = c.getCacheDir();
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, APK_FILE);
    }

    static void download(Context ctx, String url, DlListener l) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File dst = apkFile(app);
            File part = new File(dst.getAbsolutePath() + ".part");
            HttpURLConnection c = null;
            try {
                c = open(url);
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[32768];
                    long done = 0; int n, last = -1;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int p = (int) (done * 100 / total);
                            if (p != last) { last = p; final int fp = p; main.post(() -> l.onProgress(fp)); }
                        }
                    }
                }
                if (total > 0 && part.length() < total) throw new Exception("connection lost");
                if (dst.exists()) dst.delete();
                if (!part.renameTo(dst)) throw new Exception("could not save the file");
                main.post(() -> l.onReady(dst));
            } catch (Exception e) {
                part.delete();
                final String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> l.onError(why));
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    /** Opens the system installer (asks for the "install unknown apps" permission first when needed). */
    static boolean install(Context a, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return false;
            }
            Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
