package com.rajibtutorial;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Office Reader (the exe's tabbed reader): Word, Excel/CSV, PowerPoint, images, video, audio, Markdown and text,
 * all rendered on the phone from the app's own assets — nothing is uploaded. PDFs go to the app's native PDF reader.
 */
public class OfficeReaderActivity extends AppCompatActivity {
    private static final int REQ_PICK = 4417;
    private WebView web;
    private ValueCallback<Uri[]> chooser;

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (AppSettings.screenProtect(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // the reader keeps its "frequently read" list in IndexedDB
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setBuiltInZoomControls(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // links inside a document must never take the reader somewhere else
                return !"appassets.androidplatform.net".equals(r.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(i, REQ_PICK);
                } catch (Exception e) {
                    chooser.onReceiveValue(null);
                    chooser = null;
                    Toast.makeText(OfficeReaderActivity.this, "File picker খোলা যায়নি", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                String t = m.message();
                if (t != null && t.startsWith("__RT_CMD__:")) {
                    if (t.endsWith(":back")) finish();
                    return true;
                }
                return super.onConsoleMessage(m);
            }
        });

        web.loadUrl("https://appassets.androidplatform.net/assets/office/viewer.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;
        List<Uri> keep = new ArrayList<>();
        if (res == Activity.RESULT_OK && data != null) {
            List<Uri> all = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) all.add(cd.getItemAt(i).getUri());
            else if (data.getData() != null) all.add(data.getData());
            for (Uri u : all) {
                if (isPdf(u)) openPdf(u); else keep.add(u);
            }
        }
        if (chooser != null) {
            chooser.onReceiveValue(keep.isEmpty() ? null : keep.toArray(new Uri[0]));
            chooser = null;
        }
    }

    private boolean isPdf(Uri u) {
        String n = displayName(u);
        String m = getContentResolver().getType(u);
        return (n != null && n.toLowerCase(Locale.ROOT).endsWith(".pdf")) || "application/pdf".equals(m);
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return u.getLastPathSegment();
    }

    /** PDFs open in the app's own native PDF reader. */
    private void openPdf(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/pdf");
            i.setClass(this, PdfViewActivity.class);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "PDF খোলা যায়নি", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (chooser != null) { chooser.onReceiveValue(null); chooser = null; }
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
