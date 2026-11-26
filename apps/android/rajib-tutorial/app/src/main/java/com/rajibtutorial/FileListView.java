package com.rajibtutorial;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Reorderable list of picked files (WPS-like rows with ▲ ▼ ✕). */
final class FileListView extends LinearLayout {

    final List<StudyToolsActivity.Picked> items = new ArrayList<>();
    private final boolean image;
    Runnable onChange;

    FileListView(Context c, boolean image) {
        super(c);
        this.image = image;
        setOrientation(VERTICAL);
    }

    void add(List<StudyToolsActivity.Picked> more) {
        items.addAll(more);
        rebuild();
    }

    void rebuild() {
        removeAllViews();
        Context c = getContext();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            StudyToolsActivity.Picked p = items.get(i);

            LinearLayout row = W.row(c);
            row.setPadding(0, W.dp(c, 8), 0, W.dp(c, 8));

            TextView num = W.text(c, String.valueOf(i + 1), 12, W.SUB, true);
            num.setGravity(Gravity.CENTER);
            row.addView(num, W.lp(W.dp(c, 20), ViewGroup.LayoutParams.WRAP_CONTENT));

            row.addView(W.badge(c, image ? "IMG" : "PDF", image ? W.BLUE : W.RED),
                W.lp(c, W.dp(c, 38), W.dp(c, 38), 4, 0, 10, 0));

            LinearLayout t = W.column(c);
            TextView n = W.text(c, p.name, 14, W.TEXT, true);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            t.addView(n);
            t.addView(W.text(c, W.size(p.size), 11.5f, W.SUB, false));
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(act(c, "▲", i > 0, v -> move(idx, -1)));
            row.addView(act(c, "▼", i < items.size() - 1, v -> move(idx, 1)));
            row.addView(act(c, "✕", true, v -> { items.remove(idx); rebuild(); }));

            addView(row);
            if (i < items.size() - 1) {
                View d = new View(c);
                d.setBackgroundColor(W.LINE);
                addView(d, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }
        if (onChange != null) onChange.run();
    }

    private TextView act(Context c, String s, boolean enabled, OnClickListener l) {
        TextView b = W.text(c, s, 14, enabled ? W.TEXT : 0xFFD0D4DC, false);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(W.dp(c, 36), W.dp(c, 36)));
        if (enabled) b.setOnClickListener(l);
        return b;
    }

    private void move(int idx, int d) {
        int j = idx + d;
        if (j < 0 || j >= items.size()) return;
        StudyToolsActivity.Picked tmp = items.get(idx);
        items.set(idx, items.get(j));
        items.set(j, tmp);
        rebuild();
    }
}
