package com.rajibtutorial.ytpremium;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a YouTube watch URL into a direct, downloadable progressive MP4 link (video + audio in one file,
 * normally 360p, sometimes 720p) using NewPipeExtractor. This is the Android counterpart of the exe's yt-dlp step.
 * Must be called from a background thread.
 */
final class YtResolver {
    private YtResolver() {}

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static boolean inited = false;

    private static synchronized void init() {
        if (inited) return;
        NewPipe.init(new Dl(), Localization.DEFAULT);
        inited = true;
    }

    /** What to download: `url` alone is a ready video+audio file; with `audioUrl` the two must be merged on the phone. */
    static final class Res {
        final String url, audioUrl;
        Res(String url, String audioUrl) { this.url = url; this.audioUrl = audioUrl; }
    }

    /**
     * YouTube rarely offers a ready video+audio file any more (at most one 360p). Like yt-dlp does on the exe, prefer a separate
     * H.264 video (up to 720p) + AAC audio pair; VideoCache downloads both and merges them with MediaMuxer.
     * @throws Exception when nothing downloadable was found.
     */
    static Res resolve(String watchUrl) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(watchUrl);
        VideoStream muxed = bestMuxed(info.getVideoStreams());
        VideoStream vo = bestVideoOnly(info.getVideoOnlyStreams());
        AudioStream au = bestAudio(info.getAudioStreams());
        if (vo != null && au != null && (muxed == null || height(vo) > height(muxed))) return new Res(vo.getContent(), au.getContent());
        if (muxed != null) return new Res(muxed.getContent(), null);
        throw new IOException("No downloadable stream for this video (ready " + size(info.getVideoStreams())
            + ", video-only " + size(info.getVideoOnlyStreams()) + ", audio " + size(info.getAudioStreams()) + ")");
    }

    /** One choice in the "Save" quality list. bytes = total download size (video + audio), -1 when YouTube does not say. */
    static final class Opt {
        final String label;
        final long bytes;
        final Res res;
        Opt(String label, long bytes, Res res) { this.label = label; this.bytes = bytes; this.res = res; }
        int height() { try { return Integer.parseInt(label.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; } }
    }

    /** Every quality that can be saved (H.264 mp4 up to 1080p + AAC audio, or a ready 360p file), biggest first, with sizes. */
    static List<Opt> options(String watchUrl) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(watchUrl);
        AudioStream au = bestAudio(info.getAudioStreams());
        long aBytes = au == null ? -1 : contentLength(au.getContent());
        List<Opt> out = new ArrayList<>();
        HashMap<Integer, VideoStream> byH = new HashMap<>();
        if (au != null && info.getVideoOnlyStreams() != null) {
            for (VideoStream v : info.getVideoOnlyStreams()) {
                if (!v.isUrl() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
                if (v.getFormat() != MediaFormat.MPEG_4) continue;
                String cd = v.getCodec();
                if (cd != null && !cd.toLowerCase().startsWith("avc1")) continue;
                int h = height(v);
                if (h <= 0 || h > 1080) continue;
                VideoStream old = byH.get(h);
                if (old == null || contentLength(v.getContent()) > contentLength(old.getContent())) byH.put(h, v);
            }
            for (java.util.Map.Entry<Integer, VideoStream> en : byH.entrySet()) {
                long vb = contentLength(en.getValue().getContent());
                out.add(new Opt(en.getKey() + "p", vb > 0 && aBytes > 0 ? vb + aBytes : -1, new Res(en.getValue().getContent(), au.getContent())));
            }
        }
        VideoStream muxed = bestMuxed(info.getVideoStreams());
        if (muxed != null && !byH.containsKey(height(muxed))) {
            out.add(new Opt(height(muxed) + "p", contentLength(muxed.getContent()), new Res(muxed.getContent(), null)));
        }
        if (out.isEmpty()) throw new IOException("No downloadable stream for this video");
        Collections.sort(out, new Comparator<Opt>() {
            @Override public int compare(Opt a, Opt b) { return b.height() - a.height(); }
        });
        return out;
    }

    private static int size(List<?> l) { return l == null ? 0 : l.size(); }

    private static VideoStream bestMuxed(List<VideoStream> list) {
        VideoStream best = null;
        if (list == null) return null;
        for (VideoStream s : list) {
            if (s.isVideoOnly() || !s.isUrl()) continue;
            if (s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            boolean mp4 = s.getFormat() == MediaFormat.MPEG_4;
            if (!mp4 && best != null) continue;
            if (best == null || (mp4 && best.getFormat() != MediaFormat.MPEG_4) || height(s) > height(best)) best = s;
        }
        return best;
    }

    /** Highest H.264 mp4 video-only stream up to 720p (MediaMuxer can write H.264 on every Android version). */
    private static VideoStream bestVideoOnly(List<VideoStream> list) {
        VideoStream best = null;
        if (list == null) return null;
        for (VideoStream s : list) {
            if (!s.isUrl() || s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            if (s.getFormat() != MediaFormat.MPEG_4) continue;
            String cd = s.getCodec();
            if (cd != null && !cd.toLowerCase().startsWith("avc1")) continue;
            int h = height(s);
            if (h <= 0 || h > 720) continue;
            if (best == null || h > height(best)) best = s;
        }
        return best;
    }

    /** Best AAC (m4a) audio stream. */
    private static AudioStream bestAudio(List<AudioStream> list) {
        AudioStream best = null;
        if (list == null) return null;
        for (AudioStream s : list) {
            if (!s.isUrl() || s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            if (s.getFormat() != MediaFormat.M4A) continue;
            if (best == null || s.getAverageBitrate() > best.getAverageBitrate()) best = s;
        }
        return best;
    }

    /** googlevideo links are made for one YouTube client and expect that client's User-Agent when downloading. */
    static String userAgentFor(String u) {
        if (u == null) return UA;
        if (u.contains("c=ANDROID_VR")) return "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
        if (u.contains("c=ANDROID")) return "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";
        if (u.contains("c=IOS")) return "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
        return UA;
    }

    /** Size of the stream from the link's clen= parameter, or -1. */
    static long contentLength(String u) {
        Matcher m = Pattern.compile("[?&]clen=(\\d+)").matcher(u == null ? "" : u);
        try { return m.find() ? Long.parseLong(m.group(1)) : -1; } catch (Exception e) { return -1; }
    }

    private static int height(VideoStream s) {
        int h = s.getHeight();
        if (h > 0) return h;
        try { return Integer.parseInt(s.getResolution().replaceAll("[^0-9].*$", "")); } catch (Exception e) { return 0; }
    }

    /** Minimal HttpURLConnection-based Downloader for NewPipeExtractor. */
    private static final class Dl extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
            try {
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestMethod(request.httpMethod());
                boolean hasUa = false;
                for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
                    if (h.getKey() == null) continue;
                    if ("User-Agent".equalsIgnoreCase(h.getKey())) hasUa = true;
                    boolean first = true;
                    for (String v : h.getValue()) {
                        if (first) c.setRequestProperty(h.getKey(), v); else c.addRequestProperty(h.getKey(), v);
                        first = false;
                    }
                }
                if (!hasUa) c.setRequestProperty("User-Agent", UA);
                byte[] data = request.dataToSend();
                if (data != null) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(data.length);
                    try (OutputStream o = c.getOutputStream()) { o.write(data); }
                }
                int code = c.getResponseCode();
                if (code == 429) throw new ReCaptchaException("reCaptcha Challenge requested", request.url());
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = "";
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                    in.close();
                    body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                }
                return new Response(code, c.getResponseMessage(), c.getHeaderFields(), body, c.getURL().toString());
            } finally {
                c.disconnect();
            }
        }
    }
}
