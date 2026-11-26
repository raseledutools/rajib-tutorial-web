package com.rajibtutorial;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDataSource;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Offline lesson videos are never stored as plain .mp4:
 *  - file content is AES-256-CTR encrypted (random IV per file, seekable so the player can jump anywhere);
 *  - the AES key is random, kept only inside the app and wrapped by the Android Keystore (hardware-backed where available);
 *  - file names are keyed hashes (no lesson id, no extension) inside a hidden folder in the app's private,
 *    backup-excluded storage, so they can't be recognised, opened by a gallery/file manager or copied to another phone.
 * Playback reads and decrypts on the fly through {@link MediaDataSource}; no decrypted copy is ever written to disk.
 */
final class OfflineCrypto {
    private OfflineCrypto() {}

    /** Secure offline storage needs API 23 (Keystore AES + MediaDataSource). */
    static boolean supported() { return Build.VERSION.SDK_INT >= 23; }

    private static final byte[] MAGIC = {'R', 'T', 'V', '1'};
    static final int HDR = 4 + 16;                       // magic + IV
    private static final String PREFS = "rt_sec";
    private static final String KS_ALIAS = "rt_offline_wrap";
    private static byte[] sKey;

    // ── key handling ────────────────────────────────────────────────────────────────────────────

    private static synchronized byte[] masterKey(Context ctx) throws IOException {
        if (sKey != null) return sKey;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String wrapped = sp.getString("mk", null);
            if (wrapped != null) {
                byte[] raw = Base64.decode(wrapped, Base64.NO_WRAP);
                sKey = unwrap(raw);
                if (sKey != null && sKey.length == 32) return sKey;
            }
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            byte[] w = wrap(k);
            sp.edit().putString("mk", Base64.encodeToString(w, Base64.NO_WRAP)).apply();
            sKey = k;
            return k;
        } catch (Exception e) {
            throw new IOException("secure key unavailable: " + e);
        }
    }

    private static SecretKey keystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KS_ALIAS)) return (SecretKey) ks.getKey(KS_ALIAS, null);
        javax.crypto.KeyGenerator g = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return g.generateKey();
    }

    /** Keystore-wrapped key blob: 0x01 | iv(12) | GCM ciphertext. */
    private static byte[] wrap(byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keystoreKey());
        byte[] iv = c.getIV(), ct = c.doFinal(key);
        byte[] out = new byte[1 + iv.length + ct.length];
        out[0] = 1;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
        return out;
    }

    private static byte[] unwrap(byte[] blob) throws Exception {
        if (blob.length < 14 || blob[0] != 1) return null;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, blob, 1, 12));
        return c.doFinal(blob, 13, blob.length - 13);
    }

    // ── hidden file naming ──────────────────────────────────────────────────────────────────────

    private static File dir(Context ctx) {
        File d = new File(ctx.getApplicationContext().getNoBackupFilesDir(), ".sys");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Hidden, unguessable file for a (sanitised) video id: keyed hash, no extension. */
    static File fileFor(Context ctx, String safeId) {
        String name;
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(masterKey(ctx), "HmacSHA256"));
            byte[] h = m.doFinal(safeId.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(".");
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", h[i] & 0xFF));
            name = sb.toString();
        } catch (Exception e) {
            name = "." + Integer.toHexString(safeId.hashCode()) + "x";   // key unavailable: still not the plain id
        }
        return new File(dir(ctx), name);
    }

    static File hiddenDir(Context ctx) { return dir(ctx); }

    // ── encrypt ─────────────────────────────────────────────────────────────────────────────────

    /** Encrypts `plain` into `dst` (header + AES-CTR). The caller deletes `plain` afterwards. */
    static void encrypt(Context ctx, File plain, File dst) throws IOException {
        try {
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            iv[8] &= 0x7F;                                   // keep headroom so the 64-bit block counter never wraps
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey(ctx), "AES"), new IvParameterSpec(iv));
            try (InputStream in = new FileInputStream(plain); OutputStream out = new FileOutputStream(dst)) {
                out.write(MAGIC);
                out.write(iv);
                byte[] buf = new byte[65536], o = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    int k = c.update(buf, 0, n, o, 0);
                    if (k > 0) out.write(o, 0, k);
                }
                byte[] fin = c.doFinal();
                if (fin != null && fin.length > 0) out.write(fin);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            throw e;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            throw new IOException("encryption failed: " + e);
        }
    }

    /** True if the file starts with our header (i.e. it is one of our encrypted copies). */
    static boolean isEncrypted(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == MAGIC[0] && h[1] == MAGIC[1] && h[2] == MAGIC[2] && h[3] == MAGIC[3];
        } catch (IOException e) {
            return false;
        }
    }

    /** Size of the video inside an encrypted copy. */
    static long plainLength(File f) { return Math.max(0, f.length() - HDR); }

    // ── decrypt-on-the-fly source for MediaPlayer ───────────────────────────────────────────────

    /** Opens an encrypted copy for MediaPlayer.setDataSource(). Needs API 23. */
    static MediaDataSource open(Context ctx, File f) throws IOException {
        if (!supported()) throw new IOException("Android 6.0+ required");
        return new Source(masterKey(ctx), f);
    }

    private static final class Source extends MediaDataSource {
        private final RandomAccessFile raf;
        private final SecretKeySpec key;
        private final byte[] iv = new byte[16];
        private final long len;
        private Cipher cipher;
        private long next = -1;

        Source(byte[] k, File f) throws IOException {
            key = new SecretKeySpec(k, "AES");
            raf = new RandomAccessFile(f, "r");
            byte[] h = new byte[HDR];
            raf.readFully(h);
            if (h[0] != MAGIC[0] || h[1] != MAGIC[1] || h[2] != MAGIC[2] || h[3] != MAGIC[3]) { raf.close(); throw new IOException("not an offline copy"); }
            System.arraycopy(h, 4, iv, 0, 16);
            len = Math.max(0, raf.length() - HDR);
        }

        /** Counter block = IV + n (128-bit, big-endian) — the CTR state at byte offset n*16. */
        private byte[] ivAt(long block) {
            byte[] r = iv.clone();
            long carry = block;
            for (int i = 15; i >= 0 && carry != 0; i--) {
                long s = (r[i] & 0xFFL) + (carry & 0xFFL);
                r[i] = (byte) s;
                carry = (carry >>> 8) + (s >>> 8);
            }
            return r;
        }

        private void seekCipher(long pos) throws IOException {
            try {
                if (cipher == null) cipher = Cipher.getInstance("AES/CTR/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(ivAt(pos / 16)));
                int skip = (int) (pos % 16);
                if (skip > 0) cipher.update(new byte[skip]);
                next = pos;
            } catch (Exception e) {
                throw new IOException("decrypt init failed: " + e);
            }
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (position < 0 || position >= len) return -1;
            if (position + size > len) size = (int) (len - position);
            if (size <= 0) return 0;
            if (cipher == null || position != next) seekCipher(position);
            raf.seek(HDR + position);
            int n = raf.read(buffer, offset, size);
            if (n <= 0) return -1;
            try {
                cipher.update(buffer, offset, n, buffer, offset);
            } catch (Exception e) {
                throw new IOException("decrypt failed: " + e);
            }
            next = position + n;
            return n;
        }

        @Override
        public synchronized long getSize() { return len; }

        @Override
        public synchronized void close() throws IOException { raf.close(); }
    }
}
