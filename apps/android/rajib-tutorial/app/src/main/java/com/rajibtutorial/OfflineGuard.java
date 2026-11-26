package com.rajibtutorial;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline lessons only play while the app has been online within the last 24 hours.
 * The app verifies the internet connection (a real HTTPS request, not just "Wi-Fi is on") when it opens and
 * whenever the last check is getting old. If a whole day passes without a successful check and the phone is
 * still offline, saved lessons are locked until the phone connects once.
 *
 * Moving the phone's clock back does not help: the time since the last check is measured with the monotonic
 * clock within one boot, and across a reboot a wall clock that is behind the latest time ever seen locks the lessons.
 */
final class OfflineGuard {
    private OfflineGuard() {}

    /** How long one successful check keeps offline lessons unlocked. */
    static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    /** While online, re-verify when the last check is older than this (so the window never runs out under the user). */
    private static final long REFRESH_MS = 6L * 60 * 60 * 1000;
    private static final long SKEW_MS = 5L * 60 * 1000;

    static final String LOCK_MSG =
        "🔒 Offline lessons are locked.\nConnect to the internet once to unlock them — the app checks the connection every 24 hours.";

    interface Callback { void done(boolean ok); }

    private static final String PREFS = "rt_guard";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sBusy = new AtomicBoolean(false);

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int bootCount(Context c) {
        if (Build.VERSION.SDK_INT >= 24) {
            try { return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
            catch (Throwable ignored) { }
        }
        return -1;
    }

    /** Milliseconds since the last successful check, or -1 if unknown / the clock was turned back (= not trusted). */
    private static long sinceLastCheck(Context c) {
        SharedPreferences p = sp(c);
        long wall = p.getLong("wall", 0);
        if (wall <= 0) return -1;
        long nowWall = System.currentTimeMillis();
        long nowEl = SystemClock.elapsedRealtime();
        int boot = bootCount(c);
        long maxSeen = p.getLong("max", wall);
        if (nowWall > maxSeen) p.edit().putLong("max", nowWall).apply();

        boolean sameBoot = boot != -1 && boot == p.getInt("boot", -2) && nowEl >= p.getLong("el", Long.MAX_VALUE);
        if (sameBoot) return nowEl - p.getLong("el", 0);          // monotonic: immune to clock changes
        if (nowWall + SKEW_MS < maxSeen) return -1;                // wall clock is behind a time we have already seen
        long d = nowWall - wall;
        return d < -SKEW_MS ? -1 : Math.max(0, d);
    }

    /** True if the last successful online check is less than 24 hours old. */
    static boolean isValid(Context c) {
        long d = sinceLastCheck(c);
        return d >= 0 && d < WINDOW_MS;
    }

    /** Hours left before saved lessons lock (0 if locked). */
    static int hoursLeft(Context c) {
        long d = sinceLastCheck(c);
        if (d < 0 || d >= WINDOW_MS) return 0;
        return (int) Math.max(1, Math.ceil((WINDOW_MS - d) / 3600000.0));
    }

    /** Checks the connection now if the last check is getting old (call on app start / resume). Cheap when recent. */
    static void checkIfDue(Context c) {
        long d = sinceLastCheck(c);
        if (d >= 0 && d < REFRESH_MS) return;
        if (!OfflineCache.online(c)) return;
        verify(c, null);
    }

    /** cb(true) if lessons are unlocked now: still inside the 24 h window, or the phone is online and the check passes. */
    static void ensure(Context c, Callback cb) {
        if (isValid(c)) { cb.done(true); return; }
        if (!OfflineCache.online(c)) { cb.done(false); return; }
        verify(c, cb);
    }

    private static void verify(Context c, Callback cb) {
        final Context app = c.getApplicationContext();
        if (!sBusy.compareAndSet(false, true)) {                 // a check is already running: re-ask shortly
            if (cb != null) MAIN.postDelayed(() -> cb.done(isValid(app)), 2500);
            return;
        }
        new Thread(() -> {
            boolean ok = ping();
            if (ok) {
                long nowWall = System.currentTimeMillis();
                SharedPreferences p = sp(app);
                p.edit().putLong("wall", nowWall).putLong("el", SystemClock.elapsedRealtime())
                    .putInt("boot", bootCount(app)).putLong("max", Math.max(nowWall, p.getLong("max", 0))).apply();
            }
            sBusy.set(false);
            if (cb != null) MAIN.post(() -> cb.done(ok));
        }, "rt-guard").start();
    }

    /** A real HTTPS round trip to the app's server (Firestore); any HTTP answer proves a working internet path. */
    private static boolean ping() {
        for (String u : new String[] { Fs.BASE + "?pageSize=1", "https://clients3.google.com/generate_204" }) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setUseCaches(false);
                int code = conn.getResponseCode();
                if (code > 0 && conn.getHeaderField("Date") != null) return true;
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }
}
