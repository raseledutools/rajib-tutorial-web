package com.rajibtutorial;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pairing between "Phone A" (owner) and "Phone B" (relay). B creates a code, the owner types it into A.
 * The code never leaves the two phones; every alert, command and call-signalling message between them is
 * signed with it (HMAC-SHA256), so nobody else can ring A or control B.
 */
final class RelayAuth {
    private RelayAuth() { }

    private static final String K_SECRET = "relay_secret";   // on B: my code
    private static final String K_PAIR = "pair_";            // on A: pair_<B number> = code
    private static final String ABC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(CallRelayReceiver.PREFS, Context.MODE_PRIVATE);
    }

    static String norm(String code) {
        return code == null ? "" : code.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    static String pretty(String code) {
        return norm(code).replaceAll("(.{4})(?!$)", "$1-");
    }

    static String random(int n) {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(ABC.charAt(r.nextInt(ABC.length())));
        return b.toString();
    }

    /** Phone B: my pairing code (created on first use). */
    static String myCode(Context c) {
        String s = sp(c).getString(K_SECRET, "");
        if (s.length() < 16) s = newCode(c);
        return s;
    }

    /** Phone B: replace the code (un-pairs the old Phone A). */
    static String newCode(Context c) {
        String s = random(16);
        sp(c).edit().putString(K_SECRET, s).apply();
        return s;
    }

    /** Phone A: code saved for relay phone {@code b}, or "". */
    static String pairedCode(Context c, String b) {
        return sp(c).getString(K_PAIR + b, "");
    }

    static void pair(Context c, String b, String code) {
        sp(c).edit().putString(K_PAIR + b, norm(code)).apply();
    }

    /** Logout: forget my code and all pairings, switch the relay off. */
    static void clear(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String k : sp(c).getAll().keySet()) if (k.startsWith(K_PAIR)) e.remove(k);
        e.remove(K_SECRET).remove(CallRelayReceiver.K_ON).remove(CallRelayReceiver.K_TO).apply();
    }

    static String sign(String code, String... parts) {
        try {
            StringBuilder b = new StringBuilder();
            for (String p : parts) {
                String v = p == null ? "" : p;
                b.append(v.length()).append(':').append(v);       // length-prefixed: fields cannot shift into each other
            }
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(norm(code).getBytes("UTF-8"), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte x : m.doFinal(b.toString().getBytes("UTF-8"))) hex.append(String.format(Locale.US, "%02x", x & 0xff));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static boolean verify(String code, String sig, String... parts) {
        if (norm(code).length() < 16 || sig == null || sig.isEmpty()) return false;
        return MessageDigest.isEqual(sign(code, parts).getBytes(), sig.getBytes());
    }
}
