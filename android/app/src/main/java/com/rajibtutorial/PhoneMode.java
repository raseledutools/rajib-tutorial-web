package com.rajibtutorial;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.InputType;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Phone Mode state, allowed-app logic and the setup dialogs. */
final class PhoneMode {
    private PhoneMode() {}

    static final int MODE_TIMER = 1, MODE_PASSWORD = 2, MODE_BOTH = 3;

    private static final String PREFS = "PhoneModePrefs";
    private static final String K_ACTIVE = "active", K_MODE = "mode", K_END = "end_ms", K_PW = "pw_hash";

    static final class AppItem {
        String pkg, label;
        Drawable icon;
        Intent intent;
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── State ────────────────────────────────────────────────
    static boolean isActive(Context c) { return p(c).getBoolean(K_ACTIVE, false); }
    static int mode(Context c) { return p(c).getInt(K_MODE, MODE_TIMER); }
    static boolean hasPassword(Context c) { return p(c).contains(K_PW); }
    static boolean hasTimer(Context c) { int m = mode(c); return m == MODE_TIMER || m == MODE_BOTH; }

    static long remainingMs(Context c) {
        long end = p(c).getLong(K_END, 0);
        return end <= 0 ? 0 : Math.max(0, end - System.currentTimeMillis());
    }

    /** Timer finished -> phone mode can end by itself. */
    static boolean timeUp(Context c) { return hasTimer(c) && remainingMs(c) <= 0; }

    static boolean checkPassword(Context c, String pw) {
        return hash(pw).equals(p(c).getString(K_PW, ""));
    }

    static void start(Context c, int mode, long durationMs, String password) {
        SharedPreferences.Editor e = p(c).edit();
        e.putBoolean(K_ACTIVE, true).putInt(K_MODE, mode);
        if (mode != MODE_PASSWORD) e.putLong(K_END, System.currentTimeMillis() + durationMs);
        else e.remove(K_END);
        if (mode != MODE_TIMER && password != null) e.putString(K_PW, hash(password));
        else e.remove(K_PW);
        e.apply();
        resetLauncherState(c);
        setHomeEnabled(c, true);
    }

    static void stop(Context c) {
        p(c).edit().clear().apply();
        setHomeEnabled(c, false);
        try { c.stopService(new Intent(c, PhoneGuardService.class)); } catch (Exception ignored) { }
    }

    /**
     * Older builds disabled PhoneLauncherActivity when Phone Mode was off (it was a HOME app).
     * That saved state survives app updates, so reset it to the manifest default (enabled).
     */
    static void resetLauncherState(Context c) {
        try {
            c.getPackageManager().setComponentEnabledSetting(
                new ComponentName(c, PhoneLauncherActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) { }
    }

    /** Turns the HOME alias on/off. On only while Phone Mode is active, so the normal launcher is untouched otherwise. */
    static void setHomeEnabled(Context c, boolean on) {
        try {
            c.getPackageManager().setComponentEnabledSetting(
                new ComponentName(c, "com.rajibtutorial.PhoneHomeAlias"),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) { }
    }

    /** One-time ask: make this app the Home app so the Home button does nothing during Phone Mode. */
    static void requestDefaultHome(Activity a, int reqCode) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.app.role.RoleManager rm = (android.app.role.RoleManager) a.getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                    a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), reqCode);
                    return;
                }
            }
            a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            try { a.startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    static String fmt(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    private static String hash(String in) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest(in.getBytes("UTF-8"))) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return in; }
    }

    // ── Permissions / setup checks ───────────────────────────
    static boolean isDefaultHome(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    @SuppressWarnings("deprecation")
    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int r = ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), c.getPackageName());
            return r == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    static boolean hasOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static boolean setupIncomplete(Context c) {
        return !hasUsageAccess(c) || !hasOverlay(c);
    }

    // ── Allowed apps ─────────────────────────────────────────
    private static final List<String> SYSTEM_OK = Arrays.asList(
        "android", "com.android.systemui", "com.android.phone", "com.android.incallui",
        "com.android.server.telecom", "com.android.providers.telephony",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.incallui");

    private static final List<String> NEVER_OK = Arrays.asList(
        "com.android.settings", "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.android.vending", "com.sec.android.app.launcher");

    static AppItem phoneItem(Context c) {
        PackageManager pm = c.getPackageManager();
        AppItem it = new AppItem();
        it.label = "Phone";
        Intent dial = new Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String pkg = null;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) pkg = tm.getDefaultDialerPackage();
            }
        } catch (Exception ignored) { }
        if (pkg != null) {
            dial.setPackage(pkg);
            if (pm.resolveActivity(dial, 0) == null) { dial.setPackage(null); }
        }
        it.pkg = pkg;
        it.intent = dial;
        try { if (pkg != null) it.icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) { }
        return it;
    }

    static AppItem messageItem(Context c) {
        PackageManager pm = c.getPackageManager();
        AppItem it = new AppItem();
        it.label = "Messages";
        String pkg = null;
        try { pkg = android.provider.Telephony.Sms.getDefaultSmsPackage(c); } catch (Exception ignored) { }
        Intent i = pkg != null ? pm.getLaunchIntentForPackage(pkg) : null;
        if (i == null) {
            i = new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:"));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        it.pkg = pkg;
        it.intent = i;
        try { if (pkg != null) it.icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) { }
        return it;
    }

    private static boolean usesInternet(PackageManager pm, String pkg) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null) {
                for (String perm : pi.requestedPermissions) {
                    if ("android.permission.INTERNET".equals(perm)) return true;
                }
            }
        } catch (Exception e) { return true; }
        return false;
    }

    /** Launcher apps that do not request INTERNET = offline apps. */
    static List<AppItem> offlineApps(Context c, boolean withIcons) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        AppItem ph = phoneItem(c), ms = messageItem(c);
        if (ph.pkg != null) seen.add(ph.pkg);
        if (ms.pkg != null) seen.add(ms.pkg);
        List<AppItem> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || NEVER_OK.contains(pkg) || !seen.add(pkg)) continue;
            if (usesInternet(pm, pkg)) continue;
            AppItem it = new AppItem();
            it.pkg = pkg;
            it.label = String.valueOf(ri.loadLabel(pm));
            if (withIcons) it.icon = ri.loadIcon(pm);
            it.intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            out.add(it);
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** Every package that may be in the foreground during Phone Mode. */
    static Set<String> allowedPackages(Context c) {
        Set<String> s = new HashSet<>(SYSTEM_OK);
        s.add(c.getPackageName());
        AppItem ph = phoneItem(c), ms = messageItem(c);
        if (ph.pkg != null) s.add(ph.pkg);
        if (ms.pkg != null) s.add(ms.pkg);
        try {
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : imm.getInputMethodList()) s.add(i.getPackageName());
        } catch (Exception ignored) { }
        return s;
    }

    static boolean isSettingsPkg(String pkg) {
        return pkg != null && (pkg.equals("com.android.settings") || pkg.endsWith(".settings"));
    }

    // ── Setup dialogs (called from the home page) ────────────
    static void showSetup(final Activity a) {
        if (isActive(a)) { begin(a); return; }
        String[] items = {"⏱   Time Duration", "🔒   Password Lock", "⏱🔒   Time + Password"};
        new AlertDialog.Builder(a)
            .setTitle("📞 Phone Mode — choose lock")
            .setItems(items, (d, which) -> askDetails(a, which + 1))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static void askDetails(final Activity a, final int mode) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 22);
        box.setPadding(pad, Ui.dp(a, 10), pad, 0);

        final EditText min = new EditText(a);
        min.setHint("Duration in minutes (e.g. 30)");
        min.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText p1 = new EditText(a), p2 = new EditText(a);
        p1.setHint("Password (min 4 characters)");
        p2.setHint("Confirm password");
        p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        p2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (mode != MODE_PASSWORD) box.addView(min);
        if (mode != MODE_TIMER) { box.addView(p1); box.addView(p2); }

        String title = mode == MODE_TIMER ? "⏱ Time Duration" : mode == MODE_PASSWORD ? "🔒 Password Lock" : "⏱🔒 Time + Password";
        final AlertDialog d = new AlertDialog.Builder(a)
            .setTitle(title).setView(box)
            .setPositiveButton("Start", null)
            .setNegativeButton("Cancel", null)
            .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long dur = 0;
            String pw = null;
            if (mode != MODE_PASSWORD) {
                try { dur = Long.parseLong(min.getText().toString().trim()) * 60_000L; } catch (Exception e) { dur = 0; }
                if (dur < 60_000L || dur > 24 * 60 * 60_000L) {
                    Toast.makeText(a, "Enter 1 – 1440 minutes", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (mode != MODE_TIMER) {
                pw = p1.getText().toString();
                if (pw.length() < 4) { Toast.makeText(a, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show(); return; }
                if (!pw.equals(p2.getText().toString())) { Toast.makeText(a, "Passwords do not match", Toast.LENGTH_SHORT).show(); return; }
            }
            d.dismiss();
            start(a, mode, dur, pw);
            begin(a);
        }));
        d.show();
    }

    private static void begin(Activity a) {
        resetLauncherState(a);
        setHomeEnabled(a, true);
        a.startActivity(new Intent(a, PhoneLauncherActivity.class));
    }
}
