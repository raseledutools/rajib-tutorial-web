package com.rajibtutorial;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Caches direct video files (non-YouTube) into app-internal storage.
 * YouTube links are skipped — they cannot be downloaded directly.
 *
 * Usage:
 *   VideoCache.startDownload(ctx, videoId, url, listener)
 *   VideoCache.getLocalPath(ctx, videoId)   → null if not cached
 *   VideoCache.isDownloaded(ctx, videoId)
 *   VideoCache.getProgress(videoId)         → 0-100, or -1 if not downloading
 *   VideoCache.cancel(videoId)
 *   VideoCache.delete(ctx, videoId)
 */
final class VideoCache {
    private VideoCache() {}

    interface ProgressListener {
        /** Called on main thread. percent 0-100 while downloading, 100 when done, -1 on error. */
        void onProgress(String videoId, int percent);
    }

    // active downloads: videoId → percent (0-100)
    private static final Map<String, Integer>  sProgress  = new HashMap<>();
    // listeners per videoId
    private static final Map<String, ProgressListener> sListeners = new HashMap<>();
    // cancellation flags
    private static final Map<String, Boolean>  sCancel    = new HashMap<>();

    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ── public API ──────────────────────────────────────────────────────────

    static boolean isYouTube(String url) {
        return url != null && (url.contains("youtube.com") || url.contains("youtu.be"));
    }

    /** Offline copies of directly-hosted videos are temporary: they expire after this many days. */
    static final long OFFLINE_DAYS   = 7;
    private static final long MAX_AGE_MS = OFFLINE_DAYS * 24L * 60 * 60 * 1000;

    /** Returns the cached file path if the video is fully downloaded and not expired, else null. */
    static String getLocalPath(Context ctx, String videoId) {
        File f = cacheFile(ctx, videoId);
        if (!f.exists() || f.length() <= 0) return null;
        if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
            // expired temporary copy — remove it; it will be re-saved next time it is played online
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        return f.getAbsolutePath();
    }

    /** Whole days left before the offline copy expires (0 if none / expired). */
    static int daysLeft(Context ctx, String videoId) {
        File f = cacheFile(ctx, videoId);
        if (!f.exists()) return 0;
        long left = MAX_AGE_MS - (System.currentTimeMillis() - f.lastModified());
        return left <= 0 ? 0 : (int) Math.ceil(left / 86400000.0);
    }

    static boolean isDownloaded(Context ctx, String videoId) {
        return getLocalPath(ctx, videoId) != null;
    }

    /** Returns current download percent (0-100), or -1 if not currently downloading. */
    static int getProgress(String videoId) {
        Integer p = sProgress.get(videoId);
        return p == null ? -1 : p;
    }

    static boolean isDownloading(String videoId) {
        return sProgress.containsKey(videoId);
    }

    /**
     * Starts a background download. Safe to call again if already downloading
     * (will attach the new listener and return). Skips if already downloaded.
     */
    static void startDownload(Context ctx, String videoId, String url, ProgressListener listener) {
        if (url == null || url.isEmpty() || isYouTube(url)) return;
        if (isDownloaded(ctx, videoId)) {
            if (listener != null) MAIN.post(() -> listener.onProgress(videoId, 100));
            return;
        }
        if (listener != null) sListeners.put(videoId, listener);
        if (isDownloading(videoId)) return; // already running, listener attached above

        sProgress.put(videoId, 0);
        sCancel.put(videoId, Boolean.FALSE);

        final Context appCtx = ctx.getApplicationContext();
        EX.execute(() -> {
            HttpURLConnection conn = null;
            File tmpFile = new File(cacheFile(appCtx, videoId).getAbsolutePath() + ".tmp");
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();
                int total = conn.getContentLength();
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(tmpFile);
                byte[] buf = new byte[8192];
                long downloaded = 0;
                int read;
                int lastPct = -1;
                while ((read = in.read(buf)) != -1) {
                    if (Boolean.TRUE.equals(sCancel.get(videoId))) {
                        out.close();
                        in.close();
                        tmpFile.delete();
                        sProgress.remove(videoId);
                        sCancel.remove(videoId);
                        return;
                    }
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        int pct = (int) (downloaded * 100L / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            sProgress.put(videoId, pct);
                            notifyListener(videoId, pct);
                        }
                    }
                }
                out.close();
                in.close();
                // rename tmp → final
                tmpFile.renameTo(cacheFile(appCtx, videoId));
                cacheFile(appCtx, videoId).setLastModified(System.currentTimeMillis()); // start the 7-day timer
                sProgress.remove(videoId);
                sCancel.remove(videoId);
                notifyListener(videoId, 100);
            } catch (Exception e) {
                if (tmpFile.exists()) tmpFile.delete();
                sProgress.remove(videoId);
                sCancel.remove(videoId);
                notifyListener(videoId, -1);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    static void cancel(String videoId) {
        sCancel.put(videoId, Boolean.TRUE);
    }

    static void delete(Context ctx, String videoId) {
        cancel(videoId);
        File f = cacheFile(ctx, videoId);
        if (f.exists()) f.delete();
        new File(f.getAbsolutePath() + ".tmp").delete();
    }

    static void setListener(String videoId, ProgressListener listener) {
        if (listener == null) sListeners.remove(videoId);
        else sListeners.put(videoId, listener);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static File cacheFile(Context ctx, String videoId) {
        File dir = new File(ctx.getFilesDir(), "video_cache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // sanitise id to safe filename
        String safe = videoId.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return new File(dir, safe + ".mp4");
    }

    private static void notifyListener(String videoId, int pct) {
        ProgressListener l = sListeners.get(videoId);
        if (l != null) MAIN.post(() -> l.onProgress(videoId, pct));
    }
}
