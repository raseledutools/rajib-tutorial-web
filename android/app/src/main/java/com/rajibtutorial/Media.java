package com.rajibtutorial;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers for embedding YouTube inside a locked-down WebView. */
final class Media {
    private Media() {}

    /** Player WebView. Lessons are shown the way the Windows exe does it: the real youtube.com page in a WebView
     *  (no YouTube IFrame API), our own controls injected on top, ads blocked, with automatic fallback. */
    @SuppressLint("SetJavaScriptEnabled")
    static WebView newPlayerWebView(Context c) {
        return new LessonWebView(c);
    }

    // ── ad / tracking networks (same list as the exe's AD_DOMAINS) ──────────────────────────────
    private static final String[] AD_DOMAINS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com", "adservice.google.com", "2mdn.net",
        "moatads.com", "adnxs.com", "adsrvr.org", "advertising.com", "adform.net", "rubiconproject.com", "pubmatic.com", "openx.net",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com", "casalemedia.com", "33across.com",
        "smartadserver.com", "mgid.com", "revcontent.com", "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "exosrv.com",
        "juicyads.com", "trafficjunky.net", "adsterra.com", "highperformanceformat.com", "effectivegatecpm.com", "onclickads.net",
        "adcash.com", "a-ads.com", "ad-maven.com", "admaven.com", "yieldmo.com", "sharethrough.com", "teads.tv", "zedo.com", "adtng.com",
        "bidswitch.net", "contextweb.com", "lijit.com", "sovrn.com", "indexww.com", "triplelift.com", "smaato.net", "inmobi.com",
        "adroll.com", "adthrive.com", "adskeeper.com", "adcolony.com", "applovin.com"
    };
    private static final Pattern YT_AD_PATH = Pattern.compile("^/(pagead/|api/stats/ads|ptracking|get_midroll_info|youtubei/v1/player/ad_break)");

    private static boolean isAd(Uri u) {
        try {
            String h = u.getHost();
            if (h == null) return false;
            h = h.toLowerCase();
            for (String d : AD_DOMAINS) if (h.equals(d) || h.endsWith("." + d)) return true;
            if ((h.equals("youtube.com") || h.endsWith(".youtube.com")) && u.getPath() != null && YT_AD_PATH.matcher(u.getPath()).find()) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** Hosts a lesson page may redirect to (consent / cookie pages); everything else is blocked. */
    private static boolean allowedHost(String h) {
        if (h == null) return false;
        h = h.toLowerCase();
        return h.equals("youtube.com") || h.endsWith(".youtube.com") || h.equals("youtube-nocookie.com") || h.endsWith(".youtube-nocookie.com")
            || h.equals("google.com") || h.endsWith(".google.com");
    }

    private static String sUiJs;

    private static synchronized String uiTemplate(Context c) {
        if (sUiJs != null) return sUiJs;
        try {
            java.io.InputStream in = c.getAssets().open("lesson_ui.js");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            sUiJs = out.toString("UTF-8");
        } catch (Exception e) { sUiJs = ""; }
        return sUiJs;
    }

    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * 1st try: youtube.com/embed/ID   2nd try (embed refused / error / no video in 12 s): youtube.com/watch?v=ID (desktop page)
     * 3rd try: the old IFrame-API player (assets/player.html). Same chain as the exe's wvPlayer.
     */
    private static final class LessonWebView extends WebView {
        private final Handler h = new Handler(Looper.getMainLooper());
        private String vid = "", embedUrl = "";
        private boolean auto, got, halted, off;
        private int startSec;
        private String mode = "embed";          // embed | watch | legacy
        private long armAt, lastEsc;
        private Runnable fsHandler;             // set => our bar shows a fullscreen button (like the exe's .ytp-fs)
        private boolean fsOpen;

        /** Polls the page flag the fullscreen button sets (no JS bridge is exposed to youtube.com). */
        private final Runnable fsTicker = new Runnable() {
            @Override public void run() {
                evaluateJavascript("(function(){try{if(window.__rtFsReq){window.__rtFsReq=0;return 1}}catch(e){}return 0})()", v -> {
                    if (("1".equals(v)) && fsHandler != null) fsHandler.run();
                });
                h.postDelayed(this, 300);
            }
        };

        private float tDownX, tDownY;
        private long tDownAt;

        /** Detects a short tap on the WebView natively and hands it to the page (window.__rtTap), so the on-screen
         *  controls react even when YouTube's page scripts swallow the page's own touch/click events. */
        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    tDownX = e.getX(); tDownY = e.getY(); tDownAt = SystemClock.uptimeMillis();
                    break;
                case android.view.MotionEvent.ACTION_UP: {
                    float slop = 12 * getResources().getDisplayMetrics().density;
                    if (Math.abs(e.getX() - tDownX) < slop && Math.abs(e.getY() - tDownY) < slop
                        && SystemClock.uptimeMillis() - tDownAt < 600 && getWidth() > 0 && getHeight() > 0) {
                        final float fx = e.getX() / getWidth(), fy = e.getY() / getHeight();
                        // give the page's own handlers a moment first; __rtTap ignores the tap if they already acted
                        h.postDelayed(() -> evaluateJavascript("window.__rtTap&&window.__rtTap(" + fx + "," + fy + ")", null), 160);
                    }
                    break;
                }
                default: break;
            }
            return super.onTouchEvent(e);
        }

        void setFsHandler(Runnable r) {
            fsHandler = r;
            h.removeCallbacks(fsTicker);
            if (r != null) h.postDelayed(fsTicker, 300);
        }

        void setFsOpen(boolean on) {
            fsOpen = on;
            evaluateJavascript("window.__rtFsState&&window.__rtFsState(" + (on ? "true" : "false") + ")", null);
        }

        private final Runnable ticker = new Runnable() {
            @Override public void run() { poll(); }
        };

        @SuppressLint("SetJavaScriptEnabled")
        LessonWebView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            WebSettings s = getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest r) {
                    if (isAd(r.getUrl())) return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                    return null;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return !allowedHost(request.getUrl().getHost()); // never navigate away from the lesson
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (!off && ("embed".equals(mode) || "watch".equals(mode))) inject();
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                    if (request != null && request.isForMainFrame() && !off && !"legacy".equals(mode)) escalate();
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse resp) {
                    if (request != null && request.isForMainFrame() && resp != null && resp.getStatusCode() >= 400
                        && !off && !"legacy".equals(mode)) escalate();
                }
            });
        }

        /** Called once by Media.load(). */
        void start(String embed) {
            embedUrl = embed;
            Matcher m = Pattern.compile("/embed/([A-Za-z0-9_-]{11})").matcher(embed);
            if (!m.find()) { legacy(); return; }
            vid = m.group(1);
            auto = embed.contains("autoplay=1");
            mode = "embed";
            go();
        }

        private String url() {
            return "embed".equals(mode)
                ? "https://www.youtube.com/embed/" + vid + "?autoplay=" + (auto ? 1 : 0)
                    + "&controls=0&rel=0&modestbranding=1&iv_load_policy=3&playsinline=1&fs=0&disablekb=1&cc_load_policy=0&start=" + startSec + "#rtlesson"
                : "https://www.youtube.com/watch?v=" + vid + "#rtlesson";
        }

        private void go() {
            got = false; halted = false; off = false;
            WebSettings s = getSettings();
            if ("watch".equals(mode)) {
                s.setUserAgentString(DESKTOP_UA);       // desktop page: no redirect to the mobile site
                s.setUseWideViewPort(true);
                s.setLoadWithOverviewMode(true);
            } else {
                s.setUserAgentString(null);             // back to the default
            }
            java.util.Map<String, String> hdr = new java.util.HashMap<>();
            hdr.put("Referer", Store.EMBED_ORIGIN);     // embed refuses playback without a referer (Error 153)
            loadUrl(url(), hdr);
            armAt = SystemClock.uptimeMillis() + ("embed".equals(mode) ? 12000 : 15000);
            h.removeCallbacks(ticker);
            h.postDelayed(ticker, 400);
        }

        private void inject() {
            String u = getUrl();
            if (u == null || !u.contains("youtube.com")) return;
            String js = uiTemplate(getContext());
            if (js.isEmpty()) return;
            evaluateJavascript(js.replace("__VID__", vid).replace("__AUTOPLAY__", auto ? "1" : "0")
                .replace("__FS__", fsHandler != null ? "1" : "0"), null);
            if (fsOpen) evaluateJavascript("window.__rtFsState&&window.__rtFsState(true)", null);
        }

        private void poll() {
            if (off || halted) return;
            evaluateJavascript("window.__rtProbe?window.__rtProbe():null", v -> {
                if (off || halted) return;
                try {
                    if (v == null || v.isEmpty() || "null".equals(v)) {
                        inject();                       // page not prepared yet (or YouTube re-rendered it)
                    } else {
                        JSONObject o = new JSONObject(v);
                        if (o.optBoolean("err")) { escalate(); return; }
                        if ("watch".equals(mode)) {
                            String pv = o.optString("v"), path = o.optString("path");
                            if (path.startsWith("/watch") && !pv.isEmpty() && !pv.equals(vid)) { halt(); return; }   // "up next"
                        }
                        if (!got) {
                            int st = o.optInt("st", -1);
                            if (o.optDouble("d", 0) > 0 || o.optBoolean("live") || st == 1 || st == 2 || st == 3 || st == 5) got = true;
                        }
                    }
                } catch (Exception ignored) { }
                if (!got && SystemClock.uptimeMillis() > armAt) escalate();
            });
            h.removeCallbacks(ticker);
            h.postDelayed(ticker, 400);
        }

        private void escalate() {
            long now = SystemClock.uptimeMillis();
            if (off || halted || now - lastEsc < 3000) return;
            lastEsc = now;
            if ("embed".equals(mode)) { mode = "watch"; go(); }
            else legacy();
        }

        private void halt() {
            halted = true;
            h.removeCallbacks(ticker);
            loadUrl("about:blank");
        }

        private void legacy() {
            mode = "legacy";
            off = true;
            h.removeCallbacks(ticker);
            getSettings().setUserAgentString(null);
            loadLegacy(this, embedUrl, fsHandler != null);
        }

        @Override
        protected void onDetachedFromWindow() {
            h.removeCallbacksAndMessages(null);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            // re-parenting (fullscreen) detaches/attaches the view: restart the button poll that detach cleared
            if (fsHandler != null) { h.removeCallbacks(fsTicker); h.postDelayed(fsTicker, 300); }
            if (!off && !halted && !got && vid.length() > 0) { h.removeCallbacks(ticker); h.postDelayed(ticker, 400); }
        }

        @Override
        public void destroy() {
            off = true;
            h.removeCallbacksAndMessages(null);
            super.destroy();
        }
    }

    private static boolean sWarmed = false;

    /**
     * Pre-warms the WebView engine and the YouTube player files once per app run, so the first
     * lesson starts faster. A tiny hidden page opens connections and fetches the player API
     * into the shared HTTP cache, then is destroyed.
     */
    @SuppressLint("SetJavaScriptEnabled")
    static void warmUp(Context c) {
        if (sWarmed) return;
        sWarmed = true;
        try {
            final WebView w = new WebView(c.getApplicationContext());
            w.getSettings().setJavaScriptEnabled(true);
            w.getSettings().setDomStorageEnabled(true);
            w.loadDataWithBaseURL(Store.EMBED_ORIGIN,
                "<html><head>"
                + "<link rel='preconnect' href='https://www.youtube.com' crossorigin>"
                + "<link rel='preconnect' href='https://www.youtube-nocookie.com' crossorigin>"
                + "<link rel='preconnect' href='https://i.ytimg.com' crossorigin>"
                + "<script src='https://www.youtube.com/iframe_api'></script>"
                + "</head><body></body></html>", "text/html", "utf-8", null);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try { w.stopLoading(); w.destroy(); } catch (Exception ignored) { }
            }, 8000);
        } catch (Exception ignored) { }
    }

    /** Shows a fullscreen button in the player's on-screen bar; tapping it runs {@code r}. Call before load(). */
    static void setFullscreenHandler(WebView w, Runnable r) {
        if (w instanceof LessonWebView) ((LessonWebView) w).setFsHandler(r);
    }

    /** Tells the on-screen button whether the player is fullscreen now (swaps its icon). */
    static void setFullscreenState(WebView w, boolean on) {
        if (w instanceof LessonWebView) ((LessonWebView) w).setFsOpen(on);
    }

    /** Starts a lesson / live video in a WebView made by newPlayerWebView(). */
    static void load(WebView w, String embedUrl) {
        if (w instanceof LessonWebView) ((LessonWebView) w).start(embedUrl);
        else loadLegacy(w, embedUrl, false);
    }

    /** Last-resort player: custom controls on top of the YouTube IFrame API (assets/player.html). Falls back to a plain iframe. */
    static void loadLegacy(WebView w, String embedUrl, boolean fs) {
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
                    .replace("__ORIGIN__", Store.EMBED_ORIGIN.replaceAll("/+$", ""))
                    .replace("__FS__", fs ? "1" : "0");
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
