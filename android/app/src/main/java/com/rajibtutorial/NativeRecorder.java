package com.rajibtutorial;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records the captured screen (+ microphone + app playback audio) straight to a real MP4 file.
 *
 * Why this exists: the old WebView MediaRecorder wrote a streamed .webm with no duration/seek index
 * (gallery shows 00:00 / won't play) and stopped producing data whenever the call page went to the
 * background. This class runs inside the foreground ScreenCaptureService, so it keeps recording on any
 * screen, and MediaMuxer writes a proper MP4 with duration.
 *
 * Frames are pushed by ScreenCaptureService (same bitmap it already builds for screen share), so only
 * ONE VirtualDisplay is ever created (Android 14+ forbids more than one per MediaProjection).
 */
final class NativeRecorder {
    private static final int SAMPLE_RATE = 44100;
    private static final int FPS = 15;

    private final Context ctx;
    private final MediaProjection projection;
    private final int w, h;

    private MediaCodec venc, aenc;
    private Surface vsurf;
    private MediaMuxer muxer;
    private AudioRecord micRec, playRec;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private File file;
    private String label = "";

    private final Object frameLock = new Object();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst;

    private volatile boolean running = false;
    private volatile boolean abort = false;
    private boolean hasAudio = false;
    private int vTrack = -1, aTrack = -1;
    private volatile boolean muxStarted = false;
    private boolean wroteAny = false;
    private long baseUs = 0;
    private long lastV = -1, lastA = -1;
    private Thread drainThread, audioThread;

    NativeRecorder(Context c, MediaProjection mp, int w, int h) {
        this.ctx = c;
        this.projection = mp;
        this.w = w;
        this.h = h;
        this.dst = new Rect(0, 0, w, h);
    }

    String label() { return label; }

    /** @return true when recording really started */
    boolean start() {
        try {
            openOutput();

            MediaFormat vf = MediaFormat.createVideoFormat("video/avc", w, h);
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            vf.setInteger(MediaFormat.KEY_BIT_RATE, 2500000);
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            venc = MediaCodec.createEncoderByType("video/avc");
            venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            vsurf = venc.createInputSurface();
            venc.start();

            hasAudio = initAudio();

            baseUs = System.nanoTime() / 1000L;
            running = true;

            // make sure the video track gets its format even before the first real frame arrives
            pushBlack();

            drainThread = new Thread(this::drainLoop, "rec-drain");
            drainThread.start();
            if (hasAudio) {
                audioThread = new Thread(this::audioLoop, "rec-audio");
                audioThread.start();
            }
            return true;
        } catch (Throwable t) {
            running = false;
            releaseAll(false);
            return false;
        }
    }

    // ── output file ─────────────────────────────────────────
    private void openOutput() throws Exception {
        String name = "RajibTutorial_rec_"
            + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RajibTutorial");
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("no uri");
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "rw");
            if (pfd == null) throw new IllegalStateException("no fd");
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            label = "Movies/RajibTutorial/" + name;
        } else {
            File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) dir = ctx.getFilesDir();
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            file = new File(dir, name);
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            label = file.getAbsolutePath();
        }
    }

    // ── audio (mic + what this app plays, e.g. the other people in the call) ──
    private boolean initAudio() {
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return false;
            int buf = Math.max(min * 2, 16384);
            micRec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
            if (micRec.getState() != AudioRecord.STATE_INITIALIZED) {
                micRec.release();
                micRec = null;
                return false;
            }
            // best effort: playback capture only works for MEDIA/GAME/UNKNOWN usages on Android 10+
            if (Build.VERSION.SDK_INT >= 29 && projection != null) {
                try {
                    AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build();
                    AudioRecord p = new AudioRecord.Builder()
                        .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                        .setBufferSizeInBytes(buf)
                        .setAudioPlaybackCaptureConfig(cfg)
                        .build();
                    if (p.getState() == AudioRecord.STATE_INITIALIZED) playRec = p; else p.release();
                } catch (Throwable ignored) { playRec = null; }
            }

            MediaFormat af = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 1);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            aenc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aenc.start();
            return true;
        } catch (Throwable t) {
            try { if (micRec != null) micRec.release(); } catch (Throwable ignored) { }
            try { if (playRec != null) playRec.release(); } catch (Throwable ignored) { }
            try { if (aenc != null) aenc.release(); } catch (Throwable ignored) { }
            micRec = null; playRec = null; aenc = null;
            return false;
        }
    }

    private void audioLoop() {
        final short[] mic = new short[2048];
        final short[] pl = new short[2048];
        final byte[] out = new byte[mic.length * 2];
        long samples = 0;
        final long base = System.nanoTime() / 1000L;
        try {
            micRec.startRecording();
            if (playRec != null) {
                try { playRec.startRecording(); } catch (Throwable t) { playRec = null; }
            }
            while (running) {
                int n = micRec.read(mic, 0, mic.length);
                if (n <= 0) {
                    if (n < 0) break;
                    continue;
                }
                int m = 0;
                if (playRec != null && Build.VERSION.SDK_INT >= 23) {
                    m = playRec.read(pl, 0, n, AudioRecord.READ_NON_BLOCKING);
                    if (m < 0) m = 0;
                }
                for (int i = 0; i < n; i++) {
                    int s = mic[i];
                    if (i < m) s += pl[i];
                    if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
                    out[2 * i] = (byte) (s & 0xFF);
                    out[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
                }
                feedAudio(out, n * 2, base + samples * 1000000L / SAMPLE_RATE, false);
                samples += n;
            }
        } catch (Throwable ignored) {
        } finally {
            feedAudio(null, 0, base + samples * 1000000L / SAMPLE_RATE, true);
            try { micRec.stop(); } catch (Throwable ignored) { }
            try { if (playRec != null) playRec.stop(); } catch (Throwable ignored) { }
        }
    }

    private void feedAudio(byte[] data, int len, long ptsUs, boolean eos) {
        for (int tries = 0; tries < (eos ? 20 : 1) && !abort; tries++) {
            try {
                int idx = aenc.dequeueInputBuffer(20000);
                if (idx < 0) continue;
                ByteBuffer in = aenc.getInputBuffer(idx);
                if (in == null) return;
                in.clear();
                if (data != null && len > 0) in.put(data, 0, Math.min(len, in.remaining()));
                aenc.queueInputBuffer(idx, 0, in.position(), Math.max(0, ptsUs - baseUs),
                    eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                return;
            } catch (Throwable t) {
                return;
            }
        }
    }

    // ── video frames ────────────────────────────────────────
    void push(Bitmap b) {
        if (!running || b == null || b.isRecycled()) return;
        Surface s = vsurf;
        if (s == null) return;
        synchronized (frameLock) {
            Canvas c = null;
            try {
                c = s.lockCanvas(null);
                c.drawBitmap(b, null, dst, paint);
            } catch (Throwable ignored) {
            } finally {
                if (c != null) { try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) { } }
            }
        }
    }

    private void pushBlack() {
        Surface s = vsurf;
        if (s == null) return;
        synchronized (frameLock) {
            Canvas c = null;
            try {
                c = s.lockCanvas(null);
                c.drawColor(Color.BLACK);
            } catch (Throwable ignored) {
            } finally {
                if (c != null) { try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) { } }
            }
        }
    }

    // ── encoder output → muxer (one thread serves both encoders) ──
    private void drainLoop() {
        final MediaCodec.BufferInfo vi = new MediaCodec.BufferInfo();
        final MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
        boolean vDone = false, aDone = !hasAudio;
        try {
            while (!(vDone && aDone) && !abort) {
                // a track whose format is already added waits until the muxer has started
                if (!vDone && (vTrack < 0 || muxStarted)) vDone = drainOne(venc, vi, true);
                if (!aDone && (aTrack < 0 || muxStarted)) aDone = drainOne(aenc, ai, false);
            }
        } catch (Throwable ignored) { }
    }

    /** @return true when this encoder reached end of stream */
    private boolean drainOne(MediaCodec c, MediaCodec.BufferInfo info, boolean video) {
        int idx = c.dequeueOutputBuffer(info, 5000);
        if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            int t = muxer.addTrack(c.getOutputFormat());
            if (video) vTrack = t; else aTrack = t;
            if (vTrack >= 0 && (aTrack >= 0 || !hasAudio)) {
                muxer.start();
                muxStarted = true;
            }
            return false;
        }
        if (idx < 0) return false;

        ByteBuffer buf = c.getOutputBuffer(idx);
        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
        if (buf != null && info.size > 0 && muxStarted) {
            long pts = info.presentationTimeUs;
            if (video) {
                pts -= baseUs;                       // surface timestamps are on the nanoTime clock
                if (pts <= lastV) pts = lastV + 1;
                lastV = pts;
            } else {
                if (pts <= lastA) pts = lastA + 1;
                lastA = pts;
            }
            if (pts < 0) pts = 0;
            info.presentationTimeUs = pts;
            buf.position(info.offset);
            buf.limit(info.offset + info.size);
            muxer.writeSampleData(video ? vTrack : aTrack, buf, info);
            wroteAny = true;
        }
        c.releaseOutputBuffer(idx, false);
        return eos;
    }

    // ── stop ────────────────────────────────────────────────
    /** Finishes the MP4. Returns the saved location or "" when nothing usable was recorded. */
    synchronized String stop() {
        if (!running && venc == null && muxer == null) return "";
        running = false;
        try { if (audioThread != null) audioThread.join(2500); } catch (InterruptedException ignored) { }
        try { if (venc != null) venc.signalEndOfInputStream(); } catch (Throwable ignored) { }
        try { if (drainThread != null) drainThread.join(3000); } catch (InterruptedException ignored) { }
        abort = true;
        try { if (drainThread != null) drainThread.join(500); } catch (InterruptedException ignored) { }
        boolean ok = muxStarted && wroteAny;
        return releaseAll(ok) ? label : "";
    }

    private boolean releaseAll(boolean ok) {
        boolean saved = false;
        try { if (muxer != null) { if (muxStarted) muxer.stop(); saved = ok; } } catch (Throwable t) { saved = false; }
        try { if (muxer != null) muxer.release(); } catch (Throwable ignored) { }
        try { if (venc != null) { venc.stop(); } } catch (Throwable ignored) { }
        try { if (venc != null) venc.release(); } catch (Throwable ignored) { }
        try { if (aenc != null) { aenc.stop(); } } catch (Throwable ignored) { }
        try { if (aenc != null) aenc.release(); } catch (Throwable ignored) { }
        try { if (vsurf != null) vsurf.release(); } catch (Throwable ignored) { }
        try { if (micRec != null) micRec.release(); } catch (Throwable ignored) { }
        try { if (playRec != null) playRec.release(); } catch (Throwable ignored) { }
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        muxer = null; venc = null; aenc = null; vsurf = null; micRec = null; playRec = null; pfd = null;

        if (uri != null) {
            try {
                if (saved) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    ctx.getContentResolver().update(uri, cv, null, null);
                } else {
                    ctx.getContentResolver().delete(uri, null, null);
                }
            } catch (Throwable ignored) { }
            uri = null;
        } else if (file != null && !saved) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        return saved;
    }
}
