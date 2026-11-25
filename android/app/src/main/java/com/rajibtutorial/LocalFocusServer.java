package com.rajibtutorial;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Local focus-page server (127.0.0.1 only) — the Android twin of web-exe/ext-server.js. It serves the SAME page
 * (assets/focus/extfocus.html: quotes, 25-min timer, Pomodoro, allowed websites, calm noise) at http://localhost:PORT/focus
 * and the same tiny JSON API:  GET /api/state · POST /api/start · POST /api/pomo · POST /api/sites.
 * Every /api call needs the per-run token header + a localhost Host + no foreign Origin. Nothing leaves the phone.
 */
final class LocalFocusServer {
    private LocalFocusServer() { }

    static final int PORT = 38417;
    static final String FOCUS_URL = "http://localhost:" + PORT + "/focus";
    private static final int POMO_MAX = 8, BREAK_MIN = 5;
    private static final String TOKEN = new BigInteger(128, new SecureRandom()).toString(16);
    private static final Object LK = new Object();
    private static Thread th;

    static synchronized void start(final Context c) {
        if (th != null && th.isAlive()) return;
        final Context app = c.getApplicationContext();
        th = new Thread(() -> {
            ServerSocket ss;
            try { ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")); }
            catch (Exception e) { return; }
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    new Thread(() -> handle(app, s), "rt-focus-conn").start();
                } catch (Exception e) {
                    if (ss.isClosed()) break;
                }
            }
        }, "rt-focus-local");
        th.setDaemon(true);
        th.start();
    }

    // ── state: allowed sites + Pomodoro (kept in prefs; the 25-min session itself is FocusManager's) ──

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("rt_focus_local", Context.MODE_PRIVATE); }

    private static JSONArray sites(Context c) {
        try { return new JSONArray(sp(c).getString("sites", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static JSONObject pomo(Context c) {
        try { String s = sp(c).getString("pomo", ""); return s.isEmpty() ? null : new JSONObject(s); } catch (Exception e) { return null; }
    }

    private static void savePomo(Context c, JSONObject p) {
        sp(c).edit().putString("pomo", p == null ? "" : p.toString()).apply();
    }

    private static String normHost(String in) {
        String s = in == null ? "" : in.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z]+://", "");
        int i = s.indexOf('/'); if (i >= 0) s = s.substring(0, i);
        i = s.indexOf('?'); if (i >= 0) s = s.substring(0, i);
        s = s.replaceFirst("^www\\.", "").replaceFirst(":\\d+$", "");
        return s.matches("^[a-z0-9-]+(\\.[a-z0-9-]+)+$") && s.length() <= 80 ? s : null;
    }

    /** A site the person allowed on the focus page (sub-domains included). */
    static boolean isAllowedHost(Context c, String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        JSONArray a = sites(c);
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i);
            if (!s.isEmpty() && (h.equals(s) || h.endsWith("." + s))) return true;
        }
        return false;
    }

    /** Moves a Pomodoro on (focus → break → next focus → end). Called from the state API and from the accessibility service. */
    static void tick(Context c) {
        synchronized (LK) {
            JSONObject p = pomo(c);
            if (p == null) return;
            FocusManager fm = new FocusManager(c);
            try {
                String ph = p.optString("phase");
                if ("focus".equals(ph) && !fm.isSessionActive()) {
                    if (p.optInt("idx") >= p.optInt("total")) savePomo(c, null);
                    else { p.put("phase", "break"); p.put("breakEndAt", System.currentTimeMillis() + BREAK_MIN * 60000L); savePomo(c, p); }
                } else if ("break".equals(ph) && System.currentTimeMillis() >= p.optLong("breakEndAt")) {
                    p.put("idx", p.optInt("idx") + 1); p.put("phase", "focus"); p.put("breakEndAt", 0);
                    savePomo(c, p);
                    FocusManager.quickStart(c);
                }
            } catch (Exception ignored) { }
        }
    }

    private static JSONObject state(Context c) throws Exception {
        tick(c);
        FocusManager fm = new FocusManager(c);
        JSONObject st = new JSONObject();
        st.put("active", fm.isSessionActive());
        st.put("endAt", fm.getSessionEndMs());
        st.put("now", System.currentTimeMillis());
        st.put("sites", sites(c));
        st.put("minutes", FocusManager.QUICK_FOCUS_MS / 60000);
        st.put("breakMinutes", BREAK_MIN);
        st.put("maxSessions", POMO_MAX);
        st.put("sessions", 1);
        JSONObject p = pomo(c);
        st.put("pomo", p == null ? JSONObject.NULL : p);
        return st;
    }

    private static JSONObject api(Context c, String method, String path, JSONObject o) throws Exception {
        synchronized (LK) {
            FocusManager fm = new FocusManager(c);
            if (method.equals("GET") && path.equals("/api/state")) return state(c);
            JSONObject r = new JSONObject();
            if (path.equals("/api/start")) {
                if (!fm.isSessionActive()) {
                    int n = Math.max(1, Math.min(POMO_MAX, o.optInt("sessions", 1)));
                    JSONObject p = null;
                    if (n > 1) { p = new JSONObject(); p.put("total", n); p.put("idx", 1); p.put("phase", "focus"); p.put("breakEndAt", 0); }
                    savePomo(c, p);
                    FocusManager.quickStart(c);
                }
                return r.put("ok", true);
            }
            if (path.equals("/api/pomo")) {
                JSONObject p = pomo(c);
                if (p == null || !"break".equals(p.optString("phase"))) return r.put("ok", false);
                if ("stop".equals(o.optString("action"))) { savePomo(c, null); return r.put("ok", true); }
                if ("skip".equals(o.optString("action"))) { p.put("breakEndAt", 0); savePomo(c, p); tick(c); return r.put("ok", true); }
                return r.put("ok", false);
            }
            if (path.equals("/api/sites")) {
                if (fm.isSessionActive()) return r.put("ok", false);            // locked while a session runs
                JSONArray a = sites(c);
                JSONArray out = new JSONArray();
                if (o.has("add")) {
                    String h = normHost(o.optString("add"));
                    if (h == null) return r.put("ok", false);
                    boolean dup = false;
                    for (int i = 0; i < a.length(); i++) { out.put(a.optString(i)); if (a.optString(i).equals(h)) dup = true; }
                    if (!dup && a.length() < 40) out.put(h);
                } else if (o.has("remove")) {
                    String h = o.optString("remove");
                    for (int i = 0; i < a.length(); i++) if (!a.optString(i).equals(h)) out.put(a.optString(i));
                } else return r.put("ok", false);
                sp(c).edit().putString("sites", out.toString()).apply();
                return r.put("ok", true).put("sites", out);
            }
            return r.put("ok", false);
        }
    }

    // ── tiny HTTP ──

    private static String asset(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void send(Socket s, int code, String type, String body, String extra) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
            + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" + extra + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        o.write(b);
        o.flush();
    }

    private static void handle(Context c, Socket s) {
        try {
            s.setSoTimeout(4000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = r.readLine();
            if (line == null) { s.close(); return; }
            String[] p = line.split(" ");
            String method = p[0], path = p.length > 1 ? p[1].split("\\?")[0] : "/";
            String host = "", origin = "", tok = "";
            int len = 0;
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i < 0) continue;
                String k = h.substring(0, i).trim().toLowerCase(Locale.ROOT), v = h.substring(i + 1).trim();
                if (k.equals("host")) host = v;
                else if (k.equals("origin")) origin = v;
                else if (k.equals("x-rf-token")) tok = v;
                else if (k.equals("content-length")) { try { len = Integer.parseInt(v); } catch (Exception ignored) { } }
            }
            boolean hostOk = host.matches("^(localhost|127\\.0\\.0\\.1):\\d+$");
            boolean originOk = origin.isEmpty() || origin.equals("http://localhost:" + PORT) || origin.equals("http://127.0.0.1:" + PORT);
            if (!hostOk) { send(s, 403, "text/plain", "forbidden", ""); s.close(); return; }
            if (method.equals("GET") && !path.startsWith("/api/")) {
                String html = asset(c, "focus/extfocus.html").replace("__RF_TOKEN__", TOKEN).replace("/*__NOISE_JS__*/", asset(c, "focus/focus-noise.js"));
                send(s, 200, "text/html; charset=utf-8", html,
                    "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'\r\n");
                s.close();
                return;
            }
            if (!path.startsWith("/api/") || !TOKEN.equals(tok) || !originOk) { send(s, 403, "application/json", "{\"ok\":false}", ""); s.close(); return; }
            JSONObject o = new JSONObject();
            if (method.equals("POST")) {
                if (len > 2000) { s.close(); return; }
                char[] buf = new char[len];
                int got = 0;
                while (got < len) { int n = r.read(buf, got, len - got); if (n < 0) break; got += n; }
                try { o = new JSONObject(new String(buf, 0, got)); } catch (Exception ignored) { }
            } else if (!method.equals("GET")) { send(s, 405, "application/json", "{\"ok\":false}", ""); s.close(); return; }
            send(s, 200, "application/json; charset=utf-8", api(c, method, path, o).toString(), "");
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }
}
