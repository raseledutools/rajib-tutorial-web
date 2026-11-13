package com.rajibtutorial;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Ad-free YouTube WebView with background audio support.
 * YouTube lock screen-এও চলবে।
 */
public class YouTubeActivity extends AppCompatActivity {

    private static final String DEFAULT_URL = "https://m.youtube.com/";

    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
        "googlesyndication.com",
        "doubleclick.net",
        "googleadservices.com",
        "pagead2.googlesyndication.com",
        "static.doubleclick.net",
        "stats.g.doubleclick.net",
        "adservice.google.com",
        "imasdk.googleapis.com",
        "www.googletagmanager.com",
        "www.googletagservices.com",
        "partner.googleadservices.com"
    ));

    private static final String AD_JS =
        "(function(){" +
        "  function rm(){" +
        "    ['.video-ads','ytm-promoted-sparkles-web-renderer'," +
        "     'ytm-promoted-video-renderer','ytm-companion-slot'," +
        "     '.ytm-paid-content-overlay','ytm-ad-slot-renderer'," +
        "     '#player-ads','.ytp-ad-overlay-container'," +
        "     '.ytp-ad-text-overlay','.ytp-ad-player-overlay'," +
        "     '.ytp-ad-progress','ytd-ad-slot-renderer'" +
        "    ].forEach(function(s){" +
        "      document.querySelectorAll(s).forEach(function(e){e.remove();});" +
        "    });" +
        "    var sk=document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button');" +
        "    if(sk)sk.click();" +
        "  }" +
        "  rm();" +
        "  new MutationObserver(rm).observe(document.body,{childList:true,subtree:true});" +
        "})();";

    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private FrameLayout fsCont;
    private boolean uploadMode = false;
    private boolean browserMode = false;
    private ValueCallback<Uri[]> filePathCb;
    private final ActivityResultLauncher<Intent> fileLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (filePathCb == null) return;
            Uri[] res = WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData());
            filePathCb.onReceiveValue(res);
            filePathCb = null;
        });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        uploadMode = getIntent().getBooleanExtra("upload", false);
        browserMode = getIntent().getBooleanExtra("browser", false);
        FrameLayout root = new FrameLayout(this);
        setContentView(root);

        fsCont = new FrameLayout(this);
        fsCont.setBackgroundColor(0xFF000000);
        fsCont.setVisibility(View.GONE);
        root.addView(fsCont, new FrameLayout.LayoutParams(-1, -1));

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36");
        if (uploadMode) {
            // YouTube Studio upload needs the desktop site; desktop UA also helps Google sign-in
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setAllowFileAccess(true);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        if (browserMode) {
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setAllowFileAccess(true);
            CookieManager cmb = CookieManager.getInstance();
            cmb.setAcceptCookie(true);
            cmb.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String host = r.getUrl().getHost();
                if (host != null) {
                    for (String h : AD_HOSTS) {
                        if (host.endsWith(h))
                            return new WebResourceResponse("text/plain","utf-8",
                                new ByteArrayInputStream(new byte[0]));
                    }
                }
                return null;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                // never jump out to the YouTube/Studio app or Play Store
                return u.startsWith("intent:") || u.startsWith("market:") || u.startsWith("vnd.youtube");
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                if (!uploadMode && !browserMode) v.evaluateJavascript(AD_JS, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                try {
                    fileLauncher.launch(params.createIntent());
                } catch (Exception e) {
                    filePathCb = null;
                    return false;
                }
                return true;
            }
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view; customCb = cb;
                webView.setVisibility(View.GONE);
                fsCont.setVisibility(View.VISIBLE);
                fsCont.addView(view);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fsCont.removeView(customView);
                fsCont.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                customCb.onCustomViewHidden();
                customView = null; customCb = null;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
        });

        String url   = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        if (url == null || url.isEmpty()) url = DEFAULT_URL;
        if (title != null && getSupportActionBar() != null)
            getSupportActionBar().setTitle(title);

        if (uploadMode) {
            TextView ext = new TextView(this);
            ext.setText("🌐 Browser");
            ext.setTextColor(0xFFFFFFFF);
            ext.setTextSize(12);
            ext.setPadding(28, 14, 28, 14);
            ext.setBackgroundColor(0xCC000000);
            ext.setOnClickListener(x -> {
                String cur = webView.getUrl();
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(cur != null ? cur : "https://studio.youtube.com/"))); }
                catch (Exception e) { Toast.makeText(this, "Browser খোলা যায়নি", Toast.LENGTH_SHORT).show(); }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.setMargins(0, 24, 16, 0);
            root.addView(ext, lp);
        }

        webView.loadUrl(url);
    }

    @Override public void onBackPressed() {
        if (customView != null) webView.getWebChromeClient().onHideCustomView();
        else if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    // Background audio: onPause-এ webView.onPause() call করব না
    @Override protected void onPause()   { super.onPause(); }
    @Override protected void onResume()  { super.onResume(); webView.onResume(); }
    @Override protected void onDestroy() { webView.stopLoading(); webView.destroy(); super.onDestroy(); }
}
