package com.rajibtutorial;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Study Tools — PDF / photo / QR utilities that run 100% offline.
 *
 * The UI is the bundled page assets/study_tools/index.html (all libraries are local,
 * nothing is downloaded). It is served through WebViewAssetLoader on a private https
 * origin, every request to any other host is blocked, and the page talks to Java through
 * the window.AndroidTools bridge (save to Downloads, open file, clipboard, open link).
 */
public class StudyToolsActivity extends AppCompatActivity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/study_tools/index.html";
    private static final String SAVE_FOLDER = "StudyTools";
    private static final int REQ_CAMERA = 710;
    private static final int REQ_STORAGE = 711;
    private static final int PAGE_BG = 0xFFF8FAFC;

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private PermissionRequest pendingPerm;
    private ValueCallback<Uri[]> filePathCb;

    private final Map<String, SaveJob> jobs = new ConcurrentHashMap<>();
    private final Set<String> savedUris = Collections.synchronizedSet(new HashSet<String>());

    private final ActivityResultLauncher<Intent> fileLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (filePathCb == null) return;
            Uri[] res = WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData());
            filePathCb.onReceiveValue(res);
            filePathCb = null;
        });

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        getWindow().setStatusBarColor(0xFF7B2FF7);
        getWindow().setNavigationBarColor(PAGE_BG);
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        // ── Layout: native top bar + WebView ──
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_header);
        bar.setElevation(Ui.dp(this, 4));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(24);
        back.setTextColor(0xFFFFFFFF);
        back.setGravity(Gravity.CENTER);
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> handleBack());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 56), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Study Tools");
        title.setTextSize(19);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ico = new TextView(this);
        ico.setText("🧰");
        ico.setTextSize(22);
        LinearLayout.LayoutParams icoLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        icoLp.rightMargin = Ui.dp(this, 16);
        bar.addView(ico, icoLp);

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        webView = new WebView(this);
        webView.setBackgroundColor(PAGE_BG);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // ── WebView: local assets only ──
        assetLoader = new WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.addJavascriptInterface(new ToolsBridge(), "AndroidTools");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) {
                    WebResourceResponse r = assetLoader.shouldInterceptRequest(u);
                    return r != null ? r : blocked(404, "Not found");
                }
                String sc = u.getScheme();
                if ("data".equals(sc) || "blob".equals(sc) || "about".equals(sc)) return null;
                return blocked(403, "Offline");   // nothing leaves the device
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !HOST.equals(req.getUrl().getHost());   // never navigate away from the tools page
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                try {
                    fileLauncher.launch(buildPickerIntent(p));
                } catch (Exception e) {
                    filePathCb = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    boolean video = false;
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) video = true;
                    }
                    if (!video || !HOST.equals(req.getOrigin().getHost())) { req.deny(); return; }
                    if (ContextCompat.checkSelfPermission(StudyToolsActivity.this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingPerm = req;
                        ActivityCompat.requestPermissions(StudyToolsActivity.this,
                            new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (request == pendingPerm) pendingPerm = null;
            }

            // Native-looking dialogs (no "The page at https://… says" header)
            @Override
            public boolean onJsAlert(WebView v, String url, String message, final JsResult result) {
                new AlertDialog.Builder(StudyToolsActivity.this)
                    .setMessage(message)
                    .setPositiveButton("OK", (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String message, final JsResult result) {
                new AlertDialog.Builder(StudyToolsActivity.this)
                    .setMessage(message)
                    .setPositiveButton("OK", (d, w) -> result.confirm())
                    .setNegativeButton("Cancel", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
                return true;
            }
        });

        webView.loadUrl(START_URL);
    }

    private static WebResourceResponse blocked(int code, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", code, reason,
            Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static Intent buildPickerIntent(WebChromeClient.FileChooserParams p) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        List<String> mimes = new ArrayList<>();
        String[] acc = p.getAcceptTypes();
        if (acc != null) {
            for (String a : acc) {
                if (a == null) continue;
                a = a.trim();
                if (a.isEmpty()) continue;
                if (a.startsWith(".")) {
                    String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.substring(1).toLowerCase(Locale.ROOT));
                    if (m != null) mimes.add(m);
                } else {
                    mimes.add(a);
                }
            }
        }
        if (mimes.isEmpty()) {
            i.setType("*/*");
        } else if (mimes.size() == 1) {
            i.setType(mimes.get(0));
        } else {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        if (p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        return i;
    }

    // ── Back handling: let the page close its own overlay (live scanner) first ──
    private void handleBack() {
        if (webView == null) { finish(); return; }
        webView.evaluateJavascript(
            "(function(){return !!(window.__nativeBack && window.__nativeBack());})()",
            value -> { if (!"true".equals(value)) finish(); });
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == REQ_CAMERA) {
            if (pendingPerm != null) {
                if (ok) pendingPerm.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                else pendingPerm.deny();
                pendingPerm = null;
            }
        } else if (code == REQ_STORAGE) {
            Toast.makeText(this, ok ? "Storage allowed — tap Download again" : "Storage permission denied",
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        // release the camera if the app goes to background
        if (webView != null) {
            webView.evaluateJavascript("window.closeLiveCameraScanner&&window.closeLiveCameraScanner()", null);
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        for (SaveJob j : jobs.values()) j.abort(this);
        jobs.clear();
        if (filePathCb != null) { filePathCb.onReceiveValue(null); filePathCb = null; }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ═════════════════════════ JS ↔ Java bridge ═════════════════════════

    /** One file being written chunk by chunk (large PDFs/PNGs never travel as one giant string). */
    private static final class SaveJob {
        OutputStream os;
        Uri uri;        // API 29+: MediaStore row
        File file;      // API < 29: file in public Downloads
        String name;
        String mime;

        void abort(Context c) {
            try { if (os != null) os.close(); } catch (Exception ignored) { }
            try {
                if (uri != null) c.getContentResolver().delete(uri, null, null);
                if (file != null) //noinspection ResultOfMethodCallIgnored
                    file.delete();
            } catch (Exception ignored) { }
        }
    }

    public final class ToolsBridge {

        @JavascriptInterface
        public boolean isNative() { return true; }

        /** Returns a job id, or null if the file can't be created. */
        @JavascriptInterface
        public String beginSave(String name, String mime) {
            try {
                SaveJob j = new SaveJob();
                j.name = safeName(name);
                j.mime = (mime == null || mime.isEmpty()) ? guessMime(j.name) : mime;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, j.name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, j.mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER);
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return null;
                    j.uri = uri;
                    OutputStream raw = getContentResolver().openOutputStream(uri);
                    if (raw == null) { j.abort(StudyToolsActivity.this); return null; }
                    j.os = new BufferedOutputStream(raw);
                } else {
                    if (ContextCompat.checkSelfPermission(StudyToolsActivity.this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        runOnUiThread(() -> ActivityCompat.requestPermissions(StudyToolsActivity.this,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
                        return null;
                    }
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_FOLDER);
                    if (!dir.exists() && !dir.mkdirs()) return null;
                    j.file = uniqueFile(dir, j.name);
                    j.os = new BufferedOutputStream(new FileOutputStream(j.file));
                }
                String id = UUID.randomUUID().toString();
                jobs.put(id, j);
                return id;
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public boolean appendChunk(String id, String base64) {
            SaveJob j = jobs.get(id);
            if (j == null || j.os == null) return false;
            try {
                j.os.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Returns JSON: {ok, name, uri, mime, where} or {ok:false, error}. */
        @JavascriptInterface
        public String endSave(String id) {
            SaveJob j = jobs.remove(id);
            JSONObject out = new JSONObject();
            try {
                if (j == null) throw new IllegalStateException("unknown save job");
                j.os.flush();
                j.os.close();
                String finalName = j.name;
                String uriStr;
                if (j.uri != null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(j.uri, cv, null, null);
                    finalName = displayName(j.uri, j.name);
                    uriStr = j.uri.toString();
                } else {
                    finalName = j.file.getName();
                    MediaScannerConnection.scanFile(StudyToolsActivity.this,
                        new String[]{j.file.getAbsolutePath()}, new String[]{j.mime}, null);
                    uriStr = FileProvider.getUriForFile(StudyToolsActivity.this,
                        getPackageName() + ".provider", j.file).toString();
                }
                savedUris.add(uriStr);
                out.put("ok", true);
                out.put("name", finalName);
                out.put("uri", uriStr);
                out.put("mime", j.mime);
                out.put("where", "Downloads/" + SAVE_FOLDER);
            } catch (Exception e) {
                if (j != null) j.abort(StudyToolsActivity.this);
                try {
                    out.put("ok", false);
                    out.put("error", String.valueOf(e.getMessage()));
                } catch (Exception ignored) { }
            }
            return out.toString();
        }

        @JavascriptInterface
        public void abortSave(String id) {
            SaveJob j = jobs.remove(id);
            if (j != null) j.abort(StudyToolsActivity.this);
        }

        /** Only files this bridge saved can be opened. */
        @JavascriptInterface
        public boolean openFile(final String uri, final String mime) {
            if (uri == null || !savedUris.contains(uri)) return false;
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(uri), (mime == null || mime.isEmpty()) ? "*/*" : mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(StudyToolsActivity.this, "No app found to open this file", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) { }
            });
            return true;
        }

        /** http(s) links only. */
        @JavascriptInterface
        public boolean openUrl(final String url) {
            if (url == null) return false;
            final Uri u = Uri.parse(url.trim());
            String sc = u.getScheme();
            if (!"http".equalsIgnoreCase(sc) && !"https".equalsIgnoreCase(sc)) return false;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    Toast.makeText(StudyToolsActivity.this, "Can't open this link", Toast.LENGTH_SHORT).show();
                }
            });
            return true;
        }

        @JavascriptInterface
        public boolean copyText(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Study Tools", text == null ? "" : text));
            });
            return true;
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(StudyToolsActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }

    // ── helpers ──
    private static String safeName(String n) {
        if (n == null) n = "";
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) n = "file";
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    private static String guessMime(String name) {
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    private String displayName(Uri uri, String fallback) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return fallback;
    }
}
