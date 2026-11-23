package com.rajibtutorial;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Saves the stack trace of an app crash to a file and shows it on the next launch,
 * so the exact cause of a crash can be read (and copied) without adb / logcat.
 */
final class CrashLog {
    private CrashLog() {}

    private static final String FILE = "last_crash.txt";
    private static boolean installed = false;

    static void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("Thread: " + t.getName());
                pw.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
                e.printStackTrace(pw);
                pw.flush();
                String s = sw.toString();
                if (s.length() > 6000) s = s.substring(0, 6000);
                FileOutputStream fo = new FileOutputStream(new File(app.getFilesDir(), FILE));
                fo.write(s.getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** Returns the saved crash text (and deletes it), or null when the last run did not crash. */
    private static String take(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            f.delete();
            String s = bos.toString("UTF-8");
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    static void showIfAny(final Activity a) {
        final String s = take(a);
        if (s == null) return;
        try {
            new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("App crashed last time")
                .setMessage(s)
                .setPositiveButton("Copy", (d, w) -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("crash", s));
                    } catch (Throwable ignored) { }
                })
                .setNegativeButton("Close", null)
                .show();
        } catch (Throwable ignored) { }
    }
}
