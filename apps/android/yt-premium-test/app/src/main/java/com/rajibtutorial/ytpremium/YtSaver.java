package com.rajibtutorial.ytpremium;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Save system for the YouTube Premium Test app. Same engine as the course section's VideoCache
 * (YtResolver -> video + audio -> MediaMuxer merge), but without the course-only encryption / 3-day expiry:
 * the finished MP4 is kept in the app's Movies folder until the user deletes it.
 */
final class YtSaver {
    private YtSaver() {}

    interface Listener {
        /** Main thread. 0-99 while running, 100 done, -1 error, -2 cancelled. */
        void onProgress(String videoId, int percent);
    }

    private static final Map<String, Integer> sProgress = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> sCancel = new ConcurrentHashMap<>();
    private static final Map<String, String> sError = new ConcurrentHashMap<>();
    private static final Map<String, Listener> sListeners = new ConcurrentHashMap<>();
    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, String> sTitle = new ConcurrentHashMap<>();
    private static volatile Context sApp;
    private static final long MIN_FREE_BYTES = 200L * 1024 * 1024;

    // ── public API ──────────────────────────────────────────────────────────

    static File dir(Context ctx) {
        File d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (d == null) d = new File(ctx.getFilesDir(), "saved");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The saved mp4 for this video id, or null. */
    static File find(Context ctx, String id) {
        File[] fs = dir(ctx).listFiles();
        if (fs == null) return null;
        String tail = "[" + id + "].mp4";
        for (File f : fs) if (f.getName().endsWith(tail) && f.length() > 0) return f;
        return null;
    }

    static boolean isDownloading(String id) { return sProgress.containsKey(id); }
    static int progress(String id) { Integer p = sProgress.get(id); return p == null ? -1 : p; }
    static boolean anyRunning() { return !sProgress.isEmpty(); }
    static String lastError(String id) { String e = sError.get(id); return e == null ? "Download failed" : e; }
    static void cancel(String id) { sCancel.put(id, Boolean.TRUE); }
    static void setListener(String id, Listener l) { if (l == null) sListeners.remove(id); else sListeners.put(id, l); }

    static String safeName(String title) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\[\\]]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) t = "video";
        return t.length() > 80 ? t.substring(0, 80).trim() : t;
    }

    static void start(Context ctx, String id, String title, Listener l) { start(ctx, id, title, null, l); }

    /** `chosen` = the stream picked in the quality list (null: best available). */
    static void start(Context ctx, String id, String title, final YtResolver.Res chosen, Listener l) {
        if (l != null) sListeners.put(id, l);
        if (find(ctx, id) != null) { notifyL(id, 100); return; }
        if (isDownloading(id)) return;
        sApp = ctx.getApplicationContext();
        sTitle.put(id, safeName(title));
        sProgress.put(id, 0);
        sCancel.put(id, Boolean.FALSE);
        sError.remove(id);
        final Context app = ctx.getApplicationContext();
        final String name = safeName(title) + " [" + id + "].mp4";
        EX.execute(() -> {
            File d = dir(app);
            File vTmp = new File(d, ".tmp_" + id + ".v");
            File aTmp = new File(d, ".tmp_" + id + ".a");
            File mTmp = new File(d, ".tmp_" + id + ".mp4");
            try {
                YtResolver.Res r = chosen != null ? chosen : YtResolver.resolve("https://www.youtube.com/watch?v=" + id);
                checkCancel(id);
                if (r.audioUrl != null) {
                    // fast path: video + audio together, each cut into pieces fetched over several connections at once
                    boolean fast = false;
                    try {
                        fetchParallel(app, id, new String[]{r.url, r.audioUrl}, new File[]{vTmp, aTmp}, 92);
                        fast = true;
                    } catch (CancelledException ce) {
                        throw ce;
                    } catch (Exception ignored) { /* fall back to the old single-connection download */ }
                    if (!fast) {
                        fetch(app, id, r.url, vTmp, 0, 80);
                        fetch(app, id, r.audioUrl, aTmp, 80, 92);
                    }
                    setPct(id, 93);
                    merge(vTmp, aTmp, mTmp, id);
                } else {
                    boolean fast = false;
                    try {
                        fetchParallel(app, id, new String[]{r.url}, new File[]{mTmp}, 99);
                        fast = true;
                    } catch (CancelledException ce) {
                        throw ce;
                    } catch (Exception ignored) { }
                    if (!fast) fetch(app, id, r.url, mTmp, 0, 99);
                }
                File dst = new File(d, name);
                if (dst.exists()) dst.delete();
                if (!mTmp.renameTo(dst)) throw new IOException("Could not save file");
                sProgress.remove(id);
                sCancel.remove(id);
                notifyL(id, 100);
            } catch (CancelledException ce) {
                sProgress.remove(id);
                sCancel.remove(id);
                notifyL(id, -2);
            } catch (Exception e) {
                String m = e.getMessage();
                sError.put(id, m == null ? "Could not get video" : m);
                sProgress.remove(id);
                sCancel.remove(id);
                notifyL(id, -1);
            } finally {
                vTmp.delete();
                aTmp.delete();
                mTmp.delete();
            }
        });
    }

    // ── fast download: YouTube slows a single long connection, but serves short ranges (&range=a-b) at full speed.
    //    Cut the files into 2 MB pieces and fetch several at once (video and audio together). ──

    private static final int WORKERS = 6;
    private static final long PIECE = 2L * 1024 * 1024;

    private static final class Piece {
        final int file; final long start, end;
        Piece(int file, long start, long end) { this.file = file; this.start = start; this.end = end; }
    }

    private static void fetchParallel(Context ctx, String id, String[] urls, File[] files, int pMax) throws Exception {
        long[] totals = new long[urls.length];
        long grand = 0;
        for (int i = 0; i < urls.length; i++) {
            totals[i] = YtResolver.contentLength(urls[i]);
            if (totals[i] <= 0) throw new IOException("size unknown");
            grand += totals[i];
        }
        checkSpace(ctx, grand);
        final ConcurrentLinkedQueue<Piece> queue = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < urls.length; i++) {
            if (files[i].exists()) files[i].delete();
            try (RandomAccessFile raf = new RandomAccessFile(files[i], "rw")) { raf.setLength(totals[i]); }
            for (long pos = 0; pos < totals[i]; pos += PIECE) queue.add(new Piece(i, pos, Math.min(totals[i], pos + PIECE) - 1));
        }
        final AtomicLong done = new AtomicLong();
        final AtomicReference<Throwable> fail = new AtomicReference<>();
        final long total = grand;
        final int pm = pMax;
        final int[] last = {-1};
        final String[] u = urls;
        final File[] f = files;
        int n = Math.min(WORKERS, queue.size());
        ExecutorService pool = Executors.newFixedThreadPool(n);
        for (int w = 0; w < n; w++) {
            pool.execute(() -> {
                byte[] buf = new byte[65536];
                Piece pc;
                while (fail.get() == null && (pc = queue.poll()) != null) {
                    int attempt = 0;
                    while (true) {
                        try {
                            checkCancel(id);
                            getPiece(u[pc.file], f[pc.file], pc, buf, done, id, total, pm, last);
                            break;
                        } catch (CancelledException ce) {
                            fail.compareAndSet(null, ce);
                            return;
                        } catch (Throwable t) {
                            done.addAndGet(-PARTIAL.get().get());           // the failed attempt is counted again on retry
                            if (++attempt >= 3) { fail.compareAndSet(null, t); return; }
                        }
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(6, TimeUnit.HOURS);
        Throwable t = fail.get();
        if (t instanceof CancelledException) throw (CancelledException) t;
        if (t != null) throw new IOException(String.valueOf(t.getMessage()));
        for (int i = 0; i < files.length; i++) if (files[i].length() != totals[i]) throw new IOException("Connection lost, try again");
    }

    private static final ThreadLocal<AtomicLong> PARTIAL = new ThreadLocal<AtomicLong>() {
        @Override protected AtomicLong initialValue() { return new AtomicLong(); }
    };

    private static long getPiece(String src, File file, Piece pc, byte[] buf, AtomicLong done, String id, long total, int pMax, int[] last) throws Exception {
        AtomicLong mine = PARTIAL.get();
        mine.set(0);
        HttpURLConnection conn = open(src + "&range=" + pc.start + "-" + pc.end);
        try {
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new HttpStatus(code);
            long want = pc.end - pc.start + 1, got = 0;
            try (InputStream in = conn.getInputStream(); RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(pc.start);
                int n;
                while (got < want && (n = in.read(buf, 0, (int) Math.min(buf.length, want - got))) != -1) {
                    checkCancel(id);
                    raf.write(buf, 0, n);
                    got += n;
                    mine.addAndGet(n);
                    long d = done.addAndGet(n);
                    int pct = (int) Math.min(pMax, pMax * d / total);
                    synchronized (last) { if (pct != last[0]) { last[0] = pct; setPct(id, pct); } }
                }
            }
            if (got < want) throw new IOException("Connection lost, try again");
            return got;
        } finally {
            conn.disconnect();
        }
    }

    // ── download helpers (same approach as VideoCache) ──────────────────────

    private static final class HttpStatus extends IOException {
        final int code;
        HttpStatus(int code) { super("Server error " + code); this.code = code; }
    }

    private static final class CancelledException extends RuntimeException { }

    private static void checkCancel(String id) {
        if (Boolean.TRUE.equals(sCancel.get(id))) throw new CancelledException();
    }

    private static void setPct(String id, int pct) {
        sProgress.put(id, pct);
        notifyL(id, pct);
    }

    private static void notifyL(String id, int pct) {
        Listener l = sListeners.get(id);
        if (l != null) MAIN.post(() -> l.onProgress(id, pct));
        Context a = sApp;
        String t = sTitle.get(id);
        if (a != null && t != null) Notifs.save(a, id, t, pct);
    }

    private static void checkSpace(Context ctx, long total) throws IOException {
        if (total > 0 && dir(ctx).getUsableSpace() - total < MIN_FREE_BYTES) throw new IOException("Not enough storage space");
    }

    private static void pump(InputStream in, FileOutputStream out, String id, long done0, long total, int p0, int p1) throws IOException {
        byte[] buf = new byte[131072];
        long done = done0;
        int n, last = -1;
        while ((n = in.read(buf)) != -1) {
            checkCancel(id);
            out.write(buf, 0, n);
            done += n;
            if (total > 0) {
                int pct = (int) Math.min(p1, p0 + (p1 - p0) * done / total);
                if (pct != last) { last = pct; setPct(id, pct); }
            }
        }
    }

    private static HttpURLConnection open(String src) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", YtResolver.userAgentFor(src));
        return c;
    }

    private static void fetch(Context ctx, String id, String src, File file, int p0, int p1) throws Exception {
        try {
            fetchOnce(ctx, id, src, file, p0, p1);
        } catch (HttpStatus e) {
            if (e.code != 403) throw e;
            fetchChunked(ctx, id, src, file, p0, p1, e);
        }
    }

    private static void fetchOnce(Context ctx, String id, String src, File file, int p0, int p1) throws Exception {
        if (file.exists()) file.delete();
        HttpURLConnection conn = open(src);
        try {
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new HttpStatus(code);
            long total = conn.getContentLengthLong();
            checkSpace(ctx, total);
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                pump(in, out, id, 0, total, p0, p1);
            }
            if (total > 0 && file.length() < total) throw new IOException("Connection lost, try again");
        } finally {
            conn.disconnect();
        }
    }

    /** YouTube links that answer 403 work when the file is requested in 4 MB pieces (range= parameter). */
    private static void fetchChunked(Context ctx, String id, String src, File file, int p0, int p1, HttpStatus first) throws Exception {
        long total = YtResolver.contentLength(src);
        if (total <= 0) throw first;
        checkSpace(ctx, total);
        if (file.exists()) file.delete();
        final long chunk = 4L * 1024 * 1024;
        long pos = 0;
        while (pos < total) {
            checkCancel(id);
            long end = Math.min(total, pos + chunk) - 1;
            HttpURLConnection conn = open(src + "&range=" + pos + "-" + end);
            try {
                conn.connect();
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) throw new HttpStatus(code);
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(file, true)) {
                    pump(in, out, id, pos, total, p0, p1);
                }
            } finally {
                conn.disconnect();
            }
            long now = file.length();
            if (now <= pos) throw new IOException("Connection lost, try again");
            pos = now;
        }
    }

    // ── merge video-only + audio-only into one mp4 (no ffmpeg) ──────────────

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
}
