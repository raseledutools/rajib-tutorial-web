package com.rajibtutorial;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Admin: "Server Control". Shows which Firebase project (A / B) the app is using, lets the admin force A or B (or go back
 * to Auto), starts the A <-> B data sync (GitHub Actions "Sync Firestore" workflow, using the token saved in the GitHub
 * screen) and shows how much of the free quota is used.
 */
final class ServerScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String REPO = "raseledutools/rajib-tutorial-web";
    private static final String SYNC_WORKFLOW = "sync-firestore.yml";
    private static final String PROJ_A = "mywebtools-f8d53";
    private static final String PROJ_B = "rajib-tutorial-2";
    private static final long LIM_READ = 50000L, LIM_WRITE = 20000L, LIM_DELETE = 20000L;   // Spark (free) daily limits per project

    private final NativeHomeActivity a;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScrollView sv;
    private volatile boolean released = false;

    private String probeA = null, probeB = null;          // null = still checking
    private boolean probing = false;
    private Fs.Doc usage = null;                          // server-side numbers (snapshot written by GitHub Actions)
    private String usageNote = "";
    private String syncStatus = "";
    private String syncResult = "";
    private boolean syncBusy = false;
    private boolean modeBusy = false;

    ServerScreen(NativeHomeActivity host) { a = host; }

    @Override
    View build() {
        sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG);
        render();
        reload();
        return sv;
    }

    @Override
    void refresh() { if (sv != null) render(); }

    @Override
    void release() {
        released = true;
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private void post(final Runnable r) {
        ui.post(new Runnable() {
            @Override public void run() { if (!released) r.run(); }
        });
    }

    private void toast(String m) { Toast.makeText(a, m, Toast.LENGTH_SHORT).show(); }

    // ── data loading ─────────────────────────────────────────
    private void reload() {
        if (probing) return;
        probing = true;
        probeA = null; probeB = null;
        render();
        io.execute(new Runnable() {
            @Override public void run() {
                final String pa = Fs.probe(false);
                final String pb = Fs.probe(true);
                Fs.Doc u = null;
                String note = "";
                try {
                    u = Fs.getIn("_sync_meta", "usage");
                    if (u == null) note = "Usage snapshot এখনো তৈরি হয়নি (প্রতি ৩০ মিনিটে GitHub থেকে আসে)।";
                } catch (Exception e) {
                    note = "Usage পড়া যায়নি: " + shortMsg(e);
                }
                final Fs.Doc fu = u;
                final String fn = note;
                post(new Runnable() {
                    @Override public void run() {
                        probeA = pa; probeB = pb; usage = fu; usageNote = fn; probing = false;
                        render();
                    }
                });
            }
        });
        loadLastSync();
    }

    private void loadLastSync() {
        final String tok = ghToken();
        if (tok.isEmpty() || syncBusy) return;
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject runs = new JSONObject(gh(tok, "GET",
                        "/repos/" + REPO + "/actions/workflows/" + SYNC_WORKFLOW + "/runs?per_page=1", null));
                    JSONArray arr = runs.optJSONArray("workflow_runs");
                    if (arr == null || arr.length() == 0) return;
                    JSONObject r = arr.getJSONObject(0);
                    final String st = r.optString("status") + (r.optString("conclusion").isEmpty() || "null".equals(r.optString("conclusion"))
                        ? "" : " / " + r.optString("conclusion")) + "  ·  " + r.optString("created_at").replace('T', ' ').replace("Z", " UTC");
                    String res = "";
                    if ("completed".equals(r.optString("status"))) res = fetchResult(tok, r.getLong("id"));
                    final String fres = res;
                    post(new Runnable() {
                        @Override public void run() {
                            if (syncBusy) return;
                            syncStatus = "শেষ run: " + st;
                            if (!fres.isEmpty()) syncResult = fres;
                            render();
                        }
                    });
                } catch (Exception ignored) { }
            }
        });
    }

    // ── global switch (APK + Web + EXE) ──────────────────────
    // Written to server.json on the "server-config" branch of the repo (no Firebase quota used); every client polls it.
    private void setMode(final int m) {
        if (modeBusy) return;
        final String tok = ghToken();
        if (tok.isEmpty()) {
            toast("সব জায়গায় বদলাতে GitHub token লাগবে — আগে GitHub screen এ দিন");
            return;
        }
        modeBusy = true;
        render();
        final String name = m == Fs.MODE_A ? "A" : m == Fs.MODE_B ? "B" : "auto";
        io.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    String path = "/repos/" + REPO + "/contents/server.json";
                    String sha = "";
                    try {
                        sha = new JSONObject(gh(tok, "GET", path + "?ref=server-config", null)).optString("sha");
                    } catch (IOException e) {
                        if (e.getMessage() == null || !e.getMessage().startsWith("HTTP 404")) throw e;
                    }
                    String content = "{\"mode\":\"" + name + "\",\"by\":\"admin-app\",\"at\":" + System.currentTimeMillis() + "}\n";
                    JSONObject body = new JSONObject();
                    body.put("message", "server: " + name + " [skip ci]");
                    body.put("content", android.util.Base64.encodeToString(content.getBytes("UTF-8"), android.util.Base64.NO_WRAP));
                    body.put("branch", "server-config");
                    if (!sha.isEmpty()) body.put("sha", sha);
                    gh(tok, "PUT", path, body.toString());
                } catch (Exception e) {
                    err = shortMsg(e);
                }
                final String fe = err;
                post(new Runnable() {
                    @Override public void run() {
                        modeBusy = false;
                        if (fe != null) {
                            toast("বদলানো যায়নি: " + fe);
                        } else {
                            Fs.setMode(m);                       // this phone follows immediately, the rest within ~2 minutes
                            toast(m == Fs.MODE_AUTO ? "Auto: সব জায়গায় (A দিয়ে শুরু)" : "সব জায়গায় শুধু Server " + name);
                            a.refreshAll();
                            reload();
                        }
                        render();
                    }
                });
            }
        });
    }

    // ── sync via GitHub Actions ──────────────────────────────
    private String ghToken() {
        try {
            return a.getSharedPreferences("gh_prefs", android.content.Context.MODE_PRIVATE).getString("token", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void startSync() {
        if (syncBusy) return;
        final String tok = ghToken();
        if (tok.isEmpty()) {
            toast("আগে GitHub screen এ token দিন");
            return;
        }
        syncBusy = true;
        syncStatus = "Sync শুরু হচ্ছে…";
        syncResult = "";
        render();
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    long before = latestRunId(tok);
                    gh(tok, "POST", "/repos/" + REPO + "/actions/workflows/" + SYNC_WORKFLOW + "/dispatches",
                        "{\"ref\":\"main\",\"inputs\":{\"dry_run\":\"false\"}}");
                    setSync("Workflow চালু করা হয়েছে, run খোঁজা হচ্ছে…");
                    long runId = 0;
                    for (int i = 0; i < 60 && !released; i++) {
                        Thread.sleep(5000);
                        JSONObject runs = new JSONObject(gh(tok, "GET",
                            "/repos/" + REPO + "/actions/workflows/" + SYNC_WORKFLOW + "/runs?per_page=3", null));
                        JSONArray arr = runs.optJSONArray("workflow_runs");
                        if (arr == null) continue;
                        JSONObject pick = null;
                        for (int k = 0; k < arr.length(); k++) {
                            JSONObject x = arr.getJSONObject(k);
                            if (x.getLong("id") > before) { pick = x; break; }
                        }
                        if (pick == null) continue;
                        runId = pick.getLong("id");
                        String status = pick.optString("status");
                        if ("completed".equals(status)) {
                            String concl = pick.optString("conclusion");
                            String res = fetchResult(tok, runId);
                            final String fres = res.isEmpty() ? "(ফলাফল আনা যায়নি — GitHub → Actions এ দেখুন)" : res;
                            setSync("success".equals(concl) ? "✔ Sync শেষ" : "⚠ Sync শেষ: " + concl);
                            post(new Runnable() {
                                @Override public void run() { syncResult = fres; render(); }
                            });
                            break;
                        }
                        setSync("Sync চলছে… (" + status + ")");
                    }
                    if (runId == 0 && !released) setSync("Run পাওয়া যায়নি — GitHub → Actions এ দেখুন");
                } catch (Exception e) {
                    setSync("✖ Sync হয়নি: " + shortMsg(e));
                } finally {
                    post(new Runnable() {
                        @Override public void run() { syncBusy = false; render(); reload(); }
                    });
                }
            }
        });
    }

    private void setSync(final String s) {
        post(new Runnable() {
            @Override public void run() { syncStatus = s; render(); }
        });
    }

    private long latestRunId(String tok) {
        try {
            JSONObject runs = new JSONObject(gh(tok, "GET",
                "/repos/" + REPO + "/actions/workflows/" + SYNC_WORKFLOW + "/runs?per_page=1", null));
            JSONArray arr = runs.optJSONArray("workflow_runs");
            return (arr == null || arr.length() == 0) ? 0 : arr.getJSONObject(0).getLong("id");
        } catch (Exception e) {
            return 0;
        }
    }

    /** The sync job publishes its summary as a run annotation titled "Firestore sync result". */
    private String fetchResult(String tok, long runId) {
        try {
            JSONObject jobs = new JSONObject(gh(tok, "GET", "/repos/" + REPO + "/actions/runs/" + runId + "/jobs", null));
            JSONArray ja = jobs.optJSONArray("jobs");
            if (ja == null || ja.length() == 0) return "";
            long jobId = ja.getJSONObject(0).getLong("id");
            JSONArray an = new JSONArray(gh(tok, "GET", "/repos/" + REPO + "/check-runs/" + jobId + "/annotations", null));
            String out = "";
            for (int i = 0; i < an.length(); i++) {
                JSONObject o = an.getJSONObject(i);
                String title = o.optString("title");
                if (title.startsWith("Firestore sync")) {
                    String m = o.optString("message");
                    if ("Firestore sync result".equals(title)) return m;
                    out = out.isEmpty() ? m : out + "\n" + m;
                }
            }
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    private static String gh(String token, String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com" + path).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "RajibTutorial-App");
            if (body != null) {
                byte[] b = body.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                text = bos.toString("UTF-8");
            }
            if (code >= 400) {
                String msg = text;
                try { msg = new JSONObject(text).optString("message", text); } catch (Exception ignored) { }
                throw new IOException("HTTP " + code + " " + (msg.length() > 140 ? msg.substring(0, 140) : msg));
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 140 ? m.substring(0, 140) : m;
    }

    // ── UI ───────────────────────────────────────────────────
    private void render() {
        if (sv == null) return;
        sv.removeAllViews();
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 28));
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        root.addView(Ui.tv(a, "Server Control", 20, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 2));
        root.addView(Ui.tv(a, "একবার বদলালে APK, Web, EXE সব জায়গায় (সব ইউজারের) বদলে যায়", 12.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        // ── active server + mode ──
        boolean onB = Fs.usingBackup();
        int mode = Fs.mode();
        LinearLayout c1 = Ui.card(a);
        c1.addView(Ui.tv(a, "এখন চালু আছে", 12, Ui.SUB, false));
        c1.addView(Ui.tv(a, onB ? "Server B" : "Server A", 26, onB ? Ui.RED : Ui.GREEN, true));
        String modeTxt;
        if (mode == Fs.MODE_A) modeTxt = "সবার জন্য সবসময় A (auto switch বন্ধ)";
        else if (mode == Fs.MODE_B) modeTxt = "সবার জন্য সবসময় B (auto switch বন্ধ)";
        else {
            modeTxt = "Auto — A চলে, quota শেষ হলে প্রত্যেকে নিজে B তে যায়";
            long until = Fs.failoverUntilMs();
            if (onB && until > 0) {
                modeTxt += "\nB চলছে, আবার A চেষ্টা হবে ~ "
                    + new java.text.SimpleDateFormat("hh:mm a", Locale.US).format(new java.util.Date(until));
            }
        }
        c1.addView(Ui.tv(a, modeTxt, 12.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 2, 0, 10));
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(Ui.btn(a, "Auto", mode == Fs.MODE_AUTO ? Ui.B_PRIMARY : Ui.B_OUTLINE, new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(Fs.MODE_AUTO); }
        }), Ui.lpm(a, 0, WRAP, 0, 0, 6, 0));
        row.addView(Ui.btn(a, "A", mode == Fs.MODE_A ? Ui.B_PRIMARY : Ui.B_OUTLINE, new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(Fs.MODE_A); }
        }), Ui.lpm(a, 0, WRAP, 0, 0, 6, 0));
        row.addView(Ui.btn(a, "B", mode == Fs.MODE_B ? Ui.B_PRIMARY : Ui.B_OUTLINE, new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(Fs.MODE_B); }
        }), Ui.lpm(a, 0, WRAP, 0, 0, 0, 0));
        for (int i = 0; i < row.getChildCount(); i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
            lp.width = 0;
            lp.weight = 1f;
        }
        c1.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        c1.addView(Ui.tv(a, modeBusy ? "সবার জন্য বদলানো হচ্ছে…" : "বদলালে এই ফোনে সাথে সাথে, বাকি সবার কাছে ~২ মিনিটে পৌঁছায়। যাদের পাতা খোলা আছে তারা পরের navigation / reconnect এ পাবে।",
            11.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 0));
        root.addView(c1, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));

        // ── A / B cards ──
        root.addView(projectCard("Server A", PROJ_A, false, !onB, probeA), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        root.addView(projectCard("Server B", PROJ_B, true, onB, probeB), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        if (!usageNote.isEmpty()) {
            root.addView(Ui.tv(a, usageNote, 11.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 4, 0, 4, 10));
        }
        root.addView(Ui.smallBtn(a, probing ? "যাচাই হচ্ছে…" : "🔄  Status ও Usage আবার দেখুন", Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { reload(); }
        }), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        // ── sync ──
        LinearLayout c3 = Ui.card(a);
        c3.addView(Ui.tv(a, "Data Sync (A ⇄ B)", 15, Ui.TEXT, true));
        c3.addView(Ui.tv(a, "দুই server এর data মিলিয়ে দেয়: যা শুধু একটাতে আছে তা অন্যটায় যায়, আলাদা হলে নতুনটা জেতে। কিছু মোছে না। GitHub এ চলে, তাই এক-দুই মিনিট লাগে।",
            12, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 4, 0, 10));
        if (ghToken().isEmpty()) {
            c3.addView(Ui.tv(a, "GitHub token সেভ করা নেই। GitHub screen এ গিয়ে Personal Access Token (repo + workflow) দিন।", 12.5f, Ui.RED, false),
                Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
            c3.addView(Ui.btn(a, "🐙  GitHub খুলুন", Ui.B_OUTLINE, new View.OnClickListener() {
                @Override public void onClick(View v) { a.startActivity(new Intent(a, GitHubActivity.class)); }
            }), new LinearLayout.LayoutParams(MATCH, WRAP));
        } else {
            c3.addView(Ui.btn(a, syncBusy ? "Sync চলছে…" : "⇄  এখনই Sync করুন", Ui.B_PRIMARY, new View.OnClickListener() {
                @Override public void onClick(View v) { startSync(); }
            }), new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        if (!syncStatus.isEmpty()) c3.addView(Ui.tv(a, syncStatus, 12.5f, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        if (!syncResult.isEmpty()) {
            TextView r = Ui.tv(a, syncResult, 11.5f, Ui.SUB, false);
            r.setTypeface(android.graphics.Typeface.MONOSPACE);
            c3.addView(r, Ui.lpm(a, MATCH, WRAP, 0, 6, 0, 0));
        }
        root.addView(c3, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));

        root.addView(Ui.tv(a, "Auto সবচেয়ে নিরাপদ। A বা B জোর করে ধরলে সবার জন্য quota শেষ হলেও আর অন্যটায় যাবে না (ওই server এ error আসবে), তাই কাজ শেষে Auto তে ফিরুন।",
            11.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 4, 4, 4, 0));
    }

    private LinearLayout projectCard(String title, final String projectId, boolean isB, boolean active, String probe) {
        LinearLayout c = Ui.card(a);
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(a, title, 15, Ui.TEXT, true));
        if (active) head.addView(Ui.pill(a, "চালু", Ui.GREEN, 0x2617915A), Ui.lpm(a, WRAP, WRAP, 8, 0, 0, 0));
        c.addView(head);
        c.addView(Ui.tv(a, projectId, 11.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 6));

        String st; int col;
        if (probe == null) { st = "যাচাই হচ্ছে…"; col = Ui.SUB; }
        else if ("OK".equals(probe)) { st = "✔ চালু — quota আছে"; col = Ui.GREEN; }
        else if ("QUOTA".equals(probe)) { st = "⛔ আজকের free quota শেষ"; col = Ui.RED; }
        else if ("NOT_READY".equals(probe)) { st = "⚠ database / rules ready না"; col = Ui.RED; }
        else if ("OFFLINE".equals(probe)) { st = "ইন্টারনেট নেই"; col = Ui.RED; }
        else { st = "সমস্যা: " + probe; col = Ui.RED; }
        c.addView(Ui.tv(a, st, 13, col, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 6));

        // server-wide numbers (all users, from Cloud Monitoring via the GitHub snapshot job)
        String p = isB ? "B" : "A";
        if (usage != null) {
            String err = str(usage.data.get("error"));
            if (!err.isEmpty()) {
                c.addView(Ui.tv(a, "সবার মোট usage আনা যায়নি: " + err
                    + "\n(Google Cloud IAM এ service account কে “Monitoring Viewer” role দিলে এখানে সংখ্যা আসবে)", 11.5f, Ui.SUB, false),
                    Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 6));
            } else {
                long r = Fs.num(usage.data, p + "_read"), w = Fs.num(usage.data, p + "_write"), d = Fs.num(usage.data, p + "_delete");
                c.addView(usageLine("পড়া (read)", r, LIM_READ));
                c.addView(usageLine("লেখা (write)", w, LIM_WRITE));
                c.addView(usageLine("মোছা (delete)", d, LIM_DELETE));
                long at = Fs.num(usage.data, "atMs");
                if (at > 0) {
                    long mins = Math.max(0, (System.currentTimeMillis() - at) / 60000L);
                    c.addView(Ui.tv(a, "সব ইউজার মিলিয়ে, আজ পর্যন্ত · " + (mins < 90 ? mins + " মিনিট আগের" : (mins / 60) + " ঘণ্টা আগের") + " সংখ্যা",
                        10.5f, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 2, 0, 6));
                }
            }
        }
        long[] du = Fs.deviceUsage();
        long dr = isB ? du[2] : du[0], dw = isB ? du[3] : du[1];
        c.addView(Ui.tv(a, "এই ফোন (আজ): পড়া ~" + fmt(dr) + " · লেখা ~" + fmt(dw), 11.5f, Ui.SUB, false));

        final String url = "https://console.firebase.google.com/project/" + projectId + "/firestore/databases/-default-/usage";
        c.addView(Ui.smallBtn(a, "📊  Firebase Console এ usage", Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("Browser খোলা গেল না"); }
            }
        }), Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        return c;
    }

    private View usageLine(String label, long used, long limit) {
        long left = Math.max(0, limit - used);
        int pct = limit == 0 ? 0 : (int) Math.min(100, (used * 100L) / limit);
        int col = pct >= 90 ? Ui.RED : Ui.TEXT;
        TextView t = Ui.tv(a, label + ":  " + fmt(used) + " / " + fmt(limit) + "   (বাকি " + fmt(left) + " · " + pct + "% ব্যবহার)", 12.5f, col, pct >= 90);
        t.setPadding(0, Ui.dp(a, 1), 0, Ui.dp(a, 1));
        return t;
    }

    private static String fmt(long n) { return String.format(Locale.US, "%,d", n); }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
