package com.rajibtutorial;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Continuous-scroll PDF view: pinch-to-zoom (focus stays under the fingers), drag, fling
 * and double-tap zoom. Pages are rendered lazily by PdfRenderer at two quality levels.
 */
final class PdfPagesView extends View {

    interface PageListener { void onPage(int page, int count); }

    private static final float MAX_ZOOM = 4f;
    private static final int HI_MAX_W = 2000;

    private final Object lock = new Object();
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private volatile boolean closed = false;

    private final int count;
    private final float[] ratio;   // page height / width
    private final float[] top;     // page top in base (unscaled) pixels

    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    private float baseW = 0f, totalH = 0f, gap = 0f;
    private float scale = 1f, panX = 0f, offY = 0f;
    private volatile int visFirst = 0, visLast = 0;
    private int lastPage = -1;
    private final PageListener listener;

    private final Paint paperPaint = new Paint();
    private final Paint bmPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    private final ScaleGestureDetector sgd;
    private final GestureDetector gd;
    private final OverScroller scroller;
    private float lastFx, lastFy;

    PdfPagesView(Context c, PdfRenderer r, ParcelFileDescriptor pfd, PageListener l) {
        super(c);
        this.renderer = r;
        this.pfd = pfd;
        this.listener = l;
        this.count = Math.max(0, r.getPageCount());
        this.ratio = new float[Math.max(1, count)];
        this.top = new float[Math.max(1, count)];
        for (int i = 0; i < ratio.length; i++) ratio[i] = 1.414f;
        this.gap = Ui.dp(c, 6);
        paperPaint.setColor(Color.WHITE);
        setBackgroundColor(0xFFE9E5E1);

        long mem = Math.min(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6);
        cache = new LruCache<String, Bitmap>((int) mem) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };

        scroller = new OverScroller(c);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                lastFx = d.getFocusX();
                lastFy = d.getFocusY();
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                // two-finger drag while pinching
                panX += d.getFocusX() - lastFx;
                offY += d.getFocusY() - lastFy;
                lastFx = d.getFocusX();
                lastFy = d.getFocusY();
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                panX -= dx;
                offY -= dy;
                clamp();
                invalidate();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                scroller.fling(Math.round(panX), Math.round(offY), Math.round(vx), Math.round(vy),
                    Math.round(minX()), 0, Math.round(minY()), 0);
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                final float fx = e.getX(), fy = e.getY();
                float target = scale > 1.05f ? 1f : 2.5f;
                ValueAnimator va = ValueAnimator.ofFloat(scale, target);
                va.setDuration(180);
                va.addUpdateListener(an -> {
                    float ns = (Float) an.getAnimatedValue();
                    zoomBy(ns / scale, fx, fy);
                });
                va.start();
                return true;
            }
        });

        // Real page sizes (so tall/landscape pages get correct height) – done off the UI thread.
        ex.execute(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    synchronized (lock) {
                        if (closed || renderer == null) return;
                        PdfRenderer.Page p = renderer.openPage(i);
                        try { ratio[i] = p.getHeight() / (float) Math.max(1, p.getWidth()); }
                        finally { p.close(); }
                    }
                }
            } catch (Throwable ignored) { }
            h.post(() -> {
                if (closed) return;
                relayout();
                invalidate();
            });
        });
    }

    // ── Geometry ────────────────────────────────────────────
    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        baseW = w;
        relayout();
    }

    private void relayout() {
        if (baseW <= 0) return;
        float y = 0;
        for (int i = 0; i < count; i++) {
            top[i] = y;
            y += baseW * ratio[i] + gap;
        }
        totalH = y;
        clamp();
    }

    private float minX() { return Math.min(0f, getWidth() - baseW * scale); }
    private float minY() { return Math.min(0f, getHeight() - totalH * scale); }

    private void clamp() {
        panX = Math.max(minX(), Math.min(0f, panX));
        offY = Math.max(minY(), Math.min(0f, offY));
    }

    private void zoomBy(float f, float fx, float fy) {
        float ns = Math.max(1f, Math.min(MAX_ZOOM, scale * f));
        float real = ns / scale;
        panX = fx - (fx - panX) * real;
        offY = fy - (fy - offY) * real;
        scale = ns;
        clamp();
        invalidate();
    }

    // ── Touch ───────────────────────────────────────────────
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        sgd.onTouchEvent(e);
        if (!sgd.isInProgress()) gd.onTouchEvent(e);
        return true;
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.getCurrX();
            offY = scroller.getCurrY();
            clamp();
            postInvalidateOnAnimation();
        }
    }

    // ── Drawing ─────────────────────────────────────────────
    private static String key(int page, int lvl) { return page + "@" + lvl; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (baseW <= 0 || count == 0) return;
        final int vh = getHeight();
        final int want = scale > 1.3f ? 1 : 0;
        int first = -1, lastVis = -1, centerPage = 0;
        for (int i = 0; i < count; i++) {
            float t = offY + top[i] * scale;
            float hgt = baseW * ratio[i] * scale;
            if (t + hgt + gap * scale < vh / 2f) centerPage = Math.min(count - 1, i + 1);
            if (t + hgt < 0) continue;
            if (t > vh) break;
            if (first < 0) first = i;
            lastVis = i;
            dst.set(panX, t, panX + baseW * scale, t + hgt);
            canvas.drawRect(dst, paperPaint);
            Bitmap exact = cache.get(key(i, want));
            Bitmap bm = exact != null ? exact : cache.get(key(i, 1 - want));
            if (bm != null) canvas.drawBitmap(bm, null, dst, bmPaint);
            if (exact == null) request(i, want);
        }
        if (first >= 0) { visFirst = first; visLast = lastVis; }
        if (centerPage != lastPage && listener != null) {
            lastPage = centerPage;
            final int pg = centerPage + 1;
            h.post(() -> { if (!closed) listener.onPage(pg, count); });
        }
    }

    private void request(final int page, final int lvl) {
        final String k = key(page, lvl);
        if (failed.contains(k) || !pending.add(k)) return;
        final int w = lvl == 0
            ? Math.max(1, Math.round(baseW))
            : Math.max(Math.round(baseW), Math.min(HI_MAX_W, Math.round(baseW * 2f)));
        try {
            ex.execute(() -> {
                Bitmap bm = null;
                boolean skipped = false;
                if (page < visFirst - 1 || page > visLast + 1) {
                    skipped = true;               // scrolled away while queued
                } else {
                    synchronized (lock) {
                        if (closed || renderer == null) return;
                        try {
                            PdfRenderer.Page p = renderer.openPage(page);
                            try {
                                int hgt = Math.max(1, Math.round(w * (float) p.getHeight() / Math.max(1, p.getWidth())));
                                bm = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
                                bm.eraseColor(Color.WHITE);
                                p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            } finally {
                                p.close();
                            }
                        } catch (Throwable t) {
                            bm = null;
                        }
                    }
                }
                final Bitmap fb = bm;
                final boolean sk = skipped;
                h.post(() -> {
                    pending.remove(k);
                    if (closed) return;
                    if (fb != null) { cache.put(k, fb); invalidate(); }
                    else if (!sk) failed.add(k);
                    else invalidate();
                });
            });
        } catch (Throwable t) {
            pending.remove(k);
        }
    }

    /** Frees the renderer and bitmaps. Call when the viewer is closed. */
    void release() {
        closed = true;
        ex.shutdownNow();
        synchronized (lock) {
            try { if (renderer != null) renderer.close(); } catch (Exception ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
            renderer = null;
            pfd = null;
        }
        cache.evictAll();
    }
}
