package com.rajibtutorial;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Camera;
import android.text.InputType;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** QR generator and QR reader (photo + live camera). */
final class QrTools {
    private QrTools() { }

    // ═════════════════════════ GENERATOR ═════════════════════════
    static final class Generator extends ToolPage {
        private EditText input;
        private LinearLayout outBox;
        private Bitmap bmp;

        Generator(StudyToolsActivity a) { super(a); }

        @Override String title() { return "QR Generator"; }

        @Override View body() {
            LinearLayout col = W.column(a);
            LinearLayout card = W.card(a);
            card.addView(W.text(a, "Text or link", 13, W.SUB, true));
            input = W.input(a, "https://example.com or any text", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
            input.setMinLines(3);
            input.setGravity(Gravity.TOP);
            card.addView(input, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
            col.addView(card);
            outBox = W.column(a);
            col.addView(outBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            return col;
        }

        @Override View footer() {
            return W.primaryBtn(a, "Generate QR", v -> generate());
        }

        @Override void onDestroy() {
            if (bmp != null) bmp.recycle();
        }

        static Bitmap encode(String text, int size) throws Exception {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        }

        private void generate() {
            final String text = input.getText().toString().trim();
            if (text.isEmpty()) { a.toast("Enter some text or a link"); return; }
            Bitmap b;
            try {
                b = encode(text, 768);
            } catch (Exception e) {
                a.showError(new Exception("This text is too long for a QR code."));
                return;
            }
            if (bmp != null) bmp.recycle();
            bmp = b;
            final Bitmap fb = b;
            outBox.removeAllViews();

            LinearLayout card = W.card(a);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(fb);
            iv.setBackgroundColor(Color.WHITE);
            card.addView(iv, W.lp(W.dp(a, 240), W.dp(a, 240)));
            card.addView(W.softBtn(a, "⬇  Save QR to Downloads", W.BLUE, v -> save(fb)),
                W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            outBox.addView(card);
        }

        private void save(final Bitmap b) {
            a.background("Saving…", u -> {
                File tmp = a.tempOut("qr.png");
                try (FileOutputStream fo = new FileOutputStream(tmp)) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, fo);
                }
                final StudyToolsActivity.Saved s = a.saveFile(tmp, "qr_code_" + PdfTools.stamp() + ".png", "image/png");
                return () -> {
                    List<StudyToolsActivity.Saved> l = new ArrayList<>();
                    l.add(s);
                    outBox.addView(a.resultCard("QR saved", null, l),
                        W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
                };
            });
        }
    }

    // ═════════════════════════ READER ═════════════════════════
    static final class Reader extends ToolPage {
        private LinearLayout scanBox, resultBox;
        private ScannerView scanner;
        private Bitmap lastBmp;

        Reader(StudyToolsActivity a) { super(a); }

        @Override String title() { return "QR Reader"; }

        @Override View body() {
            LinearLayout col = W.column(a);
            LinearLayout row = W.row(a);
            TextView cam = W.primaryBtn(a, "📹  Scan with camera", v -> a.requestCamera(this::startScan));
            row.addView(cam, new LinearLayout.LayoutParams(0, W.dp(a, 52), 1f));
            TextView img = W.softBtn(a, "🖼️  From photo", W.BLUE, v -> a.pick(false, PdfTools.IMG_MIME, p -> readImage(p.get(0))));
            row.addView(img, W.lp(a, 0, W.dp(a, 52), 10, 0, 0, 0));
            ((LinearLayout.LayoutParams) img.getLayoutParams()).weight = 1f;
            col.addView(row);

            scanBox = W.column(a);
            col.addView(scanBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            resultBox = W.column(a);
            col.addView(resultBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));

            TextView hint = W.text(a, "Point the camera at a QR code, or pick a photo that contains one.", 12.5f, W.SUB, false);
            hint.setGravity(Gravity.CENTER);
            col.addView(hint, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 20, 0, 0));
            return col;
        }

        @Override boolean onBack() {
            if (scanner != null) { stopScan(); return true; }
            return false;
        }

        @Override void onPause() { stopScan(); }

        @Override void onDestroy() {
            stopScan();
            if (lastBmp != null) lastBmp.recycle();
        }

        private void startScan() {
            stopScan();
            resultBox.removeAllViews();
            scanner = new ScannerView(a, new ScannerView.Listener() {
                @Override public void onResult(String text) { showText(text, true); }
                @Override public void onError(String msg) { a.toast(msg); stopScan(); }
            });
            LinearLayout card = W.column(a);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.addView(scanner, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout bt = W.row(a);
            bt.addView(W.softBtn(a, "🔄  Switch camera", W.BLUE, v -> { if (scanner != null) scanner.switchCamera(); }),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView close = W.softBtn(a, "✕  Close", W.RED, v -> stopScan());
            bt.addView(close, W.lp(a, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));
            ((LinearLayout.LayoutParams) close.getLayoutParams()).weight = 1f;
            card.addView(bt, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
            scanBox.addView(card);
            scanner.start();
        }

        private void stopScan() {
            if (scanner != null) {
                scanner.stop();
                scanner = null;
            }
            if (scanBox != null) scanBox.removeAllViews();
        }

        private void readImage(final StudyToolsActivity.Picked p) {
            stopScan();
            resultBox.removeAllViews();
            a.background("Reading QR…", u -> {
                String text = null;
                for (int side : new int[]{1600, 1000, 600}) {
                    Bitmap b = PhotoTools.decode(p.file, side);
                    if (b == null) continue;
                    text = decodeBitmap(b);
                    b.recycle();
                    if (text != null) break;
                }
                final String res = text;
                return () -> {
                    if (res == null) {
                        LinearLayout c = W.card(a);
                        c.addView(W.text(a, "No QR code found", 16, W.RED, true));
                        c.addView(W.text(a, "Make sure the QR is clear, in focus and fully inside the photo.", 13, W.SUB, false),
                            W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
                        resultBox.addView(c);
                    } else {
                        showText(res, false);
                    }
                };
            });
        }

        static String decodeBitmap(Bitmap b) {
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            MultiFormatReader reader = new MultiFormatReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            List<BarcodeFormat> fmts = new ArrayList<>();
            fmts.add(BarcodeFormat.QR_CODE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, fmts);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            reader.setHints(hints);
            try {
                RGBLuminanceSource src = new RGBLuminanceSource(w, h, px);
                try {
                    return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src))).getText();
                } catch (NotFoundException e) {
                    reader.reset();
                    return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src.invert()))).getText();
                }
            } catch (Exception e) {
                return null;
            }
        }

        private void showText(final String text, boolean fromCamera) {
            if (fromCamera && scanner == null) return;
            resultBox.removeAllViews();
            LinearLayout card = W.card(a);
            LinearLayout head = W.row(a);
            TextView ok = W.text(a, "✓", 18, 0xFFFFFFFF, true);
            ok.setGravity(Gravity.CENTER);
            ok.setBackground(W.rect(a, W.GREEN, 16));
            head.addView(ok, W.lp(W.dp(a, 32), W.dp(a, 32)));
            head.addView(W.text(a, "QR code found", 16, W.TEXT, true),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0, 0, 0));
            card.addView(head);

            TextView t = W.text(a, text, 14, W.TEXT, false);
            t.setTextIsSelectable(true);
            t.setBackground(W.rect(a, 0xFFF6F7FA, 10));
            int pd = W.dp(a, 12);
            t.setPadding(pd, pd, pd, pd);
            card.addView(t, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));

            LinearLayout btns = W.row(a);
            btns.addView(W.softBtn(a, "Copy", W.BLUE, v -> a.copyText(text)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (text.toLowerCase(java.util.Locale.ROOT).startsWith("http://")
                    || text.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                TextView open = W.softBtn(a, "Open link", W.GREEN, v -> a.openUrl(text));
                btns.addView(open, W.lp(a, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
                ((LinearLayout.LayoutParams) open.getLayoutParams()).weight = 1f;
            }
            TextView share = W.softBtn(a, "Share", W.ORANGE, v -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                a.startActivity(Intent.createChooser(i, "Share"));
            });
            btns.addView(share, W.lp(a, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
            ((LinearLayout.LayoutParams) share.getLayoutParams()).weight = 1f;
            card.addView(btns, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));

            if (fromCamera) {
                card.addView(W.softBtn(a, "🔄  Scan again", W.TEAL, v -> {
                    resultBox.removeAllViews();
                    if (scanner != null) scanner.resume();
                }), W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
            }
            resultBox.addView(card);
        }
    }

    // ═════════════════════════ LIVE CAMERA VIEW ═════════════════════════
    /** Camera preview with a QR-decoding loop (platform Camera API + ZXing, no extra libraries). */
    @SuppressWarnings("deprecation")
    static final class ScannerView extends FrameLayout implements SurfaceHolder.Callback, Camera.PreviewCallback {

        interface Listener {
            void onResult(String text);
            void onError(String msg);
        }

        private final Listener listener;
        private final SurfaceView surface;
        private final Overlay overlay;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final MultiFormatReader reader = new MultiFormatReader();
        private Camera cam;
        private int camId = 0;
        private int pw, ph;
        private boolean surfaceReady, wantRunning;
        private volatile boolean decoding, found, stopped;

        ScannerView(Context c, Listener l) {
            super(c);
            this.listener = l;
            setBackgroundColor(Color.BLACK);

            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            List<BarcodeFormat> fmts = new ArrayList<>();
            fmts.add(BarcodeFormat.QR_CODE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, fmts);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            reader.setHints(hints);

            surface = new SurfaceView(c);
            surface.getHolder().addCallback(this);
            addView(surface, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay = new Overlay(c);
            addView(overlay, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int h = w * 4 / 3;
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }

        void start() {
            wantRunning = true;
            stopped = false;
            if (surfaceReady) openCamera();
        }

        void stop() {
            wantRunning = false;
            stopped = true;
            closeCamera();
            overlay.cancel();
            worker.shutdownNow();
        }

        void resume() {
            found = false;
            if (cam != null) cam.setPreviewCallback(this);
        }

        void switchCamera() {
            int n = Camera.getNumberOfCameras();
            if (n < 2) return;
            camId = (camId + 1) % n;
            closeCamera();
            if (surfaceReady) openCamera();
        }

        private void openCamera() {
            if (cam != null || !wantRunning) return;
            try {
                cam = Camera.open(camId);
                Camera.Parameters p = cam.getParameters();
                List<String> fm = p.getSupportedFocusModes();
                if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                }
                Camera.Size best = null;
                for (Camera.Size s : p.getSupportedPreviewSizes()) {
                    if (s.width > 1280) continue;
                    boolean ratio43 = Math.abs(s.width / (double) s.height - 4.0 / 3.0) < 0.05;
                    if (ratio43 && (best == null || s.width > best.width)) best = s;
                }
                if (best == null) {
                    for (Camera.Size s : p.getSupportedPreviewSizes()) {
                        if (s.width <= 1280 && (best == null || s.width > best.width)) best = s;
                    }
                }
                if (best != null) {
                    p.setPreviewSize(best.width, best.height);
                }
                try { cam.setParameters(p); } catch (Exception ignored) { }
                Camera.Size cur = cam.getParameters().getPreviewSize();
                pw = cur.width;
                ph = cur.height;

                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(camId, info);
                int deg = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? (360 - info.orientation) % 360 : info.orientation;
                cam.setDisplayOrientation(deg);
                cam.setPreviewDisplay(surface.getHolder());
                found = false;
                cam.setPreviewCallback(this);
                cam.startPreview();
                overlay.begin();
            } catch (Exception e) {
                closeCamera();
                listener.onError("Can't open the camera");
            }
        }

        private void closeCamera() {
            if (cam != null) {
                try { cam.setPreviewCallback(null); } catch (Exception ignored) { }
                try { cam.stopPreview(); } catch (Exception ignored) { }
                try { cam.release(); } catch (Exception ignored) { }
                cam = null;
            }
        }

        @Override
        public void onPreviewFrame(final byte[] data, Camera camera) {
            if (decoding || found || stopped || data == null) return;
            decoding = true;
            final int w = pw, h = ph;
            try {
                worker.execute(() -> {
                    try {
                        PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
                        Result r;
                        try {
                            r = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
                        } catch (NotFoundException nf) {
                            reader.reset();
                            r = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src.invert())));
                        }
                        final String text = r.getText();
                        if (text != null && !stopped) {
                            found = true;
                            post(() -> { if (!stopped) listener.onResult(text); });
                        }
                    } catch (Exception ignored) {
                        // no code in this frame
                    } finally {
                        reader.reset();
                        decoding = false;
                    }
                });
            } catch (Exception e) {
                decoding = false;
            }
        }

        @Override public void surfaceCreated(SurfaceHolder holder) {
            surfaceReady = true;
            if (wantRunning) openCamera();
        }

        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            closeCamera();
        }

        /** Dimmed frame, corner brackets and a moving scan line. */
        private static final class Overlay extends View {
            private final Paint dim = new Paint(), corner = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint();
            private ValueAnimator anim;
            private float t;

            Overlay(Context c) {
                super(c);
                dim.setColor(0x88000000);
                corner.setColor(0xFF2F6BFF);
                corner.setStyle(Paint.Style.STROKE);
                corner.setStrokeWidth(W.dp(c, 4));
                corner.setStrokeCap(Paint.Cap.ROUND);
                line.setColor(0xCC2F6BFF);
                line.setStrokeWidth(W.dp(c, 2));
            }

            void begin() {
                if (anim != null) return;
                anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(1800);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setRepeatMode(ValueAnimator.REVERSE);
                anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
                anim.start();
            }

            void cancel() {
                if (anim != null) { anim.cancel(); anim = null; }
            }

            @Override
            protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight();
                float side = Math.min(w, h) * 0.66f;
                float l = (w - side) / 2f, tp = (h - side) / 2f, r = l + side, b = tp + side;
                cv.drawRect(0, 0, w, tp, dim);
                cv.drawRect(0, b, w, h, dim);
                cv.drawRect(0, tp, l, b, dim);
                cv.drawRect(r, tp, w, b, dim);

                float k = side * 0.16f;
                cv.drawLine(l, tp, l + k, tp, corner); cv.drawLine(l, tp, l, tp + k, corner);
                cv.drawLine(r, tp, r - k, tp, corner); cv.drawLine(r, tp, r, tp + k, corner);
                cv.drawLine(l, b, l + k, b, corner);   cv.drawLine(l, b, l, b - k, corner);
                cv.drawLine(r, b, r - k, b, corner);   cv.drawLine(r, b, r, b - k, corner);

                float y = tp + 8 + (side - 16) * t;
                cv.drawLine(l + 10, y, r - 10, y, line);
            }
        }
    }
}
