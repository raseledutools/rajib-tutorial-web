package com.rajibtutorial;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Lessons saved inside the app (direct videos only). Plays without internet. */
final class OfflineScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final NativeHomeActivity a;
    private LinearLayout list;
    private TextView sub;

    OfflineScreen(NativeHomeActivity host) { a = host; }

    @Override
    View build() {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG2);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 28));
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(Ui.tv(a, "📥 Offline Lessons", 20, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 4));
        sub = Ui.tv(a, "", 12.5f, Ui.SUB, false);
        root.addView(sub, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(MATCH, WRAP));
        render();
        return sv;
    }

    @Override
    void refresh() { if (list != null) render(); }

    @Override
    void onResume() { if (list != null) render(); }

    private void message(String m) {
        TextView t = Ui.tv(a, m, 13.5f, Ui.SUB, false);
        t.setGravity(Gravity.CENTER);
        list.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 28, 0, 12));
    }

    private void render() {
        list.removeAllViews();
        if (!(Store.adminIn || Store.isPremium())) {
            sub.setText("");
            message("Log in as a Premium member to watch your saved lessons.");
            return;
        }
        List<VideoCache.Entry> items = VideoCache.list(a);
        sub.setText(items.isEmpty() ? "Saved inside this app — plays without internet"
            : items.size() + " saved · " + VideoCache.mb(VideoCache.totalBytes(a)) + " · plays without internet");
        if (items.isEmpty()) {
            message("No lessons saved yet.\nA lesson is saved automatically the first time you play it with internet (YouTube lessons can't be saved).");
            return;
        }
        for (final VideoCache.Entry e : items) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
            row.setBackground(Ui.bg(a, Ui.BG, 14, 0x1A000000));

            LinearLayout txt = new LinearLayout(a);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.addView(Ui.ellipsized(a, e.title.isEmpty() ? "Lesson" : e.title, 14, Ui.TEXT, true, 2));
            txt.addView(Ui.tv(a, (e.course.isEmpty() ? "" : e.course + " · ") + VideoCache.mb(e.size), 11.5f, Ui.SUB, false));
            row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));

            row.addView(Ui.smallBtn(a, "▶ Play", Ui.ACCENT, v -> {
                Intent i = new Intent(a, PlayerActivity.class);
                i.putExtra("url", e.url);
                i.putExtra("title", e.title);
                a.startActivity(i);
            }), Ui.lpm(a, WRAP, WRAP, 6, 0, 6, 0));
            row.addView(Ui.smallBtn(a, "🗑", Ui.SUB, v -> {
                VideoCache.remove(a, e.url);
                render();
                a.toast("Removed from offline");
            }));
            list.addView(row, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
    }
}
