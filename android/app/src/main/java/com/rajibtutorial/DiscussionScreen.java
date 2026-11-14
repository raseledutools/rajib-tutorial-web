package com.rajibtutorial;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Discussion: one group chat for all Premium members (and the admin).
 * Text, photos, audio, video and files. Files go to Cloudinary, messages
 * are stored in the same Firestore collection as the rest of the app
 * (documents with the "_disc_" id prefix, which sort chronologically).
 */
final class DiscussionScreen extends Screen {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final String SYNC_APP_ID = "rasel-mia-universal-sync";
    private static final String PREFIX = "_disc_";
    private static final int FETCH_LIMIT = 80;
    private static final long POLL_MS = 4000;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(24);

    private static final class Msg {
        String id = "", name = "", mobile = "", role = "", type = "text", text = "", url = "", fileName = "";
        long ts = 0;
    }

    private final NativeHomeActivity a;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    private FrameLayout host;
    private LinearLayout chatView, list, upWrap;
    private ScrollView scroll;
    private EditText input;
    private TextView upInfo;
    private ProgressBar upBar;
    private TextView headSub;

    private int mode = -1;            // 0 loading, 1 locked, 2 chat
    private boolean polling = false, fetching = false, pendingForce = false;
    private boolean uploading = false, released = false, firstRender = true;
    private String lastSig = "";
    private List<Msg> msgs = new ArrayList<>();

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!polling || released) return;
            fetch(false);
            h.postDelayed(this, Fs.queryBroken ? POLL_MS * 2 : POLL_MS);
        }
    };

    DiscussionScreen(NativeHomeActivity host) { a = host; }

    // ── Screen ──────────────────────────────────────────────
    @Override
    View build() {
        host = new FrameLayout(a);
        host.setBackgroundColor(Ui.BG);
        render();
        return host;
    }

    @Override
    void refresh() { render(); }

    @Override
    void onResume() { if (mode == 2) startPoll(); }

    @Override
    void onPause() { stopPoll(); }

    @Override
    void release() {
        released = true;
        stopPoll();
    }

    // ── Access ──────────────────────────────────────────────
    private boolean hasAccess() { return Store.adminIn || Store.isPremium(); }

    private String myId() {
        if (Store.adminIn) return "admin";
        return Store.sessionMobile == null ? "" : Store.sessionMobile;
    }

    private String myName() {
        if (Store.adminIn) return "Admin";
        Store.Member m = Store.curMember();
        return (m == null || m.name.isEmpty()) ? "Member" : m.name;
    }

    private void render() {
        if (released || host == null) return;
        int want = a.loading ? 0 : (hasAccess() ? 2 : 1);
        if (want == mode) return;
        mode = want;
        host.removeAllViews();
        if (want == 0) {
            stopPoll();
            ProgressBar pb = new ProgressBar(a);
            host.addView(pb, new FrameLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36), Gravity.CENTER));
        } else if (want == 1) {
            stopPoll();
            host.addView(lockedView(), new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            if (chatView == null) chatView = buildChat();
            host.addView(chatView, new FrameLayout.LayoutParams(MATCH, MATCH));
            startPoll();
        }
    }

    private View lockedView() {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(Ui.dp(a, 28), Ui.dp(a, 30), Ui.dp(a, 28), Ui.dp(a, 30));
        sv.addView(col, new ScrollView.LayoutParams(MATCH, MATCH));

        TextView lock = Ui.tv(a, "🔒", 30, Ui.ACCENT, true);
        lock.setGravity(Gravity.CENTER);
        lock.setBackground(Ui.circle(Ui.ACCENT_SOFT));
        col.addView(lock, Ui.lpm(a, Ui.dp(a, 72), Ui.dp(a, 72), 0, 0, 0, 16));

        TextView t = Ui.tv(a, "Premium members only", 19, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        col.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView s = Ui.tv(a, "The Discussion group is open to Premium members. Log in with your Premium mobile number and you will join the chat automatically.", 13.5f, Ui.SUB, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.2f);
        col.addView(s, Ui.lpm(a, MATCH, WRAP, 0, 8, 0, 20));

        col.addView(Ui.btn(a, "Login", Ui.B_PRIMARY, v -> a.dialogs.memberLogin()),
            Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 10));
        col.addView(Ui.btn(a, "★  Become Premium", Ui.B_OUTLINE, v -> a.dialogs.premiumGate(null)),
            new LinearLayout.LayoutParams(MATCH, WRAP));
        return sv;
    }

    // ── Chat UI ─────────────────────────────────────────────
    private static final int WALL = 0xFFF4F0EC;
    private static final int[] PALETTE = {0xFFE8365D, 0xFF6D4AF0, 0xFF0E9F8E, 0xFF2563EB,
        0xFFD97706, 0xFF9333EA, 0xFF16A34A, 0xFFDB2777};

    private static int colorFor(String key) {
        return PALETTE[Math.abs((key == null ? "" : key).hashCode()) % PALETTE.length];
    }

    private int memberCount() {
        int n = 0;
        String today = Store.todayStr();
        for (Store.Member m : Store.members) if (m.expiry.isEmpty() || today.compareTo(m.expiry) <= 0) n++;
        return n;
    }

    private String subText() {
        int n = memberCount();
        return (n > 0 ? n + " members · " : "") + "messages, media & group calls";
    }

    private TextView roundIcon(String icon, View.OnClickListener l) {
        TextView t = Ui.tv(a, icon, 18, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.circle(Ui.CARD2));
        Ui.pressFx(t);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout buildChat() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // header: group avatar, title, member count, call buttons
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Ui.BG);
        head.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
        TextView gav = Ui.tv(a, "💬", 18, Ui.WHITE, true);
        gav.setGravity(Gravity.CENTER);
        gav.setBackground(Ui.circle(Ui.ACCENT));
        head.addView(gav, Ui.lpm(a, Ui.dp(a, 42), Ui.dp(a, 42), 0, 0, 12, 0));
        LinearLayout meta = new LinearLayout(a);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.addView(Ui.tv(a, "Discussion Group", 16, Ui.TEXT, true));
        headSub = Ui.ellipsized(a, subText(), 12, Ui.SUB, false, 1);
        meta.addView(headSub);
        head.addView(meta, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(roundIcon("📞", v -> startCall(false)), Ui.lpm(a, Ui.dp(a, 40), Ui.dp(a, 40), 0, 0, 8, 0));
        head.addView(roundIcon("🎥", v -> startCall(true)), new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
        root.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        View hl = new View(a);
        hl.setBackgroundColor(Ui.BORDER);
        root.addView(hl, new LinearLayout.LayoutParams(MATCH, 1));

        scroll = new ScrollView(a);
        scroll.setBackgroundColor(WALL);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
        scroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        upWrap = new LinearLayout(a);
        upWrap.setOrientation(LinearLayout.VERTICAL);
        upWrap.setBackgroundColor(Ui.BG);
        upWrap.setPadding(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8));
        upInfo = Ui.tv(a, "", 12, Ui.SUB, false);
        upWrap.addView(upInfo);
        upBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        upBar.setMax(100);
        upWrap.addView(upBar, Ui.lpm(a, MATCH, Ui.dp(a, 6), 0, 4, 0, 0));
        upWrap.setVisibility(View.GONE);
        root.addView(upWrap, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM);
        bar.setBackgroundColor(Ui.BG);
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8));

        TextView attach = Ui.tv(a, "📎", 21, Ui.TEXT, false);
        attach.setGravity(Gravity.CENTER);
        Ui.pressFx(attach);
        attach.setOnClickListener(v -> attachMenu());
        bar.addView(attach, new LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 44)));

        input = new EditText(a);
        input.setHint("Message");
        input.setTextSize(15);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(0xFFA39C93);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(Ui.bg(a, Ui.CARD2, 22, 0));
        input.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
        bar.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView send = Ui.tv(a, "➤", 18, Ui.WHITE, true);
        send.setGravity(Gravity.CENTER);
        send.setBackground(Ui.circle(Ui.ACCENT));
        Ui.pressFx(send);
        send.setOnClickListener(v -> sendText());
        bar.addView(send, Ui.lpm(a, Ui.dp(a, 44), Ui.dp(a, 44), 8, 0, 0, 0));

        root.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        return root;
    }

    private void showUp(boolean on, String info, int pct) {
        if (upWrap == null) return;
        upWrap.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            upInfo.setText("Uploading " + info + (pct > 0 ? "  " + pct + "%" : "…"));
            upBar.setProgress(pct);
        }
    }

    private void attachMenu() {
        if (uploading) { a.toast("Please wait for the current upload"); return; }
        final Ui.Modal m = new Ui.Modal(a, "Share in Discussion", "Photos, videos, audio or any file (up to 100 MB).");
        m.col("🖼  Photo", Ui.B_GHOST, v -> { m.dismiss(); pick("image/*"); });
        m.col("🎬  Video", Ui.B_GHOST, v -> { m.dismiss(); pick("video/*"); });
        m.col("🎵  Audio", Ui.B_GHOST, v -> { m.dismiss(); pick("audio/*"); });
        m.col("📎  File / Document", Ui.B_GHOST, v -> { m.dismiss(); pick("*/*"); });
        m.col("Cancel", Ui.B_OUTLINE, v -> m.dismiss());
        m.show();
    }

    // ── Loading messages ────────────────────────────────────
    private static volatile boolean OPEN = false;

    /** True while the chat is on screen and polling (used to skip push notifications). */
    static boolean isOpen() { return OPEN; }

    private void startPoll() {
        if (polling || released) return;
        polling = true;
        OPEN = true;
        h.removeCallbacks(pollTask);
        h.post(pollTask);
    }

    private void stopPoll() {
        polling = false;
        OPEN = false;
        h.removeCallbacks(pollTask);
    }

    private static String str(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof String ? (String) o : "";
    }

    private static Msg toMsg(Fs.Doc d) {
        Msg m = new Msg();
        m.id = d.id;
        m.name = str(d.data, "name");
        m.mobile = str(d.data, "mobile");
        m.role = str(d.data, "role");
        m.type = str(d.data, "type").isEmpty() ? "text" : str(d.data, "type");
        m.text = str(d.data, "text");
        m.url = str(d.data, "url");
        m.fileName = str(d.data, "fileName");
        Object t = d.data.get("ts");
        m.ts = t instanceof Number ? ((Number) t).longValue() : 0L;
        return m;
    }

    private void fetch(final boolean force) {
        if (released) return;
        if (fetching) { if (force) pendingForce = true; return; }
        fetching = true;
        Store.async(() -> Fs.latestChat(PREFIX, FETCH_LIMIT), (List<Fs.Doc> docs, Exception e) -> {
            fetching = false;
            if (released) return;
            if (e != null || docs == null) {
                if (msgs.isEmpty() && list != null) {
                    String why = e != null && e.getMessage() != null ? e.getMessage() : "";
                    showNote("Could not load messages. Retrying…" + (why.isEmpty() ? "" : "\n\n" + why));
                }
                return;
            }
            List<Msg> out = new ArrayList<>();
            for (int i = docs.size() - 1; i >= 0; i--) out.add(toMsg(docs.get(i)));   // oldest → newest
            String sig = out.size() + ":" + (out.isEmpty() ? "" : out.get(0).id + ":" + out.get(out.size() - 1).id);
            if (force || !sig.equals(lastSig) || firstRender) {
                lastSig = sig;
                msgs = out;
                if (headSub != null) headSub.setText(subText());
                renderMessages();
            }
            if (pendingForce) { pendingForce = false; fetch(true); }
        });
    }

    private void showNote(String s) {
        list.removeAllViews();
        TextView t = Ui.tv(a, s, 13.5f, Ui.SUB, false);
        t.setGravity(Gravity.CENTER);
        list.addView(t, Ui.lpm(a, MATCH, WRAP, 0, 40, 0, 0));
    }

    private boolean atBottom() {
        View c = scroll.getChildAt(0);
        return c == null || c.getBottom() - (scroll.getHeight() + scroll.getScrollY()) < Ui.dp(a, 140);
    }

    private void renderMessages() {
        if (list == null) return;
        boolean stick = firstRender || atBottom();
        list.removeAllViews();
        if (msgs.isEmpty()) {
            showNote("No messages yet.\nSay hello 👋");
            firstRender = false;
            return;
        }
        String prevDay = "";
        for (Msg m : msgs) {
            String day = dayFmt.format(new Date(m.ts));
            if (!day.equals(prevDay)) {
                prevDay = day;
                TextView chip = Ui.tv(a, day, 11, Ui.SUB, true);
                chip.setGravity(Gravity.CENTER);
                chip.setBackground(Ui.bg(a, Ui.CARD2, 10, 0));
                chip.setPadding(Ui.dp(a, 10), Ui.dp(a, 3), Ui.dp(a, 10), Ui.dp(a, 3));
                LinearLayout w = new LinearLayout(a);
                w.setGravity(Gravity.CENTER);
                w.addView(chip, new LinearLayout.LayoutParams(WRAP, WRAP));
                list.addView(w, Ui.lpm(a, MATCH, WRAP, 0, 6, 0, 8));
            }
            list.addView(bubble(m), Ui.lpm(a, MATCH, WRAP, 0, 0, 0, 8));
        }
        firstRender = false;
        if (stick) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ── Bubbles ─────────────────────────────────────────────
    private boolean canDelete(Msg m) {
        return Store.adminIn || (!myId().isEmpty() && myId().equals(m.mobile));
    }

    private void markDeletable(View v, final Msg m) {
        if (!canDelete(m)) return;
        v.setOnLongClickListener(x -> { confirmDelete(m); return true; });
    }

    private View bubble(final Msg m) {
        final boolean mine = !myId().isEmpty() && myId().equals(m.mobile);
        final String who = m.name.isEmpty() ? "Member" : m.name;
        final int uc = colorFor(m.mobile.isEmpty() ? m.name : m.mobile);
        int fg = mine ? Ui.WHITE : Ui.TEXT;
        int sub = mine ? 0xCCFFFFFF : Ui.SUB;
        int maxW = Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.68f);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(mine ? Gravity.END : Gravity.START);

        if (!mine) {
            TextView av = Ui.tv(a, who.trim().isEmpty() ? "?" : who.trim().substring(0, 1).toUpperCase(), 14, Ui.WHITE, true);
            av.setGravity(Gravity.CENTER);
            av.setBackground(Ui.circle(uc));
            LinearLayout.LayoutParams ap = Ui.lpm(a, Ui.dp(a, 34), Ui.dp(a, 34), 0, 0, 8, 0);
            ap.gravity = Gravity.BOTTOM;
            row.addView(av, ap);
        }

        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        float r = Ui.dp(a, 16), t = Ui.dp(a, 4);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(mine ? Ui.ACCENT : Ui.WHITE);
        g.setCornerRadii(mine ? new float[]{r, r, r, r, t, t, r, r} : new float[]{r, r, r, r, r, r, t, t});
        if (!mine) g.setStroke(Math.max(1, Ui.dp(a, 1)), 0x14000000);
        b.setBackground(g);
        b.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 6));
        markDeletable(b, m);

        if (!mine) {
            LinearLayout nr = new LinearLayout(a);
            nr.setOrientation(LinearLayout.HORIZONTAL);
            nr.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = Ui.tv(a, who, 12.5f, uc, true);
            n.setMaxWidth(maxW - Ui.dp(a, 70));
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nr.addView(n);
            if ("admin".equals(m.role)) {
                TextView pill = Ui.pill(a, "ADMIN", Ui.WHITE, Ui.ACCENT);
                nr.addView(pill, Ui.lpm(a, WRAP, WRAP, 6, 0, 0, 0));
            }
            b.addView(nr, Ui.lpm(a, WRAP, WRAP, 0, 0, 0, 3));
        }

        switch (m.type) {
            case "image": {
                final ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.bg(a, 0x22000000, 10, 0));
                b.addView(iv, Ui.lpm(a, Ui.dp(a, 220), Ui.dp(a, 180), 0, 2, 0, 6));
                loadBitmap(cdn(m.url, "w_600,q_auto,f_jpg", false), iv);
                iv.setOnClickListener(v -> showImage(cdn(m.url, "w_1600,q_auto,f_jpg", false)));
                markDeletable(iv, m);
                break;
            }
            case "video": {
                FrameLayout fl = new FrameLayout(a);
                fl.setBackground(Ui.bg(a, 0xFF000000, 10, 0));
                final ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                fl.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
                TextView play = Ui.tv(a, "▶", 22, Ui.WHITE, true);
                play.setGravity(Gravity.CENTER);
                play.setBackground(Ui.circle(0x99000000));
                fl.addView(play, new FrameLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52), Gravity.CENTER));
                b.addView(fl, Ui.lpm(a, Ui.dp(a, 220), Ui.dp(a, 150), 0, 2, 0, 6));
                loadBitmap(cdn(m.url, "so_0,w_600,f_jpg", true), iv);
                fl.setOnClickListener(v -> showPlayer(m));
                markDeletable(fl, m);
                break;
            }
            case "audio": {
                TextView at = Ui.tv(a, "🎵  ▶  " + (m.fileName.isEmpty() ? "Audio" : m.fileName), 14, fg, true);
                at.setMaxWidth(maxW);
                at.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 6));
                Ui.pressFx(at);
                at.setOnClickListener(v -> showPlayer(m));
                markDeletable(at, m);
                b.addView(at);
                break;
            }
            case "file": {
                TextView ft = Ui.tv(a, FileViewer.icon(m.fileName, m.url) + "  " + (m.fileName.isEmpty() ? "File" : m.fileName), 14, fg, true);
                ft.setMaxWidth(maxW);
                ft.setPaintFlags(ft.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                ft.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 6));
                Ui.pressFx(ft);
                ft.setOnClickListener(v -> FileViewer.open(a, m.url, m.fileName));
                markDeletable(ft, m);
                b.addView(ft);
                break;
            }
            case "call": {
                TextView ct = Ui.tv(a, "📞  Group call", 15, fg, true);
                b.addView(ct);
                TextView cs = Ui.tv(a, (mine ? "You" : who) + " started a call", 12, sub, false);
                b.addView(cs, Ui.lpm(a, WRAP, WRAP, 0, 2, 0, 8));
                TextView join = Ui.btn(a, "Join call", mine ? Ui.B_OUTLINE : Ui.B_PRIMARY, v -> joinMenu());
                join.setMinHeight(Ui.dp(a, 38));
                b.addView(join, new LinearLayout.LayoutParams(Ui.dp(a, 170), WRAP));
                break;
            }
            default:
                break;
        }

        if (!m.text.isEmpty()) {
            TextView tt = Ui.tv(a, m.text, 15, fg, false);
            tt.setMaxWidth(maxW);
            tt.setLineSpacing(0, 1.1f);
            b.addView(tt);
        }

        TextView time = Ui.tv(a, m.ts > 0 ? timeFmt.format(new Date(m.ts)) : "", 10.5f, sub, false);
        time.setGravity(Gravity.END);
        b.addView(time, Ui.lpm(a, WRAP, WRAP, 0, 3, 0, 0));
        ((LinearLayout.LayoutParams) time.getLayoutParams()).gravity = Gravity.END;

        row.addView(b, new LinearLayout.LayoutParams(WRAP, WRAP));
        return row;
    }

    // ── Group call ──────────────────────────────────────────
    private void startCall(boolean video) {
        long now = System.currentTimeMillis();
        boolean recent = false;
        for (Msg m : msgs) if ("call".equals(m.type) && now - m.ts < 15 * 60 * 1000L) recent = true;
        if (!recent) post("call", "", "", "");        // announces the call (message + push to everybody)
        launchCall(video);
    }

    private void joinMenu() {
        final Ui.Modal m = new Ui.Modal(a, "Join group call", "Everyone in the Discussion group can join.");
        m.col("🎤  Join with voice", Ui.B_GHOST, v -> { m.dismiss(); launchCall(false); });
        m.col("🎥  Join with video", Ui.B_PRIMARY, v -> { m.dismiss(); launchCall(true); });
        m.col("Cancel", Ui.B_OUTLINE, v -> m.dismiss());
        m.show();
    }

    private void launchCall(boolean video) {
        Intent i = new Intent(a, GroupCallActivity.class)
            .putExtra(GroupCallActivity.EXTRA_UID, myId())
            .putExtra(GroupCallActivity.EXTRA_NAME, myName())
            .putExtra(GroupCallActivity.EXTRA_VIDEO, video);
        a.startActivity(i);
    }

    // ── Push notification for every new message ─────────────
    private void pushOut(String type, String text, String fileName, String id) {
        String body;
        switch (type) {
            case "image": body = "📷 Photo"; break;
            case "video": body = "🎬 Video"; break;
            case "audio": body = "🎵 Audio"; break;
            case "file": body = "📎 " + (fileName.isEmpty() ? "File" : fileName); break;
            case "call": body = "📞 Started a group call — tap to join"; break;
            default: body = text; break;
        }
        if (!"text".equals(type) && !"call".equals(type) && !text.isEmpty()) body += ": " + text;
        if (body.length() > 180) body = body.substring(0, 177) + "…";
        new AdminBridge(a.getApplicationContext(), null).sendChatPush(myName(), body, myId(), id);
    }

    /** Cloudinary on-the-fly transformation (thumbnails). Other hosts are returned untouched. */
    private static String cdn(String url, String tr, boolean swapExtToJpg) {
        if (url == null || !url.contains("res.cloudinary.com") || !url.contains("/upload/")) return url;
        String u = url.replace("/upload/", "/upload/" + tr + "/");
        if (swapExtToJpg) u = u.replaceAll("\\.[A-Za-z0-9]{2,5}$", ".jpg");
        return u;
    }

    private void loadBitmap(final String url, final ImageView iv) {
        Bitmap c = CACHE.get(url);
        if (c != null) { iv.setImageBitmap(c); return; }
        iv.setTag(url);
        Store.async(() -> download(url), (Bitmap bm, Exception e) -> {
            if (bm == null) return;
            CACHE.put(url, bm);
            if (url.equals(iv.getTag())) iv.setImageBitmap(bm);
        });
    }

    private static Bitmap download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            InputStream in = c.getInputStream();
            Bitmap bm = BitmapFactory.decodeStream(in);
            in.close();
            return bm;
        } finally {
            c.disconnect();
        }
    }

    // ── Viewers ─────────────────────────────────────────────
    private void showImage(String url) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout fl = new FrameLayout(a);
        fl.setBackgroundColor(Color.BLACK);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fl.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView close = Ui.tv(a, "✕", 22, Ui.WHITE, true);
        close.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));
        fl.addView(close, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END));
        fl.setOnClickListener(v -> d.dismiss());
        d.setContentView(fl);
        d.show();
        loadBitmap(url, iv);
    }

    private void showPlayer(Msg m) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout fl = new FrameLayout(a);
        fl.setBackgroundColor(Color.BLACK);
        if ("audio".equals(m.type)) {
            TextView t = Ui.tv(a, "🎵\n" + (m.fileName.isEmpty() ? "Audio" : m.fileName), 18, Ui.WHITE, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 60));
            fl.addView(t, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        }
        final VideoView vv = new VideoView(a);
        fl.addView(vv, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        MediaController mc = new MediaController(a);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);
        vv.setOnPreparedListener(mp -> mp.start());
        vv.setOnErrorListener((mp, what, extra) -> { a.toast("Cannot play this file"); return true; });
        TextView close = Ui.tv(a, "✕", 22, Ui.WHITE, true);
        close.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));
        close.setOnClickListener(v -> d.dismiss());
        fl.addView(close, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END));
        d.setOnDismissListener(x -> { try { vv.stopPlayback(); } catch (Exception ignored) { } });
        d.setContentView(fl);
        d.show();
        vv.setVideoURI(Uri.parse(m.url));
    }

    // ── Sending ─────────────────────────────────────────────
    private void sendText() {
        if (uploading) { a.toast("Please wait for the current upload"); return; }
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        input.setText("");
        post("text", t, "", "");
    }

    private void post(final String type, final String text, final String url, final String fileName) {
        final long ts = System.currentTimeMillis();
        final String id = PREFIX + String.format(Locale.US, "%013d", ts) + "_" + Integer.toString(new Random().nextInt(1679616) + 1679616, 36).substring(1);
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("appId", SYNC_APP_ID);
        d.put("name", myName());
        d.put("mobile", myId());
        d.put("role", Store.adminIn ? "admin" : "member");
        d.put("type", type);
        d.put("text", text);
        d.put("url", url);
        d.put("fileName", fileName);
        d.put("ts", Long.valueOf(ts));
        Store.async(() -> { Fs.set(id, d); return Boolean.TRUE; }, (Boolean ok, Exception e) -> {
            if (released) return;
            if (e != null) {
                a.toast("Could not send: " + (e.getMessage() == null ? "check your internet" : e.getMessage()));
                if ("text".equals(type) && input != null && input.getText().length() == 0) input.setText(text);
            } else {
                fetch(true);
                pushOut(type, text, fileName, id);
            }
        });
    }

    private void pick(String mime) {
        a.pickFile(mime, uri -> startUpload(uri));
    }

    private static String typeOf(String mime) {
        if (mime == null) return "file";
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        return "file";
    }

    private void startUpload(final Uri uri) {
        if (uploading || released) return;
        final String caption = input.getText().toString().trim();
        uploading = true;
        showUp(true, "…", 0);
        final Context app = a.getApplicationContext();
        Store.async(() -> {
            final Cloud.Meta meta = Cloud.meta(app, uri);
            if (meta.size > Cloud.MAX_BYTES) throw new Exception("File is too large (max 100 MB)");
            h.post(() -> showUp(true, meta.name, 0));
            return Cloud.upload(app, uri, meta, pct -> h.post(() -> showUp(true, meta.name, pct)));
        }, (Cloud.Result r, Exception e) -> {
            uploading = false;
            showUp(false, "", 0);
            if (released) return;
            if (e != null || r == null) {
                String msg = e != null && e.getMessage() != null ? e.getMessage() : "Upload failed";
                a.toast("Upload failed: " + msg);
                return;
            }
            if (!caption.isEmpty()) input.setText("");
            post(typeOf(r.mime), caption, r.url, r.name);
        });
    }

    // ── Delete ──────────────────────────────────────────────
    private void confirmDelete(final Msg m) {
        final Ui.Modal md = new Ui.Modal(a, "Delete message?", "This message will be removed for everyone in the group.");
        md.actions("Cancel", "Delete", Ui.B_RED, () -> {
            md.dismiss();
            Store.async(() -> { Fs.delete(m.id); return Boolean.TRUE; }, (Boolean ok, Exception e) -> {
                if (released) return;
                if (e != null) a.toast("Could not delete. Try again.");
                else fetch(true);
            });
        });
        md.show();
    }
}
