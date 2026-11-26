package com.rajibtutorial;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Safe Browser (the exe's Safe Browser, on Android): tabs, private tabs, history, bookmarks, downloads,
 * delete browsing data, find in page, desktop site, save page as PDF, share — with Adult Block + forced SafeSearch.
 * Browser extensions are not possible in an Android WebView, so that part of the exe is not included.
 */
public class SafeBrowserActivity extends AppCompatActivity {
    private static final String HOME = "https://www.google.com/";
    private static final String DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int REQ_FILE = 4418;

    private static final class Tab {
        WebView web;
        String title = "New tab", url = "";
        boolean priv;
        boolean focus;          // this tab turned into the calm Focus page (adult search / site)
        View focusView;
    }

    private final List<Tab> tabs = new ArrayList<>();
    private Tab cur;
    private LinearLayout tabRow;
    private FrameLayout stage;
    private EditText omni;
    private ProgressBar bar;
    private LinearLayout findBar;
    private EditText findIn;
    private TextView btnBack, btnFwd, btnReload;
    private boolean desktop = false;
    private String defaultUa;
    private ValueCallback<Uri[]> fileCb;
    private SharedPreferences sp;

    // ── build ───────────────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (AppSettings.screenProtect(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        sp = getSharedPreferences("rt_safebrowser", MODE_PRIVATE);
        defaultUa = WebSettings.getDefaultUserAgent(this);
        CookieManager.getInstance().setAcceptCookie(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // tab strip
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFFDEE1E6);
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), 0);
        hs.addView(tabRow, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 38)));
        root.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // toolbar: back, forward, reload, address, menu
        LinearLayout tool = new LinearLayout(this);
        tool.setOrientation(LinearLayout.HORIZONTAL);
        tool.setGravity(Gravity.CENTER_VERTICAL);
        tool.setBackgroundColor(Ui.BG);
        tool.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        btnBack = icon("◀", v -> { if (cur != null && cur.web.canGoBack()) cur.web.goBack(); });
        btnFwd = icon("▶", v -> { if (cur != null && cur.web.canGoForward()) cur.web.goForward(); });
        btnReload = icon("⟳", v -> { if (cur == null) return; if (cur.web.getProgress() < 100) cur.web.stopLoading(); else cur.web.reload(); });
        tool.addView(btnBack); tool.addView(btnFwd); tool.addView(btnReload);

        omni = new EditText(this);
        omni.setSingleLine(true);
        omni.setTextSize(14);
        omni.setHint("Search or type URL");
        omni.setTextColor(Ui.TEXT);
        omni.setHintTextColor(0xFF8A8F96);
        omni.setBackground(Ui.bg(this, 0xFFF1F3F4, 20, 0));
        omni.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        omni.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        omni.setImeOptions(EditorInfo.IME_ACTION_GO);
        omni.setSelectAllOnFocus(true);
        omni.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_GO || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigate(omni.getText().toString());
                hideKeyboard();
                return true;
            }
            return false;
        });
        tool.addView(omni, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));
        tool.addView(icon("⋮", this::showMenu));
        root.addView(tool, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.INVISIBLE);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));

        // find-in-page bar (hidden until used)
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(0xFFF1F3F4);
        findBar.setVisibility(View.GONE);
        findIn = new EditText(this);
        findIn.setSingleLine(true);
        findIn.setTextSize(14);
        findIn.setHint("Find in page");
        findIn.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { if (cur != null) cur.web.findAllAsync(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        findBar.addView(findIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        findBar.addView(icon("▲", v -> { if (cur != null) cur.web.findNext(false); }));
        findBar.addView(icon("▼", v -> { if (cur != null) cur.web.findNext(true); }));
        findBar.addView(icon("✕", v -> closeFind()));
        root.addView(findBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String start = getIntent().getStringExtra("url");
        newTab(start == null || start.isEmpty() ? HOME : start, false);
    }

    private TextView icon(String glyph, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(17);
        t.setTextColor(Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 40));
        t.setMinHeight(Ui.dp(this, 40));
        t.setOnClickListener(l);
        return t;
    }

    // ── tabs ────────────────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private Tab newTab(String url, boolean priv) {
        final Tab t = new Tab();
        t.priv = priv;
        t.web = makeWeb(t);
        tabs.add(t);
        select(t);
        if (url != null && !url.isEmpty()) navigate(url);
        return t;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView makeWeb(final Tab t) {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        if (t.priv) { s.setCacheMode(WebSettings.LOAD_NO_CACHE); s.setSaveFormData(false); }
        applyUa(w);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setDownloadListener((url, ua, cd, mime, len) -> startDownload(url, ua, cd, mime));

        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (!AppSettings.adultBlock(SafeBrowserActivity.this)) return null;
                try {
                    Uri u = r.getUrl();
                    boolean main = r.isForMainFrame();
                    boolean bad = main ? SafeGuard.isBlocked(u.toString()) : SafeGuard.isAdultHost(u.getHost());
                    if (!bad) return null;
                    if (main) {
                        runOnUiThread(() -> toFocus(t));
                        return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream("<html><body style='background:#0B1F24'></body></html>".getBytes("UTF-8")));
                    }
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                String sc = r.getUrl().getScheme();
                if (sc == null) return true;
                if (!(sc.equals("http") || sc.equals("https"))) return !(sc.equals("about") || sc.equals("data") || sc.equals("blob"));
                if (r.isForMainFrame() && AppSettings.adultBlock(SafeBrowserActivity.this)) {
                    if (SafeGuard.isBlocked(url)) { toFocus(t); return true; }
                    String safe = SafeGuard.safeSearchUrl(url);
                    if (!safe.isEmpty()) { v.loadUrl(safe); return true; }
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap fav) {
                if (t.focus) return;
                t.url = url == null ? "" : url;
                if (t == cur) { omni.setText(t.url); bar.setVisibility(View.VISIBLE); btnReload.setText("✕"); }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (t.focus) return;
                t.url = url == null ? "" : url;
                if (t == cur) { omni.setText(t.url); bar.setVisibility(View.INVISIBLE); btnReload.setText("⟳"); updateNav(); }
                if (!t.priv && url != null && url.startsWith("http")) addHistory(t.title, url);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) { if (t == cur) bar.setProgress(p); }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                if (t.focus) return;
                t.title = title == null || title.isEmpty() ? "New tab" : title;
                rebuildTabs();
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (focusLocked()) { toastRemaining(); return false; }
                Tab nt = new Tab();
                nt.priv = t.priv;
                nt.web = makeWeb(nt);
                tabs.add(nt);
                select(nt);
                WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                tr.setWebView(nt.web);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView v) { closeTab(t); }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p != null && p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(i, REQ_FILE); }
                catch (Exception e) { fileCb.onReceiveValue(null); fileCb = null; }
                return true;
            }
        });
        return w;
    }

    private void applyUa(WebView w) {
        WebSettings s = w.getSettings();
        s.setUserAgentString(desktop ? DESKTOP_UA : defaultUa);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
    }

    private void select(Tab t) {
        Tab ft = focusTab();
        if (focusLocked() && ft != null && t != ft && cur == ft) { toastRemaining(); return; }   // Focus running: stay on the focus tab
        cur = t;
        stage.removeAllViews();
        View body = t.focus && t.focusView != null ? t.focusView : t.web;
        if (body.getParent() != null) ((ViewGroup) body.getParent()).removeView(body);
        stage.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        omni.setText(t.focus ? "" : t.url);
        bar.setVisibility(View.INVISIBLE);
        btnReload.setText("⟳");
        updateNav();
        rebuildTabs();
    }

    private void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        if (t.focus && focusLocked()) { toastRemaining(); return; }
        tabs.remove(i);
        if (t.web != null) {
            if (t.web.getParent() != null) ((ViewGroup) t.web.getParent()).removeView(t.web);
            t.web.destroy();
        }
        if (tabs.isEmpty()) { finish(); return; }
        if (cur == t) select(tabs.get(Math.min(i, tabs.size() - 1))); else rebuildTabs();
    }

    private void rebuildTabs() {
        tabRow.removeAllViews();
        for (final Tab t : tabs) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(Ui.bg(this, t == cur ? Ui.BG : 0xFFEEF0F3, 10, 0));
            chip.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
            TextView title = new TextView(this);
            title.setText((t.priv ? "🕶 " : "") + t.title);
            title.setTextSize(12.5f);
            title.setTextColor(Ui.TEXT);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setMaxWidth(Ui.dp(this, 120));
            chip.addView(title);
            TextView x = new TextView(this);
            x.setText("✕");
            x.setTextSize(12);
            x.setTextColor(Ui.SUB);
            x.setGravity(Gravity.CENTER);
            x.setMinWidth(Ui.dp(this, 30));
            x.setMinHeight(Ui.dp(this, 30));
            x.setOnClickListener(v -> closeTab(t));
            chip.addView(x);
            chip.setOnClickListener(v -> select(t));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 32));
            lp.setMargins(0, 0, Ui.dp(this, 4), 0);
            tabRow.addView(chip, lp);
        }
        TextView plus = icon("＋", v -> openTab(false));
        plus.setMinWidth(Ui.dp(this, 34));
        plus.setMinHeight(Ui.dp(this, 32));
        tabRow.addView(plus);
    }

    private void updateNav() {
        if (cur == null) return;
        btnBack.setAlpha(cur.web.canGoBack() ? 1f : 0.35f);
        btnFwd.setAlpha(cur.web.canGoForward() ? 1f : 0.35f);
    }

    // ── navigation ──────────────────────────────────────────────────────────────────────────────

    private void navigate(String input) {
        if (cur == null) return;
        String in = input == null ? "" : input.trim();
        if (in.isEmpty()) return;
        if (cur.focus && focusLocked()) { toastRemaining(); return; }
        String url;
        if (in.matches("(?i)^https?://\\S+$")) url = in;
        else if (in.contains(" ") || !in.contains(".")) {
            try { url = "https://www.google.com/search?q=" + URLEncoder.encode(in, "UTF-8"); } catch (Exception e) { return; }
        } else url = "https://" + in;
        if (AppSettings.adultBlock(this)) {
            if (SafeGuard.isBlocked(url)) { toFocus(cur); return; }
            String safe = SafeGuard.safeSearchUrl(url);
            if (!safe.isEmpty()) url = safe;
        }
        if (cur.focus) { cur.focus = false; cur.title = "New tab"; select(cur); }   // leaving the focus page (no Focus running)
        cur.web.loadUrl(url);
    }

    private static String blockPage() {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0;background:#111;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'>"
            + "<div style='padding:24px'><div style='font-size:46px'>🔞</div>"
            + "<h2>এই সাইটটি বন্ধ আছে</h2>"
            + "<p style='color:#bbb;line-height:1.6'>Adult Block চালু থাকায় এই পাতা খোলা যাবে না।<br>মন দিয়ে পড়াশোনায় ফোকাস করুন 🎯</p></div></body></html>";
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(omni.getWindowToken(), 0);
        omni.clearFocus();
    }


    // ── Focus tab (adult search → the tab turns into a calm Focus page, like the exe) ──────────

    private static final String[][] QUOTES = {
        {"ওঠো, জাগো এবং লক্ষ্যে না পৌঁছানো পর্যন্ত থেমো না।", "স্বামী বিবেকানন্দ"},
        {"স্বপ্ন সেটা নয় যা তুমি ঘুমিয়ে দেখো, স্বপ্ন সেটাই যা তোমাকে ঘুমাতে দেয় না।", "এ. পি. জে. আবদুল কালাম"},
        {"এক সময়ে একটি কাজ করো, আর তা করার সময় নিজের সমস্ত মন তাতে ঢেলে দাও।", "স্বামী বিবেকানন্দ"},
        {"চিত্ত যেথা ভয়শূন্য, উচ্চ যেথা শির।", "রবীন্দ্রনাথ ঠাকুর"},
        {"বল বীর — বল উন্নত মম শির!", "কাজী নজরুল ইসলাম"},
        {"আমরা যা বারবার করি, আমরা তা-ই। তাই শ্রেষ্ঠত্ব কোনো একটি কাজ নয়, এটি একটি অভ্যাস।", "অ্যারিস্টটল"},
        {"তুমি তোমার মনের ওপর ক্ষমতা রাখো, বাইরের ঘটনার ওপর নয়। এটা বুঝলেই তুমি শক্তি পাবে।", "মার্কাস অরেলিয়াস"},
        {"বাস্তবের চেয়ে কল্পনাতেই আমরা বেশি কষ্ট পাই।", "সেনেকা"},
        {"কাজটা শেষ না হওয়া পর্যন্ত সবসময় অসম্ভবই মনে হয়।", "নেলসন ম্যান্ডেলা"},
        {"প্রকৃত শক্তিশালী সে নয় যে কুস্তিতে অন্যকে হারায়; বরং সে-ই শক্তিশালী যে রাগের সময় নিজেকে নিয়ন্ত্রণ করে।", "হাদিস (বুখারি ও মুসলিম)"}
    };

    private Tab focusTab() {
        for (Tab x : tabs) if (x.focus) return x;
        return null;
    }

    /** Focus is running AND this browser has its focus tab: no other tab can be opened / switched to / closed. */
    private boolean focusLocked() {
        return focusTab() != null && new FocusManager(this).isSessionActive();
    }

    private void toastRemaining() {
        long ms = new FocusManager(this).getRemainingMs();
        long min = (ms + 59999) / 60000;
        Toast.makeText(this, "🎯 Focus চলছে — " + min + " মিনিট বাকি। এখন অন্য ট্যাব খোলা যাবে না।", Toast.LENGTH_SHORT).show();
    }

    private void openTab(boolean priv) {
        if (focusLocked()) { toastRemaining(); return; }
        newTab(HOME, priv);
    }

    /** The tab fades into the focus page (UI thread). Typing never gets here — only a submitted search / opened site. */
    private void toFocus(final Tab t) {
        if (t == null || t.focus || tabs.indexOf(t) < 0) return;
        try { t.web.stopLoading(); t.web.loadUrl("about:blank"); } catch (Exception ignored) { }
        t.focus = true;
        t.title = "Focus time";
        t.url = "";
        if (t.focusView == null) t.focusView = buildFocusView(t);
        if (t == cur) select(t); else rebuildTabs();
        hideKeyboard();
    }

    private View buildFocusView(final Tab t) {
        final FocusManager fm = new FocusManager(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0B1F24);
        root.setPadding(Ui.dp(this, 26), Ui.dp(this, 20), Ui.dp(this, 26), Ui.dp(this, 20));

        TextView title = Ui.tv(this, "একটু থামুন 🌿", 24, 0xFFFFFFFF, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = Ui.tv(this, "আপনি যা খুঁজছিলেন তা আপনার লক্ষ্য থেকে মনোযোগ সরিয়ে দিতে পারে। এই মুহূর্তটা আপনার ভবিষ্যৎ গড়ার সময় — চলুন আবার পড়াশোনায় ফিরি।",
            13.5f, 0xFFB8C7CB, false);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(0, 1.25f);
        root.addView(sub, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 16));

        final int[] qi = {(int) (Math.random() * QUOTES.length)};
        final TextView quote = Ui.tv(this, "", 16, 0xFFFFFFFF, false);
        quote.setGravity(Gravity.CENTER);
        quote.setLineSpacing(0, 1.25f);
        final TextView who = Ui.tv(this, "", 12.5f, 0xFF7FE0A0, true);
        who.setGravity(Gravity.CENTER);
        root.addView(quote, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(who, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 18));
        final Runnable showQuote = () -> {
            quote.setText("“" + QUOTES[qi[0]][0] + "”");
            who.setText("— " + QUOTES[qi[0]][1]);
        };
        showQuote.run();

        final TextView timer = Ui.tv(this, "25:00", 46, 0xFFFFFFFF, true);
        timer.setGravity(Gravity.CENTER);
        root.addView(timer, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));

        final TextView start = Ui.btn(this, "▶  ২৫ মিনিট Focus শুরু করুন", Ui.B_PRIMARY, v -> {
            FocusManager.quickStart(SafeBrowserActivity.this);
            Toast.makeText(this, "Focus শুরু হয়েছে — ২৫ মিনিট", Toast.LENGTH_SHORT).show();
        });
        root.addView(start, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));
        root.addView(Ui.btn(this, "💬 আরেকটি উক্তি", Ui.B_GHOST, v -> { qi[0] = (qi[0] + 1) % QUOTES.length; showQuote.run(); }),
            Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));
        root.addView(Ui.btn(this, "📚 পড়াশোনায় ফিরুন", Ui.B_GHOST, v -> {
            if (focusLocked()) { toastRemaining(); return; }
            if (tabs.size() > 1) closeTab(t); else { t.focus = false; t.title = "New tab"; select(t); t.web.loadUrl(HOME); }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // live clock: the remaining time of the running Focus (or 25:00 when none runs)
        final boolean[] wasOn = {fm.isSessionActive()};
        final int[] tick = {0};
        root.post(new Runnable() {
            @Override public void run() {
                if (!t.focus || tabs.indexOf(t) < 0) return;
                boolean on = fm.isSessionActive();
                long ms = on ? fm.getRemainingMs() : FocusManager.QUICK_FOCUS_MS;
                long sec = (ms + 999) / 1000;
                timer.setText(String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60));
                start.setVisibility(on ? View.GONE : View.VISIBLE);
                if (wasOn[0] && !on) Toast.makeText(SafeBrowserActivity.this, "সাবাশ! ২৫ মিনিটের ফোকাস শেষ। এবার ৫ মিনিট বিরতি নিন।", Toast.LENGTH_LONG).show();
                wasOn[0] = on;
                if (++tick[0] % 12 == 0) { qi[0] = (qi[0] + 1) % QUOTES.length; showQuote.run(); }
                root.postDelayed(this, 1000);
            }
        });
        return root;
    }

    // ── menu ────────────────────────────────────────────────────────────────────────────────────

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "New tab");
        m.add(0, 2, 1, "New private tab");
        m.add(0, 3, 2, "History");
        m.add(0, 4, 3, "Downloads");
        m.add(0, 5, 4, "Bookmarks");
        m.add(0, 6, 5, "Bookmark this page");
        m.add(0, 7, 6, "Desktop site").setCheckable(true).setChecked(desktop);
        m.add(0, 8, 7, "Find in page");
        m.add(0, 9, 8, "Save page as PDF…");
        m.add(0, 10, 9, "Copy link");
        m.add(0, 11, 10, "Share link");
        m.add(0, 12, 11, "Translate to Bangla");
        m.add(0, 13, 12, "Delete browsing data…");
        m.add(0, 14, 13, "Adult Block").setCheckable(true).setChecked(AppSettings.adultBlock(this));
        m.add(0, 15, 14, "About Safe Browser");
        m.add(0, 16, 15, "Exit");
        pm.setOnMenuItemClickListener(this::onMenu);
        pm.show();
    }

    private boolean onMenu(MenuItem it) {
        switch (it.getItemId()) {
            case 1: openTab(false); break;
            case 2: openTab(true); break;
            case 3: showHistory(); break;
            case 4: showDownloads(); break;
            case 5: showBookmarks(); break;
            case 6: addBookmark(); break;
            case 7:
                desktop = !desktop;
                for (Tab t : tabs) applyUa(t.web);
                if (cur != null) cur.web.reload();
                break;
            case 8:
                findBar.setVisibility(View.VISIBLE);
                findIn.requestFocus();
                InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(findIn, 0);
                break;
            case 9: savePage(); break;
            case 10: copyLink(); break;
            case 11: shareLink(); break;
            case 12:
                if (cur != null && cur.url.startsWith("http")) {
                    try { cur.web.loadUrl("https://translate.google.com/translate?sl=auto&tl=bn&u=" + URLEncoder.encode(cur.url, "UTF-8")); }
                    catch (Exception ignored) { }
                }
                break;
            case 13: deleteData(); break;
            case 14:
                AppSettings.set(this, AppSettings.ADULT_BLOCK, !AppSettings.adultBlock(this));
                Toast.makeText(this, "Adult Block " + (AppSettings.adultBlock(this) ? "চালু" : "বন্ধ"), Toast.LENGTH_SHORT).show();
                break;
            case 15:
                Toast.makeText(this, "Rajib Tutorial Safe Browser — Adult Block " + (AppSettings.adultBlock(this) ? "চালু" : "বন্ধ")
                    + ", SafeSearch সক্রিয়। Private tab-এ history সেভ হয় না।", Toast.LENGTH_LONG).show();
                break;
            case 16: finish(); break;
            default: return false;
        }
        return true;
    }

    private void closeFind() {
        findBar.setVisibility(View.GONE);
        findIn.setText("");
        if (cur != null) cur.web.clearMatches();
    }

    private void copyLink() {
        if (cur == null || !cur.url.startsWith("http")) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", cur.url));
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
    }

    private void shareLink() {
        if (cur == null || !cur.url.startsWith("http")) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, cur.url);
        startActivity(Intent.createChooser(i, "Share link"));
    }

    private void savePage() {
        if (cur == null) return;
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String name = (cur.title == null || cur.title.isEmpty() ? "page" : cur.title).replaceAll("[^A-Za-z0-9_\\- ]", "_");
            pm.print(name, cur.web.createPrintDocumentAdapter(name), new PrintAttributes.Builder().build());
        } catch (Exception e) {
            Toast.makeText(this, "Save করা যায়নি", Toast.LENGTH_SHORT).show();
        }
    }

    // ── history / bookmarks / downloads (small JSON lists in prefs) ──────────────────────────────

    private JSONArray load(String key) {
        try { return new JSONArray(sp.getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void save(String key, JSONArray a) { sp.edit().putString(key, a.toString()).apply(); }

    private void addHistory(String title, String url) {
        try {
            JSONArray a = load("history");
            if (a.length() > 0 && url.equals(a.getJSONObject(a.length() - 1).optString("u"))) return;
            a.put(new JSONObject().put("t", title == null ? url : title).put("u", url).put("at", System.currentTimeMillis()));
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, a.length() - 200); i < a.length(); i++) out.put(a.get(i));
            save("history", out);
        } catch (Exception ignored) { }
    }

    private void listDialog(String title, final JSONArray a, final boolean showTime, final String clearKey) {
        if (a.length() == 0) { Toast.makeText(this, "কিছু নেই", Toast.LENGTH_SHORT).show(); return; }
        final List<String> labels = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("t", o.optString("u"));
            if (showTime && o.optLong("at") > 0)
                t += "\n" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(o.optLong("at")));
            labels.add(t);
            urls.add(o.optString("u"));
        }
        new AlertDialog.Builder(this).setTitle(title)
            .setItems(labels.toArray(new String[0]), (d, w) -> { if (cur != null) navigate(urls.get(w)); })
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear", (d, w) -> { save(clearKey, new JSONArray()); Toast.makeText(this, "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show(); })
            .show();
    }

    private void showHistory() { listDialog("History", load("history"), true, "history"); }

    private void showBookmarks() { listDialog("Bookmarks", load("bookmarks"), false, "bookmarks"); }

    private void addBookmark() {
        if (cur == null || !cur.url.startsWith("http")) return;
        try {
            JSONArray a = load("bookmarks");
            for (int i = 0; i < a.length(); i++) if (cur.url.equals(a.getJSONObject(i).optString("u"))) { Toast.makeText(this, "আগেই Bookmark করা আছে", Toast.LENGTH_SHORT).show(); return; }
            a.put(new JSONObject().put("t", cur.title).put("u", cur.url));
            save("bookmarks", a);
            Toast.makeText(this, "Bookmark করা হয়েছে", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    private void startDownload(String url, String ua, String cd, String mime) {
        try {
            String name = URLUtil.guessFileName(url, cd, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mime);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            else r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(r);
            JSONArray a = load("downloads");
            a.put(new JSONObject().put("id", id).put("t", name).put("at", System.currentTimeMillis()));
            save("downloads", a);
            Toast.makeText(this, "Downloading: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download শুরু করা যায়নি", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDownloads() {
        final JSONArray a = load("downloads");
        if (a.length() == 0) { Toast.makeText(this, "কোনো download নেই", Toast.LENGTH_SHORT).show(); return; }
        final DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        final List<String> labels = new ArrayList<>();
        final List<Long> ids = new ArrayList<>();
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            long id = o.optLong("id");
            String status = "—";
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (c != null && c.moveToFirst()) {
                    int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    status = st == DownloadManager.STATUS_SUCCESSFUL ? "✅ Done" : st == DownloadManager.STATUS_FAILED ? "❌ Failed"
                        : st == DownloadManager.STATUS_PAUSED ? "⏸ Paused" : "⏳ Downloading";
                }
            } catch (Exception ignored) { }
            labels.add(o.optString("t") + "\n" + status);
            ids.add(id);
        }
        new AlertDialog.Builder(this).setTitle("Downloads")
            .setItems(labels.toArray(new String[0]), (d, w) -> {
                try {
                    Uri u = dm.getUriForDownloadedFile(ids.get(w));
                    if (u == null) { Toast.makeText(this, "এখনো শেষ হয়নি", Toast.LENGTH_SHORT).show(); return; }
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(u, dm.getMimeTypeForDownloadedFile(ids.get(w)));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception e) { Toast.makeText(this, "ফাইল খোলা যায়নি", Toast.LENGTH_SHORT).show(); }
            })
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear list", (d, w) -> save("downloads", new JSONArray()))
            .show();
    }

    private void deleteData() {
        final String[] items = {"Browsing history", "Cookies & site data (সব site থেকে logout হবে)", "Cached images & files"};
        final boolean[] on = {true, false, true};
        new AlertDialog.Builder(this).setTitle("Delete browsing data")
            .setMultiChoiceItems(items, on, (d, i, c) -> on[i] = c)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("Delete", (d, w) -> {
                if (on[0]) save("history", new JSONArray());
                if (on[1]) { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData(); }
                if (on[2]) for (Tab t : tabs) t.web.clearCache(true);
                Toast.makeText(this, "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show();
            }).show();
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE || fileCb == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) out = new Uri[]{data.getData()};
        }
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    @Override
    public void onBackPressed() {
        if (findBar.getVisibility() == View.VISIBLE) { closeFind(); return; }
        if (cur != null && cur.focus && focusLocked()) { toastRemaining(); return; }
        if (cur != null && !cur.focus && cur.web.canGoBack()) { cur.web.goBack(); return; }
        if (tabs.size() > 1) { closeTab(cur); return; }
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (Tab t : tabs) t.web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Tab t : tabs) t.web.onResume();
        if (cur != null && focusTab() == null && new FocusManager(this).isSessionActive()) toFocus(cur);
    }

    @Override
    protected void onDestroy() {
        if (fileCb != null) { fileCb.onReceiveValue(null); fileCb = null; }
        for (Tab t : new ArrayList<>(tabs)) {
            try {
                if (t.web.getParent() != null) ((ViewGroup) t.web.getParent()).removeView(t.web);
                t.web.destroy();
            } catch (Exception ignored) { }
        }
        tabs.clear();
        super.onDestroy();
    }
}
