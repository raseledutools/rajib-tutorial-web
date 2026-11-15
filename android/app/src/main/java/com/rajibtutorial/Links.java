package com.rajibtutorial;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds web links inside notification text and opens them. */
final class Links {
    private Links() {}

    private static final Pattern URL = Pattern.compile(
        "(?i)\\b((?:https?://|www\\.)[^\\s<>\"']+)");

    /** First web link found in the given texts (http/https/www), or null. */
    static String firstUrl(String... texts) {
        if (texts == null) return null;
        for (String t : texts) {
            if (t == null || t.isEmpty()) continue;
            Matcher m = URL.matcher(t);
            if (m.find()) {
                String u = m.group(1);
                // drop trailing punctuation that is not part of the link
                while (u.length() > 1 && ".,;:!?)]}\u0964".indexOf(u.charAt(u.length() - 1)) >= 0) {
                    u = u.substring(0, u.length() - 1);
                }
                if (!u.toLowerCase().startsWith("http")) u = "https://" + u;
                return u;
            }
        }
        return null;
    }

    /** Intent that opens the link in the browser / matching app. */
    static Intent viewIntent(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static boolean open(Context c, String url) {
        try {
            c.startActivity(viewIntent(url));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Makes links inside a TextView tappable. */
    static void linkify(TextView tv, int linkColor) {
        Linkify.addLinks(tv, Linkify.WEB_URLS);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setLinkTextColor(linkColor);
    }
}
