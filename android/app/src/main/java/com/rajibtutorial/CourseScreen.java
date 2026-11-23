package com.rajibtutorial;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import java.util.ArrayList;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    // ── offline indicator colours (OneDrive-style) ──────────────
    // Green circle  = fully downloaded / available offline
    // Grey circle   = not downloaded
    // Blue spinner  = downloading now
    private static final int COL_OFFLINE_OK  = 0xFF2EAD6C; // green
    private static final int COL_OFFLINE_NO  = 0x40FFFFFF;  // faint white ring
    private static final int COL_DL_ACTIVE   = 0xFF4A9EFF;  // blue

    private final NativeHomeActivity a;
    private LinearLayout root, listWrap;
    private FrameLayout playerFrame;
    private TextView cpTitle, cpSub, playerTitle, playerMeta, buyBtn;
    private WebView web;
    private VideoView video;
    private boolean released      = false;
    private boolean inlineSuspended = false;
    // in-place fullscreen: the SAME player view is moved into fsLayer (no re-create => no re-buffering)
    private FrameLayout fsLayer;
    private TextView fsClose;
    private int fsPrevOrientation;
    private boolean resumeVideo = false, fsWasPlaying = false;
    private int fsPos = 0;

    CourseScreen(NativeHomeActivity host) { a = host; }

    @Override
    View build() {
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // header
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(Ui.BG2);
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

        // player (16:9)
        playerFrame = new FrameLayout(a);
        playerFrame.setBackgroundColor(0xFF14121A);
        int w = a.getResources().getDisplayMetrics().widthPixels;
        root.addView(playerFrame, new LinearLayout.LayoutParams(MATCH, w * 9 / 16));

        // player info
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        LinearLayout tt = new LinearLayout(a);
        tt.setOrientation(LinearLayout.VERTICAL);
        playerTitle = Ui.ellipsized(a, "–", 14.5f, Ui.TEXT, true, 2);
        playerMeta  = Ui.tv(a, "", 12, Ui.SUB, false);
        tt.addView(playerTitle);
        tt.addView(playerMeta);
        info.addView(tt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        info.addView(Ui.smallBtn(a, "⛶  Fullscreen", Ui.ACCENT, v -> openFullscreen()));
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
        showPlaceholder("Select a lesson to start watching");
        renderList();
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
            showPlaceholder("YouTube lessons need an internet connection");
        } else if (embed == null && !v.url.isEmpty() && VideoCache.getLocalPath(a, v.id) == null && !OfflineCache.online(a)) {
            showPlaceholder("You're offline and this lesson isn't saved yet.\nOpen it once with internet to save it.");
        } else if (embed != null) {
            // YouTube — stream only, no local cache possible
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
                VideoCache.startDownload(a, v.id, v.url,
                        (videoId, percent) -> {
                            if (!released) renderList(); // refresh icons
                        });
            }
        } else {
            showPlaceholder("No video link set for this lesson");
        }
        playerTitle.setText(v.title);
        playerMeta.setText(v.durationMinutes > 0 ? v.durationMinutes + " min" : "");
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
        if (fsLayer != null) closeFullscreen();
        if (root != null) clearPlayer();
    }

    // ── Lessons list ─────────────────────────────────────────────────────────

    private void renderList() {
        listWrap.removeAllViews();
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
            boolean isYT    = VideoCache.isYouTube(v.url);

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
            row.setBackground(Ui.bg(a, playing ? Ui.ACCENT_SOFT : Ui.BG, 14, playing ? Ui.ACCENT : 0x1A000000));
            Ui.pressFx(row);
            row.setOnClickListener(x -> play(v.id));

            // ── number / play indicator ──────────────────────────────────────
            TextView num = Ui.tv(a, playing ? "▶" : String.valueOf(i + 1), 12.5f,
                    playing ? Ui.WHITE : Ui.SUB, true);
            num.setGravity(Gravity.CENTER);
            num.setBackground(Ui.circle(playing ? Ui.ACCENT : Ui.CARD2));
            row.addView(num, Ui.lpm(a, Ui.dp(a, 30), Ui.dp(a, 30), 0, 0, 12, 0));

            // ── title + duration ─────────────────────────────────────────────
            LinearLayout txt = new LinearLayout(a);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.addView(Ui.ellipsized(a, v.title, 14, Ui.TEXT, playing, 2));
            if (v.durationMinutes > 0)
                txt.addView(Ui.tv(a, v.durationMinutes + " min", 11.5f, Ui.SUB, false));
            row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));

            // ── lock icon (non-member) ───────────────────────────────────────
            if (!can) {
                row.addView(Ui.tv(a, "🔒", 15, Ui.SUB, false));
            }

            // ── offline / download indicator (only for direct video, not YouTube) ──
            if (!v.url.isEmpty() && can) {
                boolean downloaded  = VideoCache.isDownloaded(a, v.id);
                boolean downloading = VideoCache.isDownloading(v.id);
                int     progress    = VideoCache.getProgress(v.id);

                if (downloading) {
                    // Blue arc progress bar
                    ProgressBar pb = new ProgressBar(a, null,
                            android.R.attr.progressBarStyleHorizontal);
                    pb.setMax(100);
                    pb.setProgress(Math.max(progress, 0));
                    pb.setIndeterminate(progress <= 0);
                    // tint blue
                    if (android.os.Build.VERSION.SDK_INT >= 21) {
                        pb.getProgressDrawable()
                          .setColorFilter(COL_DL_ACTIVE,
                                  android.graphics.PorterDuff.Mode.SRC_IN);
                        pb.getIndeterminateDrawable()
                          .setColorFilter(COL_DL_ACTIVE,
                                  android.graphics.PorterDuff.Mode.SRC_IN);
                    }
                    int pbSize = Ui.dp(a, 20);
                    LinearLayout.LayoutParams pbLp =
                            new LinearLayout.LayoutParams(Ui.dp(a, 28), pbSize);
                    pbLp.setMargins(Ui.dp(a, 8), 0, 0, 0);
                    row.addView(pb, pbLp);

                    // percent label e.g. "73%"
                    if (progress > 0) {
                        TextView pct = Ui.tv(a, progress + "%", 10, COL_DL_ACTIVE, false);
                        pct.setPadding(Ui.dp(a, 2), 0, 0, 0);
                        row.addView(pct);
                    }
                } else {
                    // Icon: green ✓ = saved offline (works without internet), faint ⬇ = not saved yet
                    TextView dot = Ui.tv(a, downloaded ? "✓" : "⬇", 12, Ui.WHITE, true);
                    dot.setGravity(Gravity.CENTER);
                    dot.setBackground(Ui.circle(downloaded ? COL_OFFLINE_OK : COL_OFFLINE_NO));
                    int dotSize = Ui.dp(a, 24);
                    LinearLayout.LayoutParams dotLp =
                            new LinearLayout.LayoutParams(dotSize, dotSize);
                    dotLp.setMargins(Ui.dp(a, 10), 0, Ui.dp(a, 2), 0);
                    dotLp.gravity = Gravity.CENTER_VERTICAL;
                    row.addView(dot, dotLp);
                    if (downloaded) {
                        TextView left = Ui.tv(a, VideoCache.daysLeft(a, v.id) + "d", 10, COL_OFFLINE_OK, false);
                        left.setPadding(Ui.dp(a, 2), 0, 0, 0);
                        row.addView(left);
                    }

                    // Long-press to delete, tap to download
                    final String vid = v.id;
                    final String url = v.url;
                    dot.setOnClickListener(x -> {
                        if (VideoCache.isDownloaded(a, vid)) {
                            // already downloaded — offer delete
                            VideoCache.delete(a, vid);
                            a.toast("Offline copy removed");
                            renderList();
                        } else {
                            a.toast("Downloading for offline…");
                            VideoCache.startDownload(a, vid, url,
                                    (id, pct) -> {
                                        if (released) return;
                                        if (pct < 0) a.toast(VideoCache.lastError(id));
                                        renderList();
                                    });
                            renderList();
                        }
                    });
                }
            }

            listWrap.addView(row, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
    }
}
