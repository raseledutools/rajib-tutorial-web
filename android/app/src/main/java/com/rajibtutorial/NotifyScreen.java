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

import com.google.firebase.firestore.AggregateSource;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.text.DateFormat;
import java.util.Date;

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
    private TextView stat, cnt, sendBtn, segAll, segUser, resultTv;
    private LinearLayout userBox, history;
    private Ui.Field fMobile, fTitle, fBody;
    private boolean targetAll = true, armed = false, sending = false, released = false;
    private final Runnable disarm = this::disarm;

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

        // ── Send card ──
        LinearLayout card = Ui.card(a);
        card.addView(Ui.tv(a, "Send a new notification", 15, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        stat = Ui.tv(a, "…", 12.5f, Ui.SUB, false);
        card.addView(stat, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 12));

        LinearLayout seg = new LinearLayout(a);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        segAll = Ui.btn(a, "👥 All members", Ui.B_OUTLINE, v -> setTarget(true));
        segUser = Ui.btn(a, "👤 One member", Ui.B_OUTLINE, v -> setTarget(false));
        seg.addView(segAll, Ui.lpm(a, 0, WRAP, 0, 0, 4, 0));
        seg.addView(segUser, Ui.lpm(a, 0, WRAP, 4, 0, 0, 0));
        ((LinearLayout.LayoutParams) segAll.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) segUser.getLayoutParams()).weight = 1;
        card.addView(seg, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

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
        card.addView(Ui.tv(a, "Members receive notifications even when the app is closed. Delivery is not possible if the app was force-stopped or the device is offline.",
            12, Ui.SUB, false), Ui.lpm(a, MATCH, WRAP, 0, 10, 0, 0));
        root.addView(card, Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 14));

        // ── History card ──
        LinearLayout hc = Ui.card(a);
        hc.addView(Ui.tv(a, "Sent notifications", 15, Ui.TEXT, true), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        history = new LinearLayout(a);
        history.setOrientation(LinearLayout.VERTICAL);
        hc.addView(history);
        root.addView(hc);

        setTarget(true);
        updateCount();
        loadHistory();
        return sv;
    }

    private static void limit(EditText et, int n) {
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(n)});
    }

    private void updateCount() {
        if (cnt != null && fBody != null) cnt.setText(fBody.et.getText().length() + " / 500");
    }

    private void setTarget(boolean all) {
        targetAll = all;
        segAll.setBackground(Ui.bg(a, all ? Ui.ACCENT_SOFT : Ui.BG, 12, all ? Ui.ACCENT : 0x33000000));
        segAll.setTextColor(all ? Ui.ACCENT2 : Ui.TEXT);
        segUser.setBackground(Ui.bg(a, !all ? Ui.ACCENT_SOFT : Ui.BG, 12, !all ? Ui.ACCENT : 0x33000000));
        segUser.setTextColor(!all ? Ui.ACCENT2 : Ui.TEXT);
        userBox.setVisibility(all ? View.GONE : View.VISIBLE);
        disarm();
    }

    private void disarm() {
        main.removeCallbacks(disarm);
        armed = false;
        if (sendBtn != null && !sending) sendBtn.setText(targetAll ? "🔔 Send to all members" : "🔔 Send to this member");
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
        if (title.isEmpty()) { a.toast("Enter a title"); return; }
        if (body.isEmpty()) { a.toast("Enter a message"); return; }
        if (!targetAll && mobile.length() < 6) { a.toast("Enter a mobile number or choose a member"); return; }
        if (targetAll && !armed) {
            armed = true;
            sendBtn.setText("⚠ This goes to everyone — tap again to confirm");
            main.removeCallbacks(disarm);
            main.postDelayed(disarm, 4000);
            return;
        }
        disarm();
        sending = true;
        sendBtn.setEnabled(false);
        sendBtn.setText("Sending…");
        resultTv.setVisibility(View.VISIBLE);
        resultTv.setTextColor(Ui.SUB);
        resultTv.setText("⏳ Sending…");
        sender.send(title, body, targetAll ? "all" : "user", targetAll ? "" : mobile, msg -> main.post(() -> {
            sending = false;
            if (released) return;
            sendBtn.setEnabled(true);
            disarm();
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

    private void loadHistory() {
        stat.setText("Loading…");
        try {
            final FirebaseFirestore db = FirebaseFirestore.getInstance();
            db.collection("push_tokens").count().get(AggregateSource.SERVER)
                .addOnSuccessListener(r -> { if (!released) stat.setText("📱 Registered devices: " + r.getCount()); })
                .addOnFailureListener(e -> { if (!released) stat.setText("❌ " + e.getMessage()); });
            db.collection("push_messages").orderBy("at", Query.Direction.DESCENDING).limit(50).get()
                .addOnSuccessListener(snap -> {
                    if (released) return;
                    history.removeAllViews();
                    if (snap.isEmpty()) {
                        history.addView(Ui.tv(a, "No notifications sent yet", 12.5f, Ui.SUB, false));
                        return;
                    }
                    DateFormat df = DateFormat.getDateTimeInstance();
                    boolean first = true;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        String title = str(d.getString("title")), body = str(d.getString("body"));
                        String target = str(d.getString("target"));
                        Long at = d.getLong("at");
                        String to = "all".equals(target) ? "All members" : "👤 " + str(d.getString("mobile"));
                        String when = at == null ? "" : df.format(new Date(at));
                        LinearLayout row = new LinearLayout(a);
                        row.setOrientation(LinearLayout.VERTICAL);
                        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
                        row.addView(Ui.tv(a, title, 13.5f, Ui.TEXT, true));
                        row.addView(Ui.tv(a, body, 12.5f, Ui.TEXT, false), Ui.lpm(a, MATCH, WRAP, 0, 3, 0, 3));
                        row.addView(Ui.tv(a, to + " · " + when, 11.5f, Ui.SUB, false));
                        if (!first) {
                            View line = new View(a);
                            line.setBackgroundColor(Ui.BORDER);
                            history.addView(line, new LinearLayout.LayoutParams(MATCH, 1));
                        }
                        history.addView(row);
                        first = false;
                    }
                })
                .addOnFailureListener(e -> {
                    if (released) return;
                    history.removeAllViews();
                    history.addView(Ui.tv(a, "❌ " + e.getMessage(), 12.5f, Ui.RED, false));
                });
        } catch (Throwable t) {
            stat.setText("❌ " + t.getMessage());
        }
    }

    private static String str(String s) { return s == null ? "" : s; }

    @Override
    void release() {
        released = true;
        main.removeCallbacks(disarm);
    }
}
