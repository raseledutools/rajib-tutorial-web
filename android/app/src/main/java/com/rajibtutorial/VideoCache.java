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
 * Saves lesson videos into app-internal storage for offline play.
 * Direct files resume after a failure; YouTube links are resolved to an MP4 via YtResolver.
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

    /** Always leave this much free on the device (same rule as the exe). */
    private static final long MIN_FREE_BYTES = 300L * 1024 * 1024;
    private static final Map<String, String> sError = new HashMap<>();

    /** Short human-readable reason of the last failed download of this video. */
    static String lastError(String videoId) {
        String e = sError.get(videoId);
        return e == null ? "Download failed" : e;
    }

    /**
     * Starts a background download (direct files resume after a failure; YouTube links are resolved to a
     * direct MP4 first). Safe to call again if already downloading. Skips if already downloaded.
     */
    static void startDownload(Context ctx, String videoId, String url, ProgressListener listener) {
        if (url == null || url.isEmpty()) return;
        if (isDownloaded(ctx, videoId)) {
            if (listener != null) MAIN.post(() -> listener.onProgress(videoId, 100));
            return;
        }
        if (listener != null) sListeners.put(videoId, listener);
        if (isDownloading(videoId)) return; // already running, listener attached above

        sProgress.put(videoId, 0);
        sCancel.put(videoId, Boolean.FALSE);
        sError.remove(videoId);

        final Context appCtx = ctx.getApplicationContext();
        final boolean yt = isYouTube(url);
        EX.execute(() -> {
            HttpURLConnection conn = null;
            File tmpFile = new File(cacheFile(appCtx, videoId).getAbsolutePath() + ".tmp");
            boolean keepTmp = false;
            try {
                String src = url;
                if (yt) {
                    if (tmpFile.exists()) tmpFile.delete();   // signed YouTube links change: never resume those
                    src = YtResolver.resolve(url);
                    if (Boolean.TRUE.equals(sCancel.get(videoId))) throw new CancelledException();
                }
                long have = tmpFile.exists() ? tmpFile.length() : 0;
                conn = (HttpURLConnection) new URL(src).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                if (yt) conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0");
                if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
                conn.connect();
                int code = conn.getResponseCode();
                if (code == 416) {                       // stale partial file: start over
                    tmpFile.delete();
                    throw new java.io.IOException("Partial file was invalid, tap again");
                }
                if (code != 200 && code != 206) throw new java.io.IOException("Server error " + code);
                boolean resumed = code == 206 && have > 0;
                if (!resumed) have = 0;
                long len = conn.getContentLengthLong();
                long total = len > 0 ? len + have : -1;
                if (total > 0 && appCtx.getFilesDir().getUsableSpace() - (total - have) < MIN_FREE_BYTES) {
                    throw new java.io.IOException("Not enough storage space");
                }
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(tmpFile, resumed);
                byte[] buf = new byte[16384];
                long downloaded = have;
                int read;
                int lastPct = -1;
                keepTmp = !yt;
                try {
                    while ((read = in.read(buf)) != -1) {
                        if (Boolean.TRUE.equals(sCancel.get(videoId))) throw new CancelledException();
                        out.write(buf, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int pct = (int) Math.min(99, downloaded * 100L / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                sProgress.put(videoId, pct);
                                notifyListener(videoId, pct);
                            }
                        }
                    }
                } finally {
                    try { out.close(); } catch (Exception ignored) { }
                    try { in.close(); } catch (Exception ignored) { }
                }
                if (total > 0 && downloaded < total) throw new java.io.IOException("Connection lost, tap again to resume");
                File dst = cacheFile(appCtx, videoId);
                if (dst.exists()) dst.delete();
                if (!tmpFile.renameTo(dst)) throw new java.io.IOException("Could not save file");
                dst.setLastModified(System.currentTimeMillis()); // start the 7-day timer
                sProgress.remove(videoId);
                sCancel.remove(videoId);
                notifyListener(videoId, 100);
            } catch (CancelledException ce) {
                tmpFile.delete();
                sProgress.remove(videoId);
                sCancel.remove(videoId);
            } catch (Exception e) {
                if (!keepTmp && tmpFile.exists()) tmpFile.delete();
                String m = e.getMessage();
                sError.put(videoId, yt ? "YouTube: " + (m == null ? "could not get video" : m) : (m == null ? "Download failed" : m));
                sProgress.remove(videoId);
                sCancel.remove(videoId);
                notifyListener(videoId, -1);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static final class CancelledException extends RuntimeException { }

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
