package com.rajibtutorial;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Continuous-scroll PDF reader: pinch zoom, fling, double-tap zoom and WPS-like text selection
 * (long-press a word, drag the blue handles, Copy / Select all / Share).
 * Pages are drawn by PdfRenderer; character positions come from PDFBox (no OCR: scanned PDFs have no text).
 */
final class PdfReaderView extends View {

    interface Host {
        void onPage(int page, int count);
        void onCopy(String text);
        void onShare(String text);
        void onNote(String msg);
        /** Single tap on the page (not on a selection). */
        void onTap();
        /** The user started scrolling / zooming. */
        void onScrollStart();
    }

    // ───────── text model (positions are fractions of the page, 0..1, origin top-left) ─────────
    static final class TCh { String s; float l, t, r, b; boolean vis; }

    static final class PageText {
        final ArrayList<TCh> chars = new ArrayList<>();
        boolean hasText() {
            for (TCh c : chars) if (c.vis) return true;
            return false;
        }
    }

    private static final class Extractor extends PDFTextStripper {
        final ArrayList<TCh> out = new ArrayList<>();

        Extractor() throws IOException { super(); }

        @Override
        protected void writeString(String text, List<TextPosition> tps) throws IOException {
            for (TextPosition tp : tps) {
                float pw = tp.getPageWidth(), ph = tp.getPageHeight();
                if (pw <= 0 || ph <= 0) continue;
                String u = tp.getUnicode();
                if (u == null || u.isEmpty()) continue;
                float x = tp.getXDirAdj(), w = tp.getWidthDirAdj(), yb = tp.getYDirAdj();
                float hh = tp.getHeightDir();
                if (hh <= 0) hh = tp.getFontSizeInPt();
                TCh c = new TCh();
                c.s = u;
                c.l = x / pw;
                c.r = (x + Math.max(w, 0.5f)) / pw;
                c.t = (yb - hh * 0.95f) / ph;
                c.b = (yb + hh * 0.22f) / ph;
                c.vis = !u.trim().isEmpty();
                out.add(c);
            }
        }

        private void sep(String s) {
            TCh c = new TCh();
            c.s = s;
            c.vis = false;
            out.add(c);
        }

        @Override protected void writeWordSeparator() throws IOException { sep(" "); }
        @Override protected void writeLineSeparator() throws IOException { sep("\n"); }
        @Override protected void writeParagraphSeparator() throws IOException { sep("\n"); }
    }

    // ───────── fields ─────────
    private static final float MAX_ZOOM = 5f;
    private static final int HI_MAX_W = 2000;

    private final Object lock = new Object();
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private volatile boolean closed = false;

    private final int count;
    private final float[] ratio;
    private final float[] top;

    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final ExecutorService tx = Executors.newSingleThreadExecutor();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    private final File file;
    private PDDocument doc;
    private boolean docFailed;
    private final Map<Integer, PageText> texts = new HashMap<>();
    private final Set<Integer> loadingText = new HashSet<>();

    private float baseW = 0f, totalH = 0f, gap = 0f;
    private float scale = 1f, panX = 0f, offY = 0f;
    private volatile int visFirst = 0, visLast = 0;
    private int lastPage = -1;
    private final Host host;

    private final Paint paperPaint = new Paint();
    private final Paint bmPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Paint selPaint = new Paint();
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barLine = new Paint();

    private final ScaleGestureDetector sgd;
    private final GestureDetector gd;
    private final OverScroller scroller;
    private float lastFx, lastFy;
    private boolean readingMode = false;
    private boolean tapClearedSel = false;
    private boolean scrollNotified = false;

    // selection
    private int selPage = -1, selA = 0, selB = 0;
    private int anchorA = 0, anchorB = 0;
    private int dragHandle = 0;          // 1 start handle, 2 end handle, 3 extending after long-press
    private int barTouch = -1;
    private float hsx, hsy, hex, hey;    // handle centres (screen)
    private final float handleR;
    private boolean barShown;
    private final RectF[] barRect = {new RectF(), new RectF(), new RectF()};
    private static final String[] BAR = {"Copy", "Select all", "Share"};

    PdfReaderView(Context c, PdfRenderer r, ParcelFileDescriptor pfd, File file, Host host) {
        super(c);
        this.renderer = r;
        this.pfd = pfd;
        this.file = file;
        this.host = host;
        this.count = Math.max(0, r.getPageCount());
        this.ratio = new float[Math.max(1, count)];
        this.top = new float[Math.max(1, count)];
        for (int i = 0; i < ratio.length; i++) ratio[i] = 1.414f;
        this.gap = W.dp(c, 6);
        this.handleR = W.dp(c, 9);
        paperPaint.setColor(Color.WHITE);
        setBackgroundColor(0xFFE9E5E1);

        selPaint.setColor(0x552F6BFF);
        handlePaint.setColor(0xFF2F6BFF);
        handlePaint.setStrokeWidth(W.dp(c, 2));
        barPaint.setColor(0xF0262A31);
        barText.setColor(Color.WHITE);
        barText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, c.getResources().getDisplayMetrics()));
        barText.setTypeface(Typeface.DEFAULT_BOLD);
        barLine.setColor(0x33FFFFFF);

        long mem = Math.min(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6);
        cache = new LruCache<String, Bitmap>((int) mem) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };

        scroller = new OverScroller(c);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                lastFx = d.getFocusX();
                lastFy = d.getFocusY();
                notifyScroll();
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
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
            @Override public boolean onSingleTapUp(MotionEvent e) {
                tapClearedSel = false;
                if (selPage >= 0) { tapClearedSel = true; clearSelection(); return true; }
                return false;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapClearedSel) { tapClearedSel = false; return false; }
                if (host != null) host.onTap();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (!readingMode) startSelection(e.getX(), e.getY());
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                notifyScroll();
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

    private void notifyScroll() {
        if (scrollNotified) return;
        scrollNotified = true;
        if (host != null) host.onScrollStart();
    }

    /** Reading mode: no text selection, warm paper colour. */
    void setReadingMode(boolean on) {
        readingMode = on;
        if (on) clearSelection();
        int paper = on ? 0xFFF6EAD2 : Color.WHITE;
        paperPaint.setColor(paper);
        bmPaint.setColorFilter(on ? new PorterDuffColorFilter(paper, PorterDuff.Mode.MULTIPLY) : null);
        setBackgroundColor(on ? 0xFF2B2824 : 0xFFE9E5E1);
        invalidate();
    }

    int pageCount() { return count; }

    int currentPage() { return Math.max(1, lastPage); }

    /** Scrolls so that the top of page n (1-based) is at the top of the screen. */
    void goToPage(int n) {
        if (count == 0) return;
        int i = Math.max(0, Math.min(count - 1, n - 1));
        offY = -top[i] * scale;
        panX = Math.min(0f, Math.max(minX(), panX));
        clamp();
        invalidate();
    }

    // ═════════════════════════ geometry ═════════════════════════
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

    // page → screen helpers
    private float px0() { return panX; }
    private float py0(int p) { return offY + top[p] * scale; }
    private float pw() { return baseW * scale; }
    private float ph(int p) { return baseW * ratio[p] * scale; }

    /** Returns the page under (x,y) or -1; fills fx/fy (fractions of that page). */
    private float hitFx, hitFy;
    private int pageAt(float x, float y) {
        for (int i = Math.max(0, visFirst - 1); i <= Math.min(count - 1, visLast + 1); i++) {
            float t = py0(i), hg = ph(i);
            if (y >= t && y <= t + hg) {
                hitFx = (x - px0()) / pw();
                hitFy = (y - t) / hg;
                return i;
            }
        }
        return -1;
    }

    // ═════════════════════════ text loading ═════════════════════════
    private void requestText(final int page) {
        if (page < 0 || page >= count || texts.containsKey(page) || !loadingText.add(page)) return;
        try {
            tx.execute(() -> {
                PageText pt = new PageText();
                try {
                    if (doc == null && !docFailed) {
                        try { doc = PDDocument.load(file); } catch (Throwable t) { docFailed = true; }
                    }
                    if (doc != null) {
                        Extractor e = new Extractor();
                        e.setStartPage(page + 1);
                        e.setEndPage(page + 1);
                        e.getText(doc);
                        pt.chars.addAll(e.out);
                    }
                } catch (Throwable ignored) { }
                final PageText fin = pt;
                h.post(() -> {
                    loadingText.remove(page);
                    if (closed) return;
                    texts.put(page, fin);
                    invalidate();
                });
            });
        } catch (Throwable t) {
            loadingText.remove(page);
        }
    }

    // ═════════════════════════ selection ═════════════════════════
    private float lastDist;

    private int nearest(PageText pt, int page, float fx, float fy) {
        float r = ratio[page];
        float best = Float.MAX_VALUE;
        int bi = -1;
        for (int i = 0; i < pt.chars.size(); i++) {
            TCh c = pt.chars.get(i);
            if (!c.vis) continue;
            float dx = fx < c.l ? c.l - fx : (fx > c.r ? fx - c.r : 0f);
            float dy = fy < c.t ? c.t - fy : (fy > c.b ? fy - c.b : 0f);
            float d = dx * dx + 4f * (dy * r) * (dy * r);
            if (d < best) { best = d; bi = i; }
        }
        lastDist = (float) Math.sqrt(best);
        return bi;
    }

    private void startSelection(float x, float y) {
        int p = pageAt(x, y);
        if (p < 0) return;
        PageText pt = texts.get(p);
        if (pt == null) {
            requestText(p);
            host.onNote("Preparing text… hold again");
            return;
        }
        if (!pt.hasText()) {
            host.onNote("No selectable text on this page (scanned image)");
            return;
        }
        int k = nearest(pt, p, hitFx, hitFy);
        if (k < 0 || lastDist > 0.06f) return;
        int a = k, b = k;
        while (a > 0 && pt.chars.get(a - 1).vis) a--;
        while (b < pt.chars.size() - 1 && pt.chars.get(b + 1).vis) b++;
        selPage = p; selA = a; selB = b; anchorA = a; anchorB = b;
        dragHandle = 3;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    void clearSelection() {
        selPage = -1;
        dragHandle = 0;
        barTouch = -1;
        barShown = false;
        invalidate();
    }

    private int firstVis(PageText pt) {
        for (int i = 0; i < pt.chars.size(); i++) if (pt.chars.get(i).vis) return i;
        return 0;
    }

    private int lastVisIdx(PageText pt) {
        for (int i = pt.chars.size() - 1; i >= 0; i--) if (pt.chars.get(i).vis) return i;
        return 0;
    }

    private String selectedText() {
        PageText pt = texts.get(selPage);
        if (pt == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = selA; i <= selB && i < pt.chars.size(); i++) sb.append(pt.chars.get(i).s);
        return sb.toString().replaceAll("[ \\t]+\\n", "\n").trim();
    }

    private void moveDrag(float x, float y) {
        PageText pt = texts.get(selPage);
        if (pt == null) return;
        float yy = dragHandle == 3 ? y : y - W.dp(getContext(), 22);
        float fx = (x - px0()) / pw();
        float fy = (yy - py0(selPage)) / ph(selPage);
        int k = nearest(pt, selPage, fx, fy);
        if (k < 0) return;
        if (dragHandle == 1) selA = Math.min(k, selB);
        else if (dragHandle == 2) selB = Math.max(k, selA);
        else { selA = Math.min(anchorA, k); selB = Math.max(anchorB, k); }
        invalidate();
    }

    private int hitHandle(float x, float y) {
        float r = W.dp(getContext(), 28);
        if (Math.hypot(x - hsx, y - hsy) <= r) return 1;
        if (Math.hypot(x - hex, y - hey) <= r) return 2;
        return 0;
    }

    private int hitBar(float x, float y) {
        if (!barShown) return -1;
        for (int i = 0; i < 3; i++) if (barRect[i].contains(x, y)) return i;
        return -1;
    }

    private void doBar(int i) {
        PageText pt = texts.get(selPage);
        if (pt == null) return;
        if (i == 0) {
            host.onCopy(selectedText());
        } else if (i == 1) {
            selA = firstVis(pt);
            selB = lastVisIdx(pt);
            invalidate();
        } else {
            host.onShare(selectedText());
        }
    }

    // ═════════════════════════ touch ═════════════════════════
    @Override public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            scrollNotified = false;
            scroller.forceFinished(true);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            if (selPage >= 0) {
                dragHandle = hitHandle(e.getX(), e.getY());
                barTouch = dragHandle == 0 ? hitBar(e.getX(), e.getY()) : -1;
                if (dragHandle != 0 || barTouch >= 0) return true;
            }
        }
        if (dragHandle != 0) {
            if (act == MotionEvent.ACTION_MOVE) moveDrag(e.getX(), e.getY());
            else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                dragHandle = 0;
                invalidate();
            }
            return true;
        }
        if (barTouch >= 0) {
            if (act == MotionEvent.ACTION_UP && hitBar(e.getX(), e.getY()) == barTouch) doBar(barTouch);
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) barTouch = -1;
            return true;
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

    // ═════════════════════════ drawing ═════════════════════════
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
        if (first >= 0) {
            visFirst = first;
            visLast = lastVis;
            for (int i = first; i <= lastVis; i++) requestText(i);
        }
        if (centerPage != lastPage && host != null) {
            lastPage = centerPage;
            final int pg = centerPage + 1;
            h.post(() -> { if (!closed) host.onPage(pg, count); });
        }
        if (selPage >= 0) drawSelection(canvas);
        else barShown = false;
    }

    private void drawSelection(Canvas cv) {
        PageText pt = texts.get(selPage);
        if (pt == null || selA >= pt.chars.size() || selB >= pt.chars.size()) return;
        float x0 = px0(), y0 = py0(selPage), w = pw(), hh = ph(selPage);
        float mnL = Float.MAX_VALUE, mnT = Float.MAX_VALUE, mxR = -Float.MAX_VALUE, mxB = -Float.MAX_VALUE;
        boolean any = false;
        for (int k = selA; k <= selB; k++) {
            TCh c = pt.chars.get(k);
            if (!c.vis) continue;
            any = true;
            float l = x0 + c.l * w - 0.5f, t = y0 + c.t * hh, r = x0 + c.r * w + 0.5f, b = y0 + c.b * hh;
            dst.set(l, t, r, b);
            cv.drawRect(dst, selPaint);
            mnL = Math.min(mnL, l); mnT = Math.min(mnT, t); mxR = Math.max(mxR, r); mxB = Math.max(mxB, b);
        }
        if (!any) { barShown = false; return; }

        TCh s = pt.chars.get(selA), e = pt.chars.get(selB);
        float sx = x0 + s.l * w, sy = y0 + s.b * hh;
        float ex = x0 + e.r * w, ey = y0 + e.b * hh;
        hsx = sx; hsy = sy + handleR;
        hex = ex; hey = ey + handleR;
        cv.drawLine(sx, sy, sx, sy + handleR, handlePaint);
        cv.drawCircle(hsx, hsy, handleR, handlePaint);
        cv.drawLine(ex, ey, ex, ey + handleR, handlePaint);
        cv.drawCircle(hex, hey, handleR, handlePaint);

        // floating action bar
        float padH = W.dp(getContext(), 16), barH = W.dp(getContext(), 40);
        float[] wd = new float[3];
        float total = 0;
        for (int i = 0; i < 3; i++) { wd[i] = barText.measureText(BAR[i]) + padH * 2; total += wd[i]; }
        float bx = (mnL + mxR) / 2f - total / 2f;
        bx = Math.max(W.dp(getContext(), 8), Math.min(getWidth() - total - W.dp(getContext(), 8), bx));
        float by = mnT - barH - W.dp(getContext(), 12);
        if (by < W.dp(getContext(), 8)) by = mxB + handleR * 2 + W.dp(getContext(), 14);
        by = Math.min(by, getHeight() - barH - W.dp(getContext(), 8));
        RectF all = new RectF(bx, by, bx + total, by + barH);
        cv.drawRoundRect(all, barH / 2f, barH / 2f, barPaint);
        float cx = bx;
        for (int i = 0; i < 3; i++) {
            barRect[i].set(cx, by, cx + wd[i], by + barH);
            cv.drawText(BAR[i], cx + padH, by + barH / 2f + barText.getTextSize() * 0.35f, barText);
            cx += wd[i];
            if (i < 2) cv.drawLine(cx, by + barH * 0.25f, cx, by + barH * 0.75f, barLine);
        }
        barShown = true;
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
                    skipped = true;
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

    /** Frees the renderer, the PDFBox document and bitmaps. */
    void release() {
        closed = true;
        ex.shutdownNow();
        tx.shutdownNow();
        synchronized (lock) {
            try { if (renderer != null) renderer.close(); } catch (Exception ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
            renderer = null;
            pfd = null;
        }
        final PDDocument d = doc;
        doc = null;
        if (d != null) new Thread(() -> { try { d.close(); } catch (Throwable ignored) { } }).start();
        cache.evictAll();
    }
}
