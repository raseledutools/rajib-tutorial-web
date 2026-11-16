package com.rajibtutorial;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** PDF tools: merge, split, compress, PDF→photo, photo→PDF. */
final class PdfTools {
    private PdfTools() { }

    static final String PDF = "application/pdf";
    static final String[] PDF_MIME = {"application/pdf"};
    static final String[] IMG_MIME = {"image/*"};

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    static int pageCount(File f) throws Exception {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) {
            PdfRenderer r = new PdfRenderer(pfd);
            int n = r.getPageCount();
            r.close();
            return n;
        }
    }

    static Exception friendly(Exception e, String what) {
        String m = String.valueOf(e.getMessage());
        if (m.toLowerCase(Locale.ROOT).contains("password") || m.toLowerCase(Locale.ROOT).contains("decrypt")
                || m.toLowerCase(Locale.ROOT).contains("encrypt")) {
            return new Exception("This PDF is password-protected. Remove the password first, then " + what + ".");
        }
        return new Exception("Couldn't " + what + ". Make sure the file is a valid PDF.\n(" + m + ")");
    }

    /** Selected-file summary row (single file tools). */
    static View fileRow(StudyToolsActivity a, StudyToolsActivity.Picked p, String extra, boolean image) {
        LinearLayout row = W.row(a);
        row.addView(W.badge(a, image ? "IMG" : "PDF", image ? W.BLUE : W.RED), W.lp(W.dp(a, 44), W.dp(a, 44)));
        LinearLayout t = W.column(a);
        TextView n = W.text(a, p.name, 14.5f, W.TEXT, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.addView(n);
        t.addView(W.text(a, W.size(p.size) + (extra == null ? "" : " · " + extra), 12, W.SUB, false));
        row.addView(t, W.lp(a, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0, 0, 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
        return row;
    }

    /** Single-choice chip group; calls back with the selected index. */
    static LinearLayout chips(StudyToolsActivity a, String[] labels, int selected, StudyToolsActivity.Cb<Integer> cb) {
        final LinearLayout wrap = W.row(a);
        final List<TextView> cs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = W.chip(a, labels[i], i == selected);
            cs.add(c);
            wrap.addView(c, W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
            c.setOnClickListener(v -> {
                for (int k = 0; k < cs.size(); k++) {
                    TextView o = cs.get(k);
                    boolean on = k == idx;
                    o.setTextColor(on ? W.BLUE : W.TEXT);
                    o.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
                    o.setBackground(W.rect(a, on ? W.alpha(W.BLUE, 0x14) : 0xFFF3F5F9, 18, on ? W.BLUE : W.LINE, 1));
                }
                cb.on(idx);
            });
        }
        return wrap;
    }

    static View hscroll(StudyToolsActivity a, View inner) {
        android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(a);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(inner);
        return h;
    }

    // ═════════════════════════ READER ═════════════════════════
    static final class Reader extends ToolPage {
        private StudyToolsActivity.Picked last;
        private LinearLayout box;

        Reader(StudyToolsActivity a) { super(a); }

        @Override String title() { return "PDF Reader"; }

        @Override View body() {
            LinearLayout col = W.column(a);
            col.addView(W.pickBox(a, "📖", "Open a PDF", "Read, zoom, select & copy text — no OCR needed",
                v -> a.pick(false, PDF_MIME, picked -> open(picked.get(0)))));
            box = W.column(a);
            col.addView(box, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));

            LinearLayout tips = W.card(a);
            tips.addView(W.text(a, "What you can do", 14, W.TEXT, true));
            tips.addView(W.text(a, "• Long-press a word, then drag the blue handles\n• Copy, Select all, or Share the selected text\n• Tap “Text” at the top to see the whole PDF as text\n• Pinch or double-tap to zoom", 13, W.SUB, false),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
            tips.addView(W.text(a, "Scanned (photo) PDFs have no text to select.", 12, W.SUB, false),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
            col.addView(tips, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            return col;
        }

        private void open(StudyToolsActivity.Picked p) {
            last = p;
            box.removeAllViews();
            LinearLayout c = W.card(a);
            c.addView(fileRow(a, p, null, false));
            c.addView(W.softBtn(a, "Open again", W.BLUE, v -> PdfReaderDialog.show(a, last.file, last.name, null)),
                W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
            box.addView(c);
            PdfReaderDialog.show(a, p.file, p.name, null);
        }
    }

    // ═════════════════════════ MERGE ═════════════════════════
    static final class Merge extends ToolPage {
        private FileListView list;
        private TextView btn;
        private LinearLayout resultBox;

        Merge(StudyToolsActivity a) { super(a); }

        @Override String title() { return "Merge PDF"; }

        @Override View body() {
            LinearLayout col = W.column(a);
            col.addView(W.pickBox(a, "➕", "Add PDF files", "Select two or more PDFs, then reorder with ▲ ▼",
                v -> a.pick(true, PDF_MIME, picked -> { list.add(picked); resultBox.removeAllViews(); })));
            list = new FileListView(a, false);
            LinearLayout card = W.card(a);
            card.addView(list);
            card.setVisibility(View.GONE);
            list.onChange = () -> {
                card.setVisibility(list.items.isEmpty() ? View.GONE : View.VISIBLE);
                if (btn != null) {
                    btn.setText(list.items.size() >= 2 ? "Merge " + list.items.size() + " PDFs" : "Merge PDFs");
                    W.setEnabledLook(btn, list.items.size() >= 2, W.BLUE);
                }
            };
            col.addView(card, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            resultBox = W.column(a);
            col.addView(resultBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            return col;
        }

        @Override View footer() {
            btn = W.primaryBtn(a, "Merge PDFs", v -> run());
            W.setEnabledLook(btn, false, W.BLUE);
            return btn;
        }

        private void run() {
            if (list.items.size() < 2) { a.toast("Add at least 2 PDFs"); return; }
            final List<StudyToolsActivity.Picked> items = new ArrayList<>(list.items);
            a.background("Merging PDFs…", u -> {
                File out = a.tempOut("merged.pdf");
                try {
                    PDFMergerUtility m = new PDFMergerUtility();
                    for (StudyToolsActivity.Picked p : items) m.addSource(p.file);
                    m.setDestinationFileName(out.getAbsolutePath());
                    m.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
                } catch (Exception e) {
                    throw friendly(e, "merge these PDFs");
                }
                final StudyToolsActivity.Saved s = a.saveFile(out, "merged_" + stamp() + ".pdf", PDF);
                return () -> {
                    resultBox.removeAllViews();
                    List<StudyToolsActivity.Saved> l = new ArrayList<>();
                    l.add(s);
                    resultBox.addView(a.resultCard("PDFs merged", items.size() + " files combined into one PDF", l));
                };
            });
        }
    }

    // ═════════════════════════ SPLIT ═════════════════════════
    static final class Split extends ToolPage {
        private StudyToolsActivity.Picked file;
        private int pages;
        private LinearLayout fileBox, resultBox;
        private EditText range;
        private TextView btn;

        Split(StudyToolsActivity a) { super(a); }

        @Override String title() { return "Split PDF"; }

        @Override View body() {
            LinearLayout col = W.column(a);
            col.addView(W.pickBox(a, "📄", "Choose a PDF", "Then type the pages you want to extract",
                v -> a.pick(false, PDF_MIME, picked -> load(picked.get(0)))));

            fileBox = W.card(a);
            fileBox.setVisibility(View.GONE);
            col.addView(fileBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));

            resultBox = W.column(a);
            col.addView(resultBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            return col;
        }

        @Override View footer() {
            btn = W.primaryBtn(a, "Extract pages", v -> run());
            W.setEnabledLook(btn, false, W.BLUE);
            return btn;
        }

        private void load(StudyToolsActivity.Picked p) {
            try {
                pages = pageCount(p.file);
            } catch (Exception e) {
                a.showError(friendly(e, "open this PDF"));
                return;
            }
            file = p;
            resultBox.removeAllViews();
            fileBox.removeAllViews();
            fileBox.setVisibility(View.VISIBLE);
            fileBox.addView(fileRow(a, p, pages + (pages == 1 ? " page" : " pages"), false));
            fileBox.addView(W.text(a, "Pages to extract", 13, W.SUB, true),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 6));
            range = W.input(a, "e.g. 1, 3-5, 8", InputType.TYPE_CLASS_TEXT);
            fileBox.addView(range);
            fileBox.addView(W.text(a, "Use commas for single pages and a dash for ranges. Total: " + pages, 11.5f, W.SUB, false),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
            W.setEnabledLook(btn, true, W.BLUE);
        }

        /** "1, 3-5" → [1,3,4,5]; null if invalid. */
        private List<Integer> parse(String s) {
            LinkedHashSet<Integer> set = new LinkedHashSet<>();
            try {
                for (String part : s.split(",")) {
                    part = part.trim();
                    if (part.isEmpty()) continue;
                    if (part.contains("-")) {
                        String[] ab = part.split("-");
                        int x = Integer.parseInt(ab[0].trim()), y = Integer.parseInt(ab[1].trim());
                        if (x > y) { int t = x; x = y; y = t; }
                        if (x < 1 || y > pages) return null;
                        for (int i = x; i <= y; i++) set.add(i);
                    } else {
                        int x = Integer.parseInt(part);
                        if (x < 1 || x > pages) return null;
                        set.add(x);
                    }
                }
            } catch (Exception e) {
                return null;
            }
            return set.isEmpty() ? null : new ArrayList<>(set);
        }

        private void run() {
            if (file == null) { a.toast("Choose a PDF first"); return; }
            final String txt = range.getText().toString().trim();
            final List<Integer> sel = parse(txt);
            if (sel == null) { a.toast("Invalid pages. Valid range: 1–" + pages); return; }
            final StudyToolsActivity.Picked f = file;
            a.background("Extracting pages…", u -> {
                File out = a.tempOut("split.pdf");
                PDDocument src = null, dst = null;
                try {
                    src = PDDocument.load(f.file);
                    dst = new PDDocument();
                    for (int p : sel) {
                        PDPage pg = src.getPage(p - 1);
                        PDPage imp = dst.importPage(pg);
                        imp.setResources(pg.getResources());
                    }
                    dst.save(out);
                } catch (Exception e) {
                    throw friendly(e, "split this PDF");
                } finally {
                    try { if (dst != null) dst.close(); } catch (Exception ignored) { }
                    try { if (src != null) src.close(); } catch (Exception ignored) { }
                }
                String tag = txt.replaceAll("[^0-9,\\-]", "").replace(",", "_");
                if (tag.length() > 30) tag = sel.get(0) + "-" + sel.get(sel.size() - 1);
                final StudyToolsActivity.Saved s = a.saveFile(out, "split_pages_" + tag + ".pdf", PDF);
                return () -> {
                    resultBox.removeAllViews();
                    List<StudyToolsActivity.Saved> l = new ArrayList<>();
                    l.add(s);
                    resultBox.addView(a.resultCard("Pages extracted", sel.size() + " page(s) saved as a new PDF", l));
                };
            });
        }
    }

    // ═════════════════════════ COMPRESS ═════════════════════════
    static final class Compress extends ToolPage {
        private StudyToolsActivity.Picked file;
        private LinearLayout fileBox, resultBox;
        private TextView btn;
        private int level = 1;   // 0 light, 1 balanced, 2 strong

        Compress(StudyToolsActivity a) { super(a); }

        @Override String title() { return "Compress PDF"; }

        @Override View body() {
            LinearLayout col = W.column(a);
            col.addView(W.pickBox(a, "🗜️", "Choose a PDF", "Shrinks embedded images, keeps text sharp",
                v -> a.pick(false, PDF_MIME, picked -> load(picked.get(0)))));
            fileBox = W.card(a);
            fileBox.setVisibility(View.GONE);
            col.addView(fileBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            resultBox = W.column(a);
            col.addView(resultBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            return col;
        }

        @Override View footer() {
            btn = W.primaryBtn(a, "Compress & save", v -> run());
            W.setEnabledLook(btn, false, W.BLUE);
            return btn;
        }

        private void load(StudyToolsActivity.Picked p) {
            file = p;
            resultBox.removeAllViews();
            fileBox.removeAllViews();
            fileBox.setVisibility(View.VISIBLE);
            fileBox.addView(fileRow(a, p, null, false));
            fileBox.addView(W.text(a, "Compression level", 13, W.SUB, true),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 8));
            fileBox.addView(hscroll(a, chips(a, new String[]{"Light", "Balanced", "Strong"}, level, i -> level = i)));
            fileBox.addView(W.text(a, "Strong gives the smallest file with lower image quality.", 11.5f, W.SUB, false),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
            W.setEnabledLook(btn, true, W.BLUE);
        }

        private void run() {
            if (file == null) { a.toast("Choose a PDF first"); return; }
            final StudyToolsActivity.Picked f = file;
            final int maxDim = level == 0 ? 2400 : level == 1 ? 1700 : 1100;
            final float quality = level == 0 ? 0.80f : level == 1 ? 0.60f : 0.40f;
            final boolean light = level == 0;
            a.background("Compressing…", u -> {
                File out = a.tempOut("compressed.pdf");
                PDDocument doc = null;
                try {
                    doc = PDDocument.load(f.file);
                    int done = 0, total = doc.getNumberOfPages();
                    for (PDPage page : doc.getPages()) {
                        done++;
                        u.msg("Compressing page " + done + " / " + total);
                        PDResources res = page.getResources();
                        if (res == null) continue;
                        for (COSName name : res.getXObjectNames()) {
                            try {
                                PDXObject xo = res.getXObject(name);
                                if (!(xo instanceof PDImageXObject)) continue;
                                PDImageXObject img = (PDImageXObject) xo;
                                if (img.isStencil() || img.getBitsPerComponent() == 1) continue;
                                if (img.getWidth() < 160 || img.getHeight() < 160) continue;
                                boolean small = img.getWidth() <= maxDim && img.getHeight() <= maxDim;
                                String suf = img.getSuffix();
                                if (light && small && suf != null && suf.startsWith("jp")) continue;
                                Bitmap bmp = img.getImage();
                                if (bmp == null) continue;
                                int big = Math.max(bmp.getWidth(), bmp.getHeight());
                                if (big > maxDim) {
                                    float sc = maxDim / (float) big;
                                    Bitmap sm = Bitmap.createScaledBitmap(bmp,
                                        Math.max(1, Math.round(bmp.getWidth() * sc)),
                                        Math.max(1, Math.round(bmp.getHeight() * sc)), true);
                                    if (sm != bmp) bmp.recycle();
                                    bmp = sm;
                                }
                                PDImageXObject neu = JPEGFactory.createFromImage(doc, bmp, quality);
                                bmp.recycle();
                                res.put(name, neu);
                            } catch (Exception | OutOfMemoryError skip) {
                                // leave this image untouched
                            }
                        }
                    }
                    doc.save(out);
                } catch (Exception e) {
                    throw friendly(e, "compress this PDF");
                } finally {
                    try { if (doc != null) doc.close(); } catch (Exception ignored) { }
                }
                final long before = f.size, after = out.length();
                if (after >= before) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    return () -> {
                        resultBox.removeAllViews();
                        LinearLayout c = W.card(a);
                        c.addView(W.text(a, "Already optimized", 16, W.TEXT, true));
                        c.addView(W.text(a, "This PDF can't be made smaller at this level (" + W.size(before)
                            + "). Try “Strong” or choose another file.", 13, W.SUB, false),
                            W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
                        resultBox.addView(c);
                    };
                }
                final StudyToolsActivity.Saved s = a.saveFile(out, "compressed_" + StudyToolsActivity.baseName(f.name) + ".pdf", PDF);
                return () -> {
                    resultBox.removeAllViews();
                    List<StudyToolsActivity.Saved> l = new ArrayList<>();
                    l.add(s);
                    int pct = Math.round(100f * (before - after) / before);
                    resultBox.addView(a.resultCard("Compressed — " + pct + "% smaller",
                        W.size(before) + "  →  " + W.size(after), l));
                };
            });
        }
    }

    // ═════════════════════════ PDF → PHOTO ═════════════════════════
    static final class ToPhoto extends ToolPage {
        private StudyToolsActivity.Picked file;
        private LinearLayout fileBox, resultBox;
        private TextView btn;
        private int q = 1;
        private static final float[] SCALE = {1.5f, 2f, 3f, 4f};

        ToPhoto(StudyToolsActivity a) { super(a); }

        @Override String title() { return "PDF to Photo"; }

        @Override View body() {
            LinearLayout col = W.column(a);
            col.addView(W.pickBox(a, "🖼️", "Choose a PDF", "Every page becomes a high-quality PNG",
                v -> a.pick(false, PDF_MIME, picked -> load(picked.get(0)))));
            fileBox = W.card(a);
            fileBox.setVisibility(View.GONE);
            col.addView(fileBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            resultBox = W.column(a);
            col.addView(resultBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            return col;
        }

        @Override View footer() {
            btn = W.primaryBtn(a, "Convert to PNG", v -> run());
            W.setEnabledLook(btn, false, W.BLUE);
            return btn;
        }

        private void load(StudyToolsActivity.Picked p) {
            int pages;
            try {
                pages = pageCount(p.file);
            } catch (Exception e) {
                a.showError(friendly(e, "open this PDF"));
                return;
            }
            file = p;
            resultBox.removeAllViews();
            fileBox.removeAllViews();
            fileBox.setVisibility(View.VISIBLE);
            fileBox.addView(fileRow(a, p, pages + (pages == 1 ? " page" : " pages"), false));
            fileBox.addView(W.text(a, "Quality", 13, W.SUB, true),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 8));
            fileBox.addView(hscroll(a, chips(a, new String[]{"Normal 150", "High 200", "Ultra HD 300", "Super HD 400"}, q, i -> q = i)));
            W.setEnabledLook(btn, true, W.BLUE);
        }

        private void run() {
            if (file == null) { a.toast("Choose a PDF first"); return; }
            final StudyToolsActivity.Picked f = file;
            final float scale = SCALE[q];
            a.background("Converting…", u -> {
                List<StudyToolsActivity.Saved> saved = new ArrayList<>();
                String base = StudyToolsActivity.baseName(f.name);
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f.file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    PdfRenderer r = new PdfRenderer(pfd);
                    int n = r.getPageCount();
                    for (int i = 0; i < n; i++) {
                        u.msg("Page " + (i + 1) + " / " + n);
                        PdfRenderer.Page pg = r.openPage(i);
                        float sc = scale;
                        long px = (long) (pg.getWidth() * sc) * (long) (pg.getHeight() * sc);
                        while (px > 18_000_000L && sc > 1f) { sc -= 0.25f; px = (long) (pg.getWidth() * sc) * (long) (pg.getHeight() * sc); }
                        int w = Math.max(1, Math.round(pg.getWidth() * sc));
                        int h = Math.max(1, Math.round(pg.getHeight() * sc));
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pg.close();
                        File out = a.tempOut("page.png");
                        try (FileOutputStream fo = new FileOutputStream(out)) {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                        }
                        bmp.recycle();
                        saved.add(a.saveFile(out, base + "_page_" + (i + 1) + ".png", "image/png"));
                    }
                    r.close();
                } catch (Exception e) {
                    if (saved.isEmpty()) throw friendly(e, "convert this PDF");
                    throw e;
                }
                final List<StudyToolsActivity.Saved> fin = saved;
                return () -> {
                    resultBox.removeAllViews();
                    resultBox.addView(a.resultCard(fin.size() + " image(s) created", "One PNG per page", fin));
                };
            });
        }
    }

    // ═════════════════════════ PHOTO → PDF ═════════════════════════
    static final class FromPhotos extends ToolPage {
        private FileListView list;
        private TextView btn;
        private LinearLayout resultBox;
        private boolean a4 = true;

        FromPhotos(StudyToolsActivity a) { super(a); }

        @Override String title() { return "Photo to PDF"; }

        @Override View body() {
            LinearLayout col = W.column(a);
            col.addView(W.pickBox(a, "➕", "Add photos", "JPG / PNG — arrange the order with ▲ ▼",
                v -> a.pick(true, IMG_MIME, picked -> { list.add(picked); resultBox.removeAllViews(); })));
            list = new FileListView(a, true);
            final LinearLayout card = W.card(a);
            card.addView(list);
            card.addView(W.text(a, "Page size", 13, W.SUB, true),
                W.lp(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 8));
            card.addView(hscroll(a, chips(a, new String[]{"A4 page", "Fit to image"}, 0, i -> a4 = i == 0)));
            card.setVisibility(View.GONE);
            list.onChange = () -> {
                card.setVisibility(list.items.isEmpty() ? View.GONE : View.VISIBLE);
                if (btn != null) {
                    btn.setText(list.items.isEmpty() ? "Create PDF" : "Create PDF (" + list.items.size() + ")");
                    W.setEnabledLook(btn, !list.items.isEmpty(), W.BLUE);
                }
            };
            col.addView(card, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            resultBox = W.column(a);
            col.addView(resultBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            return col;
        }

        @Override View footer() {
            btn = W.primaryBtn(a, "Create PDF", v -> run());
            W.setEnabledLook(btn, false, W.BLUE);
            return btn;
        }

        private void run() {
            if (list.items.isEmpty()) { a.toast("Add photos first"); return; }
            final List<StudyToolsActivity.Picked> items = new ArrayList<>(list.items);
            final boolean fitA4 = a4;
            a.background("Creating PDF…", u -> {
                File out = a.tempOut("photos.pdf");
                PDDocument doc = new PDDocument();
                try {
                    int i = 0;
                    for (StudyToolsActivity.Picked p : items) {
                        u.msg("Adding photo " + (++i) + " / " + items.size());
                        Bitmap bmp = PhotoTools.decode(p.file, 2200);
                        if (bmp == null) throw new Exception("Can't read " + p.name);
                        PDImageXObject img = JPEGFactory.createFromImage(doc, bmp, 0.85f);
                        float iw = bmp.getWidth(), ih = bmp.getHeight();
                        bmp.recycle();
                        PDPage page;
                        float x, y, w, h;
                        if (fitA4) {
                            PDRectangle r = PDRectangle.A4;
                            float m = 24f;
                            float sc = Math.min((r.getWidth() - 2 * m) / iw, (r.getHeight() - 2 * m) / ih);
                            w = iw * sc; h = ih * sc;
                            x = (r.getWidth() - w) / 2f; y = (r.getHeight() - h) / 2f;
                            page = new PDPage(r);
                        } else {
                            w = 595f; h = ih * 595f / iw; x = 0; y = 0;
                            page = new PDPage(new PDRectangle(w, h));
                        }
                        doc.addPage(page);
                        PDPageContentStream cs = new PDPageContentStream(doc, page);
                        cs.drawImage(img, x, y, w, h);
                        cs.close();
                    }
                    doc.save(out);
                } finally {
                    try { doc.close(); } catch (Exception ignored) { }
                }
                final StudyToolsActivity.Saved s = a.saveFile(out, "photos_" + stamp() + ".pdf", PDF);
                return () -> {
                    resultBox.removeAllViews();
                    List<StudyToolsActivity.Saved> l = new ArrayList<>();
                    l.add(s);
                    resultBox.addView(a.resultCard("PDF created", items.size() + " photo(s) combined", l));
                };
            });
        }
    }
}
