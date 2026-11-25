package com.rajibtutorial;

import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Adult-site blocking + forced SafeSearch for the Safe Browser (port of web-exe/safety.js). */
final class SafeGuard {
    private SafeGuard() { }

    private static final Set<String> SET = new HashSet<>(Arrays.asList(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com", "youporn.com", "spankbang.com", "eporner.com",
        "tnaflix.com", "beeg.com", "tube8.com", "motherless.com", "xtube.com", "brazzers.com", "bangbros.com", "naughtyamerica.com",
        "realitykings.com", "chaturbate.com", "stripchat.com", "livejasmin.com", "bongacams.com", "cam4.com", "myfreecams.com",
        "onlyfans.com", "fansly.com", "e-hentai.org", "nhentai.net", "hentaihaven.xxx", "hqporner.com", "porntrex.com", "txxx.com",
        "hclips.com", "vporn.com", "4tube.com", "sunporno.com", "tubegalore.com", "fuq.com", "thothub.to", "camsoda.com",
        "erome.com", "rule34.xxx", "gelbooru.com", "literotica.com", "xvideos2.com", "banglachoti.com", "sex.com", "adultfriendfinder.com",
        "ashleymadison.com", "playboy.com", "penthouse.com", "hustler.com", "javhd.com", "missav.com", "jable.tv", "xfreehd.com"));

    private static final Pattern SUBSTR = Pattern.compile(
        "(porn|xxx|hentai|xvideo|xnxx|xhamster|redtube|youporn|brazzers|camgirl|sexcam|livesex|nudepic|fuck|erotic)");
    private static final Pattern LABEL = Pattern.compile(
        "(^|[.-])(sex|sexy|nude|nudes|nsfw|fap|jav|milf|bdsm|escort|escorts)([.-]|$)");
    private static final Pattern BAD_TLD = Pattern.compile("\\.(xxx|porn|sex|adult)$");
    private static final Pattern ADULT_Q = Pattern.compile(
        "(\\bporn\\w*|\\bxxx\\b|\\bxvideos?\\b|\\bxnxx\\b|\\bxhamster\\b|\\bhentai\\b|\\bnsfw\\b|\\bnudes?\\b|\\bnaked\\b|\\berotic\\w*"
            + "|\\bblowjob\\b|\\bmilf\\b|\\bbdsm\\b|\\bfetish\\b|\\bcamgirls?\\b|\\bhorny\\b|\\bonlyfans\\b|\\bboobs?\\b|\\bpussy\\b|\\bsexy\\b"
            + "|\\bsex(?!\\s*(education|ed|ratio|chromosomes?|hormones?|determination|discrimination|differences?|offenders?|trafficking))\\b"
            + "|bangla\\s*choti|choti\\s*golpo|চটি|সেক্স|পর্ন|নেংটা|যৌন\\s*(ভিডিও|গল্প))",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SEARCH_HOST = Pattern.compile(
        "(^|\\.)(google|bing|duckduckgo|yahoo|yandex|brave|ecosia|youtube|startpage)\\.", Pattern.CASE_INSENSITIVE);

    static boolean isAdultHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return false;
        String[] parts = host.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < parts.length; j++) { if (j > i) sb.append('.'); sb.append(parts[j]); }
            if (SET.contains(sb.toString())) return true;
        }
        return BAD_TLD.matcher(host).find() || SUBSTR.matcher(host).find() || LABEL.matcher(host).find();
    }

    private static boolean isAdultSearch(Uri u) {
        String host = u.getHost();
        if (host == null || !SEARCH_HOST.matcher(host + ".").find()) return false;
        String q = null;
        for (String k : new String[]{"q", "search_query", "p", "text"}) {
            q = u.getQueryParameter(k);
            if (q != null && !q.isEmpty()) break;
        }
        return q != null && !q.isEmpty() && ADULT_Q.matcher(q).find();
    }

    /** True when this top-level URL must not be opened. */
    static boolean isBlocked(String url) {
        try {
            Uri u = Uri.parse(url);
            String sc = u.getScheme();
            if (sc == null || !(sc.equals("http") || sc.equals("https"))) return false;
            return isAdultHost(u.getHost()) || isAdultSearch(u);
        } catch (Exception e) {
            return false;
        }
    }

    /** A URL that forces SafeSearch for Google / Bing / DuckDuckGo, or "" when nothing has to change. */
    static String safeSearchUrl(String url) {
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            String path = u.getPath() == null ? "" : u.getPath();
            if (h.matches("google\\.[a-z.]+") && (path.equals("/search") || path.equals("/imgres"))) {
                if (!"active".equals(u.getQueryParameter("safe"))) return withParam(u, "safe", "active");
            } else if (h.equals("bing.com") && path.matches("^/(images/)?(search|async).*")) {
                if (!"strict".equals(u.getQueryParameter("adlt"))) return withParam(u, "adlt", "strict");
            } else if (h.equals("duckduckgo.com") && path.equals("/")) {
                if (u.getQueryParameter("q") != null && !"1".equals(u.getQueryParameter("kp"))) return withParam(u, "kp", "1");
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static String withParam(Uri u, String key, String val) {
        Uri.Builder b = u.buildUpon().clearQuery();
        for (String n : u.getQueryParameterNames()) {
            if (n.equals(key)) continue;
            for (String v : u.getQueryParameters(n)) b.appendQueryParameter(n, v);
        }
        b.appendQueryParameter(key, val);
        return b.build().toString();
    }

    private static final java.util.List<String> SITE_CORES = new java.util.ArrayList<>();
    static {
        for (String d : SET) { String c = d.substring(0, d.indexOf('.')); if (c.length() >= 5) SITE_CORES.add(c); }
    }

    /** Adult words typed into a search box / address bar. */
    static boolean isAdultQuery(String text) {
        return text != null && !text.isEmpty() && ADULT_Q.matcher(text).find();
    }

    /** A page title / address-bar text of an external browser (same idea as the exe's isAdultTitle). */
    static boolean isAdultText(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        if (ADULT_Q.matcher(t).find() || SUBSTR.matcher(t).find()) return true;
        for (String c : SITE_CORES) if (t.contains(c)) return true;
        return false;
    }
}
