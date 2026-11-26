package com.rajibtutorial;

import android.app.AlertDialog;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Feedback, same data as the web / exe version: "tutorial_feedback" collection (falls back to "_fb_…" docs
 * inside tutorial_courses when the rules refuse the first one). Everybody can send; the admin gets this list.
 */
final class FeedbackScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String COL = "tutorial_feedback";
    private static final String COURSES = "tutorial_courses";

    private static long lastSent = 0;

    // ── send dialog (for every user) ────────────────────────────────────────────────────────────

    static void showSendDialog(final NativeHomeActivity a) {
        final int[] rating = {0};
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 12), Ui.dp(a, 20), 0);

        box.addView(Ui.tv(a, "আপনার মতামত, সমস্যা বা পরামর্শ জানান — admin সরাসরি দেখতে পারবেন।", 12.5f, Ui.SUB, false),
            Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        box.addView(Ui.tv(a, "Rating (ঐচ্ছিক)", 12.5f, Ui.SUB, true));
        final LinearLayout stars = new LinearLayout(a);
        stars.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(stars, Ui.lpm(a, MATCH, WRAP, 0, 2, 0, 10));
        final TextView[] st = new TextView[5];
        for (int i = 0; i < 5; i++) {
            final int n = i + 1;
            TextView s = Ui.tv(a, "★", 30, 0xFFCBD0D6, false);
            s.setPadding(0, 0, Ui.dp(a, 8), 0);
            s.setOnClickListener(v -> {
                rating[0] = rating[0] == n ? 0 : n;
                for (int k = 0; k < 5; k++) st[k].setTextColor(k < rating[0] ? 0xFFF5A623 : 0xFFCBD0D6);
            });
            st[i] = s;
            stars.addView(s);
        }

        Store.Member me = null;
        try { me = Store.curMember(); } catch (Exception ignored) { }
        final Ui.Field msg = Ui.field(a, "আপনার মতামত", "এখানে লিখুন…", Ui.IN_MULTI);
        msg.et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        box.addView(msg.root);
        final Ui.Field name = Ui.field(a, "নাম (ঐচ্ছিক)", "আপনার নাম", Ui.IN_TEXT);
        name.et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        if (me != null && me.name != null) name.et.setText(me.name);
        box.addView(name.root);
        final Ui.Field mobile = Ui.field(a, "মোবাইল (ঐচ্ছিক)", "01XXXXXXXXX", Ui.IN_PHONE);
        mobile.et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(14)});
        if (Store.sessionMobile != null) mobile.et.setText(Store.sessionMobile);
        box.addView(mobile.root);
        final TextView err = Ui.tv(a, "", 12.5f, Ui.RED, false);
        err.setVisibility(View.GONE);
        box.addView(err);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        final AlertDialog d = new AlertDialog.Builder(a)
            .setTitle("💬 Feedback")
            .setView(sv)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("পাঠান", null)
            .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String m = msg.text();
            if (m.length() < 5) { Ui.setErr(err, "অনুগ্রহ করে অন্তত ৫টি অক্ষরের মতামত লিখুন।"); return; }
            if (System.currentTimeMillis() - lastSent < 30000) { Ui.setErr(err, "একটু পরে আবার পাঠান।"); return; }
            Map<String, Object> data = new HashMap<>();
            data.put("message", m.length() > 1000 ? m.substring(0, 1000) : m);
            data.put("rating", rating[0]);
            data.put("name", name.text());
            data.put("mobile", mobile.text().replaceAll("\\D", ""));
            data.put("member", Store.sessionMobile != null);
            data.put("device", Store.deviceLabel());
            data.put("deviceId", Store.deviceId == null ? "" : Store.deviceId);
            data.put("page", "Android app");
            data.put("platform", "android");
            data.put("at", System.currentTimeMillis());
            data.put("status", "new");
            final String id = "fb_" + Long.toString(System.currentTimeMillis(), 36) + Long.toString(new Random().nextInt(1679616), 36);
            v.setEnabled(false);
            ((TextView) v).setText("পাঠানো হচ্ছে…");
            send(id, data, (Void r, Exception e) -> {
                if (e == null) {
                    lastSent = System.currentTimeMillis();
                    d.dismiss();
                    a.toast("ধন্যবাদ! আপনার feedback পাঠানো হয়েছে।");
                } else {
                    v.setEnabled(true);
                    ((TextView) v).setText("পাঠান");
                    Ui.setErr(err, "পাঠানো যায়নি। ইন্টারনেট দেখে আবার চেষ্টা করুন।");
                }
            });
        }));
        d.show();
    }

    private static void send(final String id, final Map<String, Object> data, Store.Cb<Void> cb) {
        Store.async(() -> {
            try {
                Fs.setIn(COL, id, data);
            } catch (Exception e1) {
                String m = e1.getMessage() == null ? "" : e1.getMessage();
                if (m.contains("HTTP 403") || m.contains("HTTP 401")) Fs.set("_fb_" + id, data);   // rules refuse the new collection
                else throw e1;
            }
            return null;
        }, cb);
    }

    // ── admin list ──────────────────────────────────────────────────────────────────────────────

    private static final class Item {
        String id, src, name, mobile, message, device, platform, page, status;
        boolean member; long at; long rating;
        Map<String, Object> raw;
    }

    private final NativeHomeActivity a;
    private LinearLayout tabs, list;
    private final List<Item> items = new ArrayList<>();
    private String filter = "new";
    private boolean released = false;

    FeedbackScreen(NativeHomeActivity host) { a = host; }

    @Override
    View build() {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG2);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 28));
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(a, "💬 Feedback", 20, Ui.TEXT, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(Ui.smallBtn(a, "⟳ Reload", Ui.ACCENT, v -> load()));
        root.addView(head, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));

        tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(tabs, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(MATCH, WRAP));
        load();
        return sv;
    }

    @Override
    void release() { released = true; }

    private void message(String msg) {
        list.removeAllViews();
        TextView t = Ui.tv(a, msg, 13.5f, Ui.SUB, false);
        t.setGravity(Gravity.CENTER);
        list.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 28, 0, 12));
    }

    private void load() {
        message("Loading…");
        Store.async(() -> {
            List<Item> out = new ArrayList<>();
            boolean ok = false;
            try {
                for (Fs.Doc d : Fs.listCol(COL, 500)) { out.add(toItem(d, COL)); }
                ok = true;
            } catch (Exception ignored) { }
            try {
                for (Fs.Doc d : Fs.latestWithPrefix("_fb_", 300)) { out.add(toItem(d, COURSES)); }
                ok = true;
            } catch (Exception ignored) { }
            if (!ok) throw new java.io.IOException("load failed");
            Collections.sort(out, (x, y) -> Long.compare(y.at, x.at));
            return out;
        }, (List<Item> res, Exception err) -> {
            if (released) return;
            if (err != null || res == null) { message("Feedback load করা যায়নি।"); return; }
            items.clear();
            items.addAll(res);
            render();
        });
    }

    private static Item toItem(Fs.Doc d, String src) {
        Item it = new Item();
        it.id = d.id; it.src = src; it.raw = d.data;
        it.name = Fs.text(d.data, "name"); it.mobile = Fs.text(d.data, "mobile");
        it.message = Fs.text(d.data, "message"); it.device = Fs.text(d.data, "device");
        it.platform = Fs.text(d.data, "platform"); it.page = Fs.text(d.data, "page");
        it.status = Fs.text(d.data, "status"); it.at = Fs.num(d.data, "at"); it.rating = Fs.num(d.data, "rating");
        it.member = Boolean.TRUE.equals(d.data.get("member"));
        return it;
    }

    private void render() {
        int nNew = 0;
        for (Item i : items) if (!"done".equals(i.status)) nNew++;
        int nDone = items.size() - nNew;
        tabs.removeAllViews();
        addTab("new", "নতুন (" + nNew + ")");
        addTab("done", "Done (" + nDone + ")");
        addTab("all", "সব (" + items.size() + ")");

        list.removeAllViews();
        int shown = 0;
        for (final Item it : items) {
            boolean done = "done".equals(it.status);
            if (filter.equals("new") && done) continue;
            if (filter.equals("done") && !done) continue;
            list.addView(card(it, done), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
            shown++;
        }
        if (shown == 0) message("new".equals(filter) ? "কোনো নতুন feedback নেই।" : "কিছু নেই।");
    }

    private void addTab(final String key, String label) {
        TextView t = Ui.smallBtn(a, label, filter.equals(key) ? Ui.ACCENT : Ui.SUB, v -> { filter = key; render(); });
        tabs.addView(t, Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
    }

    private View card(final Item it, final boolean done) {
        LinearLayout c = Ui.card(a);
        c.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        String who = (it.name.isEmpty() ? "নাম নেই" : it.name) + (it.mobile.isEmpty() ? "" : " · " + it.mobile) + (it.member ? " · ⭐ Premium" : "");
        c.addView(Ui.tv(a, who, 13.5f, Ui.TEXT, true));
        if (it.at > 0) c.addView(Ui.tv(a, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(it.at)), 11.5f, Ui.SUB, false));
        if (it.rating > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 5; i++) sb.append(i <= it.rating ? "★" : "☆");
            c.addView(Ui.tv(a, sb.toString(), 16, 0xFFF5A623, false), Ui.lpm(a, WRAP, WRAP, 0, 4, 0, 0));
        }
        c.addView(Ui.tv(a, it.message, 14, Ui.TEXT, false), Ui.lpm(a, MATCH, WRAP, 0, 6, 0, 6));
        c.addView(Ui.tv(a, "📱 " + it.device + " · " + it.platform + (it.page.isEmpty() ? "" : " · পাতা: " + it.page), 11.5f, Ui.SUB, false));

        LinearLayout acts = new LinearLayout(a);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.addView(Ui.smallBtn(a, done ? "↩ নতুন করুন" : "✔ Done", Ui.ACCENT, v -> setStatus(it, !done)), Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        acts.addView(Ui.smallBtn(a, "🗑 Delete", Ui.RED, v -> confirmDelete(it)));
        c.addView(acts, Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 0));
        return c;
    }

    private void setStatus(final Item it, final boolean done) {
        final Map<String, Object> data = new HashMap<>(it.raw);
        data.put("status", done ? "done" : "new");
        Store.async(() -> { Fs.setIn(it.src, it.id, data); return null; }, (Void r, Exception e) -> {
            if (released) return;
            if (e != null) { a.toast("Error: " + e.getMessage()); return; }
            it.status = done ? "done" : "new";
            it.raw = data;
            render();
        });
    }

    private void confirmDelete(final Item it) {
        new AlertDialog.Builder(a)
            .setTitle("Feedback মুছবেন?")
            .setMessage("এই feedback টি স্থায়ীভাবে মুছে যাবে।")
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("মুছুন", (d, w) -> Store.async(() -> { Fs.deleteIn(it.src, it.id); return null; }, (Void r, Exception e) -> {
                if (released) return;
                if (e != null) { a.toast("Error: " + e.getMessage()); return; }
                items.remove(it);
                render();
                a.toast("Feedback মুছে ফেলা হয়েছে");
            })).show();
    }
}
