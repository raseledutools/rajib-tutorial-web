package com.rajibtutorial;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.Voice;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Phone dialer screen: places outgoing PSTN calls through the Twilio Voice SDK. */
public class CallActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int REQ_MIC = 7001;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText number;
    private TextView status, timer, btnCall, btnMute, btnSpeaker;
    private LinearLayout dialBox, callBox;
    private Call activeCall;
    private boolean muted = false, speaker = false, busy = false;
    private long connectedAt = 0;
    private AudioManager audio;
    private int prevAudioMode = AudioManager.MODE_NORMAL;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (connectedAt > 0) {
                long s = (System.currentTimeMillis() - connectedAt) / 1000;
                timer.setText(String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60));
                ui.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 22), Ui.dp(this, 36), Ui.dp(this, 22), Ui.dp(this, 22));

        TextView back = Ui.smallBtn(this, "‹  Back", Ui.TEXT, v -> onBackPressed());
        root.addView(back, Ui.lpm(this, WRAP, WRAP, 0, 0, 0, 18));

        TextView title = Ui.tv(this, "Phone Call", 24, Ui.TEXT, true);
        root.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView sub = Ui.tv(this, "Call any number with country code, e.g. +8801XXXXXXXXX", 13, Ui.SUB, false);
        root.addView(sub, Ui.lpm(this, MATCH, WRAP, 0, 4, 0, 18));

        status = Ui.tv(this, "Ready", 15, Ui.SUB, true);
        status.setGravity(Gravity.CENTER);
        root.addView(status, Ui.lpm(this, MATCH, WRAP, 0, 6, 0, 4));
        timer = Ui.tv(this, "", 30, Ui.TEXT, true);
        timer.setGravity(Gravity.CENTER);
        root.addView(timer, Ui.lpm(this, MATCH, WRAP, 0, 0, 0, 18));

        // dial box
        dialBox = new LinearLayout(this);
        dialBox.setOrientation(LinearLayout.VERTICAL);
        number = new EditText(this);
        number.setHint("+8801XXXXXXXXX");
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        number.setTextSize(20);
        number.setSingleLine(true);
        number.setGravity(Gravity.CENTER);
        number.setTextColor(Ui.TEXT);
        number.setHintTextColor(0xFFA39C93);
        number.setBackground(Ui.bg(this, Ui.BG, 14, 0x33000000));
        number.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16));
        dialBox.addView(number, Ui.lpm(this, MATCH, WRAP, 0, 0, 0, 14));
        btnCall = Ui.btn(this, "📞  Call", Ui.B_GREEN, v -> startCall());
        dialBox.addView(btnCall, new LinearLayout.LayoutParams(MATCH, WRAP));
        root.addView(dialBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        // in-call box
        callBox = new LinearLayout(this);
        callBox.setOrientation(LinearLayout.VERTICAL);
        callBox.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnMute = Ui.btn(this, "🎙  Mute", Ui.B_OUTLINE, v -> toggleMute());
        btnSpeaker = Ui.btn(this, "🔊  Speaker", Ui.B_OUTLINE, v -> toggleSpeaker());
        row.addView(btnMute, Ui.lpm(this, 0, WRAP, 0, 0, 6, 0));
        row.addView(btnSpeaker, Ui.lpm(this, 0, WRAP, 6, 0, 0, 0));
        ((LinearLayout.LayoutParams) btnMute.getLayoutParams()).weight = 1f;
        ((LinearLayout.LayoutParams) btnSpeaker.getLayoutParams()).weight = 1f;
        callBox.addView(row, Ui.lpm(this, MATCH, WRAP, 0, 0, 0, 12));
        callBox.addView(Ui.btn(this, "End Call", Ui.B_RED, v -> hangUp()), new LinearLayout.LayoutParams(MATCH, WRAP));
        root.addView(callBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        return root;
    }

    // ───────── dialing ─────────

    /** Normalises user input to E.164. Returns "" if it cannot be a valid number. */
    static String normalize(String raw) {
        String n = raw.replaceAll("[\\s\\-().]", "");
        if (n.startsWith("00")) n = "+" + n.substring(2);
        else if (n.matches("^01\\d{9}$")) n = "+88" + n;          // Bangladesh local mobile
        if (!n.matches("^\\+[1-9]\\d{6,14}$")) return "";
        return n;
    }

    private void startCall() {
        if (busy || activeCall != null) return;
        final String to = normalize(number.getText().toString());
        if (to.isEmpty()) {
            Toast.makeText(this, "Enter a valid number with country code", Toast.LENGTH_SHORT).show();
            return;
        }
        if (BuildConfig.TWILIO_TOKEN_URL.isEmpty()) {
            setStatus("Calling is not configured yet (missing token server URL).");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        busy = true;
        btnCall.setEnabled(false);
        setStatus("Connecting…");
        new Thread(() -> {
            try {
                final String token = fetchToken();
                ui.post(() -> connect(token, to));
            } catch (final Exception e) {
                ui.post(() -> { busy = false; btnCall.setEnabled(true); setStatus("Could not get call token: " + e.getMessage()); });
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_MIC) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startCall();
            else setStatus("Microphone permission is required to make calls.");
        }
    }

    private String identity() {
        SharedPreferences sp = getSharedPreferences("call", MODE_PRIVATE);
        String id = sp.getString("identity", null);
        if (id == null) {
            id = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            sp.edit().putString("identity", id).apply();
        }
        return id;
    }

    private String fetchToken() throws Exception {
        String base = BuildConfig.TWILIO_TOKEN_URL;
        String url = base + (base.contains("?") ? "&" : "?") + "identity=" + URLEncoder.encode(identity(), "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("server returned " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            c.disconnect();
        }
        String body = sb.toString().trim();
        if (body.startsWith("{")) body = new JSONObject(body).getString("token");
        if (body.isEmpty()) throw new Exception("empty token");
        return body;
    }

    private void connect(String token, String to) {
        Map<String, String> params = new HashMap<>();
        params.put("To", to);
        ConnectOptions opts = new ConnectOptions.Builder(token).params(params).build();
        prevAudioMode = audio.getMode();
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        activeCall = Voice.connect(this, opts, listener);
    }

    private final Call.Listener listener = new Call.Listener() {
        public void onRinging(Call call) { setStatus("Ringing…"); }

        public void onConnectFailure(Call call, CallException e) {
            setStatus("Call failed: " + e.getMessage());
            endUi();
        }

        public void onConnected(Call call) {
            setStatus("Connected");
            dialBox.setVisibility(View.GONE);
            callBox.setVisibility(View.VISIBLE);
            connectedAt = System.currentTimeMillis();
            ui.post(tick);
        }

        public void onReconnecting(Call call, CallException e) { setStatus("Reconnecting…"); }

        public void onReconnected(Call call) { setStatus("Connected"); }

        public void onDisconnected(Call call, CallException e) {
            setStatus(e == null ? "Call ended" : "Call ended: " + e.getMessage());
            endUi();
        }

        // Present in some SDK versions; harmless if the interface does not declare it.
        @SuppressWarnings("rawtypes")
        public void onCallQualityWarningsChanged(Call call, java.util.Set current, java.util.Set previous) {}
    };

    // ───────── in-call controls ─────────

    private void toggleMute() {
        if (activeCall == null) return;
        muted = !muted;
        activeCall.mute(muted);
        btnMute.setText(muted ? "🔇  Unmute" : "🎙  Mute");
    }

    private void toggleSpeaker() {
        speaker = !speaker;
        audio.setSpeakerphoneOn(speaker);
        btnSpeaker.setText(speaker ? "🔈  Earpiece" : "🔊  Speaker");
    }

    private void hangUp() {
        if (activeCall != null) activeCall.disconnect();
        else endUi();
    }

    private void endUi() {
        activeCall = null;
        busy = false;
        connectedAt = 0;
        muted = false;
        speaker = false;
        audio.setSpeakerphoneOn(false);
        audio.setMode(prevAudioMode);
        timer.setText("");
        btnCall.setEnabled(true);
        btnMute.setText("🎙  Mute");
        btnSpeaker.setText("🔊  Speaker");
        callBox.setVisibility(View.GONE);
        dialBox.setVisibility(View.VISIBLE);
    }

    private void setStatus(String s) { status.setText(s); }

    @Override
    public void onBackPressed() {
        if (activeCall != null) { Toast.makeText(this, "End the call first", Toast.LENGTH_SHORT).show(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (activeCall != null) activeCall.disconnect();
        super.onDestroy();
    }
}
