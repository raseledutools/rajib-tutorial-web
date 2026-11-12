package com.rajibtutorial;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Admin: manage courses and lessons. */
final class ManageScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private final NativeHomeActivity a;
    private ScrollView sv;

    ManageScreen(NativeHomeActivity host) { a = host; }

    @Override
    View build() {
        sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG);
        render();
        return sv;
    }

    @Override
    void refresh() { if (sv != null) render(); }

    private void render() {
        sv.removeAllViews();
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 28));
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        root.addView(Ui.tv(a, "Manage Courses", 20, Ui.TEXT, true));
        LinearLayout acts = new LinearLayout(a);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.addView(Ui.btn(a, "Live & Premium Settings", Ui.B_OUTLINE, v -> a.dialogs.liveSettings()), Ui.lpm(a, 0, WRAP, 0, 0, 6, 0));
        acts.addView(Ui.btn(a, "+ Add Course", Ui.B_PRIMARY, v -> a.dialogs.course(null)), Ui.lpm(a, 0, WRAP, 6, 0, 0, 0));
        ((LinearLayout.LayoutParams) acts.getChildAt(0).getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) acts.getChildAt(1).getLayoutParams()).weight = 1;
        root.addView(acts, Ui.lpm(a, MATCH, WRAP, 0, 12, 0, 14));

        if (Store.courses.isEmpty()) {
            TextView t = Ui.tv(a, "No courses yet. Click “+ Add Course” to create one.", 13.5f, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            root.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 24, 0, 0));
            return;
        }
        for (final Store.Course c : Store.courses) {
            LinearLayout card = Ui.card(a);
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout info = new LinearLayout(a);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(Ui.tv(a, c.title, 15, Ui.TEXT, true));
            String meta = "";
            for (String s : new String[]{c.courseName, c.className, c.subject}) {
                if (!s.isEmpty()) meta += (meta.isEmpty() ? "" : " · ") + s;
            }
            if (!meta.isEmpty()) info.addView(Ui.tv(a, meta, 12, Ui.SUB, false));
            info.addView(Ui.tv(a, Store.lessonsLabel(c.videos.size()), 12, Ui.ACCENT2, false));
            top.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
            top.addView(Ui.smallBtn(a, "✎ Edit", Ui.ACCENT, v -> a.dialogs.course(c.id)), Ui.lpm(a, WRAP, WRAP, 6, 0, 0, 0));
            top.addView(Ui.smallBtn(a, "🗑", Ui.RED, v -> a.dialogs.confirmDelCourse(c.id)), Ui.lpm(a, WRAP, WRAP, 6, 0, 0, 0));
            card.addView(top);

            LinearLayout hdr = new LinearLayout(a);
            hdr.setOrientation(LinearLayout.HORIZONTAL);
            hdr.setGravity(Gravity.CENTER_VERTICAL);
            hdr.addView(Ui.tv(a, "Lessons (" + c.videos.size() + ")", 12.5f, Ui.SUB, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
            hdr.addView(Ui.smallBtn(a, "+ Add Video", Ui.ACCENT, v -> a.dialogs.video(c.id, null)));
            card.addView(hdr, Ui.lpm(a, MATCH, WRAP, 0, 12, 0, 6));

            if (c.videos.isEmpty()) {
                card.addView(Ui.tv(a, "No lessons yet. Click “+ Add Video”.", 12, Ui.SUB, false));
            }
            for (int j = 0; j < c.videos.size(); j++) {
                final Store.Video v = c.videos.get(j);
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
                TextView n = Ui.tv(a, String.valueOf(j + 1), 11.5f, Ui.SUB, true);
                n.setGravity(Gravity.CENTER);
                n.setBackground(Ui.circle(Ui.CARD2));
                row.addView(n, Ui.lpm(a, Ui.dp(a, 24), Ui.dp(a, 24), 0, 0, 10, 0));
                LinearLayout t = new LinearLayout(a);
                t.setOrientation(LinearLayout.VERTICAL);
                t.addView(Ui.ellipsized(a, v.title, 13.5f, Ui.TEXT, true, 1));
                String u = v.url.isEmpty() ? "No link" : (v.url.length() > 48 ? v.url.substring(0, 48) + "…" : v.url);
                t.addView(Ui.ellipsized(a, u, 11, Ui.SUB, false, 1));
                row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
                row.addView(Ui.smallBtn(a, "✎", Ui.ACCENT, x -> a.dialogs.video(c.id, v.id)), Ui.lpm(a, WRAP, WRAP, 6, 0, 0, 0));
                row.addView(Ui.smallBtn(a, "🗑", Ui.RED, x -> a.dialogs.confirmDelVideo(c.id, v.id)), Ui.lpm(a, WRAP, WRAP, 6, 0, 0, 0));
                card.addView(row);
            }
            root.addView(card, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));
        }
    }
}
