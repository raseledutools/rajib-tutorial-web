package com.rajibtutorial;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * "Telephone": a WhatsApp-style messenger for Rajib Tutorial members.
 * One-to-one chats (text, photos, files, voice notes, read ticks) and voice/video calls.
 * Messages live in the shared Firestore collection under the "_dm_" id prefix (ids sort by time),
 * files go to Cloudinary, calls reuse the WebRTC call page (GroupCallActivity) with a private room.
 * The look copies the dark WhatsApp palette of the Discussion screen.
 */
public class MessengerActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // WhatsApp dark palette (same constants as DiscussionScreen)
    private static final int C_BG = 0xFF0B141A, C_BAR = 0xFF1F2C34, C_IN = 0xFF202C33, C_OUT = 0xFF005C4B;
    private static final int C_TEXT = 0xFFE9EDEF, C_SUB = 0xFF8696A0, C_GREEN = 0xFF00A884, C_TICK = 0xFF53BDEB;
    private static final int C_DIV = 0xFF1B262D, C_RED = 0xFFF15C6D, C_CHIP = 0xFF182229;

    /** Read by PushService: Telephone is on screen (it polls & rings by itself) and which chat is open. */
    static volatile boolean ON_SCREEN = false;
    static volatile String CUR_PEER = "";
    static final String EXTRA_PEER = "peer";

    private static final String PREFIX = "_dm_";
    private static final long POLL_MS = 3000;
    private static final int REQ_PICK = 4101, REQ_MIC = 4102;

    private static final int S_HOME = 0, S_CHAT = 1, S_PICK = 2;

    private static final int[] AV_COLORS = {0xFF6A7175, 0xFF1F7AEC, 0xFFB0368A, 0xFF8E5BD6, 0xFFD6663A,
        0xFF2E9E6B, 0xFFC09A1E, 0xFF0F8FA3};

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(30);

    // ── data ─────────────────────────────────────────────────
    private static final class Contact {
        String id, name, sub;
        Contact(String i, String n, String s) { id = i; name = n; sub = s; }
    }

    private static final class Msg {
        String id = "", from = "", fromName = "", to = "", toName = "", type = "text";
        String text = "", url = "", fileName = "", room = "";
        long ts = 0, dur = 0;
        boolean read = false, dl = false;
        int ans = 0;     // calls: 0 = unanswered, 1 = accepted, 2 = declined
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd/MM/yy", Locale.getDefault());
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault());

    private String meId = "", meName = "";
    private final List<Contact> contacts = new ArrayList<>();
    private final Map<String, Contact> byId = new HashMap<>();
    private List<Msg> all = new ArrayList<>();
    private final Map<String, Msg> pending = new HashMap<>();
    private final Set<String> acked = new HashSet<>();
    private final Set<String> handledCalls = new HashSet<>();

    private int screen = S_HOME, tab = 0;
    private String curPeer = "";
    private String query = "";
    private boolean searching = false;
    private boolean polling = false, fetching = false, released = false;
    private String lastSig = "";
    private String lastOutCall = "", callMsgId = "";

    // views
    private FrameLayout body;
    private LinearLayout listBox, msgBox, upWrap, inputBar, recRow;
    private ScrollView msgScroll;
    private EditText input, searchEt;
    private TextView sendBtn, recTimeTv, upInfo;

    // incoming call
    private Dialog callDlg;
    private Ringtone ringtone;

    // voice note
    private MediaRecorder recorder;
    private File recFile;
    private long recStart = 0;
    private boolean recording = false, uploading = false;
    private MediaPlayer player;
    private String playingId = "";

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!polling || released) return;
            fetch();
            h.postDelayed(this, Fs.queryBroken ? POLL_MS * 2 : POLL_MS);
        }
    };

    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long s = (System.currentTimeMillis() - recStart) / 1000;
            if (recTimeTv != null) recTimeTv.setText(String.format(Locale.US, "● %d:%02d", s / 60, s % 60));
            h.postDelayed(this, 500);
        }
    };

    // ── lifecycle ────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.init(this);
        getWindow().setStatusBarColor(C_BAR);
        getWindow().setNavigationBarColor(C_BAR);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        meId = Store.adminIn ? "admin" : san(Store.sessionMobile == null ? "" : Store.sessionMobile);
        if (Store.adminIn) meName = "Admin";
        else {
            Store.Member m = Store.curMember();
            meName = (m == null || m.name.isEmpty()) ? (Store.sessionMobile == null ? "Member" : Store.sessionMobile) : m.name;
        }

        body = new FrameLayout(this);
        body.setBackgroundColor(C_BG);
        setContentView(body);

        if (meId.isEmpty()) { renderLoginNeeded(); return; }

        try { PushManager.setDm(getApplicationContext(), true, meId, meName); } catch (Throwable ignored) { }
        buildContacts();
        if (Store.members.isEmpty()) {
            Store.loadAll((Boolean ok, Exception e) -> {
                if (released) return;
                buildContacts();
                render();
            });
        }
        render();
        handlePeerIntent(getIntent());
        ensureCallPermissions();
    }

    /** Once per install: allow notifications + the full-screen call screen so calls ring with the phone locked. */
    private void ensureCallPermissions() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("rt_native", MODE_PRIVATE);
            if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4103);
            }
            if (android.os.Build.VERSION.SDK_INT >= 34 && !sp.getBoolean("dm_fsi_asked", false)) {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && !nm.canUseFullScreenIntent()) {
                    sp.edit().putBoolean("dm_fsi_asked", true).apply();
                    final Ui.Modal m = new Ui.Modal(this, "Show calls on lock screen",
                        "Allow “Full-screen notifications” so incoming calls open the call screen even when the phone is locked.");
                    m.col("Open settings", Ui.B_PRIMARY, v -> {
                        m.dismiss();
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) { toast("Open Settings → Apps → Special access"); }
                    });
                    m.col("Later", Ui.B_OUTLINE, v -> m.dismiss());
                    m.show();
                }
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handlePeerIntent(i);
    }

    private void handlePeerIntent(Intent i) {
        if (i == null || meId.isEmpty()) return;
        String peer = i.getStringExtra(EXTRA_PEER);
        if (peer != null && !peer.isEmpty() && !peer.equals(meId)) {
            i.removeExtra(EXTRA_PEER);
            openChat(peer);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ON_SCREEN = true;
        if (meId.isEmpty()) return;
        cancelUnansweredCall();
        polling = true;
        h.removeCallbacks(pollTask);
        h.post(pollTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ON_SCREEN = false;
        polling = false;
        h.removeCallbacks(pollTask);
        stopPlayer();
        if (recording) cancelRecording();
    }

    @Override
    protected void onDestroy() {
        ON_SCREEN = false;
        CUR_PEER = "";
        released = true;
        h.removeCallbacksAndMessages(null);
        stopRing();
        stopPlayer();
        if (recording) cancelRecording();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (screen == S_CHAT || screen == S_PICK) {
            hideKeyboard();
            screen = S_HOME;
            curPeer = "";
            lastSig = "";
            render();
        } else if (searching) {
            searching = false;
            query = "";
            render();
        } else {
            super.onBackPressed();
        }
    }

    // ── helpers ──────────────────────────────────────────────
    private static String san(String s) { return s == null ? "" : s.replaceAll("[^A-Za-z0-9]", ""); }

    private int dp(float v) { return Ui.dp(this, v); }

    private static String keyOf(String a, String b) { return a.compareTo(b) < 0 ? a + "-" + b : b + "-" + a; }

    private static String str(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof String ? (String) o : "";
    }

    private static long num(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            View f = getCurrentFocus();
            if (im != null && f != null) im.hideSoftInputFromWindow(f.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void buildContacts() {
        contacts.clear();
        byId.clear();
        if (!"admin".equals(meId)) add(new Contact("admin", "Admin", "Course admin"));
        for (Store.Member m : Store.members) {
            String raw = m.id != null && m.id.startsWith("_member_") ? m.id.substring(8) : m.mobile;
            String id = san(raw);
            if (id.isEmpty() || id.equals(meId) || byId.containsKey(id)) continue;
            add(new Contact(id, (m.name == null || m.name.isEmpty()) ? raw : m.name, raw));
        }
        Collections.sort(contacts, (x, y) -> x.name.compareToIgnoreCase(y.name));
    }

    private void add(Contact c) { contacts.add(c); byId.put(c.id, c); }

    private Contact contactOf(String id, String fallbackName) {
        Contact c = byId.get(id);
        if (c != null) return c;
        String n = fallbackName == null || fallbackName.isEmpty() ? id : fallbackName;
        return new Contact(id, n, id);
    }

    private String peerOf(Msg m) { return m.from.equals(meId) ? m.to : m.from; }

    private String dayLabel(long ts) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "Today";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "Yesterday";
        return dayFmt.format(new Date(ts));
    }

    private String listTime(long ts) {
        String d = dayLabel(ts);
        if ("Today".equals(d)) return timeFmt.format(new Date(ts));
        if ("Yesterday".equals(d)) return d;
        return dateFmt.format(new Date(ts));
    }

    private String preview(Msg m) {
        switch (m.type) {
            case "image": return "📷 Photo";
            case "video": return "🎬 Video";
            case "audio": return "🎤 Voice message";
            case "file": return "📎 " + (m.fileName.isEmpty() ? "File" : m.fileName);
            case "call": return "video".equals(m.text) ? "📹 Video call" : "📞 Voice call";
            default: return m.text;
        }
    }

    private TextView avatar(String name, String id, int sizeDp, float sp) {
        String ini = name == null || name.trim().isEmpty() ? "?" : name.trim().substring(0, 1).toUpperCase(Locale.getDefault());
        TextView a = Ui.tv(this, ini, sp, Color.WHITE, true);
        a.setGravity(Gravity.CENTER);
        a.setBackground(Ui.circle(AV_COLORS[Math.abs(id.hashCode()) % AV_COLORS.length]));
        a.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return a;
    }

    private TextView iconBtn(String glyph, View.OnClickListener l) {
        TextView t = Ui.tv(this, glyph, 20, C_TEXT, false);
        t.setGravity(Gravity.CENTER);
        Ui.pressFx(t);
        t.setOnClickListener(l);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_DIV);
        return v;
    }

    // ── data fetch ───────────────────────────────────────────
    private void fetch() {
        if (released || fetching) return;
        fetching = true;
        Store.async(() -> Fs.latestChat(PREFIX, 400), (List<Fs.Doc> docs, Exception e) -> {
            fetching = false;
            if (released || e != null || docs == null) return;
            applyDocs(docs);
        });
    }

    private Msg toMsg(Fs.Doc d) {
        Msg m = new Msg();
        m.id = d.id;
        m.from = str(d.data, "from");
        m.fromName = str(d.data, "fromName");
        m.to = str(d.data, "to");
        m.toName = str(d.data, "toName");
        String t = str(d.data, "type");
        m.type = t.isEmpty() ? "text" : t;
        m.text = str(d.data, "text");
        m.url = str(d.data, "url");
        m.fileName = str(d.data, "fileName");
        m.room = str(d.data, "room");
        m.ts = num(d.data, "ts");
        m.dur = num(d.data, "dur");
        m.read = num(d.data, "read") == 1;
        m.dl = num(d.data, "dl") == 1;
        m.ans = (int) num(d.data, "ans");
        return m;
    }

    private void applyDocs(List<Fs.Doc> docs) {
        List<Msg> list = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Fs.Doc d : docs) {
            if (!d.id.startsWith(PREFIX)) continue;
            Msg m = toMsg(d);
            if (!m.from.equals(meId) && !m.to.equals(meId)) continue;
            list.add(m);
            ids.add(m.id);
        }
        long now = System.currentTimeMillis();
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, Msg> en : pending.entrySet()) {
            if (ids.contains(en.getKey()) || now - en.getValue().ts > 30000) drop.add(en.getKey());
            else list.add(en.getValue());
        }
        for (String k : drop) pending.remove(k);
        Collections.sort(list, (x, y) -> x.id.compareTo(y.id));
        all = list;

        StringBuilder sb = new StringBuilder();
        sb.append(screen).append('|').append(tab).append('|').append(curPeer).append('|').append(list.size());
        for (Msg m : list) sb.append(m.read ? 'r' : m.dl ? 'd' : 's').append(m.ans);
        if (!sb.toString().equals(lastSig)) {
            lastSig = sb.toString();
            if (screen == S_HOME) renderHomeList();
            else if (screen == S_CHAT) renderMessages(true);
        }
        ack();
        checkIncomingCall();
    }

    /** Marks incoming messages as delivered (list open) or read (their chat open). */
    private void ack() {
        final List<String[]> todo = new ArrayList<>();
        for (Msg m : all) {
            if (m.from.equals(meId) || "call".equals(m.type) || pending.containsKey(m.id)) continue;
            boolean open = screen == S_CHAT && m.from.equals(curPeer);
            if (open && !m.read && acked.add(m.id + "r")) todo.add(new String[]{m.id, "r"});
            else if (!open && !m.dl && !m.read && acked.add(m.id + "d")) todo.add(new String[]{m.id, "d"});
        }
        if (todo.isEmpty()) return;
        Store.async(() -> {
            for (String[] t : todo) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("dl", Long.valueOf(1));
                if ("r".equals(t[1])) d.put("read", Long.valueOf(1));
                try { Fs.update(t[0], d); } catch (Exception ignored) { }
            }
            return Boolean.TRUE;
        }, null);
    }

    // ── sending ──────────────────────────────────────────────
    private String send(String type, String text, String url, String fileName, Map<String, Object> extra) {
        if (curPeer.isEmpty()) return "";
        final String peer = curPeer;
        final long ts = System.currentTimeMillis();
        final String id = PREFIX + keyOf(meId, curPeer) + "_" + String.format(Locale.US, "%013d", ts) + "_"
            + Integer.toString(new Random().nextInt(1679616) + 1679616, 36).substring(1);
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("appId", DiscussionScreen.SYNC_APP_ID);
        d.put("from", meId);
        d.put("fromName", meName);
        d.put("to", curPeer);
        d.put("toName", contactOf(curPeer, "").name);
        d.put("type", type);
        d.put("text", text);
        d.put("url", url);
        d.put("fileName", fileName);
        d.put("ts", Long.valueOf(ts));
        if (extra != null) d.putAll(extra);

        Msg pm = new Msg();
        pm.id = id; pm.from = meId; pm.fromName = meName; pm.to = curPeer; pm.type = type;
        pm.text = text; pm.url = url; pm.fileName = fileName; pm.ts = ts;
        pm.room = extra != null && extra.get("room") instanceof String ? (String) extra.get("room") : "";
        pm.dur = extra != null && extra.get("dur") instanceof Number ? ((Number) extra.get("dur")).longValue() : 0;
        pending.put(id, pm);
        all.add(pm);
        lastSig = "";
        if (screen == S_CHAT) renderMessages(true);

        Store.async(() -> { Fs.set(id, d); return Boolean.TRUE; }, (Boolean ok, Exception e) -> {
            if (released) return;
            if (e != null) {
                pending.remove(id);
                all.remove(pm);
                lastSig = "";
                if (screen == S_CHAT) renderMessages(false);
                toast("Could not send: " + (e.getMessage() == null ? "check your internet" : e.getMessage()));
            } else {
                fetch();
                boolean call = "call".equals(pm.type);
                try {
                    new AdminBridge(getApplicationContext(), null).sendDmPush(peer, call ? "dmcall" : "dm", meId, meName,
                        preview(pm), id, pm.room, "video".equals(pm.text));
                } catch (Throwable ignored) { }
            }
        });
        return id;
    }

    private void sendText() {
        if (input == null) return;
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        input.setText("");
        send("text", t, "", "", null);
    }

    // ── calls ────────────────────────────────────────────────
    private void startCall(boolean video) {
        if (curPeer.isEmpty()) return;
        String room = "dm_" + keyOf(meId, curPeer) + "_" + Long.toString(System.currentTimeMillis(), 36);
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("room", room);
        lastOutCall = send("call", video ? "video" : "voice", "", "", ex);
        launchCall(room, video);
    }

    private void callBack(String peer, boolean video) {
        curPeer = peer;
        startCall(video);
        curPeer = screen == S_CHAT ? peer : "";
    }

    private void launchCall(String room, boolean video) {
        Intent i = new Intent(this, GroupCallActivity.class)
            .putExtra(GroupCallActivity.EXTRA_UID, meId)
            .putExtra(GroupCallActivity.EXTRA_NAME, meName)
            .putExtra(GroupCallActivity.EXTRA_VIDEO, video)
            .putExtra(GroupCallActivity.EXTRA_ROOM, room);
        startActivity(i);
    }

    /** Back from a call screen: if the other side never answered, tell them the call was cancelled (stops their ringing). */
    private void cancelUnansweredCall() {
        final String id = lastOutCall;
        if (id.isEmpty()) return;
        lastOutCall = "";
        Store.async(() -> {
            Fs.Doc d = Fs.get(id);
            if (d != null && Fs.num(d.data, "ans") == 0) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("ans", Long.valueOf(3));
                Fs.update(id, u);
            }
            return Boolean.TRUE;
        }, null);
    }

    private void checkIncomingCall() {
        if (callDlg != null && callDlg.isShowing()) {
            for (Msg m : all) {
                if (m.id.equals(callMsgId) && m.ans != 0) { stopRing(); callDlg.dismiss(); break; }
            }
            return;
        }
        long now = System.currentTimeMillis();
        for (Msg m : all) {
            if (!"call".equals(m.type) || m.from.equals(meId) || m.ans != 0 || m.room.isEmpty()) continue;
            if (now - m.ts > 40000 || handledCalls.contains(m.id)) continue;
            handledCalls.add(m.id);
            showIncoming(m);
            return;
        }
    }

    private void showIncoming(final Msg m) {
        final boolean video = "video".equals(m.text);
        final Contact c = contactOf(m.from, m.fromName);
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        callDlg = d;
        callMsgId = m.id;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(0xFF0F1B21);
        col.setPadding(dp(24), dp(90), dp(24), dp(60));

        col.addView(Ui.tv(this, video ? "Telephone video call" : "Telephone voice call", 14, C_SUB, false));
        TextView nm = Ui.tv(this, c.name, 30, C_TEXT, true);
        nm.setGravity(Gravity.CENTER);
        col.addView(nm, Ui.lpm(this, WRAP, WRAP, 0, 10, 0, 0));
        col.addView(Ui.tv(this, "Incoming call…", 15, C_SUB, false), Ui.lpm(this, WRAP, WRAP, 0, 6, 0, 0));
        col.addView(avatar(c.name, c.id, 140, 56), Ui.lpm(this, dp(140), dp(140), 0, 50, 0, 0));
        View sp = new View(this);
        col.addView(sp, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        TextView dec = bigRound("✕", C_RED);
        TextView acc = bigRound(video ? "🎥" : "📞", C_GREEN);
        row.addView(labelled(dec, "Decline"), Ui.lpm(this, WRAP, WRAP, 0, 0, 56, 0));
        row.addView(labelled(acc, "Accept"));
        col.addView(row);

        dec.setOnClickListener(v -> { stopRing(); d.dismiss(); answerCall(m, 2); });
        acc.setOnClickListener(v -> {
            stopRing();
            d.dismiss();
            answerCall(m, 1);
            launchCall(m.room, video);
        });
        d.setContentView(col);
        d.setCancelable(false);
        d.show();
        startRing();
        h.postDelayed(() -> { if (d.isShowing() && !released) { stopRing(); d.dismiss(); } }, 40000);
    }

    private TextView bigRound(String glyph, int color) {
        TextView t = Ui.tv(this, glyph, 26, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.circle(color));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(68), dp(68)));
        Ui.pressFx(t);
        return t;
    }

    private View labelled(View v, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(v);
        c.addView(Ui.tv(this, label, 13, C_SUB, false), Ui.lpm(this, WRAP, WRAP, 0, 8, 0, 0));
        return c;
    }

    private void answerCall(final Msg m, final int ans) {
        m.ans = ans;
        Store.async(() -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("ans", Long.valueOf(ans));
            Fs.update(m.id, d);
            return Boolean.TRUE;
        }, null);
    }

    private void startRing() {
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) { }
    }

    private void stopRing() {
        try { if (ringtone != null) ringtone.stop(); } catch (Exception ignored) { }
        ringtone = null;
    }

    // ── rendering: shell ─────────────────────────────────────
    private void render() {
        hideKeyboardSafe();
        body.removeAllViews();
        input = null; searchEt = null; msgBox = null; msgScroll = null; listBox = null;
        lastSig = "";
        CUR_PEER = screen == S_CHAT ? curPeer : "";
        if (screen == S_CHAT) body.addView(buildChat(), new FrameLayout.LayoutParams(MATCH, MATCH));
        else if (screen == S_PICK) body.addView(buildPicker(), new FrameLayout.LayoutParams(MATCH, MATCH));
        else body.addView(buildHome(), new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void hideKeyboardSafe() { if (body != null && body.getChildCount() > 0) hideKeyboard(); }

    private void renderLoginNeeded() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(32), 0, dp(32), 0);
        TextView e = Ui.tv(this, "💬", 56, C_TEXT, false);
        e.setGravity(Gravity.CENTER);
        c.addView(e);
        TextView t = Ui.tv(this, "Telephone", 24, C_TEXT, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t, Ui.lpm(this, WRAP, WRAP, 0, 12, 0, 6));
        TextView s = Ui.tv(this, "Please log in to your Rajib Tutorial account first (Native app → Login), then open Telephone again.", 14, C_SUB, false);
        s.setGravity(Gravity.CENTER);
        c.addView(s);
        TextView b = Ui.tv(this, "Go back", 15, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.bg(this, C_GREEN, 24, 0));
        b.setPadding(dp(28), dp(12), dp(28), dp(12));
        Ui.pressFx(b);
        b.setOnClickListener(v -> finish());
        c.addView(b, Ui.lpm(this, WRAP, WRAP, 0, 24, 0, 0));
        body.addView(c, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    // ── rendering: home (Chats / Calls) ──────────────────────
    private View buildHome() {
        FrameLayout wrap = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(C_BG);
        top.setPadding(dp(4), dp(8), dp(8), dp(8));
        TextView back = iconBtn("←", v -> finish());
        back.setTextSize(24);
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        if (searching) {
            searchEt = new EditText(this);
            searchEt.setHint("Search…");
            searchEt.setHintTextColor(C_SUB);
            searchEt.setTextColor(C_TEXT);
            searchEt.setTextSize(16);
            searchEt.setSingleLine(true);
            searchEt.setText(query);
            searchEt.setBackground(Ui.bg(this, C_BAR, 22, 0));
            searchEt.setPadding(dp(16), dp(8), dp(16), dp(8));
            searchEt.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int s0, int c0, int a0) { }
                @Override public void onTextChanged(CharSequence c, int s0, int b0, int c0) { }
                @Override public void afterTextChanged(Editable e) { query = e.toString(); renderHomeList(); }
            });
            top.addView(searchEt, new LinearLayout.LayoutParams(0, WRAP, 1f));
            top.addView(iconBtn("✕", v -> { searching = false; query = ""; render(); }), new LinearLayout.LayoutParams(dp(44), dp(44)));
            searchEt.requestFocus();
        } else {
            TextView title = Ui.tv(this, tab == 0 ? "Telephone" : "Calls", 22, C_TEXT, true);
            top.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
            top.addView(iconBtn("🔍", v -> { searching = true; render(); }), new LinearLayout.LayoutParams(dp(44), dp(44)));
            top.addView(iconBtn("⋮", v -> homeMenu()), new LinearLayout.LayoutParams(dp(40), dp(44)));
        }
        root.addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // bottom nav
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(C_BAR);
        nav.addView(navItem("💬", "Chats", 0), new LinearLayout.LayoutParams(0, WRAP, 1f));
        nav.addView(navItem("📞", "Calls", 1), new LinearLayout.LayoutParams(0, WRAP, 1f));
        root.addView(nav, new LinearLayout.LayoutParams(MATCH, WRAP));

        wrap.addView(root, new FrameLayout.LayoutParams(MATCH, MATCH));

        TextView fab = Ui.tv(this, tab == 0 ? "✉" : "📞", 22, Color.WHITE, true);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(Ui.bg(this, C_GREEN, 16, 0));
        Ui.pressFx(fab);
        fab.setOnClickListener(v -> { screen = S_PICK; query = ""; searching = false; render(); });
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM | Gravity.END);
        fl.setMargins(0, 0, dp(18), dp(84));
        wrap.addView(fab, fl);

        renderHomeList();
        return wrap;
    }

    private View navItem(String icon, String label, final int idx) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, dp(10), 0, dp(10));
        boolean on = tab == idx;
        TextView ic = Ui.tv(this, icon, 18, C_TEXT, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(on ? Ui.bg(this, 0xFF0A3D35, 16, 0) : null);
        ic.setPadding(dp(20), dp(4), dp(20), dp(4));
        c.addView(ic);
        c.addView(Ui.tv(this, label, 12, on ? C_TEXT : C_SUB, on), Ui.lpm(this, WRAP, WRAP, 0, 4, 0, 0));
        Ui.pressFx(c);
        c.setOnClickListener(v -> { tab = idx; searching = false; query = ""; render(); });
        return c;
    }

    private void homeMenu() {
        final Ui.Modal m = new Ui.Modal(this, "Telephone", "Chats & calls with Rajib Tutorial members.");
        m.col("✉  New chat", Ui.B_GHOST, v -> { m.dismiss(); screen = S_PICK; query = ""; render(); });
        m.col("🔄  Refresh", Ui.B_GHOST, v -> { m.dismiss(); lastSig = ""; fetch(); });
        m.col("Close", Ui.B_OUTLINE, v -> m.dismiss());
        m.show();
    }

    private void renderHomeList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        if (tab == 0) renderChatRows(); else renderCallRows();
    }

    private void renderChatRows() {
        LinkedHashMap<String, List<Msg>> by = new LinkedHashMap<>();
        for (Msg m : all) {
            String p = peerOf(m);
            if (p.isEmpty()) continue;
            List<Msg> l = by.get(p);
            if (l == null) { l = new ArrayList<>(); by.put(p, l); }
            l.add(m);
        }
        List<String> peers = new ArrayList<>(by.keySet());
        Collections.sort(peers, (x, y) -> Long.compare(by.get(y).get(by.get(y).size() - 1).ts, by.get(x).get(by.get(x).size() - 1).ts));
        String q = query.trim().toLowerCase(Locale.getDefault());
        int shown = 0;
        for (final String p : peers) {
            List<Msg> l = by.get(p);
            Msg last = l.get(l.size() - 1);
            String fn = last.from.equals(meId) ? last.toName : last.fromName;
            final Contact c = contactOf(p, fn);
            if (!q.isEmpty() && !c.name.toLowerCase(Locale.getDefault()).contains(q) && !preview(last).toLowerCase(Locale.getDefault()).contains(q)) continue;
            int unread = 0;
            for (Msg m : l) if (!m.from.equals(meId) && !m.read && !"call".equals(m.type)) unread++;
            listBox.addView(chatRow(c, last, unread));
            shown++;
        }
        if (shown == 0) {
            TextView e = Ui.tv(this, q.isEmpty() ? "No chats yet.\nTap ✉ to start a conversation." : "No results", 15, C_SUB, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(24), dp(80), dp(24), 0);
            listBox.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    private View chatRow(final Contact c, Msg last, int unread) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        Ui.pressFx(row);
        row.setOnClickListener(v -> openChat(c.id));
        row.addView(avatar(c.name, c.id, 52, 20), Ui.lpm(this, dp(52), dp(52), 0, 0, 14, 0));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(Ui.ellipsized(this, c.name, 16.5f, C_TEXT, true, 1));
        LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        sub.setGravity(Gravity.CENTER_VERTICAL);
        if (last.from.equals(meId)) {
            TextView tk = Ui.tv(this, last.read ? "✓✓ " : last.dl ? "✓✓ " : "✓ ", 13, last.read ? C_TICK : C_SUB, false);
            sub.addView(tk);
        }
        sub.addView(Ui.ellipsized(this, preview(last), 14, unread > 0 ? C_TEXT : C_SUB, false, 1), new LinearLayout.LayoutParams(0, WRAP, 1f));
        mid.addView(sub, Ui.lpm(this, MATCH, WRAP, 0, 3, 0, 0));
        row.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(Ui.tv(this, listTime(last.ts), 12, unread > 0 ? C_GREEN : C_SUB, false));
        if (unread > 0) {
            TextView bd = Ui.tv(this, String.valueOf(unread), 12, C_BG, true);
            bd.setGravity(Gravity.CENTER);
            bd.setBackground(Ui.bg(this, C_GREEN, 12, 0));
            bd.setMinWidth(dp(22));
            bd.setPadding(dp(6), dp(1), dp(6), dp(1));
            right.addView(bd, Ui.lpm(this, WRAP, WRAP, 0, 5, 0, 0));
        }
        row.addView(right);
        return row;
    }

    private void renderCallRows() {
        String q = query.trim().toLowerCase(Locale.getDefault());
        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < 80; i--) {
            final Msg m = all.get(i);
            if (!"call".equals(m.type)) continue;
            final boolean out = m.from.equals(meId);
            String peer = peerOf(m);
            final Contact c = contactOf(peer, out ? m.toName : m.fromName);
            if (!q.isEmpty() && !c.name.toLowerCase(Locale.getDefault()).contains(q)) continue;
            final boolean video = "video".equals(m.text);
            boolean missed = !out && m.ans != 1;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.addView(avatar(c.name, c.id, 52, 20), Ui.lpm(this, dp(52), dp(52), 0, 0, 14, 0));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.addView(Ui.ellipsized(this, c.name, 16.5f, missed ? C_RED : C_TEXT, true, 1));
            String st = (out ? "↗ " : missed ? "↙ Missed · " : "↙ ") + listTime(m.ts) + ", " + timeFmt.format(new Date(m.ts));
            mid.addView(Ui.tv(this, st, 13, C_SUB, false), Ui.lpm(this, WRAP, WRAP, 0, 3, 0, 0));
            row.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));
            TextView ci = iconBtn(video ? "🎥" : "📞", v -> callBack(c.id, video));
            row.addView(ci, new LinearLayout.LayoutParams(dp(44), dp(44)));
            listBox.addView(row);
            shown++;
        }
        if (shown == 0) {
            TextView e = Ui.tv(this, "No calls yet.\nTap 📞 to call a member.", 15, C_SUB, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(24), dp(80), dp(24), 0);
            listBox.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    // ── rendering: contact picker ────────────────────────────
    private View buildPicker() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(C_BG);
        top.setPadding(dp(4), dp(8), dp(8), dp(8));
        top.addView(iconBtn("←", v -> onBackPressed()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.tv(this, "Select contact", 18, C_TEXT, true));
        t.addView(Ui.tv(this, contacts.size() + " contacts", 12, C_SUB, false));
        top.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        root.addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));

        searchEt = new EditText(this);
        searchEt.setHint("Search name or number");
        searchEt.setHintTextColor(C_SUB);
        searchEt.setTextColor(C_TEXT);
        searchEt.setTextSize(15);
        searchEt.setSingleLine(true);
        searchEt.setBackground(Ui.bg(this, C_BAR, 22, 0));
        searchEt.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.addView(searchEt, Ui.lpm(this, MATCH, WRAP, 12, 0, 12, 8));

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        searchEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int s0, int c0, int a0) { }
            @Override public void onTextChanged(CharSequence c, int s0, int b0, int c0) { }
            @Override public void afterTextChanged(Editable e) { query = e.toString(); renderPickerList(); }
        });
        renderPickerList();
        return root;
    }

    private void renderPickerList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        String q = query.trim().toLowerCase(Locale.getDefault());
        int shown = 0;
        for (final Contact c : contacts) {
            if (!q.isEmpty() && !c.name.toLowerCase(Locale.getDefault()).contains(q) && !c.sub.toLowerCase(Locale.getDefault()).contains(q)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(9), dp(16), dp(9));
            Ui.pressFx(row);
            row.setOnClickListener(v -> openChat(c.id));
            row.addView(avatar(c.name, c.id, 46, 18), Ui.lpm(this, dp(46), dp(46), 0, 0, 14, 0));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.addView(Ui.ellipsized(this, c.name, 16, C_TEXT, true, 1));
            mid.addView(Ui.ellipsized(this, c.sub, 13, C_SUB, false, 1));
            row.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));
            if (tab == 1) {
                row.addView(iconBtn("🎥", v -> { openChat(c.id); startCall(true); }), new LinearLayout.LayoutParams(dp(44), dp(44)));
                row.addView(iconBtn("📞", v -> { openChat(c.id); startCall(false); }), new LinearLayout.LayoutParams(dp(44), dp(44)));
            }
            listBox.addView(row);
            shown++;
        }
        if (shown == 0) {
            TextView e = Ui.tv(this, contacts.isEmpty() ? "No contacts loaded yet.\nOpen the Native app once, then try again." : "No results", 15, C_SUB, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(24), dp(60), dp(24), 0);
            listBox.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    private void openChat(String peer) {
        curPeer = peer;
        screen = S_CHAT;
        searching = false;
        query = "";
        render();
        renderMessages(true);
        fetch();
    }

    // ── rendering: chat ──────────────────────────────────────
    private View buildChat() {
        final Contact c = contactOf(curPeer, "");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(C_BAR);
        head.setPadding(dp(4), dp(8), dp(8), dp(8));
        head.addView(iconBtn("←", v -> onBackPressed()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        head.addView(avatar(c.name, c.id, 40, 16), Ui.lpm(this, dp(40), dp(40), 0, 0, 10, 0));
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.addView(Ui.ellipsized(this, c.name, 16.5f, C_TEXT, true, 1));
        meta.addView(Ui.ellipsized(this, c.sub, 12, C_SUB, false, 1));
        head.addView(meta, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(iconBtn("🎥", v -> startCall(true)), new LinearLayout.LayoutParams(dp(42), dp(42)));
        head.addView(iconBtn("📞", v -> startCall(false)), new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        msgScroll = new ScrollView(this);
        msgScroll.setBackgroundColor(C_BG);
        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setPadding(dp(10), dp(10), dp(10), dp(10));
        msgScroll.addView(msgBox, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(msgScroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        upWrap = new LinearLayout(this);
        upWrap.setOrientation(LinearLayout.VERTICAL);
        upWrap.setBackgroundColor(C_BAR);
        upWrap.setPadding(dp(14), dp(8), dp(14), dp(8));
        upInfo = Ui.tv(this, "", 12, C_SUB, false);
        upWrap.addView(upInfo);
        upWrap.setVisibility(View.GONE);
        root.addView(upWrap, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM);
        bar.setBackgroundColor(C_BG);
        bar.setPadding(dp(8), dp(6), dp(8), dp(8));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.bg(this, C_BAR, 26, 0));
        TextView emoji = Ui.tv(this, "😊", 21, C_SUB, false);
        emoji.setGravity(Gravity.CENTER);
        pill.addView(emoji, new LinearLayout.LayoutParams(dp(42), dp(44)));
        input = new EditText(this);
        input.setHint("Message");
        input.setTextSize(16);
        input.setTextColor(C_TEXT);
        input.setHintTextColor(C_SUB);
        input.setMaxLines(5);
        input.setBackground(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(0, dp(10), 0, dp(10));
        pill.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView attach = Ui.tv(this, "📎", 20, C_SUB, false);
        attach.setGravity(Gravity.CENTER);
        Ui.pressFx(attach);
        attach.setOnClickListener(v -> attachMenu());
        pill.addView(attach, new LinearLayout.LayoutParams(dp(42), dp(44)));
        bar.addView(pill, new LinearLayout.LayoutParams(0, WRAP, 1f));

        sendBtn = Ui.tv(this, "🎤", 19, Color.WHITE, true);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setBackground(Ui.circle(C_GREEN));
        Ui.pressFx(sendBtn);
        sendBtn.setOnClickListener(v -> {
            if (input.getText().toString().trim().isEmpty()) startRecording(); else sendText();
        });
        bar.addView(sendBtn, Ui.lpm(this, dp(48), dp(48), 6, 0, 0, 0));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c0, int s0, int c1, int a0) { }
            @Override public void onTextChanged(CharSequence c0, int s0, int b0, int c1) { }
            @Override public void afterTextChanged(Editable e) { sendBtn.setText(e.toString().trim().isEmpty() ? "🎤" : "➤"); }
        });
        inputBar = bar;
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));

        recRow = new LinearLayout(this);
        recRow.setOrientation(LinearLayout.HORIZONTAL);
        recRow.setGravity(Gravity.CENTER_VERTICAL);
        recRow.setBackgroundColor(C_BG);
        recRow.setPadding(dp(8), dp(8), dp(10), dp(8));
        recRow.addView(iconBtn("🗑", v -> cancelRecording()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        recTimeTv = Ui.tv(this, "● 0:00", 16, C_RED, true);
        recTimeTv.setPadding(dp(10), 0, 0, 0);
        recRow.addView(recTimeTv, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView sendV = Ui.tv(this, "➤", 19, Color.WHITE, true);
        sendV.setGravity(Gravity.CENTER);
        sendV.setBackground(Ui.circle(C_GREEN));
        Ui.pressFx(sendV);
        sendV.setOnClickListener(v -> finishRecording());
        recRow.addView(sendV, new LinearLayout.LayoutParams(dp(48), dp(48)));
        recRow.setVisibility(View.GONE);
        root.addView(recRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        return root;
    }

    private void renderMessages(boolean toBottom) {
        if (msgBox == null) return;
        msgBox.removeAllViews();
        long lastDay = -1;
        boolean any = false;
        for (Msg m : all) {
            if (!peerOf(m).equals(curPeer)) continue;
            any = true;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(m.ts);
            long day = cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR);
            if (day != lastDay) {
                lastDay = day;
                TextView chip = Ui.tv(this, dayLabel(m.ts), 12, C_SUB, false);
                chip.setBackground(Ui.bg(this, C_CHIP, 8, 0));
                chip.setPadding(dp(12), dp(5), dp(12), dp(5));
                LinearLayout.LayoutParams lp = Ui.lpm(this, WRAP, WRAP, 0, 8, 0, 8);
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                msgBox.addView(chip, lp);
            }
            msgBox.addView(bubble(m));
        }
        if (!any) {
            TextView e = Ui.tv(this, "Say hi 👋\nMessages and calls here are between you and " + contactOf(curPeer, "").name + ".", 13, C_SUB, false);
            e.setGravity(Gravity.CENTER);
            e.setBackground(Ui.bg(this, C_CHIP, 10, 0));
            e.setPadding(dp(16), dp(10), dp(16), dp(10));
            LinearLayout.LayoutParams lp = Ui.lpm(this, WRAP, WRAP, 24, 20, 24, 0);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            msgBox.addView(e, lp);
        }
        if (toBottom && msgScroll != null) msgScroll.post(() -> msgScroll.fullScroll(View.FOCUS_DOWN));
    }

    private View bubble(final Msg m) {
        final boolean mine = m.from.equals(meId);
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(mine ? Gravity.END : Gravity.START);
        row.setPadding(0, dp(2), 0, dp(2));

        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(mine ? C_OUT : C_IN);
        float r = dp(10), s = dp(2);
        g.setCornerRadii(mine ? new float[]{r, r, s, s, r, r, r, r} : new float[]{s, s, r, r, r, r, r, r});
        b.setBackground(g);
        b.setPadding(dp(9), dp(6), dp(9), dp(5));

        switch (m.type) {
            case "image": {
                final ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0x22000000);
                int w = Math.min(maxW - dp(18), dp(240));
                b.addView(iv, new LinearLayout.LayoutParams(w, (int) (w * 1.0f)));
                loadBitmap(thumb(m.url), iv);
                iv.setOnClickListener(v -> openUrl(m.url));
                break;
            }
            case "audio": b.addView(audioRow(m, mine)); break;
            case "video": b.addView(fileRow("🎬", m.fileName.isEmpty() ? "Video" : m.fileName, m.url, maxW)); break;
            case "file": b.addView(fileRow("📎", m.fileName.isEmpty() ? "File" : m.fileName, m.url, maxW)); break;
            case "call": b.addView(callRow(m, mine)); break;
            default: {
                TextView t = Ui.tv(this, m.text, 15.5f, C_TEXT, false);
                t.setMaxWidth(maxW);
                t.setTextIsSelectable(false);
                b.addView(t);
            }
        }

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        meta.addView(Ui.tv(this, timeFmt.format(new Date(m.ts)), 11, mine ? 0xFFA8C5BC : C_SUB, false));
        if (mine) {
            boolean pend = pending.containsKey(m.id);
            TextView tk = Ui.tv(this, pend ? " 🕓" : (m.read || m.dl) ? " ✓✓" : " ✓", 12, m.read ? C_TICK : 0xFFA8C5BC, false);
            meta.addView(tk);
        }
        b.addView(meta, new LinearLayout.LayoutParams(MATCH, WRAP));
        row.addView(b, new LinearLayout.LayoutParams(WRAP, WRAP));
        return row;
    }

    private View fileRow(String icon, String name, final String url, int maxW) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.bg(this, 0x33000000, 8, 0));
        r.setPadding(dp(10), dp(8), dp(12), dp(8));
        r.addView(Ui.tv(this, icon, 24, C_TEXT, false), Ui.lpm(this, WRAP, WRAP, 0, 0, 10, 0));
        TextView n = Ui.ellipsized(this, name, 14, C_TEXT, false, 2);
        n.setMaxWidth(maxW - dp(90));
        r.addView(n);
        Ui.pressFx(r);
        r.setOnClickListener(v -> openUrl(url));
        return r;
    }

    private View callRow(Msg m, boolean mine) {
        boolean video = "video".equals(m.text);
        boolean missed = !mine && m.ans != 1;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = Ui.tv(this, video ? "🎥" : "📞", 18, Color.WHITE, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.circle(missed ? C_RED : C_GREEN));
        r.addView(ic, Ui.lpm(this, dp(38), dp(38), 0, 0, 10, 0));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        String title = (video ? "Video call" : "Voice call");
        String sub = mine ? (m.ans == 2 ? "Declined" : m.ans == 1 ? "Answered" : m.ans == 3 ? "No answer" : "Outgoing") : (missed ? "Missed" : "Answered");
        t.addView(Ui.tv(this, title, 15, C_TEXT, true));
        t.addView(Ui.tv(this, sub, 12.5f, mine ? 0xFFA8C5BC : C_SUB, false));
        r.addView(t);
        r.setMinimumWidth(dp(170));
        return r;
    }

    private View audioRow(final Msg m, boolean mine) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        final boolean on = m.id.equals(playingId);
        TextView play = Ui.tv(this, on ? "⏸" : "▶", 22, C_TEXT, false);
        play.setGravity(Gravity.CENTER);
        Ui.pressFx(play);
        play.setOnClickListener(v -> togglePlay(m));
        r.addView(play, new LinearLayout.LayoutParams(dp(40), dp(40)));
        View line = new View(this);
        line.setBackgroundColor(mine ? 0x66FFFFFF : 0x558696A0);
        r.addView(line, Ui.lpm(this, dp(110), dp(3), 6, 0, 8, 0));
        long s = m.dur / 1000;
        r.addView(Ui.tv(this, m.dur > 0 ? String.format(Locale.US, "%d:%02d", s / 60, s % 60) : "🎤", 12.5f, mine ? 0xFFA8C5BC : C_SUB, false));
        return r;
    }

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("Cannot open this file"); }
    }

    // ── images ───────────────────────────────────────────────
    private static String thumb(String url) {
        if (url == null || !url.contains("res.cloudinary.com") || !url.contains("/upload/")) return url;
        return url.replace("/upload/", "/upload/w_500,c_limit,q_auto/");
    }

    private void loadBitmap(final String url, final ImageView iv) {
        if (url == null || url.isEmpty()) return;
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

    // ── attachments ──────────────────────────────────────────
    private void attachMenu() {
        if (uploading) { toast("Please wait for the current upload"); return; }
        final Ui.Modal m = new Ui.Modal(this, "Share", "Photos, videos, audio or any file (up to 100 MB).");
        m.col("🖼  Photo", Ui.B_GHOST, v -> { m.dismiss(); pick("image/*"); });
        m.col("🎬  Video", Ui.B_GHOST, v -> { m.dismiss(); pick("video/*"); });
        m.col("🎵  Audio", Ui.B_GHOST, v -> { m.dismiss(); pick("audio/*"); });
        m.col("📎  Document", Ui.B_GHOST, v -> { m.dismiss(); pick("*/*"); });
        m.col("Cancel", Ui.B_OUTLINE, v -> m.dismiss());
        m.show();
    }

    private void pick(String mime) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(mime);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "Choose"), REQ_PICK); }
        catch (Exception e) { toast("No file picker found"); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Cloud.Meta meta = Cloud.meta(this, uri);
        upload(uri, meta, false, 0);
    }

    private void showUp(boolean on, String info) {
        if (upWrap == null) return;
        upWrap.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) upInfo.setText(info);
    }

    private static String typeOf(String mime) {
        if (mime == null) return "file";
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        return "file";
    }

    private void upload(final Uri uri, final Cloud.Meta meta, final boolean voice, final long durMs) {
        if (uploading) return;
        if (meta.size > Cloud.MAX_BYTES) { toast("File is too large (max 100 MB)"); return; }
        uploading = true;
        final String peerAtStart = curPeer;
        showUp(true, "Uploading " + meta.name + "…");
        Store.async(() -> Cloud.upload(this, uri, meta, pct -> h.post(() -> showUp(true, "Uploading " + meta.name + "  " + pct + "%"))),
            (Cloud.Result r, Exception e) -> {
                uploading = false;
                if (released) return;
                showUp(false, "");
                if (voice && recFile != null) { try { recFile.delete(); } catch (Exception ignored) { } }
                if (e != null || r == null) {
                    toast("Upload failed: " + (e == null || e.getMessage() == null ? "try again" : e.getMessage()));
                    return;
                }
                String keep = curPeer;
                curPeer = peerAtStart;
                Map<String, Object> ex = null;
                if (voice) { ex = new LinkedHashMap<>(); ex.put("dur", Long.valueOf(durMs)); }
                String type = voice ? "audio" : typeOf(r.mime.isEmpty() ? meta.mime : r.mime);
                send(type, "", r.url, voice ? "Voice message" : r.name, ex);
                curPeer = keep;
            });
    }

    // ── voice notes ──────────────────────────────────────────
    private void startRecording() {
        if (recording || uploading) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        try {
            recFile = new File(getCacheDir(), "dm_voice_" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(32000);
            recorder.setAudioEncodingBitRate(48000);
            recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recStart = System.currentTimeMillis();
            if (inputBar != null) inputBar.setVisibility(View.GONE);
            if (recRow != null) recRow.setVisibility(View.VISIBLE);
            h.post(recTick);
        } catch (Exception e) {
            releaseRecorder();
            toast("Cannot start recording");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startRecording();
    }

    private void releaseRecorder() {
        recording = false;
        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) { }
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        if (inputBar != null) inputBar.setVisibility(View.VISIBLE);
        if (recRow != null) recRow.setVisibility(View.GONE);
    }

    private void cancelRecording() {
        releaseRecorder();
        try { if (recFile != null) recFile.delete(); } catch (Exception ignored) { }
    }

    private void finishRecording() {
        if (!recording) return;
        final long dur = System.currentTimeMillis() - recStart;
        releaseRecorder();
        if (recFile == null || dur < 700) { cancelRecording(); return; }
        Cloud.Meta meta = new Cloud.Meta();
        meta.name = "voice_" + System.currentTimeMillis() + ".m4a";
        meta.mime = "audio/mp4";
        meta.size = recFile.length();
        upload(Uri.fromFile(recFile), meta, true, dur);
    }

    private void togglePlay(Msg m) {
        if (m.id.equals(playingId)) { stopPlayer(); renderMessages(false); return; }
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(m.url);
            player.setOnPreparedListener(mp -> mp.start());
            player.setOnCompletionListener(mp -> { stopPlayer(); if (screen == S_CHAT) renderMessages(false); });
            player.setOnErrorListener((mp, w, x) -> { stopPlayer(); toast("Cannot play this audio"); return true; });
            playingId = m.id;
            player.prepareAsync();
            renderMessages(false);
        } catch (Exception e) {
            stopPlayer();
            toast("Cannot play this audio");
        }
    }

    private void stopPlayer() {
        playingId = "";
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) { }
        player = null;
    }
}
