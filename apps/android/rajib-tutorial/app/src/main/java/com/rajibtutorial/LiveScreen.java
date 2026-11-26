package com.rajibtutorial;

import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Live Class / Live Exam page. */
final class LiveScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final NativeHomeActivity a;
    private final boolean exam;
    private ScrollView sv;
    private WebView web;

    LiveScreen(NativeHomeActivity host, boolean exam) { a = host; this.exam = exam; }

    @Override
    View build() {
        sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG);
        render();
        return sv;
    }

    @Override
    void refresh() { if (sv != null) render(); }

    @Override
    void release() { Media.destroy(web); web = null; }

    @Override
    void onPause() { if (web != null) web.onPause(); }

    private void render() {
        Media.destroy(web);
        web = null;
        sv.removeAllViews();
        final String name = exam ? "Live Exam" : "Live Class";
        final String url = Store.safeUrl(exam ? Store.live.examUrl : Store.live.classUrl);
        final boolean on = !url.isEmpty();
        String title = exam ? Store.live.examTitle : Store.live.classTitle;
        if (title.isEmpty()) title = name;
        String time = exam ? Store.live.examTime : Store.live.classTime;

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout hero = new LinearLayout(a);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackgroundColor(0xFFF8F4F1);
        hero.setPadding(Ui.dp(a, 18), Ui.dp(a, 18), Ui.dp(a, 18), Ui.dp(a, 22));
        root.addView(hero, new LinearLayout.LayoutParams(MATCH, WRAP));

        // tabs
        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(Ui.bg(a, Ui.CARD2, 14, 0));
        tabs.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        tabs.addView(tab("🎥  Live Class", !exam, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        tabs.addView(tab("📝  Live Exam", exam, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        hero.addView(tabs, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 16));

        TextView st = Ui.pill(a, on ? "● LIVE NOW" : "NOT SCHEDULED", on ? Ui.WHITE : Ui.SUB, on ? Ui.RED : Ui.CARD2);
        hero.addView(st, new LinearLayout.LayoutParams(WRAP, WRAP));
        hero.addView(Ui.tv(a, title, 22, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 6));
        TextView lead = Ui.tv(a, exam
            ? "Test your preparation under real exam conditions and get ready for your finals."
            : "Learn together with your instructor in real time, ask questions and clear your doubts instantly.", 13.5f, Ui.SUB, false);
        lead.setLineSpacing(0, 1.2f);
        hero.addView(lead);
        if (!time.isEmpty()) hero.addView(Ui.tv(a, "🕒  " + time, 13.5f, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        if (on) {
            hero.addView(Ui.btn(a, "Join " + name + "  ↗", Ui.B_PRIMARY, v -> {
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { a.toast("Could not open the link"); }
            }), Ui.lpm(a, MATCH, WRAP, 0, 16, 0, 0));
        } else {
            hero.addView(Ui.btn(a, "Browse recorded courses", Ui.B_OUTLINE, v -> a.goHome()), Ui.lpm(a, MATCH, WRAP, 0, 16, 0, 0));
        }

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 24));
        root.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));

        body.addView(infoCard("📡", "Status", on ? "Open for joining" : "Nothing scheduled"), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        body.addView(infoCard("🕒", "Schedule", time.isEmpty() ? "To be announced" : time), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        body.addView(infoCard("🌐", "Platform", on ? Store.platformOf(url) : "Not available yet"), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        String embed = on ? Store.ytEmbed(url, false) : null;
        if (embed != null) {
            FrameLayout frame = new FrameLayout(a);
            frame.setBackgroundColor(Ui.TEXT);
            int w = a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 32);
            web = Media.newPlayerWebView(a);
            frame.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));
            body.addView(frame, Ui.lpm(a, MATCH, w * 9 / 16, 0, 0, 0, 14));
            Media.load(web, embed);
        }
        if (!on) {
            LinearLayout empty = Ui.card(a);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.addView(Ui.tv(a, "🎥", 30, Ui.SUB, false));
            empty.addView(Ui.tv(a, "No " + name.toLowerCase() + " right now", 16, Ui.TEXT, true), Ui.lpm(a, WRAP, WRAP, 0, 6, 0, 4));
            TextView p = Ui.tv(a, "There is no " + name.toLowerCase() + " scheduled at the moment. Please check back later, or watch the recorded courses in the meantime.", 13, Ui.SUB, false);
            p.setGravity(Gravity.CENTER);
            empty.addView(p);
            body.addView(empty, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));
        }

        body.addView(Ui.tv(a, on ? "Before you join" : "When a session is scheduled", 17, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 4, 0, 8));
        String[][] tips = exam ? new String[][]{
            {"Stable internet", "Use a reliable connection and keep your phone or laptop fully charged."},
            {"Be ready early", "Join about 10 minutes before the start time so you can settle in."},
            {"Keep materials handy", "Have a pen, paper and a calculator ready before the exam begins."},
            {"Stay in the session", "Do not refresh or leave the page once the exam has started."}
        } : new String[][]{
            {"Stable internet", "A steady connection gives you smooth video and audio."},
            {"Join on time", "Open the session 5 minutes early so you do not miss the beginning."},
            {"Prepare your notes", "Keep a notebook and pen ready to write down key points."},
            {"Ask questions", "Note down your doubts and ask the instructor during the class."}
        };
        for (int i = 0; i < tips.length; i++) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
            TextView n = Ui.tv(a, String.valueOf(i + 1), 12.5f, Ui.ACCENT, true);
            n.setGravity(Gravity.CENTER);
            n.setBackground(Ui.circle(Ui.ACCENT_SOFT));
            row.addView(n, Ui.lpm(a, Ui.dp(a, 28), Ui.dp(a, 28), 0, 0, 12, 0));
            LinearLayout t = new LinearLayout(a);
            t.setOrientation(LinearLayout.VERTICAL);
            t.addView(Ui.tv(a, tips[i][0], 14, Ui.TEXT, true));
            t.addView(Ui.tv(a, tips[i][1], 12.5f, Ui.SUB, false));
            row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
            body.addView(row);
        }
    }

    private View tab(String text, boolean on, final boolean toExam) {
        TextView t = Ui.tv(a, text, 13.5f, on ? Ui.WHITE : Ui.SUB, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10));
        if (on) t.setBackground(Ui.bg(a, Ui.ACCENT, 11, 0));
        Ui.pressFx(t);
        t.setOnClickListener(v -> { if (toExam != exam) a.openLive(toExam); });
        return t;
    }

    private View infoCard(String icon, String label, String value) {
        LinearLayout c = Ui.card(a);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = Ui.tv(a, icon, 17, Ui.ACCENT, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.circle(Ui.ACCENT_SOFT));
        c.addView(ic, Ui.lpm(a, Ui.dp(a, 40), Ui.dp(a, 40), 0, 0, 12, 0));
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.tv(a, label, 11.5f, Ui.SUB, false));
        t.addView(Ui.tv(a, value, 14.5f, Ui.TEXT, true));
        c.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        return c;
    }
}
