package com.rajibtutorial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Screen capture for the group call (screen share + screen record).
 * A foreground service holds the MediaProjection; the latest screen frame is kept as a small JPEG
 * that the call page pulls through a WebView request interceptor (see GroupCallActivity).
 */
public class ScreenCaptureService extends Service {
    interface Cb { void started(int w, int h); void stopped(); }

    static volatile Cb cb;
    static volatile boolean active = false;
    static volatile byte[] frame = null;
    private static volatile ScreenCaptureService inst = null;
    private static volatile String lastRecLabel = "";

    private volatile NativeRecorder nrec = null;
    private Bitmap lastCrop = null;
    private long lastPushMs = 0;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            // keep the recording alive on a still screen (ImageReader only fires when pixels change)
            NativeRecorder r = nrec;
            long now = SystemClock.uptimeMillis();
            if (r != null && lastCrop != null && now - lastPushMs > 500) {
                r.push(lastCrop);
                lastPushMs = now;
            }
            Handler h = bg;
            if (h != null && !cleaned) h.postDelayed(this, 250);
        }
    };

    private static final String EXTRA_CODE = "code", EXTRA_DATA = "data", CH = "rt_capture";
    private static final int NOTIF_ID = 7711;
    private static final long MIN_FRAME_GAP_MS = 110;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection mp;
    private VirtualDisplay vd;
    private ImageReader ir;
    private HandlerThread ht;
    private Handler bg;
    private Bitmap bmp;
    private long lastMs = 0;
    private int outW = 0, outH = 0;
    private boolean cleaned = false;

    static void start(Context c, int resultCode, Intent data) {
        Intent i = new Intent(c, ScreenCaptureService.class)
            .putExtra(EXTRA_CODE, resultCode)
            .putExtra(EXTRA_DATA, data);
        ContextCompat.startForegroundService(c, i);
    }

    /** Starts the native MP4 recording. Needs the capture to be running. */
    static boolean startRec() {
        ScreenCaptureService s = inst;
        if (s == null || !active) return false;
        return s.beginNativeRec();
    }

    /** Stops the native recording; returns where it was saved ("" when nothing was saved). */
    static String stopRec() {
        ScreenCaptureService s = inst;
        // when the system already stopped the capture, the service finalised the file itself
        if (s == null) return lastRecLabel;
        String r = s.endNativeRec();
        return r.isEmpty() ? lastRecLabel : r;
    }

    private synchronized boolean beginNativeRec() {
        if (nrec != null) return true;
        lastRecLabel = "";
        if (mp == null || outW <= 0 || outH <= 0) return false;
        NativeRecorder r = new NativeRecorder(this, mp, outW, outH);
        if (!r.start()) return false;
        nrec = r;
        return true;
    }

    private synchronized String endNativeRec() {
        NativeRecorder r = nrec;
        nrec = null;
        if (r == null) return "";
        String label = r.stop();
        lastRecLabel = label;
        return label;
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, ScreenCaptureService.class));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || active) {
            if (intent != null && active) notifyStarted();
            return START_NOT_STICKY;
        }
        try {
            goForeground();
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (data == null) throw new IllegalStateException("no permission data");
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mp = m.getMediaProjection(code, data);
            if (mp == null) throw new IllegalStateException("no projection");

            ht = new HandlerThread("rt-capture");
            ht.start();
            bg = new Handler(ht.getLooper());

            // Android 14+: the callback must be registered before the virtual display is created
            mp.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(ScreenCaptureService.this::stopSelf); }
            }, main);

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            float sc = Math.min(1f, 720f / Math.min(dm.widthPixels, dm.heightPixels));
            outW = Math.max(2, ((int) (dm.widthPixels * sc)) & ~1);
            outH = Math.max(2, ((int) (dm.heightPixels * sc)) & ~1);

            ir = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2);
            ir.setOnImageAvailableListener(this::onFrame, bg);
            vd = mp.createVirtualDisplay("rt-capture", outW, outH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.getSurface(), null, bg);
            active = true;
            inst = this;
            frame = null;
            bg.postDelayed(tick, 300);
            notifyStarted();
        } catch (Throwable t) {
            cleanup(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void notifyStarted() {
        final Cb c = cb;
        final int w = outW, h = outH;
        if (c != null) main.post(() -> c.started(w, h));
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Screen sharing", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setContentTitle("Screen sharing / recording")
            .setContentText("Rajib Tutorial is capturing your screen during the call")
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            try {
                startForeground(NOTIF_ID, n, type);
            } catch (Throwable t) {
                // microphone type can be refused (e.g. permission state); media projection alone is enough
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastMs < MIN_FRAME_GAP_MS) return;
            lastMs = now;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int pixel = p.getPixelStride(), row = p.getRowStride();
            int bw = row / pixel;
            if (bmp == null || bmp.getWidth() != bw) bmp = Bitmap.createBitmap(bw, outH, Bitmap.Config.ARGB_8888);
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            Bitmap crop = bw == outW ? bmp : Bitmap.createBitmap(bmp, 0, 0, outW, outH);
            lastCrop = crop;
            NativeRecorder rr = nrec;
            if (rr != null) { rr.push(crop); lastPushMs = now; }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            crop.compress(Bitmap.CompressFormat.JPEG, 60, bos);
            frame = bos.toByteArray();
        } catch (Throwable ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private void cleanup(boolean quiet) {
        if (cleaned) return;
        cleaned = true;
        active = false;
        inst = null;
        try { endNativeRec(); } catch (Throwable ignored) { }
        try { if (vd != null) vd.release(); } catch (Throwable ignored) { }
        try { if (ir != null) { ir.setOnImageAvailableListener(null, null); ir.close(); } } catch (Throwable ignored) { }
        try { if (mp != null) mp.stop(); } catch (Throwable ignored) { }
        try { if (ht != null) ht.quitSafely(); } catch (Throwable ignored) { }
        vd = null; ir = null; mp = null; ht = null; bmp = null;
        frame = null;
        final Cb c = cb;
        if (!quiet && c != null) main.post(c::stopped);
    }

    @Override
    public void onDestroy() {
        boolean wasActive = active;
        cleanup(false);
        if (!wasActive) {
            final Cb c = cb;
            if (c != null) main.post(c::stopped);
        }
        super.onDestroy();
    }
}
