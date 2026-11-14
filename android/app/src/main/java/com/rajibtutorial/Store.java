package com.rajibtutorial;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-wide state + business rules, ported 1:1 from the web version (index.html). */
final class Store {
    private Store() {}

    static final String LIVE_DOC = "_live_settings";
    static final String[] SEMESTERS = {
        "1st Semester", "2nd Semester", "3rd Semester", "4th Semester",
        "5th Semester", "6th Semester", "7th Semester", "8th Semester"
    };
    static final String[] SUBJECTS = {
        "Basic Electricity", "Electrical Circuits", "Electronics", "Digital Electronics",
        "Electrical Machines", "Power Systems", "Power Electronics", "Control Systems",
        "Microprocessor & Microcontroller", "Measurement & Instrumentation",
        "Electrical Installation & Wiring", "Mathematics", "Physics", "Computer & ICT", "Other"
    };
    static final String ADMIN_PW = "1111";
    static final String BACKEND = "https://rasfocus-backend.onrender.com";
    static final String WHATSAPP = "https://wa.me/8801566054963";
    static final String WHATSAPP_NUM = "01566054963";
    static final String EMBED_ORIGIN = "https://raseledutools.github.io/rajib-tutorial-web/";

    // ── Models ──────────────────────────────────────────────
    static final class Video {
        String id = "", title = "", url = "";
        int durationMinutes = 0;
    }

    static final class Course {
        String id = "", courseName = "", title = "", className = "", subject = "", description = "";
        List<Video> videos = new ArrayList<>();
    }

    static final class Member {
        String id = "", name = "", mobile = "", expiry = "";
        boolean isMember = false;
        Boolean allCourses = null;          // null = field not set
        List<String> courseIds = null;      // null = field not set
        List<String> allowedDevices = new ArrayList<>();
        Map<String, Object> activeDevice = null;
        String sessionToken = "", sessionDevice = "";
    }

    static final class Live {
        String classTitle = "", classTime = "", classUrl = "";
        String examTitle = "", examTime = "", examUrl = "";
        String premiumUrl = "", premiumInfo = "";
    }

    // ── State ───────────────────────────────────────────────
    static List<Course> courses = new ArrayList<>();
    static List<Member> members = new ArrayList<>();
    static Live live = new Live();
    static boolean adminIn = false;
    static String sessionMobile = null, sessionToken = null;
    static String deviceId = "";
    static String pendingToast = null;

    private static SharedPreferences prefs;
    private static boolean inited = false;

    interface Cb<T> { void done(T res, Exception err); }

    private static final ExecutorService EX = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static <T> void async(final Callable<T> job, final Cb<T> cb) {
        EX.execute(() -> {
            T r = null;
            Exception e = null;
            try { r = job.call(); } catch (Exception ex) { e = ex; }
            final T fr = r;
            final Exception fe = e;
            if (cb != null) MAIN.post(() -> cb.done(fr, fe));
        });
    }

    static void init(Context ctx) {
        if (inited) return;
        inited = true;
        prefs = ctx.getApplicationContext().getSharedPreferences("rt_native", Context.MODE_PRIVATE);
        String d = prefs.getString("rt_device_id", null);
        if (d == null) {
            d = "dev_" + Long.toString(System.currentTimeMillis(), 36) + "_" + UUID.randomUUID().toString().substring(0, 6);
            prefs.edit().putString("rt_device_id", d).apply();
        }
        deviceId = d;
        sessionMobile = prefs.getString("rt_mobile", null);
        sessionToken = prefs.getString("rt_token", null);
        if (sessionMobile != null && sessionToken == null) sessionMobile = null;
    }

    private static void saveSession() {
        SharedPreferences.Editor ed = prefs.edit();
        if (sessionMobile != null) ed.putString("rt_mobile", sessionMobile).putString("rt_token", sessionToken);
        else ed.remove("rt_mobile").remove("rt_token");
        ed.apply();
    }

    // ── Helpers ─────────────────────────────────────────────
    static String esc(String s) { return s == null ? "" : s; }

    static String safeUrl(String u) {
        u = u == null ? "" : u.trim();
        String l = u.toLowerCase(Locale.US);
        return (l.startsWith("http://") || l.startsWith("https://")) ? u : "";
    }

    static String normMobile(String v) {
        String d = v == null ? "" : v.replaceAll("\\D", "");
        if (d.startsWith("880")) d = "0" + d.substring(3);
        return d;
    }

    static String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static String deviceLabel() {
        return "Android · " + (Build.MODEL == null ? "App" : Build.MODEL);
    }

    static String lessonsLabel(int n) { return n + (n == 1 ? " lesson" : " lessons"); }

    static String ytEmbed(String url, boolean autoplay) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})").matcher(url);
        if (!m.find()) return null;
        return "https://www.youtube.com/embed/" + m.group(1) + "?autoplay=" + (autoplay ? "1" : "0")
            + "&rel=0&modestbranding=1&iv_load_policy=3&playsinline=1&color=white&enablejsapi=0&fs=0";
    }

    static String platformOf(String u) {
        try {
            String h = new java.net.URL(u).getHost().replaceFirst("^www\\.", "");
            if (h.contains("youtu")) return "YouTube Live";
            if (h.contains("meet.google")) return "Google Meet";
            if (h.contains("zoom")) return "Zoom";
            if (h.contains("facebook") || h.contains("fb.watch")) return "Facebook Live";
            return h;
        } catch (Exception e) {
            return "Online";
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof String ? (String) o : "";
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (!(o instanceof List)) return null;
        List<String> out = new ArrayList<>();
        for (Object x : (List<Object>) o) if (x instanceof String) out.add((String) x);
        return out;
    }

    static Map<String, Object> videoMap(Video v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id);
        m.put("title", v.title);
        m.put("cloudinaryUrl", v.url);
        m.put("url", v.url);
        m.put("durationMinutes", Integer.valueOf(v.durationMinutes));
        return m;
    }

    static List<Object> videoMaps(List<Video> vids) {
        List<Object> out = new ArrayList<>();
        for (Video v : vids) out.add(videoMap(v));
        return out;
    }

    static Live copyLive(Live l) {
        Live n = new Live();
        n.classTitle = l.classTitle; n.classTime = l.classTime; n.classUrl = l.classUrl;
        n.examTitle = l.examTitle; n.examTime = l.examTime; n.examUrl = l.examUrl;
        n.premiumUrl = l.premiumUrl; n.premiumInfo = l.premiumInfo;
        return n;
    }

    static Map<String, Object> liveMap(Live l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("classTitle", l.classTitle); m.put("classTime", l.classTime); m.put("classUrl", l.classUrl);
        m.put("examTitle", l.examTitle); m.put("examTime", l.examTime); m.put("examUrl", l.examUrl);
        m.put("premiumUrl", l.premiumUrl); m.put("premiumInfo", l.premiumInfo);
        return m;
    }

    static Member parseMember(Fs.Doc d) {
        Member m = new Member();
        m.id = d.id;
        m.name = str(d.data, "name");
        m.mobile = str(d.data, "mobile");
        m.expiry = str(d.data, "expiry");
        m.isMember = Boolean.TRUE.equals(d.data.get("isMember"));
        Object ac = d.data.get("allCourses");
        m.allCourses = ac instanceof Boolean ? (Boolean) ac : null;
        m.courseIds = strList(d.data.get("courseIds"));
        List<String> ad = strList(d.data.get("allowedDevices"));
        m.allowedDevices = ad == null ? new ArrayList<String>() : ad;
        @SuppressWarnings("unchecked")
        Map<String, Object> dev = d.data.get("activeDevice") instanceof Map
            ? (Map<String, Object>) d.data.get("activeDevice") : null;
        m.activeDevice = dev;
        m.sessionToken = str(d.data, "sessionToken");
        m.sessionDevice = str(d.data, "sessionDevice");
        return m;
    }

    // ── Loading ─────────────────────────────────────────────
    private static final class Loaded {
        List<Course> courses = new ArrayList<>();
        List<Member> members = new ArrayList<>();
        Live live = new Live();
    }

    @SuppressWarnings("unchecked")
    static void loadAll(final Cb<Boolean> cb) {
        async(() -> {
            Loaded r = new Loaded();
            for (Fs.Doc d : Fs.list()) {
                if (LIVE_DOC.equals(d.id)) {
                    Object lo = d.data.get("live");
                    if (lo instanceof Map) {
                        Map<String, Object> lm = (Map<String, Object>) lo;
                        r.live.classTitle = str(lm, "classTitle"); r.live.classTime = str(lm, "classTime");
                        r.live.classUrl = str(lm, "classUrl"); r.live.examTitle = str(lm, "examTitle");
                        r.live.examTime = str(lm, "examTime"); r.live.examUrl = str(lm, "examUrl");
                        r.live.premiumUrl = str(lm, "premiumUrl"); r.live.premiumInfo = str(lm, "premiumInfo");
                    }
                    continue;
                }
                if (d.id.startsWith("_member_")) { r.members.add(parseMember(d)); continue; }
                if (d.id.startsWith("_disc_") || d.id.startsWith("_call_")) continue;   // Discussion chat / call signalling — not courses
                Course c = new Course();
                c.id = d.id;
                c.courseName = str(d.data, "courseName");
                c.title = str(d.data, "title").isEmpty() ? d.id : str(d.data, "title");
                c.className = str(d.data, "className");
                c.subject = str(d.data, "subject");
                c.description = str(d.data, "description");
                Object vo = d.data.get("videos");
                if (vo instanceof List) {
                    for (Object o : (List<Object>) vo) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> vm = (Map<String, Object>) o;
                        Video v = new Video();
                        v.id = str(vm, "id");
                        v.title = str(vm, "title");
                        v.url = !str(vm, "cloudinaryUrl").isEmpty() ? str(vm, "cloudinaryUrl") : str(vm, "url");
                        Object du = vm.get("durationMinutes");
                        v.durationMinutes = du instanceof Number ? ((Number) du).intValue() : 0;
                        c.videos.add(v);
                    }
                }
                r.courses.add(c);
            }
            return r;
        }, (Loaded r, Exception e) -> {
            if (e != null || r == null) { cb.done(false, e); return; }
            courses = r.courses;
            members = r.members;
            live = r.live;
            String vm = validateSession();
            if (vm != null) pendingToast = vm;
            cb.done(true, null);
        });
    }

    static Course findCourse(String id) {
        for (Course c : courses) if (c.id.equals(id)) return c;
        return null;
    }

    // ── Premium rules ───────────────────────────────────────
    static Member curMember() {
        if (sessionMobile == null) return null;
        for (Member m : members) if (m.id.equals("_member_" + sessionMobile)) return m;
        return null;
    }

    static boolean memberValid(Member m) {
        return m != null && sessionMobile != null && (m.expiry.isEmpty() || todayStr().compareTo(m.expiry) <= 0);
    }

    static boolean hasAll(Member m) {
        return Boolean.TRUE.equals(m.allCourses) || (m.allCourses == null && m.courseIds == null);
    }

    static boolean isPremium() { return memberValid(curMember()); }

    static boolean courseAllowed(Course c) {
        if (adminIn) return true;
        Member m = curMember();
        if (c == null || m == null || !isPremium()) return false;
        return hasAll(m) || (m.courseIds != null && m.courseIds.contains(c.id));
    }

    static String accessLabel(Member m) {
        if (hasAll(m)) return "All courses";
        int n = 0;
        if (m.courseIds != null) for (String id : m.courseIds) if (findCourse(id) != null) n++;
        return n + (n == 1 ? " course" : " courses");
    }

    /** Returns a toast message when the session had to be dropped, else null. */
    static String validateSession() {
        if (sessionMobile != null && !isPremium()) {
            Member m = curMember();
            String msg = (m != null && !m.expiry.isEmpty() && todayStr().compareTo(m.expiry) > 0)
                ? "আপনার Premium membership মেয়াদোত্তীর্ণ হয়েছে" : null;
            sessionMobile = null; sessionToken = null; saveSession();
            return msg;
        }
        return null;
    }

    // ── Member auth / device handling ───────────────────────
    static final class AuthResult {
        String err = null, name = "", otherDevice = "";
        boolean noAccess = false, deviceConflict = false;
    }

    static void memberAuth(final String mobile, final boolean force, final Cb<AuthResult> cb) {
        async(() -> {
            AuthResult r = new AuthResult();
            Fs.Doc d;
            try {
                d = Fs.get("_member_" + mobile);
            } catch (Exception e) {
                r.err = "এখন verify করা যাচ্ছে না। আবার চেষ্টা করুন।";
                return new Object[]{r, null};
            }
            Member m = d == null ? null : parseMember(d);
            if (m == null || !m.isMember) { r.noAccess = true; return new Object[]{r, null}; }
            if (!m.expiry.isEmpty() && todayStr().compareTo(m.expiry) > 0) {
                r.err = "এই Premium membership মেয়াদোত্তীর্ণ। অনুগ্রহ করে নবায়ন করুন।";
                return new Object[]{r, null};
            }
            if (!m.allowedDevices.isEmpty() && !m.allowedDevices.contains(deviceId)) {
                r.err = "এই device থেকে login করার অনুমতি নেই। Admin এর সাথে যোগাযোগ করুন।";
                return new Object[]{r, null};
            }
            boolean otherActive = !force && !m.sessionToken.isEmpty() && !m.sessionDevice.isEmpty()
                && !m.sessionDevice.equals(deviceId);
            if (otherActive) {
                r.deviceConflict = true;
                Object lbl = m.activeDevice == null ? null : m.activeDevice.get("label");
                r.otherDevice = lbl instanceof String ? (String) lbl : "অন্য device";
                return new Object[]{r, null};
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> dev = new LinkedHashMap<>();
            dev.put("deviceId", deviceId);
            dev.put("label", deviceLabel());
            dev.put("loginTime", Long.valueOf(System.currentTimeMillis()));
            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("activeDevice", dev);
            upd.put("sessionToken", token);
            upd.put("sessionDevice", deviceId);
            try {
                Fs.update("_member_" + mobile, upd);
            } catch (Exception e) {
                r.err = "এখন verify করা যাচ্ছে না। আবার চেষ্টা করুন।";
                return new Object[]{r, null};
            }
            m.activeDevice = dev; m.sessionToken = token; m.sessionDevice = deviceId;
            r.name = m.name.isEmpty() ? "Member" : m.name;
            return new Object[]{r, new Object[]{m, token}};
        }, (Object[] res, Exception e) -> {
            if (e != null || res == null) {
                AuthResult x = new AuthResult();
                x.err = "এখন verify করা যাচ্ছে না। আবার চেষ্টা করুন।";
                cb.done(x, null);
                return;
            }
            AuthResult r = (AuthResult) res[0];
            if (res[1] != null) {
                Object[] pair = (Object[]) res[1];
                Member m = (Member) pair[0];
                sessionMobile = mobile;
                sessionToken = (String) pair[1];
                saveSession();
                boolean replaced = false;
                for (int i = 0; i < members.size(); i++) {
                    if (members.get(i).id.equals(m.id)) { members.set(i, m); replaced = true; }
                }
                if (!replaced) members.add(m);
            }
            cb.done(r, null);
        });
    }

    /**
     * Checks the Firestore session record. Cb result: a message when the user
     * must be logged out, "" when everything is fine, null on network trouble.
     */
    static void checkSession(final boolean forPlay, final Cb<String> cb) {
        final String mobile = sessionMobile, token = sessionToken;
        if (mobile == null || adminIn) { cb.done("", null); return; }
        async(() -> {
            Fs.Doc d = Fs.get("_member_" + mobile);
            if (d == null) return forPlay ? "" : "এই account টি মুছে ফেলা হয়েছে।";
            Member m = parseMember(d);
            if (!m.sessionToken.isEmpty() && !m.sessionToken.equals(token)) {
                return forPlay ? "এই video চালু করা যাচ্ছে না। আপনার account অন্য device-এ login হয়েছে।"
                               : "আপনার account অন্য একটি device-এ login হয়েছে। এই session শেষ।";
            }
            if (forPlay && !m.allowedDevices.isEmpty() && !m.allowedDevices.contains(deviceId)) {
                return "এই device-এ video দেখার অনুমতি নেই।";
            }
            if (!m.expiry.isEmpty() && todayStr().compareTo(m.expiry) > 0) {
                return "আপনার Premium membership মেয়াদোত্তীর্ণ।";
            }
            return "";
        }, (String r, Exception e) -> cb.done(e != null ? null : r, null));
    }

    static void dropSession() {
        sessionMobile = null; sessionToken = null; saveSession();
    }

    static void memberLogout() {
        final String mobile = sessionMobile;
        dropSession();
        if (mobile == null) return;
        async(() -> {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("activeDevice", null);
            u.put("sessionToken", null);
            u.put("sessionDevice", null);
            Fs.update("_member_" + mobile, u);
            return Boolean.TRUE;
        }, null);
    }

    // ── bKash payment ───────────────────────────────────────
    /** Returns the bKash URL, or throws an Exception whose message is shown to the user. */
    static String createPayment(String name, String mobile, String plan) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BACKEND + "/api/tutorial/bkash/create").openConnection();
        try {
            c.setConnectTimeout(45000);
            c.setReadTimeout(45000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            JSONObject b = new JSONObject();
            b.put("name", name);
            b.put("mobile", mobile);
            b.put("plan", plan);
            byte[] bytes = b.toString().getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.close();
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            JSONObject d = new JSONObject(sb.toString());
            if (d.optBoolean("success") && d.optString("bkashURL", "").length() > 0) return d.getString("bkashURL");
            throw new Exception(d.optString("message", "Could not start the payment. Please try again."));
        } finally {
            c.disconnect();
        }
    }
}
