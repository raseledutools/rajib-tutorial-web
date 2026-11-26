package com.rajibtutorial;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.HashSet;
import java.util.Set;

/** "Apps Lock": user picks apps, a password is asked every time one of them opens. */
final class AppLock {
    private AppLock() {}

    private static final String PREFS = "AppLockPrefs", K_SET = "locked", K_PW = "pw_hash";

    /** package the user just unlocked (cleared when they leave it / screen off). */
    static volatile String unlockedPkg = null;

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static Set<String> locked(Context c) { return new HashSet<>(p(c).getStringSet(K_SET, new HashSet<String>())); }

    static void setLocked(Context c, Set<String> s) {
        p(c).edit().putStringSet(K_SET, new HashSet<>(s)).apply();
        if (s.isEmpty()) c.stopService(new Intent(c, AppLockService.class));
        else ensureService(c);
    }

    static boolean hasPassword(Context c) { return p(c).contains(K_PW); }
    static void setPassword(Context c, String pw) { p(c).edit().putString(K_PW, hash(pw)).apply(); }
    static boolean checkPassword(Context c, String pw) { return hash(pw).equals(p(c).getString(K_PW, "")); }

    static void ensureService(Context c) {
        if (locked(c).isEmpty()) return;
        Intent s = new Intent(c, AppLockService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
        } catch (Exception ignored) { }
    }

    private static String hash(String in) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest(in.getBytes("UTF-8"))) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return in; }
    }
}
