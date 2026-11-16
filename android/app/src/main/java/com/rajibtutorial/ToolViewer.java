package com.rajibtutorial;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Full-screen in-app viewer for files created by Study Tools (PDF with zoom, photos with zoom + swipe list). */
final class ToolViewer {
    private ToolViewer() { }

    static boolean canView(StudyToolsActivity.Saved s) {
        return s != null && s.mime != null && (s.mime.contains("pdf") || s.mime.startsWith("image/"));
    }

    static void show(final StudyToolsActivity a, final List<StudyToolsActivity.Saved> files, int start) {
        if (files == null || files.isEmpty()) return;
        if (!canView(files.get(Math.max(0, Math.min(start, files.size() - 1))))) {
            a.openSaved(files.get(0));
            return;
        }
        final StudyToolsActivity.Saved f0 = files.get(Math.max(0, Math.min(start, files.size() - 1)));
        if (f0.mime != null && f0.mime.contains("pdf")) {
            openPdf(a, f0);
            return;
        }
        final int dark = 0xFF15171B;
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        final LinearLayout root = W.column(a);
        root.setBackgroundColor(dark);

        // top bar
        LinearLayout top = W.row(a);
        TextView close = W.text(a, "✕", 20, 0xFFFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> d.dismiss());
        top.addView(close, W.lp(W.dp(a, 52), W.dp(a, 52)));
        final TextView title = W.text(a, "", 15, 0xFFFFFFFF, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView ind = W.text(a, "", 13, 0xCCFFFFFF, false);
        ind.setPadding(W.dp(a, 10), 0, W.dp(a, 16), 0);
        top.addView(ind);
        root.addView(top, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(a, 52)));

        final FrameLayout content = new FrameLayout(a);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // state
        final int[] idx = {Math.max(0, Math.min(start, files.size() - 1))};
        final PdfPagesView[] pv = {null};
        final Bitmap[] bm = {null};
        final boolean[] dead = {false};
        final Runnable[] load = new Runnable[1];

        // bottom bar
        LinearLayout bar = W.row(a);
        bar.setBackgroundColor(0xFF1F2227);
        int bp = W.dp(a, 10);
        bar.setPadding(bp, bp, bp, bp);
        if (files.size() > 1) {
            TextView prev = barBtn(a, "‹ Prev", v -> {
                if (idx[0] > 0) { idx[0]--; load[0].run(); }
            });
            bar.addView(prev, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView next = barBtn(a, "Next ›", v -> {
                if (idx[0] < files.size() - 1) { idx[0]++; load[0].run(); }
            });
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nlp.leftMargin = W.dp(a, 8);
            bar.addView(next, nlp);
        }
        addBar(a, bar, "📁 Folder", v -> a.openFolder());
        addBar(a, bar, "Open with", v -> a.openSaved(files.get(idx[0])));
        addBar(a, bar, "Share", v -> {
            List<StudyToolsActivity.Saved> one = new ArrayList<>();
            one.add(files.get(idx[0]));
            a.shareSaved(one);
        });
        root.addView(bar, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable freeCurrent = () -> {
            content.removeAllViews();
            if (pv[0] != null) { pv[0].release(); pv[0] = null; }
            if (bm[0] != null) { bm[0].recycle(); bm[0] = null; }
        };

        load[0] = () -> {
            freeCurrent.run();
            final StudyToolsActivity.Saved s = files.get(idx[0]);
            final int my = idx[0];
            title.setText(s.name);
            ind.setText(files.size() > 1 ? (my + 1) + " / " + files.size() : "");
            final Uri uri = Uri.parse(s.uri);

            if (s.mime != null && s.mime.contains("pdf")) {
                try {
                    ParcelFileDescriptor pfd = a.getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd == null) throw new Exception("open failed");
                    PdfRenderer r = new PdfRenderer(pfd);
                    PdfPagesView view = new PdfPagesView(a, r, pfd, (p, c) -> ind.setText(p + " / " + c));
                    content.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    pv[0] = view;
                } catch (Exception e) {
                    content.addView(msg(a, "Can't preview this PDF.\nUse “Open with”."));
                }
                return;
            }

            ProgressBar pb = new ProgressBar(a);
            content.addView(pb, new FrameLayout.LayoutParams(W.dp(a, 40), W.dp(a, 40), Gravity.CENTER));
            a.io.execute(() -> {
                Bitmap b = null;
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    try (InputStream in = a.getContentResolver().openInputStream(uri)) {
                        BitmapFactory.decodeStream(in, null, o);
                    }
                    int sample = 1, big = Math.max(o.outWidth, o.outHeight);
                    while (big / (sample * 2) >= 2600) sample *= 2;
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = sample;
                    try (InputStream in = a.getContentResolver().openInputStream(uri)) {
                        b = BitmapFactory.decodeStream(in, null, o);
                    }
                } catch (Throwable ignored) { }
                final Bitmap fb = b;
                a.runOnUiThread(() -> {
                    if (dead[0] || idx[0] != my || fb == null) {
                        if (fb != null) fb.recycle();
                        if (!dead[0] && idx[0] == my) {
                            content.removeAllViews();
                            content.addView(msg(a, "Can't preview this image.\nUse “Open with”."));
                        }
                        return;
                    }
                    content.removeAllViews();
                    ZoomImageView iv = new ZoomImageView(a);
                    iv.setImageBitmap(fb);
                    bm[0] = fb;
                    content.addView(iv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                });
            });
        };

        d.setContentView(root);
        d.setOnDismissListener(x -> { dead[0] = true; freeCurrent.run(); });
        d.show();
        load[0].run();
    }

    /** PDFs open in the reader (zoom + text selection); the saved file is copied to cache first. */
    private static void openPdf(final StudyToolsActivity a, final StudyToolsActivity.Saved s) {
        a.background("Opening…", u -> {
            java.io.File tmp = a.tempOut(s.name);
            try (InputStream in = a.getContentResolver().openInputStream(Uri.parse(s.uri));
                 java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                if (in == null) throw new Exception("Can't open " + s.name);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return () -> PdfReaderDialog.show(a, tmp, s.name, s);
        });
    }

    private static TextView msg(StudyToolsActivity a, String t) {
        TextView tv = W.text(a, t, 14, 0xCCFFFFFF, false);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        return tv;
    }

    private static TextView barBtn(StudyToolsActivity a, String label, View.OnClickListener l) {
        TextView b = W.text(a, label, 13, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(W.dp(a, 8), W.dp(a, 11), W.dp(a, 8), W.dp(a, 11));
        W.ripple(b, 0x26FFFFFF, 18);
        b.setOnClickListener(l);
        return b;
    }

    private static void addBar(StudyToolsActivity a, LinearLayout bar, String label, View.OnClickListener l) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (bar.getChildCount() > 0) lp.leftMargin = W.dp(a, 8);
        bar.addView(barBtn(a, label, l), lp);
    }
}
