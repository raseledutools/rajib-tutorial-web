package com.rajibtutorial;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opens files shared in the Discussion inside the app: PDF, Office documents and text. */
final class FileViewer {
    private FileViewer() {}

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int PAPER = 0xFFE9E5E1;

    private static final Set<String> OFFICE = new HashSet<>(Arrays.asList(
        "doc", "docx", "xls", "xlsx", "ppt", "pptx"));
    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
        "txt", "csv", "json", "md", "log", "xml", "html", "htm", "java", "py", "js", "c", "cpp", "ino"));

    interface Prog { void on(int pct); }

    /** Lets the viewer send an edited copy of a file back into the Discussion. */
    interface Reshare { void send(Uri uri); }

    static final class TooLarge extends Exception {
        final long size;
        TooLarge(long size) { super("File is too large to preview"); this.size = size; }
    }

    static String ext(String name, String url) {
        String s = (name != null && name.contains(".")) ? name : url;
        if (s == null) return "";
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int d = s.lastIndexOf('.');
        return d < 0 ? "" : s.substring(d + 1).toLowerCase(Locale.US);
    }

    static String icon(String name, String url) {
        String e = ext(name, url);
        if (e.equals("pdf")) return "📕";
        if (OFFICE.contains(e)) return "📘";
        if (TEXT.contains(e)) return "📄";
        return "📎";
    }

    static void open(NativeHomeActivity a, String url, String name) { open(a, url, name, null); }

    static void open(NativeHomeActivity a, String url, String name, Reshare rs) {
        String e = ext(name, url);
        String title = (name == null || name.isEmpty()) ? "File" : name;
        if (e.equals("pdf")) pdf(a, url, title);
        else if (OFFICE.contains(e)) office(a, url, title, rs);
        else if (TEXT.contains(e)) text(a, url, title);
        else external(a, url);
    }

    static void external(Context c, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(c, "No app found to open this file", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ── Shell (title bar + close) ───────────────────────────
    private static Dialog shell(Context c, String title, View body, View extra) {
        Dialog d = new Dialog(c, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 4), 0);
        TextView t = Ui.ellipsized(c, title, 15.5f, Ui.TEXT, true, 1);
        bar.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (extra != null) bar.addView(extra, Ui.lpm(c, WRAP, WRAP, 8, 0, 4, 0));
        final Dialog fd = d;
        TextView close = Ui.tv(c, "✕", 20, Ui.TEXT, true);
        close.setGravity(Gravity.CENTER);
        Ui.pressFx(close);
        close.setOnClickListener(v -> fd.dismiss());
        bar.addView(close, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 52)));
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, Ui.dp(c, 52)));

        View line = new View(c);
        line.setBackgroundColor(Ui.BORDER);
        root.addView(line, new LinearLayout.LayoutParams(MATCH, 1));
        root.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        d.setContentView(root);
        return d;
    }

    private static LinearLayout loader(Context c, final TextView label, final ProgressBar bar) {
        LinearLayout ld = new LinearLayout(c);
        ld.setOrientation(LinearLayout.VERTICAL);
        ld.setGravity(Gravity.CENTER);
        ld.setPadding(Ui.dp(c, 40), 0, Ui.dp(c, 40), 0);
        label.setGravity(Gravity.CENTER);
        ld.addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));
        ld.addView(bar, Ui.lpm(c, MATCH, Ui.dp(c, 6), 0, 10, 0, 0));
        return ld;
    }

    private static String failText(Exception e) {
        String m = e != null && e.getMessage() != null ? e.getMessage() : "";
        if (m.contains("401") || m.contains("403")) {
            return "File is blocked by the server (HTTP 401/403). In Cloudinary: Settings → Security → enable PDF and ZIP delivery.";
        }
        return "Could not load the file. Check your internet connection.";
    }

    // ── Download to cache ───────────────────────────────────
    static File download(File cacheDir, String url, String ext, long maxBytes, Prog p) throws Exception {
        File dir = new File(cacheDir, "disc");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "." + ext);
        if (f.exists() && f.length() > 0) return f;
        File tmp = new File(dir, f.getName() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code >= 400) throw new Exception("HTTP " + code);
            int total = c.getContentLength();
            if (total > maxBytes) throw new TooLarge(total);
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[32 * 1024];
            long got = 0;
            int last = -2, n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (got > maxBytes) { os.close(); in.close(); tmp.delete(); throw new TooLarge(got); }
                int pct = total > 0 ? (int) (got * 100 / total) : -1;
                if (p != null && pct != last) { last = pct; p.on(pct); }
            }
            os.close();
            in.close();
            if (!tmp.renameTo(f)) throw new Exception("Cannot save the file");
            return f;
        } finally {
            c.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    // ── PDF (pinch-to-zoom, drag, double-tap) ───────────────
    private static void pdf(final NativeHomeActivity a, final String url, final String title) {
        final FrameLayout body = new FrameLayout(a);
        body.setBackgroundColor(PAPER);
        final TextView pageInd = Ui.tv(a, "", 12.5f, Ui.SUB, true);
        final Handler h = new Handler(Looper.getMainLooper());

        final TextView label = Ui.tv(a, "Downloading…", 13.5f, Ui.SUB, false);
        final ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setIndeterminate(true);
        final LinearLayout ld = loader(a, label, pb);
        body.addView(ld, new FrameLayout.LayoutParams(MATCH, MATCH));

        final PdfPagesView[] view = {null};
        final boolean[] closed = {false};
        final Dialog d = shell(a, title, body, pageInd);
        d.setOnDismissListener(x -> {
            closed[0] = true;
            if (view[0] != null) view[0].release();
        });
        d.show();

        Store.async(() -> download(a.getCacheDir(), url, "pdf", 60L * 1024 * 1024, pct -> h.post(() -> {
            if (pct >= 0) { pb.setIndeterminate(false); pb.setProgress(pct); label.setText("Downloading…  " + pct + "%"); }
        })), (File f, Exception e) -> {
            if (closed[0]) return;
            if (e != null || f == null) { label.setText(failText(e)); pb.setVisibility(View.GONE); return; }
            ParcelFileDescriptor pfd = null;
            PdfRenderer r;
            try {
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                r = new PdfRenderer(pfd);
            } catch (Exception ex) {
                try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
                f.delete();
                label.setText("Cannot open this PDF (it may be damaged or password protected).");
                pb.setVisibility(View.GONE);
                return;
            }
            body.removeView(ld);
            PdfPagesView v = new PdfPagesView(a, r, pfd, (page, count) -> pageInd.setText(page + " / " + count));
            view[0] = v;
            body.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
        });
    }

    // ── Office documents ────────────────────────────────────
    private static String mimeFor(String e) {
        switch (e) {
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default: return "*/*";
        }
    }

    private static String safeName(String title, String ext) {
        String n = title == null ? "file" : title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty()) n = "file";
        if (!n.toLowerCase(Locale.US).endsWith("." + ext)) n = n + "." + ext;
        return n;
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new java.io.FileInputStream(src);
        OutputStream os = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            in.close();
            os.close();
        }
    }

    private static void edit(final NativeHomeActivity a, final String url, final String title, final String ext,
                             final File[] editFile, final long[] stamp, final TextView sendBtn, final Reshare rs) {
        a.toast("Preparing file for editing…");
        Store.async(() -> {
            File src = download(a.getCacheDir(), url, ext, 100L * 1024 * 1024, null);
            File dir = new File(a.getCacheDir(), "edit");
            dir.mkdirs();
            File dst = new File(dir, safeName(title, ext));
            copy(src, dst);
            return dst;
        }, (File f, Exception e) -> {
            if (e != null || f == null) { a.toast("Could not prepare the file. Check your internet connection."); return; }
            editFile[0] = f;
            stamp[0] = f.lastModified();
            Uri u = androidx.core.content.FileProvider.getUriForFile(a, a.getPackageName() + ".provider", f);
            String mime = mimeFor(ext);
            boolean started = false;
            for (String act : new String[]{Intent.ACTION_EDIT, Intent.ACTION_VIEW}) {
                Intent i = new Intent(act);
                i.setDataAndType(u, mime);
                i.setClipData(android.content.ClipData.newRawUri("file", u));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { a.startActivity(i); started = true; break; } catch (Exception ignored) { }
            }
            if (!started) {
                a.toast("No editor found. Install WPS Office, Microsoft Office or Google Docs/Sheets/Slides.");
                return;
            }
            if (rs != null) sendBtn.setVisibility(View.VISIBLE);
        });
    }

    private static void office(final NativeHomeActivity a, final String url, final String title, final Reshare rs) {
        final String ext = ext(title, url);
        final FrameLayout body = new FrameLayout(a);
        body.setBackgroundColor(Ui.BG);
        final WebView w = new WebView(a);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        final ProgressBar spin = new ProgressBar(a);
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) { spin.setVisibility(View.GONE); }
        });
        body.addView(w, new FrameLayout.LayoutParams(MATCH, MATCH));
        body.addView(spin, new FrameLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36), Gravity.CENTER));

        // title-bar actions: Edit (opens an editor app) and Send (shares the edited copy back)
        final File[] editFile = {null};
        final long[] stamp = {0};
        final boolean[] warned = {false};
        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        final TextView sendBtn = Ui.smallBtn(a, "⬆ Send", Ui.ACCENT, null);
        sendBtn.setVisibility(View.GONE);
        final Dialog[] dref = {null};
        sendBtn.setOnClickListener(v -> {
            File f = editFile[0];
            if (f == null || rs == null) return;
            if (f.lastModified() == stamp[0] && !warned[0]) {
                warned[0] = true;
                a.toast("No changes saved yet. Save in the editor first, then tap Send. Tap again to send as is.");
                return;
            }
            try {
                String base = title;
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                File out = new File(f.getParentFile(), safeName(base + " (edited)", ext));
                copy(f, out);
                Uri u = androidx.core.content.FileProvider.getUriForFile(a, a.getPackageName() + ".provider", out);
                if (dref[0] != null) dref[0].dismiss();
                rs.send(u);
            } catch (Exception ex) {
                a.toast("Could not send the edited file.");
            }
        });
        final TextView editBtn = Ui.smallBtn(a, "✏️ Edit", Ui.TEXT, v -> edit(a, url, title, ext, editFile, stamp, sendBtn, rs));
        actions.addView(editBtn);
        actions.addView(sendBtn, Ui.lpm(a, WRAP, WRAP, 6, 0, 0, 0));

        final Dialog d = shell(a, title, body, actions);
        dref[0] = d;
        d.setOnDismissListener(x -> Media.destroy(w));
        d.show();
        try {
            w.loadUrl("https://view.officeapps.live.com/op/embed.aspx?src=" + URLEncoder.encode(url, "UTF-8"));
        } catch (Exception e) {
            d.dismiss();
            external(a, url);
        }
    }

    // ── Plain text ──────────────────────────────────────────
    private static void text(final NativeHomeActivity a, final String url, String title) {
        final FrameLayout body = new FrameLayout(a);
        body.setBackgroundColor(Ui.BG);
        final TextView label = Ui.tv(a, "Loading…", 13.5f, Ui.SUB, false);
        final ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        final LinearLayout ld = loader(a, label, pb);
        body.addView(ld, new FrameLayout.LayoutParams(MATCH, MATCH));
        final boolean[] closed = {false};
        final Dialog d = shell(a, title, body, null);
        d.setOnDismissListener(x -> closed[0] = true);
        d.show();
        final String e = ext(title, url);
        Store.async(() -> {
            File f = download(a.getCacheDir(), url, e.isEmpty() ? "txt" : e, 2L * 1024 * 1024, null);
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        }, (String txt, Exception ex) -> {
            if (closed[0]) return;
            if (ex != null || txt == null) { label.setText(failText(ex)); pb.setVisibility(View.GONE); return; }
            body.removeView(ld);
            ScrollView sv = new ScrollView(a);
            TextView t = Ui.tv(a, txt, 13, Ui.TEXT, false);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextIsSelectable(true);
            t.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 24));
            sv.addView(t, new ScrollView.LayoutParams(MATCH, WRAP));
            body.addView(sv, new FrameLayout.LayoutParams(MATCH, MATCH));
        });
    }
}
