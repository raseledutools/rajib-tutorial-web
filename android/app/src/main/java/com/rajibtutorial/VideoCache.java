package com.rajibtutorial;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
        /** Called on main thread. percent 0-100 while downloading, 100 when done, -1 on error, -2 when cancelled. */
        void onProgress(String videoId, int percent);
    }

    // active downloads: videoId → percent (0-100)
    private static final Map<String, Integer>  sProgress  = new ConcurrentHashMap<>();
    // listeners per videoId
    private static final Map<String, ProgressListener> sListeners = new ConcurrentHashMap<>();
    // cancellation flags
    private static final Map<String, Boolean>  sCancel    = new ConcurrentHashMap<>();
    // bytes received so far (for "12.3 MB" when the server sends no size) and "looking up the YouTube link" phase
    private static final Map<String, Long> sBytes = new ConcurrentHashMap<>();
    private static final Set<String> sResolving = ConcurrentHashMap.newKeySet();

    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ── public API ──────────────────────────────────────────────────────────

    static boolean isYouTube(String url) {
        return url != null && (url.contains("youtube.com") || url.contains("youtu.be"));
    }

    /** Offline copies are temporary: they expire (and are deleted) after this many days. */
    static final long OFFLINE_DAYS   = 3;
    private static final long MAX_AGE_MS = OFFLINE_DAYS * 24L * 60 * 60 * 1000;

    /** Returns the cached file path if the video is fully downloaded and not expired, else null. */
    static String getLocalPath(Context ctx, String videoId) {
        File f = cacheFile(ctx, videoId);
        if (!f.exists() || f.length() <= OfflineCrypto.HDR) return null;
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

    /** Opens the saved (encrypted) copy for MediaPlayer; decrypts on the fly, nothing plain is written to disk. */
    @android.annotation.TargetApi(23)
    static android.media.MediaDataSource openLocal(Context ctx, String videoId) throws IOException {
        String p = getLocalPath(ctx, videoId);
        if (p == null) throw new IOException("not saved");
        return OfflineCrypto.open(ctx, new File(p));
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

    /** Bytes received so far by the running download (0 when not downloading). */
    static long getBytes(String videoId) {
        Long b = sBytes.get(videoId);
        return b == null ? 0 : b;
    }

    /** True while a YouTube link is still being looked up (before the first byte arrives). */
    static boolean isResolving(String videoId) {
        return sResolving.contains(videoId);
    }

    /** True after cancel() until the download thread has actually stopped. */
    static boolean isCancelling(String videoId) {
        return isDownloading(videoId) && Boolean.TRUE.equals(sCancel.get(videoId));
    }

    /** Size in bytes of the saved copy (0 if none). */
    static long sizeOf(Context ctx, String videoId) {
        String p = getLocalPath(ctx, videoId);
        return p == null ? 0 : OfflineCrypto.plainLength(new File(p));
    }

    private static boolean sMigrating = false;

    /** Removes expired copies and stale partial files (the exe does the same every few hours). */
    static void purgeExpired(Context ctx) {
        try {
            migrateLegacyAsync(ctx.getApplicationContext());
            File dir = OfflineCrypto.hiddenDir(ctx);
            File[] fs = dir.listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            boolean busy = !sProgress.isEmpty();            // a download is running: leave its partial files alone
            for (File f : fs) {
                String n = f.getName();
                boolean tmp = n.contains(".tmp") || n.endsWith(".enc");
                if (tmp && busy) continue;
                long age = now - f.lastModified();
                if ((!tmp && age > MAX_AGE_MS) || (tmp && age > 2 * 86400000L)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) { }
    }

    /** Older versions kept plain .mp4 files in files/video_cache: encrypt them into the hidden store (timer kept) and delete the originals. */
    private static synchronized void migrateLegacyAsync(final Context app) {
        if (sMigrating) return;
        File old = new File(app.getFilesDir(), "video_cache");
        if (!old.exists()) return;
        sMigrating = true;
        EX.execute(() -> {
            try {
                File[] fs = old.listFiles();
                if (fs != null) for (File f : fs) {
                    String n = f.getName();
                    if (OfflineCrypto.supported() && n.endsWith(".mp4") && f.length() > 0) {
                        File dst = OfflineCrypto.fileFor(app, n.substring(0, n.length() - 4));
                        if (!dst.exists()) {
                            File enc = new File(dst.getAbsolutePath() + ".enc");
                            try {
                                OfflineCrypto.encrypt(app, f, enc);
                                if (enc.renameTo(dst)) dst.setLastModified(f.lastModified()); else enc.delete();
                            } catch (IOException e) { enc.delete(); }
                        }
                    }
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            } catch (Exception ignored) {
            } finally {
                sMigrating = false;
            }
        });
    }

    /** Always leave this much free on the device (same rule as the exe). */
    private static final long MIN_FREE_BYTES = 300L * 1024 * 1024;
    private static final Map<String, String> sError = new ConcurrentHashMap<>();

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
        if (!OfflineCrypto.supported()) {
            sError.put(videoId, "Secure offline lessons need Android 6.0 or newer");
            if (listener != null) MAIN.post(() -> listener.onProgress(videoId, -1));
            return;
        }
        if (isDownloaded(ctx, videoId)) {
            if (listener != null) MAIN.post(() -> listener.onProgress(videoId, 100));
            return;
        }
        if (listener != null) sListeners.put(videoId, listener);
        if (isDownloading(videoId)) return; // already running, listener attached above

        sProgress.put(videoId, 0);
        sCancel.put(videoId, Boolean.FALSE);
        sBytes.put(videoId, 0L);
        sError.remove(videoId);

        final Context appCtx = ctx.getApplicationContext();
        final boolean yt = isYouTube(url);
        EX.execute(() -> {
            File tmpFile = new File(cacheFile(appCtx, videoId).getAbsolutePath() + ".tmp");
            File vTmp = new File(tmpFile.getAbsolutePath() + ".v");     // YouTube: video-only part
            File aTmp = new File(tmpFile.getAbsolutePath() + ".a");     // YouTube: audio-only part
            boolean keepTmp = false;
            try {
                String src = url, audio = null;
                if (yt) {
                    tmpFile.delete(); vTmp.delete(); aTmp.delete();   // signed YouTube links change: never resume those
                    sResolving.add(videoId);
                    YtResolver.Res r;
                    try { r = YtResolver.resolve(url); } finally { sResolving.remove(videoId); }
                    src = r.url;
                    audio = r.audioUrl;
                    checkCancel(videoId);
                }
                if (audio != null) {
                    // separate video + audio (like yt-dlp on the exe): download both, then merge into one mp4 on the phone
                    fetch(appCtx, videoId, src, vTmp, false, true, 0L, 0, 80);
                    fetch(appCtx, videoId, audio, aTmp, false, true, vTmp.length(), 80, 92);
                    setPct(videoId, 93);
                    try { merge(vTmp, aTmp, tmpFile, videoId); }
                    catch (CancelledException ce) { throw ce; }
                    catch (Exception me) { throw new IOException("could not merge video+audio (" + me + ")"); }
                } else {
                    keepTmp = !yt;                                    // direct files keep their partial file for resume
                    fetch(appCtx, videoId, src, tmpFile, !yt, yt, 0L, 0, 99);
                }
                File dst = cacheFile(appCtx, videoId);
                setPct(videoId, 99);
                File enc = new File(dst.getAbsolutePath() + ".enc");
                OfflineCrypto.encrypt(appCtx, tmpFile, enc);     // the plain download never stays on disk
                tmpFile.delete();
                if (dst.exists()) dst.delete();
                if (!enc.renameTo(dst)) { enc.delete(); throw new IOException("Could not save file"); }
                dst.setLastModified(System.currentTimeMillis()); // start the offline timer (OFFLINE_DAYS)
                sProgress.remove(videoId);
                sCancel.remove(videoId);
                notifyListener(videoId, 100);
            } catch (CancelledException ce) {
                tmpFile.delete();
                sProgress.remove(videoId);
                sCancel.remove(videoId);
                notifyListener(videoId, -2);
            } catch (Exception e) {
                if (!keepTmp && tmpFile.exists()) tmpFile.delete();
                String m = e.getMessage();
                sError.put(videoId, yt ? "YouTube: " + (m == null ? "could not get video" : m) : (m == null ? "Download failed" : m));
                sProgress.remove(videoId);
                sCancel.remove(videoId);
                notifyListener(videoId, -1);
            } finally {
                vTmp.delete();
                aTmp.delete();
                new File(cacheFile(appCtx, videoId).getAbsolutePath() + ".enc").delete();
                sBytes.remove(videoId);
                sResolving.remove(videoId);
            }
        });
    }

    // ── download helpers ────────────────────────────────────────────────────

    private static final class HttpStatus extends IOException {
        final int code;
        HttpStatus(int code) { super("Server error " + code); this.code = code; }
    }

    private static void checkCancel(String id) {
        if (Boolean.TRUE.equals(sCancel.get(id))) throw new CancelledException();
    }

    private static void setPct(String id, int pct) {
        sProgress.put(id, pct);
        notifyListener(id, pct);
    }

    private static void checkSpace(Context ctx, long total, long have) throws IOException {
        if (total > 0 && ctx.getFilesDir().getUsableSpace() - (total - have) < MIN_FREE_BYTES) throw new IOException("Not enough storage space");
    }

    /** Copies `in` to `out`, reporting progress mapped onto p0..p1 percent (or MB when the size is unknown). */
    private static void pump(InputStream in, FileOutputStream out, String id, long baseBytes, long done0, long total, int p0, int p1) throws IOException {
        byte[] buf = new byte[32768];
        long done = done0, lastPing = done0;
        int n, lastPct = -1;
        while ((n = in.read(buf)) != -1) {
            checkCancel(id);
            out.write(buf, 0, n);
            done += n;
            sBytes.put(id, baseBytes + done);
            if (total > 0) {
                int pct = (int) Math.min(p1, p0 + (p1 - p0) * done / total);
                if (pct != lastPct) { lastPct = pct; setPct(id, pct); }
            } else if (done - lastPing >= 512 * 1024) {      // size unknown: still refresh the "MB" label
                lastPing = done;
                notifyListener(id, 0);
            }
        }
    }

    private static HttpURLConnection open(String src, boolean yt) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (yt) c.setRequestProperty("User-Agent", YtResolver.userAgentFor(src));
        return c;
    }

    /** Downloads one URL into `file`. Direct videos resume; a YouTube link that answers 403 is retried in small range= chunks. */
    private static void fetch(Context ctx, String id, String src, File file, boolean resume, boolean yt, long baseBytes, int p0, int p1) throws Exception {
        try {
            fetchOnce(ctx, id, src, file, resume, yt, baseBytes, p0, p1);
        } catch (HttpStatus e) {
            if (!yt || e.code != 403) throw e;
            fetchChunked(ctx, id, src, file, baseBytes, p0, p1, e);
        }
    }

    private static void fetchOnce(Context ctx, String id, String src, File file, boolean resume, boolean yt, long baseBytes, int p0, int p1) throws Exception {
        if (!resume && file.exists()) file.delete();
        long have = file.exists() ? file.length() : 0;
        HttpURLConnection conn = open(src, yt);
        try {
            if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
            conn.connect();
            int code = conn.getResponseCode();
            if (code == 416) {                       // stale partial file: start over
                file.delete();
                throw new IOException("Partial file was invalid, tap again");
            }
            if (code != 200 && code != 206) throw new HttpStatus(code);
            boolean resumed = code == 206 && have > 0;
            if (!resumed) have = 0;
            long len = conn.getContentLengthLong();
            long total = len > 0 ? len + have : -1;
            checkSpace(ctx, total, have);
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(file, resumed)) {
                pump(in, out, id, baseBytes, have, total, p0, p1);
            }
            if (total > 0 && file.length() < total) throw new IOException("Connection lost, tap again to resume");
        } finally {
            conn.disconnect();
        }
    }

    /** Same thing YouTube's own players do: ask for the file in 4 MB pieces with the range= parameter. */
    private static void fetchChunked(Context ctx, String id, String src, File file, long baseBytes, int p0, int p1, HttpStatus first) throws Exception {
        long total = YtResolver.contentLength(src);
        if (total <= 0) throw first;
        checkSpace(ctx, total, 0);
        if (file.exists()) file.delete();
        final long chunk = 4L * 1024 * 1024;
        long pos = 0;
        while (pos < total) {
            checkCancel(id);
            long end = Math.min(total, pos + chunk) - 1;
            HttpURLConnection conn = open(src + "&range=" + pos + "-" + end, true);
            try {
                conn.connect();
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) throw new HttpStatus(code);
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(file, true)) {
                    pump(in, out, id, baseBytes, pos, total, p0, p1);
                }
            } finally {
                conn.disconnect();
            }
            long now = file.length();
            if (now <= pos) throw new IOException("Connection lost, tap again");
            pos = now;
        }
    }

    // ── merge video-only + audio-only into one mp4 (no ffmpeg needed) ───────

    private static int firstTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void merge(File video, File audio, File out, String id) throws Exception {
        MediaExtractor ve = new MediaExtractor(), ae = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vt = firstTrack(ve, "video/"), at = firstTrack(ae, "audio/");
            if (vt < 0 || at < 0) throw new IOException("downloaded file has no video or audio track");
            ve.selectTrack(vt);
            ae.selectTrack(at);
            if (out.exists()) out.delete();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vi = mux.addTrack(ve.getTrackFormat(vt));
            int ai = mux.addTrack(ae.getTrackFormat(at));
            mux.start();
            started = true;
            copySamples(ve, mux, vi, id);
            copySamples(ae, mux, ai, id);
            mux.stop();
            started = false;
        } finally {
            try { ve.release(); } catch (Exception ignored) { }
            try { ae.release(); } catch (Exception ignored) { }
            if (mux != null) {
                try { if (started) mux.stop(); } catch (Exception ignored) { }
                try { mux.release(); } catch (Exception ignored) { }
            }
        }
    }

    private static void copySamples(MediaExtractor ex, MediaMuxer mux, int track, String id) {
        ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            checkCancel(id);
            bi.offset = 0;
            bi.size = size;
            bi.presentationTimeUs = ex.getSampleTime();
            bi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            mux.writeSampleData(track, buf, bi);
            ex.advance();
        }
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
        new File(f.getAbsolutePath() + ".enc").delete();
    }

    static void setListener(String videoId, ProgressListener listener) {
        if (listener == null) sListeners.remove(videoId);
        else sListeners.put(videoId, listener);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /** Hidden + encrypted location of a lesson's offline copy (keyed-hash name, no extension, private no-backup storage). */
    private static File cacheFile(Context ctx, String videoId) {
        return OfflineCrypto.fileFor(ctx, videoId.replaceAll("[^A-Za-z0-9_\\-]", "_"));
    }

    private static void notifyListener(String videoId, int pct) {
        ProgressListener l = sListeners.get(videoId);
        if (l != null) MAIN.post(() -> l.onProgress(videoId, pct));
    }
}
