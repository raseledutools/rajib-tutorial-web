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

    private long lastAct = 0;
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
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        if (!AppSettings.adultExternal(this)) return;
        String pkg = e.getPackageName().toString();
        if (getPackageName().equals(pkg) || SKIP.contains(pkg)) return;
        if (overlay != null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastAct < 2000) return;
        final boolean browser = BROWSERS.contains(pkg);
        if (!browser && now - lastScan < 900) return;          // other apps: scan at most about once a second (battery)
        lastScan = now;

        boolean bad = false;
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
        if (bad) {
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
            if (urlish && urlBad(t.toString())) return true;
            if (!urlish && n.isEditable() && n.isFocused() && (browser || web) && SafeGuard.isAdultQuery(t.toString())) return true;
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
        performGlobalAction(GLOBAL_ACTION_BACK);          // leave the page / search
        showOverlay();
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
            fm.setFocusEnabled(true);
            fm.startSession(QUICK_FOCUS_MS);
            try {
                Intent si = new Intent(this, FocusOverlayService.class).setAction(FocusOverlayService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, si); else startService(si);
            } catch (Exception ignored) { }
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
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) { }
        }
        overlay = null;
    }
}
