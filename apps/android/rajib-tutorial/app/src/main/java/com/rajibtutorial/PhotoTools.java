package com.rajibtutorial;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Photo tools: resizer, job photo (300x300), signature (300x80). */
final class PhotoTools {
    private PhotoTools() { }

    /** Decodes an image (honouring EXIF rotation) so that its longest side is at most maxSide. */
    static Bitmap decode(File f, int maxSide) {
        String path = f.getAbsolutePath();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        int big = Math.max(o.outWidth, o.outHeight);
        while (big / (sample * 2) >= maxSide) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(path, o);
        if (b == null) return null;

        Matrix m = new Matrix();
        try {
            ExifInterface ex = new ExifInterface(path);
            int ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (ori) {
                case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
                default: break;
            }
        } catch (Exception ignored) { }
        int longest = Math.max(b.getWidth(), b.getHeight());
        if (longest > maxSide) {
            float sc = maxSide / (float) longest;
            m.postScale(sc, sc);
        }
        if (!m.isIdentity()) {
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    static int[] bounds(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return new int[]{o.outWidth, o.outHeight};
    }

    // ═════════════════════════ RESIZE (mode 0 free, 1 job photo, 2 signature) ═════════════════════════
    static final class Resize extends ToolPage {
        private final int mode;
        private StudyToolsActivity.Picked file;
        private Bitmap previewBmp, resultBmp;
        private LinearLayout infoBox, resultBox;
        private EditText wIn, hIn, kbIn;
        private TextView btn;
        private boolean crop = false;

        Resize(StudyToolsActivity a, int mode) {
            super(a);
            this.mode = mode;
        }

        private int fixedW() { return mode == 1 ? 300 : mode == 2 ? 300 : 0; }
        private int fixedH() { return mode == 1 ? 300 : mode == 2 ? 80 : 0; }

        @Override String title() { return mode == 1 ? "Job Photo" : mode == 2 ? "Signature" : "Photo Resizer"; }

        @Override View body() {
            LinearLayout col = W.column(a);
            String sub = mode == 0 ? "Resize to exact pixels" : "Converted to " + fixedW() + " × " + fixedH() + " px JPG";
            col.addView(W.pickBox(a, mode == 2 ? "✍️" : "📷", mode == 2 ? "Choose signature photo" : "Choose a photo", sub,
                v -> a.pick(false, PdfTools.IMG_MIME, p -> load(p.get(0)))));

            infoBox = W.card(a);
            infoBox.setVisibility(View.GONE);
            col.addView(infoBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));

            resultBox = W.column(a);
            col.addView(resultBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            return col;
        }

        @Override View footer() {
            btn = W.primaryBtn(a, "Convert", v -> run());
            W.setEnabledLook(btn, false, W.BLUE);
            return btn;
        }

        @Override void onDestroy() {
            if (previewBmp != null) previewBmp.recycle();
            if (resultBmp != null) resultBmp.recycle();
        }

        private void load(StudyToolsActivity.Picked p) {
            Bitmap pv = decode(p.file, 900);
            if (pv == null) { a.showError(new Exception("This file isn't a readable image.")); return; }
            if (previewBmp != null) previewBmp.recycle();
            previewBmp = pv;
            file = p;
            int[] wh = bounds(p.file);
            resultBox.removeAllViews();
            infoBox.removeAllViews();
            infoBox.setVisibility(View.VISIBLE);

            ImageView iv = new ImageView(a);
            iv.setImageBitmap(pv);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackground(W.rect(a, 0xFFF0F2F6, 10));
            infoBox.addView(iv, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(a, 190)));
            infoBox.addView(W.text(a, p.name + "  ·  " + wh[0] + " × " + wh[1] + " px  ·  " + W.size(p.size), 12, W.SUB, false),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

            if (mode == 0) {
                infoBox.addView(W.text(a, "New size (pixels)", 13, W.SUB, true),
                    W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 6));
                LinearLayout r = W.row(a);
                wIn = W.input(a, "Width", InputType.TYPE_CLASS_NUMBER);
                hIn = W.input(a, "Height", InputType.TYPE_CLASS_NUMBER);
                r.addView(wIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r.addView(W.text(a, "×", 18, W.SUB, true), W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 10, 0));
                r.addView(hIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                infoBox.addView(r);
                final String[] pre = {"300×300", "300×80", "200×200", "413×531", "600×600"};
                LinearLayout cr = W.row(a);
                for (final String s : pre) {
                    TextView c = W.chip(a, s, false);
                    c.setOnClickListener(v -> {
                        String[] x = s.split("×");
                        wIn.setText(x[0]);
                        hIn.setText(x[1]);
                    });
                    cr.addView(c, W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
                }
                infoBox.addView(PdfTools.hscroll(a, cr), W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
            } else {
                infoBox.addView(W.text(a, "Output: " + fixedW() + " × " + fixedH() + " px · JPG", 13, W.BLUE, true),
                    W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            }

            infoBox.addView(W.text(a, "Fit", 13, W.SUB, true),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 8));
            infoBox.addView(PdfTools.hscroll(a, PdfTools.chips(a, new String[]{"Stretch (exact)", "Crop to fill"}, crop ? 1 : 0, i -> crop = i == 1)));

            infoBox.addView(W.text(a, "Max file size in KB (optional)", 13, W.SUB, true),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 6));
            kbIn = W.input(a, mode == 1 ? "e.g. 100" : mode == 2 ? "e.g. 60" : "e.g. 200", InputType.TYPE_CLASS_NUMBER);
            infoBox.addView(kbIn);
            W.setEnabledLook(btn, true, W.BLUE);
        }

        private void run() {
            if (file == null) { a.toast("Choose a photo first"); return; }
            int w, h;
            if (mode == 0) {
                try {
                    w = Integer.parseInt(wIn.getText().toString().trim());
                    h = Integer.parseInt(hIn.getText().toString().trim());
                } catch (Exception e) { a.toast("Enter width and height"); return; }
                if (w < 1 || h < 1 || w > 8000 || h > 8000) { a.toast("Size must be between 1 and 8000"); return; }
            } else {
                w = fixedW(); h = fixedH();
            }
            int kb = 0;
            try { kb = Integer.parseInt(kbIn.getText().toString().trim()); } catch (Exception ignored) { }
            final int fw = w, fh = h, maxKb = kb;
            final boolean doCrop = crop;
            final StudyToolsActivity.Picked f = file;
            final String outName = mode == 1 ? "job-photo.jpg" : mode == 2 ? "signature.jpg" : "resized_" + w + "x" + h + ".jpg";

            a.background("Converting…", u -> {
                Bitmap src = decode(f.file, Math.max(fw, fh) * 2);
                if (src == null) throw new Exception("This file isn't a readable image.");
                Bitmap out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(out);
                cv.drawColor(Color.WHITE);
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
                Rect dst = new Rect(0, 0, fw, fh);
                if (doCrop) {
                    float sc = Math.max(fw / (float) src.getWidth(), fh / (float) src.getHeight());
                    int cw = Math.min(src.getWidth(), Math.round(fw / sc));
                    int ch = Math.min(src.getHeight(), Math.round(fh / sc));
                    int x = (src.getWidth() - cw) / 2, y = (src.getHeight() - ch) / 2;
                    cv.drawBitmap(src, new Rect(x, y, x + cw, y + ch), dst, paint);
                } else {
                    cv.drawBitmap(src, null, dst, paint);
                }
                src.recycle();

                int q = 90;
                byte[] data;
                while (true) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    out.compress(Bitmap.CompressFormat.JPEG, q, bo);
                    data = bo.toByteArray();
                    if (maxKb <= 0 || data.length <= maxKb * 1024L || q <= 15) break;
                    q -= 5;
                }
                File tmp = a.tempOut(outName);
                try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(data); }
                final boolean tooBig = maxKb > 0 && data.length > maxKb * 1024L;
                final int usedQ = q;
                final StudyToolsActivity.Saved s = a.saveFile(tmp, outName, "image/jpeg");
                final Bitmap shown = out;
                return () -> {
                    if (resultBmp != null) resultBmp.recycle();
                    resultBmp = shown;
                    resultBox.removeAllViews();
                    List<StudyToolsActivity.Saved> l = new ArrayList<>();
                    l.add(s);
                    String detail = fw + " × " + fh + " px · " + W.size(s.size) + " · JPG quality " + usedQ
                        + (tooBig ? "\n⚠ Couldn't get under " + maxKb + " KB at this size." : "");
                    resultBox.addView(a.resultCard("Photo ready", detail, l));
                    ImageView iv = new ImageView(a);
                    iv.setImageBitmap(shown);
                    iv.setAdjustViewBounds(true);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setBackground(W.rect(a, 0xFFFFFFFF, 12, W.LINE, 1));
                    int pd = W.dp(a, 10);
                    iv.setPadding(pd, pd, pd, pd);
                    resultBox.addView(iv, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, W.dp(a, 160), 0, 12, 0, 0));
                };
            });
        }
    }
}
