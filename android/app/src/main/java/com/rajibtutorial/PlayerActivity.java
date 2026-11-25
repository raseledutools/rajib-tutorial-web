package com.rajibtutorial;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Landscape, screenshot-protected fullscreen player. */
public class PlayerActivity extends Activity {
    private WebView web;
    private LessonPlayerView video;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (inMultiWindow()) {                 // split-screen / floating window: never play course video here
            android.widget.Toast.makeText(this, "Split-screen / floating window এ ভিডিও চলবে না", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        hideSystemBars();

        String url = getIntent().getStringExtra("url");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        String embed = Store.ytEmbed(url, true);
        if (embed != null) {
            web = Media.newPlayerWebView(this);
            root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Media.load(web, embed);
        } else if (url != null && !url.isEmpty()) {
            video = new LessonPlayerView(this);
            root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            video.setOnFullscreen(this::finish);              // the bar's fullscreen button exits here
            video.setFullscreenState(true);
            video.setOnError(() -> android.widget.Toast.makeText(this, video.lastError(), android.widget.Toast.LENGTH_SHORT).show());
            String vid = getIntent().getStringExtra("vid");
            if (vid != null && !vid.isEmpty() && VideoCache.getLocalPath(this, vid) != null) video.playLocal(vid, true);
            else video.playUrl(url, true);
        }

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(20);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0x88000000);
        int s = Math.round(44 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(s, s);
        cl.gravity = Gravity.TOP | Gravity.END;
        cl.setMargins(0, 8, 8, 0);
        root.addView(close, cl);
        close.setOnClickListener(v -> finish());
    }

    private boolean inMultiWindow() {
        return android.os.Build.VERSION.SDK_INT >= 24 && (isInMultiWindowMode() || isInPictureInPictureMode());
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        if (isInMultiWindowMode) {
            if (video != null && video.isPlaying()) video.pause();
            finish();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (inMultiWindow()) {                 // freeform / pop-up window (configChanges swallows the restart)
            if (video != null && video.isPlaying()) video.pause();
            finish();
        }
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        Media.destroy(web);
        web = null;
        if (video != null) { video.stopPlayback(); video = null; }
        super.onDestroy();
    }
}
