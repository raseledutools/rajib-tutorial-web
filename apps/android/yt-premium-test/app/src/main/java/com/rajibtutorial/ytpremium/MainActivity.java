package com.rajibtutorial.ytpremium;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
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
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * YouTube inside a WebView, behaving like a native app:
 *  - ad blocking, "Open app" banners hidden, Save button next to Subscribe (assets/ytp.js)
 *  - Back button / swipe down on a playing video  -> the video shrinks into a mini player (native ExoPlayer) while you keep browsing
 *  - Home button  -> floating window over other apps;  screen lock / background -> audio keeps playing (native ExoPlayer + foreground service)
 *  - sidebar with Home, Saved videos, Apps update
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://m.youtube.com/";
    private static final String[] AD_HOSTS = {
        "googlesyndication.com", "doubleclick.net", "googleadservices.com", "adservice.google.com",
        "imasdk.googleapis.com", "googletagservices.com", "2mdn.net"
    };

    static volatile boolean ALIVE;
    private static WeakReference<MainActivity> LIVE;

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
    private TextView updItem, floatItem, battItem;

    private LinearLayout mini;
    private TextureView miniTv;
    private TextView miniTitle, miniPlay;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // what the web player reports (ytp.js -> YTP.info)
    private volatile String webId = null;
    private volatile String webTitle = "";
    private volatile double webPos = 0;
    private volatile boolean webPlaying = false;
    private volatile long webInfoAt = 0, lastPlayAt = 0, handoffAt = 0;
    private volatile String prefetched = "";

    private boolean leaving, stopped, resumeToWeb, slideIn, qualityBusy;

    /** Play/Pause button of the background-playback notification. */
    static void togglePlayback() {
        if (NativePlayer.active()) { new Handler(Looper.getMainLooper()).post(NativePlayer::toggle); return; }
        final MainActivity a = LIVE == null ? null : LIVE.get();
        if (a == null) return;
        a.runOnUiThread(() -> { if (a.web != null) a.web.evaluateJavascript("window.__ytpToggle&&window.__ytpToggle()", null); });
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ALIVE = true;
        LIVE = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Notifs.init(this);
        pageJs = readAsset("ytp.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        crashReporter();
        web = new SwipeWebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        fsBox = new SwipeFrame(this);
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
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                if (bar != null) bar.setVisibility(View.GONE);
                if (mini != null) mini.setVisibility(View.GONE);
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
                if (bar != null) bar.setVisibility(View.VISIBLE);
                if (NativePlayer.active() && !resumeToWeb) showMini();
            }
        };
        web.setWebChromeClient(chrome);

        buildButtons();
        buildMini();
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
        public void openSaved() {
            runOnUiThread(() -> { if (trusted()) startActivity(new Intent(MainActivity.this, SavedActivity.class)); });
        }

        /** every ~0.7 s from the page: which video, where it is, is it playing */
        @JavascriptInterface
        public void info(String id, String title, double pos, boolean p) {
            boolean ok = idOk(id);
            long now = System.currentTimeMillis();
            webId = ok ? id : null;
            webTitle = title == null ? "" : title;
            webPos = pos;
            webPlaying = ok && p;
            webInfoAt = now;
            if (webPlaying) lastPlayAt = now;
            if (ok && p && !id.equals(prefetched)) {
                prefetched = id;
                NativePlayer.prefetch(id);          // get the stream links ready so a hand-over is instant
                runOnUiThread(MainActivity.this::askOverlayOnce);
            }
            // a different video started on the web page: the mini player's video is obsolete
            if (ok && p && NativePlayer.active() && !id.equals(NativePlayer.id()) && now - handoffAt > 3000) {
                runOnUiThread(() -> {
                    if (stopped || !NativePlayer.active() || webId == null || webId.equals(NativePlayer.id())) return;
                    NativePlayer.stop();
                    resumeToWeb = false;
                    hideMini();
                });
            }
        }

        /** swipe down on the player */
        @JavascriptInterface
        public void minimize() {
            runOnUiThread(() -> { if (trusted() && onWatchPage() && webActive() && customView == null) minimize(); });
        }
    }

    private void doSave(final String id, String title) {
        if (YtSaver.isDownloading(id)) {
            YtSaver.cancel(id);
            Toast.makeText(this, "Cancelling…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (YtSaver.find(this, id) != null) {
            startActivity(new Intent(this, SavedActivity.class));
            return;
        }
        if (qualityBusy) return;
        final String ttl = title == null ? "" : title.replaceAll("\\s*-\\s*YouTube\\s*$", "");
        qualityBusy = true;
        Toast.makeText(this, "Checking available qualities…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final List<YtResolver.Opt> opts = YtResolver.options("https://www.youtube.com/watch?v=" + id);
                runOnUiThread(() -> { qualityBusy = false; if (!isFinishing()) showQualityDialog(id, ttl, opts); });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    qualityBusy = false;
                    Toast.makeText(this, "Could not get qualities: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static String fmtSize(long b) {
        if (b <= 0) return "size unknown";
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    private void showQualityDialog(final String id, final String ttl, final List<YtResolver.Opt> opts) {
        String[] items = new String[opts.size()];
        for (int i = 0; i < items.length; i++) items[i] = opts.get(i).label + "     •     " + fmtSize(opts.get(i).bytes);
        new AlertDialog.Builder(this)
            .setTitle("Save — choose quality")
            .setItems(items, (d, which) -> {
                YtResolver.Opt o = opts.get(which);
                Toast.makeText(this, "Saving " + o.label + "…", Toast.LENGTH_SHORT).show();
                YtSaver.start(this, id, ttl, o.res, (vid, pct) -> {
                    if (pct == 100) Toast.makeText(this, "Saved ✓ — open the sidebar ☰ → Saved videos", Toast.LENGTH_SHORT).show();
                    else if (pct == -1) Toast.makeText(this, YtSaver.lastError(vid), Toast.LENGTH_LONG).show();
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── web player <-> native player ────────────────────────────────────────

    private boolean onWatchPage() {
        String u = web.getUrl();
        return u != null && u.contains("/watch");
    }

    private boolean webActive() {
        long now = System.currentTimeMillis();
        return webId != null && (webPlaying || now - lastPlayAt < 2000) && now - webInfoAt < 6000;
    }

    private long basePos() {
        return (long) (webPos * 1000) + (webPlaying ? Math.max(0, System.currentTimeMillis() - webInfoAt) : 0);
    }

    private static String cleanTitle(String t) {
        return t == null ? "" : t.replaceAll("\\s*-\\s*YouTube\\s*$", "");
    }

    private static String watchUrl(String id, long posMs) {
        long sec = posMs / 1000;
        return HOME + "watch?v=" + id + (sec > 2 ? "&t=" + sec + "s" : "");
    }

    private void pauseWeb(String id) {
        if (id != null && webId != null && !id.equals(webId)) return;   // the web page already moved on
        web.evaluateJavascript("(function(){window.__ytpUserPause=true;var v=document.querySelector('video');if(v)v.pause();})()", null);
    }

    /** The web video continues in the native player. Nothing changes if the stream cannot be resolved (the web video simply keeps playing). */
    private void handoff(final boolean showMini, final boolean backToWeb) {
        final String id = webId;
        if (id == null) return;
        final String ttl = cleanTitle(webTitle);
        final long p0 = basePos(), t0 = System.currentTimeMillis();
        handoffAt = t0;
        NativePlayer.start(this, id, ttl, () -> p0 + (System.currentTimeMillis() - t0), (ok, err) -> {
            if (!ok) {
                if (showMini && !isFinishing()) {            // the page was already left: bring the video back
                    web.loadUrl(watchUrl(id, p0 + (System.currentTimeMillis() - t0)));
                    Toast.makeText(this, "Mini player unavailable: " + err, Toast.LENGTH_LONG).show();
                }
                return;
            }
            handoffAt = System.currentTimeMillis();
            pauseWeb(id);
            resumeToWeb = backToWeb;
            if (stopped) { if (leaving) showFloat(); }
            else if (showMini) showMini();
        });
    }

    /** Back button / swipe down while a video plays: native mini player, and the page goes back to where you were. */
    private void minimize() {
        try {
            ghostAnim();             // the video drops down into the mini player
            slideIn = true;
            handoff(true, false);
            if (web.canGoBack()) web.goBack(); else web.loadUrl(HOME);
        } catch (Exception e) {
            Toast.makeText(this, "Minimize failed: " + e, Toast.LENGTH_LONG).show();
        }
    }

    /** a black frame the size of the player shrinks and slides down to the mini player's place */
    private void ghostAnim() {
        final int w = web.getWidth();
        final int h = (int) Math.min(web.getHeight() * 0.6f, w * 9f / 16f);
        if (w <= 0 || h <= 0) return;
        final View g = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF000000);
        gd.setCornerRadius(dp(8));
        g.setBackground(gd);
        root.addView(g, new FrameLayout.LayoutParams(w, h));
        g.setPivotX(0);
        g.setPivotY(0);
        g.animate()
            .scaleX(dp(107) / (float) w).scaleY(dp(60) / (float) h)
            .translationY(root.getHeight() - dp(60))
            .alpha(0.25f)
            .setDuration(280)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .withEndAction(() -> root.removeView(g))
            .start();
    }

    /** Swipe down on the playing video (finger starts inside the player) = same as Back. Handled natively and consumed, so neither YouTube nor the system sees it. */
    private final class SwipeWebView extends WebView {
        private float sx, sy;
        private boolean armed, fired;
        SwipeWebView(Context c) { super(c); }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    fired = false;
                    sx = e.getX();
                    sy = e.getY();
                    armed = customView == null && onWatchPage() && webActive() && sy < getWidth() * 9f / 16f + dp(8);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (fired) return true;
                    if (armed) {
                        float dy = e.getY() - sy, dx = e.getX() - sx;
                        if (dy > dp(70) && Math.abs(dx) < dy * 0.6f) {
                            fired = true;
                            MotionEvent c = MotionEvent.obtain(e);
                            c.setAction(MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(c);
                            c.recycle();
                            ui.post(() -> minimize());
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    armed = false;
                    if (fired) { fired = false; return true; }
                    break;
                default: break;
            }
            return super.dispatchTouchEvent(e);
        }
    }

    /** If the app ever closes by itself, the reason is shown the next time it opens. */
    private void crashReporter() {
        final File f = new File(getFilesDir(), "crash.txt");
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(sw.toString().getBytes("UTF-8")); }
            } catch (Exception ignored) { }
            if (old != null) old.uncaughtException(t, e);
        });
        if (!f.exists()) return;
        String txt = "";
        try (java.util.Scanner sc = new java.util.Scanner(f, "UTF-8")) { txt = sc.useDelimiter("\\A").hasNext() ? sc.next() : ""; } catch (Exception ignored) { }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (txt.isEmpty()) return;
        final String full = txt;
        final String shown = txt.length() > 1500 ? txt.substring(0, 1500) + "…" : txt;
        ui.post(() -> new AlertDialog.Builder(this)
            .setTitle("The app closed last time")
            .setMessage(shown)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy", (d, w) -> {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("crash", full));
            })
            .show());
    }

    /** mini player or floating window tapped: back to the full YouTube page of that video */
    private void expand() {
        String id = NativePlayer.id();
        long pos = NativePlayer.posMs();
        NativePlayer.stop();
        resumeToWeb = false;
        hideMini();
        FloatingWindow.hide();
        if (id != null) web.loadUrl(watchUrl(id, pos));
    }

    /** the app came back to the front after a lock / background hand-over: carry on in the web player, same second */
    private void returnToWeb() {
        String id = NativePlayer.id();
        long pos = NativePlayer.posMs();
        boolean playing = NativePlayer.isPlaying();
        NativePlayer.stop();
        resumeToWeb = false;
        hideMini();
        if (id == null) return;
        if (id.equals(webId)) {
            web.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return;try{v.currentTime=" + (pos / 1000.0) + ";}catch(e){}"
                + (playing ? "window.__ytpUserPause=false;var p=v.play();if(p&&p.catch)p.catch(function(){});" : "") + "})()", null);
        } else {
            web.loadUrl(watchUrl(id, pos));
        }
    }

    private void showFloat() {
        if (!NativePlayer.active() || !FloatingWindow.can(this)) return;
        hideMini();
        FloatingWindow.show(getApplicationContext());
    }

    // ── mini player bar (bottom, like the native YouTube app) ───────────────

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void buildMini() {
        mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.HORIZONTAL);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setBackgroundColor(0xF2212121);
        mini.setVisibility(View.GONE);

        miniTv = new TextureView(this);
        mini.addView(miniTv, new LinearLayout.LayoutParams(dp(107), dp(60)));

        miniTitle = new TextView(this);
        miniTitle.setTextColor(Color.WHITE);
        miniTitle.setTextSize(13);
        miniTitle.setMaxLines(2);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniTitle.setPadding(dp(10), 0, dp(6), 0);
        mini.addView(miniTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        miniPlay = new TextView(this);
        miniPlay.setText("⏸");
        miniPlay.setTextColor(Color.WHITE);
        miniPlay.setTextSize(20);
        miniPlay.setGravity(Gravity.CENTER);
        miniPlay.setOnClickListener(v -> { NativePlayer.toggle(); miniPlay.setText(NativePlayer.isPlaying() ? "⏸" : "▶"); });
        mini.addView(miniPlay, new LinearLayout.LayoutParams(dp(48), dp(60)));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(18);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> { NativePlayer.stop(); resumeToWeb = false; hideMini(); });
        mini.addView(close, new LinearLayout.LayoutParams(dp(48), dp(60)));

        mini.setOnClickListener(v -> expand());
        root.addView(mini, new FrameLayout.LayoutParams(-1, dp(60), Gravity.BOTTOM));
    }

    private final Runnable miniTick = new Runnable() {
        @Override public void run() {
            if (mini == null || mini.getVisibility() != View.VISIBLE) return;
            if (!NativePlayer.active()) { hideMini(); return; }
            miniPlay.setText(NativePlayer.isPlaying() ? "⏸" : "▶");
            ui.postDelayed(this, 500);
        }
    };

    private void layoutForMini(boolean on) {
        FrameLayout.LayoutParams wp = (FrameLayout.LayoutParams) web.getLayoutParams();
        wp.bottomMargin = on ? dp(60) : 0;
        web.setLayoutParams(wp);
        if (bar != null) {
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            bp.bottomMargin = dp(96) + (on ? dp(60) : 0);
            bar.setLayoutParams(bp);
        }
    }

    private void showMini() {
        if (mini == null || !NativePlayer.active() || customView != null) return;
        miniTitle.setText(NativePlayer.title());
        mini.setVisibility(View.VISIBLE);
        layoutForMini(true);
        if (slideIn) {
            slideIn = false;
            mini.setTranslationY(dp(60));
            mini.animate().translationY(0).setDuration(260).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        } else {
            mini.setTranslationY(0);
        }
        if (NativePlayer.player() != null) NativePlayer.player().setVideoTextureView(miniTv);
        ui.removeCallbacks(miniTick);
        ui.post(miniTick);
    }

    private void hideMini() {
        if (mini == null) return;
        ui.removeCallbacks(miniTick);
        if (NativePlayer.player() != null) NativePlayer.player().clearVideoTextureView(miniTv);
        mini.setVisibility(View.GONE);
        layoutForMini(false);
    }

    /** swipe down on the fullscreen video = same as Back: leave fullscreen and shrink into the mini player */
    private final class SwipeFrame extends FrameLayout {
        private float sx, sy;
        private boolean fired;
        SwipeFrame(Context c) { super(c); }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: sx = e.getX(); sy = e.getY(); fired = false; break;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getY() - sy, dx = e.getX() - sx;
                    if (!fired && dy > dp(90) && Math.abs(dx) < dy * 0.6f) {
                        fired = true;
                        post(() -> {
                            chrome.onHideCustomView();
                            if (webActive()) minimize();
                        });
                        return true;
                    }
                    break;
                }
                default: break;
            }
            return false;
        }
    }

    // ── permissions the floating window / background play need ──────────────

    private void openOverlaySettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings → Apps → this app → Display over other apps", Toast.LENGTH_LONG).show();
        }
    }

    private boolean batteryFree() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    private void askOverlayOnce() {
        if (isFinishing() || FloatingWindow.can(this)) return;
        SharedPreferences sp = getSharedPreferences("ytp", MODE_PRIVATE);
        if (sp.getBoolean("askedOverlay", false)) return;
        sp.edit().putBoolean("askedOverlay", true).apply();
        new AlertDialog.Builder(this)
            .setTitle("Floating window")
            .setMessage("To keep the video in a floating window when you press Home, allow “Display over other apps” for this app.")
            .setPositiveButton("Allow", (d, w) -> openOverlaySettings())
            .setNegativeButton("Not now", null)
            .show();
    }

    // ── buttons + sidebar ───────────────────────────────────────────────────

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

    private TextView drawerItem(String text, View.OnClickListener l, boolean closeAfter) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(20), 0, dp(20), 0);
        t.setOnClickListener(v -> { if (closeAfter) closeDrawer(); l.onClick(v); });
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

        drawerItem("🏠   Home", v -> web.loadUrl(HOME), true);
        drawerItem("📁   Saved videos", v -> startActivity(new Intent(this, SavedActivity.class)), true);
        updItem = drawerItem("⟳   Apps update", v -> checkUpdate(true), true);
        floatItem = drawerItem("🪟   Floating window", v -> {
            if (FloatingWindow.can(this)) Toast.makeText(this, "Floating window is ON — press Home while a video plays", Toast.LENGTH_LONG).show();
            else openOverlaySettings();
        }, true);
        battItem = drawerItem("🔋   Background play fix", v -> {
            if (batteryFree()) Toast.makeText(this, "Already allowed to run in the background", Toast.LENGTH_SHORT).show();
            else openBatterySettings();
        }, true);

        int w = Math.min(dp(290), (int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        drawer.addView(panel, new FrameLayout.LayoutParams(w, -1, Gravity.START));
    }

    private void openDrawer() {
        if (drawer == null || drawer.getVisibility() == View.VISIBLE) return;
        floatItem.setText(FloatingWindow.can(this) ? "🪟   Floating window: ON" : "🪟   Floating window: tap to allow");
        battItem.setText(batteryFree() ? "🔋   Background play: OK" : "🔋   Background play fix: tap to allow");
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
                    Toast.makeText(MainActivity.this, "Allow “Install unknown apps” for this app, then open ☰ → Apps update again", Toast.LENGTH_LONG).show();
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

    // ── lifecycle ───────────────────────────────────────────────────────────

    private void setBg(boolean on) {
        if (web != null) web.evaluateJavascript("window.__ytpBg=" + on, null);
    }

    @Override protected void onResume() {
        super.onResume();
        stopped = false;
        leaving = false;
        setBg(false);
        web.onResume();
        web.resumeTimers();
        KeepAliveService.start(this);
        FloatingWindow.hide();
        if (NativePlayer.expandRequested) {
            NativePlayer.expandRequested = false;
            if (NativePlayer.active()) { if (resumeToWeb) returnToWeb(); else expand(); }
        } else if (NativePlayer.active()) {
            if (resumeToWeb) returnToWeb(); else showMini();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        setBg(true);                 // fallback: a pause YouTube does by itself while hidden is undone
        web.onResume();              // keep the page "running" while the screen is off / another app is on top
        web.resumeTimers();
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        leaving = true;              // Home / recents (not the power button): show the floating window
    }

    @Override protected void onStop() {
        super.onStop();
        stopped = true;
        setBg(true);
        web.onResume();
        web.resumeTimers();
        if (NativePlayer.active()) {
            if (leaving) showFloat();
        } else if (webActive()) {
            handoff(false, true);    // audio (and the floating window after Home) now comes from the native player
        }
    }

    @Override public void onBackPressed() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (customView != null) chrome.onHideCustomView();
        else if (onWatchPage() && webActive()) minimize();
        else if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);   // keep playing in the background instead of killing the video
    }

    @Override protected void onDestroy() {
        ALIVE = false;
        ui.removeCallbacks(miniTick);
        if (!NativePlayer.active()) { FloatingWindow.hide(); KeepAliveService.stop(this); }
        else hideMini();
        if (dlDialog != null && dlDialog.isShowing()) dlDialog.dismiss();
        web.stopLoading();
        web.destroy();
        super.onDestroy();
    }
}
