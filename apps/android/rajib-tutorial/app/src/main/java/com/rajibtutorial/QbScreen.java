package com.rajibtutorial;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCQ Practice (same Firestore "qb_questions" collection as the web / exe): Practice + Read, filters, search, saved. */
final class QbScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String LET = "কখগঘঙচ";

    static final class Q {
        String id = "", q = "", e = "", t = "", src = "", sub = "", ttl = "";
        List<String> o = new ArrayList<>();
        int a;
        long at;
    }

    private static List<Q> cache = null;

    private final NativeHomeActivity a;
    private String mode = "practice", sub = "", src = "", query = "";
    private boolean savedOnly = false;
    private int idx = 0, readN = 20;
    private final Map<String, Integer> ans = new HashMap<>();
    private ScrollView sv;
    private LinearLayout filters, body;
    private TextView tabP, tabR, savedChip;

    QbScreen(NativeHomeActivity host) { a = host; }

    @Override
    View build() {
        sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG2);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 28));
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        root.addView(Ui.tv(a, "📝 MCQ Practice", 20, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 4));
        root.addView(Ui.tv(a, "Practice and read MCQs by subject and exam", 12.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabP = chip("Practice", v -> { mode = "practice"; render(); });
        tabR = chip("Read", v -> { mode = "read"; render(); });
        tabs.addView(tabP, Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        tabs.addView(tabR, Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
        root.addView(tabs, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));

        filters = new LinearLayout(a);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(filters, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));

        LinearLayout sr = new LinearLayout(a);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        EditText et = new EditText(a);
        et.setHint("Search questions…");
        et.setTextSize(14);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(0xFFA39C93);
        et.setSingleLine(true);
        et.setBackground(Ui.bg(a, Ui.BG, 10, 0x33000000));
        et.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                query = s.toString();
                idx = 0;
                readN = 20;
                render();
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        sr.addView(et, new LinearLayout.LayoutParams(0, WRAP, 1f));
        savedChip = chip("♥ Saved", v -> { savedOnly = !savedOnly; idx = 0; readN = 20; render(); });
        sr.addView(savedChip, Ui.lpm(a, WRAP, WRAP, 8, 0, 0, 0));
        root.addView(sr, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));

        styleChips();
        load(false);
        return sv;
    }

    // ── data ────────────────────────────────────────────────
    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Q parse(Fs.Doc d) {
        Object oo = d.data.get("o");
        if (!(oo instanceof List)) return null;
        Q x = new Q();
        for (Object o : (List<Object>) oo) x.o.add(String.valueOf(o));
        if (x.o.size() < 2) return null;
        x.id = d.id;
        x.q = s(d.data, "q");
        x.e = s(d.data, "e");
        x.t = s(d.data, "t");
        x.src = s(d.data, "src");
        x.sub = s(d.data, "sub");
        x.ttl = s(d.data, "ttl");
        Object av = d.data.get("a");
        x.a = av instanceof Number ? ((Number) av).intValue() : 0;
        Object tv = d.data.get("at");
        x.at = tv instanceof Number ? ((Number) tv).longValue() : 0L;
        return x;
    }

    private void load(boolean force) {
        if (cache != null && !force) { ready(); return; }
        body.removeAllViews();
        body.addView(msg("Loading…"));
        Store.async(() -> {
            List<Q> out = new ArrayList<>();
            for (Fs.Doc d : Fs.listCol("qb_questions", 3000)) {
                Q x = parse(d);
                if (x != null) out.add(x);
            }
            Collections.sort(out, (p, q) -> Long.compare(p.at, q.at));
            return out;
        }, (List<Q> r, Exception e) -> {
            if (body == null) return;
            if (e != null || r == null) {
                body.removeAllViews();
                body.addView(msg("Could not load questions." + (e != null && e.getMessage() != null ? "\n" + e.getMessage() : "")));
                body.addView(Ui.btn(a, "Retry", Ui.B_OUTLINE, v -> load(true)));
                return;
            }
            cache = r;
            ready();
        });
    }

    private void ready() {
        buildFilters();
        render();
    }

    private SharedPreferences sp() { return a.getSharedPreferences("rt_qb", Context.MODE_PRIVATE); }

    private Set<String> bm() { return new HashSet<>(sp().getStringSet("bm", new HashSet<String>())); }

    private void toggleBm(String id) {
        Set<String> st = bm();
        if (!st.remove(id)) st.add(id);
        sp().edit().putStringSet("bm", st).apply();
    }

    private List<Q> list() {
        Set<String> b = savedOnly ? bm() : null;
        String k = query.trim().toLowerCase();
        List<Q> out = new ArrayList<>();
        for (Q x : cache) {
            if (!sub.isEmpty() && !sub.equals(x.sub)) continue;
            if (!src.isEmpty() && !src.equals(x.src)) continue;
            if (b != null && !b.contains(x.id)) continue;
            if (!k.isEmpty()) {
                StringBuilder sb = new StringBuilder(x.q);
                for (String o : x.o) sb.append(' ').append(o);
                if (!sb.toString().toLowerCase().contains(k)) continue;
            }
            out.add(x);
        }
        return out;
    }

    // ── UI pieces ───────────────────────────────────────────
    private TextView chip(String text, View.OnClickListener l) {
        TextView c = Ui.tv(a, text, 13, Ui.TEXT, true);
        c.setGravity(Gravity.CENTER);
        c.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8));
        Ui.pressFx(c);
        c.setOnClickListener(l);
        return c;
    }

    private void styleChip(TextView c, boolean on) {
        c.setTextColor(on ? Ui.ACCENT2 : Ui.TEXT);
        c.setBackground(Ui.bg(a, on ? Ui.ACCENT_SOFT : Ui.BG, 18, on ? Ui.ACCENT : 0x33000000));
    }

    private void styleChips() {
        styleChip(tabP, "practice".equals(mode));
        styleChip(tabR, "read".equals(mode));
        styleChip(savedChip, savedOnly);
    }

    private TextView msg(String t) {
        TextView v = Ui.tv(a, t, 13.5f, Ui.SUB, false);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, Ui.dp(a, 28), 0, Ui.dp(a, 12));
        return v;
    }

    private Spinner filterSpinner(String all, Set<String> vals, final boolean isSub) {
        final List<String> items = new ArrayList<>();
        items.add(all);
        items.addAll(vals);
        String cur = isSub ? sub : src;
        Spinner sp = Ui.spinner(a, items, cur.isEmpty() ? all : cur);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String val = pos == 0 ? "" : items.get(pos);
                if (isSub) { if (val.equals(sub)) return; sub = val; }
                else { if (val.equals(src)) return; src = val; }
                idx = 0;
                readN = 20;
                render();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        return sp;
    }

    private void buildFilters() {
        filters.removeAllViews();
        LinkedHashSet<String> subs = new LinkedHashSet<>(), srcs = new LinkedHashSet<>();
        for (Q x : cache) {
            if (!x.sub.isEmpty()) subs.add(x.sub);
            if (!x.src.isEmpty()) srcs.add(x.src);
        }
        if (!sub.isEmpty() && !subs.contains(sub)) sub = "";
        if (!src.isEmpty() && !srcs.contains(src)) src = "";
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        l1.setMargins(0, 0, Ui.dp(a, 8), 0);
        filters.addView(filterSpinner("All subjects", subs, true), l1);
        filters.addView(filterSpinner("All exams", srcs, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
    }

    private String label(Q x) {
        StringBuilder sb = new StringBuilder();
        String[] parts = {x.ttl, x.src, x.sub};
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.length() == 0 ? "MCQ" : sb.toString();
    }

    private View head(final Q x) {
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(Ui.tv(a, label(x), 11.5f, Ui.ACCENT2, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        boolean on = bm().contains(x.id);
        TextView heart = Ui.tv(a, on ? "♥" : "♡", 22, on ? Ui.ACCENT : Ui.SUB, false);
        heart.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 4), 0);
        Ui.pressFx(heart);
        heart.setOnClickListener(v -> { toggleBm(x.id); render(); });
        h.addView(heart);
        return h;
    }

    private View optRow(final Q x, final int i, Integer chosen, boolean reveal, boolean clickable) {
        boolean right = reveal && i == x.a;
        boolean wrong = reveal && chosen != null && chosen == i && i != x.a;
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(a, 12), Ui.dp(a, 11), Ui.dp(a, 12), Ui.dp(a, 11));
        int fill = right ? 0x1A17915A : wrong ? 0x1AD6483A : Ui.BG;
        int stroke = right ? Ui.GREEN : wrong ? Ui.RED : 0x33000000;
        r.setBackground(Ui.bg(a, fill, 12, stroke));
        TextView let = Ui.tv(a, i < LET.length() ? String.valueOf(LET.charAt(i)) : String.valueOf(i + 1), 13,
            right ? Ui.GREEN : wrong ? Ui.RED : Ui.SUB, true);
        let.setGravity(Gravity.CENTER);
        let.setMinWidth(Ui.dp(a, 26));
        r.addView(let, new LinearLayout.LayoutParams(WRAP, WRAP));
        r.addView(Ui.tv(a, x.o.get(i), 14.5f, Ui.TEXT, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (right || wrong) r.addView(Ui.tv(a, right ? "✓" : "✗", 15, right ? Ui.GREEN : Ui.RED, true));
        if (clickable) {
            Ui.pressFx(r);
            r.setOnClickListener(v -> {
                if (!ans.containsKey(x.id)) { ans.put(x.id, i); render(); }
            });
        }
        return r;
    }

    private void addExplain(LinearLayout card, Q x) {
        if (!x.e.isEmpty()) {
            TextView t = Ui.tv(a, "ব্যাখ্যা:\n" + x.e, 13.5f, Ui.TEXT, false);
            t.setBackground(Ui.bg(a, Ui.CARD2, 10, 0));
            t.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
            card.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 6, 0, 0));
        }
        if (!x.t.isEmpty()) {
            TextView t = Ui.tv(a, "⚡ শর্টকাট সমাধান\n" + x.t, 13.5f, Ui.TEXT, false);
            t.setBackground(Ui.bg(a, Ui.ACCENT_SOFT, 10, 0));
            t.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
            card.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 6, 0, 0));
        }
    }

    // ── rendering ───────────────────────────────────────────
    private void render() {
        if (body == null || tabP == null) return;
        styleChips();
        body.removeAllViews();
        if (cache == null) return;
        List<Q> L = list();
        if (L.isEmpty()) {
            body.addView(msg(cache.isEmpty() ? "Questions will be added soon. Please check back." : "No questions match the filter."));
            return;
        }
        if ("read".equals(mode)) readView(L); else practiceView(L);
    }

    private void goTo(int i) {
        idx = i;
        render();
        sv.post(() -> sv.scrollTo(0, 0));
    }

    private void practiceView(final List<Q> L) {
        if (idx >= L.size()) idx = L.size() - 1;
        if (idx < 0) idx = 0;
        final Q x = L.get(idx);
        Integer ch = ans.get(x.id);
        boolean done = ch != null;
        int ok = 0, bad = 0;
        for (Q q : L) {
            Integer v = ans.get(q.id);
            if (v != null && v >= 0) { if (v == q.a) ok++; else bad++; }
        }
        LinearLayout stat = new LinearLayout(a);
        stat.setOrientation(LinearLayout.HORIZONTAL);
        stat.setGravity(Gravity.CENTER_VERTICAL);
        stat.addView(Ui.tv(a, "✓ " + ok, 13.5f, Ui.GREEN, true), Ui.lpm(a, WRAP, WRAP, 0, 0, 12, 0));
        stat.addView(Ui.tv(a, "✗ " + bad, 13.5f, Ui.RED, true));
        TextView cnt = Ui.tv(a, (idx + 1) + " / " + L.size(), 13, Ui.SUB, true);
        cnt.setGravity(Gravity.CENTER);
        stat.addView(cnt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        stat.addView(Ui.smallBtn(a, "Reset", Ui.SUB, v -> { ans.clear(); render(); }));
        body.addView(stat, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));

        LinearLayout card = Ui.card(a);
        card.addView(head(x));
        card.addView(Ui.tv(a, (idx + 1) + ". " + x.q, 16, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 10));
        for (int i = 0; i < x.o.size(); i++) {
            card.addView(optRow(x, i, ch, done, !done), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
        if (done) {
            addExplain(card, x);
        } else {
            card.addView(Ui.btn(a, "Show answer", Ui.B_GHOST, v -> { ans.put(x.id, -1); render(); }),
                Ui.lpm(a, MATCH, WRAP, 0, 4, 0, 0));
        }
        body.addView(card, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        LinearLayout nav = new LinearLayout(a);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        TextView prev = Ui.btn(a, "‹ Prev", Ui.B_OUTLINE, v -> { if (idx > 0) goTo(idx - 1); });
        TextView next = Ui.btn(a, "Next ›", Ui.B_PRIMARY, v -> { if (idx < L.size() - 1) goTo(idx + 1); });
        prev.setAlpha(idx > 0 ? 1f : 0.4f);
        next.setAlpha(idx < L.size() - 1 ? 1f : 0.4f);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        pl.setMargins(0, 0, Ui.dp(a, 8), 0);
        nav.addView(prev, pl);
        nav.addView(next, new LinearLayout.LayoutParams(0, WRAP, 1f));
        body.addView(nav, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void readView(List<Q> L) {
        int n = Math.min(readN, L.size());
        for (int i = 0; i < n; i++) {
            Q x = L.get(i);
            LinearLayout card = Ui.card(a);
            card.addView(head(x));
            card.addView(Ui.tv(a, (i + 1) + ". " + x.q, 15.5f, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 10));
            for (int k = 0; k < x.o.size(); k++) {
                card.addView(optRow(x, k, null, true, false), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
            }
            addExplain(card, x);
            body.addView(card, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        }
        if (n < L.size()) {
            body.addView(Ui.btn(a, "Load more (" + (L.size() - n) + " left)", Ui.B_OUTLINE, v -> { readN += 20; render(); }));
        }
    }
}
