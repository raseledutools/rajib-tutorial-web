package com.rajibtutorial;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.net.URLDecoder;
import java.util.Locale;

/**
 * Settings → "Adult Block — সব ব্রাউজার": watches the address bar / search box of other browsers
 * (Chrome, Edge, Firefox, Brave …). When adult words or an adult site show up, the page is left (Back)
 * and a full-screen Focus page covers the browser, from where a Focus session can be started — like the exe.
 * Only the address bar, the focused text box and the window title are looked at; nothing is stored or sent.
 */
public class AdultWatchService extends AccessibilityService {
    private static final long QUICK_FOCUS_MS = 25L * 60 * 1000;

    /** Known browsers: their address bar and any focused text box are checked. */
    private static final java.util.Set<String> BROWSERS = new java.util.HashSet<>(java.util.Arrays.asList(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.mozilla.firefox", "org.mozilla.firefox_beta",
        "org.mozilla.fenix", "org.mozilla.focus", "com.microsoft.emmx", "com.brave.browser", "com.opera.browser", "com.opera.mini.native",
        "com.opera.gx", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser",
        "com.UCMobile.intl", "com.uc.browser.en", "com.android.browser", "com.google.android.apps.chrome", "com.mi.globalbrowser",
        "com.miui.browser", "com.huawei.browser", "org.chromium.webview_shell", "com.yandex.browser", "com.ecosia.android"));
    /** Never looked at: our own app, system UI and the launcher / settings. */
    private static final java.util.Set<String> SKIP = new java.util.HashSet<>(java.util.Arrays.asList(
        "com.android.systemui", "com.android.settings", "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher",
        "com.miui.home", "com.android.launcher3"));

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    // typing is never judged; but adult words typed and then SUBMITTED (box no longer being typed in) count as a search.
    // (Chrome shows only "google.com" in the address bar after a search, so the typed words are remembered.)
    private boolean pendingAdult = false, scanTyping = false, scanAdult = false;
    private int enters = 0;               // how many times Enter was pressed for the focus link
    private long drivingSince = 0;
    private boolean driving = false;      // typing the focus link into the address bar right now
    private long lastAct = 0;
    private long newTabSince = 0, focusLoadedAt = 0;   // new-tab debounce / cooldown after the focus page was loaded
    private long lastScan = 0;
    private View overlay;
    private WindowManager wm;

    static boolean isEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String me = new ComponentName(c, AdultWatchService.class).flattenToString();
            String meShort = new ComponentName(c, AdultWatchService.class).flattenToShortString();
            for (String part : s.split(":")) if (part.equalsIgnoreCase(me) || part.equalsIgnoreCase(meShort)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        LocalFocusServer.start(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        final boolean focusOn = new FocusManager(this).isSessionActive();
        // Focus running = always enforced (even if the Adult Block switch is off); otherwise only when Adult Block is on
        if (!focusOn && !AppSettings.adultExternal(this)) return;
        LocalFocusServer.start(this);                                  // no-op when it already runs; restarts it if it died
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return;   // still typing: nothing is blocked before the search is submitted
        String pkg = e.getPackageName().toString();
        if (getPackageName().equals(pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (driving && now - drivingSince > 6000) driving = false;     // never stay stuck
        if (overlay != null || driving) return;
        if (now - lastAct < (focusOn ? 1200 : 2000)) return;
        final boolean browser = BROWSERS.contains(pkg);
        if (SKIP.contains(pkg)) return;
        if (focusOn && !BROWSERS.contains(pkg) && !AppSettings.adultExternal(this)) return;   // Focus only turns browser tabs into the focus page
        if (!browser && now - lastScan < 900) return;          // other apps: scan at most about once a second (battery)
        lastScan = now;

        LocalFocusServer.tick(this);
        if (browser && focusOn) {                                      // Focus is running: any tab / site that is not allowed becomes the focus page
            if (!driving && enforceFocus()) lastAct = now;
            return;
        }
        if (!AppSettings.adultExternal(this)) return;                  // Focus-only run: no adult scan when that switch is off

        boolean bad = false;
        scanTyping = false; scanAdult = false;
        if (browser && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getText() != null) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence cs : e.getText()) if (cs != null) sb.append(cs).append(' ');
            bad = SafeGuard.isAdultText(sb.toString());
        }
        if (!bad) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try { bad = scan(root, new int[]{0}, browser, false); } finally { try { root.recycle(); } catch (Exception ignored) { } }
        }
        if (!bad && browser) {
            if (scanAdult) pendingAdult = true;                       // adult words are being typed: wait for the search
            else if (scanTyping) pendingAdult = false;               // typing something else / cleared
            else if (pendingAdult) { pendingAdult = false; bad = true; }   // typing ended: the search was submitted
        }
        if (bad) {
            pendingAdult = false;
            lastAct = now;
            act();
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    // ── detection ───────────────────────────────────────────────────────────────────────────────

    /**
     * browser: a known browser (address bar + any focused text box are checked).
     * Other apps: only an address-bar-like view, the page title of a WebView, and a focused text box
     * that sits INSIDE a WebView (a web search box) — chats and other app text are never looked at.
     */
    private boolean scan(AccessibilityNodeInfo n, int[] budget, boolean browser, boolean inWeb) {
        if (n == null || budget[0]++ > 350) return false;
        CharSequence cls = n.getClassName();
        boolean isWeb = cls != null && cls.toString().contains("WebView");
        boolean web = inWeb || isWeb;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0 && t.length() < 600) {
            String id = n.getViewIdResourceName();
            String lid = id == null ? "" : id.toLowerCase(Locale.ROOT);
            boolean urlish = lid.endsWith("url_bar") || lid.contains("url_view") || lid.contains("location_bar")
                || lid.contains("omnibox") || lid.contains("search_box") || lid.contains("toolbar_text") || lid.contains("url_field");
            boolean typing = n.isEditable() && n.isFocused();             // the person is still typing here
            if (typing && (browser || web)) { scanTyping = true; if (SafeGuard.isAdultQuery(t.toString())) scanAdult = true; }
            if (urlish && !typing && urlBad(t.toString())) return true;
            if (isWeb && SafeGuard.isAdultText(t.toString())) return true;            // WebView: the page title
        }
        if (isWeb) {
            CharSequence d = n.getContentDescription();
            if (d != null && d.length() > 0 && d.length() < 300 && SafeGuard.isAdultText(d.toString())) return true;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            boolean r;
            try { r = scan(c, budget, browser, web); } finally { try { c.recycle(); } catch (Exception ignored) { } }
            if (r) return true;
        }
        return false;
    }

    /** Address-bar text: an adult host, or adult words inside a search address. */
    private static boolean urlBad(String text) {
        String s = text.trim();
        if (s.isEmpty()) return false;
        if (!s.contains(" ") && s.contains(".")) {
            String u = s.contains("://") ? s : "https://" + s;
            try {
                if (SafeGuard.isAdultHost(Uri.parse(u).getHost())) return true;
            } catch (Exception ignored) { }
        }
        String dec = s;
        try { dec = URLDecoder.decode(s.replace("+", " "), "UTF-8"); } catch (Exception ignored) { }
        if (dec.indexOf('?') >= 0 || dec.indexOf('&') >= 0 || dec.indexOf('#') >= 0) return SafeGuard.isAdultText(dec);
        return SafeGuard.isAdultQuery(dec);
    }

    // ── the Focus page ──────────────────────────────────────────────────────────────────────────

    private void act() {
        if (!openFocusPageInSameTab(true)) {
            performGlobalAction(GLOBAL_ACTION_BACK);      // fallback: leave the page / search, cover it with the Focus page
            showOverlay();
        }
    }

    /** Focus running: the tab must show the focus page, or an allowed site. Address bar is read only when nobody is typing in it. */
    private boolean enforceFocus() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        String txt = null;
        boolean typing = false;
        try {
            AccessibilityNodeInfo bar = findUrlBar(root, new int[]{0}, false);
            if (bar != null) {
                CharSequence t = bar.getText();
                txt = t == null ? "" : t.toString().trim();
                typing = bar.isEditable() && bar.isFocused();
                try { bar.recycle(); } catch (Exception ignored) { }
            }
        } finally { try { root.recycle(); } catch (Exception ignored) { } }
        if (txt == null || typing) return false;                     // no address bar on screen (tab grid, menu …) or still typing
        if (txt.contains("localhost")) return false;                 // our focus page
        long nowE = SystemClock.elapsedRealtime();
        if (nowE - focusLoadedAt < 6000) return false;                // the focus page was just loaded: let it finish (no reload loop / flicker)
        if (isNewTabText(txt)) {                                      // a NEW TAB: only when it STAYS a new tab for a moment (a loading page also shows an empty bar for a blink)
            if (newTabSince == 0) { newTabSince = nowE; return false; }
            if (nowE - newTabSince < 900) return false;
            newTabSince = 0;
            return openFocusPageInSameTab(false);
        }
        newTabSince = 0;
        String host = null;
        if (!txt.contains(" ") && txt.contains(".")) {
            try { host = Uri.parse(txt.contains("://") ? txt : "https://" + txt).getHost(); } catch (Exception ignored) { }
        }
        if (host != null && LocalFocusServer.isAllowedHost(this, host)) return false;   // an allowed website
        return openFocusPageInSameTab(false);
    }

    /** The browser's new-tab page: empty address bar, the hint text of the bar, or an internal page (chrome://newtab, about:blank …). */
    private static boolean isNewTabText(String txt) {
        String t = txt.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return true;
        if (t.startsWith("search or type") || t.startsWith("search google or type") || t.contains("type web address")
            || t.startsWith("search or enter") || t.startsWith("type url") || t.startsWith("enter search")) return true;
        return t.startsWith("chrome://") || t.startsWith("edge://") || t.startsWith("about:") || t.startsWith("brave://")
            || t.startsWith("opera:") || t.startsWith("vivaldi://") || t.startsWith("chrome-search://");
    }

    /** The SAME tab becomes the Focus page: the address bar gets http://localhost:PORT/focus (LocalFocusServer) + Enter, retried until it sticks. */
    private boolean openFocusPageInSameTab(final boolean adult) {
        if (Build.VERSION.SDK_INT < 30) { if (!adult) performGlobalAction(GLOBAL_ACTION_HOME); return !adult; }   // Enter action needs Android 11+
        LocalFocusServer.start(this);
        if (!LocalFocusServer.isUp()) {                                // give a just-started server a moment
            try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            if (!LocalFocusServer.isUp()) return false;                // server cannot start: old way (overlay) instead of an error page
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo bar;
        try { bar = findUrlBar(root, new int[]{0}, false); } finally { try { root.recycle(); } catch (Exception ignored) { } }
        if (bar == null) return false;
        bar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        bar.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        try { bar.recycle(); } catch (Exception ignored) { }
        driving = true;
        drivingSince = SystemClock.elapsedRealtime();
        enters = 0;
        ui.postDelayed(() -> drive(adult, 0), 350);
        return true;
    }

    private void drive(final boolean adult, final int attempt) {
        boolean done = false, fail = false;
        AccessibilityNodeInfo r = getRootInActiveWindow();
        AccessibilityNodeInfo b = null;
        if (r != null) {
            try { b = findUrlBar(r, new int[]{0}, true); } finally { try { r.recycle(); } catch (Exception ignored) { } }
        }
        if (b == null) {
            if (attempt >= 3) fail = true;
        } else {
            CharSequence cur = b.getText();
            String ct = cur == null ? "" : cur.toString();
            boolean has = ct.contains("localhost:" + LocalFocusServer.PORT);
            if (has && enters >= 1 && !ct.contains("://")) done = true;      // the browser shows the loaded page's short address
            else if (has && enters >= 2 && !b.isFocused()) done = true;      // Enter pressed twice and the box is left
            else if (has) { b.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()); enters++; }   // typed link: press Enter
            else {
                android.os.Bundle a = new android.os.Bundle();
                a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, LocalFocusServer.FOCUS_URL);
                b.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
            }
            try { b.recycle(); } catch (Exception ignored) { }
            if (!done && attempt >= 7) { if (enters > 0) done = true; else fail = true; }
        }
        if (done || fail) {
            driving = false;
            lastAct = SystemClock.elapsedRealtime();
            if (done) focusLoadedAt = lastAct;
            if (fail) {
                if (adult) { performGlobalAction(GLOBAL_ACTION_BACK); showOverlay(); }
                else performGlobalAction(GLOBAL_ACTION_HOME);
            }
            return;
        }
        ui.postDelayed(() -> drive(adult, attempt + 1), 450);
    }

    private AccessibilityNodeInfo findUrlBar(AccessibilityNodeInfo n, int[] budget, boolean editable) {
        if (n == null || budget[0]++ > 350) return null;
        String id = n.getViewIdResourceName();
        String lid = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if ((!editable || n.isEditable()) && (lid.endsWith("url_bar") || lid.contains("url_view") || lid.contains("location_bar")
            || lid.contains("omnibox") || lid.contains("url_field") || lid.contains("search_box"))) return AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo r;
            try { r = findUrlBar(c, budget, editable); } finally { try { c.recycle(); } catch (Exception ignored) { } }
            if (r != null) return r;
        }
        return null;
    }

    private void showOverlay() {
        if (overlay != null) return;
        if (wm == null) wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        final FocusManager fm = new FocusManager(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0B1F24);
        root.setPadding(Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28));

        TextView emoji = Ui.tv(this, "🎯", 56, 0xFFFFFFFF, false);
        emoji.setGravity(Gravity.CENTER);
        root.addView(emoji, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Ui.tv(this, "এখন ফোকাস করার সময়", 22, 0xFFFFFFFF, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 8));
        TextView msg = Ui.tv(this, "Adult Block চালু থাকায় এই পাতা বন্ধ করা হয়েছে।\nমন দিয়ে পড়াশোনায় ফোকাস করুন — আপনি পারবেন 💪",
            14.5f, 0xFFB8C7CB, false);
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(0, 1.25f);
        root.addView(msg, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 22));

        final TextView status = Ui.tv(this, "", 13, 0xFF7FE0A0, true);
        status.setGravity(Gravity.CENTER);
        final TextView start = Ui.btn(this, "▶  ২৫ মিনিট Focus শুরু করুন", Ui.B_PRIMARY, null);
        if (fm.isSessionActive()) {
            start.setVisibility(View.GONE);
            status.setText("✅ Focus চলছে — " + (fm.getRemainingMs() / 60000) + " মিনিট বাকি");
        }
        start.setOnClickListener(v -> {
            FocusManager.quickStart(this);
            start.setVisibility(View.GONE);
            status.setText("✅ Focus শুরু হয়েছে — ২৫ মিনিট");
        });
        root.addView(start, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10));
        root.addView(status, Ui.lpm(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10));
        TextView close = Ui.btn(this, "বন্ধ করুন", Ui.B_GHOST, v -> {
            removeOverlay();
            performGlobalAction(GLOBAL_ACTION_HOME);
        });
        root.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE);
        try {
            wm.addView(root, lp);
            overlay = root;
        } catch (Exception e) {
            overlay = null;
        }
    }

    private void removeOverlay() {
        ui.removeCallbacksAndMessages(null);
        driving = false;
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) { }
        }
        overlay = null;
    }
}
