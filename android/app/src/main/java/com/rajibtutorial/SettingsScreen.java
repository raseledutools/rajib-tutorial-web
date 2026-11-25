package com.rajibtutorial;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;

/** Settings tab (same switches as the Windows exe's Settings, minus the Windows-only ones). */
final class SettingsScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final NativeHomeActivity a;

    SettingsScreen(NativeHomeActivity host) { a = host; }

    @Override
    View build() {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG2);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 28));
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        root.addView(Ui.tv(a, "⚙️ Settings", 20, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 4));
        root.addView(Ui.tv(a, "অ্যাপের সেটিং (এই ফোনে সেভ থাকে)", 12.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        LinearLayout card = Ui.card(a);
        card.setPadding(Ui.dp(a, 14), Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 6));
        root.addView(card, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        card.addView(switchRow(AppSettings.NOTIFICATIONS, "🔔 Notifications",
            "Admin-এর পাঠানো নোটিফিকেশন ফোনে দেখাবে। বন্ধ থাকলেও Notifications পাতায় মেসেজ জমা হবে।"));
        card.addView(switchRow(AppSettings.NOTIFY_SOUND, "🔊 Notification Sound",
            "বন্ধ থাকলে নোটিফিকেশন নিঃশব্দে আসবে।"));
        card.addView(switchRow(AppSettings.SCREEN_PROTECT, "🛡️ Screenshot / Screen-Record Protection",
            "চালু থাকলে Safe Browser ও Office Reader-এ স্ক্রিনশট/স্ক্রিন রেকর্ডে কালো আসবে। কোর্স ও লাইভ ভিডিও সবসময় সুরক্ষিত থাকে।"));
        card.addView(switchRow(AppSettings.ADULT_BLOCK, "🔞 Adult Block (Safe Browsing)",
            "চালু থাকলে Safe Browser-এ অ্যাডাল্ট সাইট বন্ধ থাকবে এবং Google / Bing / DuckDuckGo-তে SafeSearch জোর করে চালু থাকবে।"));

        card.addView(switchRow(AppSettings.ADULT_EXTERNAL, "🔞 Adult Block — সব ব্রাউজার (Chrome, Edge, Firefox, Brave...)",
            "চালু থাকলে অন্য যেকোনো ব্রাউজারে অ্যাডাল্ট সার্চ বা সাইট খুললে সেই পাতা বন্ধ হয়ে Focus পর্দা আসবে, যেখান থেকে Focus শুরু করা যায়। এর জন্য নিচের Accessibility অনুমতি লাগবে।"));

        LinearLayout acts = Ui.card(a);
        acts.setPadding(Ui.dp(a, 14), Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 6));
        root.addView(acts, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));
        accDesc = Ui.tv(a, "", 12, Ui.SUB, false);
        acts.addView(accessRow());
        acts.addView(actionRow("🔔 Test Notification", "একটা টেস্ট নোটিফিকেশন পাঠিয়ে দেখুন।", "Test", v -> testNotify()));
        acts.addView(actionRow("⟳ Update", "নতুন ভার্সন আছে কিনা দেখুন।", "Check", v -> checkUpdate()));
        acts.addView(actionRow("🧹 Clear Cache", "টেম্প ক্যাশ মুছবে। লগইন ও অফলাইন ভিডিও মুছবে না।", "Clear", v -> clearCache()));

        String ver = "";
        try { ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView vt = Ui.tv(a, "Version " + ver, 12, Ui.SUB, false);
        vt.setGravity(Gravity.CENTER);
        root.addView(vt, Ui.lpm(a, MATCH, WRAP, 0, 4, 0, 0));
        return sv;
    }

    private TextView accDesc;

    private View accessRow() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        LinearLayout txt = new LinearLayout(a);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(Ui.tv(a, "♿ Accessibility অনুমতি", 14, Ui.TEXT, true));
        txt.addView(accDesc, Ui.lpm(a, MATCH, WRAP, 0, 3, 0, 0));
        row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(Ui.smallBtn(a, "চালু করুন", Ui.ACCENT, v -> askAccessibility()), new LinearLayout.LayoutParams(WRAP, WRAP));
        refreshAcc();
        return row;
    }

    private void refreshAcc() {
        if (accDesc == null) return;
        boolean on = AdultWatchService.isEnabled(a);
        accDesc.setText(on ? "✅ চালু আছে — অন্য ব্রাউজারের Adult Block কাজ করছে।"
            : "❌ বন্ধ — চালু না করলে অন্য ব্রাউজারে Adult Block কাজ করবে না।");
        accDesc.setTextColor(on ? Ui.GREEN : Ui.RED);
    }

    private void askAccessibility() {
        new AlertDialog.Builder(a)
            .setTitle("Accessibility অনুমতি")
            .setMessage("এই অনুমতি শুধু অন্য ব্রাউজারের ঠিকানা-বার ও সার্চ বক্সে অ্যাডাল্ট শব্দ আছে কিনা দেখে, আর পেলে পাতাটি বন্ধ করে Focus পর্দা দেখায়। কোনো তথ্য সেভ বা ইন্টারনেটে পাঠানো হয় না।\n\nSettings এ গিয়ে \"Rajib Tutorial — Adult Block\" খুঁজে চালু করুন।")
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("Settings খুলুন", (d, w) -> {
                try { a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                catch (Exception e) { a.toast("Settings খোলা যায়নি"); }
            }).show();
    }

    @Override
    void onResume() { refreshAcc(); }

    private View switchRow(final String key, String title, String desc) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        LinearLayout txt = new LinearLayout(a);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(Ui.tv(a, title, 14, Ui.TEXT, true));
        txt.addView(Ui.tv(a, desc, 12, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 3, 0, 0));
        row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        Switch sw = new Switch(a);
        sw.setChecked(AppSettings.get(a, key));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> AppSettings.set(a, key, on));
        row.addView(sw, new LinearLayout.LayoutParams(WRAP, WRAP));
        return row;
    }

    private View actionRow(String title, String desc, String btnText, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        LinearLayout txt = new LinearLayout(a);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(Ui.tv(a, title, 14, Ui.TEXT, true));
        txt.addView(Ui.tv(a, desc, 12, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 3, 0, 0));
        row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView b = Ui.smallBtn(a, btnText, Ui.ACCENT, l);
        row.addView(b, new LinearLayout.LayoutParams(WRAP, WRAP));
        return row;
    }

    private void testNotify() {
        if (!AppSettings.notifications(a)) { a.toast("আগে Notifications চালু করুন"); return; }
        try {
            PushManager.createChannel(a);
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(a, PushManager.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_push)
                .setColor(0xFFE8365D)
                .setContentTitle("Rajib Tutorial")
                .setContentText("এটা একটা টেস্ট নোটিফিকেশন ✅")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
            if (!AppSettings.notifySound(a)) b.setSilent(true);
            androidx.core.app.NotificationManagerCompat.from(a).notify(7311, b.build());
            a.toast("টেস্ট নোটিফিকেশন পাঠানো হয়েছে");
        } catch (SecurityException e) {
            a.toast("Notification permission দেওয়া নেই — ফোনের Settings থেকে চালু করুন");
        } catch (Exception e) {
            a.toast("পাঠানো যায়নি");
        }
    }

    private void checkUpdate() {
        a.toast("খোঁজা হচ্ছে…");
        new UpdateChecker(a).checkForUpdate(new UpdateChecker.ManualCheckListener() {
            @Override public void onUpToDate(int installedCode) { a.toast("আপনার অ্যাপ সর্বশেষ ভার্সনে আছে ✅"); }

            @Override public void onUpdateFound(final String version, final String apkUrl, String notes) {
                a.runOnUiThread(() -> new AlertDialog.Builder(a)
                    .setTitle("নতুন ভার্সন " + version)
                    .setMessage("নতুন আপডেট পাওয়া গেছে। ডাউনলোড করবেন?")
                    .setNegativeButton("পরে", null)
                    .setPositiveButton("Download", (d, w) -> {
                        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))); }
                        catch (Exception e) { a.toast("খোলা যায়নি"); }
                    }).show());
            }

            @Override public void onError(String reason) { a.toast("চেক করা যায়নি: " + reason); }
        });
    }

    private void clearCache() {
        long freed = 0;
        try { freed += deleteTree(a.getCacheDir(), false); } catch (Exception ignored) { }
        try { File ext = a.getExternalCacheDir(); if (ext != null) freed += deleteTree(ext, false); } catch (Exception ignored) { }
        try { new android.webkit.WebView(a).clearCache(true); } catch (Exception ignored) { }
        a.toast("Cache পরিষ্কার হয়েছে (" + Math.max(1, freed / 1024) + " KB)");
    }

    /** Deletes the content of a cache folder (never offline videos: those live in no_backup/.sys). */
    private static long deleteTree(File f, boolean self) {
        long n = 0;
        if (f == null || !f.exists()) return 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += deleteTree(k, true);
        if (self) { n += f.length(); //noinspection ResultOfMethodCallIgnored
            f.delete(); }
        return n;
    }
}
