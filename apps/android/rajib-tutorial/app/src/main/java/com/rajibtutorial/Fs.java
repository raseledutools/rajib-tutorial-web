package com.rajibtutorial;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Firestore REST client (same project / collection / API key the web
 * version uses), so the native screens read and write exactly the same data.
 */
final class Fs {
    private Fs() {}

    static final String KEY = "AIzaSyDGd3KAo45UuqmeGFALziz_oKm3htEASHY";
    static final String BASE =
        "https://firestore.googleapis.com/v1/projects/mywebtools-f8d53/databases/(default)/documents/tutorial_courses";
    static final String REFERER = "https://raseledutools.github.io/rajib-tutorial-web/";

    // ── Failover: project A (primary) -> project B (backup) when A's free quota is exhausted ──
    // Same behaviour as the web/EXE index.html: on HTTP 429 / RESOURCE_EXHAUSTED switch to B and retry,
    // stay on B until the next 08:00 UTC (Spark quota reset), then try A again.
    private static final String PROJ_A = "mywebtools-f8d53";
    private static final String PROJ_B = "rajib-tutorial-2";
    private static final String KEY_B  = "AIzaSyBYAgr_vJjKdsmjhFgvh89GM03QBMJ72Is";
    private static final String PREF = "fs_failover";
    private static final String PREF_UNTIL = "until";
    private static volatile long failoverUntil = 0;
    private static volatile android.content.SharedPreferences foPrefs;

    /** Call once from Application.onCreate so the failover state survives app restarts. */
    static void init(android.content.Context c) {
        try {
            foPrefs = c.getApplicationContext().getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE);
            failoverUntil = foPrefs.getLong(PREF_UNTIL, 0);
            backupBadUntil = foPrefs.getLong(PREF_BAD, 0);
            mode = foPrefs.getInt(PREF_MODE, MODE_AUTO);
            synchronized (USAGE_LOCK) {
                usageDay = foPrefs.getString("u_day", "");
                rA = foPrefs.getLong("u_ra", 0); wA = foPrefs.getLong("u_wa", 0);
                rB = foPrefs.getLong("u_rb", 0); wB = foPrefs.getLong("u_wb", 0);
                rolloverLocked();
            }
        } catch (Throwable ignored) { }
    }

    // ── Manual server selection (admin "Server Control" screen) ──
    // AUTO = A, switch to B on quota exhaustion (default). MODE_A / MODE_B = stay on that project, no automatic switching.
    static final int MODE_AUTO = 0, MODE_A = 1, MODE_B = 2;
    private static final String PREF_MODE = "mode";
    private static volatile int mode = MODE_AUTO;

    static int mode() { return mode; }

    static void setMode(int m) {
        mode = m;
        if (m == MODE_AUTO) { failoverUntil = 0; backupBadUntil = 0; }   // start again from A
        try {
            if (foPrefs != null) {
                android.content.SharedPreferences.Editor e = foPrefs.edit().putInt(PREF_MODE, m);
                if (m == MODE_AUTO) e.putLong(PREF_UNTIL, 0).putLong(PREF_BAD, 0);
                e.apply();
            }
        } catch (Throwable ignored) { }
    }

    /** True while requests go to project B. */
    static boolean usingBackup() { return onBackup(); }

    /** When the automatic failover to B ends (ms since epoch), 0 if not failed over. */
    static long failoverUntilMs() { return failoverUntil > System.currentTimeMillis() ? failoverUntil : 0; }

    // ── Usage counters of THIS phone (reads ~ documents returned, writes = PATCH/DELETE), reset at the daily quota reset ──
    private static final Object USAGE_LOCK = new Object();
    private static String usageDay = "";
    private static long rA, wA, rB, wB;

    private static String quotaDay() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(System.currentTimeMillis() - 8L * 3600L * 1000L));
    }

    private static void rolloverLocked() {
        String d = quotaDay();
        if (!d.equals(usageDay)) { usageDay = d; rA = 0; wA = 0; rB = 0; wB = 0; }
    }

    private static void count(String method, String url, String text) {
        try {
            boolean b = url.contains("/projects/" + PROJ_B + "/");
            boolean write = "PATCH".equals(method) || "DELETE".equals(method);
            long n = 1;
            if (!write && text != null) {
                int c = 0, i = 0;
                while ((i = text.indexOf("\"name\": \"projects/", i)) >= 0) { c++; i += 18; }
                n = Math.max(1, c);
            }
            synchronized (USAGE_LOCK) {
                rolloverLocked();
                if (write) { if (b) wB += n; else wA += n; } else { if (b) rB += n; else rA += n; }
                if (foPrefs != null) {
                    foPrefs.edit().putString("u_day", usageDay).putLong("u_ra", rA).putLong("u_wa", wA)
                        .putLong("u_rb", rB).putLong("u_wb", wB).apply();
                }
            }
        } catch (Throwable ignored) { }
    }

    /** {readsA, writesA, readsB, writesB} counted on this phone today. */
    static long[] deviceUsage() {
        synchronized (USAGE_LOCK) {
            rolloverLocked();
            return new long[] { rA, wA, rB, wB };
        }
    }

    /** Cheap health check of one project: OK / QUOTA / NOT_READY / OFFLINE / "ERR ...". Never throws. */
    static String probe(boolean backup) {
        String u = DB + "/_ping/x?key=" + KEY;
        try {
            doRequest("GET", backup ? toBackup(u) : u, null);
            return "OK";
        } catch (NotFound e) {
            return "OK";
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (isQuota(e)) return "QUOTA";
            if (isBackupBad(e) || m.contains("DATABASE_NOT_FOUND")) return "NOT_READY";
            if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) return "OFFLINE";
            return "ERR " + m;
        } catch (Throwable t) {
            return "ERR";
        }
    }

    /** One document of any top-level collection; null if it does not exist. */
    static Doc getIn(String col, String id) throws Exception {
        try {
            return parseDoc(new JSONObject(request("GET", colDocUrl(col, id), null)));
        } catch (NotFound e) {
            return null;
        }
    }

    // If B is not provisioned (database missing -> 404, rules deny -> 401/403) don't stay on it: go back to A
    // and don't try B again for 30 minutes.
    private static final String PREF_BAD = "bad_until";
    private static volatile long backupBadUntil = 0;

    private static boolean backupBadNow() { return backupBadUntil > System.currentTimeMillis(); }

    private static boolean onBackup() {
        if (mode == MODE_A) return false;
        if (mode == MODE_B) return true;
        return failoverUntil > System.currentTimeMillis() && !backupBadNow();
    }

    private static boolean isBackupBad(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.startsWith("HTTP 403") || m.startsWith("HTTP 401") || m.contains("DATABASE_NOT_FOUND");
    }

    private static void stopFailover() {
        failoverUntil = 0;
        backupBadUntil = System.currentTimeMillis() + 30L * 60L * 1000L;
        try {
            if (foPrefs != null) foPrefs.edit().putLong(PREF_UNTIL, 0).putLong(PREF_BAD, backupBadUntil).apply();
        } catch (Throwable ignored) { }
    }

    private static long nextReset() {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.set(java.util.Calendar.HOUR_OF_DAY, 8);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private static void startFailover() {
        failoverUntil = nextReset();
        try {
            if (foPrefs != null) foPrefs.edit().putLong(PREF_UNTIL, failoverUntil).apply();
        } catch (Throwable ignored) { }
    }

    private static boolean isQuota(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        String l = m.toLowerCase(java.util.Locale.US);
        return m.startsWith("HTTP 429") || l.contains("resource_exhausted") || l.contains("quota");
    }

    /** Rewrite a project-A URL / JSON body so it targets the backup project. */
    private static String toBackup(String x) {
        return x == null ? null : x.replace(PROJ_A, PROJ_B).replace(KEY, KEY_B);
    }

    static final class Doc {
        final String id;
        final Map<String, Object> data;
        Doc(String id, Map<String, Object> data) { this.id = id; this.data = data; }
    }

    static final class NotFound extends IOException {
        NotFound() { super("not found"); }
    }

    // ── Public API ──────────────────────────────────────────

    static List<Doc> list() throws Exception {
        List<Doc> out = new ArrayList<>();
        String token = null;
        do {
            String url = BASE + "?pageSize=300&key=" + KEY
                + (token != null ? "&pageToken=" + URLEncoder.encode(token, "UTF-8") : "");
            JSONObject o = new JSONObject(request("GET", url, null));
            JSONArray docs = o.optJSONArray("documents");
            if (docs != null) {
                for (int i = 0; i < docs.length(); i++) out.add(parseDoc(docs.getJSONObject(i)));
            }
            token = o.has("nextPageToken") ? o.getString("nextPageToken") : null;
        } while (token != null);
        return out;
    }

    /** Returns null when the document does not exist. */
    static Doc get(String id) throws Exception {
        try {
            return parseDoc(new JSONObject(request("GET", docUrl(id, null), null)));
        } catch (NotFound e) {
            return null;
        }
    }

    /** Replace the whole document (create if missing) — like setDoc. */
    static void set(String id, Map<String, Object> data) throws Exception {
        request("PATCH", docUrl(id, null), body(data));
    }

    /** Merge the given fields into an existing document — like updateDoc. */
    static void update(String id, Map<String, Object> data) throws Exception {
        List<String> mask = new ArrayList<>(data.keySet());
        request("PATCH", docUrl(id, mask) + "&currentDocument.exists=true", body(data));
    }

    static void delete(String id) throws Exception {
        try {
            request("DELETE", docUrl(id, null), null);
        } catch (NotFound ignored) { }
    }


    /** All documents of another top-level collection (e.g. qb_questions), up to {@code max}. */
    static List<Doc> listCol(String col, int max) throws Exception {
        String base = "https://firestore.googleapis.com/v1/projects/mywebtools-f8d53/databases/(default)/documents/" + col;
        List<Doc> out = new ArrayList<>();
        String token = null;
        do {
            String url = base + "?pageSize=300&key=" + KEY
                + (token != null ? "&pageToken=" + URLEncoder.encode(token, "UTF-8") : "");
            JSONObject o = new JSONObject(request("GET", url, null));
            JSONArray docs = o.optJSONArray("documents");
            if (docs != null) {
                for (int i = 0; i < docs.length(); i++) out.add(parseDoc(docs.getJSONObject(i)));
            }
            token = o.has("nextPageToken") ? o.getString("nextPageToken") : null;
        } while (token != null && out.size() < max);
        return out;
    }

    /**
     * Newest documents whose id starts with {@code prefix} (ids sort chronologically),
     * newest first. Uses a __name__ range query, so no composite index is needed.
     */
    static List<Doc> latestWithPrefix(String prefix, int limit) throws Exception {
        String root = "projects/mywebtools-f8d53/databases/(default)/documents/tutorial_courses/";
        char lastCh = prefix.charAt(prefix.length() - 1);
        String lo = root + prefix;
        String hi = root + prefix.substring(0, prefix.length() - 1) + (char) (lastCh + 1);
        JSONArray filters = new JSONArray()
            .put(nameFilter(lo, "GREATER_THAN_OR_EQUAL"))
            .put(nameFilter(hi, "LESS_THAN"));
        JSONObject q = new JSONObject();
        q.put("from", new JSONArray().put(new JSONObject().put("collectionId", "tutorial_courses")));
        q.put("where", new JSONObject().put("compositeFilter",
            new JSONObject().put("op", "AND").put("filters", filters)));
        q.put("orderBy", new JSONArray().put(new JSONObject()
            .put("field", new JSONObject().put("fieldPath", "__name__"))
            .put("direction", "DESCENDING")));
        q.put("limit", limit);
        String url = "https://firestore.googleapis.com/v1/projects/mywebtools-f8d53/databases/(default)/documents:runQuery?key=" + KEY;
        String res = request("POST", url, new JSONObject().put("structuredQuery", q).toString());
        JSONArray arr = new JSONArray(res);
        List<Doc> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.has("document")) out.add(parseDoc(o.getJSONObject("document")));
        }
        return out;
    }

    /** Documents whose id starts with {@code prefix}, oldest id first (default order: works without any index). */
    static List<Doc> prefixAsc(String prefix, int limit) throws Exception {
        String root = "projects/mywebtools-f8d53/databases/(default)/documents/tutorial_courses/";
        String hi = prefix.substring(0, prefix.length() - 1) + (char) (prefix.charAt(prefix.length() - 1) + 1);
        JSONObject q = new JSONObject();
        q.put("from", new JSONArray().put(new JSONObject().put("collectionId", "tutorial_courses")));
        q.put("where", new JSONObject().put("compositeFilter", new JSONObject().put("op", "AND").put("filters", new JSONArray()
            .put(nameFilter(root + prefix, "GREATER_THAN_OR_EQUAL"))
            .put(nameFilter(root + hi, "LESS_THAN")))));
        q.put("limit", limit);
        String res = request("POST", DB + ":runQuery?key=" + KEY, new JSONObject().put("structuredQuery", q).toString());
        JSONArray arr = new JSONArray(res);
        List<Doc> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.has("document")) out.add(parseDoc(o.getJSONObject("document")));
        }
        return out;
    }

    /** Short explanation of a server error for the screen. */
    static String explain(Exception e) {
        String m = e == null || e.getMessage() == null ? "" : e.getMessage();
        if (m.startsWith("HTTP 429") || m.toLowerCase(java.util.Locale.US).contains("quota"))
            return "Server-এর আজকের ফ্রি সীমা শেষ (quota)। বাংলাদেশ সময় দুপুর ২টার দিকে আবার চালু হবে।";
        if (m.startsWith("HTTP ")) return "Server error: " + m;
        return "Internet সংযোগ দেখো (" + (m.isEmpty() ? "no answer" : m) + ")";
    }

    /** Set once the range query is rejected by the server (HTTP 4xx); chat then uses list() instead. */
    static volatile boolean queryBroken = false;
    static volatile String lastQueryError = "";

    /** Chat messages, newest first: fast range query, with a list()-based fallback that always works. */
    static List<Doc> latestChat(String prefix, int limit) throws Exception {
        if (!queryBroken) {
            try {
                return latestWithPrefix(prefix, limit);
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!m.startsWith("HTTP 4")) throw e;       // network problem: just retry later
                queryBroken = true;
                lastQueryError = m;
            }
        }
        List<Doc> out = new ArrayList<>();
        for (Doc d : list()) if (d.id.startsWith(prefix)) out.add(d);
        java.util.Collections.sort(out, (x, y) -> y.id.compareTo(x.id));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static JSONObject nameFilter(String ref, String op) throws JSONException {
        return new JSONObject().put("fieldFilter", new JSONObject()
            .put("field", new JSONObject().put("fieldPath", "__name__"))
            .put("op", op)
            .put("value", new JSONObject().put("referenceValue", ref)));
    }


    // ── Other collections (push_tokens, push_messages …) via REST — replaces the Firestore SDK ──

    private static final String DB =
        "https://firestore.googleapis.com/v1/projects/mywebtools-f8d53/databases/(default)/documents";

    private static String colDocUrl(String col, String id) throws Exception {
        return DB + "/" + URLEncoder.encode(col, "UTF-8") + "/" + URLEncoder.encode(id, "UTF-8") + "?key=" + KEY;
    }

    /** Replace the whole document {@code col/id} (create if missing). */
    static void setIn(String col, String id, Map<String, Object> data) throws Exception {
        request("PATCH", colDocUrl(col, id), body(data));
    }

    static void deleteIn(String col, String id) throws Exception {
        try {
            request("DELETE", colDocUrl(col, id), null);
        } catch (NotFound ignored) { }
    }

    /**
     * Query one collection: optional single-field filter (op = EQUAL / IN, value = a value or a List for IN),
     * optional order by one field, optional limit (0 = no limit). Single-field only, so no index is needed.
     */
    static List<Doc> query(String col, String field, String op, Object value,
                           String orderField, boolean desc, int limit) throws Exception {
        JSONObject q = new JSONObject();
        q.put("from", new JSONArray().put(new JSONObject().put("collectionId", col)));
        if (field != null) {
            q.put("where", new JSONObject().put("fieldFilter", new JSONObject()
                .put("field", new JSONObject().put("fieldPath", field))
                .put("op", op)
                .put("value", enc(value))));
        }
        if (orderField != null) {
            q.put("orderBy", new JSONArray().put(new JSONObject()
                .put("field", new JSONObject().put("fieldPath", orderField))
                .put("direction", desc ? "DESCENDING" : "ASCENDING")));
        }
        if (limit > 0) q.put("limit", limit);
        String res = request("POST", DB + ":runQuery?key=" + KEY,
            new JSONObject().put("structuredQuery", q).toString());
        JSONArray arr = new JSONArray(res);
        List<Doc> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.has("document")) out.add(parseDoc(o.getJSONObject("document")));
        }
        return out;
    }

    /** Number of documents in a collection (server-side count). */
    static long count(String col) throws Exception {
        JSONObject sq = new JSONObject()
            .put("from", new JSONArray().put(new JSONObject().put("collectionId", col)));
        JSONObject agg = new JSONObject().put("alias", "n").put("count", new JSONObject());
        JSONObject b = new JSONObject().put("structuredAggregationQuery", new JSONObject()
            .put("structuredQuery", sq).put("aggregations", new JSONArray().put(agg)));
        String res = request("POST", DB + ":runAggregationQuery?key=" + KEY, b.toString());
        JSONArray arr = new JSONArray(res);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.getJSONObject(i).optJSONObject("result");
            if (r == null) continue;
            JSONObject f = r.optJSONObject("aggregateFields");
            if (f != null && f.has("n")) return Long.parseLong(f.getJSONObject("n").optString("integerValue", "0"));
        }
        return 0;
    }

    /** Numeric field of a document (Firestore integers arrive as Long). */
    static long num(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    static String text(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? "" : String.valueOf(o);
    }

    // ── HTTP ────────────────────────────────────────────────

    private static String docUrl(String id, List<String> mask) throws Exception {
        StringBuilder sb = new StringBuilder(BASE).append('/')
            .append(URLEncoder.encode(id, "UTF-8")).append("?key=").append(KEY);
        if (mask != null) {
            for (String m : mask) sb.append("&updateMask.fieldPaths=").append(URLEncoder.encode(m, "UTF-8"));
        }
        return sb.toString();
    }

    private static String body(Map<String, Object> data) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("fields", fields(data));
        return o.toString();
    }

    private static String request(String method, String url, String json) throws IOException {
        String r = requestCore(method, url, json);
        mirror(method, url, json);
        return r;
    }

    // ── Write-through mirror ──
    // Every successful write (PATCH / DELETE) is replayed, best effort and in the background, on the OTHER project, so A and B
    // hold the same data while both still have quota (nothing can be copied from A once its quota is gone). It never blocks or
    // fails the caller; if the other side is over quota / not ready it backs off for 10 minutes. The daily GitHub
    // "Sync Firestore" job reconciles whatever was missed.
    private static volatile long mirrorOffUntil = 0;
    private static final java.util.concurrent.ExecutorService MIRROR =
        java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fs-mirror");
                t.setDaemon(true);
                return t;
            }
        });

    private static void mirror(final String method, final String url, final String json) {
        if (!"PATCH".equals(method) && !"DELETE".equals(method)) return;
        if (backupBadNow() || System.currentTimeMillis() < mirrorOffUntil) return;
        if (url.contains("/_call_")) return;                 // temporary call-signalling docs
        final boolean toA = onBackup();                      // the write just landed on B -> copy it to A, otherwise to B
        try {
            MIRROR.execute(new Runnable() {
                @Override public void run() {
                    try {
                        doRequest(method, toA ? url : toBackup(url), toA ? json : toBackup(json));
                    } catch (NotFound ignored) {
                        // doc missing on the other side: the daily sync creates it
                    } catch (IOException e) {
                        if (isQuota(e) || isBackupBad(e)) mirrorOffUntil = System.currentTimeMillis() + 10L * 60L * 1000L;
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    private static String requestCore(String method, String url, String json) throws IOException {
        if (mode == MODE_A) return doRequest(method, url, json);                                   // manual: always A
        if (mode == MODE_B) return doRequest(method, toBackup(url), toBackup(json));               // manual: always B
        if (onBackup()) {
            try {
                return doRequest(method, toBackup(url), toBackup(json));
            } catch (IOException e) {
                if (!isBackupBad(e)) throw e;
                stopFailover();                       // backup not ready -> back to the primary project
                return doRequest(method, url, json);
            }
        }
        try {
            return doRequest(method, url, json);
        } catch (IOException e) {
            if (!isQuota(e) || backupBadNow()) throw e;
            startFailover();
            try {
                return doRequest(method, toBackup(url), toBackup(json));
            } catch (IOException e2) {
                if (isBackupBad(e2)) { stopFailover(); throw e; }   // report the primary's original quota error
                throw e2;
            }
        }
    }

    private static String doRequest(String method, String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("Referer", REFERER);
            c.setRequestProperty("Accept", "application/json");
            if ("PATCH".equals(method)) {
                try {
                    c.setRequestMethod("PATCH");
                } catch (ProtocolException e) {
                    c.setRequestMethod("POST");
                    c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                }
            } else {
                c.setRequestMethod(method);
            }
            if (json != null) {
                byte[] b = json.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = is == null ? "" : readAll(is);
            if (code == 404) {
                // "database/project does not exist" is a setup problem, not a missing document.
                String lt = text.toLowerCase(java.util.Locale.US);
                if (lt.contains("does not exist") && (lt.contains("database") || lt.contains("project")))
                    throw new IOException("HTTP 404 DATABASE_NOT_FOUND " + shortErr(text));
                throw new NotFound();
            }
            if (code >= 400) throw new IOException("HTTP " + code + " " + shortErr(text));
            count(method, url, text);
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    private static String shortErr(String body) {
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e != null) return e.optString("message", body);
        } catch (Exception ignored) { }
        return body.length() > 160 ? body.substring(0, 160) : body;
    }

    // ── Firestore typed value <-> plain Java ────────────────

    private static Doc parseDoc(JSONObject d) throws JSONException {
        String name = d.getString("name");
        String id = name.substring(name.lastIndexOf('/') + 1);
        Map<String, Object> data = new HashMap<>();
        JSONObject f = d.optJSONObject("fields");
        if (f != null) {
            Iterator<String> it = f.keys();
            while (it.hasNext()) {
                String k = it.next();
                data.put(k, dec(f.getJSONObject(k)));
            }
        }
        return new Doc(id, data);
    }

    private static Object dec(JSONObject v) throws JSONException {
        if (v.has("stringValue")) return v.getString("stringValue");
        if (v.has("integerValue")) return Long.valueOf(v.getString("integerValue"));
        if (v.has("doubleValue")) return v.getDouble("doubleValue");
        if (v.has("booleanValue")) return v.getBoolean("booleanValue");
        if (v.has("timestampValue")) return v.getString("timestampValue");
        if (v.has("arrayValue")) {
            List<Object> out = new ArrayList<>();
            JSONArray a = v.getJSONObject("arrayValue").optJSONArray("values");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(dec(a.getJSONObject(i)));
            return out;
        }
        if (v.has("mapValue")) {
            Map<String, Object> out = new HashMap<>();
            JSONObject f = v.getJSONObject("mapValue").optJSONObject("fields");
            if (f != null) {
                Iterator<String> it = f.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    out.put(k, dec(f.getJSONObject(k)));
                }
            }
            return out;
        }
        return null; // nullValue and anything unknown
    }

    private static JSONObject fields(Map<String, Object> m) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Object> e : new LinkedHashMap<>(m).entrySet()) o.put(e.getKey(), enc(e.getValue()));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject enc(Object v) throws JSONException {
        JSONObject o = new JSONObject();
        if (v == null) {
            o.put("nullValue", JSONObject.NULL);
        } else if (v instanceof String) {
            o.put("stringValue", v);
        } else if (v instanceof Boolean) {
            o.put("booleanValue", ((Boolean) v).booleanValue());
        } else if (v instanceof Integer || v instanceof Long) {
            o.put("integerValue", String.valueOf(v));
        } else if (v instanceof Number) {
            o.put("doubleValue", ((Number) v).doubleValue());
        } else if (v instanceof List) {
            JSONArray a = new JSONArray();
            for (Object x : (List<Object>) v) a.put(enc(x));
            o.put("arrayValue", new JSONObject().put("values", a));
        } else if (v instanceof Map) {
            o.put("mapValue", new JSONObject().put("fields", fields((Map<String, Object>) v)));
        } else {
            o.put("stringValue", String.valueOf(v));
        }
        return o;
    }
}
