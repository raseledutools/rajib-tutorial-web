package com.rajibtutorial;

import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Home: hero, live buttons, stats and the course catalogue. */
final class HomeScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int[] PALETTE = {0xFFE8365D, 0xFF6D4AF0, 0xFF0E9F8E, 0xFF2563EB};
    private static final String[] ICONS = {"⚡", "🔌", "⚙", "〰"};

    private final NativeHomeActivity a;
    private ScrollView sv;
    private TextView btnClass, btnExam, liveInfo, statC, statV, cnt;
    private LinearLayout listWrap;
    private Spinner semSp;
    private View anchor;
    private String filterSem = "", searchQ = "";
    private boolean semBusy = false;

    HomeScreen(NativeHomeActivity host) { a = host; }

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
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setBackgroundColor(0xFFF8F4F1);
        hero.setPadding(Ui.dp(a, 22), Ui.dp(a, 30), Ui.dp(a, 22), Ui.dp(a, 22));
        root.addView(hero, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView logo = Ui.tv(a, "✳", 30, Ui.ACCENT, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.circle(Ui.ACCENT_SOFT));
        hero.addView(logo, Ui.lpm(a, Ui.dp(a, 68), Ui.dp(a, 68), 0, 0, 0, 16));

        String full = "Master Diploma EEE with structured, expert-led learning";
        String hl = "structured, expert-led";
        SpannableString ss = new SpannableString(full);
        int s = full.indexOf(hl);
        ss.setSpan(new ForegroundColorSpan(Ui.ACCENT), s, s + hl.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView h1 = Ui.tv(a, ss, 22, Ui.TEXT, true);
        h1.setGravity(Gravity.CENTER);
        hero.addView(h1, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView badge = Ui.tv(a, "Diploma in Electrical & Electronic Engineering", 13, Ui.SUB, false);
        badge.setGravity(Gravity.CENTER);
        hero.addView(badge, Ui.lpm(a, MATCH, WRAP, 0, 6, 0, 0));

        TextView p = Ui.tv(a, "Comprehensive video courses, live classes and scheduled live exams designed for Diploma in EEE students across all eight semesters. Study at your own pace and measure your progress with confidence.", 13, Ui.SUB, false);
        p.setGravity(Gravity.CENTER);
        p.setLineSpacing(0, 1.2f);
        hero.addView(p, Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 16));

        btnClass = Ui.btn(a, "Join Live Class", Ui.B_PRIMARY, v -> a.openLive(false));
        btnExam = Ui.btn(a, "Take Live Exam", Ui.B_OUTLINE, v -> a.openLive(true));
        TextView browse = Ui.btn(a, "Browse Courses", Ui.B_GHOST, v -> sv.post(() -> sv.smoothScrollTo(0, anchor.getTop())));
        hero.addView(btnClass, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        hero.addView(btnExam, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        hero.addView(browse, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 4));

        liveInfo = Ui.tv(a, "", 12.5f, Ui.SUB, false);
        liveInfo.setGravity(Gravity.CENTER);
        hero.addView(liveInfo, Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 0));

        LinearLayout stats = new LinearLayout(a);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        statC = Ui.tv(a, "–", 22, Ui.TEXT, true);
        statV = Ui.tv(a, "–", 22, Ui.TEXT, true);
        stats.addView(statCell(statC, "Courses"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        stats.addView(statCell(statV, "Video Lessons"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        stats.addView(statCell(Ui.tv(a, "8", 22, Ui.TEXT, true), "Semesters Covered"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        hero.addView(stats, Ui.lpm(a, MATCH, WRAP, 0, 20, 0, 0));

        // ── Catalogue ──
        LinearLayout sec = new LinearLayout(a);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setPadding(Ui.dp(a, 16), Ui.dp(a, 22), Ui.dp(a, 16), Ui.dp(a, 22));
        root.addView(sec, new LinearLayout.LayoutParams(MATCH, WRAP));
        anchor = sec;

        sec.addView(Ui.tv(a, "Course Catalogue", 19, Ui.TEXT, true));
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
        sec.addView(search, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));

        semSp = new Spinner(a);
        semSp.setBackground(Ui.bg(a, Ui.BG, 12, 0x33000000));
        semSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (semBusy) return;
                String v = pos == 0 ? "" : String.valueOf(parent.getItemAtPosition(pos));
                if (!v.equals(filterSem)) { filterSem = v; renderList(); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        sec.addView(semSp, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        listWrap = new LinearLayout(a);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        sec.addView(listWrap, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView foot = Ui.tv(a, "© " + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            + " Rajib Tutorial. All rights reserved.", 11.5f, Ui.SUB, false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 24));

        refresh();
        return sv;
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

    @Override
    void refresh() {
        if (sv == null) return;
        boolean cOn = !Store.safeUrl(Store.live.classUrl).isEmpty();
        boolean eOn = !Store.safeUrl(Store.live.examUrl).isEmpty();
        btnClass.setText("Join Live Class" + (cOn ? "   ● LIVE" : ""));
        btnExam.setText("Take Live Exam" + (eOn ? "   ● LIVE" : ""));
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

        int tv = 0;
        for (Store.Course c : Store.courses) tv += c.videos.size();
        statC.setText(a.loading ? "–" : String.valueOf(Store.courses.size()));
        statV.setText(a.loading ? "–" : String.valueOf(tv));
        buildSemFilter();
        renderList();
    }

    private void buildSemFilter() {
        List<String> used = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (String s : Store.SEMESTERS) all.add(s);
        for (Store.Course c : Store.courses) if (!c.className.isEmpty() && !all.contains(c.className)) all.add(c.className);
        for (String s : all) {
            for (Store.Course c : Store.courses) if (s.equals(c.className)) { used.add(s); break; }
        }
        if (!filterSem.isEmpty() && !used.contains(filterSem)) filterSem = "";
        List<String> items = new ArrayList<>();
        items.add("All Semesters");
        items.addAll(used);
        semBusy = true;
        semSp.setAdapter(new android.widget.ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, items));
        int i = filterSem.isEmpty() ? 0 : items.indexOf(filterSem);
        semSp.setSelection(Math.max(0, i));
        semSp.post(() -> semBusy = false);
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
            if (!filterSem.isEmpty() && !filterSem.equals(c.className)) continue;
            if (!searchQ.isEmpty()) {
                String hay = (c.title + " " + c.courseName + " " + c.subject + " " + c.className + " " + c.description).toLowerCase(Locale.getDefault());
                if (!hay.contains(searchQ)) continue;
            }
            list.add(c);
        }
        cnt.setText(Store.courses.isEmpty() ? "" : list.size() + " of " + Store.courses.size() + " courses");
        if (Store.courses.isEmpty() || list.isEmpty()) {
            TextView t = Ui.tv(a, Store.courses.isEmpty() ? "New courses will be published soon." : "No courses match your search.", 13.5f, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            listWrap.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 24, 0, 24));
            return;
        }
        for (int i = 0; i < list.size(); i++) listWrap.addView(courseRow(list.get(i), i), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
    }

    private View courseRow(final Store.Course c, int i) {
        int col = PALETTE[i % PALETTE.length];
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.bg(a, Ui.BG, 16, 0x26000000));
        row.setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14));
        Ui.pressFx(row);
        row.setOnClickListener(v -> a.openCourse(c.id));

        TextView ic = Ui.tv(a, ICONS[i % ICONS.length], 20, col, true);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.circle((col & 0x00FFFFFF) | 0x22000000));
        row.addView(ic, Ui.lpm(a, Ui.dp(a, 44), Ui.dp(a, 44), 0, 0, 12, 0));

        LinearLayout txt = new LinearLayout(a);
        txt.setOrientation(LinearLayout.VERTICAL);
        if (!c.courseName.isEmpty()) txt.addView(Ui.ellipsized(a, c.courseName.toUpperCase(Locale.getDefault()), 10.5f, col, true, 1));
        txt.addView(Ui.ellipsized(a, c.title, 15, Ui.TEXT, true, 2));
        String meta = "";
        if (!c.className.isEmpty()) meta = c.className;
        if (!c.subject.isEmpty()) meta += (meta.isEmpty() ? "" : " · ") + c.subject;
        if (meta.isEmpty()) meta = "Diploma in EEE";
        txt.addView(Ui.ellipsized(a, meta, 12, Ui.SUB, false, 1));
        if (!c.description.isEmpty()) txt.addView(Ui.ellipsized(a, c.description, 12, Ui.SUB, false, 2));
        txt.addView(Ui.tv(a, "▶  " + Store.lessonsLabel(c.videos.size()), 11.5f, Ui.ACCENT2, true), Ui.lpm(a, WRAP, WRAP, 0, 4, 0, 0));
        row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(Ui.tv(a, "›", 26, Ui.ACCENT, false));
        return row;
    }
}
