package com.rajibtutorial;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Full-screen PDF reader: pages with text selection, plus a "Text" screen with the whole document text. */
final class PdfReaderDialog {
    private PdfReaderDialog() { }

    private static final int MAX_SHOWN = 200_000;

    static void show(final StudyToolsActivity a, final File pdf, final String name, final StudyToolsActivity.Saved saved) {
        final PdfRenderer renderer;
        final ParcelFileDescriptor pfd;
        try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            a.showError(new Exception("Can't open this PDF. It may be damaged or password-protected."));
            return;
        }

        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final LinearLayout root = W.column(a);
        root.setBackgroundColor(0xFF15171B);

        // ── top bar ──
        LinearLayout top = W.row(a);
        TextView close = W.text(a, "✕", 20, 0xFFFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> d.dismiss());
        top.addView(close, W.lp(W.dp(a, 52), W.dp(a, 52)));
        TextView title = W.text(a, name, 15, 0xFFFFFFFF, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView ind = W.text(a, "", 13, 0xCCFFFFFF, false);
        ind.setPadding(W.dp(a, 8), 0, W.dp(a, 8), 0);
        top.addView(ind);
        final TextView modeBtn = W.text(a, "Aa  Text", 13, 0xFFFFFFFF, true);
        modeBtn.setGravity(Gravity.CENTER);
        modeBtn.setPadding(W.dp(a, 12), W.dp(a, 8), W.dp(a, 12), W.dp(a, 8));
        W.ripple(modeBtn, 0x33FFFFFF, 16);
        top.addView(modeBtn, W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 10, 0));
        root.addView(top, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(a, 52)));

        final FrameLayout content = new FrameLayout(a);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final FrameLayout bottom = new FrameLayout(a);
        bottom.setBackgroundColor(0xFF1F2227);
        root.addView(bottom, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── state ──
        final boolean[] textMode = {false};
        final String[] full = {null};
        final float[] textSize = {16f};
        final TextView[] textView = {null};

        final PdfReaderView view = new PdfReaderView(a, renderer, pfd, pdf, new PdfReaderView.Host() {
            @Override public void onPage(int page, int count) {
                if (!textMode[0]) ind.setText(page + " / " + count);
            }
            @Override public void onCopy(String text) { a.copyText(text); }
            @Override public void onShare(String text) {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    a.startActivity(Intent.createChooser(i, "Share"));
                } catch (Exception e) { a.toast("Can't share"); }
            }
            @Override public void onNote(String msg) { a.toast(msg); }
        });

        final Runnable showPages = () -> {
            textMode[0] = false;
            modeBtn.setText("Aa  Text");
            content.removeAllViews();
            content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            bottom.removeAllViews();
            bottom.addView(pageBar(a, saved));
        };

        final Runnable showText = () -> {
            textMode[0] = true;
            modeBtn.setText("📄  Pages");
            ind.setText("");
            content.removeAllViews();
            bottom.removeAllViews();
            final String text = full[0];
            if (text == null || text.isEmpty()) {
                TextView m = W.text(a, "This PDF has no selectable text.\nIt looks like a scanned image.", 15, 0xCCFFFFFF, false);
                m.setGravity(Gravity.CENTER);
                content.addView(m, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                return;
            }
            ScrollView sv = new ScrollView(a);
            sv.setBackgroundColor(0xFFF7F5F2);
            TextView tv = W.text(a, text.length() > MAX_SHOWN
                ? text.substring(0, MAX_SHOWN) + "\n\n… (showing the first " + MAX_SHOWN + " characters — Copy all copies everything)"
                : text, textSize[0], 0xFF1B1F27, false);
            tv.setTextIsSelectable(true);
            tv.setLineSpacing(0, 1.3f);
            int p = W.dp(a, 16);
            tv.setPadding(p, p, p, p * 2);
            sv.addView(tv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            textView[0] = tv;

            LinearLayout bar = W.row(a);
            int bp = W.dp(a, 10);
            bar.setPadding(bp, bp, bp, bp);
            addBtn(a, bar, "Copy all", v -> a.copyText(text));
            addBtn(a, bar, "Share .txt", v -> shareTxt(a, name, text));
            addBtn(a, bar, "A−", v -> { textSize[0] = Math.max(11f, textSize[0] - 2f); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize[0]); });
            addBtn(a, bar, "A+", v -> { textSize[0] = Math.min(30f, textSize[0] + 2f); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize[0]); });
            bottom.addView(bar);
        };

        modeBtn.setOnClickListener(v -> {
            if (textMode[0]) { showPages.run(); return; }
            if (full[0] != null) { showText.run(); return; }
            a.background("Reading text…", u -> {
                StringBuilder sb = new StringBuilder();
                PDDocument doc = null;
                try {
                    doc = PDDocument.load(pdf);
                    PDFTextStripper st = new PDFTextStripper();
                    int n = doc.getNumberOfPages();
                    for (int p = 1; p <= n; p++) {
                        u.msg("Page " + p + " / " + n);
                        st.setStartPage(p);
                        st.setEndPage(p);
                        String t = st.getText(doc).trim();
                        if (t.isEmpty()) continue;
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append("──── Page ").append(p).append(" ────\n").append(t);
                    }
                } catch (Exception e) {
                    throw new Exception("Couldn't read the text of this PDF.");
                } finally {
                    try { if (doc != null) doc.close(); } catch (Exception ignored) { }
                }
                final String res = sb.toString();
                return () -> { full[0] = res; showText.run(); };
            });
        });

        d.setContentView(root);
        d.setOnDismissListener(x -> view.release());
        d.show();
        showPages.run();
        a.toast("Long-press a word to select text");
    }

    private static View pageBar(final StudyToolsActivity a, final StudyToolsActivity.Saved saved) {
        LinearLayout bar = W.row(a);
        int bp = W.dp(a, 10);
        bar.setPadding(bp, bp, bp, bp);
        if (saved == null) {
            TextView hint = W.text(a, "Pinch to zoom · long-press a word to select · tap “Text” for full text", 12, 0xAAFFFFFF, false);
            hint.setGravity(Gravity.CENTER);
            bar.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return bar;
        }
        addBtn(a, bar, "📁 Folder", v -> a.openFolder());
        addBtn(a, bar, "Open with", v -> a.openSaved(saved));
        addBtn(a, bar, "Share", v -> {
            List<StudyToolsActivity.Saved> one = new ArrayList<>();
            one.add(saved);
            a.shareSaved(one);
        });
        return bar;
    }

    private static void addBtn(StudyToolsActivity a, LinearLayout bar, String label, View.OnClickListener l) {
        TextView b = W.text(a, label, 13, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(W.dp(a, 8), W.dp(a, 11), W.dp(a, 8), W.dp(a, 11));
        W.ripple(b, 0x26FFFFFF, 18);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (bar.getChildCount() > 0) lp.leftMargin = W.dp(a, 8);
        bar.addView(b, lp);
    }

    private static void shareTxt(final StudyToolsActivity a, final String name, final String text) {
        a.background("Saving text…", u -> {
            String base = StudyToolsActivity.baseName(name);
            File tmp = a.tempOut(base + ".txt");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(text.getBytes(StandardCharsets.UTF_8));
            }
            final StudyToolsActivity.Saved s = a.saveFile(tmp, base + ".txt", "text/plain");
            return () -> {
                List<StudyToolsActivity.Saved> one = new ArrayList<>();
                one.add(s);
                a.shareSaved(one);
            };
        });
    }
}
