package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Bug 7 fix: PIN hash is now stored in SecurePrefs (EncryptedSharedPreferences)
 * instead of plain duoshield_prefs. This prevents the hash from being extracted
 * via ADB backup or root access and subjected to offline dictionary attacks.
 *
 * Migration note: existing hashes in duoshield_prefs are silently abandoned —
 * users will need to re-set their PIN. This is the same trade-off accepted
 * elsewhere in the codebase (ECDH key migration has no backward path either).
 */
public class PinManager {

    private static final String KEY_PIN    = "app_pin_hash";
    private static final int    ITERATIONS = 310_000;
    private static final int    KEY_LEN    = 256;

    public static void setPin(Context ctx, String pin) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(pin, salt);
            String stored = bytesToHex(salt) + ":" + bytesToHex(hash);
            SecurePrefs.get(ctx).edit().putString(KEY_PIN, stored).apply();
        } catch (Exception ignored) {}
    }

    public static boolean hasPinSet(Context ctx) {
        return SecurePrefs.get(ctx).getString(KEY_PIN, null) != null;
    }

    public static boolean verifyPin(Context ctx, String entered) {
        String stored = SecurePrefs.get(ctx).getString(KEY_PIN, null);
        if (stored == null) return false;
        int sep = stored.indexOf(':');
        if (sep < 0) return false;
        try {
            byte[] salt     = hexToBytes(stored.substring(0, sep));
            byte[] expected = hexToBytes(stored.substring(sep + 1));
            byte[] actual   = pbkdf2(entered, salt);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) { return false; }
    }

    public static void clearPin(Context ctx) {
        SecurePrefs.get(ctx).edit().remove(KEY_PIN).apply();
    }

    private static byte[] pbkdf2(String pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LEN);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return out;
    }
}
