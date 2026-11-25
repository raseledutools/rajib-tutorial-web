package com.rajibtutorial;

import android.content.Context;
import android.content.SharedPreferences;

/** The few switches shown in Settings (same idea as the Windows exe's Settings tab). */
final class AppSettings {
    private AppSettings() { }

    static final String NOTIFICATIONS = "notifications";
    static final String NOTIFY_SOUND = "notifySound";
    static final String SCREEN_PROTECT = "screenProtect";
    static final String ADULT_BLOCK = "adultBlock";
    static final String ADULT_EXTERNAL = "adultExternal";

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("rt_settings", Context.MODE_PRIVATE);
    }

    static boolean get(Context c, String key) { return p(c).getBoolean(key, true); }

    static void set(Context c, String key, boolean v) { p(c).edit().putBoolean(key, v).apply(); }

    static boolean notifications(Context c) { return get(c, NOTIFICATIONS); }
    static boolean notifySound(Context c) { return get(c, NOTIFY_SOUND); }
    /** Browser / reader screens only; course videos stay protected no matter what. */
    static boolean screenProtect(Context c) { return get(c, SCREEN_PROTECT); }
    /** Applies the screenshot-protection switch to a window. force = always protected (course video, payment, calls). */
    static void applySecureFlag(android.app.Activity act, boolean force) {
        if (force || screenProtect(act)) act.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else act.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }
    static boolean adultBlock(Context c) { return get(c, ADULT_BLOCK); }
    static boolean adultExternal(Context c) { return get(c, ADULT_EXTERNAL); }
}
