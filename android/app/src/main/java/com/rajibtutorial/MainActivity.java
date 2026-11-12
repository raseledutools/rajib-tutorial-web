package com.rajibtutorial;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.animation.ObjectAnimator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private static final String SITE_URL = "https://raseledutools.github.io/rajib-tutorial-web/";

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private UpdateChecker updateChecker;

    // Full-screen video support
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout fullscreenContainer;

    // Double-back-to-exit
    private boolean backPressedOnce = false;
    private final Handler backHandler = new Handler();

    // Update banner
    private LinearLayout updateBanner;
    private String       pendingApkUrl = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // FLAG_SECURE — screenshot ও screen record block
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        );

        setContentView(R.layout.activity_main);

        progressBar         = findViewById(R.id.progressBar);
        swipeRefresh        = findViewById(R.id.swipeRefresh);
        webView             = findViewById(R.id.webView);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        updateBanner        = findViewById(R.id.updateBanner);

        // ── Update checker + in-app banner ──
        updateChecker = new UpdateChecker(this);
        updateChecker.setUpdateListener((version, apkUrl) -> {
            pendingApkUrl = apkUrl;
            showUpdateBanner(version, apkUrl);
        });
        updateChecker.checkForUpdate();

        // ── WebView settings ──
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // ── Pull-to-refresh conflict fix ──
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            swipeRefresh.setEnabled(scrollY == 0);
        });

        // ── WebViewClient ──
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                swipeRefresh.setEnabled(true);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // ── Custom scheme: fullscreen rotation bridge ──
                if (url.equals("rtapp://fullscreen")) {
                    enterFullscreen();
                    return true;
                }
                if (url.equals("rtapp://exitfullscreen")) {
                    exitFullscreen();
                    return true;
                }

                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                if (url.startsWith("https://raseledutools.github.io")) {
                    return false;
                }
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }
        });

        // ── WebChromeClient — full-screen video support ──
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;

                // Hide normal content, show fullscreen container
                swipeRefresh.setVisibility(View.GONE);
                fullscreenContainer.setVisibility(View.VISIBLE);
                fullscreenContainer.addView(view);

                // Go landscape + true fullscreen
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;

                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                swipeRefresh.setVisibility(View.VISIBLE);

                customViewCallback.onCustomViewHidden();
                customView = null;
                customViewCallback = null;

                // Restore portrait + normal UI
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        });

        // ── Swipe to refresh ──
        swipeRefresh.setColorSchemeResources(android.R.color.holo_blue_bright);
        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        // ── Load site ──
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(SITE_URL);
        }
    }

    // ── In-app update banner ──────────────────────────────────────────────
    private void showUpdateBanner(String version, String apkUrl) {
        if (updateBanner == null) return;

        // Version text: "v7" → "1.7 আপডেট"
        String vNum  = version.replace("v", "");
        String vName = "1." + vNum;
        android.widget.TextView tv = updateBanner.findViewById(R.id.updateBannerText);
        if (tv != null) tv.setText("v" + vName + " আপডেট");

        // Slide-in animation
        updateBanner.setVisibility(View.VISIBLE);
        updateBanner.setTranslationX(600f);
        updateBanner.animate()
            .translationX(0f)
            .setDuration(400)
            .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
            .start();

        // Pulsing dot animation
        View dot = updateBanner.findViewById(R.id.updateDot);
        if (dot != null) {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f, 1f);
            pulse.setDuration(1200);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.start();
        }

        // Click → start download immediately
        updateBanner.setOnClickListener(v -> {
            // Banner slide out
            updateBanner.animate()
                .translationX(600f)
                .setDuration(250)
                .withEndAction(() -> updateBanner.setVisibility(View.GONE))
                .start();

            // Download শুরু
            android.content.Intent intent = new android.content.Intent(this,
                UpdateChecker.DownloadReceiver.class);
            intent.putExtra("apk_url", apkUrl);
            intent.putExtra("version", version);
            sendBroadcast(intent);

            Toast.makeText(this, "আপডেট download হচ্ছে…", Toast.LENGTH_SHORT).show();
        });
    }

    private void enterFullscreen() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private void exitFullscreen() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    public void onBackPressed() {
        // 1. Full-screen video mode → exit fullscreen
        if (customView != null) {
            webView.getWebChromeClient().onHideCustomView();
            return;
        }
        // 2. JS overlay fullscreen (landscape) → exit fullscreen
        if (getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            exitFullscreen();
            webView.evaluateJavascript("closeFsOverlay()", null);
            return;
        }
        // 3. WebView এ history আছে (SPA pushState থেকে) → back navigate
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // 4. Home page এ আছি → double-back-to-exit
        if (backPressedOnce) {
            backHandler.removeCallbacksAndMessages(null);
            super.onBackPressed();  // app close
            return;
        }
        backPressedOnce = true;
        Toast.makeText(this, "আবার Back চাপলে বন্ধ হবে", Toast.LENGTH_SHORT).show();
        // ২ সেকেন্ড পর reset
        backHandler.postDelayed(() -> backPressedOnce = false, 2000);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
