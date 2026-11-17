package com.rajibtutorial;

import android.content.Intent;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** All modal dialogs of the native section (ported from the web modals). */
final class Dialogs {
    private final NativeHomeActivity a;
    private String pendingDeviceMobile = null;

    Dialogs(NativeHomeActivity host) { a = host; }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private void run(Callable<Boolean> job, final Runnable onOk) {
        Store.async(job, (Boolean r, Exception e) -> {
            if (e != null) a.toast("Error: " + e.getMessage());
            else onOk.run();
        });
    }

    private void openUrl(String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            a.toast("Could not open the link");
        }
    }

    private TextView link(String text, final String url) {
        TextView t = Ui.tv(a, text, 12.5f, Ui.ACCENT2, true);
        t.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
        t.setOnClickListener(v -> openUrl(url));
        return t;
    }

    // ── Notice / confirm ────────────────────────────────────
    void notice(String title, String msg) {
        final Ui.Modal m = new Ui.Modal(a, title, msg);
        m.col("Got it", Ui.B_PRIMARY, v -> m.dismiss());
        m.show();
    }

    void confirm(String title, String msg, final Runnable onYes) {
        final Ui.Modal m = new Ui.Modal(a, title, msg);
        m.actions("Cancel", "Delete", Ui.B_RED, () -> { m.dismiss(); onYes.run(); });
        m.show();
    }

    // ── Admin login ─────────────────────────────────────────
    void adminLogin() {
        if (Store.adminIn) { a.openManage(); return; }
        final Ui.Modal m = new Ui.Modal(a, "Admin Login", "Enter the admin password to manage courses and live sessions.");
        final Ui.Field pw = Ui.field(a, "Password", "Enter password", Ui.IN_PASS);
        final TextView err = m.errorView();
        m.add(pw.root).add(err);
        m.actions("Cancel", "Login", Ui.B_PRIMARY, () -> {
            if (pw.et.getText().toString().equals(Store.ADMIN_PW)) {
                Store.adminIn = true;
                PushManager.setAdmin(a, true);
                m.dismiss();
                a.onAdminChanged();
                a.openManage();
                a.toast("Welcome to the admin panel");
            } else {
                Ui.setErr(err, "Incorrect password. Please try again.");
                pw.et.setText("");
            }
        });
        m.show();
    }

    // ── Premium gate / pay / member login ───────────────────
    void premiumGate(String reason) { premiumGate(reason, null); }

    /** {@code course} (optional): when it has a price, a "Buy this course" button is offered. */
    void premiumGate(String reason, final Store.Course course) {
        final boolean na = "noaccess".equals(reason);
        final Ui.Modal m = new Ui.Modal(a,
            na ? "Course not included" : "Premium required",
            na ? "Your Premium access does not include this course. Upgrade to unlock all courses, or contact the admin."
               : "Video lessons are available to Premium members only. Become Premium, or log in if your number already has access.");
        String info = Store.live.premiumInfo == null ? "" : Store.live.premiumInfo.trim();
        if (!info.isEmpty()) {
            TextView t = Ui.tv(a, info, 13, Ui.TEXT, false);
            t.setLineSpacing(0, 1.2f);
            t.setBackground(Ui.bg(a, Ui.BG, 12, Ui.BORDER));
            t.setPadding(Ui.dp(a, 13), Ui.dp(a, 11), Ui.dp(a, 13), Ui.dp(a, 11));
            m.add(t);
        }
        if (course != null && course.price > 0) {
            m.col("Buy this course · " + Store.priceLabel(course.price), Ui.B_PRIMARY, v -> { m.dismiss(); payCourse(course); });
            m.col("See all courses & prices", Ui.B_OUTLINE, v -> { m.dismiss(); pay(); });
        } else {
            m.col("See courses & prices", Ui.B_PRIMARY, v -> { m.dismiss(); pay(); });
        }
        if (!na) m.col("Already a Premium member? Log in", Ui.B_OUTLINE, v -> { m.dismiss(); memberLogin(); });
        m.col("Maybe later", Ui.B_GHOST, v -> m.dismiss());
        m.show();
    }

    void contactAdmin() {
        final Ui.Modal m = new Ui.Modal(a, "Contact Admin", "To get Premium access, contact the admin on WhatsApp:");
        m.col("WhatsApp: " + Store.WHATSAPP_NUM, Ui.B_GREEN, v -> openUrl(Store.WHATSAPP));
        m.col("Close", Ui.B_GHOST, v -> m.dismiss());
        m.show();
    }

    /** Buy one course with bKash — the price is the one the admin set on the course. */
    void payCourse(final Store.Course c) {
        final Ui.Modal m = new Ui.Modal(a, "Buy this course",
            "Pay securely with bKash. The course is unlocked automatically right after payment.");
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.bg(a, Ui.BG, 12, Ui.BORDER));
        box.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(Ui.ellipsized(a, c.title, 14, Ui.TEXT, true, 2));
        if (!c.courseName.isEmpty()) left.addView(Ui.tv(a, c.courseName, 11.5f, Ui.SUB, false));
        box.addView(left, new LinearLayout.LayoutParams(0, WRAP, 1f));
        box.addView(Ui.tv(a, Store.priceLabel(c.price), 18, Ui.ACCENT, true));
        m.add(box);

        final Ui.Field name = Ui.field(a, "Your Name *", "Full name", Ui.IN_TEXT);
        final Ui.Field mobile = Ui.field(a, "Mobile Number *", "01XXXXXXXXX", Ui.IN_PHONE);
        final TextView err = m.errorView();
        m.addSpaced(name.root, 10).add(mobile.root).add(err);

        final TextView payBtn = Ui.btn(a, "Pay " + Store.priceLabel(c.price) + " with bKash", Ui.B_PRIMARY, null);
        payBtn.setBackground(Ui.bg(a, 0xFFE2136E, 11, 0));
        payBtn.setOnClickListener(v -> {
            String n = name.text();
            String mob = Store.normMobile(mobile.text());
            Ui.setErr(err, "");
            if (n.length() < 2) { Ui.setErr(err, "Please enter your name."); return; }
            if (!mob.matches("^01\\d{9}$")) { Ui.setErr(err, "Enter a valid 11-digit mobile number (01XXXXXXXXX)."); return; }
            payBtn.setEnabled(false);
            payBtn.setAlpha(0.65f);
            payBtn.setText("Connecting to bKash… (may take up to 30 seconds)");
            final String fn = n, fm = mob;
            Store.async(() -> Store.createPayment(fn, fm, "course", c.id), (String url, Exception e) -> {
                payBtn.setEnabled(true);
                payBtn.setAlpha(1f);
                payBtn.setText("Pay " + Store.priceLabel(c.price) + " with bKash");
                if (e == null && url != null) {
                    m.dismiss();
                    PaymentActivity.start(a, url);
                } else if (e instanceof java.io.IOException) {
                    Ui.setErr(err, "Server is waking up or unreachable. Please wait 30 seconds and try again.");
                } else {
                    Ui.setErr(err, e != null && e.getMessage() != null ? e.getMessage() : "Could not start the payment. Please try again.");
                }
            });
        });
        m.addSpaced(payBtn, 6);

        final TextView cardBtn = Ui.btn(a, "Pay " + Store.priceLabel(c.price) + " by Card / Nagad / Rocket", Ui.B_OUTLINE, null);
        cardBtn.setOnClickListener(v -> {
            String n = name.text();
            String mob = Store.normMobile(mobile.text());
            Ui.setErr(err, "");
            if (n.length() < 2) { Ui.setErr(err, "Please enter your name."); return; }
            if (!mob.matches("^01\\d{9}$")) { Ui.setErr(err, "Enter a valid 11-digit mobile number (01XXXXXXXXX)."); return; }
            cardBtn.setEnabled(false);
            cardBtn.setAlpha(0.65f);
            cardBtn.setText("Connecting… (may take up to 30 seconds)");
            final String fn = n, fm = mob;
            Store.async(() -> Store.createCardPayment(fn, fm, c.id), (String url, Exception e) -> {
                cardBtn.setEnabled(true);
                cardBtn.setAlpha(1f);
                cardBtn.setText("Pay " + Store.priceLabel(c.price) + " by Card / Nagad / Rocket");
                if (e == null && url != null) {
                    m.dismiss();
                    PaymentActivity.start(a, url);
                } else if (e instanceof java.io.IOException) {
                    Ui.setErr(err, "Server is waking up or unreachable. Please wait 30 seconds and try again.");
                } else {
                    Ui.setErr(err, e != null && e.getMessage() != null ? e.getMessage() : "Could not start the payment. Please try again.");
                }
            });
        });
        m.addSpaced(cardBtn, 8);

        // ── Manual payment: pay by bKash yourself, then send the TrxID for the admin to approve ──
        View sep = new View(a);
        sep.setBackgroundColor(Ui.BORDER);
        m.addSpaced(sep, 14);
        m.addSpaced(Ui.tv(a, "Already paid with bKash? Enter the TrxID", 13.5f, Ui.TEXT, true), 10);
        String how = Store.live.premiumInfo == null ? "" : Store.live.premiumInfo.trim();
        if (how.isEmpty()) how = "Send " + Store.priceLabel(c.price) + " by bKash to the number the admin gave you "
            + "(ask on WhatsApp " + Store.WHATSAPP_NUM + "), then type the TrxID from the bKash SMS below.";
        m.addSpaced(Ui.tv(a, how, 12, Ui.SUB, false), 4);
        final Ui.Field trx = Ui.field(a, "bKash TrxID *", "e.g. 8N7A6D5EE1", Ui.IN_TEXT);
        m.addSpaced(trx.root, 10);
        final TextView trxBtn = Ui.btn(a, "Submit TrxID for approval", Ui.B_OUTLINE, null);
        trxBtn.setOnClickListener(v -> {
            String n = name.text();
            String mob = Store.normMobile(mobile.text());
            String t = PayRequests.cleanTrx(trx.text());
            Ui.setErr(err, "");
            if (n.length() < 2) { Ui.setErr(err, "Please enter your name."); return; }
            if (!mob.matches("^01\\d{9}$")) { Ui.setErr(err, "Enter a valid 11-digit mobile number (01XXXXXXXXX)."); return; }
            if (!PayRequests.validTrx(t)) { Ui.setErr(err, "Enter the TrxID exactly as shown in the bKash SMS."); return; }
            trxBtn.setEnabled(false);
            trxBtn.setAlpha(0.65f);
            trxBtn.setText("Submitting…");
            final String fn = n, fm = mob, ft = t;
            Store.async(() -> { PayRequests.submit(a, fn, fm, c, ft); return Boolean.TRUE; }, (Boolean ok, Exception e) -> {
                trxBtn.setEnabled(true);
                trxBtn.setAlpha(1f);
                trxBtn.setText("Submit TrxID for approval");
                if (e == null) {
                    m.dismiss();
                    a.toast("✅ TrxID sent. You will get a notification as soon as the admin approves it.");
                } else if (e instanceof java.io.IOException) {
                    Ui.setErr(err, "Network problem. Please check your connection and try again.");
                } else {
                    Ui.setErr(err, e.getMessage() != null ? e.getMessage() : "Could not submit the TrxID. Please try again.");
                }
            });
        });
        m.addSpaced(trxBtn, 8);
        m.col("Cancel", Ui.B_GHOST, v -> m.dismiss());
        m.show();
    }

    /** Courses & prices: every course the admin has priced, tap one to buy it with bKash. */
    void pay() {
        final Ui.Modal m = new Ui.Modal(a, "Courses & prices",
            "Choose a course and pay securely with bKash. The course is unlocked automatically right after payment.");
        int shown = 0;
        for (final Store.Course c : Store.courses) {
            if (c.price <= 0 || Store.courseAllowed(c)) continue;
            shown++;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.bg(a, Ui.BG, 14, Ui.BORDER));
            row.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
            LinearLayout left = new LinearLayout(a);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(Ui.ellipsized(a, c.title, 14, Ui.TEXT, true, 2));
            String sub = Store.lessonsLabel(c.videos.size());
            if (!c.className.isEmpty()) sub = c.className + "  ·  " + sub;
            left.addView(Ui.tv(a, sub, 11.5f, Ui.SUB, false));
            row.addView(left, new LinearLayout.LayoutParams(0, WRAP, 1f));
            row.addView(Ui.tv(a, Store.priceLabel(c.price), 16, Ui.ACCENT, true), Ui.lpm(a, WRAP, WRAP, 10, 0, 0, 0));
            Ui.pressFx(row);
            row.setOnClickListener(v -> { m.dismiss(); payCourse(c); });
            m.box.addView(row, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
        if (shown == 0) {
            TextView none = Ui.tv(a, Store.isPremium()
                ? "You already have access to every priced course."
                : "Course prices have not been set yet. Please contact the admin.", 13.5f, Ui.SUB, false);
            none.setGravity(Gravity.CENTER);
            m.addSpaced(none, 12);
        }
        m.col("Close", Ui.B_GHOST, v -> m.dismiss());

        TextView note = Ui.tv(a, "Payment is processed securely by bKash. Already have access? ", 12, Ui.SUB, false);
        note.setGravity(Gravity.CENTER);
        m.addSpaced(note, 12);
        LinearLayout links = new LinearLayout(a);
        links.setGravity(Gravity.CENTER);
        TextView login = Ui.tv(a, "Login", 12.5f, Ui.ACCENT2, true);
        login.setOnClickListener(v -> { m.dismiss(); memberLogin(); });
        links.addView(login);
        String help = Store.safeUrl(Store.live.premiumUrl);
        if (!help.isEmpty()) {
            links.addView(Ui.tv(a, "  ·  ", 12, Ui.SUB, false));
            links.addView(link("Need help?", help));
        }
        m.add(links);
        LinearLayout wa = new LinearLayout(a);
        wa.setGravity(Gravity.CENTER);
        wa.addView(Ui.tv(a, "Contact: ", 12, Ui.SUB, false));
        wa.addView(link("WhatsApp " + Store.WHATSAPP_NUM, Store.WHATSAPP));
        m.addSpaced(wa, 8);
        m.show();
    }

    void memberLogin() {
        final Ui.Modal m = new Ui.Modal(a, "Premium Login", "Enter the mobile number that has Premium access to log in.");
        final Ui.Field mob = Ui.field(a, "Mobile Number", "01XXXXXXXXX", Ui.IN_PHONE);
        final TextView err = m.errorView();
        m.add(mob.root).add(err);
        m.actions("Cancel", "Login", Ui.B_PRIMARY, () -> {
            final String mobile = Store.normMobile(mob.text());
            if (!mobile.matches("^01\\d{9}$")) {
                Ui.setErr(err, "Enter a valid 11-digit mobile number (01XXXXXXXXX).");
                return;
            }
            final TextView ok = m.lastOk;
            ok.setEnabled(false);
            ok.setText("Verifying…");
            Store.memberAuth(mobile, false, (Store.AuthResult r, Exception e) -> {
                ok.setEnabled(true);
                ok.setText("Login");
                if (r == null) return;
                if (r.noAccess) { m.dismiss(); contactAdmin(); return; }
                if (r.deviceConflict) {
                    pendingDeviceMobile = mobile;
                    m.dismiss();
                    deviceConflict(r.otherDevice);
                    return;
                }
                if (r.err != null) { Ui.setErr(err, r.err); return; }
                m.dismiss();
                a.onSessionChanged();
                a.toast("Welcome, " + r.name);
                a.resumePending();
            });
        });
        m.show();
    }

    private void deviceConflict(String other) {
        final Ui.Modal m = new Ui.Modal(a, "Already logged in on another device",
            "This number is currently active on " + other + ".\nDo you want to log out of that device and continue on this one?");
        m.col("Yes, log in on this device", Ui.B_PRIMARY, v -> {
            if (pendingDeviceMobile == null) return;
            final String mobile = pendingDeviceMobile;
            m.dismiss();
            Store.memberAuth(mobile, true, (Store.AuthResult r, Exception e) -> {
                if (r == null) return;
                if (r.err != null) { a.toast(r.err); return; }
                if (r.noAccess) { contactAdmin(); return; }
                pendingDeviceMobile = null;
                a.onSessionChanged();
                a.toast("Welcome, " + r.name);
                a.resumePending();
            });
        });
        m.col("No, cancel", Ui.B_GHOST, v -> m.dismiss());
        m.show();
    }

    // ── Course / video CRUD (admin) ─────────────────────────
    private List<String> semItems(String extra) {
        List<String> set = new ArrayList<>();
        for (String s : Store.SEMESTERS) set.add(s);
        for (Store.Course c : Store.courses) if (!c.className.isEmpty() && !set.contains(c.className)) set.add(c.className);
        if (extra != null && !extra.isEmpty() && !set.contains(extra)) set.add(extra);
        return set;
    }

    void course(final String editId) {
        final Store.Course ec = editId == null ? null : Store.findCourse(editId);
        final Ui.Modal m = new Ui.Modal(a, ec == null ? "New Course" : "Edit Course", "Enter the course details below.");
        final Ui.Field name = Ui.field(a, "Course Name *", "e.g. EEE Foundation Course", Ui.IN_TEXT);
        final Ui.Field title = Ui.field(a, "Title *", "e.g. Basic Electricity & Circuit Analysis", Ui.IN_TEXT);
        final Ui.Field desc = Ui.field(a, "Description", "Short description of the course…", Ui.IN_MULTI);
        final List<String> sems = new ArrayList<>();
        sems.add("Select semester");
        sems.addAll(semItems(ec == null ? "" : ec.className));
        final Spinner semSp = Ui.spinner(a, sems, ec == null || ec.className.isEmpty() ? "Select semester" : ec.className);
        final List<String> subs = new ArrayList<>();
        subs.add("Select subject");
        for (String s : Store.SUBJECTS) subs.add(s);
        if (ec != null && !ec.subject.isEmpty() && !subs.contains(ec.subject)) subs.add(ec.subject);
        final Spinner subSp = Ui.spinner(a, subs, ec == null || ec.subject.isEmpty() ? "Select subject" : ec.subject);
        final Ui.Field price = Ui.field(a, "Price (৳) — shown to students when buying", "e.g. 500 (leave empty = no price)", Ui.IN_PHONE);
        if (ec != null) { name.et.setText(ec.courseName); title.et.setText(ec.title); desc.et.setText(ec.description); if (ec.price > 0) price.et.setText(String.valueOf(ec.price)); }
        m.add(name.root).add(title.root).add(Ui.labeled(a, "Semester", semSp)).add(Ui.labeled(a, "Subject", subSp)).add(price.root).add(desc.root);
        m.actions("Cancel", "Save", Ui.B_PRIMARY, () -> {
            final String n = name.text(), t = title.text();
            if (n.isEmpty() || t.isEmpty()) { a.toast("Please enter Course Name and Title"); return; }
            String cls = semSp.getSelectedItemPosition() == 0 ? "" : String.valueOf(semSp.getSelectedItem());
            String sub = subSp.getSelectedItemPosition() == 0 ? "" : String.valueOf(subSp.getSelectedItem());
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("courseName", n);
            data.put("title", t);
            data.put("className", cls);
            data.put("subject", sub);
            data.put("description", desc.text());
            data.put("price", Long.valueOf(Store.parsePrice(price.text())));
            if (ec == null) {
                for (Store.Course c : Store.courses) {
                    if (c.title.trim().equalsIgnoreCase(t) && c.courseName.trim().equalsIgnoreCase(n)) {
                        a.toast("A course with this name already exists");
                        return;
                    }
                }
            }
            final boolean isEdit = ec != null;
            run(() -> {
                if (isEdit) {
                    Fs.update(editId, data);
                } else {
                    data.put("videos", new ArrayList<Object>());
                    Fs.set("course_" + Long.toString(System.currentTimeMillis(), 36), data);
                }
                return Boolean.TRUE;
            }, () -> {
                a.toast(isEdit ? "Course updated" : "Course created");
                m.dismiss();
                a.reload();
            });
        });
        m.show();
    }

    static String resTypeName(String type) {
        return Store.R_CIRCULAR.equals(type) ? "Circular" : Store.R_RECENT.equals(type) ? "Recent Question Bank" : "Question Bank";
    }

    /** Admin: add / edit a circular or a question-bank link. */
    void resource(final String type, final String editId, String presetCat) {
        final Store.Resource er = editId == null ? null : Store.findResource(editId);
        final boolean hasCat = Store.R_QBANK.equals(type);
        final Ui.Modal m = new Ui.Modal(a, (er == null ? "Add " : "Edit ") + resTypeName(type),
            "Upload a PDF or image from your phone, or paste a link (Google Drive / web page).");
        final Ui.Field title = Ui.field(a, "Title *", Store.R_CIRCULAR.equals(type) ? "e.g. Bangladesh Railway Sub-Assistant Engineer circular" : "e.g. BUET Diploma Admission Test 2025", Ui.IN_TEXT);
        final Ui.Field url = Ui.field(a, "File link (filled automatically after upload) *", "https://…", Ui.IN_PLAIN);
        final Ui.Field note = Ui.field(a, "Note (optional)", "e.g. Last date 30 Nov · 25 posts", Ui.IN_TEXT);
        final List<String> cats = new ArrayList<>();
        for (String c : Store.QB_CATS) cats.add(c);
        String sel = er != null && !er.category.isEmpty() ? er.category : (presetCat != null && !presetCat.isEmpty() ? presetCat : Store.QB_CATS[0]);
        if (!cats.contains(sel)) cats.add(sel);
        final Spinner catSp = Ui.spinner(a, cats, sel);
        if (er != null) { title.et.setText(er.title); url.et.setText(er.url); note.et.setText(er.note); }
        m.add(title.root);
        if (hasCat) m.add(Ui.labeled(a, "Category", catSp));

        // upload a PDF / image from the phone — or paste a link instead
        final boolean[] uploading = {false};
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        final TextView upBtn = Ui.smallBtn(a, "📎  Upload PDF / image from phone", Ui.ACCENT, null);
        upBtn.setOnClickListener(v -> {
            if (uploading[0]) return;
            a.pickFile("*/*", uri -> {
                uploading[0] = true;
                upBtn.setText("Uploading… 0%");
                final android.content.Context app = a.getApplicationContext();
                Store.async(() -> {
                    final Cloud.Meta meta = Cloud.meta(app, uri);
                    if (meta.size > Cloud.MAX_BYTES) throw new Exception("File is too large (max 100 MB)");
                    return new Object[]{meta, Cloud.upload(app, uri, meta, pct -> ui.post(() -> upBtn.setText("Uploading… " + pct + "%")))};
                }, (Object[] out, Exception e) -> {
                    uploading[0] = false;
                    if (e != null || out == null) {
                        upBtn.setText("📎  Upload PDF / image from phone");
                        a.toast("Upload failed: " + (e != null && e.getMessage() != null ? e.getMessage() : "try again"));
                        return;
                    }
                    Cloud.Meta meta = (Cloud.Meta) out[0];
                    Cloud.Result r = (Cloud.Result) out[1];
                    url.et.setText(r.url);
                    if (title.text().isEmpty()) title.et.setText(meta.name.replaceAll("\\.[A-Za-z0-9]{1,5}$", ""));
                    upBtn.setText("✓ Uploaded: " + meta.name);
                });
            });
        });
        m.add(upBtn);
        m.add(url.root).add(note.root);
        m.actions("Cancel", "Save", Ui.B_PRIMARY, () -> {
            if (uploading[0]) { a.toast("Please wait — the file is still uploading"); return; }
            final String t = title.text();
            final String u = Store.safeUrl(url.text());
            if (t.isEmpty()) { a.toast("Please enter a title"); return; }
            if (u.isEmpty()) { a.toast("Link must start with http:// or https://"); return; }
            final Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", type);
            d.put("title", t);
            d.put("url", u);
            d.put("note", note.text());
            d.put("category", hasCat ? String.valueOf(catSp.getSelectedItem()) : "");
            final boolean isEdit = er != null;
            run(() -> {
                if (isEdit) {
                    Fs.update(editId, d);
                } else {
                    d.put("at", Long.valueOf(System.currentTimeMillis()));
                    Fs.set("_res_" + Long.toString(System.currentTimeMillis(), 36), d);
                }
                return Boolean.TRUE;
            }, () -> {
                a.toast(isEdit ? "Updated" : "Added");
                m.dismiss();
                a.reload();
            });
        });
        m.show();
    }

    void confirmDelResource(final String id) {
        Store.Resource r = Store.findResource(id);
        confirm("Delete this item?", "“" + (r == null ? id : r.title) + "” will be removed for everyone.", () ->
            run(() -> { Fs.delete(id); return Boolean.TRUE; }, () -> { a.toast("Deleted"); a.reload(); }));
    }

    void confirmDelCourse(final String cid) {
        Store.Course c = Store.findCourse(cid);
        confirm("Delete course?", "“" + (c == null ? cid : c.title) + "” and all its videos will be permanently deleted.", () ->
            run(() -> { Fs.delete(cid); return Boolean.TRUE; }, () -> { a.toast("Deleted"); a.reload(); }));
    }

    void video(final String cid, final String editVid) {
        final Store.Course course = Store.findCourse(cid);
        if (course == null) return;
        Store.Video ev = null;
        if (editVid != null) for (Store.Video v : course.videos) if (v.id.equals(editVid)) ev = v;
        final Store.Video editing = ev;
        final Ui.Modal m = new Ui.Modal(a, editing == null ? "Add Video" : "Edit Video", "Paste a YouTube link for this lesson.");
        final Ui.Field title = Ui.field(a, "Video Title *", "e.g. Lesson 1: Ohm's Law", Ui.IN_TEXT);
        final Ui.Field url = Ui.field(a, "YouTube Link *", "https://youtube.com/watch?v=… or youtu.be/…", Ui.IN_URL);
        final Ui.Field dur = Ui.field(a, "Duration (minutes, optional)", "e.g. 18", Ui.IN_NUM);
        if (editing != null) {
            title.et.setText(editing.title);
            url.et.setText(editing.url);
            if (editing.durationMinutes > 0) dur.et.setText(String.valueOf(editing.durationMinutes));
        }
        m.add(title.root).add(url.root).add(dur.root);
        m.actions("Cancel", "Save", Ui.B_PRIMARY, () -> {
            final String t = title.text(), u = url.text();
            if (t.isEmpty() || u.isEmpty()) { a.toast("Please enter a title and a video link"); return; }
            int d = 0;
            try { d = Integer.parseInt(dur.text()); } catch (Exception ignored) { }
            final int fd = d;
            final List<Store.Video> vids = new ArrayList<>(course.videos);
            if (editing != null) {
                for (int i = 0; i < vids.size(); i++) {
                    if (vids.get(i).id.equals(editing.id)) {
                        Store.Video nv = new Store.Video();
                        nv.id = editing.id; nv.title = t; nv.url = u; nv.durationMinutes = fd;
                        vids.set(i, nv);
                    }
                }
            } else {
                Store.Video nv = new Store.Video();
                nv.id = "vid_" + Long.toString(System.currentTimeMillis(), 36);
                nv.title = t; nv.url = u; nv.durationMinutes = fd;
                vids.add(nv);
            }
            final boolean isEdit = editing != null;
            run(() -> {
                Map<String, Object> upd = new LinkedHashMap<>();
                upd.put("videos", Store.videoMaps(vids));
                Fs.update(cid, upd);
                return Boolean.TRUE;
            }, () -> { a.toast(isEdit ? "Video updated" : "Video added"); m.dismiss(); a.reload(); });
        });
        m.show();
    }

    void confirmDelVideo(final String cid, final String vid) {
        final Store.Course c = Store.findCourse(cid);
        if (c == null) return;
        String vt = vid;
        for (Store.Video v : c.videos) if (v.id.equals(vid)) vt = v.title;
        confirm("Delete video?", "“" + vt + "” will be permanently deleted.", () -> {
            final List<Store.Video> vids = new ArrayList<>();
            for (Store.Video v : c.videos) if (!v.id.equals(vid)) vids.add(v);
            run(() -> {
                Map<String, Object> upd = new LinkedHashMap<>();
                upd.put("videos", Store.videoMaps(vids));
                Fs.update(cid, upd);
                return Boolean.TRUE;
            }, () -> { a.toast("Deleted"); a.reload(); });
        });
    }

    // ── Live & premium settings (admin) ─────────────────────
    void liveSettings() {
        final Store.Live l = Store.live;
        final Ui.Modal m = new Ui.Modal(a, "Live & Premium Settings",
            "Set the links shown on the home page buttons. Leave a link empty when nothing is scheduled.");
        final Ui.Field lcT = Ui.field(a, "Live Class · Title", "e.g. Circuit Analysis: Thevenin's Theorem", Ui.IN_TEXT);
        final Ui.Field lcS = Ui.field(a, "Live Class · Schedule", "e.g. Every Friday, 8:00 PM", Ui.IN_TEXT);
        final Ui.Field lcU = Ui.field(a, "Live Class · Link", "https://meet.google.com/… or YouTube live link", Ui.IN_URL);
        final Ui.Field leT = Ui.field(a, "Live Exam · Title", "e.g. Weekly Model Test 05", Ui.IN_TEXT);
        final Ui.Field leS = Ui.field(a, "Live Exam · Schedule", "e.g. Saturday, 9:00 PM", Ui.IN_TEXT);
        final Ui.Field leU = Ui.field(a, "Live Exam · Link", "https://…", Ui.IN_URL);
        final Ui.Field pmU = Ui.field(a, "Support / Help Link (optional)", "WhatsApp or Facebook link shown as “Need help?”", Ui.IN_URL);
        final Ui.Field pmI = Ui.field(a, "Premium Info (shown in the Premium popup, optional)", "e.g. Unlock every lesson, live class and exam with Premium.", Ui.IN_MULTI);
        lcT.et.setText(l.classTitle); lcS.et.setText(l.classTime); lcU.et.setText(l.classUrl);
        leT.et.setText(l.examTitle); leS.et.setText(l.examTime); leU.et.setText(l.examUrl);
        pmU.et.setText(l.premiumUrl); pmI.et.setText(l.premiumInfo);
        m.add(lcT.root).add(lcS.root).add(lcU.root).add(leT.root).add(leS.root).add(leU.root).add(pmU.root).add(pmI.root);
        m.actions("Cancel", "Save", Ui.B_PRIMARY, () -> {
            String cu = lcU.text(), eu = leU.text(), pu = pmU.text();
            if ((!cu.isEmpty() && Store.safeUrl(cu).isEmpty()) || (!eu.isEmpty() && Store.safeUrl(eu).isEmpty())
                || (!pu.isEmpty() && Store.safeUrl(pu).isEmpty())) {
                a.toast("Links must start with http:// or https://");
                return;
            }
            final Store.Live next = new Store.Live();
            next.classTitle = lcT.text(); next.classTime = lcS.text(); next.classUrl = cu;
            next.examTitle = leT.text(); next.examTime = leS.text(); next.examUrl = eu;
            next.premiumUrl = pu; next.premiumInfo = pmI.text();
            run(() -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("live", Store.liveMap(next));
                d.put("isSettings", Boolean.TRUE);
                Fs.set(Store.LIVE_DOC, d);
                return Boolean.TRUE;
            }, () -> { Store.live = next; a.refreshAll(); m.dismiss(); a.toast("Live settings saved"); });
        });
        m.show();
    }

    // ── Members (admin) ─────────────────────────────────────
    private Store.Member findMember(String id) {
        for (Store.Member m : Store.members) if (m.id.equals(id)) return m;
        return null;
    }

    void member(final String editId) {
        final Store.Member em = editId == null ? null : findMember(editId);
        if (editId != null && em == null) return;
        final Ui.Modal m = new Ui.Modal(a, em == null ? "Add Premium Member" : "Edit Premium Member",
            "This mobile number will be able to log in. Choose which courses the member can watch.");
        final Ui.Field name = Ui.field(a, "Name *", "Student name", Ui.IN_TEXT);
        final Ui.Field mob = Ui.field(a, "Mobile Number *", "01XXXXXXXXX", Ui.IN_PHONE);
        final Ui.Field exp = Ui.field(a, "Valid Until (optional, YYYY-MM-DD)", "e.g. 2026-12-31", Ui.IN_PLAIN);
        final Ui.Field batch = Ui.field(a, "Batch (optional)", "e.g. Batch 1", Ui.IN_TEXT);
        if (em != null) {
            name.et.setText(em.name);
            mob.et.setText(em.mobile);
            mob.et.setEnabled(false);
            exp.et.setText(em.expiry);
            batch.et.setText(em.batch);
        }
        m.add(name.root).add(mob.root).add(batch.root);
        if (!Store.batchNames().isEmpty()) {
            m.add(Ui.smallBtn(a, "📋 Choose existing batch", Ui.ACCENT, v -> {
                final Ui.Modal pm = new Ui.Modal(a, "Select a batch", null);
                for (final String bn : Store.batchNames()) {
                    pm.col(bn + " · " + Store.batchCount(bn) + " members", Ui.B_GHOST, x -> { batch.et.setText(bn); pm.dismiss(); });
                }
                pm.col("Close", Ui.B_OUTLINE, x -> pm.dismiss());
                pm.show();
            }));
        }
        m.add(exp.root);

        final boolean allInit = em == null || Store.hasAll(em);
        final CheckBox all = new CheckBox(a);
        all.setText("All courses (including new ones)");
        all.setChecked(allInit);
        all.setTextColor(Ui.TEXT);
        final List<CheckBox> boxes = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        LinearLayout ca = new LinearLayout(a);
        ca.setOrientation(LinearLayout.VERTICAL);
        ca.setBackground(Ui.bg(a, Ui.BG, 12, Ui.BORDER));
        ca.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4));
        ca.addView(all);
        for (Store.Course c : Store.courses) {
            CheckBox cb = new CheckBox(a);
            cb.setText(c.title);
            cb.setTextColor(Ui.TEXT);
            boolean on = !allInit && em != null && em.courseIds != null && em.courseIds.contains(c.id);
            cb.setChecked(on);
            cb.setEnabled(!allInit);
            boxes.add(cb);
            ids.add(c.id);
            ca.addView(cb);
        }
        all.setOnCheckedChangeListener((btn, checked) -> {
            for (CheckBox cb : boxes) { cb.setEnabled(!checked); if (checked) cb.setChecked(false); }
        });
        if (Store.courses.isEmpty()) ca.addView(Ui.tv(a, "No courses yet.", 12, Ui.SUB, false));
        m.add(Ui.labeled(a, "Course Access", ca));

        m.actions("Cancel", "Save", Ui.B_PRIMARY, () -> {
            final String n = name.text();
            final String mobile = Store.normMobile(mob.text());
            final String expiry = exp.text();
            final String batchName = batch.text().trim();
            final boolean allC = all.isChecked();
            final List<Object> sel = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) sel.add(ids.get(i));
            if (n.isEmpty()) { a.toast("Please enter the member name"); return; }
            if (!mobile.matches("^01\\d{9}$")) { a.toast("Enter a valid 11-digit mobile number"); return; }
            if (!expiry.isEmpty() && !expiry.matches("^\\d{4}-\\d{2}-\\d{2}$")) { a.toast("Date format must be YYYY-MM-DD"); return; }
            if (!allC && sel.isEmpty()) { a.toast("Select at least one course, or choose All courses"); return; }
            final boolean isEdit = em != null;
            if (!isEdit) {
                for (Store.Member x : Store.members) {
                    if (x.id.equals("_member_" + mobile)) { a.toast("This mobile number is already a member"); return; }
                }
            }
            run(() -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", n);
                d.put("expiry", expiry);
                d.put("batch", batchName);
                d.put("allCourses", Boolean.valueOf(allC));
                d.put("courseIds", sel);
                if (isEdit) {
                    Fs.update(editId, d);
                } else {
                    d.put("isMember", Boolean.TRUE);
                    d.put("mobile", mobile);
                    d.put("created", Long.valueOf(System.currentTimeMillis()));
                    Fs.set("_member_" + mobile, d);
                }
                return Boolean.TRUE;
            }, () -> { a.toast(isEdit ? "Member updated" : "Member added"); m.dismiss(); a.reload(); });
        });
        m.show();
    }

    void confirmDelMember(final String id) {
        Store.Member mm = findMember(id);
        confirm("Remove member?", "“" + (mm == null ? id : mm.name) + "” will lose Premium access immediately.", () ->
            run(() -> { Fs.delete(id); return Boolean.TRUE; }, () -> { a.toast("Member removed"); a.reload(); }));
    }

    void deviceSettings(final String id) {
        final Store.Member mm = findMember(id);
        if (mm == null) return;
        final Ui.Modal m = new Ui.Modal(a, "Device Settings", mm.name + " — " + mm.mobile);
        Object lbl = mm.activeDevice == null ? null : mm.activeDevice.get("label");
        Object did = mm.activeDevice == null ? null : mm.activeDevice.get("deviceId");
        TextView cur = Ui.tv(a, mm.activeDevice != null
            ? "Currently logged in:\n" + (lbl instanceof String ? lbl : "Unknown device") + "\nDevice ID: " + (did instanceof String ? did : "")
            : "This member is not logged in on any device.", 13, Ui.TEXT, false);
        cur.setBackground(Ui.bg(a, Ui.BG2, 10, 0));
        cur.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
        m.add(cur);
        m.addSpaced(Ui.tv(a, "ALLOWED DEVICES", 11.5f, Ui.SUB, true), 14);
        m.add(Ui.tv(a, "Only these devices can log in. Leave the list empty to allow any device (one active device at a time).", 12, Ui.SUB, false));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        if (mm.allowedDevices.isEmpty()) {
            list.addView(Ui.tv(a, "No device restrictions are set.", 12.5f, Ui.SUB, false));
        }
        for (int i = 0; i < mm.allowedDevices.size(); i++) {
            final String dv = mm.allowedDevices.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
            String shown = dv.length() > 12 ? dv.substring(dv.length() - 12) : dv;
            row.addView(Ui.tv(a, "Device " + (i + 1) + ": " + shown, 12.5f, Ui.TEXT, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
            row.addView(Ui.smallBtn(a, "Remove", Ui.RED, v -> {
                final List<Object> next = new ArrayList<>();
                for (String x : mm.allowedDevices) if (!x.equals(dv)) next.add(x);
                run(() -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("allowedDevices", next);
                    Fs.update(id, u);
                    return Boolean.TRUE;
                }, () -> { m.dismiss(); a.toast("Device removed"); a.reload(deviceTask(id)); });
            }));
            list.addView(row);
        }
        m.add(list);
        if (did instanceof String && !mm.allowedDevices.contains(did)) {
            final String fid = (String) did;
            m.col("+ Allow the current device", Ui.B_OUTLINE, v -> {
                final List<Object> next = new ArrayList<>(mm.allowedDevices);
                next.add(fid);
                run(() -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("allowedDevices", next);
                    Fs.update(id, u);
                    return Boolean.TRUE;
                }, () -> { m.dismiss(); a.toast("Device allowed"); a.reload(deviceTask(id)); });
            });
        }
        m.col("Log out device", Ui.B_RED, v -> run(() -> {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("activeDevice", null);
            u.put("sessionToken", null);
            u.put("sessionDevice", null);
            Fs.update(id, u);
            return Boolean.TRUE;
        }, () -> { m.dismiss(); a.toast("Device logged out"); a.reload(); }));
        m.col("Close", Ui.B_GHOST, v -> m.dismiss());
        m.show();
    }

    private Runnable deviceTask(final String id) {
        return () -> deviceSettings(id);
    }
}
