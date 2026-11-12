package com.rajibtutorial;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Admin: premium members. */
final class MembersScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private final NativeHomeActivity a;
    private ScrollView sv;

    MembersScreen(NativeHomeActivity host) { a = host; }

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

        LinearLayout hdr = new LinearLayout(a);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.addView(Ui.tv(a, "Premium Members", 20, Ui.TEXT, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        hdr.addView(Ui.btn(a, "+ Add Member", Ui.B_PRIMARY, v -> a.dialogs.member(null)));
        root.addView(hdr, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        if (Store.members.isEmpty()) {
            TextView t = Ui.tv(a, "No Premium members yet. Click “+ Add Member”.", 13.5f, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            root.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 24, 0, 0));
            return;
        }
        for (final Store.Member m : Store.members) {
            boolean exp = !m.expiry.isEmpty() && Store.todayStr().compareTo(m.expiry) > 0;
            LinearLayout card = Ui.card(a);
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout info = new LinearLayout(a);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(Ui.tv(a, m.name, 15, Ui.TEXT, true));
            info.addView(Ui.tv(a, m.mobile, 12.5f, Ui.SUB, false));
            LinearLayout st = new LinearLayout(a);
            st.setOrientation(LinearLayout.HORIZONTAL);
            st.setGravity(Gravity.CENTER_VERTICAL);
            st.addView(exp ? Ui.pill(a, "Expired", Ui.RED, 0x26D6483A) : Ui.pill(a, "Active", Ui.GREEN, 0x2617915A));
            st.addView(Ui.tv(a, "  " + (m.expiry.isEmpty() ? "No expiry" : "until " + m.expiry) + " · " + Store.accessLabel(m), 11.5f, Ui.SUB, false));
            info.addView(st, Ui.lpm(a, WRAP, WRAP, 0, 4, 0, 0));
            top.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
            card.addView(top);

            LinearLayout acts = new LinearLayout(a);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            acts.addView(Ui.smallBtn(a, "✎ Edit", Ui.ACCENT, v -> a.dialogs.member(m.id)), Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
            acts.addView(Ui.smallBtn(a, "📱 Devices", Ui.ACCENT, v -> a.dialogs.deviceSettings(m.id)), Ui.lpm(a, WRAP, WRAP, 0, 0, 8, 0));
            acts.addView(Ui.smallBtn(a, "🗑 Remove", Ui.RED, v -> a.dialogs.confirmDelMember(m.id)));
            card.addView(acts, Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
            root.addView(card, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        }
    }
}
