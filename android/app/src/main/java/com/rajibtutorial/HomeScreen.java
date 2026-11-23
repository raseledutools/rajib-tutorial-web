package com.rajibtutorial;

import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Home — mirrors the web / exe home: hero, live cards, course catalog (categories + semesters), free resources, FAQ. */
final class HomeScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int[] PALETTE = {0xFFE8365D, 0xFF6D4AF0, 0xFF0E9F8E, 0xFF2563EB};
    private static final String[] ICONS = {"⚡", "🔌", "⚙", "〰"};
    private static final String[][] FAQ = {
        {"What is Rajib Tutorial and who is it for?", "Rajib Tutorial is built for Diploma in Electrical & Electronic Engineering (EEE) students. It brings video lessons for all 8 semesters, live classes and exams, a question bank and MCQ practice together in one place."},
        {"How do I get started?", "Choose a semester from the course catalog above, open a course and watch the video lessons in order. You can also use the search box to find a course quickly."},
        {"How do I join live classes and exams?", "When a session is live, the Live Class or Live Exam card shows a red LIVE badge. Tap the card to join the class or start the exam. Results and rankings appear at the scheduled time after the exam ends."},
        {"How does MCQ Practice work?", "MCQ Practice has three modes — Practice, Read and Exam. You can also bookmark questions, use night mode and set a timer."},
        {"Can I watch videos without internet?", "Yes. In the mobile app or desktop version you can save lessons for offline use, then watch them from Offline Lessons."}
    };

    private final NativeHomeActivity a;
    private ScrollView sv;
    private TextView pillClass, pillExam, liveInfo, statC, statV, cnt, premT, premS;
    private LinearLayout listWrap, catBar, semBar;
    private HorizontalScrollView semScroll;
    private View anchor;
    private String filterCat = "", filterSem = "", searchQ = "";

    HomeScreen(NativeHomeActivity host) { a = host; }

    // ── helpers ─────────────────────────────────────────────
    private TextView badge(String t) {
        TextView b = Ui.tv(a, t, 12, Ui.ACCENT2, true);
        b.setBackground(Ui.bg(a, Ui.ACCENT_SOFT, 20, 0));
        b.setPadding(Ui.dp(a, 12), Ui.dp(a, 5), Ui.dp(a, 12), Ui.dp(a, 5));
        return b;
    }

    private TextView chip(String text, boolean on, View.OnClickListener l) {
        TextView c = Ui.tv(a, text, 13, on ? Ui.ACCENT2 : Ui.TEXT, true);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.bg(a, on ? Ui.ACCENT_SOFT : Ui.BG, 18, on ? Ui.ACCENT : 0x33000000));
        c.setPadding(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8));
        Ui.pressFx(c);
        c.setOnClickListener(l);
        return c;
    }

    private GradientDrawable topRound(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = Ui.dp(a, 16);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    private HorizontalScrollView hscroll(LinearLayout inner) {
        HorizontalScrollView h = new HorizontalScrollView(a);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(inner, new ViewGroup.LayoutParams(WRAP, WRAP));
        return h;
    }

    // ── build ───────────────────────────────────────────────
    @Override
    View build() {
        sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        // ── Hero ──
        LinearLayout hero = new LinearLayout(a);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackgroundColor(0xFFFFF5F8);
        hero.setPadding(Ui.dp(a, 18), Ui.dp(a, 24), Ui.dp(a, 18), Ui.dp(a, 22));
        root.addView(hero, new LinearLayout.LayoutParams(MATCH, WRAP));

        hero.addView(badge("Diploma in EEE"), new LinearLayout.LayoutParams(WRAP, WRAP));
        String full = "Learn EEE the Smart & Simple Way";
        String hl = "Smart & Simple Way";
        SpannableString ss = new SpannableString(full);
        int s0 = full.indexOf(hl);
        ss.setSpan(new ForegroundColorSpan(Ui.ACCENT), s0, s0 + hl.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        hero.addView(Ui.tv(a, ss, 28, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 12, 0, 0));
        TextView p = Ui.tv(a, "Video lessons, live classes and exams, a question bank and MCQ practice — complete preparation for all 8 semesters, right at your fingertips.", 14, Ui.SUB, false);
        p.setLineSpacing(0, 1.2f);
        hero.addView(p, Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 16));
        hero.addView(Ui.btn(a, "Browse Courses", Ui.B_PRIMARY, v -> sv.post(() -> sv.smoothScrollTo(0, anchor.getTop()))),
            Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 16));

        LinearLayout box = Ui.card(a);
        LinearLayout brandRow = new LinearLayout(a);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = Ui.tv(a, "✳", 24, Ui.ACCENT, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.circle(Ui.ACCENT_SOFT));
        brandRow.addView(logo, Ui.lpm(a, Ui.dp(a, 52), Ui.dp(a, 52), 0, 0, 12, 0));
        LinearLayout bt = new LinearLayout(a);
        bt.setOrientation(LinearLayout.VERTICAL);
        bt.addView(Ui.tv(a, "Rajib Tutorial", 17, Ui.TEXT, true));
        bt.addView(Ui.tv(a, "Diploma in EEE", 12, Ui.SUB, false));
        brandRow.addView(bt);
        box.addView(brandRow);
        LinearLayout stats = new LinearLayout(a);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        statC = Ui.tv(a, "–", 22, Ui.TEXT, true);
        statV = Ui.tv(a, "–", 22, Ui.TEXT, true);
        stats.addView(statCell(statC, "Courses"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        stats.addView(statCell(statV, "Video Lessons"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        stats.addView(statCell(Ui.tv(a, "8", 22, Ui.TEXT, true), "Semesters"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        box.addView(stats, Ui.lpm(a, MATCH, WRAP, 0, 16, 0, 0));
        hero.addView(box, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ── Live classes & exams ──
        LinearLayout live = new LinearLayout(a);
        live.setOrientation(LinearLayout.VERTICAL);
        live.setPadding(Ui.dp(a, 16), Ui.dp(a, 22), Ui.dp(a, 16), 0);
        live.addView(Ui.tv(a, "Live Classes & Exams", 19, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        LinearLayout lrow = new LinearLayout(a);
        lrow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        l1.setMargins(0, 0, Ui.dp(a, 10), 0);
        lrow.addView(liveCard(false), l1);
        lrow.addView(liveCard(true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        live.addView(lrow, new LinearLayout.LayoutParams(MATCH, WRAP));
        liveInfo = Ui.tv(a, "", 12.5f, Ui.SUB, false);
        live.addView(liveInfo, Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 0));
        root.addView(live, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ── Course catalog ──
        LinearLayout sec = new LinearLayout(a);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setPadding(Ui.dp(a, 16), Ui.dp(a, 22), Ui.dp(a, 16), Ui.dp(a, 22));
        root.addView(sec, new LinearLayout.LayoutParams(MATCH, WRAP));
        anchor = sec;
        sec.addView(Ui.tv(a, "Course Catalog", 19, Ui.TEXT, true));
        cnt = Ui.tv(a, "", 12.5f, Ui.SUB, false);
        sec.addView(cnt, Ui.lpm(a, WRAP, WRAP, 0, 2, 0, 10));

        EditText search = new EditText(a);
        search.setHint("Search courses");
        search.setTextSize(14);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(0xFFA39C93);
        search.setSingleLine(true);
        search.setBackground(Ui.bg(a, Ui.BG, 12, 0x33000000));
        search.setPadding(Ui.dp(a, 14), Ui.dp(a, 11), Ui.dp(a, 14), Ui.dp(a, 11));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence x, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                searchQ = e.toString().trim().toLowerCase(Locale.getDefault());
                renderList();
            }
        });
        sec.addView(search, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));

        catBar = new LinearLayout(a);
        catBar.setOrientation(LinearLayout.HORIZONTAL);
        sec.addView(hscroll(catBar), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        semBar = new LinearLayout(a);
        semBar.setOrientation(LinearLayout.HORIZONTAL);
        semBar.setGravity(Gravity.CENTER_VERTICAL);
        semScroll = hscroll(semBar);
        semScroll.setVisibility(View.GONE);
        sec.addView(semScroll, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));

        listWrap = new LinearLayout(a);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        sec.addView(listWrap, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ── Free resources band ──
        LinearLayout band = new LinearLayout(a);
        band.setOrientation(LinearLayout.VERTICAL);
        band.setBackgroundColor(Ui.BG2);
        band.setPadding(Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 20));
        band.addView(badge("Free Resources"), new LinearLayout.LayoutParams(WRAP, WRAP));
        band.addView(Ui.tv(a, "All Your Study Resources in One Place", 20, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        band.addView(Ui.tv(a, "Circulars, question bank, MCQ practice and much more", 13, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 4, 0, 14));
        premT = Ui.tv(a, "Go Premium", 14, Ui.TEXT, true);
        premS = Ui.tv(a, "Unlock all features", 11.5f, Ui.SUB, false);
        View[] tiles = {
            tile("📢", "Current Circular", "Latest notices and circulars", false, null, null, v -> a.openResources(Store.R_CIRCULAR)),
            tile("📚", "Question Bank", "Past questions with solutions", false, null, null, v -> a.openResources(Store.R_QBANK)),
            tile("🆕", "Recent Questions", "Questions from recent exams", false, null, null, v -> a.openResources(Store.R_RECENT)),
            tile("📝", "MCQ Practice", "Practice, Read and Exam modes", false, null, null, v -> a.openQb()),
            tile("💬", "Discussion", "Ask questions and join discussions", true, null, null, v -> a.openDiscussion()),
            tile("🔔", "Notifications", "New updates and announcements", false, null, null, v -> a.openInbox()),
            tile("📥", "Offline Lessons", "Watch saved lessons offline", false, null, null, v -> a.openOffline()),
            tile("⭐", "", "", false, premT, premS, v -> {
                if (Store.isPremium() || Store.adminIn) a.toast("Premium is active"); else a.dialogs.pay();
            })
        };
        for (int i = 0; i < tiles.length; i += 2) {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams ta = new LinearLayout.LayoutParams(0, MATCH, 1f);
            ta.setMargins(0, 0, Ui.dp(a, 10), 0);
            r.addView(tiles[i], ta);
            r.addView(tiles[i + 1], new LinearLayout.LayoutParams(0, MATCH, 1f));
            band.addView(r, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        }
        root.addView(band, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ── FAQ ──
        LinearLayout faq = new LinearLayout(a);
        faq.setOrientation(LinearLayout.VERTICAL);
        faq.setPadding(Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 8));
        faq.addView(badge("Common Questions"), new LinearLayout.LayoutParams(WRAP, WRAP));
        faq.addView(Ui.tv(a, "FAQ", 20, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 12));
        for (String[] f : FAQ) faq.addView(faqItem(f[0], f[1]), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        root.addView(faq, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ── Footer ──
        TextView foot = Ui.tv(a, "© " + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            + " Rajib Tutorial. All rights reserved.\nDiploma in Electrical & Electronic Engineering", 11.5f, Ui.SUB, false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, Ui.lpm(a, MATCH, WRAP, 0, 16, 0, 24));

        refresh();
        return sv;
    }

    private View liveCard(final boolean exam) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.bg(a, Ui.BG, 16, Ui.BORDER));
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(topRound(exam ? 0xFF1E1B18 : Ui.ACCENT));
        head.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        head.addView(Ui.tv(a, exam ? "Live Exam" : "Live Class", 14, Ui.WHITE, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(Ui.tv(a, exam ? "✔" : "▶", 14, Ui.WHITE, true));
        c.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        body.addView(Ui.tv(a, exam ? "Exams & Results" : "Join a Class", 15, Ui.TEXT, true));
        TextView sub = Ui.tv(a, exam ? "Scheduled MCQ exams, answers and rankings" : "Find links to ongoing and upcoming classes here", 12, Ui.SUB, false);
        body.addView(sub, Ui.lpm(a, MATCH, WRAP, 0, 3, 0, 10));
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(Ui.tv(a, exam ? "Exams & results" : "Join the class", 12, Ui.ACCENT2, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView pill = Ui.pill(a, "LIVE", Ui.WHITE, Ui.RED);
        pill.setVisibility(View.GONE);
        foot.addView(pill);
        if (exam) pillExam = pill; else pillClass = pill;
        body.addView(foot);
        c.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
        Ui.pressFx(c);
        c.setOnClickListener(v -> a.openLive(exam));
        return c;
    }

    private View tile(String icon, String title, String sub, boolean pro, TextView tT, TextView tS, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(Ui.bg(a, Ui.BG, 16, Ui.BORDER));
        t.setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14));
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = Ui.tv(a, icon, 22, Ui.TEXT, false);
        top.addView(ic, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (pro) top.addView(Ui.pill(a, "PRO", Ui.ACCENT2, Ui.ACCENT_SOFT));
        t.addView(top);
        TextView title2 = tT != null ? tT : Ui.tv(a, title, 14, Ui.TEXT, true);
        TextView sub2 = tS != null ? tS : Ui.tv(a, sub, 11.5f, Ui.SUB, false);
        t.addView(title2, Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 0));
        t.addView(sub2, Ui.lpm(a, MATCH, WRAP, 0, 2, 0, 0));
        Ui.pressFx(t);
        t.setOnClickListener(l);
        return t;
    }

    private View faqItem(String q, String ans) {
        final LinearLayout c = Ui.card(a);
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(Ui.tv(a, q, 14, Ui.TEXT, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        final TextView sign = Ui.tv(a, "＋", 18, Ui.ACCENT, true);
        h.addView(sign);
        final TextView body = Ui.tv(a, ans, 13, Ui.SUB, false);
        body.setLineSpacing(0, 1.2f);
        body.setVisibility(View.GONE);
        c.addView(h);
        c.addView(body, Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 0));
        Ui.pressFx(c);
        c.setOnClickListener(v -> {
            boolean open = body.getVisibility() == View.VISIBLE;
            body.setVisibility(open ? View.GONE : View.VISIBLE);
            sign.setText(open ? "＋" : "－");
        });
        return c;
    }

    private View statCell(TextView num, String label) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        num.setGravity(Gravity.CENTER);
        l.addView(num);
        TextView t = Ui.tv(a, label, 11.5f, Ui.SUB, false);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        return l;
    }

    // ── refresh / catalog ───────────────────────────────────
    @Override
    void refresh() {
        if (sv == null) return;
        boolean cOn = !Store.safeUrl(Store.live.classUrl).isEmpty();
        boolean eOn = !Store.safeUrl(Store.live.examUrl).isEmpty();
        pillClass.setVisibility(cOn ? View.VISIBLE : View.GONE);
        pillExam.setVisibility(eOn ? View.VISIBLE : View.GONE);
        StringBuilder sb = new StringBuilder();
        if (cOn && (!Store.live.classTitle.isEmpty() || !Store.live.classTime.isEmpty())) {
            sb.append("Live Class: ").append(Store.live.classTitle.isEmpty() ? "Scheduled" : Store.live.classTitle);
            if (!Store.live.classTime.isEmpty()) sb.append(" · ").append(Store.live.classTime);
        }
        if (eOn && (!Store.live.examTitle.isEmpty() || !Store.live.examTime.isEmpty())) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Live Exam: ").append(Store.live.examTitle.isEmpty() ? "Scheduled" : Store.live.examTitle);
            if (!Store.live.examTime.isEmpty()) sb.append(" · ").append(Store.live.examTime);
        }
        liveInfo.setText(sb.toString());
        liveInfo.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);

        boolean prem = Store.isPremium() || Store.adminIn;
        premT.setText(prem ? "Premium Active" : "Go Premium");
        premS.setText(prem ? "All features unlocked" : "Unlock all features");

        int tv = 0;
        for (Store.Course c : Store.courses) tv += c.videos.size();
        statC.setText(a.loading ? "–" : String.valueOf(Store.courses.size()));
        statV.setText(a.loading ? "–" : String.valueOf(tv));
        buildBars();
        renderList();
    }

    private int countCat(String id) {
        int n = 0;
        for (Store.Course c : Store.courses) if (c.category.equals(id)) n++;
        return n;
    }

    private void buildBars() {
        catBar.removeAllViews();
        catBar.addView(chip("🗂️ All  " + Store.courses.size(), filterCat.isEmpty(), v -> {
            filterCat = "";
            filterSem = "";
            buildBars();
            renderList();
        }), Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        for (final String[] k : Store.CATS) {
            catBar.addView(chip(k[3] + " " + k[1] + "  " + countCat(k[0]), filterCat.equals(k[0]), v -> {
                filterCat = k[0];
                if (!"diploma".equals(k[0])) filterSem = "";
                buildBars();
                renderList();
            }), Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        }
        boolean dip = "diploma".equals(filterCat);
        semScroll.setVisibility(dip ? View.VISIBLE : View.GONE);
        semBar.removeAllViews();
        if (!dip) return;
        TextView lbl = Ui.tv(a, "Semester", 12, Ui.SUB, true);
        semBar.addView(lbl, Ui.lpm(a, WRAP, WRAP, 0, 0, 10, 0));
        semBar.addView(chip("All", filterSem.isEmpty(), v -> { filterSem = ""; buildBars(); renderList(); }),
            Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        LinkedHashSet<String> sems = new LinkedHashSet<>();
        for (int i = 0; i < 7; i++) sems.add(Store.SEMESTERS[i]);
        for (Store.Course c : Store.courses) {
            if ("diploma".equals(c.category) && !c.className.isEmpty()) sems.add(c.className);
        }
        for (final String x : sems) {
            int n = 0;
            for (Store.Course c : Store.courses) if ("diploma".equals(c.category) && x.equals(c.className)) n++;
            semBar.addView(chip(x.replace(" Semester", " Sem") + "  " + n, filterSem.equals(x), v -> {
                filterSem = x;
                buildBars();
                renderList();
            }), Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        }
    }

    private void sortBySem(List<Store.Course> l) {
        Collections.sort(l, (x, y) -> Integer.compare(Store.semRank(x.className), Store.semRank(y.className)));
    }

    private void renderList() {
        listWrap.removeAllViews();
        if (a.loading) {
            ProgressBar pb = new ProgressBar(a);
            listWrap.addView(pb, Ui.lpm(a, Ui.dp(a, 36), Ui.dp(a, 36), 0, 20, 0, 6));
            ((LinearLayout.LayoutParams) pb.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
            TextView t = Ui.tv(a, "Loading courses…", 13, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            listWrap.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
            cnt.setText("");
            return;
        }
        if (a.loadFailed) {
            TextView t = Ui.tv(a, "We could not load the courses at the moment.\nPlease check your internet connection and try again.", 13.5f, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            listWrap.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 20, 0, 6));
            if (!a.loadError.isEmpty()) {
                TextView er = Ui.tv(a, a.loadError, 11, 0xFFA39C93, false);
                er.setGravity(Gravity.CENTER);
                listWrap.addView(er, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));
            }
            listWrap.addView(Ui.btn(a, "Retry", Ui.B_PRIMARY, v -> a.reload()), new LinearLayout.LayoutParams(MATCH, WRAP));
            cnt.setText("");
            return;
        }
        List<Store.Course> list = new ArrayList<>();
        for (Store.Course c : Store.courses) {
            if (!filterCat.isEmpty() && !filterCat.equals(c.category)) continue;
            if (!filterSem.isEmpty() && !filterSem.equals(c.className)) continue;
            if (!searchQ.isEmpty()) {
                String hay = (c.title + " " + c.courseName + " " + c.subject + " " + c.className + " " + c.description + " "
                    + Store.catOf(c.category)[1]).toLowerCase(Locale.getDefault());
                if (!hay.contains(searchQ)) continue;
            }
            list.add(c);
        }
        cnt.setText(Store.courses.isEmpty() ? "" : list.size() + " of " + Store.courses.size() + " courses");
        if (Store.courses.isEmpty() || list.isEmpty()) {
            String m = Store.courses.isEmpty() ? "New courses will be published soon."
                : !searchQ.isEmpty() ? "No courses match your search." : "New courses in this category are coming soon.";
            TextView t = Ui.tv(a, m, 13.5f, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            listWrap.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 24, 0, 24));
            return;
        }
        int[] idx = {0};
        if (filterCat.isEmpty()) {
            for (String[] k : Store.CATS) {
                List<Store.Course> sub = new ArrayList<>();
                for (Store.Course c : list) if (k[0].equals(c.category)) sub.add(c);
                if (sub.isEmpty()) {
                    if (!searchQ.isEmpty()) continue;
                    listWrap.addView(section(k[3], k[1], k[2], 0));
                    TextView soon = Ui.tv(a, "New courses coming soon", 12.5f, Ui.SUB, false);
                    soon.setGravity(Gravity.CENTER);
                    soon.setBackground(Ui.bg(a, Ui.CARD2, 12, 0));
                    soon.setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 16));
                    listWrap.addView(soon, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
                    continue;
                }
                if ("diploma".equals(k[0])) sortBySem(sub);
                listWrap.addView(section(k[3], k[1], k[2], sub.size()));
                for (Store.Course c : sub) listWrap.addView(courseCard(c, idx[0]++), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));
            }
        } else if ("diploma".equals(filterCat) && filterSem.isEmpty()) {
            sortBySem(list);
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (Store.Course c : list) keys.add(c.className.isEmpty() ? "Other" : c.className);
            for (String key : keys) {
                List<Store.Course> sub = new ArrayList<>();
                for (Store.Course c : list) if (key.equals(c.className.isEmpty() ? "Other" : c.className)) sub.add(c);
                listWrap.addView(section("📘", key, "Diploma", sub.size()));
                for (Store.Course c : sub) listWrap.addView(courseCard(c, idx[0]++), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));
            }
        } else {
            if ("diploma".equals(filterCat)) sortBySem(list);
            for (Store.Course c : list) listWrap.addView(courseCard(c, idx[0]++), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));
        }
    }

    private View section(String icon, String title, String sub, int n) {
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        TextView ic = Ui.tv(a, icon, 18, Ui.TEXT, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.circle(Ui.ACCENT_SOFT));
        h.addView(ic, Ui.lpm(a, Ui.dp(a, 38), Ui.dp(a, 38), 0, 0, 10, 0));
        LinearLayout tx = new LinearLayout(a);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(Ui.tv(a, title, 16, Ui.TEXT, true));
        tx.addView(Ui.tv(a, sub, 12, Ui.SUB, false));
        h.addView(tx, new LinearLayout.LayoutParams(0, WRAP, 1f));
        h.addView(Ui.pill(a, String.valueOf(n), Ui.ACCENT2, Ui.ACCENT_SOFT));
        return h;
    }

    private View courseCard(final Store.Course c, int i) {
        int col = PALETTE[i % PALETTE.length];
        String[] k = Store.catOf(c.category);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.bg(a, Ui.BG, 16, 0x26000000));
        Ui.pressFx(card);
        card.setOnClickListener(v -> a.openCourse(c.id));

        LinearLayout thumb = new LinearLayout(a);
        thumb.setOrientation(LinearLayout.HORIZONTAL);
        thumb.setGravity(Gravity.CENTER_VERTICAL);
        thumb.setBackground(topRound((col & 0x00FFFFFF) | 0x1F000000));
        thumb.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 14), 0);
        thumb.addView(Ui.tv(a, ICONS[i % ICONS.length], 30, col, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        thumb.addView(Ui.pill(a, "▶ " + Store.lessonsLabel(c.videos.size()), col, Ui.WHITE));
        card.addView(thumb, new LinearLayout.LayoutParams(MATCH, Ui.dp(a, 74)));

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        LinearLayout tags = new LinearLayout(a);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        tags.addView(Ui.pill(a, k[3] + " " + k[1], Ui.ACCENT2, Ui.ACCENT_SOFT), Ui.lpm(a, WRAP, WRAP, 0, 0, 6, 0));
        if ("diploma".equals(c.category) && !c.className.isEmpty()) tags.addView(Ui.pill(a, c.className, Ui.SUB, Ui.CARD2));
        body.addView(tags);
        body.addView(Ui.ellipsized(a, c.title, 16, Ui.TEXT, true, 2), Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 0));
        LinkedHashSet<String> mm = new LinkedHashSet<>();
        if (!c.courseName.isEmpty()) mm.add(c.courseName);
        if (!c.subject.isEmpty()) mm.add(c.subject);
        String meta = mm.isEmpty() ? "Diploma in EEE" : android.text.TextUtils.join(" · ", mm);
        body.addView(Ui.ellipsized(a, meta, 12, Ui.SUB, false, 1), Ui.lpm(a, MATCH, WRAP, 0, 2, 0, 0));
        if (!c.description.isEmpty()) body.addView(Ui.ellipsized(a, c.description, 12.5f, Ui.SUB, false, 2), Ui.lpm(a, MATCH, WRAP, 0, 4, 0, 0));
        if (c.price > 0) {
            String extra = Store.courseAllowed(c) && !Store.adminIn ? "✓ Unlocked" : Store.priceLabel(c.price);
            body.addView(Ui.tv(a, extra, 12, Ui.GREEN, true), Ui.lpm(a, MATCH, WRAP, 0, 6, 0, 0));
        }
        body.addView(Ui.tv(a, "View Course  →", 13, Ui.ACCENT2, true), Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        card.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
        return card;
    }
}
