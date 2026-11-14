package com.rajibtutorial;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Firestore REST client (same project / collection / API key the web
 * version uses), so the native screens read and write exactly the same data.
 */
final class Fs {
    private Fs() {}

    static final String KEY = "AIzaSyDGd3KAo45UuqmeGFALziz_oKm3htEASHY";
    static final String BASE =
        "https://firestore.googleapis.com/v1/projects/mywebtools-f8d53/databases/(default)/documents/tutorial_courses";
    static final String REFERER = "https://raseledutools.github.io/rajib-tutorial-web/";

    static final class Doc {
        final String id;
        final Map<String, Object> data;
        Doc(String id, Map<String, Object> data) { this.id = id; this.data = data; }
    }

    static final class NotFound extends IOException {
        NotFound() { super("not found"); }
    }

    // ── Public API ──────────────────────────────────────────

    static List<Doc> list() throws Exception {
        List<Doc> out = new ArrayList<>();
        String token = null;
        do {
            String url = BASE + "?pageSize=300&key=" + KEY
                + (token != null ? "&pageToken=" + URLEncoder.encode(token, "UTF-8") : "");
            JSONObject o = new JSONObject(request("GET", url, null));
            JSONArray docs = o.optJSONArray("documents");
            if (docs != null) {
                for (int i = 0; i < docs.length(); i++) out.add(parseDoc(docs.getJSONObject(i)));
            }
            token = o.has("nextPageToken") ? o.getString("nextPageToken") : null;
        } while (token != null);
        return out;
    }

    /** Returns null when the document does not exist. */
    static Doc get(String id) throws Exception {
        try {
            return parseDoc(new JSONObject(request("GET", docUrl(id, null), null)));
        } catch (NotFound e) {
            return null;
        }
    }

    /** Replace the whole document (create if missing) — like setDoc. */
    static void set(String id, Map<String, Object> data) throws Exception {
        request("PATCH", docUrl(id, null), body(data));
    }

    /** Merge the given fields into an existing document — like updateDoc. */
    static void update(String id, Map<String, Object> data) throws Exception {
        List<String> mask = new ArrayList<>(data.keySet());
        request("PATCH", docUrl(id, mask) + "&currentDocument.exists=true", body(data));
    }

    static void delete(String id) throws Exception {
        try {
            request("DELETE", docUrl(id, null), null);
        } catch (NotFound ignored) { }
    }


    /**
     * Newest documents whose id starts with {@code prefix} (ids sort chronologically),
     * newest first. Uses a __name__ range query, so no composite index is needed.
     */
    static List<Doc> latestWithPrefix(String prefix, int limit) throws Exception {
        String root = "projects/mywebtools-f8d53/databases/(default)/documents/tutorial_courses/";
        char lastCh = prefix.charAt(prefix.length() - 1);
        String lo = root + prefix;
        String hi = root + prefix.substring(0, prefix.length() - 1) + (char) (lastCh + 1);
        JSONArray filters = new JSONArray()
            .put(nameFilter(lo, "GREATER_THAN_OR_EQUAL"))
            .put(nameFilter(hi, "LESS_THAN"));
        JSONObject q = new JSONObject();
        q.put("from", new JSONArray().put(new JSONObject().put("collectionId", "tutorial_courses")));
        q.put("where", new JSONObject().put("compositeFilter",
            new JSONObject().put("op", "AND").put("filters", filters)));
        q.put("orderBy", new JSONArray().put(new JSONObject()
            .put("field", new JSONObject().put("fieldPath", "__name__"))
            .put("direction", "DESCENDING")));
        q.put("limit", limit);
        String url = "https://firestore.googleapis.com/v1/projects/mywebtools-f8d53/databases/(default)/documents:runQuery?key=" + KEY;
        String res = request("POST", url, new JSONObject().put("structuredQuery", q).toString());
        JSONArray arr = new JSONArray(res);
        List<Doc> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.has("document")) out.add(parseDoc(o.getJSONObject("document")));
        }
        return out;
    }

    private static JSONObject nameFilter(String ref, String op) throws JSONException {
        return new JSONObject().put("fieldFilter", new JSONObject()
            .put("field", new JSONObject().put("fieldPath", "__name__"))
            .put("op", op)
            .put("value", new JSONObject().put("referenceValue", ref)));
    }

    // ── HTTP ────────────────────────────────────────────────

    private static String docUrl(String id, List<String> mask) throws Exception {
        StringBuilder sb = new StringBuilder(BASE).append('/')
            .append(URLEncoder.encode(id, "UTF-8")).append("?key=").append(KEY);
        if (mask != null) {
            for (String m : mask) sb.append("&updateMask.fieldPaths=").append(URLEncoder.encode(m, "UTF-8"));
        }
        return sb.toString();
    }

    private static String body(Map<String, Object> data) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("fields", fields(data));
        return o.toString();
    }

    private static String request(String method, String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("Referer", REFERER);
            c.setRequestProperty("Accept", "application/json");
            if ("PATCH".equals(method)) {
                try {
                    c.setRequestMethod("PATCH");
                } catch (ProtocolException e) {
                    c.setRequestMethod("POST");
                    c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                }
            } else {
                c.setRequestMethod(method);
            }
            if (json != null) {
                byte[] b = json.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = is == null ? "" : readAll(is);
            if (code == 404) throw new NotFound();
            if (code >= 400) throw new IOException("HTTP " + code + " " + shortErr(text));
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    private static String shortErr(String body) {
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e != null) return e.optString("message", body);
        } catch (Exception ignored) { }
        return body.length() > 160 ? body.substring(0, 160) : body;
    }

    // ── Firestore typed value <-> plain Java ────────────────

    private static Doc parseDoc(JSONObject d) throws JSONException {
        String name = d.getString("name");
        String id = name.substring(name.lastIndexOf('/') + 1);
        Map<String, Object> data = new HashMap<>();
        JSONObject f = d.optJSONObject("fields");
        if (f != null) {
            Iterator<String> it = f.keys();
            while (it.hasNext()) {
                String k = it.next();
                data.put(k, dec(f.getJSONObject(k)));
            }
        }
        return new Doc(id, data);
    }

    private static Object dec(JSONObject v) throws JSONException {
        if (v.has("stringValue")) return v.getString("stringValue");
        if (v.has("integerValue")) return Long.valueOf(v.getString("integerValue"));
        if (v.has("doubleValue")) return v.getDouble("doubleValue");
        if (v.has("booleanValue")) return v.getBoolean("booleanValue");
        if (v.has("timestampValue")) return v.getString("timestampValue");
        if (v.has("arrayValue")) {
            List<Object> out = new ArrayList<>();
            JSONArray a = v.getJSONObject("arrayValue").optJSONArray("values");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(dec(a.getJSONObject(i)));
            return out;
        }
        if (v.has("mapValue")) {
            Map<String, Object> out = new HashMap<>();
            JSONObject f = v.getJSONObject("mapValue").optJSONObject("fields");
            if (f != null) {
                Iterator<String> it = f.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    out.put(k, dec(f.getJSONObject(k)));
                }
            }
            return out;
        }
        return null; // nullValue and anything unknown
    }

    private static JSONObject fields(Map<String, Object> m) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Object> e : new LinkedHashMap<>(m).entrySet()) o.put(e.getKey(), enc(e.getValue()));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject enc(Object v) throws JSONException {
        JSONObject o = new JSONObject();
        if (v == null) {
            o.put("nullValue", JSONObject.NULL);
        } else if (v instanceof String) {
            o.put("stringValue", v);
        } else if (v instanceof Boolean) {
            o.put("booleanValue", ((Boolean) v).booleanValue());
        } else if (v instanceof Integer || v instanceof Long) {
            o.put("integerValue", String.valueOf(v));
        } else if (v instanceof Number) {
            o.put("doubleValue", ((Number) v).doubleValue());
        } else if (v instanceof List) {
            JSONArray a = new JSONArray();
            for (Object x : (List<Object>) v) a.put(enc(x));
            o.put("arrayValue", new JSONObject().put("values", a));
        } else if (v instanceof Map) {
            o.put("mapValue", new JSONObject().put("fields", fields((Map<String, Object>) v)));
        } else {
            o.put("stringValue", String.valueOf(v));
        }
        return o;
    }
}
