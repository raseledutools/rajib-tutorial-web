package com.rajibtutorial;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Native (Java) version of the web app: same Firestore data, same rules,
 * same screens — home, course player, live class / exam, premium login and
 * the admin panel.
 */
public class NativeHomeActivity extends AppCompatActivity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final String EXTRA_OPEN = "open";
    static final int V_HOME = 0, V_COURSE = 1, V_LIVE = 2, V_MANAGE = 3, V_MEMBERS = 4, V_NOTIFY = 5, V_DISCUSS = 6;

    // state shared with screens / dialogs
    Store.Course curCourse = null;
    Store.Video curVideo = null;
    String pendingVid = null;
    boolean loading = true, loadFailed = false;
    String loadError = "";
    Dialogs dialogs;

    private boolean loadedOnce = false;
    private int currentView = V_HOME;
    private boolean liveExam = false;
    private Screen screen;
    private HomeScreen homeScreen;
    private View homeView;
    private FrameLayout content;
    private TextView titleTv;
    private LinearLayout topBar;
    private View topLine;
    private LinearLayout userArea;
    private Dialog drawer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling = false;
    private String pushUserSynced = null;   // last mobile sent to PushManager
    private Toast toast;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!polling || Store.sessionMobile == null) { polling = false; return; }
            Store.checkSession(false, (String msg, Exception e) -> {
                if (msg != null && !msg.isEmpty()) forceLogout(msg);
            });
            handler.postDelayed(this, 10000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.init(this);
        dialogs = new Dialogs(this);
        // Push notifications: runtime permission (Android 13+) + channel/topic/token
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 501);
        }
        PushManager.init(this);
        setRequestedOrientationPortrait();
        styleSystemBars();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BG);
        bar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 10), 0);
        TextView burger = Ui.tv(this, "☰", 22, Ui.TEXT, false);
        burger.setGravity(Gravity.CENTER);
        Ui.pressFx(burger);
        burger.setOnClickListener(v -> showDrawer());
        bar.addView(burger, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 52)));
        titleTv = Ui.ellipsized(this, "Rajib Tutorial", 16.5f, Ui.TEXT, true, 1);
        bar.addView(titleTv, new LinearLayout.LayoutParams(0, WRAP, 1f));
        userArea = new LinearLayout(this);
        userArea.setOrientation(LinearLayout.HORIZONTAL);
        userArea.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(userArea, new LinearLayout.LayoutParams(WRAP, WRAP));
        topBar = bar;
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, Ui.dp(this, 52)));

        View line = new View(this);
        line.setBackgroundColor(Ui.BORDER);
        topLine = line;
        root.addView(line, new LinearLayout.LayoutParams(MATCH, 1));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(root);

        renderUser();
        goHome();
        reload();
        handleOpenIntent(getIntent());
    }

    private void handleOpenIntent(Intent i) {
        if (i != null && "discussion".equals(i.getStringExtra(EXTRA_OPEN))) {
            i.removeExtra(EXTRA_OPEN);
            openDiscussion();
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleOpenIntent(i);
    }

    private void setRequestedOrientationPortrait() {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void styleSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(Color.WHITE);
        w.setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) {
            int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    /** Discussion chat draws its own (WhatsApp-style) header: hide the app bar and go dark. */
    void setChatChrome(boolean on) {
        if (topBar != null) topBar.setVisibility(on ? View.GONE : View.VISIBLE);
        if (topLine != null) topLine.setVisibility(on ? View.GONE : View.VISIBLE);
        Window w = getWindow();
        w.setStatusBarColor(on ? 0xFF1F2C34 : Color.WHITE);
        w.setNavigationBarColor(on ? 0xFF0B141A : Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) {
            int f = 0;
            if (!on) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    // ── Public helpers used by screens/dialogs ──────────────
    void toast(String msg) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        toast.show();
    }

    void reload() { reload(null); }

    void reload(final Runnable after) {
        if (!loadedOnce) { loading = true; loadFailed = false; refreshAll(); }
        Store.loadAll((Boolean ok, Exception e) -> {
            if (isFinishing()) return;
            loading = false;
            if (ok != null && ok) {
                loadedOnce = true;
                loadFailed = false;
                syncCurrent();
                if (Store.pendingToast != null) { toast(Store.pendingToast); Store.pendingToast = null; }
                if (Store.sessionMobile != null) startPoll();
            } else if (!loadedOnce) {
                loadFailed = true;
                loadError = e != null && e.getMessage() != null ? e.getMessage() : "";
            } else {
                toast("Could not refresh. Check your internet connection.");
            }
            refreshAll();
            if (after != null && ok != null && ok) after.run();
        });
    }

    private void syncCurrent() {
        if (curCourse != null) {
            Store.Course fresh = Store.findCourse(curCourse.id);
            if (fresh == null) { curCourse = null; curVideo = null; if (currentView == V_COURSE) goHome(); return; }
            curCourse = fresh;
            if (curVideo != null) {
                Store.Video nv = null;
                for (Store.Video v : fresh.videos) if (v.id.equals(curVideo.id)) nv = v;
                curVideo = nv;
            }
        }
    }

    /** Tells PushManager which member is logged in (same rule as the web: not admin, premium only). */
    private String discSynced = null;

    /** Premium members + admin receive Discussion pushes (FCM topic); everybody else is unsubscribed. */
    private void syncDiscussionTopic() {
        if (loading && Store.sessionMobile != null) return;
        boolean on = Store.adminIn || Store.isPremium();
        String me = Store.adminIn ? "admin" : (Store.sessionMobile == null ? "" : Store.sessionMobile);
        String key = on + ":" + me;
        if (key.equals(discSynced)) return;
        discSynced = key;
        try { PushManager.setDiscussion(getApplicationContext(), on, me); } catch (Throwable ignored) { }
    }

    private void syncPushUser() {
        syncDiscussionTopic();
        if (loading && Store.sessionMobile != null) return;   // members not loaded yet — wait
        String m = Store.sessionMobile != null ? Store.sessionMobile : "";   // admin phone logged in as member must register too
        if (m.equals(pushUserSynced)) return;
        pushUserSynced = m;
        try { PushManager.setUser(getApplicationContext(), m); } catch (Throwable ignored) { }
    }

    void refreshAll() {
        renderUser();
        syncPushUser();
        if (screen != null) screen.refresh();
    }

    void onAdminChanged() { refreshAll(); }

    void onSessionChanged() {
        if (Store.sessionMobile != null) startPoll();
        refreshAll();
    }

    void resumePending() {
        if (pendingVid != null && screen instanceof CourseScreen) {
            String v = pendingVid;
            pendingVid = null;
            ((CourseScreen) screen).play(v);
        }
    }

    void forceLogout(String msg) {
        stopPoll();
        Store.dropSession();
        if (screen instanceof CourseScreen) ((CourseScreen) screen).stopWithMessage(msg);
        curVideo = null;
        refreshAll();
        toast(msg);
    }

    void adminLogout() {
        Store.adminIn = false;
        renderUser();
        syncPushUser();
        goHome();
        toast("Logged out");
    }

    void memberLogout() {
        stopPoll();
        Store.memberLogout();
        pendingVid = null;
        curVideo = null;
        renderUser();
        syncPushUser();
        if (currentView == V_COURSE && curCourse != null) openCourse(curCourse.id); else refreshAll();
        toast("Logged out");
    }

    // ── Navigation ──────────────────────────────────────────
    private void show(Screen s, int view, String title) {
        if (screen != null && screen != homeScreen) screen.release();
        screen = s;
        currentView = view;
        content.removeAllViews();
        View v;
        if (s == homeScreen && homeView != null) {
            v = homeView;
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            s.refresh();
        } else {
            v = s.build();
            if (s == homeScreen) homeView = v;
        }
        content.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
        titleTv.setText(title);
        if (view == V_COURSE || view == V_LIVE) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    void goHome() {
        curCourse = null;
        curVideo = null;
        pendingVid = null;
        if (homeScreen == null) homeScreen = new HomeScreen(this);
        show(homeScreen, V_HOME, "Rajib Tutorial");
        closeDrawer();
    }

    void openCourse(String id) {
        Store.Course c = Store.findCourse(id);
        if (c == null) return;
        curCourse = c;
        curVideo = null;
        pendingVid = null;
        show(new CourseScreen(this), V_COURSE, c.title);
        closeDrawer();
    }

    void openLive(boolean exam) {
        liveExam = exam;
        show(new LiveScreen(this, exam), V_LIVE, exam ? "Live Exam" : "Live Class");
        closeDrawer();
    }

    void openManage() {
        if (!Store.adminIn) return;
        show(new ManageScreen(this), V_MANAGE, "Manage Courses");
        closeDrawer();
    }

    void openDiscussion() {
        show(new DiscussionScreen(this), V_DISCUSS, "Discussion");
        closeDrawer();
    }

    // ── File picker (Discussion attachments) ────────────────
    interface PickCb { void onPicked(android.net.Uri uri); }
    private static final int REQ_PICK = 7301;
    private PickCb pickCb;

    void pickFile(String mime, PickCb cb) {
        pickCb = cb;
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType(mime);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Select"), REQ_PICK);
        } catch (Exception e) {
            pickCb = null;
            toast("Could not open the file picker");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK) {
            PickCb cb = pickCb;
            pickCb = null;
            if (res == RESULT_OK && data != null && data.getData() != null && cb != null) cb.onPicked(data.getData());
        }
    }

    void openMembers() {
        if (!Store.adminIn) return;
        show(new MembersScreen(this), V_MEMBERS, "Premium Members");
        closeDrawer();
    }

    void openNotify() {
        if (!Store.adminIn) return;
        show(new NotifyScreen(this), V_NOTIFY, "Notification Center");
        closeDrawer();
    }

    /** Opens an in-app WebView (YouTubeActivity) – no external browser/app is launched. */
    private void openWeb(String url, String title, boolean studio, boolean browser) {
        closeDrawer();
        Intent i = new Intent(this, YouTubeActivity.class);
        i.putExtra("url", url);
        i.putExtra("title", title);
        if (studio) i.putExtra("upload", true);     // desktop site + file picker for uploads
        if (browser) i.putExtra("browser", true);   // plain browser behaviour
        startActivity(i);
    }

    @Override
    public void onBackPressed() {
        if (currentView != V_HOME) goHome();
        else super.onBackPressed();
    }

    // ── Lifecycle ───────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        if (screen != null) screen.onResume();
        if (Store.sessionMobile != null) startPoll();
    }

    @Override
    protected void onPause() {
        if (screen != null) screen.onPause();
        stopPoll();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopPoll();
        if (screen != null) screen.release();
        super.onDestroy();
    }

    private void startPoll() {
        if (polling || Store.sessionMobile == null) return;
        polling = true;
        handler.removeCallbacks(pollTask);
        handler.post(pollTask);
    }

    private void stopPoll() {
        polling = false;
        handler.removeCallbacks(pollTask);
    }

    // ── Top-bar user area ───────────────────────────────────
    void renderUser() {
        if (userArea == null) return;
        userArea.removeAllViews();
        if (Store.adminIn) {
            userArea.addView(chip("Admin", "Administrator access", false));
            return;
        }
        if (Store.isPremium()) {
            Store.Member m = Store.curMember();
            String sub = "Premium · " + Store.accessLabel(m) + (m.expiry.isEmpty() ? "" : " · until " + m.expiry);
            userArea.addView(chip(m.name.isEmpty() ? "Member" : m.name, sub, true));
            return;
        }
        TextView login = Ui.btn(this, "Login", Ui.B_OUTLINE, v -> dialogs.memberLogin());
        login.setMinHeight(Ui.dp(this, 36));
        login.setTextSize(12.5f);
        login.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        TextView prem = Ui.btn(this, "★ Premium", Ui.B_PRIMARY, v -> dialogs.premiumGate(null));
        prem.setMinHeight(Ui.dp(this, 36));
        prem.setTextSize(12.5f);
        prem.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        userArea.addView(login, Ui.lpm(this, WRAP, WRAP, 0, 0, 6, 0));
        userArea.addView(prem);
    }

    private View chip(final String name, final String sub, final boolean canLogout) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(Ui.bg(this, Ui.BG2, 20, Ui.BORDER));
        c.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        TextView av = Ui.tv(this, name.trim().isEmpty() ? "U" : name.trim().substring(0, 1).toUpperCase(), 13, Ui.WHITE, true);
        av.setGravity(Gravity.CENTER);
        av.setBackground(Ui.circle(Ui.ACCENT));
        c.addView(av, Ui.lpm(this, Ui.dp(this, 28), Ui.dp(this, 28), 0, 0, 8, 0));
        TextView n = Ui.ellipsized(this, name, 13, Ui.TEXT, true, 1);
        n.setMaxWidth(Ui.dp(this, 110));
        c.addView(n);
        Ui.pressFx(c);
        c.setOnClickListener(v -> {
            final Ui.Modal m = new Ui.Modal(this, name, sub);
            if (canLogout) m.col("Logout", Ui.B_OUTLINE, x -> { m.dismiss(); memberLogout(); });
            m.col("Close", Ui.B_GHOST, x -> m.dismiss());
            m.show();
        });
        return c;
    }

    // ── Side drawer ─────────────────────────────────────────
    private void closeDrawer() {
        if (drawer != null) {
            try { drawer.dismiss(); } catch (Exception ignored) { }
            drawer = null;
        }
    }

    private View navItem(String text, boolean active, boolean dot, final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        if (active) row.setBackground(Ui.bg(this, Ui.ACCENT_SOFT, 12, 0));
        TextView t = Ui.tv(this, text, 14.5f, active ? Ui.ACCENT2 : Ui.TEXT, active);
        row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (dot) {
            View d = new View(this);
            d.setBackground(Ui.circle(Ui.RED));
            row.addView(d, new LinearLayout.LayoutParams(Ui.dp(this, 9), Ui.dp(this, 9)));
        }
        Ui.pressFx(row);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private TextView navLabel(String s) {
        TextView t = Ui.tv(this, s.toUpperCase(), 11, Ui.SUB, true);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), 0, Ui.dp(this, 4));
        return t;
    }

    void showDrawer() {
        closeDrawer();
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        drawer = d;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG2);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 14));
        TextView logo = Ui.tv(this, "✳", 20, Ui.ACCENT, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.circle(Ui.ACCENT_SOFT));
        top.addView(logo, Ui.lpm(this, Ui.dp(this, 40), Ui.dp(this, 40), 0, 0, 12, 0));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(Ui.tv(this, "Rajib Tutorial", 16, Ui.TEXT, true));
        brand.addView(Ui.tv(this, "Diploma in EEE", 12, Ui.SUB, false));
        top.addView(brand);
        col.addView(top);

        ScrollView sv = new ScrollView(this);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 8));
        sv.addView(nav, new ScrollView.LayoutParams(MATCH, WRAP));
        col.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        nav.addView(navLabel("Menu"));
        nav.addView(navItem("🏠  Home", currentView == V_HOME, false, this::goHome));
        boolean cOn = !Store.safeUrl(Store.live.classUrl).isEmpty();
        boolean eOn = !Store.safeUrl(Store.live.examUrl).isEmpty();
        nav.addView(navItem("🎥  Live Class", currentView == V_LIVE && !liveExam, cOn, () -> openLive(false)));
        nav.addView(navItem("📝  Live Exam", currentView == V_LIVE && liveExam, eOn, () -> openLive(true)));
        nav.addView(navItem("💬  Discussion", currentView == V_DISCUSS, false, this::openDiscussion));
        nav.addView(navLabel("Courses"));
        if (loading) {
            nav.addView(Ui.tv(this, "Loading…", 12, Ui.SUB, false));
        } else if (Store.courses.isEmpty()) {
            TextView t = Ui.tv(this, "No courses yet", 12, Ui.SUB, false);
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), 0, 0);
            nav.addView(t);
        }
        for (final Store.Course c : Store.courses) {
            boolean active = currentView == V_COURSE && curCourse != null && curCourse.id.equals(c.id);
            nav.addView(navItem("•  " + c.title, active, false, () -> openCourse(c.id)));
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 14));
        if (Store.adminIn) {
            bottom.addView(navLabel("Admin Panel"));
            bottom.addView(navItem("📚  Manage Courses", currentView == V_MANAGE, false, this::openManage));
            bottom.addView(navItem("⭐  Premium Members", currentView == V_MEMBERS, false, this::openMembers));
            bottom.addView(navItem("🔔  Notification Center", currentView == V_NOTIFY, false, this::openNotify));
            bottom.addView(navItem("▶  YouTube", false, false, () -> openWeb("https://m.youtube.com/", "YouTube", false, false)));
            bottom.addView(navItem("🌐  Google", false, false, () -> openWeb("https://www.google.com/", "Google", false, true)));
            bottom.addView(navItem("🎬  YouTube Studio", false, false, () -> openWeb("https://studio.youtube.com/", "YouTube Studio", true, false)));
            bottom.addView(navItem("⚙  Live & Premium Settings", false, false, () -> { closeDrawer(); dialogs.liveSettings(); }));
            bottom.addView(navItem("⎋  Logout", false, false, () -> { closeDrawer(); adminLogout(); }));
        } else {
            bottom.addView(Ui.btn(this, "🔒  Admin Login", Ui.B_GHOST, v -> { closeDrawer(); dialogs.adminLogin(); }));
        }
        col.addView(bottom, new LinearLayout.LayoutParams(MATCH, WRAP));

        d.setContentView(col);
        Window w = d.getWindow();
        if (w != null) {
            int sw = getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(Ui.dp(this, 310), Math.round(sw * 0.84f));
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.START | Gravity.TOP);
            w.setLayout(width, MATCH);
            w.setDimAmount(0.45f);
        }
        d.setCanceledOnTouchOutside(true);
        d.setOnDismissListener(x -> { if (drawer == d) drawer = null; });
        d.show();
    }
}
