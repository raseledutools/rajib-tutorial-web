package com.rajibtutorial;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Shared helpers for embedding YouTube inside a locked-down WebView. */
final class Media {
    private Media() {}

    @SuppressLint("SetJavaScriptEnabled")
    static WebView newPlayerWebView(Context c) {
        WebView w = new WebView(c);
        w.setBackgroundColor(Color.BLACK);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // never navigate away from the embedded player
            }
        });
        return w;
    }

    /** Loads the custom player (no title / share / copy-link / branding). Falls back to a plain iframe. */
    static void load(WebView w, String embedUrl) {
        String html = null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/embed/([A-Za-z0-9_-]{11})").matcher(embedUrl);
            if (m.find()) {
                boolean auto = embedUrl.contains("autoplay=1");
                java.io.InputStream in = w.getContext().getAssets().open("player.html");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                html = out.toString("UTF-8")
                    .replace("__VID__", m.group(1))
                    .replace("__AUTOPLAY__", auto ? "1" : "0")
                    .replace("__ORIGIN__", Store.EMBED_ORIGIN.replaceAll("/+$", ""));
            }
        } catch (Exception ignored) { }
        if (html == null) {
            html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'/></head>"
                + "<body style='margin:0;background:#000'>"
                + "<iframe style='position:absolute;top:0;left:0;width:100%;height:100%;border:0' src='" + embedUrl + "' "
                + "allow='autoplay; encrypted-media; accelerometer; gyroscope; picture-in-picture' allowfullscreen></iframe>"
                + "</body></html>";
        }
        w.loadDataWithBaseURL(Store.EMBED_ORIGIN, html, "text/html", "utf-8", null);
    }

    static void destroy(WebView w) {
        if (w == null) return;
        try {
            w.stopLoading();
            w.loadUrl("about:blank");
            if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
            w.destroy();
        } catch (Exception ignored) { }
    }
}
