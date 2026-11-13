package com.rajibtutorial;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/** Full-screen password prompt shown on top of a locked app. */
public class AppLockScreenActivity extends AppCompatActivity {
    private String pkg;
    private EditText pw;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) { finish(); return; }

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF1A0B2E, 0xFF3B0A5C, 0xFF0F2A5C});
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(bg);
        root.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);

        String label = pkg;
        PackageManager pm = getPackageManager();
        try {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(pm.getApplicationIcon(pkg));
            root.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception ignored) { }

        TextView t = Ui.tv(this, "🔒 " + label + " is locked", 20, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        root.addView(t, Ui.lpm(this, -1, -2, 0, 16, 0, 4));
        TextView s = Ui.tv(this, "Enter password to open", 13, 0xCCFFFFFF, false);
        s.setGravity(Gravity.CENTER);
        root.addView(s, Ui.lpm(this, -1, -2, 0, 0, 0, 18));

        pw = new EditText(this);
        pw.setHint("Password");
        pw.setHintTextColor(0x88FFFFFF);
        pw.setTextColor(0xFFFFFFFF);
        pw.setGravity(Gravity.CENTER);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pw.setBackground(Ui.bg(this, 0x22FFFFFF, 14, 0x55FFFFFF));
        pw.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        pw.setOnEditorActionListener((v, a, e) -> { tryUnlock(); return true; });
        root.addView(pw, Ui.lp(-1, -2));

        root.addView(Ui.btn(this, "Unlock", Ui.B_PRIMARY, v -> tryUnlock()), Ui.lpm(this, -1, -2, 0, 14, 0, 8));
        root.addView(Ui.btn(this, "Go back", Ui.B_GHOST, v -> goHome()), Ui.lp(-1, -2));
        setContentView(root);
    }

    private void tryUnlock() {
        if (AppLock.checkPassword(this, pw.getText().toString())) {
            AppLock.unlockedPkg = pkg;
            Intent open = getPackageManager().getLaunchIntentForPackage(pkg);
            if (open != null) startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        } else {
            pw.setText("");
            Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show();
        }
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override public void onBackPressed() { goHome(); }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String p = i.getStringExtra("pkg");
        if (p != null) pkg = p;
    }
}
