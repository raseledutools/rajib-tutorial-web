package com.rajibtutorial;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Admin: Notification Center (native version of the web "viewNotify").
 * Sends FCM messages to all users or one member, and lists the sent history.
 */
final class NotifyScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final NativeHomeActivity a;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AdminBridge sender;

    private ScrollView sv;
    private TextView stat, cnt, sendBtn, segAll, segBatch, segUser, resultTv, batchInfo;
    private LinearLayout userBox, batchBox, history, payBox;
    private TextView payStat;
    private Ui.Field fMobile, fBatch, fTitle, fBody;
    private static final int T_ALL = 0, T_BATCH = 1, T_USER = 2;
    private int target = T_ALL;
    private boolean sending = false, released = false;

    NotifyScreen(NativeHomeActivity host) {
        a = host;
        sender = new AdminBridge(host, null);
    }

    @Override
    View build() {
        sv = new ScrollView(a);
        sv.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 28));
        sv.addView(root, new ScrollView.LayoutParams(MATCH, WRAP));

        root.addView(Ui.tv(a, "🔔 Notification Center", 20, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        // ── Payment requests (TrxID) ──
        LinearLayout pc = Ui.card(a);
        LinearLayout ph = new LinearLayout(a);
        ph.setOrientation(LinearLayout.HORIZONTAL);
        ph.setGravity(Gravity.CENTER_VERTICAL);
        ph.addView(Ui.tv(a, "💳 Payment requests", 15, Ui.TEXT, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        ph.addView(Ui.smallBtn(a, "↻ Refresh", Ui.ACCENT, v -> loadRequests()));
        pc.addView(ph, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 4));
        payStat = Ui.tv(a, "Compare the TrxID with the bKash SMS on your phone, then tap Accept.", 12, Ui.SUB, false);
        pc.addView(payStat, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        payBox = new LinearLayout(a);
        payBox.setOrientation(LinearLayout.VERTICAL);
        pc.addView(payBox);
        root.addView(pc, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        // ── Send card ──
        LinearLayout card = Ui.card(a);
        card.addView(Ui.tv(a, "Send a new notification", 15, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        stat = Ui.tv(a, "…", 12.5f, Ui.SUB, false);
        card.addView(stat, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        LinearLayout seg = new LinearLayout(a);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        segAll = Ui.btn(a, "👥 All", Ui.B_OUTLINE, v -> setTarget(T_ALL));
        segBatch = Ui.btn(a, "🎓 Batch", Ui.B_OUTLINE, v -> setTarget(T_BATCH));
        segUser = Ui.btn(a, "👤 One", Ui.B_OUTLINE, v -> setTarget(T_USER));
        seg.addView(segAll, Ui.lpm(a, 0, WRAP, 0, 0, 3, 0));
        seg.addView(segBatch, Ui.lpm(a, 0, WRAP, 3, 0, 3, 0));
        seg.addView(segUser, Ui.lpm(a, 0, WRAP, 3, 0, 0, 0));
        ((LinearLayout.LayoutParams) segAll.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) segBatch.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) segUser.getLayoutParams()).weight = 1;
        card.addView(seg, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        batchBox = new LinearLayout(a);
        batchBox.setOrientation(LinearLayout.VERTICAL);
        fBatch = Ui.field(a, "Batch", "e.g. Batch 1", Ui.IN_TEXT);
        fBatch.et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { updateBatchInfo(); }
        });
        batchBox.addView(fBatch.root);
        batchBox.addView(Ui.smallBtn(a, "📋 Choose a batch", Ui.ACCENT, v -> pickBatch()),
            Ui.lpm(a, WRAP, WRAP, 0, 0, 0, 6));
        batchInfo = Ui.tv(a, "", 12, Ui.SUB, false);
        batchBox.addView(batchInfo, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));
        card.addView(batchBox);

        userBox = new LinearLayout(a);
        userBox.setOrientation(LinearLayout.VERTICAL);
        fMobile = Ui.field(a, "Member mobile number", "Enter a number or choose from the list below", Ui.IN_PHONE);
        userBox.addView(fMobile.root);
        userBox.addView(Ui.smallBtn(a, "📋 Choose from members", Ui.ACCENT, v -> pickMember()),
            Ui.lpm(a, WRAP, WRAP, 0, 0, 0, 12));
        card.addView(userBox);

        fTitle = Ui.field(a, "Title", "e.g. Live class tonight at 8 PM", Ui.IN_TEXT);
        limit(fTitle.et, 100);
        card.addView(fTitle.root);

        fBody = Ui.field(a, "Message", "Write your message…", Ui.IN_MULTI);
        limit(fBody.et, 500);
        fBody.et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { updateCount(); }
        });
        card.addView(fBody.root);
        cnt = Ui.tv(a, "0 / 500", 11, Ui.SUB, false);
        cnt.setGravity(Gravity.END);
        card.addView(cnt, Ui.lpm(a, MATCH, WRAP, 0, -8, 0, 8));

        sendBtn = Ui.btn(a, "🔔 Send to all members", Ui.B_PRIMARY, v -> send());
        card.addView(sendBtn, Ui.lpm(a, MATCH, WRAP, 0, 4, 0, 0));
        resultTv = Ui.tv(a, "", 13, Ui.SUB, true);
        resultTv.setVisibility(View.GONE);
        card.addView(resultTv, Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        card.addView(Ui.tv(a, "Members receive notifications even when the app is closed. If the message contains a web link, tapping the notification opens that link directly. Delivery is not possible if the app was force-stopped or the device is offline.",
            12, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        root.addView(card, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        // ── History card ──
        LinearLayout hc = Ui.card(a);
        hc.addView(Ui.tv(a, "Sent notifications", 15, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        history = new LinearLayout(a);
        history.setOrientation(LinearLayout.VERTICAL);
        hc.addView(history);
        root.addView(hc);

        setTarget(T_ALL);
        updateCount();
        loadHistory();
        loadRequests();
        return sv;
    }

    private static void limit(EditText et, int n) {
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(n)});
    }

    private void updateCount() {
        if (cnt != null && fBody != null) cnt.setText(fBody.et.getText().length() + " / 500");
    }

    private void styleSeg(TextView t, boolean on) {
        t.setBackground(Ui.bg(a, on ? Ui.ACCENT_SOFT : Ui.BG, 12, on ? Ui.ACCENT : 0x33000000));
        t.setTextColor(on ? Ui.ACCENT2 : Ui.TEXT);
    }

    private void setTarget(int t) {
        target = t;
        styleSeg(segAll, t == T_ALL);
        styleSeg(segBatch, t == T_BATCH);
        styleSeg(segUser, t == T_USER);
        batchBox.setVisibility(t == T_BATCH ? View.VISIBLE : View.GONE);
        userBox.setVisibility(t == T_USER ? View.VISIBLE : View.GONE);
        updateBatchInfo();
        resetSendLabel();
    }

    private void resetSendLabel() {
        if (sendBtn == null || sending) return;
        sendBtn.setText(target == T_ALL ? "🔔 Send to all members"
            : target == T_BATCH ? "🔔 Send to this batch" : "🔔 Send to this member");
    }

    private void updateBatchInfo() {
        if (batchInfo == null || fBatch == null) return;
        String b = fBatch.text().trim();
        if (b.isEmpty()) { batchInfo.setText(Store.batchNames().isEmpty()
            ? "No batches yet. Set a batch on each member in Premium Members." : ""); return; }
        int n = Store.batchCount(b);
        batchInfo.setText(n == 0 ? "⚠ No members in this batch" : "👥 " + n + " member" + (n == 1 ? "" : "s") + " in this batch");
    }

    private void pickBatch() {
        final java.util.List<String> names = Store.batchNames();
        final Ui.Modal m = new Ui.Modal(a, "Select a batch", names.isEmpty() ? "No batches yet" : null);
        for (final String bn : names) {
            m.col(bn + " · " + Store.batchCount(bn) + " members", Ui.B_GHOST, v -> { fBatch.et.setText(bn); m.dismiss(); });
        }
        m.col("Close", Ui.B_OUTLINE, v -> m.dismiss());
        m.show();
    }

    private void pickMember() {
        final Ui.Modal m = new Ui.Modal(a, "Select a member", Store.members.isEmpty() ? "No members yet" : null);
        for (final Store.Member mb : Store.members) {
            String label = (mb.name.isEmpty() ? "Member" : mb.name) + " · " + mb.mobile;
            m.col(label, Ui.B_GHOST, v -> { fMobile.et.setText(mb.mobile); m.dismiss(); });
        }
        m.col("Close", Ui.B_OUTLINE, v -> m.dismiss());
        m.show();
    }

    private void send() {
        if (sending) return;
        final String title = fTitle.text(), body = fBody.text();
        final String mobile = fMobile.text().replaceAll("\\D", "");
        final String batch = fBatch.text().trim();
        if (title.isEmpty()) { a.toast("Enter a title"); return; }
        if (body.isEmpty()) { a.toast("Enter a message"); return; }
        if (target == T_USER && mobile.length() < 6) { a.toast("Enter a mobile number or choose a member"); return; }
        if (target == T_BATCH && batch.isEmpty()) { a.toast("Enter or choose a batch"); return; }
        if (target == T_BATCH && Store.batchCount(batch) == 0) { a.toast("No members in this batch"); return; }
        sending = true;
        sendBtn.setEnabled(false);
        sendBtn.setText("Sending…");
        resultTv.setVisibility(View.VISIBLE);
        resultTv.setTextColor(Ui.SUB);
        resultTv.setText("⏳ Sending…");
        sender.send(title, body,
            target == T_ALL ? "all" : target == T_BATCH ? "batch" : "user",
            target == T_ALL ? "" : target == T_BATCH ? batch : mobile, msg -> main.post(() -> {
            sending = false;
            if (released) return;
            sendBtn.setEnabled(true);
            resetSendLabel();
            a.toast(msg);
            boolean okMsg = msg != null && msg.startsWith("✅");
            resultTv.setVisibility(View.VISIBLE);
            resultTv.setTextColor(okMsg ? 0xFF16A34A : Ui.RED);
            resultTv.setText(msg == null ? "❌ Something went wrong" : msg);
            if (okMsg) {
                fTitle.et.setText("");
                fBody.et.setText("");
                loadHistory();
            }
        }));
    }

    // ── Payment requests ────────────────────────────────────
    private void loadRequests() {
        if (payBox == null) return;
        payStat.setText("Loading…");
        Store.async(() -> PayRequests.latest(40), (java.util.List<PayRequests.Req> list, Exception e) -> {
            if (released) return;
            payBox.removeAllViews();
            if (e != null || list == null) {
                payStat.setText("❌ Could not load requests: " + (e == null ? "" : e.getMessage()));
                return;
            }
            int pending = 0;
            for (PayRequests.Req r : list) if (PayRequests.PENDING.equals(r.status)) pending++;
            payStat.setText(pending == 0 ? "No pending requests. Compare the TrxID with the bKash SMS, then tap Accept."
                : "⏳ " + pending + " waiting for you. Compare each TrxID with the bKash SMS, then tap Accept.");
            payStat.setTextColor(pending == 0 ? Ui.SUB : Ui.ACCENT2);
            if (list.isEmpty()) return;
            boolean first = true;
            for (PayRequests.Req r : list) {
                if (!first) {
                    View line = new View(a);
                    line.setBackgroundColor(Ui.BORDER);
                    payBox.addView(line, new LinearLayout.LayoutParams(MATCH, 1));
                }
                first = false;
                payBox.addView(requestRow(r));
            }
        });
    }

    private View requestRow(final PayRequests.Req r) {
        final boolean pend = PayRequests.PENDING.equals(r.status);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        String who = (r.name.isEmpty() ? "Buyer" : r.name) + " · " + r.mobile;
        row.addView(Ui.tv(a, who, 13.5f, Ui.TEXT, true));
        row.addView(Ui.tv(a, r.courseTitle + "  ·  " + Store.priceLabel(r.amount), 12.5f, Ui.SUB, false),
            Ui.lpm(a, MATCH, WRAP, 0, 2, 0, 4));
        TextView trx = Ui.tv(a, "TrxID  " + r.trx, 17, Ui.ACCENT2, true);
        trx.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        trx.setTextIsSelectable(true);
        row.addView(trx);
        String when = r.at == 0 ? "" : DateFormat.getDateTimeInstance().format(new Date(r.at));
        row.addView(Ui.tv(a, when + (pend ? "" : "  ·  " + r.status.toUpperCase()), 11.5f,
            PayRequests.ACCEPTED.equals(r.status) ? 0xFF16A34A : PayRequests.REJECTED.equals(r.status) ? Ui.RED : Ui.SUB, false),
            Ui.lpm(a, MATCH, WRAP, 0, 3, 0, 0));
        if (pend) {
            LinearLayout btns = new LinearLayout(a);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            TextView ok = Ui.btn(a, "✅ Accept", Ui.B_GREEN, v -> confirmDecision(r, true));
            TextView no = Ui.btn(a, "Reject", Ui.B_OUTLINE, v -> confirmDecision(r, false));
            btns.addView(ok, Ui.lpm(a, 0, WRAP, 0, 0, 4, 0));
            btns.addView(no, Ui.lpm(a, 0, WRAP, 4, 0, 0, 0));
            ((LinearLayout.LayoutParams) ok.getLayoutParams()).weight = 1;
            ((LinearLayout.LayoutParams) no.getLayoutParams()).weight = 1;
            row.addView(btns, Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        }
        return row;
    }

    private void confirmDecision(final PayRequests.Req r, final boolean accept) {
        final Ui.Modal m = new Ui.Modal(a, accept ? "Accept this payment?" : "Reject this request?",
            accept ? "Does TrxID " + r.trx + " (" + Store.priceLabel(r.amount) + ") match a bKash SMS you received?\n\n"
                    + "\"" + r.courseTitle + "\" will be unlocked for " + r.mobile + " and they get a push notification."
                   : "TrxID " + r.trx + " will be rejected and " + r.mobile + " will be notified.");
        m.actions("Cancel", accept ? "Accept" : "Reject", accept ? Ui.B_GREEN : Ui.B_RED, () -> {
            m.dismiss();
            payStat.setText("⏳ Working…");
            Store.async(() -> accept ? PayRequests.accept(a, r) : PayRequests.reject(a, r), (String msg, Exception e) -> {
                if (released) return;
                a.toast(e != null ? "❌ " + e.getMessage() : msg);
                if (e == null) {
                    // course list / members changed: refresh the in-memory copy too
                    Store.loadAll((Boolean ok2, Exception e2) -> { });
                }
                loadRequests();
            });
        });
        m.show();
    }

    private void loadHistory() {
        stat.setText("Loading…");
        Store.async(() -> Fs.count("push_tokens"), (Long n, Exception err) -> {
            if (released) return;
            if (err != null || n == null) stat.setText("❌ " + (err == null ? "" : err.getMessage()));
            else stat.setText("📱 Registered devices: " + n);
        });
        Store.async(() -> Fs.query("push_messages", null, null, null, "at", true, 50), (List<Fs.Doc> docs, Exception err) -> {
            if (released) return;
            history.removeAllViews();
            if (err != null || docs == null) {
                history.addView(Ui.tv(a, "❌ " + (err == null ? "" : err.getMessage()), 12.5f, Ui.RED, false));
                return;
            }
            if (docs.isEmpty()) {
                history.addView(Ui.tv(a, "No notifications sent yet", 12.5f, Ui.SUB, false));
                return;
            }
            DateFormat df = DateFormat.getDateTimeInstance();
            boolean first = true;
            for (Fs.Doc d : docs) {
                String title = Fs.text(d.data, "title"), body = Fs.text(d.data, "body");
                String tg = Fs.text(d.data, "target");
                long at = Fs.num(d.data, "at");
                String to = "all".equals(tg) ? "All members"
                    : "batch".equals(tg) ? "🎓 " + Fs.text(d.data, "batch") : "👤 " + Fs.text(d.data, "mobile");
                String when = at == 0 ? "" : df.format(new Date(at));
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
                row.addView(Ui.tv(a, title, 13.5f, Ui.TEXT, true));
                TextView bodyTv = Ui.tv(a, body, 12.5f, Ui.TEXT, false);
                Links.linkify(bodyTv, Ui.ACCENT2);
                row.addView(bodyTv, Ui.lpm(a, MATCH, WRAP, 0, 3, 0, 3));
                row.addView(Ui.tv(a, to + " · " + when, 11.5f, Ui.SUB, false));
                if (!first) {
                    View line = new View(a);
                    line.setBackgroundColor(Ui.BORDER);
                    history.addView(line, new LinearLayout.LayoutParams(MATCH, 1));
                }
                history.addView(row);
                first = false;
            }
        });
    }

    private static String str(String s) { return s == null ? "" : s; }

    @Override
    void release() {
        released = true;
    }
}
