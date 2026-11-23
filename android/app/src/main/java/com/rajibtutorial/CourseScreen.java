package com.rajibtutorial;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.graphics.Outline;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

/** Course page: lesson list + inline player (YouTube embed or direct video). */
final class CourseScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT;

    // offline button colours (same palette as the exe's "Offline" button)
    private static final int COL_SAVED = 0xFF16A34A;   // green: saved, plays without internet

    private final NativeHomeActivity a;
    private LinearLayout root, listWrap;
    private FrameLayout playerFrame;
    private TextView cpTitle, cpSub, playerTitle, playerMeta, buyBtn;
    private TextView offBtn, offNote;                       // "Save offline" button + status line under the player
    private final Map<String, OffIcon> icons = new HashMap<>();   // lesson id -> its list indicator
    private WebView web;
    private VideoView video;
    private boolean released      = false;
    /** One listener for every save started from this screen (list icon, player button, auto-save). */
    private final VideoCache.ProgressListener dl;
    private boolean inlineSuspended = false;
    // in-place fullscreen: the SAME player view is moved into fsLayer (no re-create => no re-buffering)
    private FrameLayout fsLayer;
    private TextView fsClose;
    private int fsPrevOrientation;
    private boolean resumeVideo = false, fsWasPlaying = false;
    private int fsPos = 0;

    CourseScreen(NativeHomeActivity host) {
        a = host;
        dl = (id, pct) -> {
            if (released) return;
            if (pct == -1) a.toast(VideoCache.lastError(id));
            else if (pct == -2) a.toast("Download cancelled");
            else if (pct == 100) a.toast("✅ Saved for offline · " + VideoCache.OFFLINE_DAYS + " days");
            offTick(id, pct >= 100 || pct < 0);
        };
    }

    @Override
    View build() {
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG2);

        // header
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(Ui.BG);
        head.setElevation(Ui.dp(a, 3));
        head.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        TextView back = Ui.tv(a, "←  All Courses", 13.5f, Ui.ACCENT2, true);
        back.setPadding(0, Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 6));
        Ui.pressFx(back);
        back.setOnClickListener(v -> a.goHome());
        head.addView(back, new LinearLayout.LayoutParams(WRAP, WRAP));
        cpTitle = Ui.ellipsized(a, "", 17, Ui.TEXT, true, 2);
        cpSub   = Ui.tv(a, "", 12.5f, Ui.SUB, false);
        head.addView(cpTitle);
        head.addView(cpSub);
        buyBtn = Ui.btn(a, "", Ui.B_PRIMARY, v -> { if (a.curCourse != null) a.dialogs.payCourse(a.curCourse); });
        buyBtn.setVisibility(View.GONE);
        head.addView(buyBtn, Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 2));
        root.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        // player (16:9, rounded dark frame like the exe's player area)
        playerFrame = new FrameLayout(a);
        playerFrame.setBackground(Ui.bg(a, 0xFF0F0C1A, 12, 0));
        final float rad = Ui.dp(a, 12);
        playerFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); }
        });
        playerFrame.setClipToOutline(true);
        int m = Ui.dp(a, 12);
        int w = a.getResources().getDisplayMetrics().widthPixels - 2 * m;
        LinearLayout.LayoutParams pfLp = new LinearLayout.LayoutParams(MATCH, w * 9 / 16);
        pfLp.setMargins(m, Ui.dp(a, 12), m, 0);
        root.addView(playerFrame, pfLp);

        // player info: title, meta, offline status, then the [Save offline] [Fullscreen] buttons (exe layout)
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        playerTitle = Ui.ellipsized(a, "–", 16, Ui.TEXT, true, 2);
        playerMeta  = Ui.tv(a, "", 12.5f, Ui.SUB, false);
        offNote     = Ui.tv(a, "", 12.5f, Ui.SUB, false);
        offNote.setVisibility(View.GONE);
        info.addView(playerTitle);
        info.addView(playerMeta);
        info.addView(offNote, Ui.lpm(a, MATCH, WRAP, 0, 6, 0, 0));
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        offBtn = Ui.tv(a, "📥  Save offline", 13.5f, Ui.ACCENT2, true);
        offBtn.setOnClickListener(v -> onOffBtn());
        TextView fsBtn = Ui.tv(a, "⛶  Fullscreen", 13.5f, Ui.ACCENT2, true);
        fsBtn.setBackground(Ui.bg(a, Ui.BG, 10, Ui.ACCENT));
        fsBtn.setGravity(Gravity.CENTER);
        fsBtn.setMinHeight(Ui.dp(a, 44));
        Ui.pressFx(fsBtn);
        fsBtn.setOnClickListener(v -> openFullscreen());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        l1.setMargins(0, 0, Ui.dp(a, 5), 0);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        l2.setMargins(Ui.dp(a, 5), 0, 0, 0);
        btns.addView(offBtn, l1);
        btns.addView(fsBtn, l2);
        info.addView(btns, Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        root.addView(info, new LinearLayout.LayoutParams(MATCH, WRAP));

        View div = new View(a);
        div.setBackgroundColor(Ui.BORDER);
        root.addView(div, new LinearLayout.LayoutParams(MATCH, 1));

        // lessons
        ScrollView sv = new ScrollView(a);
        listWrap = new LinearLayout(a);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 16));
        sv.addView(listWrap, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        Store.Course c = a.curCourse;
        cpTitle.setText(c == null ? "Course" : c.title);
        cpSub.setText(c == null ? "" : Store.lessonsLabel(c.videos.size()));
        updateBuy();
        VideoCache.purgeExpired(a);
        showPlaceholder("Select a lesson to start watching");
        renderList();
        updateOffControls();
        return root;
    }

    @Override
    void refresh() {
        if (root == null) return;
        Store.Course c = a.curCourse;
        if (c != null) {
            cpTitle.setText(c.title);
            cpSub.setText(Store.lessonsLabel(c.videos.size()));
        }
        updateBuy();
        renderList();
        updateOffControls();
    }

    private void updateBuy() {
        Store.Course c = a.curCourse;
        boolean show = c != null && c.price > 0 && !Store.courseAllowed(c);
        buyBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) buyBtn.setText("Buy this course · " + Store.priceLabel(c.price));
    }

    // ── Player ──────────────────────────────────────────────────────────────

    private void clearPlayer() {
        resumeVideo = false;
        if (video != null) {
            try { video.stopPlayback(); } catch (Exception ignored) { }
            video = null;
        }
        if (web != null) { Media.destroy(web); web = null; }
        playerFrame.removeAllViews();
    }

    private void showPlaceholder(String msg) {
        clearPlayer();
        TextView t = Ui.tv(a, "▶\n" + msg, 13, 0xFFCFC9D6, false);
        t.setGravity(Gravity.CENTER);
        playerFrame.addView(t, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    void stopWithMessage(String msg) {
        if (a.curVideo != null) {
            a.curVideo = null;
            showPlaceholder(msg);
            playerTitle.setText("–");
            playerMeta.setText("");
        }
        renderList();
        updateOffControls();
    }

    void play(final String vid) {
        final Store.Course c = a.curCourse;
        if (c == null) return;
        Store.Video want = null;
        for (Store.Video v : c.videos) if (v.id.equals(vid)) want = v;
        if (want == null) return;
        if (!Store.courseAllowed(c)) {
            a.pendingVid = vid;
            a.dialogs.premiumGate(Store.isPremium() ? "noaccess" : "login", c);
            return;
        }
        final Store.Video fw = want;
        if (Store.sessionMobile != null && !Store.adminIn) {
            Store.checkSession(true, (String msg, Exception e) -> {
                if (released) return;
                if (msg != null && !msg.isEmpty()) { a.forceLogout(msg); return; }
                startPlay(fw, true);
            });
        } else {
            startPlay(fw, true);
        }
    }

    private void startPlay(Store.Video v, boolean autoplay) {
        a.curVideo = v;
        renderList();
        clearPlayer();
        String embed = Store.ytEmbed(v.url, autoplay);
        if (embed != null && VideoCache.getLocalPath(a, v.id) != null) embed = null;   // saved YouTube lesson: play the local file
        if (embed != null && !OfflineCache.online(a)) {
            showPlaceholder("This YouTube lesson isn't saved on this phone.\nConnect to the internet, or save it offline first.");
        } else if (embed == null && !v.url.isEmpty() && VideoCache.getLocalPath(a, v.id) == null && !OfflineCache.online(a)) {
            showPlaceholder("You're offline and this lesson isn't saved yet.\nTap \"Save offline\" while you have internet.");
        } else if (embed != null) {
            // YouTube, not saved yet — stream it (tap "Save offline" to keep a copy)
            web = Media.newPlayerWebView(a);
            playerFrame.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));
            Media.load(web, embed);
        } else if (!v.url.isEmpty()) {
            // Direct video — use cached file if available, else stream + auto-cache
            String local = VideoCache.getLocalPath(a, v.id);
            String playUri = (local != null) ? local : v.url;

            video = new VideoView(a);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, MATCH);
            lp.gravity = Gravity.CENTER;
            playerFrame.addView(video, lp);
            MediaController mc = new MediaController(a);
            mc.setAnchorView(video);
            video.setMediaController(mc);
            video.setVideoURI(android.net.Uri.parse(
                    local != null ? "file://" + playUri : playUri));
            video.setOnErrorListener((mp, what, extra) -> {
                a.toast("Could not play this video");
                return true;
            });
            // buffering spinner until the first frame is ready
            final android.widget.ProgressBar vSpin = new android.widget.ProgressBar(a);
            FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44));
            sLp.gravity = Gravity.CENTER;
            playerFrame.addView(vSpin, sLp);
            video.setOnPreparedListener(mp -> {
                vSpin.setVisibility(View.GONE);
                if (resumeVideo) {            // coming back from in-place fullscreen: continue where it was
                    resumeVideo = false;
                    try { if (fsPos > 0) video.seekTo(fsPos); if (fsWasPlaying) video.start(); } catch (Exception ignored) { }
                }
                mp.setOnInfoListener((m, what, extra) -> {
                    if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) vSpin.setVisibility(View.VISIBLE);
                    else if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END
                          || what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) vSpin.setVisibility(View.GONE);
                    return false;
                });
            });
            if (autoplay) video.start();

            // Auto-cache: if not downloaded yet, start downloading in background
            if (local == null && !VideoCache.isDownloading(v.id)) {
                VideoCache.startDownload(a, v.id, v.url, dl);
                offTick(v.id, false);
            }
        } else {
            showPlaceholder("No video link set for this lesson");
        }
        playerTitle.setText(v.title);
        playerMeta.setText(v.durationMinutes > 0 ? v.durationMinutes + " min" : "");
        updateOffControls();
    }

    private void openFullscreen() {
        Store.Video v = a.curVideo;
        if (v == null || v.url.isEmpty()) { a.toast("Select a lesson first"); return; }
        if (fsLayer != null) { closeFullscreen(); return; }
        final View pv = web != null ? web : video;
        if (pv == null) {                     // no live player (placeholder) -> old separate-screen fullscreen
            Intent i = new Intent(a, PlayerActivity.class);
            String local = VideoCache.getLocalPath(a, v.id);
            i.putExtra("url", local != null ? "file://" + local : v.url);
            i.putExtra("title", v.title);
            inlineSuspended = true;
            showPlaceholder("Playing in fullscreen…");
            a.startActivity(i);
            return;
        }
        saveState();

        // take every child (player + buffering spinner) out of the inline frame
        ArrayList<View> kids = new ArrayList<>();
        for (int k = 0; k < playerFrame.getChildCount(); k++) kids.add(playerFrame.getChildAt(k));
        playerFrame.removeAllViews();

        fsLayer = new FrameLayout(a);
        fsLayer.setBackgroundColor(Color.BLACK);
        for (View k : kids) {
            ViewGroup.LayoutParams lp = k.getLayoutParams();
            fsLayer.addView(k, lp != null ? lp : new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        fsClose = new TextView(a);
        fsClose.setText("✕");
        fsClose.setTextColor(Color.WHITE);
        fsClose.setTextSize(20);
        fsClose.setGravity(Gravity.CENTER);
        fsClose.setBackgroundColor(0x88000000);
        int sz = Ui.dp(a, 44);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(sz, sz);
        cl.gravity = Gravity.TOP | Gravity.END;
        cl.setMargins(0, Ui.dp(a, 8), Ui.dp(a, 8), 0);
        fsLayer.addView(fsClose, cl);
        fsClose.setOnClickListener(x -> closeFullscreen());

        // placeholder where the player used to be
        TextView t = Ui.tv(a, "▶\nPlaying in fullscreen…", 13, 0xFFCFC9D6, false);
        t.setGravity(Gravity.CENTER);
        playerFrame.addView(t, new FrameLayout.LayoutParams(MATCH, MATCH));

        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        decor.addView(fsLayer, new FrameLayout.LayoutParams(MATCH, MATCH));
        fsPrevOrientation = a.getRequestedOrientation();
        a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        hideBars(decor);
        afterMove();
    }

    private void closeFullscreen() {
        if (fsLayer == null) return;
        saveState();
        ArrayList<View> kids = new ArrayList<>();
        for (int k = 0; k < fsLayer.getChildCount(); k++) {
            View c = fsLayer.getChildAt(k);
            if (c != fsClose) kids.add(c);
        }
        fsLayer.removeAllViews();
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        decor.removeView(fsLayer);
        fsLayer = null;
        fsClose = null;

        playerFrame.removeAllViews();
        for (View k : kids) {
            ViewGroup.LayoutParams lp = k.getLayoutParams();
            playerFrame.addView(k, lp != null ? lp : new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        a.setRequestedOrientation(fsPrevOrientation);
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        afterMove();
    }

    /** Remember play state before the player view is re-parented. */
    private void saveState() {
        if (video != null) {
            try { fsPos = video.getCurrentPosition(); fsWasPlaying = video.isPlaying(); resumeVideo = true; }
            catch (Exception ignored) { }
        }
        if (web != null) web.evaluateJavascript("window.__fsPrep&&window.__fsPrep()", null);
    }

    /** Resume playback after the player view changed parent. */
    private void afterMove() {
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.__fsRestore&&window.__fsRestore()", null);
        }
        if (video != null) {
            // if the surface was not re-created, the prepared-listener never fires: just keep playing
            playerFrame.postDelayed(() -> {
                try { if (video != null && fsWasPlaying && !video.isPlaying()) video.start(); } catch (Exception ignored) { }
            }, 1500);
        }
    }

    private void hideBars(View decor) {
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    boolean onBack() {
        if (fsLayer != null) { closeFullscreen(); return true; }
        return false;
    }

    @Override
    void onResume() {
        if (inlineSuspended && a.curVideo != null && !released) {
            inlineSuspended = false;
            startPlay(a.curVideo, false);
        }
    }

    @Override
    void onPause() {
        if (web   != null) web.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }

    @Override
    void release() {
        released = true;
        Store.Course rc = a.curCourse;
        if (rc != null) for (Store.Video rv : rc.videos) if (VideoCache.isDownloading(rv.id)) VideoCache.setListener(rv.id, null);
        if (fsLayer != null) closeFullscreen();
        if (root != null) clearPlayer();
    }

    // ── Offline save: button under the player + indicator on every lesson row ───────────────

    private static boolean savable(Store.Video v) {
        return v != null && v.url != null && v.url.matches("(?i)^https?://.*");
    }

    private static String mb(long bytes) { return String.format(Locale.US, "%.1f MB", bytes / 1048576.0); }

    private static String days(int n) { return n + (n == 1 ? " day" : " days"); }

    /** Start / cancel / remove, depending on the lesson's current state. Used by the list icon and the player button. */
    private void offToggle(final Store.Video v) {
        if (v == null) { a.toast("Play a lesson first"); return; }
        if (!savable(v)) { a.toast("This lesson can't be saved offline"); return; }
        Store.Course c = a.curCourse;
        if (c == null || !Store.courseAllowed(c)) { a.toast("Log in as a Premium member to save lessons"); return; }

        if (VideoCache.isDownloading(v.id)) {                 // downloading → cancel
            if (!VideoCache.isCancelling(v.id)) VideoCache.cancel(v.id);
            offTick(v.id, false);
            return;
        }
        if (VideoCache.isDownloaded(a, v.id)) {               // saved → ask, then remove
            final Ui.Modal[] m = new Ui.Modal[1];
            m[0] = new Ui.Modal(a, "Remove offline copy?",
                "\"" + v.title + "\" will need internet again until you save it once more.");
            m[0].actions("Cancel", "Remove", Ui.B_RED, () -> {
                m[0].dismiss();
                VideoCache.delete(a, v.id);
                a.toast("Offline copy removed");
                offTick(v.id, true);
            });
            m[0].show();
            return;
        }
        if (!OfflineCache.online(a)) { a.toast("No internet — can't download now"); return; }
        a.toast(VideoCache.isYouTube(v.url) ? "Saving YouTube lesson…" : "Saving for offline…");
        VideoCache.startDownload(a, v.id, v.url, dl);
        offTick(v.id, false);
    }

    private void onOffBtn() { offToggle(a.curVideo); }

    /** Refresh the indicator of one lesson (and the player button when it is the playing lesson). */
    private void offTick(String id, boolean rebuildRow) {
        if (released || root == null) return;
        OffIcon ic = icons.get(id);
        if (ic != null) ic.bind(a, id);
        if (a.curVideo != null && a.curVideo.id.equals(id)) updateOffControls();
        if (rebuildRow) renderList();      // saved / removed: the "N days left" line under the title changes too
    }

    private void styleOffBtn(String text, int fg, int fill, int stroke, boolean dim) {
        offBtn.setText(text);
        offBtn.setTextColor(fg);
        offBtn.setBackground(Ui.bg(a, fill, 10, stroke));
        offBtn.setGravity(Gravity.CENTER);
        offBtn.setMinHeight(Ui.dp(a, 44));
        offBtn.setAlpha(dim ? 0.55f : 1f);
        Ui.pressFx(offBtn);
    }

    /** Button text/colour + the status line, exactly the exe's three states: Save offline / ⏳ 45% / ✅ Saved · 7 days. */
    private void updateOffControls() {
        if (offBtn == null) return;
        Store.Video v = a.curVideo;
        if (v == null || !savable(v)) {
            styleOffBtn("📥  Save offline", Ui.ACCENT2, Ui.BG, Ui.ACCENT, true);
            offNote.setVisibility(View.GONE);
            return;
        }
        if (VideoCache.isDownloading(v.id)) {
            String t;
            if (VideoCache.isCancelling(v.id)) t = "Cancelling…";
            else {
                int p = VideoCache.getProgress(v.id);
                long b = VideoCache.getBytes(v.id);
                t = p > 0 ? "⏳  " + p + "%" : (b > 0 ? "⏳  " + mb(b) : (VideoCache.isResolving(v.id) ? "⏳  Getting video…" : "⏳  Starting…"));
            }
            styleOffBtn(t, Ui.ACCENT2, Ui.ACCENT_SOFT, Ui.ACCENT, false);
            offNote.setText("Downloading… tap the button to cancel");
            offNote.setTextColor(Ui.SUB);
            offNote.setVisibility(View.VISIBLE);
        } else if (VideoCache.isDownloaded(a, v.id)) {
            int d = Math.max(1, VideoCache.daysLeft(a, v.id));
            styleOffBtn("✅  Saved · " + days(d), COL_SAVED, 0x1A16A34A, COL_SAVED, false);
            offNote.setText("Saved on this phone (" + mb(VideoCache.sizeOf(a, v.id)) + ") — plays without internet, removes itself in " + days(d));
            offNote.setTextColor(COL_SAVED);
            offNote.setVisibility(View.VISIBLE);
        } else {
            styleOffBtn("📥  Save offline", Ui.ACCENT2, Ui.BG, Ui.ACCENT, false);
            offNote.setVisibility(View.GONE);
        }
    }

    // ── Lessons list ─────────────────────────────────────────────────────────

    private void renderList() {
        listWrap.removeAllViews();
        icons.clear();
        Store.Course c = a.curCourse;
        if (c == null || c.videos.isEmpty()) {
            TextView t = Ui.tv(a, "No lessons yet", 13.5f, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            listWrap.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 28, 0, 0));
            return;
        }
        boolean can = Store.courseAllowed(c);
        for (int i = 0; i < c.videos.size(); i++) {
            final Store.Video v = c.videos.get(i);
            boolean playing = a.curVideo != null && a.curVideo.id.equals(v.id);
            boolean saved = can && savable(v) && VideoCache.isDownloaded(a, v.id);

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10));
            row.setBackground(Ui.bg(a, playing ? Ui.ACCENT_SOFT : Ui.BG, 14, playing ? Ui.ACCENT : 0x1A000000));
            Ui.pressFx(row);
            row.setOnClickListener(x -> play(v.id));

            // ── number / play indicator ──
            TextView num = Ui.tv(a, playing ? "▶" : String.valueOf(i + 1), 12.5f,
                    playing ? Ui.WHITE : Ui.SUB, true);
            num.setGravity(Gravity.CENTER);
            num.setBackground(Ui.circle(playing ? Ui.ACCENT : Ui.CARD2));
            row.addView(num, Ui.lpm(a, Ui.dp(a, 30), Ui.dp(a, 30), 0, 0, 12, 0));

            // ── title + duration (+ saved info) ──
            LinearLayout txt = new LinearLayout(a);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.addView(Ui.ellipsized(a, v.title, 14, Ui.TEXT, playing, 2));
            String meta = v.durationMinutes > 0 ? v.durationMinutes + " min" : "";
            if (saved) meta += (meta.isEmpty() ? "" : "  ·  ") + "Offline · " + VideoCache.daysLeft(a, v.id) + "d left";
            if (!meta.isEmpty()) txt.addView(Ui.tv(a, meta, 11.5f, saved ? COL_SAVED : Ui.SUB, false));
            row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));

            if (!can) {
                // ── lock icon (non-member) ──
                row.addView(Ui.tv(a, "🔒", 15, Ui.SUB, false));
            } else if (savable(v)) {
                // ── offline indicator: ☁ save · ring while downloading · ✓ saved (YouTube and direct videos) ──
                OffIcon ic = new OffIcon(a);
                ic.bind(a, v.id);
                ic.setOnClickListener(x -> offToggle(v));
                icons.put(v.id, ic);
                row.addView(ic, Ui.lpm(a, Ui.dp(a, 40), Ui.dp(a, 40), 4, 0, 0, 0));
                // a download that was started on an earlier visit keeps running: listen to it again
                if (VideoCache.isDownloading(v.id)) VideoCache.setListener(v.id, dl);
            }

            listWrap.addView(row, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
    }
}
