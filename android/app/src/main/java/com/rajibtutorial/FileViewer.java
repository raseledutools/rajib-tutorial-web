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

    private interface Prog { void on(int pct); }

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

    static void open(NativeHomeActivity a, String url, String name) {
        String e = ext(name, url);
        String title = (name == null || name.isEmpty()) ? "File" : name;
        if (e.equals("pdf")) pdf(a, url, title);
        else if (OFFICE.contains(e)) office(a, url, title);
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
    private static File download(File cacheDir, String url, String ext, long maxBytes, Prog p) throws Exception {
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
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[32 * 1024];
            long got = 0;
            int last = -2, n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (got > maxBytes) { os.close(); in.close(); tmp.delete(); throw new Exception("File is too large to preview"); }
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

    // ── PDF ─────────────────────────────────────────────────
    private static final class Pdf {
        final Object lock = new Object();
        PdfRenderer r;
        ParcelFileDescriptor pfd;
        boolean closed = false;
        int zoomIdx = 0;
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };

        void close() {
            synchronized (lock) {
                closed = true;
                try { if (r != null) r.close(); } catch (Exception ignored) { }
                try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
                r = null;
                pfd = null;
            }
            ex.shutdownNow();
            cache.evictAll();
        }
    }

    private static void pdf(final NativeHomeActivity a, final String url, final String title) {
        final Handler h = new Handler(Looper.getMainLooper());
        final Pdf st = new Pdf();
        final FrameLayout body = new FrameLayout(a);
        body.setBackgroundColor(PAPER);
        final TextView pageInd = Ui.tv(a, "", 12.5f, Ui.SUB, true);

        final TextView label = Ui.tv(a, "Downloading…", 13.5f, Ui.SUB, false);
        final ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setIndeterminate(true);
        final LinearLayout ld = loader(a, label, pb);
        body.addView(ld, new FrameLayout.LayoutParams(MATCH, MATCH));

        final Dialog d = shell(a, title, body, pageInd);
        d.setOnDismissListener(x -> st.close());
        d.show();

        Store.async(() -> download(a.getCacheDir(), url, "pdf", 60L * 1024 * 1024, pct -> h.post(() -> {
            if (pct >= 0) { pb.setIndeterminate(false); pb.setProgress(pct); label.setText("Downloading…  " + pct + "%"); }
        })), (File f, Exception e) -> {
            if (st.closed) return;
            if (e != null || f == null) { label.setText(failText(e)); pb.setVisibility(View.GONE); return; }
            synchronized (st.lock) {
                try {
                    st.pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    st.r = new PdfRenderer(st.pfd);
                } catch (Exception ex) {
                    f.delete();
                    label.setText("Cannot open this PDF (it may be damaged or password protected).");
                    pb.setVisibility(View.GONE);
                    return;
                }
            }
            body.removeView(ld);
            showPages(a, body, st, pageInd, h);
        });
    }

    private static Bitmap renderPage(PdfRenderer r, int idx, int w) {
        PdfRenderer.Page p = r.openPage(idx);
        try {
            int hgt = Math.max(1, Math.round(w * (float) p.getHeight() / Math.max(1, p.getWidth())));
            Bitmap bm = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.WHITE);
            p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bm;
        } finally {
            p.close();
        }
    }

    private static void showPages(final NativeHomeActivity a, FrameLayout body, final Pdf st,
                                  final TextView pageInd, final Handler h) {
        final int count;
        synchronized (st.lock) { count = st.r == null ? 0 : st.r.getPageCount(); }
        final int sw = a.getResources().getDisplayMetrics().widthPixels;
        final float[] zooms = {1f, 1.4f, 1.8f};

        final ListView lv = new ListView(a);
        lv.setBackgroundColor(PAPER);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setDivider(new ColorDrawable(PAPER));
        lv.setDividerHeight(Ui.dp(a, 6));
        final HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setFillViewport(true);
        hs.addView(lv, new FrameLayout.LayoutParams(sw, MATCH));
        body.addView(hs, new FrameLayout.LayoutParams(MATCH, MATCH));
        pageInd.setText("1 / " + count);

        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return count; }
            @Override public Object getItem(int p) { return Integer.valueOf(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(final int pos, View cv, ViewGroup parent) {
                final ImageView iv;
                if (cv instanceof ImageView) {
                    iv = (ImageView) cv;
                } else {
                    iv = new ImageView(a);
                    iv.setScaleType(ImageView.ScaleType.FIT_XY);
                    iv.setBackgroundColor(Color.WHITE);
                }
                final int w = Math.round(sw * zooms[st.zoomIdx]);
                final String key = pos + "@" + w;
                iv.setTag(key);
                Bitmap c = st.cache.get(key);
                if (c != null) {
                    iv.setImageBitmap(c);
                    iv.setLayoutParams(new AbsListView.LayoutParams(w, c.getHeight()));
                    return iv;
                }
                iv.setImageDrawable(null);
                iv.setLayoutParams(new AbsListView.LayoutParams(w, Math.round(w * 1.41f)));
                try {
                    st.ex.execute(() -> {
                        Bitmap bm = null;
                        synchronized (st.lock) {
                            if (st.closed || st.r == null) return;
                            try { bm = renderPage(st.r, pos, w); } catch (Throwable ignored) { }
                        }
                        if (bm == null) return;
                        final Bitmap fb = bm;
                        st.cache.put(key, fb);
                        h.post(() -> {
                            if (st.closed || !key.equals(iv.getTag())) return;
                            iv.setImageBitmap(fb);
                            iv.setLayoutParams(new AbsListView.LayoutParams(w, fb.getHeight()));
                        });
                    });
                } catch (Exception ignored) { }
                return iv;
            }
        };
        lv.setAdapter(ad);
        lv.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }
            @Override public void onScroll(AbsListView v, int first, int vis, int total) {
                pageInd.setText((first + 1) + " / " + count);
            }
        });

        // zoom buttons
        LinearLayout zb = new LinearLayout(a);
        zb.setOrientation(LinearLayout.VERTICAL);
        View.OnClickListener zoom = v -> {
            int ni = st.zoomIdx + (v.getTag().equals("+") ? 1 : -1);
            ni = Math.max(0, Math.min(zooms.length - 1, ni));
            if (ni == st.zoomIdx) return;
            st.zoomIdx = ni;
            lv.setLayoutParams(new FrameLayout.LayoutParams(Math.round(sw * zooms[ni]), MATCH));
            st.cache.evictAll();
            ad.notifyDataSetChanged();
        };
        for (String s : new String[]{"+", "−"}) {
            TextView b = Ui.tv(a, s, 22, Ui.WHITE, true);
            b.setTag(s.equals("+") ? "+" : "-");
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.circle(0xCC1E1B18));
            Ui.pressFx(b);
            b.setOnClickListener(zoom);
            zb.addView(b, Ui.lpm(a, Ui.dp(a, 44), Ui.dp(a, 44), 0, 0, 0, 10));
        }
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
        zp.setMargins(0, 0, Ui.dp(a, 14), Ui.dp(a, 10));
        body.addView(zb, zp);
    }

    // ── Office documents (Microsoft online viewer inside a WebView) ──
    private static void office(final NativeHomeActivity a, String url, String title) {
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
        final Dialog d = shell(a, title, body, null);
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
