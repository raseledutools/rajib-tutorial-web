package com.rajibtutorial;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/**
 * Tap on an admin notification -> colorful overlay card with the full message.
 */
public class NotificationDetailActivity extends Activity {

    static final String EXTRA_TITLE = "nd_title";
    static final String EXTRA_BODY  = "nd_body";
    static final String EXTRA_TIME  = "nd_time";

    private static final int PINK = 0xFFFF4D7E, ORANGE = 0xFFFF9A3D, PURPLE = 0xFF7C3AED, BLUE = 0xFF3B82F6;

    private int dp(float v) { return Ui.dp(this, v); }

    private GradientDrawable grad(int[] colors, GradientDrawable.Orientation o, float[] radii) {
        GradientDrawable g = new GradientDrawable(o, colors);
        g.setCornerRadii(radii);
        return g;
    }

    private static float[] r(float tl, float tr, float br, float bl, float d) {
        return new float[]{tl * d, tl * d, tr * d, tr * d, br * d, br * d, bl * d, bl * d};
    }

    private View blob(int size, int color, int alpha) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        v.setAlpha(alpha / 255f);
        v.setLayoutParams(new FrameLayout.LayoutParams(dp(size), dp(size)));
        return v;
    }

    private TextView pill(String text, GradientDrawable bg, int textColor, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(textColor);
        t.setTextSize(14.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(13), dp(12), dp(13));
        t.setBackground(bg);
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String title = in.getStringExtra(EXTRA_TITLE);
        String body  = in.getStringExtra(EXTRA_BODY);
        long time    = in.getLongExtra(EXTRA_TIME, System.currentTimeMillis());
        if (title == null || title.isEmpty()) title = "Rajib Tutorial";
        if (body == null) body = "";
        final float d = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish());   // tap outside closes

        // ── card = colorful header + light body ──
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setElevation(dp(16));
        card.setBackground(Ui.bg(this, 0xFFFFFFFF, 26, 0));
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 26 * d);
            }
        });

        // header (gradient + decorative blobs)
        FrameLayout head = new FrameLayout(this);
        head.setBackground(grad(new int[]{PINK, PURPLE}, GradientDrawable.Orientation.TL_BR, r(0, 0, 0, 0, d)));
        View b1 = blob(110, 0xFFFFFFFF, 40);
        FrameLayout.LayoutParams p1 = new FrameLayout.LayoutParams(dp(110), dp(110), Gravity.TOP | Gravity.END);
        p1.topMargin = -dp(30); p1.rightMargin = -dp(24);
        head.addView(b1, p1);
        View b2 = blob(70, ORANGE, 150);
        FrameLayout.LayoutParams p2 = new FrameLayout.LayoutParams(dp(70), dp(70), Gravity.BOTTOM | Gravity.START);
        p2.bottomMargin = -dp(26); p2.leftMargin = -dp(18);
        head.addView(b2, p2);
        View b3 = blob(26, 0xFFFFFFFF, 70);
        FrameLayout.LayoutParams p3 = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.START);
        p3.topMargin = dp(16); p3.leftMargin = dp(120);
        head.addView(b3, p3);

        LinearLayout hc = new LinearLayout(this);
        hc.setOrientation(LinearLayout.VERTICAL);
        hc.setGravity(Gravity.CENTER_HORIZONTAL);
        hc.setPadding(dp(20), dp(24), dp(20), dp(22));

        final TextView bell = new TextView(this);
        bell.setText("\uD83D\uDD14");
        bell.setTextSize(30);
        bell.setGravity(Gravity.CENTER);
        GradientDrawable bellBg = new GradientDrawable();
        bellBg.setShape(GradientDrawable.OVAL);
        bellBg.setColor(0xFFFFFFFF);
        bell.setBackground(bellBg);
        bell.setElevation(dp(6));
        hc.addView(bell, new LinearLayout.LayoutParams(dp(68), dp(68)));

        TextView chip = new TextView(this);
        chip.setText("\uD83D\uDCE2  Admin বার্তা");
        chip.setTextColor(0xFFFFFFFF);
        chip.setTextSize(12);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(dp(20));
        chipBg.setColor(0x33FFFFFF);
        chip.setBackground(chipBg);
        LinearLayout.LayoutParams cpar = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cpar.topMargin = dp(14);
        hc.addView(chip, cpar);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tpar = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tpar.topMargin = dp(10);
        hc.addView(t, tpar);

        TextView tm = new TextView(this);
        tm.setText("\uD83D\uDD52  " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time)));
        tm.setTextColor(0xE6FFFFFF);
        tm.setTextSize(12);
        LinearLayout.LayoutParams tmp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tmp.topMargin = dp(6);
        hc.addView(tm, tmp);
        head.addView(hc, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(head, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // body
        LinearLayout bodyBox = new LinearLayout(this);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        bodyBox.setBackgroundColor(0xFFFFFFFF);
        bodyBox.setPadding(dp(18), dp(18), dp(18), dp(18));

        // message in a soft tinted panel with a colorful left stripe
        LinearLayout msgRow = new LinearLayout(this);
        msgRow.setOrientation(LinearLayout.HORIZONTAL);
        msgRow.setBackground(Ui.bg(this, 0xFFFFF3F7, 16, 0));
        View stripe = new View(this);
        stripe.setBackground(grad(new int[]{PINK, ORANGE}, GradientDrawable.Orientation.TOP_BOTTOM, r(16, 0, 0, 16, d)));
        msgRow.addView(stripe, new LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT));
        ScrollView sv = new ScrollView(this);
        sv.setPadding(dp(14), dp(12), dp(14), dp(12));
        sv.setClipToPadding(false);
        TextView msg = new TextView(this);
        msg.setText(body);
        msg.setTextColor(0xFF2A2430);
        msg.setTextSize(16);
        msg.setLineSpacing(0, 1.3f);
        msg.setTextIsSelectable(true);
        sv.addView(msg);
        msgRow.addView(sv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bodyBox.addView(msgRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // buttons
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setCornerRadius(dp(14));
        closeBg.setColor(0xFFF1EEF8);
        TextView close = pill("বন্ধ করুন", closeBg, PURPLE, v -> finish());
        GradientDrawable openBg = grad(new int[]{PINK, ORANGE}, GradientDrawable.Orientation.LEFT_RIGHT, r(14, 14, 14, 14, d));
        TextView open = pill("অ্যাপ খুলুন  \u2192", openBg, 0xFFFFFFFF, v -> {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        LinearLayout.LayoutParams q1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        q1.rightMargin = dp(6);
        LinearLayout.LayoutParams q2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        q2.leftMargin = dp(6);
        row.addView(close, q1);
        row.addView(open, q2);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(16);
        bodyBox.addView(row, rp);

        // colorful bottom strip
        View strip = new View(this);
        strip.setBackground(grad(new int[]{PINK, ORANGE, PURPLE, BLUE}, GradientDrawable.Orientation.LEFT_RIGHT, r(0, 0, 0, 0, d)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        sp.topMargin = dp(0);
        card.addView(bodyBox, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(strip, sp);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cp.setMargins(dp(22), dp(40), dp(22), dp(40));
        root.addView(card, cp);
        setContentView(root);

        // entrance: card pops in, bell rings
        card.setScaleX(0.85f); card.setScaleY(0.85f); card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
            .setInterpolator(new OvershootInterpolator(1.1f)).start();
        bell.setPivotX(34 * d); bell.setPivotY(10 * d);
        ObjectAnimator ring = ObjectAnimator.ofFloat(bell, "rotation", 0f, -18f, 16f, -12f, 9f, -5f, 0f);
        ring.setStartDelay(250);
        ring.setDuration(800);
        ring.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();
    }
}
