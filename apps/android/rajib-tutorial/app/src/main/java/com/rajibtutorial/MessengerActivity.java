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
    private static volatile MessengerActivity LIVE;

    /** FCM says something new arrived: fetch now instead of waiting for the next poll. */
    static void poke() {
        final MessengerActivity a = LIVE;
        if (a == null) return;
        a.h.post(() -> { a.typingFrom = ""; a.refreshTyping(); a.fetch(); });
        a.h.postDelayed(a::fetch, 700);        // in case the first fetch was already running
    }

    /** Live signal from the other phone (FCM): "typing", or "read" / "upd" = something changed on the server. */
    static void onSignal(final String sender, final String what) {
        final MessengerActivity a = LIVE;
        if (a == null) return;
        a.h.post(() -> a.gotSignal(sender, what));
    }
    static volatile String CUR_PEER = "";
    static final String EXTRA_PEER = "peer";

    private static final String PREFIX = "_tm_";      // Telephone messages (own namespace)
    private static final String USER_PREFIX = "_tel_";  // Telephone registered numbers
    private static final long POLL_MS = 4000;
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
        boolean read = false, dl = false, del = false;
        String rt = "", rn = "";   // reply quote: text + sender name
        boolean fw = false;        // forwarded
        Map<String, String> reacts = new LinkedHashMap<>();   // person -> emoji
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
    private final Map<String, Fs.Doc> cache = new HashMap<>();
    private final Set<String> hidden = new HashSet<>();   // "delete for me" (this phone only)
    private final Set<String> pins = new HashSet<>();     // pinned chats (this phone only)
    private final Set<String> starred = new HashSet<>();  // starred messages (this phone only)
    private Msg replyTo = null;
    private LinearLayout replyBar;
    private TextView replyTv;
    private boolean saveQueued = false;
    private long lastBeat = 0, presFetchAt = 0, peerSeenTs = 0, typingAt = 0, typingSentAt = 0;
    private boolean peerOn = false;
    private String typingFrom = "";
    private TextView peerSub;
    private long lastFull = 0, lastOk = 0, contactsAt = 0;
    private String netError = "";
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
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastBeat > 45000) { lastBeat = nowMs; beat(true); }
            if (screen == S_CHAT && nowMs - presFetchAt > 15000) loadPeerPresence();
            fetch();
            h.postDelayed(this, screen == S_CHAT ? POLL_MS : POLL_MS * 2);
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

        // Telephone has its OWN login: just a phone number (no Rajib Tutorial member account involved)
        meId = getSharedPreferences("rt_tel", MODE_PRIVATE).getString("num", "");
        meName = meId;

        body = new FrameLayout(this);
        body.setBackgroundColor(C_BG);
        setContentView(body);

        if (meId.isEmpty()) { renderLogin(); return; }
        startSession();
    }

    private void startSession() {
        try { PushManager.setDm(getApplicationContext(), true, meId, meName); } catch (Throwable ignored) { }
        loadLocalSets();
        loadDiskCache();          // chat list is shown at once from the copy saved on this phone, then refreshed online
        buildContacts();
        applyCached();
        register();
        render();
        handlePeerIntent(getIntent());
        ensureCallPermissions();
        polling = true;
        h.removeCallbacks(pollTask);
        h.post(pollTask);
    }

    private final List<String> regUsers = new ArrayList<>();

    /** Puts my number on the user list (every start, so a failed first attempt repairs itself). */
    private void register() {
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("appId", DiscussionScreen.SYNC_APP_ID);
        d.put("number", meId);
        d.put("ts", Long.valueOf(System.currentTimeMillis()));
        final String id = USER_PREFIX + meId;
        Store.async(() -> { Fs.set(id, d); return Boolean.TRUE; }, (Boolean ok, Exception e) -> {
            if (released) return;
            if (e != null) setError(Fs.explain(e));
            loadContacts();
        });
    }

    /** Numbers that have joined Telephone (documents "_tel_<number>"). */
    private void loadContacts() {
        contactsAt = System.currentTimeMillis();
        Store.async(() -> Fs.prefixAsc(USER_PREFIX, 500), (List<Fs.Doc> docs, Exception e) -> {
            if (released) return;
            if (e != null || docs == null) { setError(Fs.explain(e)); return; }
            regUsers.clear();
            for (Fs.Doc d : docs) {
                String n = d.id.substring(USER_PREFIX.length());
                if (!n.isEmpty() && !regUsers.contains(n)) regUsers.add(n);
            }
            buildContacts();
            scheduleSave();
            if (screen == S_PICK) renderPickerList();
            else if (screen == S_HOME) renderHomeList();
        });
    }

    // ── local copy (WhatsApp-style: the list never waits for the network) ──
    private java.io.File cacheFile() { return new java.io.File(getFilesDir(), "tel_cache_" + meId + ".json"); }

    private void loadLocalSets() {
        android.content.SharedPreferences sp = getSharedPreferences("rt_tel", MODE_PRIVATE);
        pins.clear();
        pins.addAll(sp.getStringSet("pins_" + meId, new HashSet<String>()));
        hidden.clear();
        hidden.addAll(sp.getStringSet("hidden_" + meId, new HashSet<String>()));
        starred.clear();
        starred.addAll(sp.getStringSet("stars_" + meId, new HashSet<String>()));
    }

    private void saveLocalSets() {
        getSharedPreferences("rt_tel", MODE_PRIVATE).edit()
            .putStringSet("pins_" + meId, new HashSet<>(pins))
            .putStringSet("hidden_" + meId, new HashSet<>(hidden))
            .putStringSet("stars_" + meId, new HashSet<>(starred)).apply();
    }

    private void loadDiskCache() {
        try {
            java.io.File f = cacheFile();
            if (!f.exists() || f.length() == 0) return;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int o = 0;
                while (o < b.length) { int r = in.read(b, o, b.length - o); if (r < 0) break; o += r; }
            } finally { in.close(); }
            org.json.JSONObject j = new org.json.JSONObject(new String(b, "UTF-8"));
            org.json.JSONArray us = j.optJSONArray("users");
            if (us != null) for (int i = 0; i < us.length(); i++) {
                String n = us.optString(i);
                if (!n.isEmpty() && !regUsers.contains(n)) regUsers.add(n);
            }
            org.json.JSONArray ds = j.optJSONArray("docs");
            if (ds != null) for (int i = 0; i < ds.length(); i++) {
                org.json.JSONObject o = ds.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                org.json.JSONObject dj = o.optJSONObject("data");
                if (id.isEmpty() || dj == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                java.util.Iterator<String> it = dj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = dj.opt(k);
                    if (v instanceof String || v instanceof Number || v instanceof Boolean) m.put(k, v);
                }
                cache.put(id, new Fs.Doc(id, m));
            }
        } catch (Throwable ignored) { }
    }

    /** Messages from the saved copy, so the very first screen already has the chat list. */
    private void applyCached() {
        List<Msg> list = new ArrayList<>();
        for (Fs.Doc d : cache.values()) {
            if (!d.id.startsWith(PREFIX)) continue;
            Msg m = toMsg(d);
            if (!m.from.equals(meId) && !m.to.equals(meId)) continue;
            if (hidden.contains(m.id)) continue;
            list.add(m);
        }
        Collections.sort(list, (x, y) -> x.id.compareTo(y.id));
        all = list;
    }

    private void scheduleSave() {
        if (saveQueued) return;
        saveQueued = true;
        h.postDelayed(() -> { saveQueued = false; saveDiskCache(); }, 1500);
    }

    private void saveDiskCache() {
        if (meId.isEmpty()) return;
        final List<Fs.Doc> docs = new ArrayList<>(cache.values());
        final List<String> us = new ArrayList<>(regUsers);
        final java.io.File f = cacheFile();
        new Thread(() -> {
            try {
                Collections.sort(docs, (a, b2) -> Long.compare(num(b2.data, "ts"), num(a.data, "ts")));
                org.json.JSONArray ds = new org.json.JSONArray();
                for (int i = 0; i < docs.size() && i < 800; i++) {
                    org.json.JSONObject dj = new org.json.JSONObject();
                    for (Map.Entry<String, Object> en : docs.get(i).data.entrySet()) {
                        Object v = en.getValue();
                        if (v instanceof String || v instanceof Number || v instanceof Boolean) dj.put(en.getKey(), v);
                    }
                    ds.put(new org.json.JSONObject().put("id", docs.get(i).id).put("data", dj));
                }
                org.json.JSONObject j = new org.json.JSONObject().put("users", new org.json.JSONArray(us)).put("docs", ds);
                java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                try { out.write(j.toString().getBytes("UTF-8")); } finally { out.close(); }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
            } catch (Throwable ignored) { }
        }, "tel-cache").start();
    }

    /** Number-only login: remembers the number; {@link #register} lists it so others can find / call it. */
    private void doLogin(String raw) {
        final String num = PushManager.normMobile(raw);
        if (num.length() < 10) { toast("Enter a valid phone number"); return; }
        hideKeyboard();
        getSharedPreferences("rt_tel", MODE_PRIVATE).edit().putString("num", num).apply();
        meId = num;
        meName = num;
        all = new ArrayList<>();
        pending.clear();
        cache.clear();
        lastFull = 0;
        screen = S_HOME;
        tab = 0;
        startSession();
    }

    private void logout() {
        try { PushManager.setDm(getApplicationContext(), false, "", ""); } catch (Throwable ignored) { }
        RelayAuth.clear(this);
        getSharedPreferences("rt_tel", MODE_PRIVATE).edit().remove("num").apply();
        RelayCall.updateNotice(this);
        polling = false;
        h.removeCallbacks(pollTask);
        meId = "";
        meName = "";
        all = new ArrayList<>();
        pending.clear();
        acked.clear();
        handledCalls.clear();
        regUsers.clear();
        cache.clear();
        lastFull = 0;
        netError = "";
        buildContacts();
        screen = S_HOME;
        curPeer = "";
        CUR_PEER = "";
        lastSig = "";
        body.removeAllViews();
        renderLogin();
    }

    /** Once per install: allow notifications + the full-screen call screen so calls ring with the phone locked. */
    private void ensureCallPermissions() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("rt_native", MODE_PRIVATE);
            if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4103);
            }
            if (android.os.Build.VERSION.SDK_INT >= 34 && System.currentTimeMillis() - sp.getLong("dm_fsi_ts", 0) > 24L * 3600 * 1000) {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && !nm.canUseFullScreenIntent()) {
                    sp.edit().putLong("dm_fsi_ts", System.currentTimeMillis()).apply();
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
        LIVE = this;
        if (meId.isEmpty()) return;
        cancelUnansweredCall();
        if (System.currentTimeMillis() - contactsAt > 60000) loadContacts();
        polling = true;
        h.removeCallbacks(pollTask);
        h.post(pollTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ON_SCREEN = false;
        if (LIVE == this) LIVE = null;
        if (!meId.isEmpty()) { saveDiskCache(); beat(false); lastBeat = 0; }
        polling = false;
        h.removeCallbacks(pollTask);
        stopPlayer();
        if (recording) cancelRecording();
    }

    @Override
    protected void onDestroy() {
        ON_SCREEN = false;
        if (LIVE == this) LIVE = null;
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
        for (String n : regUsers) {
            if (n.isEmpty() || n.equals(meId) || byId.containsKey(n)) continue;
            add(new Contact(n, n, "On Telephone"));
        }
        Collections.sort(contacts, (x, y) -> x.name.compareTo(y.name));
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
        if (m.del) return "🚫 This message was deleted";
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
    /**
     * Reads as little as possible (the free server plan counts every document read): all of my messages once,
     * afterwards only documents changed in the last three minutes ("up" = time of the last change).
     */
    private void fetch() {
        if (released || fetching || meId.isEmpty()) return;
        fetching = true;
        final long now = System.currentTimeMillis();
        final boolean full = lastFull == 0 || now - lastOk > 90000 || now - lastFull > 15 * 60 * 1000L;
        final String who = meId;
        Store.async(() -> {
            List<Fs.Doc> out = new ArrayList<>();
            if (full) {
                out.addAll(Fs.query("tutorial_courses", "from", "EQUAL", who, null, false, 0));
                out.addAll(Fs.query("tutorial_courses", "to", "EQUAL", who, null, false, 0));
            } else {
                out.addAll(Fs.query("tutorial_courses", "up", "GREATER_THAN", Long.valueOf(now - 180000), null, false, 0));
            }
            return out;
        }, (List<Fs.Doc> docs, Exception e) -> {
            fetching = false;
            if (released || !who.equals(meId)) return;
            if (e != null || docs == null) { setError(Fs.explain(e)); return; }
            if (full) { cache.clear(); lastFull = now; }
            lastOk = now;
            boolean dirty = full;
            for (Fs.Doc d : docs) {
                if (!d.id.startsWith(PREFIX)) continue;
                Fs.Doc old = cache.put(d.id, d);
                if (old == null || num(old.data, "up") != num(d.data, "up")) dirty = true;
            }
            if (dirty) scheduleSave();
            setError("");
            applyDocs(new ArrayList<>(cache.values()));
        });
    }

    private void setError(String msg) {
        if (msg.equals(netError)) return;
        netError = msg;
        if (screen == S_HOME) renderHomeList();
        else if (screen == S_PICK) renderPickerList();
        else if (!msg.isEmpty()) toast(msg);
    }

    private View errorBanner() {
        TextView t = Ui.tv(this, "⚠️  " + netError, 13.5f, C_TEXT, false);
        t.setBackground(Ui.bg(this, 0xFF5C2B29, 10, 0));
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setOnClickListener(v -> { lastFull = 0; fetch(); loadContacts(); });
        t.setLayoutParams(Ui.lpm(this, MATCH, WRAP, 12, 6, 12, 6));
        return t;
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
        m.del = num(d.data, "del") == 1;
        m.rt = str(d.data, "rt");
        m.rn = str(d.data, "rn");
        m.fw = num(d.data, "fw") == 1;
        for (Map.Entry<String, Object> en : d.data.entrySet()) {
            if (en.getKey().startsWith("r_") && en.getValue() instanceof String && !((String) en.getValue()).isEmpty()) {
                m.reacts.put(en.getKey().substring(2), (String) en.getValue());
            }
        }
        return m;
    }

    private void applyDocs(List<Fs.Doc> docs) {
        List<Msg> list = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Fs.Doc d : docs) {
            if (!d.id.startsWith(PREFIX)) continue;
            Msg m = toMsg(d);
            if (!m.from.equals(meId) && !m.to.equals(meId)) continue;
            if (hidden.contains(m.id)) continue;
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
        for (Msg m : list) sb.append(m.read ? 'r' : m.dl ? 'd' : 's').append(m.ans).append(m.del ? 'x' : '-').append(m.reacts.hashCode());
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
        final Set<String> senders = new HashSet<>();
        for (Msg m : all) for (String[] t : todo) if (t[0].equals(m.id)) senders.add(m.from);
        Store.async(() -> {
            for (String[] t : todo) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("dl", Long.valueOf(1));
                d.put("up", Long.valueOf(System.currentTimeMillis()));
                if ("r".equals(t[1])) d.put("read", Long.valueOf(1));
                try { Fs.update(t[0], d); } catch (Exception ignored) { }
            }
            return Boolean.TRUE;
        }, (Boolean ok, Exception e) -> { for (String sdr : senders) pushSig(sdr, "read"); });
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
        d.put("up", Long.valueOf(ts));
        if (extra != null) d.putAll(extra);

        Msg pm = new Msg();
        pm.id = id; pm.from = meId; pm.fromName = meName; pm.to = curPeer; pm.type = type;
        pm.text = text; pm.url = url; pm.fileName = fileName; pm.ts = ts;
        pm.room = extra != null && extra.get("room") instanceof String ? (String) extra.get("room") : "";
        pm.rt = extra != null && extra.get("rt") instanceof String ? (String) extra.get("rt") : "";
        pm.rn = extra != null && extra.get("rn") instanceof String ? (String) extra.get("rn") : "";
        pm.fw = extra != null && extra.get("fw") instanceof Number;
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
        Map<String, Object> ex = null;
        if (replyTo != null) {
            ex = new LinkedHashMap<>();
            String pv = preview(replyTo);
            ex.put("rt", pv.length() > 120 ? pv.substring(0, 120) : pv);
            ex.put("rn", replyTo.from.equals(meId) ? "You" : contactOf(replyTo.from, replyTo.fromName).name);
            setReply(null);
        }
        send("text", t, "", "", ex);
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
                u.put("up", Long.valueOf(System.currentTimeMillis()));
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
            d.put("up", Long.valueOf(System.currentTimeMillis()));
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
        replyBar = null; replyTv = null; peerSub = null;
        if (screen != S_CHAT) replyTo = null;
        lastSig = "";
        CUR_PEER = screen == S_CHAT ? curPeer : "";
        if (screen == S_CHAT) body.addView(buildChat(), new FrameLayout.LayoutParams(MATCH, MATCH));
        else if (screen == S_PICK) body.addView(buildPicker(), new FrameLayout.LayoutParams(MATCH, MATCH));
        else body.addView(buildHome(), new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void hideKeyboardSafe() { if (body != null && body.getChildCount() > 0) hideKeyboard(); }

    private void renderLogin() {
        body.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(32), dp(90), dp(32), 0);
        TextView e = Ui.tv(this, "💬", 56, C_TEXT, false);
        e.setGravity(Gravity.CENTER);
        c.addView(e);
        TextView t = Ui.tv(this, "Welcome to Telephone", 24, C_TEXT, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t, Ui.lpm(this, WRAP, WRAP, 0, 14, 0, 8));
        TextView s = Ui.tv(this, "Enter your phone number to start chatting and calling.", 14, C_SUB, false);
        s.setGravity(Gravity.CENTER);
        c.addView(s);
        final EditText num = new EditText(this);
        num.setHint("Phone number");
        num.setHintTextColor(C_SUB);
        num.setTextColor(C_TEXT);
        num.setTextSize(20);
        num.setSingleLine(true);
        num.setInputType(InputType.TYPE_CLASS_PHONE);
        num.setGravity(Gravity.CENTER);
        num.setBackground(Ui.bg(this, C_BAR, 14, 0));
        num.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.addView(num, Ui.lpm(this, MATCH, WRAP, 0, 32, 0, 0));
        TextView b = Ui.tv(this, "Next", 16, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.bg(this, C_GREEN, 26, 0));
        b.setPadding(dp(28), dp(14), dp(28), dp(14));
        Ui.pressFx(b);
        b.setOnClickListener(v -> doLogin(num.getText().toString()));
        c.addView(b, Ui.lpm(this, MATCH, WRAP, 0, 22, 0, 0));
        num.setOnEditorActionListener((v, a, ev) -> { doLogin(num.getText().toString()); return true; });
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
        final Ui.Modal m = new Ui.Modal(this, "Telephone", "Your number: " + meId);
        m.col("✉  New chat", Ui.B_GHOST, v -> { m.dismiss(); screen = S_PICK; query = ""; render(); });
        m.col("⭐  Starred messages", Ui.B_GHOST, v -> { m.dismiss(); starredMenu(); });
        m.col("🔄  Refresh", Ui.B_GHOST, v -> { m.dismiss(); lastSig = ""; lastFull = 0; fetch(); loadContacts(); });
        m.col("🔗  Pair relay phone (Phone A)", Ui.B_GHOST, v -> { m.dismiss(); pairMenu(); });
        m.col("📲  Call relay setup (Phone B)", Ui.B_GHOST, v -> { m.dismiss(); relayMenu(); });
        m.col(hideSeen() ? "👁  Show my online & last seen" : "🙈  Hide my online & last seen", Ui.B_GHOST, v -> {
            m.dismiss();
            getSharedPreferences("rt_tel", MODE_PRIVATE).edit().putBoolean("hide_seen", !hideSeen()).apply();
            beat(true);
            toast(hideSeen() ? "Others can no longer see when you are online" : "Others can see when you are online");
        });
        m.col("🚪  Log out", Ui.B_RED, v -> { m.dismiss(); logout(); });
        m.col("Close", Ui.B_OUTLINE, v -> m.dismiss());
        m.show();
    }

    // ── Call relay: Phone B shares its SIM calls with the paired Phone A ──
    private void relayMenu() {
        final android.content.SharedPreferences sp = getSharedPreferences(CallRelayReceiver.PREFS, MODE_PRIVATE);
        final boolean on = sp.getBoolean(CallRelayReceiver.K_ON, false);
        final String to = sp.getString(CallRelayReceiver.K_TO, "");
        final boolean acc = accessibilityOn();
        final Ui.Modal m = new Ui.Modal(this, "Call relay (this is Phone B)",
            "When a SIM call rings here, Phone A rings with the caller's number. Phone A can receive it (experimental loudspeaker bridge), cut it, or send an SMS. "
            + "Keep this phone charged and online.");
        m.col("1.  Alert number (Phone A): " + (to.isEmpty() ? "not set" : to), Ui.B_GHOST, v -> { m.dismiss(); askRelayNumber(sp); });
        m.col("2.  Pairing code: " + RelayAuth.pretty(RelayAuth.myCode(this)), Ui.B_GHOST, v -> { m.dismiss(); codeMenu(); });
        m.col("3.  Allow permissions" + (relayPermsMissing().isEmpty() ? "  ✅" : ""), Ui.B_GHOST, v -> { m.dismiss(); askRelayPermissions(); });
        m.col("4.  Accessibility service" + (acc ? "  ✅" : "  (off)"), Ui.B_GHOST, v -> {
            m.dismiss();
            openSettings(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("Turn on “Rajib Tutorial — Telephone call bridge”");
        });
        m.col("5.  Battery: allow in background", Ui.B_GHOST, v -> {
            m.dismiss();
            openSettings(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            toast("Battery → Unrestricted");
        });
        m.col(on ? "✅  Relay is ON (tap to turn off)" : "⭕  Relay is OFF (tap to turn on)", on ? Ui.B_GREEN : Ui.B_PRIMARY, v -> {
            m.dismiss();
            if (on) { setRelay(false); relayMenu(); return; }
            if (to.isEmpty()) { toast("Set the alert number first"); relayMenu(); return; }
            new android.app.AlertDialog.Builder(this)
                .setTitle("Share this phone's calls?")
                .setMessage("Every caller on this phone will be sent to " + to + ". The paired phone can answer and hear those calls, end them and send SMS to the caller. "
                    + "Only turn this on for your own phones.")
                .setPositiveButton("Turn on", (d, w) -> { setRelay(true); askRelayPermissions(); })
                .setNegativeButton("Cancel", null)
                .show();
        });
        m.col("🧪  Send a test ring to Phone A", Ui.B_GHOST, v -> {
            m.dismiss();
            if (!RelayCall.enabled(this)) { toast("Turn the relay ON first"); relayMenu(); return; }
            RelayCall.testRing(this);
            toast("Sending… open this menu again to see the result");
        });
        m.col("📋  What happened (log)", Ui.B_GHOST, v -> {
            m.dismiss();
            final Ui.Modal lm = new Ui.Modal(this, "Relay log", RelayCall.readLog(this));
            lm.col("Back", Ui.B_OUTLINE, x -> { lm.dismiss(); relayMenu(); });
            lm.show();
        });
        m.col("Close", Ui.B_OUTLINE, v -> m.dismiss());
        m.show();
    }

    private void setRelay(boolean on) {
        getSharedPreferences(CallRelayReceiver.PREFS, MODE_PRIVATE).edit().putBoolean(CallRelayReceiver.K_ON, on).apply();
        RelayCall.updateNotice(this);
    }

    private void openSettings(Intent i) {
        try { startActivity(i); } catch (Exception e) { toast("Open the phone's Settings app"); }
    }

    private boolean accessibilityOn() {
        if (TelBridgeService.get() != null) return true;
        String list = android.provider.Settings.Secure.getString(getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && list.contains(TelBridgeService.class.getSimpleName());
    }

    private void codeMenu() {
        final String code = RelayAuth.pretty(RelayAuth.myCode(this));
        final Ui.Modal m = new Ui.Modal(this, "Pairing code", code + "\n\nOn Phone A: Telephone ⋮ → Pair relay phone. Enter this phone's number (" + meId + ") and this code. Do not share it with anyone else.");
        m.col("📋  Copy", Ui.B_GHOST, v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("pairing code", code));
            toast("Copied");
        });
        m.col("🔄  New code (old pairing stops working)", Ui.B_GHOST, v -> { m.dismiss(); RelayAuth.newCode(this); codeMenu(); });
        m.col("Back", Ui.B_OUTLINE, v -> { m.dismiss(); relayMenu(); });
        m.show();
    }

    /** Phone A: shows saved relay phones and adds / removes them. */
    private void pairMenu() {
        final List<String> saved = RelayAuth.paired(this);
        final Ui.Modal m = new Ui.Modal(this, "Pair relay phone (this is Phone A)",
            saved.isEmpty() ? "No relay phone is paired yet." : "Saved. Tap a phone to remove it.");
        for (final String n : saved) {
            m.col("✅  Paired: " + n + "   ·   code …" + RelayAuth.tail(RelayAuth.pairedCode(this, n)), Ui.B_GREEN, v -> {
                m.dismiss();
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Remove " + n + "?")
                    .setPositiveButton("Remove", (d, w) -> { RelayAuth.unpair(this, n); pairMenu(); })
                    .setNegativeButton("Keep", (d, w) -> pairMenu())
                    .show();
            });
        }
        m.col("➕  Add / change Phone B", Ui.B_PRIMARY, v -> { m.dismiss(); pairForm(); });
        m.col("Close", Ui.B_OUTLINE, v -> m.dismiss());
        m.show();
    }

    private void pairForm() {
        final Ui.Modal md = new Ui.Modal(this, "Add Phone B", "Enter Phone B's Telephone number and the pairing code shown on Phone B.");
        final Ui.Field num = Ui.field(this, "Phone B number", "01XXXXXXXXX", InputType.TYPE_CLASS_PHONE);
        final Ui.Field code = Ui.field(this, "Pairing code", "XXXX-XXXX-XXXX-XXXX", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        md.add(num.root);
        md.add(code.root);
        final TextView err = md.errorView();
        md.addSpaced(err, 8);
        md.actions("Cancel", "Save", Ui.B_PRIMARY, () -> {
            String b = PushManager.normMobile(num.text());
            if (b.length() < 10 || b.equals(meId)) { Ui.setErr(err, "Enter Phone B's number (not this phone's)"); return; }
            if (RelayAuth.norm(code.text()).length() != 16) { Ui.setErr(err, "The code has 16 letters and digits"); return; }
            RelayAuth.pair(this, b, code.text());
            md.dismiss();
            pairMenu();
        });
        md.show();
    }

    private void askRelayNumber(final android.content.SharedPreferences sp) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_PHONE);
        et.setHint("Phone A's Telephone number");
        et.setText(sp.getString(CallRelayReceiver.K_TO, ""));
        new android.app.AlertDialog.Builder(this)
            .setTitle("Alert number")
            .setMessage("The number you log in with on Phone A.")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String n = PushManager.normMobile(et.getText().toString());
                if (n.length() < 10 || n.equals(meId)) toast("Enter Phone A's number");
                else { sp.edit().putString(CallRelayReceiver.K_TO, n).apply(); RelayCall.updateNotice(this); }
                relayMenu();
            })
            .setNegativeButton("Cancel", (d, w) -> relayMenu())
            .show();
    }

    private List<String> relayPermsMissing() {
        List<String> all = new ArrayList<>();
        all.add(Manifest.permission.READ_PHONE_STATE);
        all.add(Manifest.permission.READ_CALL_LOG);
        all.add(Manifest.permission.RECORD_AUDIO);
        all.add(Manifest.permission.SEND_SMS);
        if (android.os.Build.VERSION.SDK_INT >= 26) all.add(Manifest.permission.ANSWER_PHONE_CALLS);
        if (android.os.Build.VERSION.SDK_INT >= 33) all.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> need = new ArrayList<>();
        for (String p : all) if (!RelayCall.has(this, p)) need.add(p);
        return need;
    }

    private void askRelayPermissions() {
        List<String> need = relayPermsMissing();
        if (need.isEmpty()) toast("All permissions are allowed");
        else requestPermissions(need.toArray(new String[0]), 4104);
    }

    private void renderHomeList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        if (!netError.isEmpty()) listBox.addView(errorBanner());
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
        Collections.sort(peers, (x, y) -> {
            boolean px = pins.contains(x), py = pins.contains(y);
            if (px != py) return px ? -1 : 1;                       // pinned chats stay on top
            return Long.compare(by.get(y).get(by.get(y).size() - 1).ts, by.get(x).get(by.get(x).size() - 1).ts);
        });
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
        // everyone else who is on Telephone: tap to start a chat
        boolean header = false;
        for (final Contact c : contacts) {
            if (by.containsKey(c.id) || (!q.isEmpty() && !c.name.toLowerCase(Locale.getDefault()).contains(q))) continue;
            if (!header) {
                header = true;
                TextView t = Ui.tv(this, "Telephone users", 13, C_SUB, true);
                t.setPadding(dp(16), dp(14), dp(16), dp(4));
                listBox.addView(t);
            }
            listBox.addView(userRow(c));
            shown++;
        }
        if (shown == 0) {
            TextView e = Ui.tv(this, q.isEmpty() ? "No users yet.\nTap ✉ and type a phone number to start a chat." : "No results", 15, C_SUB, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(24), dp(80), dp(24), 0);
            listBox.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    private View userRow(final Contact c) {
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
        return row;
    }

    private View chatRow(final Contact c, Msg last, int unread) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        Ui.pressFx(row);
        row.setOnClickListener(v -> openChat(c.id));
        row.setOnLongClickListener(v -> { chatMenu(c); return true; });
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
        boolean ty = isTyping(c.id);
        sub.addView(Ui.ellipsized(this, ty ? "typing…" : preview(last), 14, ty ? C_GREEN : (unread > 0 ? C_TEXT : C_SUB), false, 1), new LinearLayout.LayoutParams(0, WRAP, 1f));
        mid.addView(sub, Ui.lpm(this, MATCH, WRAP, 0, 3, 0, 0));
        row.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(Ui.tv(this, listTime(last.ts), 12, unread > 0 ? C_GREEN : C_SUB, false));
        if (pins.contains(c.id)) right.addView(Ui.tv(this, "📌", 12, C_SUB, false));
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
        searchEt.setHint("Search or type a phone number");
        searchEt.setInputType(InputType.TYPE_CLASS_TEXT);
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
        if (!netError.isEmpty()) listBox.addView(errorBanner());
        String q = query.trim().toLowerCase(Locale.getDefault());
        int shown = 0;
        String digits = q.replaceAll("[^0-9]", "");
        if (digits.length() >= 10) {
            final String target = PushManager.normMobile(digits);
            if (!target.equals(meId)) {
                LinearLayout nr = new LinearLayout(this);
                nr.setOrientation(LinearLayout.HORIZONTAL);
                nr.setGravity(Gravity.CENTER_VERTICAL);
                nr.setPadding(dp(16), dp(10), dp(16), dp(10));
                Ui.pressFx(nr);
                nr.setOnClickListener(v -> openChat(target));
                TextView ic = Ui.tv(this, "💬", 20, Color.WHITE, false);
                ic.setGravity(Gravity.CENTER);
                ic.setBackground(Ui.circle(C_GREEN));
                nr.addView(ic, Ui.lpm(this, dp(46), dp(46), 0, 0, 14, 0));
                nr.addView(Ui.tv(this, "Chat with " + target, 16, C_TEXT, true));
                listBox.addView(nr);
                shown++;
            }
        }
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
            TextView e = Ui.tv(this, contacts.isEmpty() ? "No one has joined yet.\nType a phone number above to start a chat." : "No results", 15, C_SUB, false);
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
        peerSeenTs = 0; peerOn = false; typingFrom = ""; presFetchAt = 0;
        render();
        renderMessages(true);
        fetch();
        loadPeerPresence();
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
        peerSub = Ui.ellipsized(this, c.sub, 12, C_SUB, false, 1);
        meta.addView(peerSub);
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

        replyBar = new LinearLayout(this);
        replyBar.setOrientation(LinearLayout.HORIZONTAL);
        replyBar.setGravity(Gravity.CENTER_VERTICAL);
        replyBar.setBackgroundColor(C_BAR);
        replyBar.setPadding(dp(14), dp(6), dp(6), dp(6));
        replyTv = Ui.tv(this, "", 13, C_SUB, false);
        replyTv.setMaxLines(2);
        replyBar.addView(replyTv, new LinearLayout.LayoutParams(0, WRAP, 1f));
        replyBar.addView(iconBtn("✕", v -> setReply(null)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        replyBar.setVisibility(View.GONE);
        root.addView(replyBar, new LinearLayout.LayoutParams(MATCH, WRAP));

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
            @Override public void afterTextChanged(Editable e) {
                sendBtn.setText(e.toString().trim().isEmpty() ? "🎤" : "➤");
                if (!e.toString().trim().isEmpty()) sendTyping();
            }
        });
        inputBar = bar;
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        setReply(replyTo);
        refreshHeaderSub();

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
        b.setOnLongClickListener(v -> { msgMenu(m); return true; });

        if (m.fw && !m.del) b.addView(Ui.tv(this, "↪ Forwarded", 12, C_SUB, false));

        if (!m.rt.isEmpty() && !m.del) {                       // quoted reply
            LinearLayout q = new LinearLayout(this);
            q.setOrientation(LinearLayout.VERTICAL);
            q.setBackground(Ui.bg(this, 0x33000000, 6, 0));
            q.setPadding(dp(8), dp(4), dp(8), dp(4));
            q.addView(Ui.ellipsized(this, m.rn, 12.5f, C_GREEN, true, 1));
            q.addView(Ui.ellipsized(this, m.rt, 13, C_SUB, false, 2));
            b.addView(q, Ui.lpm(this, MATCH, WRAP, 0, 0, 0, 4));
        }

        switch (m.del ? "deleted" : m.type) {
            case "deleted": {
                b.addView(Ui.tv(this, "🚫 This message was deleted", 14.5f, C_SUB, false));
                break;
            }
            case "image": {
                final ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0x22000000);
                int w = Math.min(maxW - dp(18), dp(240));
                b.addView(iv, new LinearLayout.LayoutParams(w, (int) (w * 1.0f)));
                loadBitmap(thumb(m.url), iv);
                iv.setOnClickListener(v -> showImage(m.url));
                iv.setOnLongClickListener(v -> { msgMenu(m); return true; });
                break;
            }
            case "audio": b.addView(audioRow(m, mine)); break;
            case "video": {
                android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
                fl.setBackground(Ui.bg(this, 0xFF000000, 10, 0));
                final ImageView pv = new ImageView(this);
                pv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                fl.addView(pv, new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));
                TextView play = Ui.tv(this, "▶", 22, Color.WHITE, true);
                play.setGravity(Gravity.CENTER);
                play.setBackground(Ui.circle(0x99000000));
                fl.addView(play, new android.widget.FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
                b.addView(fl, new LinearLayout.LayoutParams(Math.min(maxW - dp(18), dp(240)), dp(150)));
                loadBitmap(cdn(m.url, "so_0,w_600,f_jpg", true), pv);
                fl.setOnClickListener(v -> showPlayer(m.url, m.fileName, false));
                fl.setOnLongClickListener(v -> { msgMenu(m); return true; });
                break;
            }
            case "file": {
                View card = FileCard.build(this, m.url, m.fileName, mine, C_TEXT, mine ? 0xFFA8C5BC : C_SUB,
                    uri -> upload(uri, Cloud.meta(this, uri), false, 0));
                card.setOnLongClickListener(v -> { msgMenu(m); return true; });
                b.addView(card, new LinearLayout.LayoutParams(dp(240), WRAP));
                break;
            }
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
        if (starred.contains(m.id)) meta.addView(Ui.tv(this, "⭐ ", 10.5f, C_SUB, false));
        meta.addView(Ui.tv(this, timeFmt.format(new Date(m.ts)), 11, mine ? 0xFFA8C5BC : C_SUB, false));
        if (mine) {
            boolean pend = pending.containsKey(m.id);
            TextView tk = Ui.tv(this, pend ? " 🕓" : (m.read || m.dl) ? " ✓✓" : " ✓", 12, m.read ? C_TICK : 0xFFA8C5BC, false);
            meta.addView(tk);
        }
        b.addView(meta, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout colw = new LinearLayout(this);
        colw.setOrientation(LinearLayout.VERTICAL);
        colw.addView(b, new LinearLayout.LayoutParams(WRAP, WRAP));
        if (!m.reacts.isEmpty() && !m.del) {
            LinearLayout.LayoutParams pp = Ui.lpm(this, WRAP, WRAP, 8, -9, 8, 0);
            pp.gravity = mine ? Gravity.END : Gravity.START;
            colw.addView(reactPill(m), pp);
        }
        row.addView(colw, new LinearLayout.LayoutParams(WRAP, WRAP));
        return row;
    }

    // ── live signals: typing, online / last seen ─────────────
    private void pushSig(String peer, String what) {
        if (peer == null || peer.isEmpty() || peer.startsWith("g_")) return;
        try { new AdminBridge(getApplicationContext(), null).sendDmPush(peer, "tsig", meId, meName, what, "", "", false); }
        catch (Throwable ignored) { }
    }

    private void sendTyping() {
        if (curPeer.isEmpty() || curPeer.startsWith("g_")) return;
        long now = System.currentTimeMillis();
        if (now - typingSentAt < 3000) return;
        typingSentAt = now;
        pushSig(curPeer, "typing");
    }

    private void gotSignal(String sender, String what) {
        if (released) return;
        long now = System.currentTimeMillis();
        if ("typing".equals(what)) {
            typingFrom = sender;
            typingAt = now;
            if (sender.equals(curPeer)) { peerOn = true; peerSeenTs = now; }
            refreshTyping();
            h.postDelayed(this::refreshTyping, 5200);
        } else {                                              // "read" / "upd": the server data changed
            fetch();
            h.postDelayed(this::fetch, 700);
        }
    }

    private boolean isTyping(String peer) {
        return !typingFrom.isEmpty() && typingFrom.equals(peer) && System.currentTimeMillis() - typingAt < 5000;
    }

    private void refreshTyping() {
        if (released) return;
        if (screen == S_CHAT) refreshHeaderSub();
        else if (screen == S_HOME && tab == 0) renderHomeList();
    }

    private void refreshHeaderSub() {
        if (peerSub == null || screen != S_CHAT) return;
        long now = System.currentTimeMillis();
        String t;
        int col = C_SUB;
        if (isTyping(curPeer)) { t = "typing…"; col = C_GREEN; }
        else if (peerOn && now - peerSeenTs < 100000) { t = "online"; col = C_GREEN; }
        else if (peerSeenTs > 0) {
            String dl = dayLabel(peerSeenTs);
            t = "last seen " + ("Today".equals(dl) ? "today" : dl) + " at " + timeFmt.format(new Date(peerSeenTs));
        } else t = contactOf(curPeer, "").sub;
        peerSub.setText(t);
        peerSub.setTextColor(col);
    }

    private boolean hideSeen() { return getSharedPreferences("rt_tel", MODE_PRIVATE).getBoolean("hide_seen", false); }

    /** My presence document (online now / last seen); written while Telephone is open. */
    private void beat(final boolean on) {
        if (meId.isEmpty()) return;
        final boolean hide = hideSeen();
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("appId", DiscussionScreen.SYNC_APP_ID);
        d.put("ts", Long.valueOf(hide ? 0 : System.currentTimeMillis()));
        d.put("on", Long.valueOf(on && !hide ? 1 : 0));
        final String id = "_pr_" + meId;
        Store.async(() -> { Fs.set(id, d); return Boolean.TRUE; }, null);
    }

    private void loadPeerPresence() {
        if (curPeer.isEmpty() || curPeer.startsWith("g_")) return;
        presFetchAt = System.currentTimeMillis();
        final String peer = curPeer;
        Store.async(() -> Fs.get("_pr_" + peer), (Fs.Doc d, Exception e) -> {
            if (released || e != null || !peer.equals(curPeer) || screen != S_CHAT) return;
            if (d == null) { peerSeenTs = 0; peerOn = false; }
            else { peerSeenTs = num(d.data, "ts"); peerOn = num(d.data, "on") == 1; }
            refreshHeaderSub();
        });
    }

    // ── message / chat actions ───────────────────────────────
    private void setReply(Msg m) {
        replyTo = m;
        if (replyBar == null || replyTv == null) return;
        if (m == null) { replyBar.setVisibility(View.GONE); return; }
        String who = m.from.equals(meId) ? "You" : contactOf(m.from, m.fromName).name;
        replyTv.setText("↩ " + who + ": " + preview(m));
        replyBar.setVisibility(View.VISIBLE);
        if (input != null) input.requestFocus();
    }

    private void msgMenu(final Msg m) {
        if (m.del || pending.containsKey(m.id) || "call".equals(m.type)) return;
        String pv = preview(m);
        final Ui.Modal d = new Ui.Modal(this, "Message", pv.length() > 80 ? pv.substring(0, 80) + "…" : pv);
        LinearLayout er = new LinearLayout(this);
        er.setOrientation(LinearLayout.HORIZONTAL);
        er.setGravity(Gravity.CENTER);
        for (final String em : new String[]{"👍", "❤️", "😂", "😮", "😢", "🙏"}) {
            TextView t = Ui.tv(this, em, 26, C_TEXT, false);
            t.setPadding(dp(9), dp(6), dp(9), dp(6));
            Ui.pressFx(t);
            t.setOnClickListener(v -> { d.dismiss(); react(m, em); });
            er.addView(t);
        }
        d.add(er);
        d.col("↩  Reply", Ui.B_PRIMARY, v -> { d.dismiss(); setReply(m); });
        if ("text".equals(m.type)) d.col("⧉  Copy", Ui.B_OUTLINE, v -> { d.dismiss(); copyText(m.text); });
        d.col("➡  Forward", Ui.B_OUTLINE, v -> { d.dismiss(); forwardPicker(m); });
        d.col(starred.contains(m.id) ? "☆  Unstar" : "⭐  Star", Ui.B_OUTLINE, v -> { d.dismiss(); toggleStar(m); });
        d.col("🗑  Delete for me", Ui.B_OUTLINE, v -> { d.dismiss(); hideMsg(m); });
        if (m.from.equals(meId)) d.col("🚫  Delete for everyone", Ui.B_RED, v -> { d.dismiss(); deleteForAll(m); });
        d.col("Cancel", Ui.B_GHOST, v -> d.dismiss());
        d.show();
    }

    // ── reactions (one field per person, like the Discussion: r_<number>) ──
    private void react(final Msg m, String emoji) {
        final String em = emoji.equals(m.reacts.get(meId)) ? "" : emoji;     // same emoji again removes it
        if (em.isEmpty()) m.reacts.remove(meId); else m.reacts.put(meId, em);
        lastSig = "";
        if (screen == S_CHAT) renderMessages(false);
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("r_" + meId, em);
        d.put("up", Long.valueOf(System.currentTimeMillis()));
        Store.async(() -> { Fs.update(m.id, d); return Boolean.TRUE; }, (Boolean ok, Exception e) -> {
            if (released) return;
            if (e != null) { toast("Could not react. Try again."); return; }
            pushSig(m.from.equals(meId) ? m.to : m.from, "upd");
        });
    }

    private View reactPill(final Msg m) {
        Map<String, Integer> cnt = new LinkedHashMap<>();
        for (String e : m.reacts.values()) cnt.put(e, cnt.containsKey(e) ? cnt.get(e) + 1 : 1);
        StringBuilder sb = new StringBuilder();
        for (String e : cnt.keySet()) sb.append(e);
        int total = m.reacts.size();
        TextView t = Ui.tv(this, sb.toString() + (total > 1 ? " " + total : ""), 12.5f, C_TEXT, false);
        t.setPadding(dp(7), dp(2), dp(7), dp(2));
        t.setBackground(Ui.bg(this, C_BAR, 12, C_BG));
        t.setOnClickListener(v -> msgMenu(m));
        return t;
    }

    // ── star ──
    private void toggleStar(Msg m) {
        if (!starred.remove(m.id)) starred.add(m.id);
        saveLocalSets();
        lastSig = "";
        if (screen == S_CHAT) renderMessages(false);
    }

    private void starredMenu() {
        final Ui.Modal d = new Ui.Modal(this, "⭐ Starred messages", "");
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int n = 0;
        for (int i = all.size() - 1; i >= 0 && n < 100; i--) {
            final Msg m = all.get(i);
            if (!starred.contains(m.id)) continue;
            n++;
            String who = m.from.equals(meId) ? "You" : contactOf(m.from, m.fromName).name;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(dp(8), dp(8), dp(8), dp(8));
            r.addView(Ui.ellipsized(this, who + " · " + listTime(m.ts), 12, C_GREEN, true, 1));
            r.addView(Ui.ellipsized(this, preview(m), 14, C_TEXT, false, 2));
            Ui.pressFx(r);
            r.setOnClickListener(v -> { d.dismiss(); openChat(peerOf(m)); });
            box.addView(r);
            box.addView(divider());
        }
        if (n == 0) box.addView(Ui.tv(this, "No starred messages.\nPress and hold a message → Star.", 14, C_SUB, false));
        sv.addView(box);
        d.add(sv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(MATCH, Math.min(dp(380), dp(70) * Math.max(1, n) + dp(40))));
        d.col("Close", Ui.B_OUTLINE, v -> d.dismiss());
        d.show();
    }

    // ── forward ──
    private void forwardPicker(final Msg m) {
        final Ui.Modal d = new Ui.Modal(this, "Forward to…", "");
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinkedHashMap<String, Long> recent = new LinkedHashMap<>();
        for (Msg x : all) { String p = peerOf(x); if (!p.isEmpty()) recent.put(p, x.ts); }
        List<String> order = new ArrayList<>(recent.keySet());
        Collections.sort(order, (x, y) -> Long.compare(recent.get(y), recent.get(x)));
        for (Contact c : contacts) if (!recent.containsKey(c.id)) order.add(c.id);
        for (final String p : order) {
            final Contact c = contactOf(p, "");
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(4), dp(8), dp(4), dp(8));
            r.addView(avatar(c.name, c.id, 40, 16), Ui.lpm(this, dp(40), dp(40), 0, 0, 12, 0));
            r.addView(Ui.ellipsized(this, c.name, 15.5f, C_TEXT, false, 1), new LinearLayout.LayoutParams(0, WRAP, 1f));
            Ui.pressFx(r);
            r.setOnClickListener(v -> { d.dismiss(); forwardTo(p, m); });
            box.addView(r);
        }
        if (order.isEmpty()) box.addView(Ui.tv(this, "No one to forward to yet.", 14, C_SUB, false));
        sv.addView(box);
        d.add(sv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(340)));
        d.col("Cancel", Ui.B_OUTLINE, v -> d.dismiss());
        d.show();
    }

    private void forwardTo(String peer, Msg m) {
        final String keep = curPeer;
        curPeer = peer;
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("fw", Long.valueOf(1));
        if (m.dur > 0) ex.put("dur", Long.valueOf(m.dur));
        send(m.type, m.text, m.url, m.fileName, ex);
        curPeer = keep;
        toast("Forwarded to " + contactOf(peer, "").name);
    }

    // ── full-screen photo and video / audio player (same as the Discussion) ──
    private static String cdn(String url, String tr, boolean swapExtToJpg) {
        if (url == null || !url.contains("res.cloudinary.com") || !url.contains("/upload/")) return url;
        String u = url.replace("/upload/", "/upload/" + tr + "/");
        if (swapExtToJpg) u = u.replaceAll("\\.[A-Za-z0-9]{2,5}$", ".jpg");
        return u;
    }

    private void showImage(String url) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
        fl.setBackgroundColor(Color.BLACK);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fl.addView(iv, new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));
        TextView close = Ui.tv(this, "✕", 22, Color.WHITE, true);
        close.setPadding(dp(18), dp(14), dp(18), dp(14));
        fl.addView(close, new android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END));
        fl.setOnClickListener(v -> d.dismiss());
        d.setContentView(fl);
        d.show();
        loadBitmap(cdn(url, "w_1600,q_auto,f_jpg", false), iv);
    }

    private void showPlayer(String url, String fileName, boolean audio) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
        fl.setBackgroundColor(Color.BLACK);
        if (audio) {
            TextView t = Ui.tv(this, "🎵\n" + (fileName.isEmpty() ? "Audio" : fileName), 18, Color.WHITE, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), 0, dp(24), dp(60));
            fl.addView(t, new android.widget.FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        }
        final android.widget.VideoView vv = new android.widget.VideoView(this);
        fl.addView(vv, new android.widget.FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        android.widget.MediaController mc = new android.widget.MediaController(this);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);
        vv.setOnPreparedListener(mp -> mp.start());
        vv.setOnErrorListener((mp, what, extra) -> { toast("Cannot play this file"); return true; });
        TextView close = Ui.tv(this, "✕", 22, Color.WHITE, true);
        close.setPadding(dp(18), dp(14), dp(18), dp(14));
        close.setOnClickListener(v -> d.dismiss());
        fl.addView(close, new android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END));
        d.setOnDismissListener(x -> { try { vv.stopPlayback(); } catch (Exception ignored) { } });
        d.setContentView(fl);
        d.show();
        vv.setVideoURI(Uri.parse(url));
    }

    private void copyText(String t) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("message", t));
            toast("Copied");
        } catch (Throwable e) { toast("Could not copy"); }
    }

    private void hideMsg(Msg m) {
        hidden.add(m.id);
        saveLocalSets();
        all.remove(m);
        lastSig = "";
        if (screen == S_CHAT) renderMessages(false); else if (screen == S_HOME) renderHomeList();
    }

    private void deleteForAll(final Msg m) {
        final Map<String, Object> u = new LinkedHashMap<>();
        u.put("del", Long.valueOf(1));
        u.put("text", "");
        u.put("url", "");
        u.put("fileName", "");
        u.put("rt", "");
        u.put("up", Long.valueOf(System.currentTimeMillis()));
        Store.async(() -> { Fs.update(m.id, u); return Boolean.TRUE; }, (Boolean ok, Exception e) -> {
            if (released) return;
            if (e != null) { toast("Could not delete: " + Fs.explain(e)); return; }
            m.del = true; m.text = ""; m.url = ""; m.fileName = ""; m.rt = "";
            pushSig(m.to, "upd");
            lastSig = "";
            if (screen == S_CHAT) renderMessages(false);
            fetch();
        });
    }

    private void chatMenu(final Contact c) {
        final boolean pinned = pins.contains(c.id);
        final Ui.Modal d = new Ui.Modal(this, c.name, "");
        d.col(pinned ? "📌  Unpin chat" : "📌  Pin chat to top", Ui.B_PRIMARY, v -> {
            d.dismiss();
            if (pinned) pins.remove(c.id); else pins.add(c.id);
            saveLocalSets();
            renderHomeList();
        });
        d.col("Cancel", Ui.B_GHOST, v -> d.dismiss());
        d.show();
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
