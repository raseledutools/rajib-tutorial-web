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
    void premiumGate(String reason) {
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
        m.col("Become Premium", Ui.B_PRIMARY, v -> { m.dismiss(); pay(); });
        if (!na) m.col("I'm already Premium: Login", Ui.B_OUTLINE, v -> { m.dismiss(); memberLogin(); });
        m.col("Maybe later", Ui.B_GHOST, v -> m.dismiss());
        m.show();
    }

    void contactAdmin() {
        final Ui.Modal m = new Ui.Modal(a, "Admin-কে Contact করুন", "Premium access পেতে নিচের WhatsApp নম্বরে যোগাযোগ করুন:");
        m.col("WhatsApp: " + Store.WHATSAPP_NUM, Ui.B_GREEN, v -> openUrl(Store.WHATSAPP));
        m.col("বন্ধ করুন", Ui.B_GHOST, v -> m.dismiss());
        m.show();
    }

    void pay() {
        final Ui.Modal m = new Ui.Modal(a, "Become Premium",
            "Choose a plan and pay securely with bKash. Your Premium access is activated automatically right after payment.");
        final String[] vals = {"1month", "6month", "1year"};
        final String[] names = {"1 Month", "6 Months", "1 Year"};
        final String[] badges = {"", "SAVE 40%", "BEST VALUE"};
        final String[] subs = {"Full access to all video lessons", "Best for a full semester", "Complete diploma preparation"};
        final String[] prices = {"৳50", "৳300", "৳500"};
        final int[] sel = {0};
        final List<LinearLayout> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
            LinearLayout left = new LinearLayout(a);
            left.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(Ui.tv(a, names[i], 14, Ui.TEXT, true));
            if (!badges[i].isEmpty()) {
                TextView b = Ui.pill(a, badges[i], Ui.GREEN, 0x2917915A);
                top.addView(b, Ui.lpm(a, WRAP, WRAP, 8, 0, 0, 0));
            }
            left.addView(top);
            left.addView(Ui.tv(a, subs[i], 11.5f, Ui.SUB, false));
            row.addView(left, new LinearLayout.LayoutParams(0, WRAP, 1f));
            row.addView(Ui.tv(a, prices[i], 16, Ui.ACCENT, true));
            row.setOnClickListener(v -> {
                sel[0] = idx;
                for (int k = 0; k < rows.size(); k++) paintPlan(rows.get(k), k == idx);
            });
            Ui.pressFx(row);
            rows.add(row);
            m.box.addView(row, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
        for (int k = 0; k < rows.size(); k++) paintPlan(rows.get(k), k == 0);

        final Ui.Field name = Ui.field(a, "Your Name *", "Full name", Ui.IN_TEXT);
        final Ui.Field mobile = Ui.field(a, "Mobile Number *", "01XXXXXXXXX", Ui.IN_PHONE);
        final TextView err = m.errorView();
        m.addSpaced(name.root, 6).add(mobile.root).add(err);

        final TextView payBtn = Ui.btn(a, "Pay with bKash", Ui.B_PRIMARY, null);
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
            final String plan = vals[sel[0]];
            final String fn = n, fm = mob;
            Store.async(() -> Store.createPayment(fn, fm, plan), (String url, Exception e) -> {
                payBtn.setEnabled(true);
                payBtn.setAlpha(1f);
                payBtn.setText("Pay with bKash");
                if (e == null && url != null) {
                    m.dismiss();
                    openUrl(url);
                } else if (e instanceof java.io.IOException) {
                    Ui.setErr(err, "Server is waking up or unreachable. Please wait 30 seconds and try again.");
                } else {
                    Ui.setErr(err, e != null && e.getMessage() != null ? e.getMessage() : "Could not start the payment. Please try again.");
                }
            });
        });
        m.addSpaced(payBtn, 6);
        m.col("Cancel", Ui.B_GHOST, v -> m.dismiss());

        TextView note = Ui.tv(a, "Payment is processed securely by bKash. Already Premium? ", 12, Ui.SUB, false);
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
        wa.addView(Ui.tv(a, "যোগাযোগ: ", 12, Ui.SUB, false));
        wa.addView(link("WhatsApp " + Store.WHATSAPP_NUM, Store.WHATSAPP));
        m.addSpaced(wa, 8);
        m.show();
    }

    private void paintPlan(LinearLayout row, boolean on) {
        row.setBackground(Ui.bg(a, on ? Ui.ACCENT_SOFT : Ui.BG, 14, on ? Ui.ACCENT : 0x33000000));
    }

    void memberLogin() {
        final Ui.Modal m = new Ui.Modal(a, "Premium Login", "Enter the mobile number that has Premium access to log in.");
        final Ui.Field mob = Ui.field(a, "Mobile Number", "01XXXXXXXXX", Ui.IN_PHONE);
        final TextView err = m.errorView();
        m.add(mob.root).add(err);
        m.actions("Cancel", "Login", Ui.B_PRIMARY, () -> {
            final String mobile = Store.normMobile(mob.text());
            if (!mobile.matches("^01\\d{9}$")) {
                Ui.setErr(err, "একটি সঠিক ১১-সংখ্যার মোবাইল নম্বর দিন (01XXXXXXXXX)।");
                return;
            }
            final TextView ok = m.lastOk;
            ok.setEnabled(false);
            ok.setText("যাচাই করা হচ্ছে…");
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
                a.toast("স্বাগতম, " + r.name);
                a.resumePending();
            });
        });
        m.show();
    }

    private void deviceConflict(String other) {
        final Ui.Modal m = new Ui.Modal(a, "অন্য একটি device-এ এই নম্বর দিয়ে login আছে",
            "এই নম্বরটি এখন " + other + "-এ সক্রিয়।\nআপনি কি সেই device logout করে এই device-এ login করতে চান?");
        m.col("হ্যাঁ, এই device-এ login করুন", Ui.B_PRIMARY, v -> {
            if (pendingDeviceMobile == null) return;
            final String mobile = pendingDeviceMobile;
            m.dismiss();
            Store.memberAuth(mobile, true, (Store.AuthResult r, Exception e) -> {
                if (r == null) return;
                if (r.err != null) { a.toast(r.err); return; }
                if (r.noAccess) { contactAdmin(); return; }
                pendingDeviceMobile = null;
                a.onSessionChanged();
                a.toast("স্বাগতম, " + r.name);
                a.resumePending();
            });
        });
        m.col("না, বাতিল করুন", Ui.B_GHOST, v -> m.dismiss());
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
        if (ec != null) { name.et.setText(ec.courseName); title.et.setText(ec.title); desc.et.setText(ec.description); }
        m.add(name.root).add(title.root).add(Ui.labeled(a, "Semester", semSp)).add(Ui.labeled(a, "Subject", subSp)).add(desc.root);
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
        if (em != null) {
            name.et.setText(em.name);
            mob.et.setText(em.mobile);
            mob.et.setEnabled(false);
            exp.et.setText(em.expiry);
        }
        m.add(name.root).add(mob.root).add(exp.root);

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
            : "এই member এখন কোনো device-এ login করা নেই।", 13, Ui.TEXT, false);
        cur.setBackground(Ui.bg(a, Ui.BG2, 10, 0));
        cur.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
        m.add(cur);
        m.addSpaced(Ui.tv(a, "ALLOWED DEVICES", 11.5f, Ui.SUB, true), 14);
        m.add(Ui.tv(a, "শুধু এই device গুলো login করতে পারবে। Empty রাখলে যেকোনো device থেকে login করা যাবে (তবে একসময়ে শুধু একটি)।", 12, Ui.SUB, false));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        if (mm.allowedDevices.isEmpty()) {
            list.addView(Ui.tv(a, "কোনো device restrict করা নেই।", 12.5f, Ui.SUB, false));
        }
        for (int i = 0; i < mm.allowedDevices.size(); i++) {
            final String dv = mm.allowedDevices.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
            String shown = dv.length() > 12 ? dv.substring(dv.length() - 12) : dv;
            row.addView(Ui.tv(a, "Device " + (i + 1) + ": " + shown, 12.5f, Ui.TEXT, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
            row.addView(Ui.smallBtn(a, "বাদ দিন", Ui.RED, v -> {
                final List<Object> next = new ArrayList<>();
                for (String x : mm.allowedDevices) if (!x.equals(dv)) next.add(x);
                run(() -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("allowedDevices", next);
                    Fs.update(id, u);
                    return Boolean.TRUE;
                }, () -> { m.dismiss(); a.toast("Device সরানো হয়েছে"); a.reload(deviceTask(id)); });
            }));
            list.addView(row);
        }
        m.add(list);
        if (did instanceof String && !mm.allowedDevices.contains(did)) {
            final String fid = (String) did;
            m.col("+ বর্তমান device allowed-list এ যোগ করুন", Ui.B_OUTLINE, v -> {
                final List<Object> next = new ArrayList<>(mm.allowedDevices);
                next.add(fid);
                run(() -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("allowedDevices", next);
                    Fs.update(id, u);
                    return Boolean.TRUE;
                }, () -> { m.dismiss(); a.toast("Device allowed-list এ যোগ হয়েছে"); a.reload(deviceTask(id)); });
            });
        }
        m.col("Device Logout করুন", Ui.B_RED, v -> run(() -> {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("activeDevice", null);
            u.put("sessionToken", null);
            u.put("sessionDevice", null);
            Fs.update(id, u);
            return Boolean.TRUE;
        }, () -> { m.dismiss(); a.toast("Device logout করা হয়েছে"); a.reload(); }));
        m.col("বন্ধ করুন", Ui.B_GHOST, v -> m.dismiss());
        m.show();
    }

    private Runnable deviceTask(final String id) {
        return () -> deviceSettings(id);
    }
}
