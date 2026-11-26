package com.rajibtutorial.ytpremium;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;

/**
 * Floating video window drawn over other apps (needs "Display over other apps").
 * Drag it anywhere, drag the ◢ corner to resize, ⤢ opens the app again, ✕ closes it.
 */
final class FloatingWindow {
    private FloatingWindow() {}

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static WindowManager wm;
    private static FrameLayout view;
    private static WindowManager.LayoutParams lp;
    private static TextureView tv;
    private static TextView playBtn;
    private static View ctl;
    private static Player.Listener lis;
    private static int wPx, minW, maxW;
    private static float aspect = 16f / 9f;
    private static final Runnable HIDE_CTL = () -> { if (ctl != null) ctl.setVisibility(View.GONE); };

    static boolean can(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }
    static boolean showing() { return view != null; }

    private static void refreshPlay() {
        if (playBtn != null) playBtn.setText(NativePlayer.isPlaying() ? "⏸" : "▶");
    }

    private static void showCtl() {
        if (ctl == null) return;
        ctl.setVisibility(View.VISIBLE);
        H.removeCallbacks(HIDE_CTL);
        H.postDelayed(HIDE_CTL, 3000);
    }

    private static void applySize() {
        if (view == null || lp == null) return;
        lp.width = wPx;
        lp.height = (int) (wPx / aspect);
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
    }

    private static TextView glyph(Context c, String t, int sizeDp, float d) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x99000000);
        v.setBackground(g);
        v.setLayoutParams(new FrameLayout.LayoutParams((int) (sizeDp * d), (int) (sizeDp * d)));
        return v;
    }

    @SuppressLint("ClickableViewAccessibility")
    static void show(final Context ctx) {
        if (view != null || !NativePlayer.active() || NativePlayer.player() == null) return;
        final Context c = ctx.getApplicationContext();
        if (!can(c)) return;
        final float d = c.getResources().getDisplayMetrics().density;
        final int sw = c.getResources().getDisplayMetrics().widthPixels;
        final int sh = c.getResources().getDisplayMetrics().heightPixels;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        minW = (int) (150 * d);
        maxW = (int) (sw * 0.95f);
        wPx = Math.min((int) (260 * d), (int) (sw * 0.7f));
        try {
            VideoSize vs = NativePlayer.player().getVideoSize();
            if (vs.width > 0 && vs.height > 0) aspect = Math.max(0.5f, Math.min(2.4f, (float) vs.width / vs.height));
        } catch (Exception ignored) { }

        view = new FrameLayout(c);
        view.setBackgroundColor(Color.BLACK);
        view.setClipToOutline(true);
        final float radius = 12 * d;
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });

        tv = new TextureView(c);
        view.addView(tv, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout box = new FrameLayout(c);
        box.setBackgroundColor(0x55000000);
        ctl = box;
        view.addView(box, new FrameLayout.LayoutParams(-1, -1));

        TextView expand = glyph(c, "⤢", 36, d);
        ((FrameLayout.LayoutParams) expand.getLayoutParams()).gravity = Gravity.TOP | Gravity.START;
        ((FrameLayout.LayoutParams) expand.getLayoutParams()).setMargins((int) (6 * d), (int) (6 * d), 0, 0);
        TextView close = glyph(c, "✕", 36, d);
        ((FrameLayout.LayoutParams) close.getLayoutParams()).gravity = Gravity.TOP | Gravity.END;
        ((FrameLayout.LayoutParams) close.getLayoutParams()).setMargins(0, (int) (6 * d), (int) (6 * d), 0);
        playBtn = glyph(c, "⏸", 48, d);
        ((FrameLayout.LayoutParams) playBtn.getLayoutParams()).gravity = Gravity.CENTER;
        TextView handle = glyph(c, "◢", 32, d);
        ((FrameLayout.LayoutParams) handle.getLayoutParams()).gravity = Gravity.BOTTOM | Gravity.END;
        ((FrameLayout.LayoutParams) handle.getLayoutParams()).setMargins(0, 0, (int) (4 * d), (int) (4 * d));
        box.addView(expand);
        box.addView(close);
        box.addView(playBtn);
        box.addView(handle);

        expand.setOnClickListener(v -> {
            NativePlayer.expandRequested = true;
            hide();
            Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try { c.startActivity(i); } catch (Exception ignored) { }
        });
        close.setOnClickListener(v -> {
            NativePlayer.stop();
            hide();
            if (!MainActivity.ALIVE) KeepAliveService.stop(c);
        });
        playBtn.setOnClickListener(v -> { NativePlayer.toggle(); refreshPlay(); showCtl(); });

        final float[] rs = new float[2];
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: rs[0] = e.getRawX(); rs[1] = wPx; return true;
                case MotionEvent.ACTION_MOVE:
                    wPx = (int) Math.max(minW, Math.min(maxW, rs[1] + (e.getRawX() - rs[0])));
                    applySize();
                    showCtl();
                    return true;
                default: return true;
            }
        });

        final float[] st = new float[4];
        final boolean[] drag = {false};
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY(); st[2] = lp.x; st[3] = lp.y; drag[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    if (!drag[0] && Math.hypot(dx, dy) > 8 * d) drag[0] = true;
                    if (drag[0]) {
                        lp.x = (int) Math.max(-wPx / 2f, Math.min(sw - wPx / 2f, st[2] + dx));
                        lp.y = (int) Math.max(0, Math.min(sh - 60 * d, st[3] + dy));
                        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!drag[0]) showCtl();
                    return true;
                default: return true;
            }
        });

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(wPx, (int) (wPx / aspect), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sw - wPx - (int) (16 * d);
        lp.y = (int) (96 * d);

        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            view = null; tv = null; ctl = null; playBtn = null;
            return;
        }

        lis = new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean b) { refreshPlay(); }
            @Override public void onPlaybackStateChanged(int s) { refreshPlay(); }
            @Override public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) {
                    aspect = Math.max(0.5f, Math.min(2.4f, (float) vs.width / vs.height));
                    applySize();
                }
            }
        };
        NativePlayer.player().addListener(lis);
        NativePlayer.player().setVideoTextureView(tv);
        refreshPlay();
        showCtl();
    }

    static void hide() {
        H.removeCallbacks(HIDE_CTL);
        try {
            if (NativePlayer.player() != null) {
                if (lis != null) NativePlayer.player().removeListener(lis);
                if (tv != null) NativePlayer.player().clearVideoTextureView(tv);
            }
        } catch (Exception ignored) { }
        lis = null;
        try { if (wm != null && view != null) wm.removeView(view); } catch (Exception ignored) { }
        view = null; tv = null; ctl = null; playBtn = null;
    }
}
