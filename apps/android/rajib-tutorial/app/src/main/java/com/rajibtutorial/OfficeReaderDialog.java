package com.rajibtutorial;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * Instant viewer for Word / PowerPoint / Excel files picked from a folder (or opened from a file manager).
 * The file is sent once to the app's own file host, then shown with the online document renderer.
 * The page is wrapped in the app's own header and loading screen, links that leave the document are blocked,
 * and the renderer's own branding is hidden on a best-effort basis.
 */
final class OfficeReaderDialog {
    private OfficeReaderDialog() { }

    private static final String VIEWER = "https://view.officeapps.live.com/op/embed.aspx?src=";
    private static final String CACHE_PREF = "doc_viewer_urls";

    /** Hides the renderer's logo / "open in" links. Runs repeatedly because the page builds itself late. */
    private static final String HIDE_JS =
        "(function(){try{var id='rt_hide',s=document.getElementById(id);if(!s){s=document.createElement('style');s.id=id;"
        + "s.textContent='a[href*=\"office.com\"],a[href*=\"microsoft.com\"],a[href*=\"live.com/\"],a[href*=\"office365\"],"
        + "[id*=\"Branding\"],[class*=\"Branding\"],[id*=\"OfficeLogo\"],[class*=\"OfficeLogo\"],[class*=\"BrandingBar\"],"
        + "[aria-label*=\"Microsoft\"],[title*=\"Microsoft\"],[class*=\"Footer\"] a{display:none!important}';"
        + "(document.head||document.documentElement).appendChild(s);}}catch(e){}})();";

    static boolean isOffice(String ext) {
        if (ext == null) return false;
        switch (ext.toLowerCase(Locale.ROOT)) {
            case "doc": case "docx": case "xls": case "xlsx": case "ppt": case "pptx":
                return true;
            default:
                return false;
        }
    }

    static boolean isOfficeMime(String mime) {
        if (mime == null) return false;
        return mime.equals("application/msword")
            || mime.equals("application/vnd.ms-excel")
            || mime.equals("application/vnd.ms-powerpoint")
            || mime.startsWith("application/vnd.openxmlformats-officedocument.");
    }

    static String ext(String name) {
        if (name == null) return "";
        int d = name.lastIndexOf('.');
        return d < 0 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    static String kind(String ext) {
        switch (ext) {
            case "doc": case "docx": return "Word";
            case "xls": case "xlsx": return "Excel";
            case "ppt": case "pptx": return "Slides";
            default: return "Document";
        }
    }

    static void show(final StudyToolsActivity a, final Uri uri, final String name, final Runnable onClose) {
        final Handler h = new Handler(Looper.getMainLooper());
        final String ext = ext(name);

        // ── layout ──
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = W.column(a);
        root.setBackgroundColor(W.SURFACE);

        LinearLayout bar = W.row(a);
        bar.setBackgroundColor(W.SURFACE);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = W.text(a, "←", 24, W.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> d.dismiss());
        bar.addView(back, W.lp(W.dp(a, 52), W.dp(a, 52)));
        TextView title = W.text(a, name, 15.5f, W.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView more = W.text(a, "Open with", 13, W.BLUE, true);
        more.setPadding(W.dp(a, 12), W.dp(a, 8), W.dp(a, 14), W.dp(a, 8));
        more.setOnClickListener(v -> openExternal(a, uri, ext));
        bar.addView(more);
        root.addView(bar, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(a, 52)));
        View line = new View(a);
        line.setBackgroundColor(W.LINE);
        root.addView(line, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        final FrameLayout body = new FrameLayout(a);
        body.setBackgroundColor(W.BG);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // own loading screen (shown until the page is ready)
        final LinearLayout loading = W.column(a);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(W.BG);
        final int pad = W.dp(a, 40);
        loading.setPadding(pad, 0, pad, 0);
        TextView ico = W.text(a, ext.startsWith("x") ? "📗" : ext.startsWith("p") ? "📙" : "📘", 44, W.TEXT, false);
        ico.setGravity(Gravity.CENTER);
        loading.addView(ico);
        final TextView msg = W.text(a, "Opening…", 14, W.SUB, false);
        msg.setGravity(Gravity.CENTER);
        loading.addView(msg, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        final ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setIndeterminate(true);
        loading.addView(pb, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, W.dp(a, 6), 0, 12, 0, 0));
        body.addView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        d.setContentView(root);
        final WebView[] web = {null};
        final boolean[] closed = {false};
        d.setOnDismissListener(x -> {
            closed[0] = true;
            Media.destroy(web[0]);
            if (onClose != null) onClose.run();
        });
        d.show();

        // ── upload (once per file), then render ──
        final SharedPreferences sp = a.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE);
        final Cloud.Meta meta = Cloud.meta(a, uri);
        if (meta.name == null || meta.name.equals("file")) meta.name = name;
        final String key = Integer.toHexString((uri.toString() + "|" + meta.size).hashCode());
        final String cached = sp.getString(key, null);

        if (meta.size > 100L * 1024 * 1024) {
            fail(msg, pb, "This file is larger than 100 MB and can't be previewed.");
            return;
        }
        if (cached != null) {
            render(a, body, loading, web, closed, d, uri, ext, cached, msg, pb);
            return;
        }
        Store.async(() -> {
            Cloud.Result r = Cloud.upload(a, uri, meta, pct -> h.post(() -> {
                if (closed[0]) return;
                pb.setIndeterminate(false);
                pb.setProgress(pct);
                msg.setText("Opening…");
            }));
            return r.url;
        }, (String url, Exception e) -> {
            if (closed[0]) return;
            if (e != null || url == null || url.isEmpty()) {
                fail(msg, pb, "Couldn't prepare this file. Check your internet connection and try again.");
                return;
            }
            sp.edit().putString(key, url).apply();
            render(a, body, loading, web, closed, d, uri, ext, url, msg, pb);
        });
    }

    private static void fail(TextView msg, ProgressBar pb, String text) {
        msg.setText(text);
        pb.setVisibility(View.GONE);
    }

    private static void render(final StudyToolsActivity a, FrameLayout body, final View loading, WebView[] web,
                               final boolean[] closed, final Dialog d, final Uri uri, String ext, String url,
                               final TextView msg, final ProgressBar pb) {
        if (closed[0]) return;
        msg.setText("Opening…");
        pb.setIndeterminate(true);
        final Handler h = new Handler(Looper.getMainLooper());
        final WebView w = new WebView(a);
        web[0] = w;
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        w.setBackgroundColor(W.BG);
        w.setWebViewClient(new WebViewClient() {
            // keep the viewer inside the document: ignore any link that leaves the renderer
            private boolean leaves(String url) {
                String host = url == null ? "" : String.valueOf(Uri.parse(url).getHost());
                return !host.endsWith("officeapps.live.com");
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return leaves(r.getUrl() == null ? null : r.getUrl().toString());
            }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return leaves(url); }
            @Override public void onPageStarted(WebView v, String u, Bitmap f) { v.evaluateJavascript(HIDE_JS, null); }
            @Override public void onPageFinished(WebView v, String u) {
                v.evaluateJavascript(HIDE_JS, null);
                for (int i = 1; i <= 6; i++) h.postDelayed(() -> { if (!closed[0]) v.evaluateJavascript(HIDE_JS, null); }, i * 700L);
                h.postDelayed(() -> { if (!closed[0]) loading.setVisibility(View.GONE); }, 900);
            }
            @Override public void onReceivedError(WebView v, int code, String desc, String failing) {
                if (!closed[0]) fail(msg, pb, "Couldn't open this file. Check your internet connection.");
            }
        });
        body.addView(w, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        try {
            w.loadUrl(VIEWER + URLEncoder.encode(url, "UTF-8"));
        } catch (Exception e) {
            d.dismiss();
            openExternal(a, uri, ext);
        }
    }

    private static void openExternal(Context c, Uri uri, String ext) {
        try {
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime == null ? "*/*" : mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(Intent.createChooser(i, "Open with"));
        } catch (Exception e) {
            android.widget.Toast.makeText(c, "No app found to open this file", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
