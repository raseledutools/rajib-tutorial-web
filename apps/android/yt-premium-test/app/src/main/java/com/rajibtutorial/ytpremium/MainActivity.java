package com.rajibtutorial.ytpremium;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * YouTube inside a WebView, behaving like an app:
 *  - ad blocking (blocked ad hosts + in-page script, see assets/ytp.js)
 *  - background playback (page told it is always visible, WebView never paused, foreground media service)
 *  - Save / Saved buttons injected into the player by ytp.js (talk to the app through the YTP bridge)
 *  - update check on start + every ~15 min in the background, with notification and in-app download/install
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://m.youtube.com/";
    private static final String[] AD_HOSTS = {
        "googlesyndication.com", "doubleclick.net", "googleadservices.com", "adservice.google.com",
        "imasdk.googleapis.com", "googletagservices.com", "2mdn.net"
    };

    private WebView web;
    private FrameLayout root, fsBox;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private WebChromeClient chrome;
    private LinearLayout bar;
    private String pageJs = "";
    private AlertDialog dlDialog;
    private boolean updateShown;
    private FrameLayout drawer;
    private LinearLayout panel;
    private TextView updItem;
    private volatile boolean playing;          // reported by ytp.js: is a video playing right now
    private static WeakReference<MainActivity> LIVE;

    /** Play/Pause button of the background-playback notification. */
    static void togglePlayback() {
        final MainActivity a = LIVE == null ? null : LIVE.get();
        if (a == null) return;
        a.runOnUiThread(() -> { if (a.web != null) a.web.evaluateJavascript("window.__ytpToggle&&window.__ytpToggle()", null); });
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LIVE = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Notifs.init(this);
        pageJs = readAsset("ytp.js");

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
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // the WebView's real, current Chrome version, minus the "; wv" / "Version/x" markers that make YouTube treat it as a lesser browser
        s.setUserAgentString(WebSettings.getDefaultUserAgent(this).replace("; wv", "").replaceAll("\\s+Version/[0-9.]+", ""));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "YTP");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String host = r.getUrl().getHost();
                if (host != null) for (String h : AD_HOSTS) {
                    if (host.endsWith(h)) return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme();
                if (sc == null || !(sc.equals("https") || sc.equals("http"))) return true;   // no intent:/market: jumps
                String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                boolean ok = h.equals("youtube.com") || h.endsWith(".youtube.com") || h.equals("youtu.be")
                    || h.endsWith(".google.com") || h.equals("google.com") || h.endsWith(".gstatic.com");
                return !ok;                                    // links that leave YouTube are not followed
            }

            @Override public void onPageStarted(WebView v, String url, Bitmap icon) { inject(); }
            @Override public void onPageFinished(WebView v, String url) { inject(); }
            // YouTube is a single-page app: video -> video navigation only fires this
            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) { inject(); }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                // only main-frame errors reach this overload on Android 6+
                Toast.makeText(MainActivity.this, "Page error: " + desc, Toast.LENGTH_LONG).show();
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
                if (!inPip()) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                if (bar != null) bar.setVisibility(View.GONE);
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
                if (bar != null && !inPip()) bar.setVisibility(View.VISIBLE);
            }
        };
        web.setWebChromeClient(chrome);

        buildButtons();
        buildDrawer();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        UpdateWorker.schedule(this);
        checkUpdate(false);

        String start = HOME;
        Intent in = getIntent();
        if (in != null && in.getData() != null && String.valueOf(in.getData()).startsWith("https://")) start = in.getData().toString();
        web.loadUrl(start);
        handleIntent(in);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void inject() {
        if (!pageJs.isEmpty()) web.evaluateJavascript(pageJs, null);
    }

    // ── JS bridge (used only by the injected ytp.js) ────────────────────────

    private static boolean idOk(String id) { return id != null && id.matches("[A-Za-z0-9_-]{6,20}"); }

    private boolean trusted() {
        String u = web.getUrl();
        if (u == null) return false;
        try {
            Uri p = Uri.parse(u);
            String h = p.getHost() == null ? "" : p.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(p.getScheme()) && (h.equals("youtube.com") || h.endsWith(".youtube.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public String state(String id) {
            if (!idOk(id)) return "idle";
            if (YtSaver.isDownloading(id)) return "p:" + Math.max(0, YtSaver.progress(id));
            return YtSaver.find(MainActivity.this, id) != null ? "saved" : "idle";
        }

        @JavascriptInterface
        public void save(final String id, final String title) {
            runOnUiThread(() -> { if (trusted() && idOk(id)) doSave(id, title); });
        }

        @JavascriptInterface
        public void playing(boolean p) { playing = p; }

        @JavascriptInterface
        public void openSaved() {
            runOnUiThread(() -> { if (trusted()) startActivity(new Intent(MainActivity.this, SavedActivity.class)); });
        }
    }

    private void doSave(String id, String title) {
        if (YtSaver.isDownloading(id)) {
            YtSaver.cancel(id);
            Toast.makeText(this, "Cancelling…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (YtSaver.find(this, id) != null) {
            startActivity(new Intent(this, SavedActivity.class));
            return;
        }
        if (title != null) title = title.replaceAll("\\s*-\\s*YouTube\\s*$", "");
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        YtSaver.start(this, id, title, (vid, pct) -> {
            if (pct == 100) Toast.makeText(this, "Saved ✓ — open 📁", Toast.LENGTH_SHORT).show();
            else if (pct == -1) Toast.makeText(this, YtSaver.lastError(vid), Toast.LENGTH_LONG).show();
        });
    }

    // ── small native buttons: Saved + check for update (Save itself is injected into the player) ──

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView round(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xB3222222);
        t.setBackground(g);
        return t;
    }

    private void buildButtons() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);

        TextView menu = round("☰");
        menu.setOnClickListener(v -> openDrawer());
        bar.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));

        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, dp(8), dp(96));
        root.addView(bar, fp);
    }

    // ── sidebar: Home, Saved videos, Apps update ────────────────────────────

    private TextView drawerItem(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(20), 0, dp(20), 0);
        t.setOnClickListener(v -> { closeDrawer(); l.onClick(v); });
        panel.addView(t, new LinearLayout.LayoutParams(-1, dp(54)));
        return t;
    }

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0x99000000);
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        drawer.setOnClickListener(v -> closeDrawer());

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF1B1B1B);
        panel.setClickable(true);                       // taps on the panel must not close it

        TextView head = new TextView(this);
        head.setText("YouTube Premium Test\nv1." + Updater.installedCode(this));
        head.setTextColor(Color.WHITE);
        head.setTextSize(17);
        head.setPadding(dp(20), dp(44), dp(20), dp(20));
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

        drawerItem("🏠   Home", v -> web.loadUrl(HOME));
        drawerItem("📁   Saved videos", v -> startActivity(new Intent(this, SavedActivity.class)));
        updItem = drawerItem("⟳   Apps update", v -> checkUpdate(true));

        int w = Math.min(dp(290), (int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        drawer.addView(panel, new FrameLayout.LayoutParams(w, -1, Gravity.START));
    }

    private void openDrawer() {
        if (drawer == null || drawer.getVisibility() == View.VISIBLE) return;
        panel.setTranslationX(-dp(300));
        drawer.setVisibility(View.VISIBLE);
        panel.animate().translationX(0).setDuration(180).start();
    }

    private void closeDrawer() {
        if (drawer == null || drawer.getVisibility() != View.VISIBLE) return;
        panel.animate().translationX(-dp(300)).setDuration(150).withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    // ── updates ─────────────────────────────────────────────────────────────

    private void checkUpdate(final boolean manual) {
        new Thread(() -> {
            try {
                final Updater.Latest l = Updater.latest();
                final int mine = Updater.installedCode(this);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (updItem != null) updItem.setText(l.code > mine ? "⟳   Apps update  •  new 1." + l.code : "⟳   Apps update");
                    if (l.code > mine) {
                        if (manual || !updateShown) { updateShown = true; showUpdateDialog(l.versionName(), l.url); }
                    } else if (manual) {
                        Toast.makeText(this, "Up to date (1." + mine + ")", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> Toast.makeText(this, "Update check failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showUpdateDialog(final String ver, final String url) {
        new AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("YouTube Premium Test " + ver + " is ready.")
            .setPositiveButton("Update now", (d, w) -> startUpdate(url, ver))
            .setNegativeButton("Later", null)
            .show();
    }

    private void startUpdate(String url, final String ver) {
        if (dlDialog != null && dlDialog.isShowing()) return;
        dlDialog = new AlertDialog.Builder(this).setTitle("Updating to " + ver).setMessage("Downloading… 0%").setCancelable(false).create();
        dlDialog.show();
        Updater.download(this, url, new Updater.DlListener() {
            @Override public void onProgress(int p) { if (dlDialog != null) dlDialog.setMessage("Downloading… " + p + "%"); }
            @Override public void onReady(File apk) {
                if (dlDialog != null) dlDialog.dismiss();
                if (!Updater.install(MainActivity.this, apk)) {
                    Toast.makeText(MainActivity.this, "Allow “Install unknown apps” for this app, then tap ⟳ again", Toast.LENGTH_LONG).show();
                }
            }
            @Override public void onError(String why) {
                if (dlDialog != null) dlDialog.dismiss();
                Toast.makeText(MainActivity.this, "Update failed: " + why, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleIntent(Intent in) {
        if (in == null) return;
        String apk = in.getStringExtra(Notifs.EXTRA_APK_URL);
        if (apk != null && apk.startsWith("https://")) {
            String v = in.getStringExtra(Notifs.EXTRA_VERSION);
            in.removeExtra(Notifs.EXTRA_APK_URL);
            startUpdate(apk, v == null ? "" : v);
        }
    }

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        handleIntent(in);
    }

    // ── lifecycle (background audio: never pause the WebView) ───────────────

    private boolean inPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    private void setBg(boolean on) {
        if (web != null) web.evaluateJavascript("window.__ytpBg=" + on, null);
    }

    @Override protected void onResume() {
        super.onResume();
        setBg(false);
        web.onResume();
        web.resumeTimers();
        KeepAliveService.start(this);
    }

    @Override protected void onPause() {
        super.onPause();
        if (!inPip()) setBg(true);   // screen off / another app on top: a pause YouTube does by itself is undone
        web.onResume();              // keep the page "running" while the screen is off / another app is on top
        web.resumeTimers();
    }

    @Override protected void onStop() {
        super.onStop();
        if (!inPip()) setBg(true);
        web.onResume();
        web.resumeTimers();
    }

    // Home button / gesture while a video plays -> floating Picture-in-Picture window
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < 26 || !playing || isInPictureInPictureMode()) return;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
        } catch (Exception ignored) { }
    }

    @Override public void onPictureInPictureModeChanged(boolean in, Configuration cfg) {
        super.onPictureInPictureModeChanged(in, cfg);
        if (in) {
            setBg(false);                                  // PiP is visible: user pauses are real pauses
            if (drawer != null) drawer.setVisibility(View.GONE);
            if (bar != null) bar.setVisibility(View.GONE);
        } else if (bar != null && customView == null) {
            bar.setVisibility(View.VISIBLE);
        }
        web.evaluateJavascript("window.__ytpPip&&window.__ytpPip(" + in + ")", null);
    }

    @Override public void onBackPressed() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (customView != null) chrome.onHideCustomView();
        else if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);   // keep playing in the background instead of killing the video
    }

    @Override protected void onDestroy() {
        KeepAliveService.stop(this);
        if (dlDialog != null && dlDialog.isShowing()) dlDialog.dismiss();
        web.stopLoading();
        web.destroy();
        super.onDestroy();
    }
}
