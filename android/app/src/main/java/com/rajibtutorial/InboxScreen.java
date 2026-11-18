package com.rajibtutorial;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Notifications inbox (bell button on the home bar): every notification the admin has sent to
 * everyone — plus the ones sent only to this member — newest first, with date and time.
 */
final class InboxScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final NativeHomeActivity a;
    private ScrollView sv;
    private LinearLayout list;
    private boolean released = false;

    InboxScreen(NativeHomeActivity host) { a = host; }

    private static final class Item {
        String title, body, tag = ""; long at;
    }

    @Override
    View build() {
        sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG2);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 28));
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        root.addView(Ui.tv(a, "🔔 Notifications", 20, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 4));
        root.addView(Ui.tv(a, "Messages from the admin, newest first", 12.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(MATCH, WRAP));

        showMessage("Loading…", Ui.SUB);
        load();
        return sv;
    }

    private void showMessage(String msg, int color) {
        list.removeAllViews();
        TextView t = Ui.tv(a, msg, 13.5f, color, false);
        t.setGravity(Gravity.CENTER);
        list.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 28, 0, 12));
    }

    private void load() {
        try {
            FirebaseFirestore.getInstance().collection("push_messages")
                .orderBy("at", Query.Direction.DESCENDING).limit(100).get()
                .addOnSuccessListener(snap -> {
                    if (released) return;
                    List<Item> items = new ArrayList<>();
                    JSONArray keep = new JSONArray();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        String target = d.getString("target");
                        if (!Store.notifForMe(target, d.getString("mobile"), d.getString("batch"))) continue;
                        Item it = new Item();
                        it.title = nz(d.getString("title"));
                        it.body = nz(d.getString("body"));
                        Long at = d.getLong("at");
                        it.at = at == null ? 0L : at;
                        it.tag = "batch".equals(target) ? "Your batch: " + nz(d.getString("batch")) : ("all".equals(target) ? "" : "For you");
                        items.add(it);
                        try { keep.put(new JSONObject().put("title", it.title).put("body", it.body).put("at", it.at).put("tag", it.tag)); } catch (Exception ignored) { }
                    }
                    OfflineCache.saveInbox(a, keep);
                    render(items);
                })
                .addOnFailureListener(e -> {
                    if (released) return;
                    // offline / blocked: fall back to what this phone received
                    List<Item> local = new ArrayList<>();
                    JSONArray arr = OfflineCache.loadInbox(a);
                    if (arr.length() == 0) arr = PushManager.inboxLoad(a);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        Item it = new Item();
                        it.title = o.optString("title");
                        it.body = o.optString("body");
                        it.at = o.optLong("at");
                        it.tag = o.optString("tag", "");
                        local.add(it);
                    }
                    if (local.isEmpty()) showMessage("Could not load notifications.\nCheck your internet connection and open this page again.", Ui.SUB);
                    else render(local);
                });
        } catch (Throwable t) {
            showMessage("Could not load notifications.", Ui.SUB);
        }
    }

    private void render(List<Item> items) {
        list.removeAllViews();
        if (items.isEmpty()) {
            showMessage("No notifications yet.", Ui.SUB);
            return;
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        long newest = 0;
        for (Item it : items) {
            newest = Math.max(newest, it.at);
            list.addView(row(it, df), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        }
        PushManager.inboxMarkSeen(a, newest);
        a.updateBell(false);
    }

    private View row(final Item it, DateFormat df) {
        LinearLayout c = Ui.card(a);
        c.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        c.addView(Ui.tv(a, it.title.isEmpty() ? "Rajib Tutorial" : it.title, 14.5f, Ui.TEXT, true));
        TextView body = Ui.tv(a, it.body, 13.5f, Ui.TEXT, false);
        body.setLineSpacing(0, 1.15f);
        Links.linkify(body, Ui.ACCENT2);
        c.addView(body, Ui.lpm(a, MATCH, WRAP, 0, 4, 0, 6));

        String url = Links.firstUrl(it.body, it.title);
        if (url != null) {
            final String u = url;
            TextView open = Ui.smallBtn(a, "🔗  Open link", Ui.ACCENT, v -> {
                if (!Links.open(a, u)) a.toast("Could not open the link");
            });
            c.addView(open, Ui.lpm(a, WRAP, WRAP, 0, 0, 0, 8));
        }
        String when = it.at == 0 ? "" : "🕒  " + df.format(new Date(it.at));
        if (!it.tag.isEmpty()) when += (when.isEmpty() ? "" : "   ·   ") + it.tag;
        if (!when.isEmpty()) c.addView(Ui.tv(a, when, 11.5f, Ui.SUB, false));
        return c;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    @Override
    void release() { released = true; }
}
