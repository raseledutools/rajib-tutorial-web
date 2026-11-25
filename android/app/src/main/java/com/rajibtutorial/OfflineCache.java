package com.rajibtutorial;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * WhatsApp-style local copy of everything the app loads from Firebase: courses (+ lessons),
 * live settings, circular / question-bank links, the logged-in member, the notification inbox and
 * the latest Discussion messages. The app opens instantly from this copy, works with no internet,
 * and a fresh download simply replaces the copy whenever the network is back.
 *
 * Files live in the app's private storage (not visible to other apps or in any folder).
 * Other members' records are never written to disk — only the logged-in member's own record.
 */
final class OfflineCache {
    private OfflineCache() {}

    private static final String F_MAIN = "rt_cache_main.json";
    private static final String F_INBOX = "rt_cache_inbox.json";
    private static final String F_CHAT = "rt_cache_chat.json";
    private static final Object LOCK = new Object();

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;      // unknown: let the real request decide
        }
    }

    // ── low level ───────────────────────────────────────────
    private static void write(Context c, String name, String text) {
        synchronized (LOCK) {
            File tmp = new File(c.getFilesDir(), name + ".tmp");
            File dst = new File(c.getFilesDir(), name);
            try {
                FileOutputStream os = new FileOutputStream(tmp);
                os.write(text.getBytes("UTF-8"));
                os.flush();
                os.getFD().sync();
                os.close();
                if (!tmp.renameTo(dst)) {            // very old devices: rename over an existing file can fail
                    //noinspection ResultOfMethodCallIgnored
                    dst.delete();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(dst);
                }
            } catch (Exception ignored) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static String read(Context c, String name) {
        synchronized (LOCK) {
            File f = new File(c.getFilesDir(), name);
            if (!f.exists()) return null;
            try {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                return bos.toString("UTF-8");
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static void async(Runnable r) {
        Thread t = new Thread(r, "rt-cache");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Wipes the cached copy (used on admin / member data that must not outlive a logout). */
    static void clearAll(Context c) {
        synchronized (LOCK) {
            for (String n : new String[]{F_MAIN, F_INBOX, F_CHAT}) {
                //noinspection ResultOfMethodCallIgnored
                new File(c.getFilesDir(), n).delete();
            }
        }
    }

    // ── main data: courses / live / resources / current member ──────────
    static void saveMain(final Context ctx) {
        if (ctx == null) return;
        final Context c = ctx.getApplicationContext();
        // snapshot on the caller's thread, write on a worker
        final JSONObject o;
        try {
            o = new JSONObject();
            JSONArray cs = new JSONArray();
            for (Store.Course x : new ArrayList<>(Store.courses)) {
                JSONObject j = new JSONObject();
                j.put("id", x.id);
                j.put("courseName", x.courseName);
                j.put("title", x.title);
                j.put("className", x.className);
                j.put("subject", x.subject);
                j.put("description", x.description);
                j.put("category", x.category);
                j.put("price", x.price);
                JSONArray vs = new JSONArray();
                for (Store.Video v : x.videos) {
                    JSONObject vj = new JSONObject();
                    vj.put("id", v.id);
                    vj.put("title", v.title);
                    vj.put("url", v.url);
                    vj.put("durationMinutes", v.durationMinutes);
                    vs.put(vj);
                }
                j.put("videos", vs);
                cs.put(j);
            }
            o.put("courses", cs);

            Store.Live l = Store.live;
            JSONObject lj = new JSONObject();
            lj.put("classTitle", l.classTitle); lj.put("classTime", l.classTime); lj.put("classUrl", l.classUrl);
            lj.put("examTitle", l.examTitle); lj.put("examTime", l.examTime); lj.put("examUrl", l.examUrl);
            lj.put("premiumUrl", l.premiumUrl); lj.put("premiumInfo", l.premiumInfo);
            lj.put("payBkash", l.payBkash); lj.put("payNagad", l.payNagad); lj.put("payRocket", l.payRocket);
            o.put("live", lj);

            JSONArray rs = new JSONArray();
            for (Store.Resource r : new ArrayList<>(Store.resources)) {
                JSONObject rj = new JSONObject();
                rj.put("id", r.id); rj.put("type", r.type); rj.put("category", r.category);
                rj.put("title", r.title); rj.put("url", r.url); rj.put("note", r.note); rj.put("at", r.at);
                rs.put(rj);
            }
            o.put("resources", rs);

            // only the logged-in member's own record (needed for the premium check while offline)
            Store.Member me = Store.curMember();
            if (me != null) {
                JSONObject mj = new JSONObject();
                mj.put("id", me.id); mj.put("name", me.name); mj.put("mobile", me.mobile);
                mj.put("expiry", me.expiry); mj.put("batch", me.batch); mj.put("isMember", me.isMember);
                if (me.allCourses != null) mj.put("allCourses", me.allCourses.booleanValue());
                if (me.courseIds != null) mj.put("courseIds", new JSONArray(me.courseIds));
                mj.put("allowedDevices", new JSONArray(me.allowedDevices));
                o.put("member", mj);
            }
            o.put("savedAt", System.currentTimeMillis());
        } catch (Exception e) {
            return;
        }
        async(() -> write(c, F_MAIN, o.toString()));
    }

    /** Fills Store.courses / live / resources / members from the saved copy. Returns false if there is none. */
    static boolean loadMain(Context ctx) {
        String s = read(ctx.getApplicationContext(), F_MAIN);
        if (s == null) return false;
        try {
            JSONObject o = new JSONObject(s);
            List<Store.Course> courses = new ArrayList<>();
            JSONArray cs = o.optJSONArray("courses");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject j = cs.getJSONObject(i);
                    Store.Course c = new Store.Course();
                    c.id = j.optString("id");
                    c.courseName = j.optString("courseName");
                    c.title = j.optString("title", c.id);
                    c.className = j.optString("className");
                    c.subject = j.optString("subject");
                    c.description = j.optString("description");
                    c.category = Store.isCat(j.optString("category")) ? j.optString("category") : "diploma";
                    c.price = j.optInt("price", 0);
                    JSONArray vs = j.optJSONArray("videos");
                    if (vs != null) {
                        for (int k = 0; k < vs.length(); k++) {
                            JSONObject vj = vs.getJSONObject(k);
                            Store.Video v = new Store.Video();
                            v.id = vj.optString("id");
                            v.title = vj.optString("title");
                            v.url = vj.optString("url");
                            v.durationMinutes = vj.optInt("durationMinutes", 0);
                            c.videos.add(v);
                        }
                    }
                    courses.add(c);
                }
            }
            Store.Live live = new Store.Live();
            JSONObject lj = o.optJSONObject("live");
            if (lj != null) {
                live.classTitle = lj.optString("classTitle"); live.classTime = lj.optString("classTime"); live.classUrl = lj.optString("classUrl");
                live.examTitle = lj.optString("examTitle"); live.examTime = lj.optString("examTime"); live.examUrl = lj.optString("examUrl");
                live.premiumUrl = lj.optString("premiumUrl"); live.premiumInfo = lj.optString("premiumInfo");
                live.payBkash = lj.optString("payBkash"); live.payNagad = lj.optString("payNagad"); live.payRocket = lj.optString("payRocket");
            }
            List<Store.Resource> res = new ArrayList<>();
            JSONArray rs = o.optJSONArray("resources");
            if (rs != null) {
                for (int i = 0; i < rs.length(); i++) {
                    JSONObject rj = rs.getJSONObject(i);
                    Store.Resource r = new Store.Resource();
                    r.id = rj.optString("id"); r.type = rj.optString("type"); r.category = rj.optString("category");
                    r.title = rj.optString("title"); r.url = rj.optString("url"); r.note = rj.optString("note");
                    r.at = rj.optLong("at", 0L);
                    res.add(r);
                }
            }
            List<Store.Member> members = new ArrayList<>();
            JSONObject mj = o.optJSONObject("member");
            if (mj != null) {
                Store.Member m = new Store.Member();
                m.id = mj.optString("id"); m.name = mj.optString("name"); m.mobile = mj.optString("mobile");
                m.expiry = mj.optString("expiry"); m.batch = mj.optString("batch");
                m.isMember = mj.optBoolean("isMember", false);
                if (mj.has("allCourses")) m.allCourses = Boolean.valueOf(mj.optBoolean("allCourses"));
                JSONArray ci = mj.optJSONArray("courseIds");
                if (ci != null) { m.courseIds = new ArrayList<>(); for (int i = 0; i < ci.length(); i++) m.courseIds.add(ci.optString(i)); }
                JSONArray ad = mj.optJSONArray("allowedDevices");
                if (ad != null) for (int i = 0; i < ad.length(); i++) m.allowedDevices.add(ad.optString(i));
                members.add(m);
            }
            Store.courses = courses;
            Store.live = live;
            java.util.Collections.sort(res, (x, y) -> Long.compare(y.at, x.at));
            Store.resources = res;
            Store.members = members;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── notification inbox (already filtered for this member) ───────────
    static void saveInbox(Context ctx, final JSONArray items) {
        final Context c = ctx.getApplicationContext();
        async(() -> write(c, F_INBOX, items.toString()));
    }

    static JSONArray loadInbox(Context ctx) {
        String s = read(ctx.getApplicationContext(), F_INBOX);
        if (s == null) return new JSONArray();
        try { return new JSONArray(s); } catch (Exception e) { return new JSONArray(); }
    }

    // ── discussion: the newest messages (raw Firestore docs, newest first) ──
    static void saveChat(Context ctx, List<Fs.Doc> docs) {
        final Context c = ctx.getApplicationContext();
        try {
            final JSONArray arr = new JSONArray();
            int n = 0;
            for (Fs.Doc d : docs) {
                if (n++ >= 120) break;
                JSONObject o = new JSONObject();
                o.put("id", d.id);
                o.put("data", toJson(d.data));
                arr.put(o);
            }
            async(() -> write(c, F_CHAT, arr.toString()));
        } catch (Exception ignored) { }
    }

    static List<Fs.Doc> loadChat(Context ctx) {
        List<Fs.Doc> out = new ArrayList<>();
        String s = read(ctx.getApplicationContext(), F_CHAT);
        if (s == null) return out;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Fs.Doc(o.getString("id"), toMap(o.getJSONObject("data"))));
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ── JSON <-> plain Java (Long / Double / Boolean / String / List / Map) ──
    private static JSONObject toJson(Map<String, Object> m) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Object> e : m.entrySet()) o.put(e.getKey(), wrap(e.getValue()));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Object wrap(Object v) throws Exception {
        if (v == null) return JSONObject.NULL;
        if (v instanceof Map) return toJson((Map<String, Object>) v);
        if (v instanceof List) {
            JSONArray a = new JSONArray();
            for (Object x : (List<Object>) v) a.put(wrap(x));
            return a;
        }
        return v;   // String / Long / Double / Boolean
    }

    private static Map<String, Object> toMap(JSONObject o) throws Exception {
        Map<String, Object> m = new HashMap<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            m.put(k, unwrap(o.get(k)));
        }
        return m;
    }

    private static Object unwrap(Object v) throws Exception {
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof JSONObject) return toMap((JSONObject) v);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<Object> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) l.add(unwrap(a.get(i)));
            return l;
        }
        if (v instanceof Integer) return Long.valueOf(((Integer) v).longValue());   // Fs.dec returns Long for integers
        return v;
    }
}
