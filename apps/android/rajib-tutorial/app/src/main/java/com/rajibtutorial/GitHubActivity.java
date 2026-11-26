package com.rajibtutorial;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
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
 * GitHub — native screen (no WebView).
 * Tabs: Repos / Code (browse, edit, commit) / Actions / Releases / Commits / Issues / PRs / Branches / Profile.
 * Actions: run workflow, re-run, cancel, jobs, artifact download, new release / issue / repo, delete release.
 * Token user nijei app e boshay (hardcoded na), SharedPreferences (MODE_PRIVATE) e thake.
 * Scope lagbe: repo, workflow.
 */
public class GitHubActivity extends AppCompatActivity {

    private static final String PREF = "gh_prefs";
    private static final String KEY_TOKEN = "token";
    private static final String DEFAULT_REPO = "raseledutools/rajib-tutorial-web";
    private static final String JSON_ACCEPT = "application/vnd.github+json";

    private static final int BG = 0xFF0D1117, CARD = 0xFF161B22, BORDER = 0xFF30363D;
    private static final int TXT = 0xFFE6EDF3, DIM = 0xFF8B949E;
    private static final int GREEN = 0xFF3FB950, RED = 0xFFF85149, AMBER = 0xFFD29922;
    private static final int BLUE = 0xFF58A6FF, PURPLE = 0xFF7B2FF7;

    private static final String[] TAB_NAMES =
            {"Repos", "Code", "Actions", "Releases", "Commits", "Issues", "PRs", "Branches", "Profile"};
    private static final int T_REPOS = 0, T_CODE = 1, T_RUNS = 2, T_REL = 3, T_COMMITS = 4,
            T_ISSUES = 5, T_PRS = 6, T_BRANCHES = 7, T_PROFILE = 8;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String token = "";
    private String repo = DEFAULT_REPO;
    private int tab = 0;
    private int reqId = 0;
    private JSONArray reposCache = new JSONArray();
    private String repoFilter = "";
    private volatile String lastScopes = "";
    private volatile String rateInfo = "";
    private String codePath = "", codeBranch = "", codeText = "";
    private JSONObject codeFile = null;

    private LinearLayout root, list, tabBar, repoBar, actionBar;
    private TextView repoLabel;
    private ProgressBar bar;
    private final TextView[] tabViews = new TextView[TAB_NAMES.length];

    private static class Resp {
        int code;
        String body = "";
        String scopes = "";
        String location = "";
    }

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

    private TextView smallBtn(String text, int color, View.OnClickListener l) {
        TextView t = tv(text, 13, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(round(color, 18, 0));
        t.setOnClickListener(l);
        return t;
    }

    private String str(JSONObject o, String k) {
        return o == null || o.isNull(k) ? "" : o.optString(k, "");
    }

    private String day(String s) {
        return s != null && s.length() >= 10 ? s.substring(0, 10) : (s == null ? "" : s);
    }

    private String sha7(String s) {
        return s != null && s.length() > 7 ? s.substring(0, 7) : (s == null ? "" : s);
    }

    private void toast(String m) {
        if (!isFinishing()) Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Open করা যায়নি");
        }
    }

    private String json(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignore) { }
        return o.toString();
    }

    private LinearLayout form() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(10), dp(20), 0);
        return f;
    }

    private EditText field(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        if (multi) {
            e.setMinLines(3);
            e.setGravity(Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        } else {
            e.setSingleLine(true);
        }
        return e;
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

        h.addView(tv("🐙  GitHub", 18, 0xFFFFFFFF, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(CARD);
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView t = tv(TAB_NAMES[i], 14, DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(18), dp(13), dp(18), dp(13));
            t.setOnClickListener(v -> setTab(idx));
            tabViews[i] = t;
            tabBar.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        hs.addView(tabBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        repoBar = new LinearLayout(this);
        repoBar.setOrientation(LinearLayout.HORIZONTAL);
        repoBar.setBackgroundColor(0xFF1C2128);
        repoBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        repoBar.addView(tv("Repo:  ", 13, DIM, false));
        repoLabel = tv(repo, 13, BLUE, true);
        repoLabel.setSingleLine(true);
        repoLabel.setEllipsize(TextUtils.TruncateAt.END);
        repoBar.addView(repoLabel);
        repoBar.setOnClickListener(v -> setTab(T_REPOS));
        root.addView(repoBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setGravity(Gravity.CENTER_VERTICAL);
        actionBar.setPadding(dp(12), dp(8), dp(12), dp(4));
        root.addView(actionBar, new LinearLayout.LayoutParams(
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

        setTab(T_REPOS);
    }

    private void setTab(int t) {
        tab = t;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setTextColor(i == t ? BLUE : DIM);
            tabViews[i].setBackgroundColor(i == t ? 0xFF1F2937 : CARD);
        }
        repoLabel.setText(repo);
        repoBar.setVisibility(t == T_REPOS || t == T_PROFILE ? View.GONE : View.VISIBLE);
        buildActionBar(t);
        load();
    }

    private void buildActionBar(int t) {
        actionBar.removeAllViews();
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.rightMargin = dp(8);
        if (t == T_REPOS) {
            final EditText s = new EditText(this);
            s.setHint("🔍 Search repo");
            s.setHintTextColor(DIM);
            s.setTextColor(TXT);
            s.setTextSize(13);
            s.setSingleLine(true);
            s.setText(repoFilter);
            s.setPadding(dp(12), dp(8), dp(12), dp(8));
            s.setBackground(round(CARD, 18, BORDER));
            s.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void afterTextChanged(Editable e) {
                    repoFilter = e.toString().trim().toLowerCase(Locale.ROOT);
                    if (tab == T_REPOS) {
                        try { renderRepos(); } catch (Exception ignore) { }
                    }
                }
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            slp.rightMargin = dp(8);
            actionBar.addView(s, slp);
            actionBar.addView(smallBtn("＋ Repo", GREEN, v -> newRepoDialog()));
        } else if (t == T_CODE) {
            actionBar.addView(smallBtn("⑂ " + (codeBranch.isEmpty() ? "default" : codeBranch), BLUE, v -> pickBranch()), gap);
            actionBar.addView(smallBtn("＋ File", GREEN, v -> newFileDialog()), gap);
        } else if (t == T_RUNS) {
            actionBar.addView(smallBtn("▶ Run workflow", GREEN, v -> runWorkflow()), gap);
        } else if (t == T_REL) {
            actionBar.addView(smallBtn("＋ New release", GREEN, v -> newReleaseDialog()), gap);
            actionBar.addView(tv("long-press = delete", 11, DIM, false));
        } else if (t == T_ISSUES) {
            actionBar.addView(smallBtn("＋ New issue", GREEN, v -> newIssueDialog()), gap);
        }
        actionBar.setVisibility(actionBar.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void showMsg(String m, int color) {
        list.removeAllViews();
        TextView t = tv(m, 13, color, false);
        t.setPadding(dp(18), dp(16), dp(18), dp(16));
        list.addView(t);
    }

    // ───────────────────────── network ─────────────────────────
    private String readAll(InputStream is) {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private Resp call(String method, String path, String body, String accept, boolean follow) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com" + path).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(follow);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            java.io.OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        r.body = readAll(r.code >= 400 ? c.getErrorStream() : c.getInputStream());
        String sc = c.getHeaderField("X-OAuth-Scopes");
        r.scopes = sc == null ? "" : sc;
        String loc = c.getHeaderField("Location");
        r.location = loc == null ? "" : loc;
        c.disconnect();
        return r;
    }

    private String errMsg(Resp r) {
        String m = r.body;
        try { m = new JSONObject(r.body).optString("message", r.body); } catch (Exception ignore) { }
        if (m.length() > 120) m = m.substring(0, 120);
        return "HTTP " + r.code + ": " + m;
    }

    private String get(String path) throws Exception {
        Resp r = call("GET", path, null, JSON_ACCEPT, true);
        if (r.code >= 400) throw new Exception(errMsg(r));
        return r.body;
    }

    /** POST/DELETE style action: toast result, optionally reload current tab. */
    private void act(final String method, final String path, final String body,
                     final String okMsg, final boolean reload) {
        toast("…");
        io.execute(() -> {
            String m;
            boolean ok = false;
            try {
                Resp r = call(method, path, body, JSON_ACCEPT, true);
                if (r.code >= 400) m = "✗ " + errMsg(r);
                else { m = okMsg; ok = true; }
            } catch (Exception e) {
                m = "✗ " + (e.getMessage() == null ? "Network error" : e.getMessage());
            }
            final String fm = m;
            final boolean fok = ok;
            ui.post(() -> {
                if (isFinishing()) return;
                toast(fm);
                if (fok && reload) load();
            });
        });
    }

    private void fail(Exception e) {
        final String m = "✗ " + (e.getMessage() == null ? "Network error" : e.getMessage());
        ui.post(() -> toast(m));
    }

    private void load() {
        if (tab == T_CODE) { loadCode(); return; }
        final int id = ++reqId;
        final int t = tab;
        final String r = repo;
        bar.setVisibility(View.VISIBLE);
        showMsg("Loading…", DIM);
        io.execute(() -> {
            try {
                final Object data;
                if (t == T_REPOS) data = new JSONArray(get("/user/repos?per_page=100&sort=updated"));
                else if (t == T_RUNS) data = new JSONObject(get("/repos/" + r + "/actions/runs?per_page=30")).getJSONArray("workflow_runs");
                else if (t == T_REL) data = new JSONArray(get("/repos/" + r + "/releases?per_page=20"));
                else if (t == T_COMMITS) data = new JSONArray(get("/repos/" + r + "/commits?per_page=30"));
                else if (t == T_ISSUES) data = new JSONArray(get("/repos/" + r + "/issues?state=open&per_page=40"));
                else if (t == T_PRS) data = new JSONArray(get("/repos/" + r + "/pulls?state=open&per_page=30"));
                else if (t == T_BRANCHES) data = new JSONArray(get("/repos/" + r + "/branches?per_page=50"));
                else {
                    Resp pr = call("GET", "/user", null, JSON_ACCEPT, true);
                    if (pr.code >= 400) throw new Exception(errMsg(pr));
                    lastScopes = pr.scopes;
                    try {
                        JSONObject core = new JSONObject(get("/rate_limit")).getJSONObject("resources").getJSONObject("core");
                        rateInfo = core.optInt("remaining") + " / " + core.optInt("limit");
                    } catch (Exception e) { rateInfo = ""; }
                    data = new JSONObject(pr.body);
                }
                ui.post(() -> {
                    if (id != reqId || isFinishing()) return;
                    bar.setVisibility(View.INVISIBLE);
                    try {
                        if (t == T_REPOS) { reposCache = (JSONArray) data; renderRepos(); }
                        else if (t == T_RUNS) renderRuns((JSONArray) data);
                        else if (t == T_REL) renderReleases((JSONArray) data);
                        else if (t == T_COMMITS) renderCommits((JSONArray) data);
                        else if (t == T_ISSUES) renderIssues((JSONArray) data);
                        else if (t == T_PRS) renderPrs((JSONArray) data);
                        else if (t == T_BRANCHES) renderBranches((JSONArray) data);
                        else renderProfile((JSONObject) data);
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


    // ───────────────────────── code browser / editor ─────────────────────────
    private void resetCode() { codePath = ""; codeBranch = ""; codeFile = null; codeText = ""; }

    private String refQ() { return codeBranch.isEmpty() ? "" : "?ref=" + Uri.encode(codeBranch); }

    private String parentOf(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    private String fmtSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }

    @Override
    public void onBackPressed() {
        if (tab == T_CODE && (codeFile != null || !codePath.isEmpty())) {
            codePath = parentOf(codePath);
            codeFile = null;
            load();
            return;
        }
        super.onBackPressed();
    }

    private void loadCode() {
        final int id = ++reqId;
        final String r = repo, p = codePath, q = refQ();
        bar.setVisibility(View.VISIBLE);
        showMsg("Loading…", DIM);
        io.execute(() -> {
            try {
                final String body = get("/repos/" + r + "/contents/" + Uri.encode(p, "/") + q);
                ui.post(() -> {
                    if (id != reqId || isFinishing()) return;
                    bar.setVisibility(View.INVISIBLE);
                    try { renderCode(body); } catch (Exception e) { showMsg("Parse error: " + e.getMessage(), RED); }
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

    private void renderCode(String body) throws Exception {
        list.removeAllViews();
        TextView crumb = tv("📂  " + repo + "/" + codePath, 12, DIM, false);
        crumb.setPadding(dp(16), dp(6), dp(16), dp(4));
        list.addView(crumb);
        String t = body.trim();
        if (t.startsWith("[")) { codeFile = null; renderDir(new JSONArray(t)); }
        else renderFile(new JSONObject(t));
    }

    private void renderDir(JSONArray a) throws Exception {
        if (!codePath.isEmpty()) {
            rowCard("⬆  ..", "", "", DIM, DIM, v -> { codePath = parentOf(codePath); load(); });
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.getJSONObject(i);
                final boolean dir = "dir".equals(str(o, "type"));
                if (dir != (pass == 0)) continue;
                final String path = str(o, "path"), sha = str(o, "sha");
                LinearLayout c = rowCard((dir ? "📁  " : "📄  ") + str(o, "name"),
                        dir ? "" : fmtSize(o.optLong("size")), "", DIM, dir ? BLUE : TXT,
                        v -> {
                            if (!"dir".equals(str(o, "type")) && !"file".equals(str(o, "type"))) {
                                openUrl(str(o, "html_url"));
                            } else { codePath = path; load(); }
                        });
                if (!dir) c.setOnLongClickListener(v -> { confirmDeleteFile(path, sha); return true; });
            }
        }
        if (a.length() == 0) showMsg("ফোল্ডার ফাঁকা", DIM);
    }

    private void renderFile(final JSONObject o) throws Exception {
        codeFile = o;
        final String name = str(o, "name"), path = str(o, "path"), sha = str(o, "sha");
        String text = null;
        if ("base64".equals(str(o, "encoding"))) {
            byte[] b = Base64.decode(str(o, "content"), Base64.DEFAULT);
            boolean bin = false;
            for (int i = 0; i < Math.min(b.length, 8000); i++) if (b[i] == 0) { bin = true; break; }
            if (!bin) text = new String(b, "UTF-8");
        }
        codeText = text == null ? "" : text;
        final String fText = text;

        LinearLayout c = card();
        c.addView(tv(name, 15, BLUE, true));
        c.addView(tv(fmtSize(o.optLong("size")) + "  •  " + path, 12, DIM, false));
        HorizontalScrollView bs = new HorizontalScrollView(this);
        bs.setHorizontalScrollBarEnabled(false);
        LinearLayout br = new LinearLayout(this);
        br.setOrientation(LinearLayout.HORIZONTAL);
        br.setPadding(0, dp(8), 0, 0);
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g.rightMargin = dp(6);
        if (fText != null) {
            br.addView(smallBtn("✏ Edit", GREEN, v -> editFileDialog(name, path, sha, fText)), g);
            br.addView(smallBtn("📋 Copy", BLUE, v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText(name, fText)); toast("Copied"); }
            }), g);
        }
        br.addView(smallBtn("🗑 Delete", RED, v -> confirmDeleteFile(path, sha)), g);
        br.addView(smallBtn("🌐 GitHub", PURPLE, v -> openUrl(str(o, "html_url"))), g);
        bs.addView(br);
        c.addView(bs);
        list.addView(c);

        if (fText == null) {
            showMsgAppend("Binary বা বড় ফাইল (>1MB) — GitHub এ খুলুন", AMBER);
            return;
        }
        String[] lines = fText.split("\n", -1);
        int n = Math.min(lines.length, 3000);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(lines[i]).append('\n');
        if (lines.length > n) sb.append("\n… (").append(lines.length - n).append(" লাইন আর দেখানো হয়নি)");
        TextView code = tv(sb.toString(), 11, TXT, false);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setPadding(dp(10), dp(8), dp(10), dp(8));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(code);
        LinearLayout box = card();
        box.setPadding(0, 0, 0, 0);
        box.addView(hs);
        list.addView(box);
    }

    private void showMsgAppend(String m, int color) {
        TextView t = tv(m, 13, color, false);
        t.setPadding(dp(18), dp(12), dp(18), dp(12));
        list.addView(t);
    }

    private void editFileDialog(String name, final String path, final String sha, String text) {
        LinearLayout f = form();
        final EditText msg = field("Commit message", false);
        msg.setText("Update " + name);
        final EditText code = field("code", true);
        code.setText(text);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(12);
        code.setMinLines(14);
        f.addView(msg);
        f.addView(code);
        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        new AlertDialog.Builder(this).setTitle("✏ " + path).setView(sv)
                .setPositiveButton("Commit", (d, w) ->
                        commitFile(path, sha, code.getText().toString(), msg.getText().toString().trim()))
                .setNegativeButton("Cancel", null).show();
    }

    private void newFileDialog() {
        LinearLayout f = form();
        final EditText pth = field("path/to/file.ext", false);
        pth.setText(codeFile == null && !codePath.isEmpty() ? codePath + "/" : "");
        final EditText msg = field("Commit message", false);
        final EditText code = field("content", true);
        code.setTypeface(Typeface.MONOSPACE);
        code.setMinLines(8);
        f.addView(pth);
        f.addView(msg);
        f.addView(code);
        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        new AlertDialog.Builder(this).setTitle("＋ নতুন ফাইল").setView(sv)
                .setPositiveButton("Commit", (d, w) -> {
                    String pp = pth.getText().toString().trim();
                    if (pp.startsWith("/")) pp = pp.substring(1);
                    if (pp.isEmpty()) { toast("Path দিন"); return; }
                    commitFile(pp, null, code.getText().toString(), msg.getText().toString().trim());
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void commitFile(final String path, final String sha, final String content, String message) {
        final String r = repo, bn = codeBranch;
        final String m = message.isEmpty() ? (sha == null ? "Add " : "Update ") + path : message;
        toast("Committing…");
        io.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("message", m);
                b.put("content", Base64.encodeToString(content.getBytes("UTF-8"), Base64.NO_WRAP));
                if (sha != null && !sha.isEmpty()) b.put("sha", sha);
                if (!bn.isEmpty()) b.put("branch", bn);
                Resp resp = call("PUT", "/repos/" + r + "/contents/" + Uri.encode(path, "/"),
                        b.toString(), JSON_ACCEPT, true);
                if (resp.code >= 400) throw new Exception(errMsg(resp));
                ui.post(() -> {
                    if (isFinishing()) return;
                    toast("✓ Commit হয়েছে");
                    if (tab == T_CODE && r.equals(repo)) { codePath = path; load(); }
                });
            } catch (Exception e) { fail(e); }
        });
    }

    private void confirmDeleteFile(final String path, final String sha) {
        new AlertDialog.Builder(this).setTitle("ফাইল মুছবেন?").setMessage(path)
                .setPositiveButton("Delete", (d, w) -> deleteFile(path, sha))
                .setNegativeButton("Cancel", null).show();
    }

    private void deleteFile(final String path, final String sha) {
        final String r = repo, bn = codeBranch;
        toast("Deleting…");
        io.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("message", "Delete " + path);
                b.put("sha", sha);
                if (!bn.isEmpty()) b.put("branch", bn);
                Resp resp = call("DELETE", "/repos/" + r + "/contents/" + Uri.encode(path, "/"),
                        b.toString(), JSON_ACCEPT, true);
                if (resp.code >= 400) throw new Exception(errMsg(resp));
                ui.post(() -> {
                    if (isFinishing()) return;
                    toast("✓ মুছে গেছে");
                    if (tab == T_CODE && r.equals(repo)) { codePath = parentOf(path); codeFile = null; load(); }
                });
            } catch (Exception e) { fail(e); }
        });
    }

    private void pickBranch() {
        final String r = repo;
        io.execute(() -> {
            try {
                JSONArray a = new JSONArray(get("/repos/" + r + "/branches?per_page=100"));
                final String[] names = new String[a.length()];
                for (int i = 0; i < names.length; i++) names[i] = a.getJSONObject(i).optString("name");
                ui.post(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(this).setTitle("Branch").setItems(names, (d, w) -> {
                        codeBranch = names[w]; codePath = ""; codeFile = null;
                        buildActionBar(T_CODE);
                        load();
                    }).show();
                });
            } catch (Exception e) { fail(e); }
        });
    }

    // ───────────────────────── renderers ─────────────────────────
    private LinearLayout rowCard(String title, String sub, String pillText, int pillColor,
                                 int titleColor, View.OnClickListener click) {
        LinearLayout c = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = tv(title, 15, titleColor, true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (pillText != null && !pillText.isEmpty()) top.addView(pill(pillText, pillColor));
        c.addView(top);
        if (sub != null && !sub.isEmpty()) {
            TextView s = tv(sub, 12, DIM, false);
            s.setPadding(0, dp(5), 0, 0);
            c.addView(s);
        }
        if (click != null) c.setOnClickListener(click);
        list.addView(c);
        return c;
    }

    private void renderRepos() throws Exception {
        list.removeAllViews();
        int shown = 0;
        for (int i = 0; i < reposCache.length(); i++) {
            final JSONObject o = reposCache.getJSONObject(i);
            final String full = str(o, "full_name");
            if (!repoFilter.isEmpty() && !full.toLowerCase(Locale.ROOT).contains(repoFilter)) continue;
            shown++;
            LinearLayout c = card();

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = tv(full, 15, BLUE, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            boolean priv = o.optBoolean("private");
            top.addView(pill(priv ? "Private" : "Public", priv ? AMBER : DIM));
            c.addView(top);

            String desc = str(o, "description");
            if (!desc.isEmpty()) {
                TextView d = tv(desc, 13, DIM, false);
                d.setMaxLines(2);
                d.setEllipsize(TextUtils.TruncateAt.END);
                d.setPadding(0, dp(4), 0, 0);
                c.addView(d);
            }
            String lang = str(o, "language");
            TextView meta = tv((lang.isEmpty() ? "" : "● " + lang + "     ") + "★ " + o.optInt("stargazers_count")
                    + "     ⑂ " + o.optInt("forks_count") + "     ⚠ " + o.optInt("open_issues_count"),
                    12, lang.isEmpty() ? DIM : GREEN, false);
            meta.setPadding(0, dp(6), 0, 0);
            c.addView(meta);

            LinearLayout qa = new LinearLayout(this);
            qa.setOrientation(LinearLayout.HORIZONTAL);
            qa.setPadding(0, dp(8), 0, 0);
            LinearLayout.LayoutParams qg = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qg.rightMargin = dp(6);
            qa.addView(smallBtn("📁 Code", BLUE, v -> { repo = full; resetCode(); setTab(T_CODE); }), qg);
            qa.addView(smallBtn("🚀 Releases", PURPLE, v -> { repo = full; resetCode(); setTab(T_REL); }), qg);
            qa.addView(smallBtn("⚙ Actions", GREEN, v -> { repo = full; resetCode(); setTab(T_RUNS); }), qg);
            c.addView(qa);

            c.setOnClickListener(v -> { repo = full; resetCode(); setTab(T_CODE); });
            c.setOnLongClickListener(v -> { openUrl(str(o, "html_url")); return true; });
            list.addView(c);
        }
        if (shown == 0) showMsg("কোনো repo পাওয়া যায়নি", DIM);
    }

    private void renderRuns(JSONArray a) throws Exception {
        list.removeAllViews();
        if (a.length() == 0) { showMsg("কোনো workflow run নেই", DIM); return; }
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
            String concl = str(o, "conclusion");
            String status = str(o, "status");
            int col = "success".equals(concl) ? GREEN : "failure".equals(concl) ? RED : AMBER;
            String icon = "success".equals(concl) ? "✅ " : "failure".equals(concl) ? "❌ "
                    : "cancelled".equals(concl) ? "⛔ " : "⏳ ";
            rowCard(icon + str(o, "name") + " #" + o.optInt("run_number"),
                    str(o, "head_branch") + "  •  " + str(o, "event") + "  •  " + day(str(o, "created_at")),
                    concl.isEmpty() ? status : concl, col, TXT, v -> runMenu(o));
        }
    }

    private void renderReleases(JSONArray a) throws Exception {
        list.removeAllViews();
        if (a.length() == 0) { showMsg("কোনো release নেই", DIM); return; }
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
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
                    an.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(an, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(tv(String.format(Locale.US, "%.1f MB", as.optLong("size") / 1048576.0), 12, DIM, false));
                    row.setOnClickListener(v -> openAsset(as));
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.topMargin = dp(6);
                    c.addView(row, rlp);
                }
            }
            c.setOnLongClickListener(v -> { confirmDeleteRelease(o); return true; });
            list.addView(c);
        }
    }

    private void renderCommits(JSONArray a) throws Exception {
        list.removeAllViews();
        if (a.length() == 0) { showMsg("কোনো commit নেই", DIM); return; }
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
            JSONObject cm = o.optJSONObject("commit");
            String msg = cm == null ? "" : str(cm, "message");
            int nl = msg.indexOf('\n');
            if (nl > 0) msg = msg.substring(0, nl);
            JSONObject au = cm == null ? null : cm.optJSONObject("author");
            final String url = str(o, "html_url");
            rowCard(msg, sha7(str(o, "sha")) + "  •  " + str(au, "name") + "  •  " + day(str(au, "date")),
                    null, 0, TXT, v -> openUrl(url));
        }
    }

    private void renderIssues(JSONArray a) throws Exception {
        list.removeAllViews();
        int shown = 0;
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
            if (o.has("pull_request")) continue;   // issues API PR-o return kore
            shown++;
            JSONObject u = o.optJSONObject("user");
            final String url = str(o, "html_url");
            int cm = o.optInt("comments");
            rowCard("#" + o.optInt("number") + "  " + str(o, "title"),
                    str(u, "login") + "  •  " + day(str(o, "created_at")),
                    cm > 0 ? "💬 " + cm : "", DIM, TXT, v -> openUrl(url));
        }
        if (shown == 0) showMsg("কোনো open issue নেই 🎉", DIM);
    }

    private void renderPrs(JSONArray a) throws Exception {
        list.removeAllViews();
        if (a.length() == 0) { showMsg("কোনো open pull request নেই", DIM); return; }
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
            JSONObject u = o.optJSONObject("user");
            JSONObject head = o.optJSONObject("head");
            JSONObject base = o.optJSONObject("base");
            boolean draft = o.optBoolean("draft");
            final String url = str(o, "html_url");
            rowCard("#" + o.optInt("number") + "  " + str(o, "title"),
                    str(head, "ref") + " → " + str(base, "ref") + "  •  " + str(u, "login") + "  •  " + day(str(o, "created_at")),
                    draft ? "Draft" : "Open", draft ? DIM : GREEN, TXT, v -> openUrl(url));
        }
    }

    private void renderBranches(JSONArray a) throws Exception {
        list.removeAllViews();
        if (a.length() == 0) { showMsg("কোনো branch নেই", DIM); return; }
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
            final String name = str(o, "name");
            JSONObject cm = o.optJSONObject("commit");
            final String r = repo;
            LinearLayout bc = rowCard("⑂  " + name, sha7(str(cm, "sha")),
                    o.optBoolean("protected") ? "protected" : "", AMBER, BLUE,
                    v -> { codeBranch = name; codePath = ""; codeFile = null; setTab(T_CODE); });
            bc.setOnLongClickListener(v -> {
                openUrl("https://github.com/" + r + "/tree/" + Uri.encode(name, "/"));
                return true;
            });
        }
    }

    private void renderProfile(JSONObject u) throws Exception {
        list.removeAllViews();
        final String url = str(u, "html_url");
        LinearLayout c = card();
        String nm = str(u, "name");
        c.addView(tv(nm.isEmpty() ? str(u, "login") : nm, 20, TXT, true));
        c.addView(tv("@" + str(u, "login"), 14, BLUE, false));
        String bio = str(u, "bio");
        if (!bio.isEmpty()) {
            TextView b = tv(bio, 13, DIM, false);
            b.setPadding(0, dp(6), 0, 0);
            c.addView(b);
        }
        TextView st = tv("Public repos: " + u.optInt("public_repos") + "\nFollowers: " + u.optInt("followers")
                + "   Following: " + u.optInt("following") + "\nJoined: " + day(str(u, "created_at")), 13, TXT, false);
        st.setPadding(0, dp(10), 0, 0);
        c.addView(st);
        c.setOnClickListener(v -> openUrl(url));
        list.addView(c);

        LinearLayout c2 = card();
        c2.addView(tv("Token", 14, TXT, true));
        String sc = lastScopes.isEmpty() ? "(fine-grained token — scopes দেখানো যায় না)" : lastScopes;
        TextView t = tv("Scopes: " + sc + (rateInfo.isEmpty() ? "" : "\nAPI rate limit left: " + rateInfo), 13, DIM, false);
        t.setPadding(0, dp(6), 0, 0);
        c2.addView(t);
        list.addView(c2);
    }

    // ───────────────────────── Actions: run menu ─────────────────────────
    private void runMenu(final JSONObject o) {
        final long id = o.optLong("id");
        final String r = repo;
        final String[] items = {"Open in browser", "Jobs", "Artifacts (download)", "Re-run", "Cancel run"};
        new AlertDialog.Builder(this)
                .setTitle(str(o, "name") + " #" + o.optInt("run_number"))
                .setItems(items, (d, i) -> {
                    if (i == 0) openUrl(str(o, "html_url"));
                    else if (i == 1) showJobs(r, id);
                    else if (i == 2) showArtifacts(r, id);
                    else if (i == 3) act("POST", "/repos/" + r + "/actions/runs/" + id + "/rerun", "{}", "✓ Re-run শুরু হয়েছে", true);
                    else act("POST", "/repos/" + r + "/actions/runs/" + id + "/cancel", "{}", "✓ Cancel পাঠানো হয়েছে", true);
                }).show();
    }

    private void showJobs(final String r, final long id) {
        toast("Loading jobs…");
        io.execute(() -> {
            try {
                JSONArray a = new JSONObject(get("/repos/" + r + "/actions/runs/" + id + "/jobs?per_page=50")).getJSONArray("jobs");
                final int n = a.length();
                final String[] labels = new String[n];
                final String[] urls = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject j = a.getJSONObject(i);
                    String c = str(j, "conclusion");
                    if (c.isEmpty()) c = str(j, "status");
                    labels[i] = ("success".equals(c) ? "✅ " : "failure".equals(c) ? "❌ " : "⏳ ") + str(j, "name") + " — " + c;
                    urls[i] = str(j, "html_url");
                }
                ui.post(() -> {
                    if (isFinishing()) return;
                    if (n == 0) { toast("কোনো job নেই"); return; }
                    new AlertDialog.Builder(this).setTitle("Jobs")
                            .setItems(labels, (d, i) -> openUrl(urls[i])).show();
                });
            } catch (Exception e) { fail(e); }
        });
    }

    private void showArtifacts(final String r, final long id) {
        toast("Loading artifacts…");
        io.execute(() -> {
            try {
                JSONArray a = new JSONObject(get("/repos/" + r + "/actions/runs/" + id + "/artifacts")).getJSONArray("artifacts");
                final int n = a.length();
                final String[] labels = new String[n];
                final long[] ids = new long[n];
                for (int i = 0; i < n; i++) {
                    JSONObject j = a.getJSONObject(i);
                    ids[i] = j.optLong("id");
                    labels[i] = "⬇  " + str(j, "name") + "  ("
                            + String.format(Locale.US, "%.1f MB", j.optLong("size_in_bytes") / 1048576.0) + ")"
                            + (j.optBoolean("expired") ? "  [expired]" : "");
                }
                ui.post(() -> {
                    if (isFinishing()) return;
                    if (n == 0) { toast("কোনো artifact নেই"); return; }
                    new AlertDialog.Builder(this).setTitle("Artifacts")
                            .setItems(labels, (d, i) -> downloadArtifact(r, ids[i])).show();
                });
            } catch (Exception e) { fail(e); }
        });
    }

    private void downloadArtifact(final String r, final long artifactId) {
        toast("Download শুরু হচ্ছে…");
        io.execute(() -> {
            try {
                Resp resp = call("GET", "/repos/" + r + "/actions/artifacts/" + artifactId + "/zip", null, JSON_ACCEPT, false);
                if (resp.code >= 300 && resp.code < 400 && !resp.location.isEmpty()) {
                    final String u = resp.location;
                    ui.post(() -> { if (!isFinishing()) openUrl(u); });
                } else {
                    final String m = "✗ " + errMsg(resp);
                    ui.post(() -> toast(m));
                }
            } catch (Exception e) { fail(e); }
        });
    }

    private void runWorkflow() {
        final String r = repo;
        toast("Loading workflows…");
        io.execute(() -> {
            try {
                JSONArray a = new JSONObject(get("/repos/" + r + "/actions/workflows")).getJSONArray("workflows");
                final int n = a.length();
                final String[] names = new String[n];
                final long[] ids = new long[n];
                for (int i = 0; i < n; i++) {
                    JSONObject w = a.getJSONObject(i);
                    names[i] = str(w, "name");
                    ids[i] = w.optLong("id");
                }
                ui.post(() -> {
                    if (isFinishing()) return;
                    if (n == 0) { toast("কোনো workflow নেই"); return; }
                    new AlertDialog.Builder(this).setTitle("Run workflow")
                            .setItems(names, (d, i) -> askBranch(r, ids[i], names[i])).show();
                });
            } catch (Exception e) { fail(e); }
        });
    }

    private void askBranch(final String r, final long wfId, String wfName) {
        LinearLayout f = form();
        final EditText br = field("Branch", false);
        br.setText("main");
        f.addView(br);
        new AlertDialog.Builder(this).setTitle(wfName).setView(f)
                .setPositiveButton("Run", (d, w) -> {
                    String b = br.getText().toString().trim();
                    if (b.isEmpty()) b = "main";
                    act("POST", "/repos/" + r + "/actions/workflows/" + wfId + "/dispatches",
                            json("ref", b), "✓ Workflow trigger হয়েছে (workflow_dispatch লাগবে)", false);
                    ui.postDelayed(() -> { if (!isFinishing() && tab == T_RUNS) load(); }, 3500);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ───────────────────────── Releases ─────────────────────────
    private void newReleaseDialog() {
        final String r = repo;
        LinearLayout f = form();
        final EditText tag = field("Tag (e.g. v1.0.0)", false);
        final EditText nm = field("Title", false);
        final EditText notes = field("Notes", true);
        final CheckBox pre = new CheckBox(this);
        pre.setText("Pre-release");
        f.addView(tag);
        f.addView(nm);
        f.addView(notes);
        f.addView(pre);
        new AlertDialog.Builder(this).setTitle("New release").setView(f)
                .setPositiveButton("Create", (d, w) -> {
                    String t = tag.getText().toString().trim();
                    if (t.isEmpty()) { toast("Tag দাও"); return; }
                    String n = nm.getText().toString().trim();
                    act("POST", "/repos/" + r + "/releases",
                            json("tag_name", t, "name", n.isEmpty() ? t : n,
                                    "body", notes.getText().toString(), "prerelease", pre.isChecked()),
                            "✓ Release তৈরি হয়েছে", true);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmDeleteRelease(final JSONObject o) {
        final String r = repo;
        final long id = o.optLong("id");
        new AlertDialog.Builder(this).setTitle("Delete release?")
                .setMessage(str(o, "tag_name") + " release মুছে যাবে (tag থেকে যাবে)।")
                .setPositiveButton("Delete", (d, w) ->
                        act("DELETE", "/repos/" + r + "/releases/" + id, null, "✓ Release মুছে গেছে", true))
                .setNegativeButton("Cancel", null).show();
    }

    /** Private repo te browser_download_url kaj kore na, tai API theke signed redirect URL niye browser e khuli. */
    private void openAsset(final JSONObject asset) {
        toast("Download শুরু হচ্ছে…");
        final String r = repo;
        io.execute(() -> {
            String url = str(asset, "browser_download_url");
            try {
                Resp resp = call("GET", "/repos/" + r + "/releases/assets/" + asset.getLong("id"),
                        null, "application/octet-stream", false);
                if (resp.code >= 300 && resp.code < 400 && !resp.location.isEmpty()) url = resp.location;
            } catch (Exception ignore) { }
            final String u = url;
            ui.post(() -> { if (!isFinishing()) openUrl(u); });
        });
    }

    // ───────────────────────── Issues / Repos create ─────────────────────────
    private void newIssueDialog() {
        final String r = repo;
        LinearLayout f = form();
        final EditText title = field("Title", false);
        final EditText body = field("Description", true);
        f.addView(title);
        f.addView(body);
        new AlertDialog.Builder(this).setTitle("New issue").setView(f)
                .setPositiveButton("Create", (d, w) -> {
                    String t = title.getText().toString().trim();
                    if (t.isEmpty()) { toast("Title দাও"); return; }
                    act("POST", "/repos/" + r + "/issues", json("title", t, "body", body.getText().toString()),
                            "✓ Issue তৈরি হয়েছে", true);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void newRepoDialog() {
        LinearLayout f = form();
        final EditText name = field("Repo name", false);
        final EditText desc = field("Description (optional)", false);
        final CheckBox priv = new CheckBox(this);
        priv.setText("Private");
        priv.setChecked(true);
        f.addView(name);
        f.addView(desc);
        f.addView(priv);
        new AlertDialog.Builder(this).setTitle("New repository").setView(f)
                .setPositiveButton("Create", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) { toast("Name দাও"); return; }
                    act("POST", "/user/repos",
                            json("name", n, "description", desc.getText().toString().trim(), "private", priv.isChecked()),
                            "✓ Repo তৈরি হয়েছে", true);
                })
                .setNegativeButton("Cancel", null).show();
    }
}
