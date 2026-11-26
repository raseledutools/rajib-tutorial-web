package com.rajibtutorial;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Play with Play Store" mode: the official, unmodified YouTube player.
 * No script is injected, nothing is hidden or overlaid, no ads are filtered, the player's own controls and
 * fullscreen button are used, and there is no way to save / download the video.
 */
final class PlayStorePlayer {
    private PlayStorePlayer() { }

    private static final Pattern ID = Pattern.compile("/embed/([A-Za-z0-9_-]{11})");

    @SuppressLint("SetJavaScriptEnabled")
    static WebView create(final Activity act, String embedUrl, boolean autoplay) {
        WebView w = new WebView(act);
        w.setBackgroundColor(Color.BLACK);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);                 // the YouTube player itself needs it
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(!autoplay);

        final Chrome chrome = new Chrome(act);
        w.setWebChromeClient(chrome);
        w.setTag(chrome);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme();
                String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if (sc == null || !(sc.equals("https") || sc.equals("about"))) return true;
                boolean ok = h.equals("youtube.com") || h.endsWith(".youtube.com") || h.equals("youtube-nocookie.com")
                    || h.endsWith(".youtube-nocookie.com") || h.equals("google.com") || h.endsWith(".google.com");
                return !ok;                           // links that leave YouTube are simply not followed
            }
        });

        Matcher m = ID.matcher(embedUrl == null ? "" : embedUrl);
        if (!m.find()) return w;
        String src = "https://www.youtube.com/embed/" + m.group(1) + "?autoplay=" + (autoplay ? "1" : "0") + "&playsinline=1&rel=0&fs=1";
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'/></head>"
            + "<body style='margin:0;background:#000'>"
            + "<iframe style='position:absolute;top:0;left:0;width:100%;height:100%;border:0' src='" + src + "' "
            + "allow='autoplay; encrypted-media; picture-in-picture; fullscreen' allowfullscreen></iframe>"
            + "</body></html>";
        w.loadDataWithBaseURL(Store.EMBED_ORIGIN, html, "text/html", "utf-8", null);
        return w;
    }

    /** Leaves the player's fullscreen (back button / screen closing). Returns true when it was in fullscreen. */
    static boolean exitFullscreen(WebView w) {
        if (w != null && w.getTag() instanceof Chrome) return ((Chrome) w.getTag()).hide();
        return false;
    }

    /** Lets the YouTube player's own fullscreen button work inside the app's window. */
    private static final class Chrome extends WebChromeClient {
        private final Activity act;
        private View custom;
        private CustomViewCallback cb;
        private int prevOrientation, prevUi;

        Chrome(Activity a) { act = a; }

        @Override
        public void onShowCustomView(View v, CustomViewCallback c) {
            if (custom != null) { c.onCustomViewHidden(); return; }
            custom = v;
            cb = c;
            FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
            v.setBackgroundColor(Color.BLACK);
            decor.addView(v, new FrameLayout.LayoutParams(-1, -1));
            prevOrientation = act.getRequestedOrientation();
            act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            prevUi = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        @Override
        public void onHideCustomView() { hide(); }

        boolean hide() {
            if (custom == null) return false;
            FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
            decor.removeView(custom);
            decor.setSystemUiVisibility(prevUi);
            act.setRequestedOrientation(prevOrientation);
            custom = null;
            try { if (cb != null) cb.onCustomViewHidden(); } catch (Exception ignored) { }
            cb = null;
            return true;
        }
    }
}
