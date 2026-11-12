package com.rajibtutorial;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

/** Small programmatic-UI toolkit that mirrors the web design tokens. */
final class Ui {
    private Ui() {}

    static final int ACCENT = 0xFFE8365D, ACCENT2 = 0xFFC81E4E, ACCENT_SOFT = 0x1AE8365D;
    static final int BG = 0xFFFFFFFF, BG2 = 0xFFFBF8F6, CARD2 = 0xFFF3EEEA;
    static final int TEXT = 0xFF1E1B18, SUB = 0xFF6C665F, BORDER = 0x1A1E1B18;
    static final int GREEN = 0xFF17915A, RED = 0xFFD6483A, WHITE = 0xFFFFFFFF;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable bg(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    static GradientDrawable circle(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lpm(Context c, int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static TextView tv(Context c, CharSequence t, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    static void pressFx(View v) {
        v.setClickable(true);
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) x.setAlpha(0.7f);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) x.setAlpha(1f);
            return false;
        });
    }

    static final int B_PRIMARY = 0, B_OUTLINE = 1, B_GHOST = 2, B_RED = 3, B_GREEN = 4;

    static TextView btn(Context c, String text, int style, View.OnClickListener l) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 46));
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        switch (style) {
            case B_OUTLINE:
                b.setTextColor(TEXT);
                b.setBackground(bg(c, BG, 12, 0x33000000));
                break;
            case B_GHOST:
                b.setTextColor(TEXT);
                b.setBackground(bg(c, CARD2, 12, 0));
                break;
            case B_RED:
                b.setTextColor(WHITE);
                b.setBackground(bg(c, RED, 12, 0));
                break;
            case B_GREEN:
                b.setTextColor(WHITE);
                b.setBackground(bg(c, 0xFF25D366, 12, 0));
                break;
            default:
                b.setTextColor(WHITE);
                b.setBackground(bg(c, ACCENT, 12, 0));
        }
        pressFx(b);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    static TextView smallBtn(Context c, String text, int color, View.OnClickListener l) {
        TextView b = tv(c, text, 12.5f, color, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        b.setBackground(bg(c, color == ACCENT ? ACCENT_SOFT : CARD2, 10, 0));
        pressFx(b);
        b.setOnClickListener(l);
        return b;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(bg(c, BG, 16, BORDER));
        l.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        return l;
    }

    static View spacer(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(lp(1, dp(c, h)));
        return v;
    }

    static TextView pill(Context c, String text, int fg, int bgc) {
        TextView t = tv(c, text, 10.5f, fg, true);
        t.setPadding(dp(c, 7), dp(c, 2), dp(c, 7), dp(c, 2));
        t.setBackground(bg(c, bgc, 20, 0));
        return t;
    }

    // ── Form fields ─────────────────────────────────────────
    static final class Field {
        final LinearLayout root;
        final EditText et;
        Field(LinearLayout r, EditText e) { root = r; et = e; }
        String text() { return et.getText().toString().trim(); }
    }

    static Field field(Context c, String label, String hint, int inputType) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(lpm(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 12));
        TextView l = tv(c, label, 12.5f, SUB, true);
        l.setPadding(0, 0, 0, dp(c, 5));
        root.addView(l);
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setTextSize(14);
        et.setTextColor(TEXT);
        et.setHintTextColor(0xFFA39C93);
        et.setInputType(inputType);
        et.setBackground(bg(c, BG, 10, 0x33000000));
        et.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11));
        if ((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
            et.setMinLines(3);
            et.setGravity(Gravity.TOP | Gravity.START);
        } else {
            et.setSingleLine(true);
        }
        root.addView(et, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Field(root, et);
    }

    static final int IN_TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    static final int IN_PLAIN = InputType.TYPE_CLASS_TEXT;
    static final int IN_URL = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
    static final int IN_PHONE = InputType.TYPE_CLASS_PHONE;
    static final int IN_NUM = InputType.TYPE_CLASS_NUMBER;
    static final int IN_MULTI = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    static final int IN_PASS = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;

    static Spinner spinner(Context c, List<String> items, String selected) {
        Spinner sp = new Spinner(c);
        ArrayAdapter<String> ad = new ArrayAdapter<>(c, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setBackground(bg(c, BG, 10, 0x33000000));
        sp.setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4));
        int i = selected == null ? -1 : items.indexOf(selected);
        if (i >= 0) sp.setSelection(i);
        return sp;
    }

    static LinearLayout labeled(Context c, String label, View inner) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(lpm(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 12));
        TextView l = tv(c, label, 12.5f, SUB, true);
        l.setPadding(0, 0, 0, dp(c, 5));
        root.addView(l);
        root.addView(inner, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    // ── Modal dialog ────────────────────────────────────────
    static final class Modal {
        final Context ctx;
        final Dialog dialog;
        final LinearLayout box;

        Modal(Context c, String title, String sub) {
            ctx = c;
            dialog = new Dialog(c);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(true);
            box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(c, 22), dp(c, 22), dp(c, 22), dp(c, 18));
            ScrollView sv = new ScrollView(c);
            sv.setBackground(bg(c, BG, 22, 0));
            sv.addView(box, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout wrap = new FrameLayout(c);
            wrap.setPadding(dp(c, 14), dp(c, 24), dp(c, 14), dp(c, 24));
            wrap.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.setContentView(wrap);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            if (title != null) {
                TextView t = tv(c, title, 19, TEXT, true);
                box.addView(t, lpm(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 6));
            }
            if (sub != null && !sub.isEmpty()) {
                TextView s = tv(c, sub, 13.5f, SUB, false);
                s.setLineSpacing(0, 1.15f);
                box.addView(s, lpm(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));
            }
        }

        Modal add(View v) { box.addView(v); return this; }

        Modal addSpaced(View v, float topDp) {
            LinearLayout.LayoutParams p = lpm(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, topDp, 0, 0);
            box.addView(v, p);
            return this;
        }

        /** Side-by-side Cancel / OK row. */
        Modal actions(String cancel, String ok, int okStyle, final Runnable onOk) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView c = btn(ctx, cancel, B_GHOST, v -> dialog.dismiss());
            TextView o = btn(ctx, ok, okStyle, v -> onOk.run());
            row.addView(c, lpm(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 6, 0));
            ((LinearLayout.LayoutParams) c.getLayoutParams()).weight = 1;
            row.addView(o, lpm(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
            ((LinearLayout.LayoutParams) o.getLayoutParams()).weight = 1;
            box.addView(row, lpm(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
            lastOk = o;
            return this;
        }

        TextView lastOk;

        /** Full-width stacked button. */
        Modal col(String text, int style, View.OnClickListener l) {
            TextView b = btn(ctx, text, style, l);
            box.addView(b, lpm(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
            return this;
        }

        TextView errorView() {
            TextView e = tv(ctx, "", 12.5f, RED, false);
            e.setVisibility(View.GONE);
            e.setPadding(0, 0, 0, dp(ctx, 8));
            return e;
        }

        void show() { dialog.show(); }
        void dismiss() { dialog.dismiss(); }
    }

    static void setErr(TextView e, String msg) {
        e.setText(msg);
        e.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    static TextView ellipsized(Context c, CharSequence t, float sp, int color, boolean bold, int lines) {
        TextView v = tv(c, t, sp, color, bold);
        v.setMaxLines(lines);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }
}
