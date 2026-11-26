package com.rajibtutorial;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub — native screen (no WebView): Repos / Actions / Releases.
 * Token user nijei app e boshay (hardcoded na), SharedPreferences (MODE_PRIVATE) e thake.
 * Scope lagbe: repo, workflow.
 */
public class GitHubActivity extends AppCompatActivity {

    private static final String PREF = "gh_prefs";
    private static final String KEY_TOKEN = "token";
    private static final String DEFAULT_REPO = "raseledutools/rajib-tutorial-web";

    private static final int BG = 0xFF0D1117, CARD = 0xFF161B22, BORDER = 0xFF30363D;
    private static final int TXT = 0xFFE6EDF3, DIM = 0xFF8B949E;
    private static final int GREEN = 0xFF3FB950, RED = 0xFFF85149, AMBER = 0xFFD29922;
    private static final int BLUE = 0xFF58A6FF, PURPLE = 0xFF7B2FF7;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String token = "";
    private String repo = DEFAULT_REPO;
    private int tab = 0;
    private int reqId = 0;

    private LinearLayout root, list, tabBar, repoBar;
    private TextView repoLabel;
    private ProgressBar bar;
    private final TextView[] tabViews = new TextView[3];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(PURPLE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        token = prefs().getString(KEY_TOKEN, "");
        if (token.isEmpty()) showTokenScreen(); else showMain();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ───────────────────────── helpers ─────────────────────────
    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView tv(CharSequence t, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private GradientDrawable round(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(round(CARD, 12, BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(5), dp(12), dp(5));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView pill(String text, int color) {
        TextView t = tv(text, 11, color, true);
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        t.setBackground(round((color & 0x00FFFFFF) | 0x26000000, 20, 0));
        return t;
    }

    private String str(JSONObject o, String k) {
        return o.isNull(k) ? "" : o.optString(k, "");
    }

    private String day(String s) {
        return s != null && s.length() >= 10 ? s.substring(0, 10) : (s == null ? "" : s);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Open করা যায়নি");
        }
    }

    // ───────────────────────── header ─────────────────────────
    private void addHeader(boolean actions) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(PURPLE);
        h.setPadding(dp(8), dp(10), dp(8), dp(10));

        TextView back = tv("←", 22, 0xFFFFFFFF, true);
        back.setPadding(dp(12), dp(4), dp(12), dp(4));
        back.setOnClickListener(v -> finish());
        h.addView(back);

        TextView title = tv("🐙  GitHub", 18, 0xFFFFFFFF, true);
        h.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (actions) {
            TextView refresh = tv("↻", 22, 0xFFFFFFFF, true);
            refresh.setPadding(dp(12), dp(4), dp(12), dp(4));
            refresh.setOnClickListener(v -> load());
            h.addView(refresh);

            TextView out = tv("Logout", 13, 0xFFFFD6D6, true);
            out.setPadding(dp(12), dp(4), dp(12), dp(4));
            out.setOnClickListener(v -> {
                prefs().edit().remove(KEY_TOKEN).apply();
                token = "";
                showTokenScreen();
            });
            h.addView(out);
        }
        root.addView(h, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ───────────────────────── token screen ─────────────────────────
    private void showTokenScreen() {
        root.removeAllViews();
        addHeader(false);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(22), dp(22), dp(22), dp(22));

        box.addView(tv("GitHub Connect", 24, TXT, true));
        TextView sub = tv("Personal Access Token দাও (scope: repo, workflow)। Token শুধু এই ফোনে থাকবে।", 13, DIM, false);
        sub.setPadding(0, dp(6), 0, dp(20));
        box.addView(sub);

        final EditText et = new EditText(this);
        et.setHint("ghp_...");
        et.setHintTextColor(DIM);
        et.setTextColor(TXT);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setBackground(round(CARD, 10, BORDER));
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView go = tv("Connect", 16, 0xFFFFFFFF, true);
        go.setGravity(Gravity.CENTER);
        go.setPadding(dp(14), dp(14), dp(14), dp(14));
        go.setBackground(round(GREEN, 12, 0));
        go.setOnClickListener(v -> {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) { toast("Token দাও"); return; }
            token = t;
            prefs().edit().putString(KEY_TOKEN, t).apply();
            showMain();
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(16);
        box.addView(go, glp);

        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    // ───────────────────────── main screen ─────────────────────────
    private void showMain() {
        root.removeAllViews();
        addHeader(true);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(CARD);
        String[] names = {"Repos", "Actions", "Releases"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = tv(names[i], 14, DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(13), 0, dp(13));
            t.setOnClickListener(v -> setTab(idx));
            tabViews[i] = t;
            tabBar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        repoBar = new LinearLayout(this);
        repoBar.setOrientation(LinearLayout.HORIZONTAL);
        repoBar.setBackgroundColor(0xFF1C2128);
        repoBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        repoBar.addView(tv("Repo:  ", 13, DIM, false));
        repoLabel = tv(repo, 13, BLUE, true);
        repoLabel.setSingleLine(true);
        repoLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        repoBar.addView(repoLabel);
        repoBar.setOnClickListener(v -> setTab(0));
        root.addView(repoBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.INVISIBLE);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(20));
        sv.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setTab(0);
    }

    private void setTab(int t) {
        tab = t;
        for (int i = 0; i < 3; i++) {
            tabViews[i].setTextColor(i == t ? BLUE : DIM);
            tabViews[i].setBackgroundColor(i == t ? 0xFF1F2937 : CARD);
        }
        repoLabel.setText(repo);
        repoBar.setVisibility(t == 0 ? View.GONE : View.VISIBLE);
        load();
    }

    private void showMsg(String m, int color) {
        list.removeAllViews();
        TextView t = tv(m, 13, color, false);
        t.setPadding(dp(18), dp(16), dp(18), dp(16));
        list.addView(t);
    }

    // ───────────────────────── network ─────────────────────────
    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    private HttpURLConnection open(String path, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com" + path).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return c;
    }

    private String get(String path) throws Exception {
        HttpURLConnection c = open(path, "application/vnd.github+json");
        int code = c.getResponseCode();
        String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) {
            String m = body;
            try { m = new JSONObject(body).optString("message", body); } catch (Exception ignore) { }
            throw new Exception("HTTP " + code + ": " + m);
        }
        return body;
    }

    private void load() {
        final int id = ++reqId;
        final int t = tab;
        final String r = repo;
        bar.setVisibility(View.VISIBLE);
        showMsg("Loading…", DIM);
        io.execute(() -> {
            try {
                final JSONArray data;
                if (t == 0) {
                    data = new JSONArray(get("/user/repos?per_page=100&sort=updated"));
                } else if (t == 1) {
                    data = new JSONObject(get("/repos/" + r + "/actions/runs?per_page=30")).getJSONArray("workflow_runs");
                } else {
                    data = new JSONArray(get("/repos/" + r + "/releases?per_page=20"));
                }
                ui.post(() -> {
                    if (id != reqId || isFinishing()) return;
                    bar.setVisibility(View.INVISIBLE);
                    try {
                        if (t == 0) renderRepos(data);
                        else if (t == 1) renderRuns(data);
                        else renderReleases(data);
                    } catch (Exception e) {
                        showMsg("Parse error: " + e.getMessage(), RED);
                    }
                });
            } catch (Exception e) {
                final String m = e.getMessage() == null ? "Network error" : e.getMessage();
                ui.post(() -> {
                    if (id != reqId || isFinishing()) return;
                    bar.setVisibility(View.INVISIBLE);
                    showMsg(m, RED);
                });
            }
        });
    }

    // ───────────────────────── renderers ─────────────────────────
    private void renderRepos(JSONArray a) throws Exception {
        list.removeAllViews();
        if (a.length() == 0) { showMsg("কোনো repo পাওয়া যায়নি", DIM); return; }
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
            final String full = str(o, "full_name");
            LinearLayout c = card();

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = tv(full, 15, BLUE, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            boolean priv = o.optBoolean("private");
            top.addView(pill(priv ? "Private" : "Public", priv ? AMBER : DIM));
            c.addView(top);

            String desc = str(o, "description");
            if (!desc.isEmpty()) {
                TextView d = tv(desc, 13, DIM, false);
                d.setMaxLines(2);
                d.setEllipsize(android.text.TextUtils.TruncateAt.END);
                d.setPadding(0, dp(4), 0, 0);
                c.addView(d);
            }
            String lang = str(o, "language");
            TextView meta = tv((lang.isEmpty() ? "" : "● " + lang + "     ") + "★ " + o.optInt("stargazers_count"),
                    12, lang.isEmpty() ? DIM : GREEN, false);
            meta.setPadding(0, dp(6), 0, 0);
            c.addView(meta);

            c.setOnClickListener(v -> { repo = full; setTab(1); });
            list.addView(c);
        }
    }

    private void renderRuns(JSONArray a) throws Exception {
        list.removeAllViews();
        if (a.length() == 0) { showMsg("কোনো workflow run নেই", DIM); return; }
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
            String concl = str(o, "conclusion");
            String status = str(o, "status");
            int col = "success".equals(concl) ? GREEN : "failure".equals(concl) ? RED : AMBER;
            LinearLayout c = card();

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(col);
            dot.setBackground(g);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
            dlp.rightMargin = dp(8);
            top.addView(dot, dlp);
            TextView name = tv(str(o, "name"), 15, TXT, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(pill(concl.isEmpty() ? status : concl, col));
            c.addView(top);

            TextView meta = tv(str(o, "head_branch") + "  •  " + day(str(o, "created_at")), 12, DIM, false);
            meta.setPadding(0, dp(6), 0, 0);
            c.addView(meta);

            final String url = str(o, "html_url");
            c.setOnClickListener(v -> openUrl(url));
            list.addView(c);
        }
    }

    private void renderReleases(JSONArray a) throws Exception {
        list.removeAllViews();
        if (a.length() == 0) { showMsg("কোনো release নেই", DIM); return; }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            LinearLayout c = card();

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(tv(str(o, "tag_name"), 16, GREEN, true),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (o.optBoolean("prerelease")) top.addView(pill("Pre-release", AMBER));
            c.addView(top);

            String nm = str(o, "name");
            if (!nm.isEmpty()) c.addView(tv(nm, 14, TXT, false));
            TextView date = tv(day(str(o, "published_at")), 12, DIM, false);
            date.setPadding(0, dp(2), 0, dp(4));
            c.addView(date);

            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int j = 0; j < assets.length(); j++) {
                    final JSONObject as = assets.getJSONObject(j);
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(10), dp(10), dp(10), dp(10));
                    row.setBackground(round(BG, 8, 0));
                    TextView an = tv("⬇  " + str(as, "name"), 13, BLUE, false);
                    an.setSingleLine(true);
                    an.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    row.addView(an, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(tv(String.format(Locale.US, "%.1f MB", as.optLong("size") / 1048576.0), 12, DIM, false));
                    row.setOnClickListener(v -> openAsset(as));
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.topMargin = dp(6);
                    c.addView(row, rlp);
                }
            }
            list.addView(c);
        }
    }

    /** Private repo te browser_download_url kaj kore na, tai API theke signed redirect URL niye browser e khuli. */
    private void openAsset(final JSONObject asset) {
        toast("Download শুরু হচ্ছে…");
        final String r = repo;
        io.execute(() -> {
            String url = str(asset, "browser_download_url");
            try {
                HttpURLConnection c = open("/repos/" + r + "/releases/assets/" + asset.getLong("id"),
                        "application/octet-stream");
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) url = loc;
                }
                c.disconnect();
            } catch (Exception ignore) { }
            final String u = url;
            ui.post(() -> { if (!isFinishing()) openUrl(u); });
        });
    }
}
