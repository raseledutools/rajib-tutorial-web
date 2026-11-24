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

/** Lessons saved inside the app (direct videos and YouTube lessons). Plays without internet; each copy removes itself after a few days. */
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
        VideoCache.purgeExpired(a);
        root.addView(Ui.tv(a, "📥 Offline Lessons", 20, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 4));
        sub = Ui.tv(a, "", 12.5f, Ui.SUB, false);
        root.addView(sub, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(MATCH, WRAP));
        render();
        OfflineGuard.ensure(a, ok -> { if (list != null) render(); });     // online: verify now so the lock state is current
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
        if (!OfflineGuard.isValid(a)) sub.setText("🔒 Locked — connect to the internet once to unlock your saved lessons (checked every 24 hours)");
        if (items.isEmpty()) {
            message("No lessons saved yet.\nOpen a lesson and tap \"Save offline\" (or the ☁ icon next to it). Works for YouTube and direct videos — saved copies play without internet and remove themselves after " + VideoCache.OFFLINE_DAYS + " days.");
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
            int left = Math.max(1, VideoCache.daysLeft(a, e.v.id));
            txt.addView(Ui.tv(a, e.course + " · " + String.format(Locale.US, "%.1f MB", e.f.length() / 1048576.0)
                + " · " + left + (left == 1 ? " day" : " days") + " left", 11.5f, Ui.SUB, false));
            row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));

            TextView play = Ui.smallBtn(a, "▶ Play", Ui.WHITE, v -> OfflineGuard.ensure(a, ok -> {
                if (!ok) { a.toast("Connect to the internet once to unlock offline lessons"); render(); return; }
                Intent i = new Intent(a, PlayerActivity.class);
                i.putExtra("url", e.v.url);
                i.putExtra("vid", e.v.id);                      // encrypted copy, decrypted while playing
                i.putExtra("title", e.v.title);
                a.startActivity(i);
            }));
            play.setBackground(Ui.bg(a, Ui.ACCENT, 10, 0));
            row.addView(play, Ui.lpm(a, WRAP, WRAP, 6, 0, 6, 0));
            row.addView(Ui.smallBtn(a, "🗑", Ui.SUB, v -> {
                VideoCache.delete(a, e.v.id);
                render();
                a.toast("Removed from offline");
            }));
            list.addView(row, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
    }
}
