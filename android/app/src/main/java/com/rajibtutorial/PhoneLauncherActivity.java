package com.rajibtutorial;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** Full-screen "phone" launcher: oval app panel + oval dock. Only Phone, Messages and offline apps. */
public class PhoneLauncherActivity extends AppCompatActivity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private LinearLayout banner, grid, dock;
    private boolean exiting = false;
    private boolean launchingAllowed = false;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (exiting) return;
            if (!PhoneMode.isActive(PhoneLauncherActivity.this) || PhoneMode.timeUp(PhoneLauncherActivity.this)) {
                leave();
                return;
            }
            updateStatus();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!PhoneMode.isActive(this) || PhoneMode.timeUp(this)) { leave(); return; }

        GradientDrawable bgd = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF1A0B2E, 0xFF3B0A5C, 0xFF0F2A5C});
        FrameLayout root = new FrameLayout(this);
        root.setBackground(bgd);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 36), Ui.dp(this, 16), Ui.dp(this, 20));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Clock + date
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("hh:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(58);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        col.addView(clock, Ui.lp(-2, -2));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(14);
        date.setTextColor(0xCCFFFFFF);
        col.addView(date, Ui.lp(-2, -2));

        status = Ui.tv(this, "", 13, 0xFFFFD166, true);
        status.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        status.setBackground(Ui.bg(this, 0x33FFFFFF, 20, 0));
        col.addView(status, Ui.lpm(this, -2, -2, 0, 10, 0, 6));

        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        col.addView(banner, Ui.lp(-1, -2));

        // Oval app panel
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackground(Ui.bg(this, 0x22FFFFFF, 56, 0x44FFFFFF));
        sv.setPadding(Ui.dp(this, 14), Ui.dp(this, 26), Ui.dp(this, 14), Ui.dp(this, 26));
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        sv.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        col.addView(sv, Ui.lpm(this, -1, 0, 0, 8, 0, 14));
        ((LinearLayout.LayoutParams) sv.getLayoutParams()).weight = 1;

        // Oval dock: Phone · Messages · Exit
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setBackground(Ui.bg(this, 0x33FFFFFF, 44, 0x55FFFFFF));
        dock.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        col.addView(dock, Ui.lp(-1, -2));

        setContentView(root);

        dock.addView(exitView());

        renderApps();
        startGuard();
    }

    private void startGuard() {
        Intent s = new Intent(this, PhoneGuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        } catch (Exception ignored) { }
    }

    /** Only Phone and Messages are allowed in Phone Mode. */
    private void renderApps() {
        grid.removeAllViews();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(appView(PhoneMode.phoneItem(this), 76, "📞"));
        row.addView(appView(PhoneMode.messageItem(this), 76, "💬"));
        grid.addView(row, Ui.lpm(this, -1, -2, 0, 40, 0, 0));
    }

    private View appView(final PhoneMode.AppItem it, int iconDp, String fallbackEmoji) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        if (it.icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(it.icon);
            v.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, iconDp), Ui.dp(this, iconDp)));
        } else {
            TextView e = Ui.tv(this, fallbackEmoji == null ? "📦" : fallbackEmoji, 26, 0xFFFFFFFF, false);
            e.setGravity(Gravity.CENTER);
            e.setBackground(Ui.circle(0x44FFFFFF));
            v.addView(e, new LinearLayout.LayoutParams(Ui.dp(this, iconDp), Ui.dp(this, iconDp)));
        }
        TextView l = Ui.tv(this, it.label, 11, 0xFFFFFFFF, false);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER);
        v.addView(l, Ui.lpm(this, -1, -2, 2, 5, 2, 0));
        v.setOnClickListener(x -> {
            launchingAllowed = true;
            try { startActivity(it.intent); }
            catch (Exception e) { Toast.makeText(this, "Can't open " + it.label, Toast.LENGTH_SHORT).show(); }
        });
        Ui.pressFx(v);
        return v;
    }

    private View exitView() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView e = Ui.tv(this, "🔓", 26, 0xFFFFFFFF, false);
        e.setGravity(Gravity.CENTER);
        e.setBackground(Ui.circle(0x66E8365D));
        v.addView(e, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        TextView l = Ui.tv(this, "Exit", 11, 0xFFFFFFFF, false);
        l.setGravity(Gravity.CENTER);
        v.addView(l, Ui.lpm(this, -1, -2, 2, 5, 2, 0));
        v.setOnClickListener(x -> tryExit());
        Ui.pressFx(v);
        return v;
    }

    // ── Exit logic ───────────────────────────────────────────
    private void tryExit() {
        if (PhoneMode.timeUp(this)) { leave(); return; }
        if (PhoneMode.hasPassword(this)) {
            final EditText pw = new EditText(this);
            pw.setHint("Password");
            pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            FrameLayout box = new FrameLayout(this);
            box.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), 0);
            box.addView(pw);
            new AlertDialog.Builder(this)
                .setTitle("🔒 Enter password to exit")
                .setView(box)
                .setPositiveButton("Unlock", (d, w) -> {
                    if (PhoneMode.checkPassword(this, pw.getText().toString())) leave();
                    else Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
        } else {
            Toast.makeText(this, "Locked — " + PhoneMode.fmt(PhoneMode.remainingMs(this)) + " left", Toast.LENGTH_SHORT).show();
        }
    }

    private void leave() {
        if (exiting) return;
        exiting = true;
        PhoneMode.stop(this);
        startActivity(new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    // ── Status + one-time setup banner ───────────────────────
    private void updateStatus() {
        if (PhoneMode.hasTimer(this)) {
            status.setText("⏱ Unlocks in " + PhoneMode.fmt(PhoneMode.remainingMs(this)));
        } else {
            status.setText("🔒 Locked — password needed to exit");
        }
    }

    private void refreshBanner() {
        banner.removeAllViews();
        if (!PhoneMode.hasUsageAccess(this)) addStep("⚠ Allow Usage access", v ->
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        if (!PhoneMode.hasOverlay(this)) addStep("⚠ Allow Display over apps", v ->
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
    }

    private void addStep(String text, View.OnClickListener l) {
        TextView b = Ui.btn(this, text, Ui.B_PRIMARY, v -> { launchingAllowed = true; l.onClick(v); });
        banner.addView(b, Ui.lpm(this, -1, -2, 0, 0, 0, 6));
    }

    // ── Lock-down behaviour ──────────────────────────────────
    @Override public void onBackPressed() { /* Back disabled in Phone Mode */ }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        launchingAllowed = false;
        if (exiting) return;
        refreshBanner();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() { h.removeCallbacks(tick); super.onPause(); }

    /** Home / Recents pressed (not our own Phone/Messages launch) → return immediately. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!exiting && !launchingAllowed && PhoneMode.isActive(this)) comeBack();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!exiting && !launchingAllowed && PhoneMode.isActive(this)) {
            h.postDelayed(this::comeBack, 80);
        }
    }

    private void comeBack() {
        if (exiting || launchingAllowed || !PhoneMode.isActive(this)) return;
        try {
            startActivity(new Intent(this, PhoneLauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) { }
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); hideBars(); }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
