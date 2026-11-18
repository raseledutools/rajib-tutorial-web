package com.rajibtutorial;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private static final class Item {
        Store.Video v; String course; File f;
    }

    private void render() {
        list.removeAllViews();
        if (!(Store.adminIn || Store.isPremium())) {
            sub.setText("");
            message("Log in as a Premium member to watch your saved lessons.");
            return;
        }
        List<Item> items = new ArrayList<>();
        long total = 0;
        for (Store.Course c : Store.courses) {
            for (Store.Video v : c.videos) {
                String path = VideoCache.getLocalPath(a, v.id);
                if (path == null) continue;
                Item it = new Item();
                it.v = v; it.course = c.title; it.f = new File(path);
                total += it.f.length();
                items.add(it);
            }
        }
        sub.setText(items.isEmpty() ? "Saved inside this app — plays without internet"
            : items.size() + " saved · " + String.format(Locale.US, "%.1f MB", total / 1048576.0) + " · plays without internet");
        if (items.isEmpty()) {
            message("No lessons saved yet.\nA lesson is saved automatically the first time you play it with internet (YouTube lessons can't be saved).");
            return;
        }
        for (final Item e : items) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
            row.setBackground(Ui.bg(a, Ui.BG, 14, 0x1A000000));

            LinearLayout txt = new LinearLayout(a);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.addView(Ui.ellipsized(a, e.v.title.isEmpty() ? "Lesson" : e.v.title, 14, Ui.TEXT, true, 2));
            txt.addView(Ui.tv(a, e.course + " · " + String.format(Locale.US, "%.1f MB", e.f.length() / 1048576.0), 11.5f, Ui.SUB, false));
            row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));

            row.addView(Ui.smallBtn(a, "▶ Play", Ui.ACCENT, v -> {
                Intent i = new Intent(a, PlayerActivity.class);
                i.putExtra("url", "file://" + e.f.getAbsolutePath());
                i.putExtra("title", e.v.title);
                a.startActivity(i);
            }), Ui.lpm(a, WRAP, WRAP, 6, 0, 6, 0));
            row.addView(Ui.smallBtn(a, "🗑", Ui.SUB, v -> {
                VideoCache.delete(a, e.v.id);
                render();
                a.toast("Removed from offline");
            }));
            list.addView(row, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
    }
}
