package com.rajibtutorial;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * WhatsApp-style document card for the Discussion chat: a preview of what is inside
 * (first PDF page / first slide text / first page text / sheet names), then file name,
 * type, page count and size. Previews are built from a cached download, so opening the
 * file afterwards is instant.
 */
final class FileCard {
    private FileCard() {}

    private static final class Info {
        Bitmap thumb;
        String snippet = "";
        String meta = "";
        int bytes() { return (thumb == null ? 0 : thumb.getByteCount()) + 1024; }
    }

    private static final LruCache<String, Info> INFO = new LruCache<String, Info>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Info v) { return v.bytes(); }
    };
    private static final HashMap<String, Long> FAIL = new HashMap<>();   // url -> last failure time
    private static final java.util.HashSet<String> LOADING = new java.util.HashSet<>();
    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final long CAP_PDF = 30L * 1024 * 1024;
    private static final long CAP_OFFICE = 15L * 1024 * 1024;
    private static final long CAP_TEXT = 1L * 1024 * 1024;

    // ── helpers ─────────────────────────────────────────────
    private static boolean isOffice(String e) {
        return e.equals("doc") || e.equals("docx") || e.equals("xls") || e.equals("xlsx")
            || e.equals("ppt") || e.equals("pptx");
    }

    private static boolean isText(String e) {
        switch (e) {
            case "txt": case "csv": case "json": case "md": case "log": case "xml": case "html":
            case "htm": case "java": case "py": case "js": case "c": case "cpp": case "ino":
                return true;
            default:
                return false;
        }
    }

    private static int color(String e) {
        if (e.equals("pdf")) return 0xFFE5453B;
        if (e.startsWith("ppt")) return 0xFFD9622B;
        if (e.startsWith("doc")) return 0xFF2B6CD9;
        if (e.startsWith("xls")) return 0xFF1E8E4F;
        return 0xFF6B7280;
    }

    private static String badge(String e) {
        if (e.equals("pdf")) return "PDF";
        if (e.startsWith("ppt")) return "PPT";
        if (e.startsWith("doc")) return "DOC";
        if (e.startsWith("xls")) return "XLS";
        if (e.isEmpty()) return "FILE";
        return e.length() > 4 ? e.substring(0, 4).toUpperCase(Locale.US) : e.toUpperCase(Locale.US);
    }

    private static String typeName(String e) {
        if (e.equals("pdf")) return "PDF";
        if (e.startsWith("ppt")) return "PowerPoint";
        if (e.startsWith("doc")) return "Word";
        if (e.startsWith("xls")) return "Excel";
        if (e.isEmpty()) return "File";
        return e.toUpperCase(Locale.US);
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    // ── UI ──────────────────────────────────────────────────
    private static final class Views {
        ImageView thumb;
        TextView snippet, bigBadge, meta;
        int width;
    }

    private static void apply(Views v, Info in) {
        if (in.meta != null && !in.meta.isEmpty()) v.meta.setText(in.meta);
        if (in.thumb != null) {
            v.bigBadge.setVisibility(View.GONE);
            v.snippet.setVisibility(View.GONE);
            v.thumb.setVisibility(View.VISIBLE);
            Matrix mx = new Matrix();
            float s = (float) v.width / Math.max(1, in.thumb.getWidth());
            mx.setScale(s, s);
            v.thumb.setImageMatrix(mx);
            v.thumb.setImageBitmap(in.thumb);
        } else if (in.snippet != null && !in.snippet.isEmpty()) {
            v.bigBadge.setVisibility(View.GONE);
            v.thumb.setVisibility(View.GONE);
            v.snippet.setText(in.snippet);
            v.snippet.setVisibility(View.VISIBLE);
        }
    }

    static View build(final NativeHomeActivity a, final String url, final String name,
                      boolean mine, int fg, int sub, final FileViewer.Reshare reshare) {
        final String ext = FileViewer.ext(name, url);
        final String shown = (name == null || name.isEmpty()) ? "File" : name;
        final int accent = color(ext);
        final int W = Ui.dp(a, 240);
        final int PH = Ui.dp(a, 140);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.bg(a, mine ? 0x26000000 : 0x0D000000, 10, 0));
        card.setClipToOutline(true);

        // preview area
        FrameLayout pv = new FrameLayout(a);
        pv.setBackgroundColor(0xFFF3EEEA);
        final Views v = new Views();
        v.width = W;

        v.bigBadge = Ui.tv(a, badge(ext), 20, Ui.WHITE, true);
        v.bigBadge.setGravity(Gravity.CENTER);
        v.bigBadge.setBackground(Ui.bg(a, accent, 14, 0));
        pv.addView(v.bigBadge, new FrameLayout.LayoutParams(Ui.dp(a, 72), Ui.dp(a, 72), Gravity.CENTER));

        v.thumb = new ImageView(a);
        v.thumb.setScaleType(ImageView.ScaleType.MATRIX);
        v.thumb.setBackgroundColor(0xFFFFFFFF);
        v.thumb.setVisibility(View.GONE);
        pv.addView(v.thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        v.snippet = Ui.tv(a, "", 11.5f, 0xFF2A2623, false);
        v.snippet.setBackgroundColor(0xFFFFFFFF);
        v.snippet.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 6));
        v.snippet.setMaxLines(9);
        v.snippet.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.snippet.setLineSpacing(0, 1.1f);
        v.snippet.setVisibility(View.GONE);
        pv.addView(v.snippet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card.addView(pv, new LinearLayout.LayoutParams(W, PH));

        // info row
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8));

        TextView sb = Ui.tv(a, badge(ext), 10, Ui.WHITE, true);
        sb.setGravity(Gravity.CENTER);
        sb.setBackground(Ui.bg(a, accent, 6, 0));
        row.addView(sb, Ui.lpm(a, Ui.dp(a, 34), Ui.dp(a, 34), 0, 0, 10, 0));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = Ui.ellipsized(a, shown, 13.5f, fg, true, 2);
        col.addView(nm, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.meta = Ui.ellipsized(a, typeName(ext), 11.5f, sub, false, 1);
        col.addView(v.meta, Ui.lpm(a, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setClickable(true);
        card.setOnClickListener(x -> FileViewer.open(a, url, shown, reshare));

        // preview data
        Info cached = INFO.get(url);
        if (cached != null) apply(v, cached);
        else load(a, url, ext, v);
        return card;
    }

    // ── Loading ─────────────────────────────────────────────
    private static void load(final NativeHomeActivity a, final String url, final String ext, final Views v) {
        final boolean pdf = ext.equals("pdf");
        final boolean off = isOffice(ext);
        final boolean txt = isText(ext);
        if (!pdf && !off && !txt) return;
        Long f = FAIL.get(url);
        if (f != null && System.currentTimeMillis() - f < 60000L) return;
        if (!LOADING.add(url)) {
            // another card for the same url is loading; it fills the cache, we retry shortly
            MAIN.postDelayed(() -> {
                Info c = INFO.get(url);
                if (c != null) apply(v, c);
            }, 1500);
            return;
        }
        final File cacheDir = a.getCacheDir();
        EX.execute(() -> {
            Info in = new Info();
            boolean ok = true;
            try {
                long cap = pdf ? CAP_PDF : off ? CAP_OFFICE : CAP_TEXT;
                File file = FileViewer.download(cacheDir, url, ext.isEmpty() ? "bin" : ext, cap, null);
                long sz = file.length();
                if (pdf) pdfInfo(file, sz, in);
                else if (off) officeInfo(file, ext, sz, in);
                else textInfo(file, ext, sz, in);
            } catch (FileViewer.TooLarge t) {
                in.meta = typeName(ext) + " · " + size(t.size);
            } catch (Throwable t) {
                ok = false;
            }
            final boolean fok = ok;
            final Info fin = in;
            MAIN.post(() -> {
                LOADING.remove(url);
                if (!fok) { FAIL.put(url, System.currentTimeMillis()); return; }
                INFO.put(url, fin);
                apply(v, fin);
            });
        });
    }

    private static void pdfInfo(File file, long sz, Info in) throws Exception {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r = null;
        try {
            r = new PdfRenderer(pfd);
            int n = r.getPageCount();
            in.meta = "PDF · " + plural(n, "page", "pages") + " · " + size(sz);
            if (n > 0) {
                PdfRenderer.Page p = r.openPage(0);
                try {
                    int w = 640;
                    int h = Math.max(1, Math.round(w * (float) p.getHeight() / Math.max(1, p.getWidth())));
                    Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bm.eraseColor(0xFFFFFFFF);
                    p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    in.thumb = bm;
                } finally {
                    p.close();
                }
            }
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
            try { pfd.close(); } catch (Exception ignored) { }
        }
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String readHead(ZipFile z, ZipEntry e, int max) throws Exception {
        InputStream in = z.getInputStream(e);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, got = 0;
            while (got < max && (n = in.read(buf, 0, Math.min(buf.length, max - got))) > 0) {
                bos.write(buf, 0, n);
                got += n;
            }
            return bos.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static List<String> matches(String xml, String regex, int maxItems, int maxChars) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(xml);
        int total = 0;
        while (m.find() && out.size() < maxItems && total < maxChars) {
            String t = unescape(m.group(1)).trim();
            if (t.isEmpty()) continue;
            out.add(t);
            total += t.length();
        }
        return out;
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    private static void officeInfo(File file, String ext, long sz, Info in) {
        in.meta = typeName(ext) + " · " + size(sz);
        ZipFile z = null;
        try {
            z = new ZipFile(file);
            if (ext.equals("pptx")) {
                int n = 0;
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    String nm = en.nextElement().getName();
                    if (nm.startsWith("ppt/slides/slide") && nm.endsWith(".xml")) n++;
                }
                if (n > 0) in.meta = "PowerPoint · " + plural(n, "slide", "slides") + " · " + size(sz);
                ZipEntry e = z.getEntry("ppt/slides/slide1.xml");
                if (e != null) in.snippet = join(matches(readHead(z, e, 300000), "<a:t>([^<]*)</a:t>", 7, 260), "\n");
            } else if (ext.equals("docx")) {
                ZipEntry app = z.getEntry("docProps/app.xml");
                if (app != null) {
                    Matcher pm = Pattern.compile("<Pages>(\\d+)</Pages>").matcher(readHead(z, app, 20000));
                    if (pm.find()) {
                        int n = Integer.parseInt(pm.group(1));
                        if (n > 0) in.meta = "Word · " + plural(n, "page", "pages") + " · " + size(sz);
                    }
                }
                ZipEntry e = z.getEntry("word/document.xml");
                if (e != null) in.snippet = join(matches(readHead(z, e, 300000), "<w:t(?: [^>]*)?>([^<]*)</w:t>", 60, 300), " ");
            } else if (ext.equals("xlsx")) {
                ZipEntry e = z.getEntry("xl/workbook.xml");
                if (e != null) {
                    List<String> sheets = matches(readHead(z, e, 100000), "<sheet [^>]*name=\"([^\"]*)\"", 30, 100000);
                    if (!sheets.isEmpty()) {
                        in.meta = "Excel · " + plural(sheets.size(), "sheet", "sheets") + " · " + size(sz);
                        in.snippet = "Sheets:\n" + join(sheets.subList(0, Math.min(6, sheets.size())), "\n");
                    }
                }
            }
        } catch (Throwable ignored) {
            // legacy .doc/.xls/.ppt are not zip files: keep type + size only
        } finally {
            try { if (z != null) z.close(); } catch (Exception ignored) { }
        }
    }

    private static void textInfo(File file, String ext, long sz, Info in) throws Exception {
        in.meta = ext.toUpperCase(Locale.US) + " · " + size(sz);
        FileInputStream fi = new FileInputStream(file);
        try {
            byte[] buf = new byte[700];
            int n = fi.read(buf);
            if (n > 0) in.snippet = new String(buf, 0, n, "UTF-8").trim();
        } finally {
            fi.close();
        }
    }
}
