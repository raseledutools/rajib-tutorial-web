package com.rajibtutorial;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
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
import java.util.List;
import java.util.Map;

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

    /** @return direct media URL. @throws Exception when nothing downloadable was found. */
    static String resolve(String watchUrl) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(watchUrl);
        List<VideoStream> list = info.getVideoStreams();   // muxed (video+audio) streams only
        VideoStream best = null;
        for (VideoStream s : list) {
            if (s.isVideoOnly() || !s.isUrl()) continue;
            if (s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            boolean mp4 = s.getFormat() == MediaFormat.MPEG_4;
            if (!mp4 && best != null) continue;
            if (best == null || (mp4 && best.getFormat() != MediaFormat.MPEG_4) || height(s) > height(best)) best = s;
        }
        if (best == null) throw new IOException("No downloadable stream for this video");
        return best.getContent();
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
