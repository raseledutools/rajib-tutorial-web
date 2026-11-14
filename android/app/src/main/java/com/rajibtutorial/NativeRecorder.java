package com.rajibtutorial;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
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
    private static final int FPS = 10;

    private final Context ctx;
    private final MediaProjection projection;
    private final int w, h;

    private MediaCodec venc, aenc;
    private final int ew, eh;                       // encoded size (multiple of 16)
    private final int[] argb;                       // latest screen frame
    private final byte[] yArr, uArr, vArr;          // converted planes
    private boolean hasFrame = false, dirty = false;
    private volatile int vIn = 0, vOut = 0, aOut = 0;
    private Thread videoThread;
    private MediaMuxer muxer;
    private AudioRecord micRec, playRec;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private File file;
    private String label = "";

    private final Object frameLock = new Object();

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
        this.ew = Math.max(16, w & ~15);
        this.eh = Math.max(16, h & ~15);
        this.argb = new int[ew * eh];
        java.util.Arrays.fill(this.argb, 0xFF000000);
        this.yArr = new byte[ew * eh];
        this.uArr = new byte[(ew / 2) * (eh / 2)];
        this.vArr = new byte[(ew / 2) * (eh / 2)];
    }

    String label() { return label; }

    String info() {
        return "video " + vOut + "/" + vIn + " frames, audio " + aOut + ", "
            + String.format(Locale.US, "%.1f", Math.max(lastV, lastA) / 1000000.0) + "s"
            + (hasAudio ? "" : ", no audio");
    }

    /** @return true when recording really started */
    boolean start() {
        try {
            openOutput();

            MediaFormat vf = MediaFormat.createVideoFormat("video/avc", ew, eh);
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            vf.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            venc = MediaCodec.createEncoderByType("video/avc");
            venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            venc.start();

            hasAudio = initAudio();

            baseUs = System.nanoTime() / 1000L;
            running = true;

            videoThread = new Thread(this::videoLoop, "rec-video");
            videoThread.start();
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
        final long base = Math.max(0, System.nanoTime() / 1000L - baseUs);
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
                aenc.queueInputBuffer(idx, 0, in.position(), Math.max(0, ptsUs),
                    eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                return;
            } catch (Throwable t) {
                return;
            }
        }
    }

    // ── video frames ────────────────────────────────────────
    /** Called with every new screen bitmap; only copies the pixels (cheap). */
    void setFrame(Bitmap b) {
        if (!running || b == null || b.isRecycled()) return;
        if (b.getWidth() < ew || b.getHeight() < eh) return;
        synchronized (frameLock) {
            try {
                b.getPixels(argb, 0, ew, 0, 0, ew, eh);
                hasFrame = true;
                dirty = true;
            } catch (Throwable ignored) { }
        }
    }

    /** Constant frame rate: encodes the latest frame every 1/FPS s with OUR OWN timestamps. */
    private void videoLoop() {
        final long period = 1000000000L / FPS;
        final int[] local = new int[argb.length];
        java.util.Arrays.fill(local, 0xFF000000);
        long next = System.nanoTime();
        try {
            toYuv(local);
            while (running) {
                long now = System.nanoTime();
                if (now < next) {
                    try { Thread.sleep(Math.max(1, (next - now) / 1000000L)); } catch (InterruptedException e) { break; }
                    continue;
                }
                next += period;
                if (next < now) next = now + period;           // fell behind: don't burst
                boolean conv = false;
                synchronized (frameLock) {
                    if (dirty) {
                        System.arraycopy(argb, 0, local, 0, local.length);
                        dirty = false;
                        conv = true;
                    }
                }
                if (conv) toYuv(local);
                int idx = venc.dequeueInputBuffer(20000);
                if (idx < 0) continue;
                Image img = venc.getInputImage(idx);
                if (img == null) { abort = true; break; }
                fillImage(img);
                long pts = Math.max(0, System.nanoTime() / 1000L - baseUs);
                venc.queueInputBuffer(idx, 0, ew * eh * 3 / 2, pts, 0);
                vIn++;
            }
        } catch (Throwable ignored) {
        } finally {
            for (int t = 0; t < 20 && !abort; t++) {
                try {
                    int idx = venc.dequeueInputBuffer(20000);
                    if (idx < 0) continue;
                    long pts = Math.max(0, System.nanoTime() / 1000L - baseUs);
                    venc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                } catch (Throwable e) { break; }
            }
        }
    }

    private void toYuv(int[] px) {
        final int W = ew, H = eh;
        for (int j = 0; j < H; j++) {
            int row = j * W;
            for (int i = 0; i < W; i++) {
                int c = px[row + i];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                yArr[row + i] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
        }
        final int cw = W / 2, ch = H / 2;
        for (int j = 0; j < ch; j++) {
            int r0 = (2 * j) * W, r1 = r0 + W;
            for (int i = 0; i < cw; i++) {
                int c0 = px[r0 + 2 * i], c1 = px[r0 + 2 * i + 1], c2 = px[r1 + 2 * i], c3 = px[r1 + 2 * i + 1];
                int r = (((c0 >> 16) & 255) + ((c1 >> 16) & 255) + ((c2 >> 16) & 255) + ((c3 >> 16) & 255)) >> 2;
                int g = (((c0 >> 8) & 255) + ((c1 >> 8) & 255) + ((c2 >> 8) & 255) + ((c3 >> 8) & 255)) >> 2;
                int b = ((c0 & 255) + (c1 & 255) + (c2 & 255) + (c3 & 255)) >> 2;
                int o = j * cw + i;
                uArr[o] = (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                vArr[o] = (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
            }
        }
    }

    private void fillImage(Image img) {
        Image.Plane[] pl = img.getPlanes();
        copyPlane(pl[0], yArr, ew, eh);
        copyPlane(pl[1], uArr, ew / 2, eh / 2);
        copyPlane(pl[2], vArr, ew / 2, eh / 2);
    }

    private static void copyPlane(Image.Plane p, byte[] src, int pw, int ph) {
        ByteBuffer b = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        for (int r = 0; r < ph; r++) {
            int base = r * rs;
            if (ps == 1) {
                b.position(base);
                b.put(src, r * pw, pw);
            } else {
                for (int c = 0; c < pw; c++) b.put(base + c * ps, src[r * pw + c]);
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
                if (pts <= lastV) pts = lastV + 1;
                lastV = pts;
                vOut++;
            } else {
                if (pts <= lastA) pts = lastA + 1;
                lastA = pts;
                aOut++;
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
        try { if (videoThread != null) videoThread.join(2500); } catch (InterruptedException ignored) { }
        try { if (audioThread != null) audioThread.join(2500); } catch (InterruptedException ignored) { }
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
        try { if (micRec != null) micRec.release(); } catch (Throwable ignored) { }
        try { if (playRec != null) playRec.release(); } catch (Throwable ignored) { }
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        muxer = null; venc = null; aenc = null; micRec = null; playRec = null; pfd = null;

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
