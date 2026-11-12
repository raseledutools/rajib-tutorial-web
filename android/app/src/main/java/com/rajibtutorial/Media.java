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

    static void load(WebView w, String embedUrl) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'/></head>"
            + "<body style='margin:0;background:#000'>"
            + "<iframe style='position:absolute;top:0;left:0;width:100%;height:100%;border:0' src='" + embedUrl + "' "
            + "allow='autoplay; encrypted-media; accelerometer; gyroscope; picture-in-picture' allowfullscreen></iframe>"
            + "</body></html>";
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
