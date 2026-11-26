package com.rajibtutorial;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Focus চলাকালীন কোন app এর window থাকতে দেওয়া হবে: নিজের app, সিস্টেমের দরকারি অংশ (launcher, keyboard, phone call,
 * system dialog, Settings) এবং ব্যবহারকারীর বেছে নেওয়া "Allowed apps"। বাকি সবকিছু Focus পর্দা দিয়ে ঢেকে Home এ পাঠানো হয়।
 */
final class FocusAllow {
    private FocusAllow() { }

    private static final Set<String> SYSTEM = new HashSet<>(Arrays.asList(
        "android", "com.android.systemui", "com.android.settings",
        "com.android.phone", "com.android.server.telecom", "com.android.incallui", "com.android.dialer",
        "com.google.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui", "com.miui.voip",
        "com.android.emergency", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.gms"));

    static boolean isAllowed(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(c.getPackageName()) || SYSTEM.contains(pkg)) return true;
        if (new FocusManager(c).getAllowedApps().contains(pkg)) return true;
        return launchers(c).contains(pkg) || keyboards(c).contains(pkg);
    }

    private static Set<String> launchers(Context c) {
        Set<String> out = new HashSet<>();
        try {
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static Set<String> keyboards(Context c) {
        Set<String> out = new HashSet<>();
        try {
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) for (InputMethodInfo i : imm.getEnabledInputMethodList()) out.add(i.getPackageName());
        } catch (Exception ignored) { }
        return out;
    }
}
