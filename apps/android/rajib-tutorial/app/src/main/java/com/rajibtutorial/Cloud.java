package com.rajibtutorial;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Unsigned Cloudinary upload (image / audio / video / any file) used by the Discussion group. */
final class Cloud {
    private Cloud() {}

    static final String UPLOAD_URL = "https://api.cloudinary.com/v1_1/de2w78yxh/auto/upload";
    static final String PRESET = "ml_default";
    static final long MAX_BYTES = 100L * 1024L * 1024L;

    interface Progress { void on(int percent); }

    static final class Meta {
        String name = "file";
        String mime = "application/octet-stream";
        long size = -1;
    }

    static final class Result {
        String url = "", name = "", mime = "";
    }

    static Meta meta(Context ctx, Uri uri) {
        Meta m = new Meta();
        ContentResolver cr = ctx.getContentResolver();
        String t = cr.getType(uri);
        if (t != null && !t.isEmpty()) m.mime = t;
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && c.getString(ni) != null) m.name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) m.size = c.getLong(si);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return m;
    }

    private static void writeAscii(OutputStream os, String s) throws Exception {
        os.write(s.getBytes("UTF-8"));
    }

    static Result upload(Context ctx, Uri uri, Meta meta, Progress p) throws Exception {
        String boundary = "----rtboundary" + System.currentTimeMillis();
        String safeName = meta.name.replace("\"", "_").replace("\r", "").replace("\n", "");
        HttpURLConnection c = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(180000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setChunkedStreamingMode(64 * 1024);

            OutputStream os = new BufferedOutputStream(c.getOutputStream(), 64 * 1024);
            writeAscii(os, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"upload_preset\"\r\n\r\n"
                + PRESET + "\r\n");
            writeAscii(os, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + safeName + "\"\r\nContent-Type: " + meta.mime + "\r\n\r\n");

            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("Cannot read the selected file");
            byte[] buf = new byte[32 * 1024];
            long sent = 0;
            int last = -1, n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                sent += n;
                if (p != null && meta.size > 0) {
                    int pct = (int) Math.min(99, sent * 100 / meta.size);
                    if (pct != last) { last = pct; p.on(pct); }
                }
            }
            in.close();
            writeAscii(os, "\r\n--" + boundary + "--\r\n");
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (is != null) {
                byte[] b2 = new byte[8192];
                int r;
                while ((r = is.read(b2)) > 0) bos.write(b2, 0, r);
                is.close();
            }
            String body = bos.toString("UTF-8");
            JSONObject o = new JSONObject(body.isEmpty() ? "{}" : body);
            if (code >= 400) {
                JSONObject er = o.optJSONObject("error");
                throw new Exception(er != null ? er.optString("message", "HTTP " + code) : "HTTP " + code);
            }
            Result res = new Result();
            res.url = o.optString("secure_url", "");
            if (res.url.isEmpty()) throw new Exception("Upload failed");
            res.name = meta.name;
            res.mime = meta.mime;
            if (p != null) p.on(100);
            return res;
        } finally {
            c.disconnect();
        }
    }
}
