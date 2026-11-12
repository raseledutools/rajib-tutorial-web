package com.rajibtutorial;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private static final String SITE_URL = "https://raseledutools.github.io/rajib-tutorial-web/";

    // Views
    private LinearLayout  homeScreen;
    private LinearLayout  btnWebSection;
    private LinearLayout  btnNativeSection;
    private TextView      versionText;

    private SwipeRefreshLayout swipeRefresh;
    private WebView            webView;
    private ProgressBar        progressBar;
    private FrameLayout        fullscreenContainer;

    // Update banner
    private LinearLayout updateBanner;

    // Download overlay
    private LinearLayout downloadOverlay;
    private ProgressBar  downloadProgressBar;
    private TextView     downloadPercent;
    private TextView     downloadStatus;
    private TextView     btnInstallNow;
    private java.io.File pendingApkFile = null;

    // Full-screen video
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    // Double-back-to-exit
    private boolean backPressedOnce = false;
    private final Handler backHandler = new Handler();

    // Update
    private UpdateChecker updateChecker;
    private String        pendingApkUrl  = "";
    private String        pendingVersion = "";
    private boolean       isWebSectionOpen = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_main);

        // ── View references ──
        homeScreen          = findViewById(R.id.homeScreen);
        btnWebSection       = findViewById(R.id.btnWebSection);
        btnNativeSection    = findViewById(R.id.btnNativeSection);
        versionText         = findViewById(R.id.versionText);
        swipeRefresh        = findViewById(R.id.swipeRefresh);
        webView             = findViewById(R.id.webView);
        progressBar         = findViewById(R.id.progressBar);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        updateBanner        = findViewById(R.id.updateBanner);
        downloadOverlay     = findViewById(R.id.downloadOverlay);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        downloadPercent     = findViewById(R.id.downloadPercent);
        downloadStatus      = findViewById(R.id.downloadStatus);
        btnInstallNow       = findViewById(R.id.btnInstallNow);

        btnInstallNow.setOnClickListener(v -> {
            if (pendingApkFile != null) triggerInstallFromActivity(pendingApkFile);
        });

        // ── Version info ──
        String vName = "v1." + BuildConfig.VERSION_CODE;
        versionText.setText(vName);

        // ── Home screen button clicks ──
        btnWebSection.setOnClickListener(v -> openWebSection());

        btnNativeSection.setOnClickListener(v ->
            Toast.makeText(this, "শীঘ্রই আসছে! 🚀", Toast.LENGTH_SHORT).show());

        findViewById(R.id.btnFocusMode).setOnClickListener(v ->
            startActivity(new Intent(this, FocusActivity.class)));

        // ── WebView setup ──
        setupWebView();

        // ── Update checker ──
        updateChecker = new UpdateChecker(this);
        updateChecker.setUpdateListener((version, apkUrl) -> {
            pendingVersion = version;
            pendingApkUrl  = apkUrl;
            showUpdateBanner(version, apkUrl);
        });
        updateChecker.setProgressListener(new UpdateChecker.DownloadProgressListener() {
            @Override
            public void onProgress(int percent, long downloaded, long total) {
                runOnUiThread(() -> {
                    downloadOverlay.setVisibility(View.VISIBLE);
                    btnInstallNow.setVisibility(View.GONE);
                    downloadProgressBar.setProgress(percent);
                    downloadPercent.setText(percent + "%");
                    if (total > 0) {
                        downloadStatus.setText(formatBytes(downloaded) + " / " + formatBytes(total));
                    } else {
                        downloadStatus.setText("download হচ্ছে…");
                    }
                });
            }

            @Override
            public void onReadyToInstall(java.io.File apkFile) {
                runOnUiThread(() -> {
                    // Progress bar 100% দেখাও
                    downloadProgressBar.setProgress(100);
                    downloadPercent.setText("100%");
                    downloadStatus.setText("✅ Download সম্পন্ন");
                    // Install button দেখাও
                    pendingApkFile = apkFile;
                    btnInstallNow.setVisibility(View.VISIBLE);
                    // Auto-trigger install
                    triggerInstallFromActivity(apkFile);
                });
            }

            @Override
            public void onError(String reason) {
                runOnUiThread(() -> {
                    downloadStatus.setText("❌ সমস্যা হয়েছে, আবার চেষ্টা করুন");
                    btnInstallNow.setVisibility(View.GONE);
                    new Handler().postDelayed(() ->
                        downloadOverlay.setVisibility(View.GONE), 3000);
                });
            }
        });
        updateChecker.checkForUpdate();
    }

    // ── Open web section ─────────────────────────────────────────────────
    private void openWebSection() {
        isWebSectionOpen = true;
        homeScreen.setVisibility(View.GONE);
        swipeRefresh.setVisibility(View.VISIBLE);

        // WebView এখনো load না হলে load করো
        if (webView.getUrl() == null) {
            webView.loadUrl(SITE_URL);
        }
    }

    // ── WebView setup ────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setOnScrollChangeListener((v, sx, sy, osx, osy) ->
            swipeRefresh.setEnabled(sy == 0));

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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.equals("rtapp://fullscreen"))     { enterFullscreen(); return true; }
                if (url.equals("rtapp://exitfullscreen")) { exitFullscreen();  return true; }
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                if (url.startsWith("https://raseledutools.github.io")) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progressBar.setProgress(p);
                if (p == 100) progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = cb;
                swipeRefresh.setVisibility(View.GONE);
                fullscreenContainer.setVisibility(View.VISIBLE);
                fullscreenContainer.addView(view);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                swipeRefresh.setVisibility(View.VISIBLE);
                customViewCallback.onCustomViewHidden();
                customView = null; customViewCallback = null;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        });

        swipeRefresh.setColorSchemeResources(android.R.color.holo_red_light);
        swipeRefresh.setOnRefreshListener(() -> webView.reload());
    }

    // ── Update banner ─────────────────────────────────────────────────────
    private void showUpdateBanner(String version, String apkUrl) {
        if (updateBanner == null) return;

        String vNum  = version.replace("v", "");
        String vName = "1." + vNum;
        TextView tv  = updateBanner.findViewById(R.id.updateBannerText);
        if (tv != null) tv.setText("v" + vName + " আপডেট");

        updateBanner.setVisibility(View.VISIBLE);
        updateBanner.setTranslationX(600f);
        updateBanner.animate()
            .translationX(0f).setDuration(400)
            .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
            .start();

        View dot = updateBanner.findViewById(R.id.updateDot);
        if (dot != null) {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f, 1f);
            pulse.setDuration(1200);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.start();
        }

        updateBanner.setOnClickListener(v -> {
            // Banner সরিয়ে দাও
            updateBanner.animate()
                .translationX(600f).setDuration(250)
                .withEndAction(() -> updateBanner.setVisibility(View.GONE))
                .start();

            // Overlay reset করে দেখাও
            pendingApkFile = null;
            btnInstallNow.setVisibility(View.GONE);
            downloadOverlay.setVisibility(View.VISIBLE);
            downloadProgressBar.setProgress(0);
            downloadPercent.setText("0%");
            downloadStatus.setText("শুরু হচ্ছে…");

            updateChecker.startDownload(apkUrl, version);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private String formatBytes(long bytes) {
        if (bytes < 1024)             return bytes + " B";
        if (bytes < 1024 * 1024)      return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ── APK install — Activity context দিয়ে (crash হয় না) ───────────────
    private void triggerInstallFromActivity(java.io.File apkFile) {
        try {
            // Android 8+: "Install unknown apps" permission না থাকলে আগে সেটা চাইতে হবে
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this,
                    "Install করার অনুমতি দিন, তারপর ফিরে এসে \"এখনই Install করুন\" চাপুন",
                    Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
                return;
            }
            Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".provider", apkFile);
            Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception e) {
            Toast.makeText(this, "Install করতে পারছে না: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void enterFullscreen() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void exitFullscreen() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    // ── Back button ───────────────────────────────────────────────────────
    @Override
    public void onBackPressed() {
        // Download চলছে — cancel করবে না
        if (downloadOverlay.getVisibility() == View.VISIBLE) {
            Toast.makeText(this, "আপডেট download হচ্ছে…", Toast.LENGTH_SHORT).show();
            return;
        }
        // Full-screen video
        if (customView != null) {
            webView.getWebChromeClient().onHideCustomView();
            return;
        }
        // Landscape JS fullscreen
        if (getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            exitFullscreen();
            webView.evaluateJavascript("closeFsOverlay()", null);
            return;
        }
        // Web section খোলা আছে
        if (isWebSectionOpen) {
            if (webView.canGoBack()) {
                webView.goBack();
                return;
            }
            // Web section এর শুরুতে এসেছি → Home screen এ ফিরে যাও
            isWebSectionOpen = false;
            swipeRefresh.setVisibility(View.GONE);
            homeScreen.setVisibility(View.VISIBLE);
            return;
        }
        // Home screen এ → double-back-to-exit
        if (backPressedOnce) {
            backHandler.removeCallbacksAndMessages(null);
            super.onBackPressed();
            return;
        }
        backPressedOnce = true;
        Toast.makeText(this, "আবার Back চাপলে বন্ধ হবে", Toast.LENGTH_SHORT).show();
        backHandler.postDelayed(() -> backPressedOnce = false, 2000);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        webView.saveState(out);
    }
}
