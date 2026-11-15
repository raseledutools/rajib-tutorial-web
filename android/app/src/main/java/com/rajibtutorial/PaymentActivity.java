package com.rajibtutorial;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.net.URI;

/**
 * In-app bKash payment page. The whole payment (bKash checkout + the result page of our backend)
 * stays inside the app — no external browser. When the screen closes, the previous screen
 * reloads the member data so Premium shows up right away.
 */
public class PaymentActivity extends AppCompatActivity {
    private static final String EXTRA_URL = "pay_url";
    private static final int BKASH = 0xFFE2136E;

    /** Set when a payment page was opened; the home screen reloads once and clears it. */
    static volatile boolean pending = false;

    static void start(Context c, String url) {
        pending = true;
        c.startActivity(new Intent(c, PaymentActivity.class).putExtra(EXTRA_URL, url));
    }

    /** True for bKash checkout hosts and our own payment backend. */
    static boolean isPaymentUrl(String url) {
        try {
            String h = URI.create(url).getHost();
            if (h == null) return false;
            h = h.toLowerCase();
            String back = URI.create(Store.BACKEND).getHost();
            return h.equals("bka.sh") || h.endsWith(".bka.sh")
                || h.equals("bkash.com") || h.endsWith(".bkash.com")
                || h.equals(back);
        } catch (Exception e) {
            return false;
        }
    }

    private WebView web;
    private ProgressBar bar;
    private TextView title, close;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);   // no screenshots of the payment page
        w.setStatusBarColor(BKASH);
        w.setNavigationBarColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(BKASH);
        top.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 14), 0);
        close = Ui.tv(this, "✕", 20, Ui.WHITE, true);
        close.setGravity(Gravity.CENTER);
        Ui.pressFx(close);
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        title = Ui.ellipsized(this, "bKash Payment", 16, Ui.WHITE, true, 1);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Ui.tv(this, "🔒 Secure", 12, 0xE6FFFFFF, false));
        root.addView(top, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        web = new WebView(this);
        body.addView(web, new FrameLayout.LayoutParams(-1, -1));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        body.addView(bar, new FrameLayout.LayoutParams(-1, Ui.dp(this, 3), Gravity.TOP));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                String sch = r.getUrl().getScheme();
                // stay inside the app: only http(s) pages load; app-jump links (intent:, market:, bkash:) are ignored
                return sch == null || !(sch.equals("https") || sch.equals("http"));
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
                boolean ours = false;
                try { ours = URI.create(Store.BACKEND).getHost().equals(URI.create(url).getHost()); } catch (Exception ignored) { }
                if (ours) {                      // result page of our backend (after bKash redirects back)
                    title.setText("Payment status");
                    close.setText("Done");
                    close.setTextSize(13);
                    close.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(PaymentActivity.this, 64), Ui.dp(PaymentActivity.this, 52)));
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    v.loadDataWithBaseURL(null,
                        "<html><body style='font-family:sans-serif;text-align:center;padding:60px 24px;color:#444'>"
                        + "<h3>Could not load the payment page</h3>"
                        + "<p>Please check your internet connection, close this screen and try again.</p></body></html>",
                        "text/html", "utf-8", null);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) { bar.setProgress(p); }
        });

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || !url.startsWith("http")) { finish(); return; }
        web.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
