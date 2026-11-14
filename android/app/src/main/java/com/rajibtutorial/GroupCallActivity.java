package com.rajibtutorial;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Rational;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Group voice/video call for the Discussion group. WebRTC runs inside a WebView
 * (assets/group_call.html, full-mesh peer connections). Signalling goes through the same
 * Firestore collection as the chat, with "_call_" document ids; this class is the bridge.
 */
public class GroupCallActivity extends AppCompatActivity {
    static final String EXTRA_UID = "uid", EXTRA_NAME = "name", EXTRA_VIDEO = "video";
    private static final String PREFIX = "_call_";
    private static final String ROOM = "discussion";
    private static final long POLL_MS = 1500;

    private WebView wv;
    private String uid = "", name = "", peerId = "";
    private boolean wantVideo = false;
    private volatile boolean running = false, leaveSent = false;
    private long startTs = 0;

    // screen share / record
    private static final int REQ_CAPTURE = 7701;
    private volatile boolean capturePrompt = false;
    private java.io.OutputStream recOut;
    private android.net.Uri recUri;
    private String recLabel = "";

    private final Set<String> seen = new HashSet<>();
    private final List<String> myDocs = Collections.synchronizedList(new ArrayList<String>());
    private final ExecutorService sendEx = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.init(this);
        uid = getIntent().getStringExtra(EXTRA_UID);
        name = getIntent().getStringExtra(EXTRA_NAME);
        wantVideo = getIntent().getBooleanExtra(EXTRA_VIDEO, false);
        if (uid == null) uid = "";
        if (name == null || name.isEmpty()) name = "Member";
        peerId = (uid.isEmpty() ? "u" : uid) + "_" + Integer.toString(new Random().nextInt(1679616) + 1679616, 36).substring(1);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0F1115);
        wv = new WebView(this);
        wv.setBackgroundColor(0xFF0F1115);
        root.addView(wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // runtime permissions first, then load the call page
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (wantVideo && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (need.isEmpty()) loadPage();
        else requestPermissions(need.toArray(new String[0]), 77);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 77) return;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!mic) {
            Toast.makeText(this, "Microphone permission is needed for calls", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (wantVideo && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) wantVideo = false;
        loadPage();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void loadPage() {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(() -> r.grant(r.getResources()));
            }
        });
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String path = req.getUrl() == null ? "" : req.getUrl().getPath();
                if (path != null && path.endsWith("/__screen.jpg")) {
                    byte[] f = ScreenCaptureService.frame;
                    Map<String, String> hd = new java.util.HashMap<>();
                    hd.put("Access-Control-Allow-Origin", "*");
                    hd.put("Cache-Control", "no-store");
                    if (f == null) {
                        return new WebResourceResponse("image/jpeg", null, 204, "No Content", hd,
                            new java.io.ByteArrayInputStream(new byte[0]));
                    }
                    return new WebResourceResponse("image/jpeg", null, 200, "OK", hd,
                        new java.io.ByteArrayInputStream(f));
                }
                return super.shouldInterceptRequest(view, req);
            }
        });
        ScreenCaptureService.cb = new ScreenCaptureService.Cb() {
            @Override public void started(int w, int h) {
                capturePrompt = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);   // otherwise the capture is black
                js("window.onCaptureStarted && window.onCaptureStarted(" + w + "," + h + ")");
            }
            @Override public void stopped() {
                capturePrompt = false;
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                js("window.onCaptureStopped && window.onCaptureStopped()");
            }
        };
        wv.addJavascriptInterface(new Bridge(), "AndroidCall");
        try {
            java.io.InputStream in = getAssets().open("group_call.html");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            // https base URL => secure context, which getUserMedia / WebRTC require
            wv.loadDataWithBaseURL(Store.EMBED_ORIGIN, out.toString("UTF-8"), "text/html", "utf-8", null);
        } catch (Exception e) {
            Toast.makeText(this, "Could not start the call", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ── JS bridge ───────────────────────────────────────────
    private final class Bridge {
        @JavascriptInterface
        public String info() {
            try {
                return new JSONObject().put("id", peerId).put("name", name).put("video", wantVideo).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void ready() {
            if (running) return;
            running = true;
            startTs = System.currentTimeMillis() - POLL_MS;
            new Thread(GroupCallActivity.this::pollLoop, "call-poll").start();
        }

        @JavascriptInterface
        public void send(final String to, final String kind, final String data) {
            if ("leave".equals(kind)) leaveSent = true;
            post(to, kind, data);
        }

        @JavascriptInterface
        public void leave() { runOnUiThread(GroupCallActivity.this::finish); }

        @JavascriptInterface
        public void requestCapture() { runOnUiThread(GroupCallActivity.this::askCapture); }

        @JavascriptInterface
        public void stopCapture() { runOnUiThread(() -> ScreenCaptureService.stop(GroupCallActivity.this)); }

        /** Native MP4 recording (runs in the foreground service, works on any screen). */
        @JavascriptInterface
        public boolean nativeRecStart() { return ScreenCaptureService.startRec(); }

        @JavascriptInterface
        public String nativeRecStop() { return ScreenCaptureService.stopRec(); }

        @JavascriptInterface
        public boolean recStart() { return openRec(); }

        @JavascriptInterface
        public void recChunk(String b64) { writeRec(b64); }

        @JavascriptInterface
        public String recStop() { return closeRec(); }
    }

    private void js(final String code) {
        runOnUiThread(() -> { if (wv != null) wv.evaluateJavascript(code, null); });
    }

    // ── Screen capture permission ───────────────────────────
    private void askCapture() {
        try {
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            capturePrompt = true;
            startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            capturePrompt = false;
            Toast.makeText(this, "Screen capture is not available on this device", Toast.LENGTH_LONG).show();
            js("window.onCaptureDenied && window.onCaptureDenied()");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res == RESULT_OK && data != null) {
            try {
                ScreenCaptureService.start(this, res, data);
            } catch (Exception e) {
                capturePrompt = false;
                js("window.onCaptureDenied && window.onCaptureDenied()");
            }
        } else {
            capturePrompt = false;
            js("window.onCaptureDenied && window.onCaptureDenied()");
        }
    }

    /** Keep the call (and screen share) alive in a small floating window when the user goes Home. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!running || capturePrompt || Build.VERSION.SDK_INT < 26) return;
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(9, 16)).build());
        } catch (Throwable ignored) { }
    }

    // ── Local recording file (saved on THIS phone only) ─────
    private synchronized boolean openRec() {
        try {
            closeRec();
            String name = "RajibTutorial_call_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".webm";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/webm");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RajibTutorial");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                recUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (recUri == null) return false;
                recOut = getContentResolver().openOutputStream(recUri);
                recLabel = "Movies/RajibTutorial/" + name;
            } else {
                java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (dir == null) dir = getFilesDir();
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, name);
                recOut = new java.io.FileOutputStream(f);
                recLabel = f.getAbsolutePath();
            }
            return recOut != null;
        } catch (Exception e) {
            recOut = null;
            return false;
        }
    }

    private synchronized void writeRec(String b64) {
        try {
            if (recOut != null && b64 != null && !b64.isEmpty()) recOut.write(Base64.decode(b64, Base64.DEFAULT));
        } catch (Exception ignored) { }
    }

    private synchronized String closeRec() {
        if (recOut == null) return "";
        try { recOut.flush(); recOut.close(); } catch (Exception ignored) { }
        recOut = null;
        if (recUri != null) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(recUri, cv, null, null);
            } catch (Exception ignored) { }
            recUri = null;
        }
        return recLabel;
    }

    private void post(final String to, final String kind, final String data) {
        final long ts = System.currentTimeMillis();
        final String id = PREFIX + String.format(java.util.Locale.US, "%013d", ts) + "_"
            + Integer.toString(new Random().nextInt(1679616) + 1679616, 36).substring(1);
        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("appId", DiscussionScreen.SYNC_APP_ID);
        d.put("room", ROOM);
        d.put("from", peerId);
        d.put("name", name);
        d.put("to", to == null ? "" : to);
        d.put("kind", kind);
        d.put("data", data == null ? "" : data);
        d.put("ts", Long.valueOf(ts));
        try {
            sendEx.execute(() -> {
                try { Fs.set(id, d); myDocs.add(id); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private void pollLoop() {
        boolean first = true;
        while (running) {
            try {
                List<Fs.Doc> docs = Fs.latestChat(PREFIX, 100);
                if (first) {                         // drop stale signals left by crashed calls
                    first = false;
                    long old = System.currentTimeMillis() - 10 * 60 * 1000L;
                    for (Fs.Doc d : docs) {
                        Object t = d.data.get("ts");
                        if (t instanceof Number && ((Number) t).longValue() < old) {
                            try { Fs.delete(d.id); } catch (Exception ignored) { }
                        }
                    }
                }
                Collections.sort(docs, (x, y) -> x.id.compareTo(y.id));    // oldest first
                final JSONArray out = new JSONArray();
                for (Fs.Doc d : docs) {
                    if (!seen.add(d.id)) continue;
                    Object t = d.data.get("ts");
                    long ts = t instanceof Number ? ((Number) t).longValue() : 0;
                    if (ts < startTs) continue;
                    String from = str(d, "from"), to = str(d, "to");
                    if (from.equals(peerId) || !ROOM.equals(str(d, "room"))) continue;
                    if (!to.isEmpty() && !to.equals(peerId)) continue;
                    out.put(new JSONObject().put("from", from).put("name", str(d, "name"))
                        .put("kind", str(d, "kind")).put("data", str(d, "data")).put("ts", ts));
                }
                if (out.length() > 0 && running) {
                    final String js = "window.onSignal && window.onSignal(" + out.toString() + ")";
                    runOnUiThread(() -> { if (wv != null) wv.evaluateJavascript(js, null); });
                }
            } catch (Exception ignored) { }
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
        }
    }

    private static String str(Fs.Doc d, String k) {
        Object o = d.data.get(k);
        return o instanceof String ? (String) o : "";
    }

    @Override
    public void onBackPressed() {
        if (wv != null && running && !leaveSent) wv.evaluateJavascript("leaveCall()", null);
        else super.onBackPressed();
        // fallback if the page never answers
        if (wv != null) wv.postDelayed(() -> { if (!isFinishing()) finish(); }, 15000);
    }

    @Override
    protected void onDestroy() {
        running = false;
        ScreenCaptureService.cb = null;
        ScreenCaptureService.stop(this);
        closeRec();
        if (!leaveSent && peerId != null && !peerId.isEmpty() && startTs > 0) post("", "leave", "");
        final List<String> toDelete = new ArrayList<>(myDocs);
        sendEx.execute(() -> { /* make sure queued sends (incl. leave) are flushed */ });
        sendEx.shutdown();
        if (wv != null) {
            try {
                wv.stopLoading();
                wv.loadUrl("about:blank");
                ((ViewGroup) wv.getParent()).removeView(wv);
                wv.destroy();
            } catch (Exception ignored) { }
            wv = null;
        }
        // remove our signalling documents a bit later, so the others can still read the "leave"
        Store.async(() -> {
            try { sendEx.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            try { Thread.sleep(6000); } catch (InterruptedException ignored) { }
            List<String> all = new ArrayList<>(toDelete);
            all.addAll(myDocs);
            for (String id : new HashSet<>(all)) { try { Fs.delete(id); } catch (Exception ignored) { } }
            return Boolean.TRUE;
        }, null);
        super.onDestroy();
    }
}
