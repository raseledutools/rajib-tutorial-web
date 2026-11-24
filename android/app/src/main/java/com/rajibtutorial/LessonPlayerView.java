package com.rajibtutorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Video player for direct / saved (encrypted) lessons with the same on-screen controls as the lesson player
 * (and the exe): play/pause, time, progress bar, speed, quality label, fullscreen, "Rajib Tutorial" watermark.
 * Saved lessons are decrypted on the fly (VideoCache.openLocal), nothing plain is written to disk.
 */
final class LessonPlayerView extends FrameLayout {
    private static final int ACCENT = 0xFFE8365D;
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.75f};

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Aspect surface;
    private final ProgressBar spin;
    private final View cover;               // big play button before the first start / after the end
    private final LinearLayout bar;
    private final Icon pp, fs;
    private final TextView tTime, tDur, tSpeed, tQual;
    private final SeekBar seek;
    private final LinearLayout qMenu;

    private MediaPlayer mp;
    private android.media.MediaDataSource dataSrc;
    private boolean prepared, wantPlay, ended, dragging, started, fsOn;
    private int speedIdx = 0, vw = 0, vh = 0, resumeAt = 0;
    private Runnable onError, onFullscreen;
    private String errMsg = "Could not play this video";

    LessonPlayerView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);

        surface = new Aspect(c);
        addView(surface, new LayoutParams(-1, -1, Gravity.CENTER));
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { try { if (mp != null) mp.setDisplay(holder); } catch (Exception ignored) { } }
            @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { try { if (mp != null) mp.setDisplay(null); } catch (Exception ignored) { } }
        });

        // tap anywhere on the picture: show the bar / toggle play (same rule as the lesson player's shield)
        View shield = new View(c);
        shield.setOnClickListener(v -> onShieldTap());
        addView(shield, new LayoutParams(-1, -1));

        cover = new View(c) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas cv) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(32);
                p.setColor(ACCENT); cv.drawCircle(cx, cy, r, p);
                p.setColor(Color.WHITE);
                Path t = new Path();
                t.moveTo(cx - dp(8), cy - dp(14)); t.lineTo(cx + dp(14), cy); t.lineTo(cx - dp(8), cy + dp(14)); t.close();
                cv.drawPath(t, p);
            }
        };
        cover.setBackgroundColor(Color.BLACK);
        cover.setOnClickListener(v -> toggle());
        addView(cover, new LayoutParams(-1, -1));

        spin = new ProgressBar(c);
        addView(spin, new LayoutParams(dp(44), dp(44), Gravity.CENTER));
        spin.setVisibility(GONE);

        GradTextView wm = new GradTextView(c);
        wm.setText("Rajib Tutorial");
        wm.setTextSize(14);
        wm.setTypeface(Typeface.DEFAULT_BOLD);
        wm.setLetterSpacing(0.05f);
        wm.setAlpha(0.6f);
        LayoutParams wl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        wl.setMargins(0, dp(10), dp(12), 0);
        addView(wm, wl);

        // ── control bar ──
        bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(22), dp(8), dp(6));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0xCC000000});
        bar.setBackground(g);
        bar.setOnClickListener(v -> { /* swallow taps on the bar */ });

        pp = new Icon(c, Icon.PLAY);
        pp.setOnClickListener(v -> { toggle(); showBar(); });
        bar.addView(pp, new LinearLayout.LayoutParams(dp(36), dp(36)));

        tTime = label(c, "0:00");
        bar.addView(tTime);

        seek = new SeekBar(c);
        seek.setMax(1000);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setSecondaryProgressTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && prepared && mp != null) tTime.setText(fmt((long) mp.getDuration() * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; showBar(); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (prepared && mp != null) {
                    try { mp.seekTo((int) ((long) mp.getDuration() * s.getProgress() / 1000)); } catch (Exception ignored) { }
                    if (ended) { ended = false; }
                }
            }
        });
        bar.addView(seek, new LinearLayout.LayoutParams(0, dp(36), 1f));

        tDur = label(c, "0:00");
        bar.addView(tDur);

        tSpeed = label(c, "1x");
        tSpeed.setTypeface(Typeface.DEFAULT_BOLD);
        tSpeed.setMinWidth(dp(34));
        tSpeed.setGravity(Gravity.CENTER);
        tSpeed.setOnClickListener(v -> cycleSpeed());
        bar.addView(tSpeed, new LinearLayout.LayoutParams(-2, dp(36)));
        if (Build.VERSION.SDK_INT < 23) tSpeed.setVisibility(GONE);     // playback speed needs API 23

        tQual = label(c, "");
        tQual.setTypeface(Typeface.DEFAULT_BOLD);
        tQual.setMinWidth(dp(44));
        tQual.setGravity(Gravity.CENTER);
        tQual.setOnClickListener(v -> toggleQualityMenu());
        bar.addView(tQual, new LinearLayout.LayoutParams(-2, dp(36)));
        tQual.setVisibility(GONE);

        fs = new Icon(c, Icon.FS);
        fs.setOnClickListener(v -> { if (onFullscreen != null) onFullscreen.run(); showBar(); });
        bar.addView(fs, new LinearLayout.LayoutParams(dp(36), dp(36)));
        fs.setVisibility(GONE);

        addView(bar, new LayoutParams(-1, -2, Gravity.BOTTOM));
        bar.setVisibility(GONE);

        // quality menu (a saved/direct file has exactly one quality: show it, highlighted, like the exe's list)
        qMenu = new LinearLayout(c);
        qMenu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable mb = new GradientDrawable();
        mb.setColor(0xF214141E);
        mb.setCornerRadius(dp(10));
        qMenu.setBackground(mb);
        qMenu.setPadding(dp(4), dp(4), dp(4), dp(4));
        LayoutParams ql = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        ql.setMargins(0, 0, dp(10), dp(52));
        addView(qMenu, ql);
        qMenu.setVisibility(GONE);
    }

    // ── public API ────────────────────────────────────────────────────────────────────────────────

    /** Shows the fullscreen button; tapping it runs r. */
    void setOnFullscreen(Runnable r) {
        onFullscreen = r;
        fs.setVisibility(r != null ? VISIBLE : GONE);
    }

    void setFullscreenState(boolean on) { fsOn = on; fs.setKind(on ? Icon.FSX : Icon.FS); }
    void setOnError(Runnable r) { onError = r; }
    String lastError() { return errMsg; }

    /** Plays a saved lesson (encrypted copy, decrypted on the fly). */
    void playLocal(String videoId, boolean autoplay) {
        if (Build.VERSION.SDK_INT < 23) { fail("Secure offline lessons need Android 6.0 or newer"); return; }
        try {
            releasePlayer();
            dataSrc = VideoCache.openLocal(getContext(), videoId);
            MediaPlayer m = newPlayer(autoplay);
            m.setDataSource(dataSrc);
            m.prepareAsync();
        } catch (Exception e) {
            fail("Could not open the saved lesson");
        }
    }

    /** Streams a direct video link. */
    void playUrl(String url, boolean autoplay) {
        try {
            releasePlayer();
            MediaPlayer m = newPlayer(autoplay);
            m.setDataSource(url);
            m.prepareAsync();
        } catch (Exception e) {
            fail("Could not play this video");
        }
    }

    void start() {
        wantPlay = true;
        if (prepared && mp != null) {
            try {
                if (ended) { mp.seekTo(0); ended = false; }
                mp.start();
                started = true;
            } catch (Exception ignored) { }
            refresh();
        }
    }

    void pause() {
        wantPlay = false;
        try { if (mp != null && prepared && mp.isPlaying()) mp.pause(); } catch (Exception ignored) { }
        refresh();
    }

    boolean isPlaying() { try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; } }
    int getCurrentPosition() { try { return mp != null && prepared ? mp.getCurrentPosition() : resumeAt; } catch (Exception e) { return 0; } }
    void seekTo(int ms) { try { if (mp != null && prepared) mp.seekTo(ms); else resumeAt = ms; } catch (Exception ignored) { } }

    /** Stops and frees everything (also closes the decrypting source). */
    void stopPlayback() {
        h.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────

    private MediaPlayer newPlayer(boolean autoplay) {
        prepared = false; ended = false; started = false; wantPlay = autoplay;
        spin.setVisibility(VISIBLE);
        cover.setVisibility(VISIBLE);
        MediaPlayer m = new MediaPlayer();
        mp = m;
        try { if (surface.getHolder().getSurface().isValid()) m.setDisplay(surface.getHolder()); } catch (Exception ignored) { }
        m.setOnPreparedListener(p -> {
            if (mp != p) return;
            prepared = true;
            spin.setVisibility(GONE);
            vw = p.getVideoWidth(); vh = p.getVideoHeight();
            surface.setVideoSize(vw, vh);
            tDur.setText(fmt(p.getDuration()));
            if (vh > 0) { tQual.setText(vh + "p"); tQual.setVisibility(VISIBLE); }
            applySpeed();
            if (resumeAt > 0) { try { p.seekTo(resumeAt); } catch (Exception ignored) { } resumeAt = 0; }
            if (wantPlay) { try { p.start(); started = true; } catch (Exception ignored) { } }
            refresh();
            showBar();
            tick();
        });
        m.setOnVideoSizeChangedListener((p, w, hh) -> { vw = w; vh = hh; surface.setVideoSize(w, hh); if (hh > 0) { tQual.setText(hh + "p"); tQual.setVisibility(VISIBLE); } });
        m.setOnCompletionListener(p -> { if (mp != p) return; ended = true; wantPlay = false; refresh(); bar.setVisibility(VISIBLE); h.removeCallbacks(hideRun); });
        m.setOnErrorListener((p, what, extra) -> { if (mp == p) fail("Could not play this video"); return true; });
        m.setOnInfoListener((p, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spin.setVisibility(VISIBLE);
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) spin.setVisibility(GONE);
            return false;
        });
        return m;
    }

    private void fail(String msg) {
        errMsg = msg;
        spin.setVisibility(GONE);
        if (onError != null) onError.run();
    }

    private void releasePlayer() {
        MediaPlayer m = mp;
        mp = null;
        prepared = false;
        if (m != null) { try { m.setOnPreparedListener(null); m.setOnErrorListener(null); m.setOnCompletionListener(null); m.release(); } catch (Exception ignored) { } }
        if (Build.VERSION.SDK_INT >= 23 && dataSrc != null) { try { dataSrc.close(); } catch (Exception ignored) { } }
        dataSrc = null;
    }

    private void onShieldTap() {
        if (qMenu.getVisibility() == VISIBLE) { qMenu.setVisibility(GONE); return; }
        if (bar.getVisibility() != VISIBLE) showBar(); else toggle();
    }

    private void toggle() {
        if (!prepared || mp == null) { wantPlay = true; spin.setVisibility(VISIBLE); return; }
        if (isPlaying()) pause(); else start();
        showBar();
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % SPEEDS.length;
        tSpeed.setText((SPEEDS[speedIdx] == (int) SPEEDS[speedIdx] ? String.valueOf((int) SPEEDS[speedIdx]) : String.valueOf(SPEEDS[speedIdx])) + "x");
        applySpeed();
        showBar();
    }

    private void applySpeed() {
        if (Build.VERSION.SDK_INT < 23 || mp == null || !prepared) return;
        try {
            boolean was = mp.isPlaying();
            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(SPEEDS[speedIdx]));
            if (!was && mp.isPlaying()) mp.pause();      // setPlaybackParams can start a paused player on some devices
        } catch (Exception ignored) { }
    }

    private void toggleQualityMenu() {
        if (qMenu.getVisibility() == VISIBLE) { qMenu.setVisibility(GONE); return; }
        qMenu.removeAllViews();
        TextView it = label(getContext(), tQual.getText() + (tQual.getText().length() > 0 ? "  ✓" : ""));
        it.setTextSize(13);
        it.setTypeface(Typeface.DEFAULT_BOLD);
        it.setGravity(Gravity.CENTER);
        it.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable on = new GradientDrawable();
        on.setColor(ACCENT);
        on.setCornerRadius(dp(7));
        it.setBackground(on);
        qMenu.addView(it);
        qMenu.setVisibility(VISIBLE);
        showBar();
    }

    private final Runnable hideRun = new Runnable() {
        @Override public void run() { if (isPlaying()) { bar.setVisibility(GONE); qMenu.setVisibility(GONE); } }
    };

    private void showBar() {
        bar.setVisibility(VISIBLE);
        h.removeCallbacks(hideRun);
        if (isPlaying()) h.postDelayed(hideRun, 2800);
    }

    private void refresh() {
        boolean pl = isPlaying();
        pp.setKind(pl ? Icon.PAUSE : Icon.PLAY);
        cover.setVisibility(started && !ended ? GONE : VISIBLE);
        if (!pl) { bar.setVisibility(VISIBLE); h.removeCallbacks(hideRun); }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (mp != null && prepared) {
                try {
                    int d = mp.getDuration(), c = mp.getCurrentPosition();
                    if (!dragging && d > 0) { seek.setProgress((int) ((long) c * 1000 / d)); tTime.setText(fmt(c)); }
                    boolean pl = mp.isPlaying();
                    if (pl && bar.getVisibility() == VISIBLE && !hasMessages()) { /* auto-hide handled by hideRun */ }
                    pp.setKind(pl ? Icon.PAUSE : Icon.PLAY);
                } catch (Exception ignored) { }
            }
            if (mp != null) h.postDelayed(this, 400);
        }
        private boolean hasMessages() { return false; }
    };

    private void tick() { h.removeCallbacks(ticker); h.post(ticker); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mp != null) tick();
    }

    @Override
    protected void onDetachedFromWindow() {
        h.removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    private static String fmt(long ms) {
        long t = Math.max(0, ms / 1000);
        return (t / 60) + ":" + (t % 60 < 10 ? "0" : "") + (t % 60);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    // ── small helper views ────────────────────────────────────────────────────────────────────────

    /** SurfaceView that letterboxes the video inside its parent. */
    private static final class Aspect extends SurfaceView {
        private int vw, vh;
        Aspect(Context c) { super(c); }
        void setVideoSize(int w, int h) { vw = w; vh = h; requestLayout(); }
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            if (vw > 0 && vh > 0) {
                if ((long) w * vh > (long) h * vw) w = (int) ((long) h * vw / vh); else h = (int) ((long) w * vh / vw);
            }
            setMeasuredDimension(w, h);
        }
    }

    /** Vector-style icons drawn with paths (play, pause, fullscreen, exit fullscreen). */
    private static final class Icon extends View {
        static final int PLAY = 0, PAUSE = 1, FS = 2, FSX = 3;
        private int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context c, int k) {
            super(c);
            kind = k;
            p.setColor(Color.WHITE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        void setKind(int k) { if (k != kind) { kind = k; invalidate(); } }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) * (kind >= FS ? 0.34f : 0.5f), cx = w / 2f, cy = h / 2f, u = s / 12f;   // 24-unit box (fullscreen glyph smaller)
            p.setStyle(Paint.Style.FILL);
            if (kind == PLAY) {
                Path t = new Path();
                t.moveTo(cx - 5 * u, cy - 7.5f * u); t.lineTo(cx + 8 * u, cy); t.lineTo(cx - 5 * u, cy + 7.5f * u); t.close();
                cv.drawPath(t, p);
            } else if (kind == PAUSE) {
                cv.drawRect(cx - 6 * u, cy - 8 * u, cx - 2 * u, cy + 8 * u, p);
                cv.drawRect(cx + 2 * u, cy - 8 * u, cx + 6 * u, cy + 8 * u, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * u);
                float a = 9 * u, b = 4 * u;
                Path t = new Path();
                if (kind == FS) {          // corners pointing outwards
                    t.moveTo(cx - a, cy - a + b); t.lineTo(cx - a, cy - a); t.lineTo(cx - a + b, cy - a);
                    t.moveTo(cx + a - b, cy - a); t.lineTo(cx + a, cy - a); t.lineTo(cx + a, cy - a + b);
                    t.moveTo(cx - a, cy + a - b); t.lineTo(cx - a, cy + a); t.lineTo(cx - a + b, cy + a);
                    t.moveTo(cx + a - b, cy + a); t.lineTo(cx + a, cy + a); t.lineTo(cx + a, cy + a - b);
                } else {                   // corners pointing inwards
                    t.moveTo(cx - a, cy - b); t.lineTo(cx - b, cy - b); t.lineTo(cx - b, cy - a);
                    t.moveTo(cx + b, cy - a); t.lineTo(cx + b, cy - b); t.lineTo(cx + a, cy - b);
                    t.moveTo(cx - a, cy + b); t.lineTo(cx - b, cy + b); t.lineTo(cx - b, cy + a);
                    t.moveTo(cx + b, cy + a); t.lineTo(cx + b, cy + b); t.lineTo(cx + a, cy + b);
                }
                cv.drawPath(t, p);
            }
        }
    }

    /** Watermark text with the exe's colour sweep (orange → pink → lavender → teal). */
    private static final class GradTextView extends TextView {
        GradTextView(Context c) { super(c); setShadowLayer(0, 0, 0, 0); }
        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            super.onLayout(ch, l, t, r, b);
            getPaint().setShader(new LinearGradient(0, 0, Math.max(1, r - l), 0,
                new int[] {0xFFFFB347, 0xFFFF5C8A, 0xFFC4B5FD, 0xFF5EEAD4}, null, Shader.TileMode.CLAMP));
        }
        @Override public boolean onTouchEvent(MotionEvent e) { return false; }
    }
}
