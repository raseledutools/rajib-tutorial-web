package com.rajibtutorial;

import android.app.DatePickerDialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Age calculator with native date pickers. */
final class AgeTool extends ToolPage {
    private Calendar dob;
    private Calendar target = Calendar.getInstance();
    private TextView dobBtn, targetBtn;
    private LinearLayout resultBox;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH);

    AgeTool(StudyToolsActivity a) { super(a); }

    @Override String title() { return "Age Calculator"; }

    @Override View body() {
        LinearLayout col = W.column(a);
        LinearLayout card = W.card(a);

        card.addView(W.text(a, "Date of birth", 13, W.SUB, true));
        dobBtn = dateBtn("Select date of birth");
        dobBtn.setOnClickListener(v -> pick(true));
        card.addView(dobBtn, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 16));

        card.addView(W.text(a, "Calculate as of", 13, W.SUB, true));
        targetBtn = dateBtn(fmt.format(target.getTime()) + "  (today)");
        targetBtn.setOnClickListener(v -> pick(false));
        card.addView(targetBtn, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        col.addView(card);

        resultBox = W.column(a);
        col.addView(resultBox, W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        return col;
    }

    @Override View footer() {
        return W.primaryBtn(a, "Calculate age", v -> calc());
    }

    private TextView dateBtn(String label) {
        TextView t = W.text(a, "📅  " + label, 15, W.TEXT, false);
        t.setPadding(W.dp(a, 14), W.dp(a, 14), W.dp(a, 14), W.dp(a, 14));
        W.ripple(t, 0xFFF6F7FA, 10);
        return t;
    }

    private void pick(final boolean isDob) {
        Calendar init = isDob ? (dob != null ? dob : defaultDob()) : target;
        new DatePickerDialog(a, (view, y, m, d) -> {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(y, m, d);
            if (isDob) {
                dob = c;
                dobBtn.setText("📅  " + fmt.format(c.getTime()));
            } else {
                target = c;
                targetBtn.setText("📅  " + fmt.format(c.getTime()));
            }
        }, init.get(Calendar.YEAR), init.get(Calendar.MONTH), init.get(Calendar.DAY_OF_MONTH)).show();
    }

    private static Calendar defaultDob() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.YEAR, -20);
        return c;
    }

    private static Calendar midnight(Calendar in) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(in.get(Calendar.YEAR), in.get(Calendar.MONTH), in.get(Calendar.DAY_OF_MONTH));
        return c;
    }

    private void calc() {
        if (dob == null) { a.toast("Select date of birth"); return; }
        Calendar d = midnight(dob), t = midnight(target);
        if (d.after(t)) { a.toast("Date of birth is after the target date"); return; }

        int years = t.get(Calendar.YEAR) - d.get(Calendar.YEAR);
        int months = t.get(Calendar.MONTH) - d.get(Calendar.MONTH);
        int days = t.get(Calendar.DAY_OF_MONTH) - d.get(Calendar.DAY_OF_MONTH);
        if (days < 0) {
            months--;
            Calendar pm = (Calendar) t.clone();
            pm.set(Calendar.DAY_OF_MONTH, 1);
            pm.add(Calendar.MONTH, -1);
            days += pm.getActualMaximum(Calendar.DAY_OF_MONTH);
        }
        if (months < 0) { years--; months += 12; }

        long totalDays = Math.round((t.getTimeInMillis() - d.getTimeInMillis()) / 86400000.0);
        long totalMonths = years * 12L + months;

        Calendar nb = (Calendar) d.clone();
        nb.set(Calendar.YEAR, t.get(Calendar.YEAR));
        if (nb.before(t)) nb.add(Calendar.YEAR, 1);
        long toNext = Math.round((nb.getTimeInMillis() - t.getTimeInMillis()) / 86400000.0);

        resultBox.removeAllViews();
        LinearLayout card = W.card(a);
        TextView big = W.text(a, years + " Years, " + months + " Months, " + days + " Days", 20, W.BLUE, true);
        big.setGravity(Gravity.CENTER);
        card.addView(big);
        card.addView(W.text(a, "as of " + fmt.format(t.getTime()), 12, W.SUB, false),
            W.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        ((TextView) card.getChildAt(1)).setGravity(Gravity.CENTER);

        card.addView(line("Total months", String.valueOf(totalMonths)));
        card.addView(line("Total days", String.valueOf(totalDays)));
        card.addView(line("Next birthday", toNext == 0 ? "Today 🎂" : "in " + toNext + " days"));
        resultBox.addView(card);
    }

    private View line(String k, String v) {
        LinearLayout r = W.row(a);
        r.setPadding(0, W.dp(a, 12), 0, 0);
        r.addView(W.text(a, k, 14, W.SUB, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(W.text(a, v, 14, W.TEXT, true));
        return r;
    }
}
