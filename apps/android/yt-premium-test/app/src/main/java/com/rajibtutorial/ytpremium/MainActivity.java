package com.rajibtutorial.ytpremium;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * YouTube inside a WebView, behaving like an app:
 *  - ad blocking (blocked ad hosts + in-page script that removes ad slots and skips video ads)
 *  - background playback (page is told it is always visible, WebView is never paused, foreground media service keeps it alive)
 *  - Save button -> downloads the current video with the same engine as the course section (see YtSaver)
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://m.youtube.com/";
    private static final String UA = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    private static final String[] AD_HOSTS = {
        "googlesyndication.com", "doubleclick.net", "googleadservices.com", "adservice.google.com",
        "imasdk.googleapis.com", "googletagservices.com", "googletagmanager.com", "pagead2.googlesyndication.com",
        "2mdn.net", "moatads.com"
    };

    /** Runs before the page's own scripts (and again after load; guarded so it only installs once). */
    private static final String PAGE_JS =
        "(function(){if(window.__ytp)return;window.__ytp=1;"
        // background playback: the page must never believe it is hidden
        + "try{var d=function(o,k,v){Object.defineProperty(o,k,{get:function(){return v},configurable:true})};"
        + "d(document,'hidden',false);d(document,'visibilityState','visible');"
        + "d(document,'webkitHidden',false);d(document,'webkitVisibilityState','visible');}catch(e){}"
        + "['visibilitychange','webkitvisibilitychange','pagehide','blur'].forEach(function(n){"
        + "document.addEventListener(n,function(e){e.stopImmediatePropagation()},true);"
        + "window.addEventListener(n,function(e){e.stopImmediatePropagation()},true);});"
        // ad slots
        + "var css=document.createElement('style');"
        + "css.textContent='.video-ads,#player-ads,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,"
        + "ytm-companion-slot,ytm-ad-slot-renderer,ytd-ad-slot-renderer,ytm-brand-video-singleton-renderer,"
        + "ytm-statement-banner-renderer,ytm-mealbar-promo-renderer,.ytp-ad-overlay-container,.ytp-ad-text-overlay,"
        + "ytm-paid-content-overlay-renderer{display:none!important}';"
        + "(document.head||document.documentElement).appendChild(css);"
        // video ads: skip button, otherwise mute + fast-forward to the end of the ad
        + "var muted=false;setInterval(function(){try{"
        + "var v=document.querySelector('video'),p=document.querySelector('.html5-video-player');"
        + "var sk=document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button,.ytp-ad-skip-button-modern,.ytp-ad-overlay-close-button');"
        + "if(sk)sk.click();"
        + "var ad=(p&&p.classList.contains('ad-showing'))||document.querySelector('.ad-showing');"
        + "if(ad&&v){muted=true;v.muted=true;v.playbackRate=16;"
        + "if(isFinite(v.duration)&&v.duration>0)v.currentTime=v.duration;}"
        + "else if(muted&&v){muted=false;v.muted=false;v.playbackRate=1;}"
        + "}catch(e){}},250);})();";

    private WebView web;
    private FrameLayout root, fsBox;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private WebChromeClient chrome;
    private TextView saveBtn;
    private String curId;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        fsBox = new FrameLayout(this);
        fsBox.setBackgroundColor(Color.BLACK);
        fsBox.setVisibility(View.GONE);
        root.addView(fsBox, new FrameLayout.LayoutParams(-1, -1));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(UA);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                Set<String> origins = new HashSet<>(Arrays.asList("https://m.youtube.com", "https://www.youtube.com"));
                WebViewCompat.addDocumentStartJavaScript(web, PAGE_JS, origins);
            } catch (Throwable ignored) { }
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost(), path = u.getPath();
                boolean block = false;
                if (host != null) for (String h : AD_HOSTS) if (host.endsWith(h)) { block = true; break; }
                if (!block && path != null && (path.startsWith("/pagead/") || path.startsWith("/api/stats/ads"))) block = true;
                if (block) return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                // never jump out to the YouTube app / Play Store
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                v.evaluateJavascript(PAGE_JS, null);
                refreshSave(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(PAGE_JS, null);
                refreshSave(url);
            }

            // YouTube is a single-page app: this fires on every in-page navigation (video -> video)
            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                refreshSave(url);
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCb = cb;
                web.setVisibility(View.GONE);
                fsBox.setVisibility(View.VISIBLE);
                fsBox.addView(view);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                setButtonsVisible(false);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fsBox.removeView(customView);
                fsBox.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
                if (customCb != null) customCb.onCustomViewHidden();
                customView = null;
                customCb = null;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                setButtonsVisible(true);
            }
        };
        web.setWebChromeClient(chrome);

        buildButtons();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        String start = HOME;
        Intent in = getIntent();
        if (in != null && in.getData() != null && in.getData().toString().startsWith("http")) start = in.getData().toString();
        web.loadUrl(start);
    }

    // ── floating buttons ────────────────────────────────────────────────────

    private LinearLayout bar;

    private TextView pill(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(22));
        t.setBackground(g);
        return t;
    }

    private void buildButtons() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.END);

        saveBtn = pill("⬇ Save", 0xEEFF0000);
        saveBtn.setVisibility(View.GONE);
        saveBtn.setOnClickListener(v -> onSaveClicked());
        TextView saved = pill("📁 Saved", 0xEE222222);
        saved.setOnClickListener(v -> startActivity(new Intent(this, SavedActivity.class)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(8);
        bar.addView(saveBtn, lp);
        bar.addView(saved, lp);

        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, dp(12), dp(84));
        root.addView(bar, fp);
    }

    private void setButtonsVisible(boolean v) { if (bar != null) bar.setVisibility(v ? View.VISIBLE : View.GONE); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ── save ────────────────────────────────────────────────────────────────

    static String videoId(String url) {
        if (url == null) return null;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost() == null ? "" : u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();
            String id = null;
            if (host.contains("youtu.be")) id = path.length() > 1 ? path.substring(1) : null;
            else if (path.startsWith("/shorts/")) id = path.substring(8);
            else if (path.equals("/watch")) id = u.getQueryParameter("v");
            if (id == null) return null;
            id = id.replaceAll("[^A-Za-z0-9_\\-].*$", "");
            return id.length() >= 6 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshSave(String url) {
        String id = videoId(url);
        curId = id;
        runOnUiThread(() -> updateSaveLabel());
    }

    private void updateSaveLabel() {
        if (saveBtn == null) return;
        if (curId == null) { saveBtn.setVisibility(View.GONE); return; }
        if (customView == null) saveBtn.setVisibility(View.VISIBLE);
        if (YtSaver.isDownloading(curId)) {
            int p = YtSaver.progress(curId);
            saveBtn.setText(p <= 0 ? "⏳ Preparing… (tap to cancel)" : "⏳ Saving " + p + "% (tap to cancel)");
        } else if (YtSaver.find(this, curId) != null) {
            saveBtn.setText("✓ Saved");
        } else {
            saveBtn.setText("⬇ Save");
        }
    }

    private void onSaveClicked() {
        final String id = curId;
        if (id == null) return;
        if (YtSaver.isDownloading(id)) { YtSaver.cancel(id); return; }
        if (YtSaver.find(this, id) != null) {
            startActivity(new Intent(this, SavedActivity.class));
            return;
        }
        String title = web.getTitle();
        if (title != null) title = title.replaceAll("\\s*-\\s*YouTube\\s*$", "");
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        YtSaver.start(this, id, title, (vid, pct) -> {
            if (pct == 100) Toast.makeText(this, "Saved ✓ — see 📁 Saved", Toast.LENGTH_SHORT).show();
            else if (pct == -1) Toast.makeText(this, YtSaver.lastError(vid), Toast.LENGTH_LONG).show();
            if (vid.equals(curId)) updateSaveLabel();
        });
        // progress ticks come through the same listener
        updateSaveLabel();
    }

    // ── lifecycle (background audio: never pause the WebView) ───────────────

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
        KeepAliveService.start(this);
        updateSaveLabel();
    }

    @Override protected void onPause() {
        super.onPause();
        web.onResume();             // keep the page "running" while the screen is off / another app is on top
    }

    @Override protected void onStop() {
        super.onStop();
        web.onResume();
        web.resumeTimers();
    }

    @Override public void onBackPressed() {
        if (customView != null) chrome.onHideCustomView();
        else if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);   // keep playing in the background instead of killing the video
    }

    @Override protected void onDestroy() {
        KeepAliveService.stop(this);
        web.stopLoading();
        web.destroy();
        super.onDestroy();
    }
}
