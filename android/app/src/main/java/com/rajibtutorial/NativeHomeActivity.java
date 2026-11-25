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
    static final int V_HOME = 0, V_COURSE = 1, V_LIVE = 2, V_MANAGE = 3, V_MEMBERS = 4, V_NOTIFY = 5, V_DISCUSS = 6, V_INBOX = 7, V_RES = 8, V_OFFLINE = 9, V_QB = 10, V_FEEDBACK = 11, V_SETTINGS = 12;
    String resType = "";

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
    private View bellDot;
    private long lastUnreadCheck = 0;
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
        CrashLog.install(this);
        Store.init(this);
        // warm up the YouTube player in the background so the first lesson starts faster
        if (OfflineCache.online(this)) Media.warmUp(this);
        // Show the last saved data instantly (works with no internet); the fresh download below replaces it.
        if (OfflineCache.loadMain(this)) {
            if (Store.curMember() != null) {
                String vm = Store.validateSession();
                if (vm != null) Store.pendingToast = vm;
            }
            loading = false;
            loadedOnce = true;
        }
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
        root.setBackgroundColor(Ui.PAGE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.TEAL);                      // teal header like the website
        bar.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 10), 0);
        TextView burger = Ui.tv(this, "☰", 18, Ui.WHITE, false);
        burger.setGravity(Gravity.CENTER);
        burger.setBackground(Ui.bg(this, 0x29FFFFFF, 9, 0));
        Ui.pressFx(burger);
        burger.setOnClickListener(v -> showDrawer());
        bar.addView(burger, Ui.lpm(this, Ui.dp(this, 34), Ui.dp(this, 34), 0, 0, 10, 0));
        titleTv = Ui.ellipsized(this, "Rajib Tutorial", 17f, Ui.WHITE, true, 1);
        bar.addView(titleTv, new LinearLayout.LayoutParams(0, WRAP, 1f));

        // Notification bell (top of the home bar): opens the list of all admin notifications
        FrameLayout bell = new FrameLayout(this);
        TextView bellIc = Ui.tv(this, "🔔", 15, Ui.WHITE, false);
        bellIc.setGravity(Gravity.CENTER);
        bellIc.setBackground(Ui.bg(this, 0x24FFFFFF, 9, 0x47FFFFFF));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32), Gravity.CENTER);
        bell.addView(bellIc, bl);
        bellDot = new View(this);
        bellDot.setBackground(Ui.circle(Ui.RED));
        bellDot.setVisibility(View.GONE);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10), Gravity.TOP | Gravity.END);
        dotLp.topMargin = Ui.dp(this, 9);
        dotLp.rightMargin = Ui.dp(this, 5);
        bell.addView(bellDot, dotLp);
        Ui.pressFx(bell);
        bell.setOnClickListener(v -> openInbox());
        bar.addView(bell, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 52)));
        userArea = new LinearLayout(this);
        userArea.setOrientation(LinearLayout.HORIZONTAL);
        userArea.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(userArea, new LinearLayout.LayoutParams(WRAP, WRAP));
        topBar = bar;
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, Ui.dp(this, 52)));

        View line = new View(this);
        line.setBackgroundColor(Ui.TEAL);
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
        } else if (i != null && "inbox".equals(i.getStringExtra(EXTRA_OPEN))) {
            i.removeExtra(EXTRA_OPEN);
            openInbox();
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
        w.setStatusBarColor(Ui.TEAL);
        w.setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) {
            int f = 0;                                         // white status-bar icons on the teal header
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    /** Discussion chat draws its own (WhatsApp-style) header: hide the app bar and go dark. */
    void setChatChrome(boolean on) {
        if (topBar != null) topBar.setVisibility(on ? View.GONE : View.VISIBLE);
        if (topLine != null) topLine.setVisibility(on ? View.GONE : View.VISIBLE);
        Window w = getWindow();
        w.setStatusBarColor(on ? 0xFF1F2C34 : Ui.TEAL);
        w.setNavigationBarColor(on ? 0xFF0B141A : Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) {
            int f = 0;
            if (!on) {
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
                toast(OfflineCache.online(this) ? "Could not refresh. Please try again." : "You're offline — showing saved data");
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
        PushManager.setAdmin(this, false);
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
        if (currentView == V_COURSE && curCourse != null) openCourseNow(curCourse.id); else refreshAll();
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
        applySplitGuard();
    }

    // ── Split-screen / floating-window guard (course + live views only) ──
    private TextView splitShield;

    private boolean inMultiWindow() {
        return Build.VERSION.SDK_INT >= 24 && (isInMultiWindowMode() || isInPictureInPictureMode());
    }

    private void applySplitGuard() {
        if (content == null) return;
        boolean guard = inMultiWindow() && (currentView == V_COURSE || currentView == V_LIVE);
        if (guard) {
            if (screen != null) screen.onPause();                 // stop any playing lesson video
            if (splitShield == null) {
                splitShield = new TextView(this);
                splitShield.setBackgroundColor(Color.BLACK);
                splitShield.setTextColor(Color.WHITE);
                splitShield.setTextSize(16);
                splitShield.setGravity(Gravity.CENTER);
                splitShield.setPadding(48, 48, 48, 48);
                splitShield.setText("🔒 Split-screen / floating window এ কোর্স ভিডিও দেখা যাবে না।\nপুরো screen এ ফিরে আসুন।");
                splitShield.setClickable(true);                   // swallow touches
            }
            if (splitShield.getParent() == null) content.addView(splitShield, new FrameLayout.LayoutParams(MATCH, MATCH));
            splitShield.bringToFront();
        } else if (splitShield != null && splitShield.getParent() instanceof ViewGroup) {
            ((ViewGroup) splitShield.getParent()).removeView(splitShield);
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        applySplitGuard();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        applySplitGuard();                                        // freeform / pop-up resize
    }

    void goHome() {
        curCourse = null;
        curVideo = null;
        pendingVid = null;
        if (homeScreen == null) homeScreen = new HomeScreen(this);
        show(homeScreen, V_HOME, "Rajib Tutorial");
        closeDrawer();
    }

    /** true = "Play with Play Store" (official player, nothing can be saved); false = normal player. */
    boolean playStoreMode = false;

    /** Tapping a course asks how to play: Play Store mode (policy-safe) or Normal. */
    void openCourse(final String id) {
        final Store.Course c = Store.findCourse(id);
        if (c == null) return;
        closeDrawer();
        new android.app.AlertDialog.Builder(this)
            .setTitle(c.title)
            .setItems(new String[]{
                "🛡️  Play with Play Store\nঅফিসিয়াল YouTube player · কোনো Save/Download নেই",
                "▶️  Play with Normal\nপুরো ফিচার · Save offline সহ"}, (d, which) -> {
                playStoreMode = (which == 0);
                openCourseNow(id);
            })
            .setNegativeButton("বাতিল", null)
            .show();
    }

    /** Opens the course with the mode that is already chosen (also used to refresh after login / logout). */
    void openCourseNow(String id) {
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

    void openResources(String type) {
        resType = type;
        show(new ResourceScreen(this, type), V_RES, Dialogs.resTypeName(type));
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

    // ── Runtime permission helper (voice messages need the microphone) ──
    interface PermCb { void result(boolean granted); }
    private PermCb permCb;

    void askPermission(String perm, PermCb cb) {
        if (android.os.Build.VERSION.SDK_INT < 23
            || checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cb.result(true);
            return;
        }
        permCb = cb;
        requestPermissions(new String[]{perm}, 502);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 502) {
            PermCb cb = permCb;
            permCb = null;
            if (cb != null) cb.result(res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
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

    void openQb() {
        show(new QbScreen(this), V_QB, "MCQ Practice");
        closeDrawer();
    }

    void openOffline() {
        show(new OfflineScreen(this), V_OFFLINE, "Offline Lessons");
        closeDrawer();
    }

    void openFeedbackInbox() {
        if (!Store.adminIn) return;
        show(new FeedbackScreen(this), V_FEEDBACK, "Feedback");
        closeDrawer();
    }

    void openSettings() {
        show(new SettingsScreen(this), V_SETTINGS, "Settings");
        closeDrawer();
    }

    void openInbox() {
        if (bellDot != null) bellDot.setVisibility(View.GONE);
        show(new InboxScreen(this), V_INBOX, "Notifications");
        closeDrawer();
    }

    /** Shows / hides the red dot on the bell. {@code check} = ask the server whether something new arrived. */
    void updateBell(boolean check) {
        if (bellDot == null) return;
        if (!check) { bellDot.setVisibility(View.GONE); return; }
        long now = System.currentTimeMillis();
        if (now - lastUnreadCheck < 15000) return;
        lastUnreadCheck = now;
        try {
            Store.async(() -> Fs.query("push_messages", null, null, null, "at", true, 30),
                (java.util.List<Fs.Doc> docs, Exception err) -> {
                    if (err != null || docs == null || isFinishing() || bellDot == null) return;
                    long seen = PushManager.inboxSeen(this);
                    if (seen == 0) { PushManager.inboxMarkSeen(this, now); return; }   // first run: nothing is "new"
                    boolean fresh = false;
                    for (Fs.Doc d : docs) {
                        long at = Fs.num(d.data, "at");
                        if (at <= seen) continue;
                        if (Store.notifForMe(Fs.text(d.data, "target"), Fs.text(d.data, "mobile"), Fs.text(d.data, "batch"))) { fresh = true; break; }
                    }
                    bellDot.setVisibility(fresh && currentView != V_INBOX ? View.VISIBLE : View.GONE);
                });
        } catch (Throwable ignored) { }
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
        if (screen != null && screen.onBack()) return;
        if (currentView != V_HOME) goHome();
        else super.onBackPressed();
    }

    // ── Lifecycle ───────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        applySplitGuard();
        if (screen != null && !(inMultiWindow() && (currentView == V_COURSE || currentView == V_LIVE))) screen.onResume();
        OfflineGuard.checkIfDue(this);       // daily internet check that keeps offline lessons unlocked
        if (Store.sessionMobile != null) startPoll();
        updateBell(true);
        if (PaymentActivity.pending) {      // back from the bKash page: refresh member / premium status
            PaymentActivity.pending = false;
            reload();
        }
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
        login.setTextColor(Ui.WHITE);
        login.setBackground(Ui.bg(this, 0x24FFFFFF, 10, 0x59FFFFFF));
        TextView prem = Ui.btn(this, "★ Premium", Ui.B_PRIMARY, v -> dialogs.pay());
        prem.setMinHeight(Ui.dp(this, 36));
        prem.setTextSize(12.5f);
        prem.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        prem.setTextColor(Ui.TEAL2);
        prem.setBackground(Ui.bg(this, Ui.WHITE, 10, 0));
        userArea.addView(login, Ui.lpm(this, WRAP, WRAP, 0, 0, 6, 0));
        userArea.addView(prem);
    }

    private View chip(final String name, final String sub, final boolean canLogout) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(Ui.bg(this, 0x24FFFFFF, 20, 0x59FFFFFF));
        c.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        TextView av = Ui.tv(this, name.trim().isEmpty() ? "U" : name.trim().substring(0, 1).toUpperCase(), 13, Ui.TEAL2, true);
        av.setGravity(Gravity.CENTER);
        av.setBackground(Ui.circle(Ui.WHITE));
        c.addView(av, Ui.lpm(this, Ui.dp(this, 28), Ui.dp(this, 28), 0, 0, 8, 0));
        TextView n = Ui.ellipsized(this, name, 13, Ui.WHITE, true, 1);
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

    /** Sidebar row like the website: full-width, white text on teal; the active row is a white bar with teal text. */
    private View navItem(String text, boolean active, boolean dot, final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11));
        if (active) row.setBackgroundColor(Ui.WHITE);
        TextView t = Ui.tv(this, text, 13.5f, active ? Ui.TEAL2 : Ui.WHITE, active);
        row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (dot) {
            View d = new View(this);
            d.setBackground(Ui.circle(0xFFFF5A5A));
            row.addView(d, new LinearLayout.LayoutParams(Ui.dp(this, 9), Ui.dp(this, 9)));
        }
        Ui.pressFx(row);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private TextView navLabel(String s) {
        TextView t = Ui.tv(this, s.toUpperCase(), 10, 0x99FFFFFF, true);
        t.setLetterSpacing(0.09f);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), 0, Ui.dp(this, 5));
        return t;
    }

    /** Admin Panel buttons: translucent white rounded buttons, like .btn-admin-action. */
    private View adminBtn(String text, boolean danger, final Runnable onClick) {
        TextView b = Ui.tv(this, text, 13, danger ? 0xFFFCA5A5 : Ui.WHITE, true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(Ui.bg(this, 0x1FFFFFFF, 12, 0x2EFFFFFF));
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        Ui.pressFx(b);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.setMargins(0, 0, 0, Ui.dp(this, 6));
        b.setLayoutParams(lp);
        return b;
    }

    void showDrawer() {
        closeDrawer();
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        drawer = d;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.TEAL2);                  // solid dark teal sidebar

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 13));
        TextView logo = Ui.tv(this, "✳", 18, Ui.TEAL2, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.circle(Ui.WHITE));
        top.addView(logo, Ui.lpm(this, Ui.dp(this, 34), Ui.dp(this, 34), 0, 0, 10, 0));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(Ui.tv(this, "Rajib Tutorial", 14, Ui.WHITE, true));
        brand.addView(Ui.tv(this, "Diploma in EEE", 10.5f, 0xBFFFFFFF, false));
        top.addView(brand);
        col.addView(top);
        View sep = new View(this);
        sep.setBackgroundColor(0x24FFFFFF);
        col.addView(sep, new LinearLayout.LayoutParams(MATCH, 1));

        ScrollView sv = new ScrollView(this);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        sv.addView(nav, new ScrollView.LayoutParams(MATCH, WRAP));
        col.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        nav.addView(navLabel("Menu"));
        nav.addView(navItem("🏠  Home", currentView == V_HOME, false, this::goHome));
        boolean cOn = !Store.safeUrl(Store.live.classUrl).isEmpty();
        boolean eOn = !Store.safeUrl(Store.live.examUrl).isEmpty();
        nav.addView(navItem("🎥  Live Class", currentView == V_LIVE && !liveExam, cOn, () -> openLive(false)));
        nav.addView(navItem("📝  Live Exam", currentView == V_LIVE && liveExam, eOn, () -> openLive(true)));
        nav.addView(navItem("💬  Discussion", currentView == V_DISCUSS, false, this::openDiscussion));
        nav.addView(navItem("🔔  Notifications", currentView == V_INBOX, false, this::openInbox));
        nav.addView(navItem("📥  Offline Lessons", currentView == V_OFFLINE, false, this::openOffline));
        nav.addView(navItem("📢  Current Circular", currentView == V_RES && Store.R_CIRCULAR.equals(resType), false, () -> openResources(Store.R_CIRCULAR)));
        nav.addView(navItem("📚  Question Bank", currentView == V_RES && Store.R_QBANK.equals(resType), false, () -> openResources(Store.R_QBANK)));
        nav.addView(navItem("🆕  Recent Question Bank", currentView == V_RES && Store.R_RECENT.equals(resType), false, () -> openResources(Store.R_RECENT)));
        nav.addView(navItem("📝  MCQ Practice", currentView == V_QB, false, this::openQb));
        nav.addView(navItem("🧰  Study Tools", false, false, () -> { closeDrawer(); startActivity(new Intent(this, StudyToolsActivity.class)); }));
        nav.addView(navItem("🌐  Safe Browser", false, false, () -> { closeDrawer(); startActivity(new Intent(this, SafeBrowserActivity.class)); }));
        nav.addView(navItem("📖  Office Reader", false, false, () -> { closeDrawer(); startActivity(new Intent(this, OfficeReaderActivity.class)); }));
        nav.addView(navItem("💬  Feedback", false, false, () -> { closeDrawer(); FeedbackScreen.showSendDialog(this); }));
        nav.addView(navItem("⚙️  Settings", currentView == V_SETTINGS, false, this::openSettings));
        nav.addView(navLabel("Courses"));
        if (loading) {
            TextView lt = Ui.tv(this, "Loading…", 12, 0xBFFFFFFF, false);
            lt.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), 0, 0);
            nav.addView(lt);
        } else if (Store.courses.isEmpty()) {
            TextView t = Ui.tv(this, "No courses yet", 12, 0xBFFFFFFF, false);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), 0, 0);
            nav.addView(t);
        }
        for (final Store.Course c : Store.courses) {
            boolean active = currentView == V_COURSE && curCourse != null && curCourse.id.equals(c.id);
            nav.addView(navItem("•  " + c.title, active, false, () -> openCourse(c.id)));
        }

        View sep2 = new View(this);
        sep2.setBackgroundColor(0x24FFFFFF);
        col.addView(sep2, new LinearLayout.LayoutParams(MATCH, 1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 14));
        if (Store.adminIn) {
            TextView al = Ui.tv(this, "ADMIN PANEL", 10, 0xFFFCD34D, true);
            al.setLetterSpacing(0.1f);
            al.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 8));
            bottom.addView(al);
            // the admin list scrolls inside a bounded area so the course list above it keeps room on small phones
            LinearLayout adm = new LinearLayout(this);
            adm.setOrientation(LinearLayout.VERTICAL);
            adm.addView(adminBtn("📚  Manage Courses", false, this::openManage));
            adm.addView(adminBtn("⭐  Premium Members", false, this::openMembers));
            adm.addView(adminBtn("🔔  Notification Center", false, this::openNotify));
            adm.addView(adminBtn("💬  Feedback Inbox", false, this::openFeedbackInbox));
            adm.addView(adminBtn("🌐  Safe Browser", false, () -> { closeDrawer(); startActivity(new Intent(this, SafeBrowserActivity.class)); }));
            adm.addView(adminBtn("▶️  YouTube", false, () -> openWeb("https://m.youtube.com/", "YouTube", false, false)));
            adm.addView(adminBtn("🔍  Google", false, () -> openWeb("https://www.google.com/", "Google", false, true)));
            adm.addView(adminBtn("🎬  YouTube Studio", false, () -> openWeb("https://studio.youtube.com/", "YouTube Studio", true, false)));
            adm.addView(adminBtn("⚙️  Live & Premium Settings", false, () -> { closeDrawer(); dialogs.liveSettings(); }));
            adm.addView(adminBtn("⎋  Logout", true, () -> { closeDrawer(); adminLogout(); }));
            ScrollView asv = new ScrollView(this);
            asv.addView(adm, new ScrollView.LayoutParams(MATCH, WRAP));
            bottom.addView(asv, new LinearLayout.LayoutParams(MATCH, Math.round(getResources().getDisplayMetrics().heightPixels * 0.34f)));
        } else {
            TextView lg = Ui.tv(this, "🔒  Admin Login", 12.5f, Ui.WHITE, true);
            lg.setGravity(Gravity.CENTER);
            lg.setBackground(Ui.bg(this, 0x1FFFFFFF, 10, 0x4DFFFFFF));
            lg.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
            Ui.pressFx(lg);
            lg.setOnClickListener(v -> { closeDrawer(); dialogs.adminLogin(); });
            bottom.addView(lg, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        col.addView(bottom, new LinearLayout.LayoutParams(MATCH, WRAP));

        d.setContentView(col);
        Window w = d.getWindow();
        if (w != null) {
            int sw = getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(Ui.dp(this, 280), Math.round(sw * 0.80f));
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
