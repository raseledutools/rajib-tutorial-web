package com.rajibtutorial;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import android.net.Uri;

/** Course page: lesson list + inline player (YouTube embed or direct video). */
final class CourseScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final NativeHomeActivity a;
    private LinearLayout root, listWrap;
    private FrameLayout playerFrame;
    private TextView cpTitle, cpSub, playerTitle, playerMeta;
    private WebView web;
    private VideoView video;
    private boolean released = false;
    private boolean inlineSuspended = false;

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
        cpSub = Ui.tv(a, "", 12.5f, Ui.SUB, false);
        head.addView(cpTitle);
        head.addView(cpSub);
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
        playerMeta = Ui.tv(a, "", 12, Ui.SUB, false);
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
        renderList();
    }

    // ── Player ──────────────────────────────────────────────
    private void clearPlayer() {
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

    /** Called when the session is revoked while a video is open. */
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
            a.dialogs.premiumGate(Store.isPremium() ? "noaccess" : "login");
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
        if (embed != null) {
            web = Media.newPlayerWebView(a);
            playerFrame.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));
            Media.load(web, embed);
        } else if (!v.url.isEmpty()) {
            video = new VideoView(a);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, MATCH);
            lp.gravity = Gravity.CENTER;
            playerFrame.addView(video, lp);
            MediaController mc = new MediaController(a);
            mc.setAnchorView(video);
            video.setMediaController(mc);
            video.setVideoURI(Uri.parse(v.url));
            video.setOnErrorListener((mp, what, extra) -> {
                a.toast("Could not play this video");
                return true;
            });
            if (autoplay) video.start();
        } else {
            showPlaceholder("No video link set for this lesson");
        }
        playerTitle.setText(v.title);
        playerMeta.setText(v.durationMinutes > 0 ? v.durationMinutes + " min" : "");
    }

    private void openFullscreen() {
        Store.Video v = a.curVideo;
        if (v == null || v.url.isEmpty()) { a.toast("Select a lesson first"); return; }
        Intent i = new Intent(a, PlayerActivity.class);
        i.putExtra("url", v.url);
        i.putExtra("title", v.title);
        inlineSuspended = true;
        showPlaceholder("Playing in fullscreen…");
        a.startActivity(i);
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
        if (web != null) web.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }

    @Override
    void release() {
        released = true;
        if (root != null) clearPlayer();
    }

    // ── Lessons list ────────────────────────────────────────
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
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
            row.setBackground(Ui.bg(a, playing ? Ui.ACCENT_SOFT : Ui.BG, 14, playing ? Ui.ACCENT : 0x1A000000));
            Ui.pressFx(row);
            row.setOnClickListener(x -> play(v.id));

            TextView num = Ui.tv(a, playing ? "▶" : String.valueOf(i + 1), 12.5f, playing ? Ui.WHITE : Ui.SUB, true);
            num.setGravity(Gravity.CENTER);
            num.setBackground(Ui.circle(playing ? Ui.ACCENT : Ui.CARD2));
            row.addView(num, Ui.lpm(a, Ui.dp(a, 30), Ui.dp(a, 30), 0, 0, 12, 0));

            LinearLayout txt = new LinearLayout(a);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.addView(Ui.ellipsized(a, v.title, 14, Ui.TEXT, playing, 2));
            if (v.durationMinutes > 0) txt.addView(Ui.tv(a, v.durationMinutes + " min", 11.5f, Ui.SUB, false));
            row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));
            if (!can) row.addView(Ui.tv(a, "🔒", 15, Ui.SUB, false));
            listWrap.addView(row, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
    }
}
