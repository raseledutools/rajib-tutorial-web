package com.rajibtutorial;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Saves the stack trace of a crash; the next app start shows it with a Copy button (so it can be pasted for debugging). */
final class CrashLog {
    private CrashLog() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "last_crash.txt"); }

    static void install(Context c) {
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String s = "Thread: " + t.getName() + "\nAndroid " + android.os.Build.VERSION.SDK_INT + " / "
                    + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n\n" + sw;
                try (FileOutputStream o = new FileOutputStream(file(app))) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    static void showIfAny(final Activity a) {
        try {
            final File f = file(a);
            if (!f.exists() || f.length() == 0) return;
            byte[] b = new byte[(int) Math.min(f.length(), 12000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { in.read(b); }
            final String txt = new String(b, StandardCharsets.UTF_8);
            TextView tv = new TextView(a);
            tv.setText(txt);
            tv.setTextSize(10);
            tv.setTextIsSelectable(true);
            tv.setPadding(24, 24, 24, 24);
            ScrollView sv = new ScrollView(a);
            sv.addView(tv);
            new AlertDialog.Builder(a)
                .setTitle("App crashed last time")
                .setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("crash", txt));
                    Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                })
                .setNegativeButton("Close", (d, w) -> { //noinspection ResultOfMethodCallIgnored
                    f.delete(); })
                .show();
        } catch (Throwable ignored) { }
    }
}
