package com.rajibtutorial;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/**
 * Opens when the user taps an admin notification: a clean overlay card showing the full message.
 */
public class NotificationDetailActivity extends Activity {

    static final String EXTRA_TITLE = "nd_title";
    static final String EXTRA_BODY  = "nd_body";
    static final String EXTRA_TIME  = "nd_time";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String title = in.getStringExtra(EXTRA_TITLE);
        String body  = in.getStringExtra(EXTRA_BODY);
        long time    = in.getLongExtra(EXTRA_TIME, System.currentTimeMillis());
        if (title == null || title.isEmpty()) title = "Rajib Tutorial";
        if (body == null) body = "";

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish());   // tap outside the card closes it

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.bg(this, Ui.WHITE, 24, 0));
        card.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 18));
        card.setClickable(true);                  // don't pass taps through to root
        card.setElevation(Ui.dp(this, 12));

        // header: round icon + app name + time
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ico = Ui.tv(this, "\uD83D\uDD14", 16, Ui.WHITE, false);
        ico.setGravity(Gravity.CENTER);
        ico.setBackground(Ui.circle(Ui.ACCENT));
        head.addView(ico, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        LinearLayout hc = new LinearLayout(this);
        hc.setOrientation(LinearLayout.VERTICAL);
        hc.addView(Ui.tv(this, "Rajib Tutorial", 13, Ui.TEXT, true));
        hc.addView(Ui.tv(this, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(new Date(time)), 11.5f, Ui.SUB, false));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hp.leftMargin = Ui.dp(this, 10);
        head.addView(hc, hp);
        card.addView(head, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // title
        TextView t = Ui.tv(this, title, 19, Ui.TEXT, true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(this, 16);
        card.addView(t, tp);

        View line = new View(this);
        line.setBackgroundColor(Ui.BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1)));
        lp.topMargin = Ui.dp(this, 12);
        lp.bottomMargin = Ui.dp(this, 12);
        card.addView(line, lp);

        // full message (scrolls if long)
        ScrollView sv = new ScrollView(this);
        TextView msg = Ui.tv(this, body, 15.5f, Ui.TEXT, false);
        msg.setLineSpacing(0, 1.25f);
        msg.setTextIsSelectable(true);
        sv.addView(msg);
        card.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // buttons
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView close = Ui.btn(this, "বন্ধ করুন", Ui.B_OUTLINE, v -> finish());
        TextView open = Ui.btn(this, "অ্যাপ খুলুন", Ui.B_PRIMARY, v -> {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p1.rightMargin = Ui.dp(this, 5);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p2.leftMargin = Ui.dp(this, 5);
        row.addView(close, p1);
        row.addView(open, p2);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(this, 16);
        card.addView(row, rp);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        int m = Ui.dp(this, 20);
        cp.setMargins(m, m * 2, m, m * 2);
        root.addView(card, cp);
        setContentView(root);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();
    }
}
