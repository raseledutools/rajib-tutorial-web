package com.rajibtutorial;

import android.content.Context;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * JS Bridge: window.AndroidAdmin
 * Admin panel → Java → FCM REST API
 * Private key stays in Java bytecode — not visible in WebView source.
 */
public class AdminBridge {

    private final Context ctx;
    private final WebView webView;

    // ── Firebase Service Account ──────────────────────────────────────────
    private static final String PROJECT_ID    = "mywebtools-f8d53";
    private static final String CLIENT_EMAIL  =
        "firebase-adminsdk-fbsvc@mywebtools-f8d53.iam.gserviceaccount.com";

    // Private key (PKCS#8 PEM, stripped of headers/newlines → pure base64)
    private static final String PRIVATE_KEY_B64 =
        "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDWqseNhhm6ZpLz" +
        "uGEJMdv61j1wKE6+mv2Jt6b4mM6jszlTk2IsbliKo9ZoUYxC2VMhShJFcb6ZjWif" +
        "ul5VVw+0/u5s2pxxdKR0odb0ZxEqsofF5EnZMdqX8zYcqK/7Ns+iDN/GXKuSJOgo" +
        "+cQV4BODKJ1NTJb8932JYUJEa+DuyHUZgggXl63D5EtXkpweVk5mYMF1B3vaPv1c" +
        "S9KX7FoXAFY3qILBrODG+FuaebUDR0t+1Q/ZFfjKbxmVzlgGGCx0iA4JP2D41Rk8" +
        "jv1bQJGHb8w78V/La4n1asHKB+D2xkFDlHALFgA4LX0xlp4627fR/kHpKs5NvfCM" +
        "gtxH9oVLAgMBAAECggEAQb4lxUMHiB237apPFYjOjSClyj6d9yPyeAXlvgbWai4O" +
        "ZXkivObCeRs6I9KtuR9jpTNRKzFtpQs4uopSL6KtLcpRbMb62IhXgVy7Pzh9em0Y" +
        "xdNnhnwNLTIQJXDIDJC5wDBfLBnfda+V5k6v7d8lOqrqmezV5P1K1hMSP3AmgBoW" +
        "erj4W8YfbgRkSIRswzYGFc/kVDHeJkH29oDTzDD1Cx6IAB/9QXi4G3Wx+TbV9976" +
        "qRdASrVnvapa0wknyBnps1mRIvHip44jwuHU5G5lwGEukYWY6YSbClBjV3pmNwm9" +
        "gsZdRmbG+gJ00i+/y63LjaTxuFqPQSkQl28V1dBaUQKBgQD1y2ndFn4GtJkRoLQd" +
        "kATc26EUslJCksYjDnINayUBbuwLqo65LqtjIkaR2yr+KDETjaoTqDF9yVQFB6Me" +
        "R8MfzJfpneAxE0oEsND4VGY4XRoEi7gnLMcLTaUzOwAPjJGpfNvmTGir1a/KQlPE" +
        "WZrWXC7qYGtH9mfYVREKxzgFgwKBgQDflIHqlV4hTJhmgpQXrlXQio7FfaoCCXHF" +
        "ZKVh+zEEJI7ZjHnTWep/2P3Jtc7BsDTCYYM661HB19fRgvUsYtIs/6Ybj6VeYcol" +
        "9/Ic3+9APthz600gMWRLCbw5jNpQJyeJrPvBOxlEnB/DOhk7kBZuFpBnzi5oE+ko" +
        "4XGSlcy+mQKBgQCMX2NSNGo9Ah75BkJXL8zXJUdVPpHkG1a05qcUKsCzlNmRwBJK" +
        "h/CxaP3MhtKC3AlLFXiwSToakEQ/qyDpRiqVmo6XiOZkoJOBIkn1yJ2oSuN0/SZq" +
        "mRQDzJAwNd3PA4ScuwYK/FA68oq2hDK6zEG1cmgIyo/6RSsBTXOyKBxCaQKBgGuW" +
        "ukHZaPebNUc3FO6kXPVCh9uvUg0y4EW93fvddcPpl7hGaiVzgm3NTKsxSvJXybu0" +
        "UwMaVDh0lo1ieHrIfx1aVGO05z4Yzr8gpqiQdkAzPdPiajXQrtORNrt29WNWxCbz" +
        "UB63QryKaJp4rcXE25ZUU18wj5JT6oJaol2IqoMZAoGBAKHeuI6HsSzBgUxfbBij" +
        "G8ouPie8AgCXpPeB3gFRvZk8A/i73xYxzOf9zvv1XZWrrwEJC7qwS8Ww5kyMbjVO" +
        "8jsuJQ3ESy2BBlyzi+B5iE6+85KUQTus/SLT0Ge/jytHzVoosL3zgJdAveDuNsAy" +
        "Ts2QZJE9zynpvH7Xr8YBgOWu";

    // ── Cached OAuth2 token ───────────────────────────────────────────────
    private volatile String cachedAccessToken = null;
    private volatile long   tokenExpireMs     = 0;
    private volatile String lastLookupError   = null;
    private volatile String lastSendError     = null;

    public AdminBridge(Context ctx, WebView webView) {
        this.ctx     = ctx.getApplicationContext();
        this.webView = webView;
    }

    // ─────────────────────────────────────────────────────────────────────
    // JS-callable: AndroidAdmin.sendNotification(title, body, target, mobile)
    //   target: "all" | "topic" | "token"
    //   mobile: member mobile number (used when target=="user")
    // ─────────────────────────────────────────────────────────────────────
    @JavascriptInterface
    public void sendNotification(String title, String body, String target, String mobile) {
        send(title, body, target, mobile, this::callback);
    }

    /** Result callback (called from a background thread). */
    public interface Result { void done(String message); }

    /**
     * Same sender, usable from native screens too (no WebView needed).
     * The callback runs on a background thread — post to the UI thread yourself.
     */
    public void send(String title, String body, String target, String mobile, Result cb) {
        new Thread(() -> {
            try {
                String accessToken = getAccessToken();
                if (accessToken == null) {
                    cb.done("❌ Access token নেওয়া যায়নি");
                    return;
                }

                if ("all".equals(target)) {
                    final String mid = "m" + System.currentTimeMillis();
                    // (a) topic "all"  (b) every registered device token directly — covers phones
                    // that never joined the topic. Same message id => shown only once per phone.
                    String topicErr = null;
                    boolean topicOk = false;
                    try {
                        sendFcm(accessToken, buildTopicPayload(title, body, "all", mid));
                        topicOk = true;
                    } catch (Exception e) { topicErr = e.getMessage(); }

                    int[] r = sendToAllTokens(accessToken, title, body, mid);   // {ok, fail, total}
                    if (lastLookupError != null && !topicOk) {
                        cb.done("❌ " + (topicErr != null ? topicErr : lastLookupError));
                        return;
                    }
                    if (!topicOk && r[0] == 0) {
                        cb.done("❌ কোনো device এ পাঠানো যায়নি" + (lastSendError == null ? "" : " (" + lastSendError + ")"));
                        return;
                    }
                    logToFirestore(title, body, "all", "");
                    cb.done("✅ সব user এর কাছে পাঠানো হয়েছে (" + r[0] + " টি device"
                        + (r[1] > 0 ? ", " + r[1] + " টি ব্যর্থ" : "") + ")");

                } else if ("user".equals(target) && mobile != null && !mobile.isEmpty()) {
                    // Fetch tokens for this mobile from Firestore (all number formats)
                    String[] tokens = getTokensForMobile(mobile);
                    if (lastLookupError != null) {
                        cb.done("❌ Token খোঁজা যায়নি: " + lastLookupError);
                        return;
                    }
                    if (tokens == null || tokens.length == 0) {
                        cb.done("❌ এই user এর কোনো device registered নেই (user কে app একবার খুলতে বলুন)");
                        return;
                    }
                    int ok = 0, fail = 0;
                    for (String tok : tokens) {
                        try {
                            sendFcm(accessToken, buildTokenPayload(title, body, tok, "m" + System.currentTimeMillis()));
                            ok++;
                        } catch (Exception e) {
                            fail++;
                            lastSendError = e.getMessage();
                        }
                    }
                    logToFirestore(title, body, "user", mobile);
                    cb.done(ok > 0
                        ? "✅ " + ok + " টি device এ পাঠানো হয়েছে"
                        : "❌ কোনো device এ পাঠানো যায়নি" + (lastSendError == null ? "" : " (" + lastSendError + ")"));
                } else {
                    cb.done("❌ Invalid target: " + target);
                }

            } catch (Throwable e) {
                String m = e.getMessage();
                cb.done("❌ " + (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m));
            }
        }).start();
    }

    /**
     * Discussion message → every subscribed device (topic "discussion"), data-only so that
     * PushService builds the notification (own messages suppressed, tap opens the chat).
     * Fire-and-forget; runs on its own thread.
     */
    public void sendChatPush(final String title, final String body, final String fromId, final String mid) {
        new Thread(() -> {
            try {
                String at = getAccessToken();
                if (at == null) return;
                JSONObject data = new JSONObject();
                data.put("title", title);
                data.put("body", body);
                data.put("kind", "chat");
                data.put("from", fromId);
                data.put("id", mid);
                data.put("time", String.valueOf(System.currentTimeMillis()));
                JSONObject android = new JSONObject();
                android.put("priority", "high");
                android.put("ttl", "3600s");
                JSONObject msg = new JSONObject();
                msg.put("topic", PushManager.TOPIC_DISCUSSION);
                msg.put("data", data);
                msg.put("android", android);
                sendFcm(at, new JSONObject().put("message", msg));
            } catch (Throwable ignored) { }
        }).start();
    }

    // ── Build payloads (data-only: the app builds the notification itself, so the
    //    sound / vibration / tap-to-open-overlay behaviour is identical in every app state) ──
    private JSONObject buildPayload(String title, String body, String id, String key, String value) throws Exception {
        JSONObject data = new JSONObject();
        data.put("title", title);
        data.put("body",  body);
        data.put("kind",  "admin");
        data.put("id",    id);
        data.put("time",  String.valueOf(System.currentTimeMillis()));

        JSONObject android = new JSONObject();
        android.put("priority", "high");
        android.put("ttl", "86400s");

        JSONObject msg = new JSONObject();
        msg.put(key, value);
        msg.put("data", data);
        msg.put("android", android);

        JSONObject payload = new JSONObject();
        payload.put("message", msg);
        return payload;
    }

    private JSONObject buildTopicPayload(String title, String body, String topic, String id) throws Exception {
        return buildPayload(title, body, id, "topic", topic);
    }

    private JSONObject buildTokenPayload(String title, String body, String token, String id) throws Exception {
        return buildPayload(title, body, id, "token", token);
    }

    // ── Send to every token in push_tokens (parallel) ────────────────────
    /** @return {ok, fail, total} */
    private int[] sendToAllTokens(final String accessToken, final String title, final String body, final String mid) {
        lastLookupError = null;
        lastSendError = null;
        final java.util.Set<String> tokens = getAllTokens();
        if (tokens.isEmpty()) return new int[]{0, 0, 0};
        final java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger fail = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<String> dead = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        for (final String tok : tokens) {
            pool.execute(() -> {
                try {
                    sendFcm(accessToken, buildTokenPayload(title, body, tok, mid));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    lastSendError = m;
                    if (m.contains("UNREGISTERED") || m.contains("HTTP 404")) dead.add(tok);
                }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        removeDeadTokens(dead);
        return new int[]{ok.get(), fail.get(), tokens.size()};
    }

    private java.util.Set<String> getAllTokens() {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.Set<String> result = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
        try {
            FirebaseFirestore.getInstance().collection("push_tokens").limit(2000).get()
                .addOnSuccessListener(snap -> {
                    for (com.google.firebase.firestore.DocumentSnapshot d : snap.getDocuments()) {
                        String tok = d.getString("token");
                        if (tok != null && !tok.isEmpty()) result.add(tok);
                    }
                    latch.countDown();
                })
                .addOnFailureListener(e -> { lastLookupError = e.getMessage(); latch.countDown(); });
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS) && result.isEmpty()) {
                lastLookupError = "timeout (internet?)";
            }
        } catch (Exception e) { lastLookupError = e.getMessage(); }
        return result;
    }

    /** Best effort: drop tokens FCM says no longer exist (uninstalled apps). */
    private void removeDeadTokens(java.util.List<String> dead) {
        for (final String tok : new java.util.ArrayList<>(dead)) {
            try {
                FirebaseFirestore.getInstance().collection("push_tokens").whereEqualTo("token", tok).get()
                    .addOnSuccessListener(snap -> {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap.getDocuments()) d.getReference().delete();
                    });
            } catch (Throwable ignored) { }
        }
    }

    // ── FCM HTTP v1 send ──────────────────────────────────────────────────
    private void sendFcm(String accessToken, JSONObject payload) throws Exception {
        String urlStr = "https://fcm.googleapis.com/v1/projects/"
            + PROJECT_ID + "/messages:send";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
            int code = conn.getResponseCode();
            if (code != 200) {
                String err = "";
                try {
                    java.io.InputStream es = conn.getErrorStream();
                    if (es != null) {
                        java.util.Scanner sc = new java.util.Scanner(es, "UTF-8").useDelimiter("\\A");
                        err = sc.hasNext() ? sc.next() : "";
                    }
                } catch (Exception ignored) {}
                if (err.length() > 400) err = err.substring(0, 400);
                throw new Exception("FCM HTTP " + code + (err.isEmpty() ? "" : " " + err));
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Firestore: get push tokens for a mobile number ───────────────────
    // Synchronous via CountDownLatch so we can call from a background thread
    private String[] getTokensForMobile(String mobile) {
        lastLookupError = null;
        lastSendError = null;
        final java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.Set<String> result = new java.util.LinkedHashSet<>();
        final java.util.List<String> variants = PushManager.mobileVariants(mobile);
        if (variants.isEmpty()) return new String[0];
        try {
            FirebaseFirestore.getInstance()
                .collection("push_tokens")
                .whereIn("mobile", variants)
                .get()
                .addOnSuccessListener(snap -> {
                    for (com.google.firebase.firestore.DocumentSnapshot d : snap.getDocuments()) {
                        String tok = d.getString("token");
                        if (tok != null && !tok.isEmpty()) result.add(tok);
                    }
                    latch.countDown();
                })
                .addOnFailureListener(e -> { lastLookupError = e.getMessage(); latch.countDown(); });
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS) && result.isEmpty()) {
                lastLookupError = "timeout (internet?)";
            }
        } catch (Exception e) { lastLookupError = e.getMessage(); }
        return result.toArray(new String[0]);
    }

    // ── Firestore: log sent message ───────────────────────────────────────
    private void logToFirestore(String title, String body, String target, String mobile) {
        try {
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("title",  title);
            data.put("body",   body);
            data.put("target", target);
            data.put("mobile", mobile);
            data.put("at",     System.currentTimeMillis());
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("sent", target.equals("all") ? "all" : "token");
            data.put("result", result);
            FirebaseFirestore.getInstance()
                .collection("push_messages")
                .document("msg_" + System.currentTimeMillis())
                .set(data);
        } catch (Throwable ignored) {}
    }

    // ── OAuth2 JWT → access token ─────────────────────────────────────────
    private synchronized String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < tokenExpireMs - 60_000) {
            return cachedAccessToken;
        }

        long nowSec = now / 1000;
        // Build JWT header + claim
        String header = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claim  = b64url("{"
            + "\"iss\":\""  + CLIENT_EMAIL + "\","
            + "\"scope\":\"https://www.googleapis.com/auth/firebase.messaging\","
            + "\"aud\":\"https://oauth2.googleapis.com/token\","
            + "\"iat\":"    + nowSec + ","
            + "\"exp\":"    + (nowSec + 3600)
            + "}");

        String unsigned = header + "." + claim;
        String sig = signRS256(unsigned);
        String jwt = unsigned + "." + sig;

        // Exchange JWT for access token
        String postBody = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
            + "&assertion=" + jwt;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://oauth2.googleapis.com/token").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(postBody.getBytes(StandardCharsets.UTF_8));
            }
            java.io.InputStream is = conn.getResponseCode() == 200
                ? conn.getInputStream()
                : conn.getErrorStream();
            java.util.Scanner sc = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            String resp = sc.hasNext() ? sc.next() : "";
            JSONObject json = new JSONObject(resp);
            if (!json.has("access_token")) {
                throw new Exception("OAuth error: " + resp);
            }
            cachedAccessToken = json.getString("access_token");
            tokenExpireMs     = now + 3600_000L;
            return cachedAccessToken;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Crypto helpers ────────────────────────────────────────────────────
    private String signRS256(String data) throws Exception {
        byte[] keyBytes;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyBytes = Base64.getDecoder().decode(PRIVATE_KEY_B64);
        } else {
            keyBytes = android.util.Base64.decode(PRIVATE_KEY_B64, android.util.Base64.DEFAULT);
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(data.getBytes(StandardCharsets.UTF_8));
        byte[] signed = sig.sign();
        return b64urlBytes(signed);
    }

    private static String b64url(String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return b64urlBytes(bytes);
    }

    private static String b64urlBytes(byte[] bytes) {
        String b64;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b64 = Base64.getEncoder().encodeToString(bytes);
        } else {
            b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        }
        return b64.replace('+', '-').replace('/', '_').replace("=", "");
    }

    // ── Callback to JS ────────────────────────────────────────────────────
    private void callback(String message) {
        if (webView == null) return;
        final String escaped = message
            .replace("\\", "\\\\")
            .replace("'", "\\'");
        webView.post(() ->
            webView.evaluateJavascript(
                "if(window._adminBridgeCb)window._adminBridgeCb('" + escaped + "')", null));
    }
}
