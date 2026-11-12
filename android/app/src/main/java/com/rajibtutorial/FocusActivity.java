package com.rajibtutorial;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FocusActivity extends AppCompatActivity {

    private FocusManager fm;

    // Views — main tabs
    private LinearLayout tabApps, tabSession, tabLock;
    private View         pageApps, pageSession, pageLock;

    // Session page
    private NumberPicker pickerHour, pickerMin;
    private RadioButton  rbDaily, rbHourly;
    private TextView     btnStartSession;
    private TextView     sessionStatus;

    // Lock page
    private LinearLayout  selfLockSection, parentLockSection;
    private Switch        switchSelfLock;
    private TextView      btnSetSelfPin, btnSetParentPin, btnClearParentPin;
    private TextView      lockStatusText;

    // Apps page
    private LinearLayout  appListContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_focus);
        fm = new FocusManager(this);

        bindViews();
        setupTabs();
        renderSessionPage();
        renderLockPage();
        renderAppsPage();

        // action parameter check — unlock_app এলে সরাসরি PIN dialog
        String action = getIntent().getStringExtra("action");
        if ("unlock_app".equals(action)) {
            String pkg   = getIntent().getStringExtra("pkg");
            String label = getIntent().getStringExtra("label");
            showUnlockDialog(pkg, label);
        }
    }

    private void bindViews() {
        tabApps    = findViewById(R.id.tabApps);
        tabSession = findViewById(R.id.tabSession);
        tabLock    = findViewById(R.id.tabLock);
        pageApps   = findViewById(R.id.pageApps);
        pageSession= findViewById(R.id.pageSession);
        pageLock   = findViewById(R.id.pageLock);

        pickerHour   = findViewById(R.id.pickerHour);
        pickerMin    = findViewById(R.id.pickerMin);
        rbDaily      = findViewById(R.id.rbDaily);
        rbHourly     = findViewById(R.id.rbHourly);
        btnStartSession = findViewById(R.id.btnStartSession);
        sessionStatus   = findViewById(R.id.sessionStatus);

        switchSelfLock   = findViewById(R.id.switchSelfLock);
        btnSetSelfPin    = findViewById(R.id.btnSetSelfPin);
        btnSetParentPin  = findViewById(R.id.btnSetParentPin);
        btnClearParentPin= findViewById(R.id.btnClearParentPin);
        lockStatusText   = findViewById(R.id.lockStatusText);

        appListContainer = findViewById(R.id.appListContainer);

        // Back button
        findViewById(R.id.focusBackBtn).setOnClickListener(v -> finish());
    }

    // ── Tabs ──────────────────────────────────────────────────────────────
    private void setupTabs() {
        tabApps.setOnClickListener(v    -> showPage(0));
        tabSession.setOnClickListener(v -> showPage(1));
        tabLock.setOnClickListener(v    -> showPage(2));
        showPage(1); // Session page default
    }

    private void showPage(int idx) {
        pageApps.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageSession.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageLock.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);

        int active   = 0xFFE8365D;
        int inactive = 0xFF9E988F;
        ((TextView)tabApps.getChildAt(0)).setTextColor(idx==0 ? active : inactive);
        ((TextView)tabSession.getChildAt(0)).setTextColor(idx==1 ? active : inactive);
        ((TextView)tabLock.getChildAt(0)).setTextColor(idx==2 ? active : inactive);
    }

    // ── Session Page ──────────────────────────────────────────────────────
    private void renderSessionPage() {
        pickerHour.setMinValue(0); pickerHour.setMaxValue(23);
        pickerMin.setMinValue(1);  pickerMin.setMaxValue(59);
        pickerHour.setValue(0);    pickerMin.setValue(30);

        updateSessionStatus();

        btnStartSession.setOnClickListener(v -> {
            if (!checkOverlayPermission()) return;
            if (!checkUsagePermission())   return;

            int hours = pickerHour.getValue();
            int mins  = pickerMin.getValue();
            long durationMs = (hours * 3600L + mins * 60L) * 1000L;

            // Self-lock check
            if (fm.isSelfLocked() && fm.hasSelfPin()) {
                showPinDialog("Self-lock PIN দিন", pin -> {
                    if (fm.checkSelfPin(pin)) startFocusSession(durationMs);
                    else Toast.makeText(this, "ভুল PIN", Toast.LENGTH_SHORT).show();
                });
            } else {
                startFocusSession(durationMs);
            }
        });
    }

    private void startFocusSession(long durationMs) {
        fm.setFocusEnabled(true);
        fm.startSession(durationMs);
        startService(new Intent(this, FocusOverlayService.class)
            .setAction(FocusOverlayService.ACTION_START));
        updateSessionStatus();
        Toast.makeText(this, "Focus Mode শুরু হয়েছে! ✅", Toast.LENGTH_SHORT).show();
    }

    private void updateSessionStatus() {
        if (fm.isSessionActive()) {
            long rem  = fm.getRemainingMs();
            long mins = rem / 60000;
            long secs = (rem % 60000) / 1000;
            sessionStatus.setText("✅ চলছে — " + mins + " মিনিট " + secs + " সেকেন্ড বাকি");
            sessionStatus.setTextColor(0xFF2E7D32);
            btnStartSession.setText("Session বাড়াও");
        } else {
            sessionStatus.setText("⏸ বন্ধ আছে");
            sessionStatus.setTextColor(0xFF9E988F);
            btnStartSession.setText("Focus Mode শুরু করুন");
        }
        // Refresh every second if active
        if (fm.isSessionActive()) {
            new Handler().postDelayed(this::updateSessionStatus, 1000);
        }
    }

    // ── Lock Page ─────────────────────────────────────────────────────────
    private void renderLockPage() {
        updateLockStatus();

        switchSelfLock.setChecked(fm.isSelfLocked());
        switchSelfLock.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !fm.hasSelfPin()) {
                // PIN set না থাকলে আগে set করতে হবে
                showSetPinDialog("Self-lock এর PIN সেট করুন", pin -> {
                    fm.setSelfPin(pin);
                    fm.setSelfLocked(true);
                    updateLockStatus();
                }, () -> switchSelfLock.setChecked(false));
            } else if (!checked) {
                if (fm.hasSelfPin()) {
                    showPinDialog("PIN দিয়ে self-lock খুলুন", pin -> {
                        if (fm.checkSelfPin(pin) || (fm.hasParentPin() && fm.checkParentPin(pin))) {
                            fm.setSelfLocked(false);
                            updateLockStatus();
                        } else {
                            Toast.makeText(this, "ভুল PIN", Toast.LENGTH_SHORT).show();
                            switchSelfLock.setChecked(true);
                        }
                    });
                } else {
                    fm.setSelfLocked(false);
                }
            } else {
                fm.setSelfLocked(true);
                updateLockStatus();
            }
        });

        btnSetSelfPin.setOnClickListener(v ->
            showSetPinDialog("নতুন Self-lock PIN", pin -> {
                fm.setSelfPin(pin);
                Toast.makeText(this, "Self PIN সেট হয়েছে ✅", Toast.LENGTH_SHORT).show();
            }, null));

        btnSetParentPin.setOnClickListener(v -> {
            if (fm.hasParentPin()) {
                // পুরনো PIN verify করে নতুন set করতে হবে
                showPinDialog("পুরনো Parental PIN", pin -> {
                    if (fm.checkParentPin(pin)) {
                        showSetPinDialog("নতুন Parental PIN", newPin -> {
                            fm.setParentPin(newPin);
                            Toast.makeText(this, "Parental PIN পরিবর্তিত হয়েছে ✅", Toast.LENGTH_SHORT).show();
                        }, null);
                    } else Toast.makeText(this, "ভুল PIN", Toast.LENGTH_SHORT).show();
                });
            } else {
                showSetPinDialog("Parental PIN সেট করুন", pin -> {
                    fm.setParentPin(pin);
                    updateLockStatus();
                    Toast.makeText(this, "Parental PIN সেট হয়েছে ✅", Toast.LENGTH_SHORT).show();
                }, null);
            }
        });

        btnClearParentPin.setOnClickListener(v ->
            showPinDialog("Parental PIN দিন", pin -> {
                if (fm.checkParentPin(pin)) {
                    fm.clearParentPin();
                    updateLockStatus();
                    Toast.makeText(this, "Parental PIN সরানো হয়েছে", Toast.LENGTH_SHORT).show();
                } else Toast.makeText(this, "ভুল PIN", Toast.LENGTH_SHORT).show();
            }));
    }

    private void updateLockStatus() {
        boolean selfLock = fm.isSelfLocked();
        boolean parentPin = fm.hasParentPin();
        String status = "";
        if (selfLock)  status += "🔒 Self-lock চালু\n";
        if (parentPin) status += "👨‍👩‍👦 Parental control চালু";
        if (status.isEmpty()) status = "কোনো lock নেই";
        lockStatusText.setText(status.trim());
        btnClearParentPin.setVisibility(parentPin ? View.VISIBLE : View.GONE);
    }

    // ── Apps Page ─────────────────────────────────────────────────────────
    private void renderAppsPage() {
        appListContainer.removeAllViews();

        // Installed apps load করো (background thread)
        new Thread(() -> {
            PackageManager pm    = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

            // User installed apps ফিল্টার করো
            List<ApplicationInfo> userApps = new ArrayList<>();
            for (ApplicationInfo a : apps) {
                if ((a.flags & ApplicationInfo.FLAG_SYSTEM) == 0) userApps.add(a);
                // কিছু common system apps যোগ করো
                else if (isCommonApp(a.packageName)) userApps.add(a);
            }
            Collections.sort(userApps, (x, y) ->
                pm.getApplicationLabel(x).toString()
                  .compareToIgnoreCase(pm.getApplicationLabel(y).toString()));

            List<FocusManager.AppRule> rules = fm.getAppRules();

            runOnUiThread(() -> {
                for (ApplicationInfo info : userApps) {
                    String label = pm.getApplicationLabel(info).toString();
                    String pkg   = info.packageName;
                    if (pkg.equals(getPackageName())) continue;

                    // এই app এর বিদ্যমান rule আছে কিনা
                    FocusManager.AppRule existing = null;
                    for (FocusManager.AppRule r : rules) {
                        if (r.packageName.equals(pkg)) { existing = r; break; }
                    }

                    addAppRow(label, pkg, existing);
                }
            });
        }).start();
    }

    private boolean isCommonApp(String pkg) {
        return pkg.contains("youtube") || pkg.contains("facebook") ||
               pkg.contains("instagram") || pkg.contains("tiktok") ||
               pkg.contains("snapchat") || pkg.contains("twitter") ||
               pkg.contains("whatsapp") || pkg.contains("telegram") ||
               pkg.contains("chrome") || pkg.contains("firefox") ||
               pkg.contains("netflix") || pkg.contains("spotify");
    }

    private void addAppRow(String label, String pkg, FocusManager.AppRule existing) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_app_rule, appListContainer, false);

        TextView  tvLabel   = row.findViewById(R.id.appRuleLabel);
        TextView  tvUsage   = row.findViewById(R.id.appRuleUsage);
        Switch    swBlock   = row.findViewById(R.id.swBlock);
        TextView  btnEdit   = row.findViewById(R.id.btnEditRule);

        tvLabel.setText(label);

        int dailyUsed = fm.getDailyUsage(pkg);
        tvUsage.setText(dailyUsed > 0 ? "আজ: " + dailyUsed + " মিনিট" : "");

        if (existing != null) {
            swBlock.setChecked(existing.blocked);
            String info = "";
            if (existing.dailyLimitMin  > 0) info += "দৈনিক: " + existing.dailyLimitMin  + "m  ";
            if (existing.hourlyLimitMin > 0) info += "প্রতি ঘণ্টা: " + existing.hourlyLimitMin + "m";
            if (!info.isEmpty()) tvUsage.setText(tvUsage.getText() + "\n" + info.trim());
        }

        swBlock.setOnCheckedChangeListener((btn, checked) -> {
            FocusManager.AppRule r = getOrCreateRule(label, pkg);
            r.blocked = checked;
            fm.upsertRule(r);
        });

        btnEdit.setOnClickListener(v -> showAppRuleDialog(label, pkg, existing));

        appListContainer.addView(row);
    }

    private FocusManager.AppRule getOrCreateRule(String label, String pkg) {
        for (FocusManager.AppRule r : fm.getAppRules()) {
            if (r.packageName.equals(pkg)) return r;
        }
        FocusManager.AppRule r = new FocusManager.AppRule();
        r.packageName = pkg; r.appLabel = label;
        return r;
    }

    private void showAppRuleDialog(String label, String pkg, FocusManager.AppRule existing) {
        View dlgView = LayoutInflater.from(this).inflate(R.layout.dialog_app_rule, null);
        NumberPicker npDaily  = dlgView.findViewById(R.id.npDailyLimit);
        NumberPicker npHourly = dlgView.findViewById(R.id.npHourlyLimit);

        npDaily.setMinValue(0); npDaily.setMaxValue(480);
        npHourly.setMinValue(0); npHourly.setMaxValue(60);
        if (existing != null) {
            npDaily.setValue(existing.dailyLimitMin);
            npHourly.setValue(existing.hourlyLimitMin);
        }

        new AlertDialog.Builder(this)
            .setTitle(label + " — সময় সীমা")
            .setView(dlgView)
            .setPositiveButton("সংরক্ষণ করুন", (d, w) -> {
                FocusManager.AppRule r = getOrCreateRule(label, pkg);
                r.dailyLimitMin  = npDaily.getValue();
                r.hourlyLimitMin = npHourly.getValue();
                fm.upsertRule(r);
                renderAppsPage();
                Toast.makeText(this, "সংরক্ষিত ✅", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("নিয়ম মুছুন", (d, w) -> {
                fm.removeRule(pkg);
                renderAppsPage();
            })
            .setNegativeButton("বাতিল", null)
            .show();
    }

    // ── Permission checks ─────────────────────────────────────────────────
    private boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                .setTitle("\"Display over apps\" Permission দরকার")
                .setMessage("Focus Mode চালু করতে Android কে অনুমতি দিন।\n\nSettings → এই App → Display over other apps → চালু করুন।")
                .setPositiveButton("Settings খুলুন", (d, w) -> startActivity(
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("পরে", null)
                .show();
            return false;
        }
        return true;
    }

    private boolean checkUsagePermission() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            android.app.usage.UsageStatsManager usm =
                (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> stats =
                usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    now - 1000, now);
            if (stats == null || stats.isEmpty()) {
                new AlertDialog.Builder(this)
                    .setTitle("\"Usage Access\" Permission দরকার")
                    .setMessage("কোন app কতক্ষণ চলছে তা দেখতে Permission দিন।\n\nSettings → Usage Access → Rajib Tutorial → চালু করুন।")
                    .setPositiveButton("Settings খুলুন", (d, w) -> startActivity(
                        new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))
                    .setNegativeButton("পরে", null)
                    .show();
                return false;
            }
        } catch (Exception ignored) {}
        return true;
    }

    // ── PIN dialogs ───────────────────────────────────────────────────────
    interface PinCallback   { void onPin(String pin); }
    interface VoidCallback  { void run(); }

    private void showPinDialog(String title, PinCallback cb) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_pin, null);
        EditText et = v.findViewById(R.id.pinInput);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(v)
            .setPositiveButton("ঠিক আছে", (d, w) -> cb.onPin(et.getText().toString().trim()))
            .setNegativeButton("বাতিল", null)
            .show();
    }

    private void showSetPinDialog(String title, PinCallback cb, VoidCallback onCancel) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_set_pin, null);
        EditText et1 = v.findViewById(R.id.pinInput1);
        EditText et2 = v.findViewById(R.id.pinInput2);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(v)
            .setPositiveButton("সেট করুন", (d, w) -> {
                String p1 = et1.getText().toString().trim();
                String p2 = et2.getText().toString().trim();
                if (p1.length() < 4) {
                    Toast.makeText(this, "কমপক্ষে ৪ সংখ্যার PIN দিন", Toast.LENGTH_SHORT).show();
                    if (onCancel != null) onCancel.run();
                } else if (!p1.equals(p2)) {
                    Toast.makeText(this, "দুটো PIN মিলছে না", Toast.LENGTH_SHORT).show();
                    if (onCancel != null) onCancel.run();
                } else cb.onPin(p1);
            })
            .setNegativeButton("বাতিল", (d, w) -> { if (onCancel != null) onCancel.run(); })
            .show();
    }

    private void showUnlockDialog(String pkg, String label) {
        showPinDialog("\"" + label + "\" unlock করুন", pin -> {
            boolean ok = (fm.hasSelfPin() && fm.checkSelfPin(pin)) ||
                         (fm.hasParentPin() && fm.checkParentPin(pin));
            if (ok) {
                // এই app কে temporarily allow করো (rule remove)
                fm.removeRule(pkg);
                Toast.makeText(this, label + " unlock হয়েছে", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "ভুল PIN", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }
}
