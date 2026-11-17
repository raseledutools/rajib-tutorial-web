package com.rajibtutorial;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manual bKash TrxID payments (works beside the automatic bKash checkout).
 *
 *  user  : pays by bKash, enters the TrxID  -> request doc "_payreq_<TRX>" + push to the admin phone
 *  admin : compares the TrxID with the bKash SMS, taps Accept in Notification Center
 *          -> course is unlocked for that mobile + push goes to the user's phone
 */
final class PayRequests {
    private PayRequests() {}

    static final String PREFIX = "_payreq_";
    static final String PENDING = "pending", ACCEPTED = "accepted", REJECTED = "rejected";

    static final class Req {
        String id = "", trx = "", name = "", mobile = "", courseId = "", courseTitle = "", status = PENDING;
        int amount = 0;
        long at = 0, decidedAt = 0;
    }

    static String cleanTrx(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    static boolean validTrx(String t) { return t != null && t.length() >= 8 && t.length() <= 14; }

    private static String s(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? "" : String.valueOf(o);
    }

    private static long l(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    static Req parse(Fs.Doc d) {
        Req r = new Req();
        r.id = d.id;
        r.trx = s(d.data, "trxId");
        r.name = s(d.data, "name");
        r.mobile = s(d.data, "mobile");
        r.courseId = s(d.data, "courseId");
        r.courseTitle = s(d.data, "courseTitle");
        r.status = s(d.data, "status").isEmpty() ? PENDING : s(d.data, "status");
        r.amount = Store.parsePrice(d.data.get("amount"));
        r.at = l(d.data, "at");
        r.decidedAt = l(d.data, "decidedAt");
        return r;
    }

    // ── User side ───────────────────────────────────────────
    /** Blocking: call from a background thread. Throws an Exception whose message is shown to the user. */
    static void submit(Context ctx, String name, String mobile, Store.Course c, String trx) throws Exception {
        final String id = PREFIX + trx;
        Fs.Doc ex = Fs.get(id);
        if (ex != null) {
            if (PENDING.equals(s(ex.data, "status")) && mobile.equals(s(ex.data, "mobile")))
                throw new Exception("This TrxID is already submitted and waiting for the admin's approval.");
            throw new Exception("This TrxID has already been used. Please check it and try again.");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("trxId", trx);
        d.put("name", name);
        d.put("mobile", mobile);
        d.put("courseId", c.id);
        d.put("courseTitle", c.title);
        d.put("amount", c.price);
        d.put("method", "bkash");
        d.put("status", PENDING);
        d.put("at", System.currentTimeMillis());
        Fs.set(id, d);

        // so the approval push can reach this phone even if the buyer never logged in
        try { PushManager.setUser(ctx, mobile); } catch (Throwable ignored) { }

        new AdminBridge(ctx, null).notifyAdmins("💳 New payment request",
            name + " · " + mobile + "\n" + c.title + " · " + Store.priceLabel(c.price)
                + "\nTrxID: " + trx + "\nMatch it with the bKash SMS, then Accept in Notification Center.");
    }

    // ── Admin side ──────────────────────────────────────────
    /** Newest first. */
    static List<Req> latest(int limit) throws Exception {
        List<Req> out = new ArrayList<>();
        for (Fs.Doc d : Fs.latestChat(PREFIX, limit)) out.add(parse(d));
        Collections.sort(out, (x, y) -> Long.compare(y.at, x.at));
        return out;
    }

    /** Blocking. Unlocks the course, marks the request accepted and pushes to the user. Returns a message. */
    static String accept(Context ctx, Req r) throws Exception {
        Fs.Doc cur = Fs.get(r.id);
        if (cur == null) throw new Exception("This request no longer exists.");
        String st = s(cur.data, "status");
        if (!st.isEmpty() && !PENDING.equals(st)) throw new Exception("Already " + st + ".");

        grantCourse(r);

        Map<String, Object> u = new LinkedHashMap<>();
        u.put("status", ACCEPTED);
        u.put("decidedAt", System.currentTimeMillis());
        Fs.update(r.id, u);

        String push = pushUser(ctx, r.mobile, "✅ Payment confirmed",
            "Your payment (TrxID " + r.trx + ") is confirmed. \"" + r.courseTitle
                + "\" is now unlocked — open the app and start learning.");
        return "✅ Accepted — course unlocked. " + shortPush(push);
    }

    static String reject(Context ctx, Req r) throws Exception {
        Fs.Doc cur = Fs.get(r.id);
        if (cur == null) throw new Exception("This request no longer exists.");
        String st = s(cur.data, "status");
        if (!st.isEmpty() && !PENDING.equals(st)) throw new Exception("Already " + st + ".");

        Map<String, Object> u = new LinkedHashMap<>();
        u.put("status", REJECTED);
        u.put("decidedAt", System.currentTimeMillis());
        Fs.update(r.id, u);

        String push = pushUser(ctx, r.mobile, "❌ Payment could not be verified",
            "We could not match TrxID " + r.trx + " with a payment. Please check it, or contact the admin on WhatsApp "
                + Store.WHATSAPP_NUM + ".");
        return "Rejected. " + shortPush(push);
    }

    private static String shortPush(String push) {
        if (push == null) return "";
        return push.startsWith("✅") ? "User notified." : "(User push: " + push.replace("❌ ", "") + ")";
    }

    /** Opens the course for this mobile: creates the member if needed, otherwise adds the course. */
    @SuppressWarnings("unchecked")
    private static void grantCourse(Req r) throws Exception {
        final String mid = "_member_" + r.mobile;
        Fs.Doc m = Fs.get(mid);
        if (m == null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("isMember", true);
            d.put("name", r.name);
            d.put("mobile", r.mobile);
            d.put("expiry", "");
            d.put("allCourses", false);
            List<Object> ids = new ArrayList<>();
            ids.add(r.courseId);
            d.put("courseIds", ids);
            d.put("created", System.currentTimeMillis());
            d.put("paidVia", "manual");
            d.put("lastTrx", r.trx);
            Fs.set(mid, d);
            return;
        }
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("isMember", true);
        u.put("paidVia", "manual");
        u.put("lastTrx", r.trx);
        Object ac = m.data.get("allCourses");
        Object ci = m.data.get("courseIds");
        boolean all = Boolean.TRUE.equals(ac) || (ac == null && !(ci instanceof List));
        if (!all) {
            List<Object> ids = new ArrayList<>();
            if (ci instanceof List) ids.addAll((List<Object>) ci);
            if (!ids.contains(r.courseId)) ids.add(r.courseId);
            u.put("courseIds", ids);
            u.put("allCourses", false);
        }
        String exp = s(m.data, "expiry");
        if (!exp.isEmpty() && Store.todayStr().compareTo(exp) > 0) u.put("expiry", "");   // expired earlier: reopen
        Fs.update(mid, u);
    }

    /** Blocking push to one member's phone(s). Returns AdminBridge's message ("✅ …" on success). */
    private static String pushUser(Context ctx, String mobile, String title, String body) {
        final String[] out = {null};
        final CountDownLatch done = new CountDownLatch(1);
        new AdminBridge(ctx, null).send(title, body, "user", mobile, msg -> { out[0] = msg; done.countDown(); });
        try { done.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out[0] == null ? "❌ push timed out" : out[0];
    }
}
