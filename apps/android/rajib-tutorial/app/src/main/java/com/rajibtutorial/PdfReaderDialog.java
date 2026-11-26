package com.rajibtutorial;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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

/**
 * Full-screen PDF reader (WPS-like):
 *  - bottom tab bar: Read · Text · Reading mode · Go to · More
 *  - header + tab bar fade away after 3 s, while scrolling, and come back on tap
 *  - pure Reading mode: no header/footer, no text selection, warm paper colour; tap shows an exit pill
 */
final class PdfReaderDialog {

    private static final int MAX_SHOWN = 200_000;
    private static final long AUTO_HIDE_MS = 3000;

    private final StudyToolsActivity a;
    private final File pdf;
    private final String name;
    private final StudyToolsActivity.Saved saved;
    private final Runnable onClose;

    private Dialog d;
    private FrameLayout content;
    private LinearLayout topBar, bottomBar;
    private TextView ind, exitPill;
    private PdfReaderView view;
    private final TextView[] tabIcon = new TextView[5];
    private final TextView[] tabLbl = new TextView[5];

    private boolean chrome = true, reading = false, textMode = false;
    private String fullText;
    private float textSize = 16f;

    private final Handler hh = new Handler(Looper.getMainLooper());
    private final Runnable hideR = this::hideChrome;
    private final Runnable hidePillR = () -> { if (exitPill != null) exitPill.setVisibility(View.GONE); };

    private PdfReaderDialog(StudyToolsActivity a, File pdf, String name, StudyToolsActivity.Saved saved, Runnable onClose) {
        this.a = a;
        this.pdf = pdf;
        this.name = name;
        this.saved = saved;
        this.onClose = onClose;
    }

    static void show(StudyToolsActivity a, File pdf, String name, StudyToolsActivity.Saved saved) {
        show(a, pdf, name, saved, null);
    }

    static void show(StudyToolsActivity a, File pdf, String name, StudyToolsActivity.Saved saved, Runnable onClose) {
        new PdfReaderDialog(a, pdf, name, saved, onClose).open();
    }

    /** The reader works on a scratch copy in the app cache; remove it the moment the reader closes. */
    private void dropTempCopy() {
        try {
            String p = pdf.getCanonicalPath();
            if (p.startsWith(a.getCacheDir().getCanonicalPath())) //noinspection ResultOfMethodCallIgnored
                pdf.delete();
        } catch (Exception ignored) { }
    }

    // ═════════════════════════ build ═════════════════════════
    private void open() {
        PdfRenderer renderer;
        ParcelFileDescriptor pfd;
        try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            a.showError(new Exception("Can't open this PDF. It may be damaged or password-protected."));
            dropTempCopy();
            if (onClose != null) onClose.run();
            return;
        }

        d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xFF15171B);

        content = new FrameLayout(a);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── header ──
        topBar = W.row(a);
        topBar.setBackgroundColor(0xEE181A1F);
        TextView close = W.text(a, "✕", 20, 0xFFFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> d.dismiss());
        topBar.addView(close, W.lp(W.dp(a, 52), W.dp(a, 52)));
        TextView title = W.text(a, name, 15, 0xFFFFFFFF, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ind = W.text(a, "", 13, 0xCCFFFFFF, false);
        ind.setPadding(W.dp(a, 8), 0, W.dp(a, 16), 0);
        topBar.addView(ind);
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(a, 52), Gravity.TOP));

        // ── bottom tab bar ──
        bottomBar = W.row(a);
        bottomBar.setBackgroundColor(0xF01F2227);
        bottomBar.addView(tab(0, "📖", "Read", v -> { if (reading) exitReading(); showPages(); }), tabLp());
        bottomBar.addView(tab(1, "Aa", "Text", v -> { if (reading) exitReading(); showTextScreen(); }), tabLp());
        bottomBar.addView(tab(2, "👓", "Reading", v -> enterReading()), tabLp());
        bottomBar.addView(tab(3, "🔢", "Go to", v -> goToDialog()), tabLp());
        bottomBar.addView(tab(4, "⋯", "More", v -> moreMenu()), tabLp());
        root.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(a, 58), Gravity.BOTTOM));

        // ── exit pill (reading mode) ──
        exitPill = W.text(a, "✕  Exit reading mode", 13.5f, 0xFFFFFFFF, true);
        exitPill.setGravity(Gravity.CENTER);
        exitPill.setPadding(W.dp(a, 18), W.dp(a, 10), W.dp(a, 18), W.dp(a, 10));
        W.ripple(exitPill, 0xE6262A31, 22);
        exitPill.setVisibility(View.GONE);
        exitPill.setOnClickListener(v -> exitReading());
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        plp.topMargin = W.dp(a, 40);
        root.addView(exitPill, plp);

        view = new PdfReaderView(a, renderer, pfd, pdf, new PdfReaderView.Host() {
            @Override public void onPage(int page, int count) {
                if (!textMode) ind.setText(page + " / " + count);
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
            @Override public void onTap() {
                if (reading) showPill();
                else if (chrome) hideChrome();
                else showChrome();
            }
            @Override public void onScrollStart() { hideChrome(); }
        });

        d.setContentView(root);
        d.setOnKeyListener((dlg, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP && reading) {
                exitReading();
                return true;
            }
            return false;
        });
        d.setOnDismissListener(x -> {
            hh.removeCallbacksAndMessages(null);
            view.release();
            dropTempCopy();
            if (onClose != null) onClose.run();
        });
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            d.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        showPages();
    }

    private LinearLayout.LayoutParams tabLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private View tab(int i, String icon, String label, final View.OnClickListener l) {
        LinearLayout t = W.column(a);
        t.setGravity(Gravity.CENTER);
        TextView ic = W.text(a, icon, 18, 0xCCFFFFFF, true);
        ic.setGravity(Gravity.CENTER);
        TextView lb = W.text(a, label, 10.5f, 0xCCFFFFFF, false);
        lb.setGravity(Gravity.CENTER);
        t.addView(ic);
        t.addView(lb);
        tabIcon[i] = ic;
        tabLbl[i] = lb;
        W.ripple(t, 0x00000000, 0);
        t.setOnClickListener(v -> { scheduleHide(); l.onClick(v); });
        return t;
    }

    private void highlight(int active) {
        for (int i = 0; i < 5; i++) {
            int c = i == active ? 0xFF6FA3FF : 0xCCFFFFFF;
            tabIcon[i].setTextColor(c);
            tabLbl[i].setTextColor(c);
        }
    }

    // ═════════════════════════ header / footer visibility ═════════════════════════
    private void scheduleHide() {
        hh.removeCallbacks(hideR);
        if (!textMode && !reading) hh.postDelayed(hideR, AUTO_HIDE_MS);
    }

    private void showChrome() {
        if (reading) return;
        chrome = true;
        fadeIn(topBar);
        fadeIn(bottomBar);
        scheduleHide();
    }

    private void hideChrome() {
        if (textMode || reading || !chrome) return;
        chrome = false;
        hh.removeCallbacks(hideR);
        fadeOut(topBar);
        fadeOut(bottomBar);
    }

    private void fadeIn(View v) {
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).setDuration(160).start();
    }

    private void fadeOut(final View v) {
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!chrome) v.setVisibility(View.GONE);
        }).start();
    }

    private void showPill() {
        exitPill.setVisibility(View.VISIBLE);
        hh.removeCallbacks(hidePillR);
        hh.postDelayed(hidePillR, 2500);
    }

    // ═════════════════════════ modes ═════════════════════════
    private void showPages() {
        textMode = false;
        content.removeAllViews();
        content.setPadding(0, 0, 0, 0);
        content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        highlight(0);
        if (!reading) showChrome();
    }

    private void enterReading() {
        if (textMode) showPages();
        reading = true;
        hh.removeCallbacks(hideR);
        chrome = false;
        topBar.animate().cancel();
        bottomBar.animate().cancel();
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        view.setReadingMode(true);
        a.toast("Reading mode — tap the screen for the exit button");
    }

    private void exitReading() {
        reading = false;
        view.setReadingMode(false);
        exitPill.setVisibility(View.GONE);
        showChrome();
    }

    private void showTextScreen() {
        if (fullText == null) {
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
                return () -> { fullText = res; buildTextScreen(); };
            });
            return;
        }
        buildTextScreen();
    }

    private void buildTextScreen() {
        textMode = true;
        hh.removeCallbacks(hideR);
        chrome = true;
        fadeIn(topBar);
        fadeIn(bottomBar);
        ind.setText("");
        highlight(1);
        content.removeAllViews();

        final String text = fullText;
        if (text == null || text.isEmpty()) {
            TextView m = W.text(a, "This PDF has no selectable text.\nIt looks like a scanned image.", 15, 0xCCFFFFFF, false);
            m.setGravity(Gravity.CENTER);
            content.addView(m, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        LinearLayout col = W.column(a);
        col.setPadding(0, W.dp(a, 52), 0, W.dp(a, 58));

        LinearLayout tools = W.row(a);
        tools.setBackgroundColor(0xFF1F2227);
        int bp = W.dp(a, 8);
        tools.setPadding(bp, bp, bp, bp);
        final ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(0xFFF7F5F2);
        final TextView tv = W.text(a, text.length() > MAX_SHOWN
            ? text.substring(0, MAX_SHOWN) + "\n\n… (showing the first " + MAX_SHOWN + " characters — Copy all copies everything)"
            : text, textSize, 0xFF1B1F27, false);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(0, 1.3f);
        int p = W.dp(a, 16);
        tv.setPadding(p, p, p, p * 2);
        sv.addView(tv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addChip(tools, "Copy all", v -> a.copyText(text));
        addChip(tools, "Share .txt", v -> shareTxt(text));
        addChip(tools, "A−", v -> { textSize = Math.max(11f, textSize - 2f); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize); });
        addChip(tools, "A+", v -> { textSize = Math.min(30f, textSize + 2f); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize); });

        col.addView(tools, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void addChip(LinearLayout row, String label, View.OnClickListener l) {
        TextView b = W.text(a, label, 13, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(W.dp(a, 8), W.dp(a, 9), W.dp(a, 8), W.dp(a, 9));
        W.ripple(b, 0x26FFFFFF, 16);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) lp.leftMargin = W.dp(a, 8);
        row.addView(b, lp);
    }

    // ═════════════════════════ tab actions ═════════════════════════
    private void goToDialog() {
        if (textMode) { a.toast("Switch to Read to jump to a page"); return; }
        final int n = view.pageCount();
        final EditText in = W.input(a, "1 – " + n, InputType.TYPE_CLASS_NUMBER);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(W.dp(a, 20), W.dp(a, 8), W.dp(a, 20), 0);
        wrap.addView(in);
        new AlertDialog.Builder(a).setTitle("Go to page").setView(wrap)
            .setPositiveButton("Go", (dlg, w) -> {
                try {
                    int p = Integer.parseInt(in.getText().toString().trim());
                    view.goToPage(p);
                } catch (Exception e) { a.toast("Enter a page number"); }
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void moreMenu() {
        final List<String> items = new ArrayList<>();
        items.add("Share PDF");
        items.add("Open with another app");
        if (saved != null) items.add("Open folder");
        new AlertDialog.Builder(a).setItems(items.toArray(new String[0]), (dlg, which) -> {
            String it = items.get(which);
            if (it.startsWith("Share")) {
                if (saved != null) {
                    List<StudyToolsActivity.Saved> one = new ArrayList<>();
                    one.add(saved);
                    a.shareSaved(one);
                } else {
                    a.shareFile(pdf, "application/pdf");
                }
            } else if (it.startsWith("Open with")) {
                if (saved != null) a.openSaved(saved); else a.openFile(pdf, "application/pdf");
            } else {
                a.openFolder();
            }
        }).show();
    }

    private void shareTxt(final String text) {
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
