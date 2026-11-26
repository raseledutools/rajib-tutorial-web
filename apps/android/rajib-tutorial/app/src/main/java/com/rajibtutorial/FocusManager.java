package com.rajibtutorial;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Focus Mode এর সব data/logic এখানে।
 * SharedPreferences এ persist করে।
 */
public class FocusManager {

    private static final String PREFS = "FocusPrefs";

    // Keys
    private static final String KEY_ENABLED        = "focus_enabled";
    private static final String KEY_LOCKED         = "focus_locked";       // self-lock চালু আছে?
    private static final String KEY_PARENT_PIN      = "parent_pin";         // parental PIN (হ্যাশ)
    private static final String KEY_SELF_PIN        = "self_pin";           // self-lock PIN (হ্যাশ)
    private static final String KEY_APP_RULES       = "app_rules";          // JSON
    private static final String KEY_SESSION_END     = "session_end_ms";     // current session শেষ সময়
    private static final String KEY_FOCUS_START_MS  = "focus_start_ms";     // block শুরু হওয়ার epoch

    private final SharedPreferences prefs;

    public FocusManager(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── Enable / Disable ─────────────────────────────────────────────────
    public boolean isFocusEnabled() { return prefs.getBoolean(KEY_ENABLED, false); }

    public void setFocusEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply();
        if (!on) prefs.edit().remove(KEY_SESSION_END).remove(KEY_FOCUS_START_MS).apply();
    }

    // ── Self-lock ────────────────────────────────────────────────────────
    public boolean isSelfLocked()     { return prefs.getBoolean(KEY_LOCKED, false); }
    public void    setSelfLocked(boolean v) { prefs.edit().putBoolean(KEY_LOCKED, v).apply(); }

    public boolean hasSelfPin()   { return prefs.contains(KEY_SELF_PIN); }
    public boolean hasParentPin() { return prefs.contains(KEY_PARENT_PIN); }

    public void setSelfPin(String pin)   { prefs.edit().putString(KEY_SELF_PIN,   hash(pin)).apply(); }
    public void setParentPin(String pin) { prefs.edit().putString(KEY_PARENT_PIN, hash(pin)).apply(); }

    public boolean checkSelfPin(String pin)   { return hash(pin).equals(prefs.getString(KEY_SELF_PIN, "")); }
    public boolean checkParentPin(String pin) { return hash(pin).equals(prefs.getString(KEY_PARENT_PIN, "")); }

    public void clearParentPin() { prefs.edit().remove(KEY_PARENT_PIN).apply(); }
    public void clearSelfPin()   { prefs.edit().remove(KEY_SELF_PIN).apply(); }

    // ── Session timing ───────────────────────────────────────────────────
    /** Session শেষ হওয়ার epoch ms। -1 মানে set নেই। */
    public long getSessionEndMs() { return prefs.getLong(KEY_SESSION_END, -1); }

    public void startSession(long durationMs) {
        long end = System.currentTimeMillis() + durationMs;
        prefs.edit()
            .putLong(KEY_SESSION_END, end)
            .putLong(KEY_FOCUS_START_MS, System.currentTimeMillis())
            .apply();
    }

    public boolean isSessionActive() {
        long end = getSessionEndMs();
        return end > 0 && System.currentTimeMillis() < end;
    }

    public long getRemainingMs() {
        long end = getSessionEndMs();
        if (end <= 0) return 0;
        return Math.max(0, end - System.currentTimeMillis());
    }

    /** The 25-minute quick Focus used by the Adult Block focus page (Safe Browser tab + other browsers' overlay). */
    public static final long QUICK_FOCUS_MS = 25L * 60 * 1000;

    public static void quickStart(Context c) { quickStart(c, QUICK_FOCUS_MS); }

    public static void quickStart(Context c, long durationMs) {
        FocusManager fm = new FocusManager(c);
        fm.setFocusEnabled(true);
        fm.startSession(durationMs);
        try {
            android.content.Intent si = new android.content.Intent(c, FocusOverlayService.class).setAction(FocusOverlayService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= 26) androidx.core.content.ContextCompat.startForegroundService(c, si); else c.startService(si);
        } catch (Exception ignored) { }
    }

    // ── Allowed apps (Focus চলাকালীন শুধু এই app গুলো চলবে — exe এর "Allow mode" এর মতো) ──
    private static final String KEY_ALLOWED_APPS = "allowed_apps";

    public java.util.Set<String> getAllowedApps() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_ALLOWED_APPS, "[]"));
            for (int i = 0; i < a.length(); i++) { String s = a.optString(i); if (!s.isEmpty()) out.add(s); }
        } catch (Exception ignored) { }
        return out;
    }

    public void setAllowedApps(java.util.Collection<String> pkgs) {
        JSONArray a = new JSONArray();
        for (String s : pkgs) a.put(s);
        prefs.edit().putString(KEY_ALLOWED_APPS, a.toString()).apply();
    }

    public void clearSession() {
        prefs.edit().remove(KEY_SESSION_END).remove(KEY_FOCUS_START_MS).apply();
    }

    // ── App Rules ────────────────────────────────────────────────────────
    /**
     * AppRule: একটি app এর জন্য প্রতি ঘণ্টায় / প্রতিদিন কত মিনিট।
     */
    public static class AppRule {
        public String packageName;
        public String appLabel;
        public int    dailyLimitMin;   // 0 = unlimited
        public int    hourlyLimitMin;  // 0 = unlimited
        public boolean blocked;        // সম্পূর্ণ block

        public JSONObject toJson() throws Exception {
            return new JSONObject()
                .put("pkg",   packageName)
                .put("label", appLabel)
                .put("daily", dailyLimitMin)
                .put("hourly",hourlyLimitMin)
                .put("blocked", blocked);
        }

        public static AppRule fromJson(JSONObject o) throws Exception {
            AppRule r = new AppRule();
            r.packageName    = o.getString("pkg");
            r.appLabel       = o.optString("label", r.packageName);
            r.dailyLimitMin  = o.optInt("daily",  0);
            r.hourlyLimitMin = o.optInt("hourly", 0);
            r.blocked        = o.optBoolean("blocked", false);
            return r;
        }
    }

    public List<AppRule> getAppRules() {
        List<AppRule> list = new ArrayList<>();
        try {
            String json = prefs.getString(KEY_APP_RULES, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++)
                list.add(AppRule.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return list;
    }

    public void saveAppRules(List<AppRule> rules) {
        try {
            JSONArray arr = new JSONArray();
            for (AppRule r : rules) arr.put(r.toJson());
            prefs.edit().putString(KEY_APP_RULES, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void upsertRule(AppRule rule) {
        List<AppRule> rules = getAppRules();
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).packageName.equals(rule.packageName)) {
                rules.set(i, rule);
                saveAppRules(rules);
                return;
            }
        }
        rules.add(rule);
        saveAppRules(rules);
    }

    public void removeRule(String packageName) {
        List<AppRule> rules = getAppRules();
        rules.removeIf(r -> r.packageName.equals(packageName));
        saveAppRules(rules);
    }

    // ── Usage tracking ───────────────────────────────────────────────────
    /** আজকের usage store করার key */
    private String todayKey(String pkg) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        return "usage_" + pkg + "_" + cal.get(java.util.Calendar.YEAR)
            + "_" + cal.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private String hourKey(String pkg) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        return "usage_" + pkg + "_h" + cal.get(java.util.Calendar.YEAR)
            + "_" + cal.get(java.util.Calendar.DAY_OF_YEAR)
            + "_" + cal.get(java.util.Calendar.HOUR_OF_DAY);
    }

    public void addUsageMinute(String pkg) {
        prefs.edit()
            .putInt(todayKey(pkg), getDailyUsage(pkg) + 1)
            .putInt(hourKey(pkg),  getHourlyUsage(pkg) + 1)
            .apply();
    }

    public int getDailyUsage(String pkg)  { return prefs.getInt(todayKey(pkg), 0); }
    public int getHourlyUsage(String pkg) { return prefs.getInt(hourKey(pkg),  0); }

    public boolean isAppOverLimit(String pkg) {
        for (AppRule r : getAppRules()) {
            if (!r.packageName.equals(pkg)) continue;
            if (r.blocked) return true;
            if (r.dailyLimitMin  > 0 && getDailyUsage(pkg)  >= r.dailyLimitMin)  return true;
            if (r.hourlyLimitMin > 0 && getHourlyUsage(pkg) >= r.hourlyLimitMin) return true;
        }
        return false;
    }

    // ── Simple hash (SHA-256 hex) ─────────────────────────────────────────
    private static String hash(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return input; }
    }
}
