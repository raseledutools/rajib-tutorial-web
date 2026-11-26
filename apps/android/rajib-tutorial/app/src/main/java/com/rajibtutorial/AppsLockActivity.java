package com.rajibtutorial;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pick which apps to lock. Protected by the Apps Lock password. */
public class AppsLockActivity extends AppCompatActivity {
    private LinearLayout list, banner;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean built = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF7B2FF7);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3EEFF);

        TextView head = Ui.tv(this, "🔐 Apps Lock", 20, 0xFFFFFFFF, true);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundResource(R.drawable.bg_header);
        head.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        root.addView(head, Ui.lp(-1, Ui.dp(this, 60)));

        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), 0);
        root.addView(banner, Ui.lp(-1, -2));

        TextView hint = Ui.tv(this, "Turn on the apps you want to lock. A password will be asked each time they open.", 12.5f, 0xFF7A6E99, false);
        hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 4));
        root.addView(hint, Ui.lp(-1, -2));

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 20));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        gate();
    }

    /** Ask for (or create) the password before showing anything. */
    private void gate() {
        final EditText p1 = field("Password");
        final EditText p2 = field("Confirm password");
        final boolean has = AppLock.hasPassword(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), 0);
        box.addView(p1);
        if (!has) box.addView(p2);

        final AlertDialog d = new AlertDialog.Builder(this)
            .setTitle(has ? "🔒 Enter Apps Lock password" : "🔐 Create Apps Lock password")
            .setView(box).setCancelable(false)
            .setPositiveButton(has ? "Open" : "Save", null)
            .setNegativeButton("Cancel", (x, w) -> finish())
            .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = p1.getText().toString();
            if (has) {
                if (!AppLock.checkPassword(this, a)) { Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show(); return; }
            } else {
                if (a.length() < 4) { Toast.makeText(this, "At least 4 characters", Toast.LENGTH_SHORT).show(); return; }
                if (!a.equals(p2.getText().toString())) { Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show(); return; }
                AppLock.setPassword(this, a);
            }
            d.dismiss();
            built = true;
            loadApps();
            refreshBanner();
        }));
        d.show();
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            final List<Row> rows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                Row r = new Row();
                r.pkg = pkg;
                r.label = String.valueOf(ri.loadLabel(pm));
                r.icon = ri.loadIcon(pm);
                rows.add(r);
            }
            Collections.sort(rows, (a, c) -> a.label.compareToIgnoreCase(c.label));
            h.post(() -> render(rows));
        }).start();
    }

    private static final class Row { String pkg, label; Drawable icon; }

    private void render(List<Row> rows) {
        list.removeAllViews();
        final Set<String> locked = AppLock.locked(this);
        for (final Row r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.bg(this, 0xFFFFFFFF, 14, 0));
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));

            ImageView iv = new ImageView(this);
            iv.setImageDrawable(r.icon);
            row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

            TextView name = Ui.tv(this, r.label, 15, 0xFF2A1B4D, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, Ui.lpm(this, 0, -2, 14, 0, 10, 0));
            ((LinearLayout.LayoutParams) name.getLayoutParams()).weight = 1;

            Switch sw = new Switch(this);
            sw.setChecked(locked.contains(r.pkg));
            sw.setOnCheckedChangeListener((v, on) -> {
                Set<String> cur = AppLock.locked(this);
                if (on) cur.add(r.pkg); else cur.remove(r.pkg);
                AppLock.setLocked(this, cur);
                refreshBanner();
            });
            row.addView(sw);
            list.addView(row, Ui.lpm(this, -1, -2, 0, 0, 0, 8));
        }
    }

    private void refreshBanner() {
        banner.removeAllViews();
        if (!built) return;
        if (!PhoneMode.hasUsageAccess(this)) addStep("⚠ Allow Usage access", v ->
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        if (!PhoneMode.hasOverlay(this)) addStep("⚠ Allow Display over apps", v ->
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
    }

    private void addStep(String text, android.view.View.OnClickListener l) {
        banner.addView(Ui.btn(this, text, Ui.B_PRIMARY, l), Ui.lpm(this, -1, -2, 0, 0, 0, 6));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBanner();
        AppLock.ensureService(this);
    }
}
