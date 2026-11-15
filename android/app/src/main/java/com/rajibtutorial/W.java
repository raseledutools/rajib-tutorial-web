package com.rajibtutorial;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** WPS-Office-like design kit used only by the native Study Tools screens. */
final class W {
    private W() { }

    static final int BG = 0xFFF3F5F9;
    static final int SURFACE = 0xFFFFFFFF;
    static final int TEXT = 0xFF1B1F27;
    static final int SUB = 0xFF8A919E;
    static final int LINE = 0xFFE6E9EF;

    static final int BLUE = 0xFF2F6BFF;
    static final int BLUE_DARK = 0xFF1F55DB;
    static final int RED = 0xFFE5483C;
    static final int ORANGE = 0xFFFF8A1F;
    static final int GREEN = 0xFF1FA463;
    static final int PURPLE = 0xFF7A5AF8;
    static final int TEAL = 0xFF10A3A3;
    static final int PINK = 0xFFE8467C;
    static final int LIME = 0xFF6BAA1B;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
            c.getResources().getDisplayMetrics()));
    }

    static int alpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    static GradientDrawable rect(Context c, int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable rect(Context c, int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = rect(c, fill, radiusDp);
        g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    static GradientDrawable dashed(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = rect(c, fill, radiusDp);
        g.setStroke(dp(c, 1.5f), stroke, dp(c, 6), dp(c, 4));
        return g;
    }

    /** Applies a rounded background with a material ripple on top. */
    static void ripple(View v, int fill, float radiusDp) {
        Context c = v.getContext();
        GradientDrawable content = rect(c, fill, radiusDp);
        GradientDrawable mask = rect(c, 0xFF000000, radiusDp);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), content, mask));
        v.setClickable(true);
        v.setFocusable(true);
    }

    static TextView text(Context c, CharSequence t, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static View space(Context c, float hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, hDp)));
        return v;
    }

    /** White rounded card. */
    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(rect(c, SURFACE, 14));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setElevation(dp(c, 1));
        return l;
    }

    static TextView sectionTitle(Context c, String t) {
        TextView tv = text(c, t, 15, TEXT, true);
        return tv;
    }

    /** Big blue pill button (WPS primary action). */
    static TextView primaryBtn(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label, 16, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        ripple(b, BLUE, 26);
        b.setOnClickListener(l);
        return b;
    }

    static TextView softBtn(Context c, String label, int color, View.OnClickListener l) {
        TextView b = text(c, label, 14, color, true);
        b.setGravity(Gravity.CENTER);
        int p = dp(c, 12);
        b.setPadding(p, dp(c, 10), p, dp(c, 10));
        ripple(b, alpha(color, 0x1A), 20);
        b.setOnClickListener(l);
        return b;
    }

    static void setEnabledLook(TextView b, boolean enabled, int color) {
        b.setEnabled(enabled);
        ripple(b, enabled ? color : 0xFFC5CBD6, 26);
    }

    /** Chip used for single-choice option groups. */
    static TextView chip(Context c, String label, boolean selected) {
        TextView t = text(c, label, 13, selected ? BLUE : TEXT, selected);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 14);
        t.setPadding(ph, dp(c, 8), ph, dp(c, 8));
        t.setBackground(rect(c, selected ? alpha(BLUE, 0x14) : 0xFFF3F5F9, 18,
            selected ? BLUE : LINE, 1));
        return t;
    }

    static EditText input(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFFB3B9C4);
        e.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        int p = dp(c, 12);
        e.setPadding(p, p, p, p);
        e.setBackground(rect(c, 0xFFF6F7FA, 10, LINE, 1));
        return e;
    }

    /** WPS-like dashed "add file" box. */
    static View pickBox(Context c, String emoji, String title, String sub, View.OnClickListener l) {
        LinearLayout box = column(c);
        box.setGravity(Gravity.CENTER);
        int p = dp(c, 22);
        box.setPadding(p, p, p, p);
        box.setBackground(dashed(c, alpha(BLUE, 0x0D), 14, alpha(BLUE, 0x66)));
        box.setClickable(true);
        box.setFocusable(true);

        TextView ico = text(c, emoji, 26, TEXT, false);
        ico.setGravity(Gravity.CENTER);
        ico.setBackground(rect(c, alpha(BLUE, 0x1F), 28));
        box.addView(ico, lp(dp(c, 56), dp(c, 56)));

        TextView t = text(c, title, 15, BLUE, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t, lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));

        if (sub != null) {
            TextView s = text(c, sub, 12, SUB, false);
            s.setGravity(Gravity.CENTER);
            box.addView(s, lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        }
        box.setOnClickListener(l);
        return box;
    }

    /** Home grid tile: tinted rounded square with an emoji and a label below. */
    static View tile(Context c, String emoji, int color, String label, View.OnClickListener l) {
        LinearLayout t = column(c);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(c, 2), dp(c, 8), dp(c, 2), dp(c, 8));
        t.setClickable(true);
        t.setFocusable(true);

        TextView ico = text(c, emoji, 24, color, false);
        ico.setGravity(Gravity.CENTER);
        ico.setBackground(rect(c, alpha(color, 0x1F), 16));
        t.addView(ico, lp(dp(c, 54), dp(c, 54)));

        TextView lb = text(c, label, 12, TEXT, false);
        lb.setGravity(Gravity.CENTER);
        lb.setMaxLines(2);
        t.addView(lb, lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        t.setOnClickListener(l);
        return t;
    }

    /** Small file-type badge like WPS' PDF / IMG icons. */
    static TextView badge(Context c, String label, int color) {
        TextView b = text(c, label, 10, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rect(c, color, 8));
        return b;
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024f) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576f);
    }
}
