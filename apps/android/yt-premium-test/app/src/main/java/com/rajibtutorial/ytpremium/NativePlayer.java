package com.rajibtutorial.ytpremium;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The one native player of the app (ExoPlayer, lives as long as the process).
 * It plays the mini player, the floating window and the lock-screen / background audio, so none of them depend on the WebView.
 * Every method except prefetch() must be called on the main thread.
 */
final class NativePlayer {
    private NativePlayer() {}

    interface Pos { long ms(); }
    interface Cb { void done(boolean ok, String err); }

    /** set by the floating window's "expand" button; MainActivity reads it when it comes to the front */
    static volatile boolean expandRequested;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Map<String, Object[]> CACHE = new HashMap<>();   // id -> {Res, time}
    private static final Set<String> BUSY = new HashSet<>();
    private static final long TTL = 20 * 60 * 1000L;
    private static ExoPlayer exo;
    private static Context appCtx;
    private static String id, title;
    private static boolean retried;

    // ── stream links (resolved ahead of time while the web player plays, so a hand-over is instant) ──

    private static YtResolver.Res cached(String vid) {
        synchronized (CACHE) {
            Object[] o = CACHE.get(vid);
            if (o != null && System.currentTimeMillis() - (Long) o[1] < TTL) return (YtResolver.Res) o[0];
        }
        return null;
    }

    static void prefetch(final String vid) {
        if (vid == null || cached(vid) != null) return;
        synchronized (BUSY) { if (!BUSY.add(vid)) return; }
        new Thread(() -> {
            try { resolve(vid); } catch (Exception ignored) { }
            finally { synchronized (BUSY) { BUSY.remove(vid); } }
        }).start();
    }

    private static YtResolver.Res resolve(String vid) throws Exception {
        YtResolver.Res r = cached(vid);
        if (r != null) return r;
        r = YtResolver.resolve("https://www.youtube.com/watch?v=" + vid);
        synchronized (CACHE) {
            if (CACHE.size() > 8) CACHE.clear();
            CACHE.put(vid, new Object[]{r, System.currentTimeMillis()});
        }
        return r;
    }

    // ── playback ──

    private static MediaSource src(String url) {
        DefaultHttpDataSource.Factory f = new DefaultHttpDataSource.Factory()
            .setUserAgent(YtResolver.userAgentFor(url))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000);
        return new ProgressiveMediaSource.Factory(f).createMediaSource(MediaItem.fromUri(url));
    }

    static ExoPlayer player() { return exo; }

    private static ExoPlayer ensure(Context app) {
        if (exo == null) {
            appCtx = app;
            exo = new ExoPlayer.Builder(app)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
            exo.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException e) {
                    // expired link: resolve again once and continue where it stopped
                    if (!retried && id != null && appCtx != null) {
                        retried = true;
                        final String v = id, t = title;
                        final long pos = exo.getCurrentPosition();
                        synchronized (CACHE) { CACHE.remove(v); }
                        start(appCtx, v, t, () -> pos, null);
                    }
                }
            });
        }
        return exo;
    }

    /** Resolve (usually instant, see prefetch) and start playing. `pos` is asked right before playback starts. */
    static void start(final Context c, final String vid, final String ttl, final Pos pos, final Cb cb) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                final YtResolver.Res r = resolve(vid);
                H.post(() -> {
                    try {
                        ExoPlayer p = ensure(app);
                        MediaSource s = src(r.url);
                        if (r.audioUrl != null) s = new MergingMediaSource(s, src(r.audioUrl));
                        p.setMediaSource(s, Math.max(0L, pos.ms()));
                        p.prepare();
                        p.setPlayWhenReady(true);
                        boolean again = vid.equals(id);
                        id = vid; title = ttl;
                        if (!again) retried = false;
                        if (cb != null) cb.done(true, null);
                    } catch (Exception e) {
                        if (cb != null) cb.done(false, String.valueOf(e.getMessage()));
                    }
                });
            } catch (final Exception e) {
                H.post(() -> { if (cb != null) cb.done(false, String.valueOf(e.getMessage())); });
            }
        }).start();
    }

    static boolean active() { return exo != null && id != null; }
    static String id() { return id; }
    static String title() { return title == null ? "" : title; }
    static long posMs() { return exo == null ? 0 : Math.max(0, exo.getCurrentPosition()); }

    static boolean isPlaying() {
        return exo != null && exo.getPlayWhenReady() && exo.getPlaybackState() != Player.STATE_ENDED;
    }

    static void toggle() {
        if (exo == null) return;
        if (exo.getPlaybackState() == Player.STATE_ENDED) { exo.seekTo(0); exo.setPlayWhenReady(true); }
        else exo.setPlayWhenReady(!exo.getPlayWhenReady());
    }

    static void stop() {
        if (exo != null) { exo.stop(); exo.clearMediaItems(); }
        id = null; title = null;
    }
}
