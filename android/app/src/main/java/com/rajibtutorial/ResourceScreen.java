package com.rajibtutorial;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/**
 * Current Circular / Question Bank (BUET · PSC · Other) / Recent Question Bank.
 * Everyone can open the links; the admin can add, edit and delete them here.
 */
final class ResourceScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final NativeHomeActivity a;
    private final String type;
    private String cat = "";                 // question-bank filter ("" = all)
    private LinearLayout list, chips;
    private ScrollView sv;

    ResourceScreen(NativeHomeActivity host, String type) { a = host; this.type = type; }

    @Override
    View build() {
        sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG2);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 28));
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        boolean circ = Store.R_CIRCULAR.equals(type);
        boolean qb = Store.R_QBANK.equals(type);
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(a, (circ ? "📢 " : qb ? "📚 " : "🆕 ") + Dialogs.resTypeName(type), 20, Ui.TEXT, true),
            new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (Store.adminIn) {
            head.addView(Ui.smallBtn(a, "+ Add", Ui.ACCENT, v -> a.dialogs.resource(type, null, cat)));
        }
        root.addView(head, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 4));
        root.addView(Ui.tv(a, circ ? "Latest job & admission circulars"
            : qb ? "Previous years' questions: BUET, PSC and others"
            : "Most recent exam questions", 12.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        if (qb) {
            chips = new LinearLayout(a);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(chips, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));
        }
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(MATCH, WRAP));
        render();
        return sv;
    }

    @Override
    void refresh() { if (list != null) render(); }

    private void drawChips() {
        chips.removeAllViews();
        String[] names = new String[Store.QB_CATS.length + 1];
        names[0] = "";
        for (int i = 0; i < Store.QB_CATS.length; i++) names[i + 1] = Store.QB_CATS[i];
        for (final String n : names) {
            boolean on = n.equals(cat);
            TextView c = Ui.tv(a, n.isEmpty() ? "All" : n, 13, on ? Ui.ACCENT2 : Ui.TEXT, true);
            c.setGravity(Gravity.CENTER);
            c.setBackground(Ui.bg(a, on ? Ui.ACCENT_SOFT : Ui.BG, 18, on ? Ui.ACCENT : 0x33000000));
            c.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8));
            Ui.pressFx(c);
            c.setOnClickListener(v -> { cat = n; render(); });
            chips.addView(c, Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        }
    }

    private void render() {
        if (chips != null) drawChips();
        list.removeAllViews();
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        int n = 0;
        for (final Store.Resource r : Store.resources) {
            if (!type.equals(r.type)) continue;
            if (Store.R_QBANK.equals(type) && !cat.isEmpty() && !cat.equalsIgnoreCase(r.category)) continue;
            n++;
            list.addView(row(r, df), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        }
        if (n == 0) {
            TextView t = Ui.tv(a, Store.adminIn ? "Nothing here yet. Tap “+ Add” to add the first one." : "Nothing here yet. Please check back soon.",
                13.5f, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            list.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 28, 0, 12));
        }
    }

    private View row(final Store.Resource r, DateFormat df) {
        LinearLayout c = Ui.card(a);
        c.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        c.addView(Ui.tv(a, r.title.isEmpty() ? "Untitled" : r.title, 14.5f, Ui.TEXT, true));
        if (!r.note.isEmpty()) c.addView(Ui.tv(a, r.note, 12.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 3, 0, 0));

        LinearLayout meta = new LinearLayout(a);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        if (Store.R_QBANK.equals(type) && !r.category.isEmpty()) {
            meta.addView(Ui.pill(a, r.category, Ui.ACCENT2, Ui.ACCENT_SOFT), Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        }
        if (r.at > 0) meta.addView(Ui.tv(a, "🕒 " + df.format(new Date(r.at)), 11.5f, Ui.SUB, false));
        c.addView(meta, Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 0));

        LinearLayout acts = new LinearLayout(a);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.addView(Ui.smallBtn(a, "🔗  Open", Ui.ACCENT, v -> {
            if (!Links.open(a, r.url)) a.toast("Could not open the link");
        }), Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        if (Store.adminIn) {
            acts.addView(Ui.smallBtn(a, "Edit", Ui.TEXT, v -> a.dialogs.resource(type, r.id, null)), Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
            acts.addView(Ui.smallBtn(a, "Delete", Ui.RED, v -> a.dialogs.confirmDelResource(r.id)));
        }
        c.addView(acts, Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        c.setOnClickListener(v -> { if (!Links.open(a, r.url)) a.toast("Could not open the link"); });
        return c;
    }
}
