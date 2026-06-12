package com.duoshield.app.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.duoshield.app.util.SecurePrefs;
import java.security.KeyPair;
import java.security.PrivateKey;
import javax.crypto.SecretKey;

/**
 * Called once at app start from MainActivity.route().
 *
 * Ensures:
 *   1. AndroidKeyStore AES-256-GCM key exists (legacy / pre-pairing fallback).
 *   2. EC P-256 key pair exists in EncryptedSharedPreferences
 *      ("ec_public_key" / "ec_private_key").
 *      If either is missing the pair is regenerated and both are saved.
 */
public class CryptoInitializer {

    public static final String KEY_EC_PUBLIC         = "ec_public_key";
    public static final String KEY_EC_PRIVATE        = "ec_private_key";
    public static final String KEY_SHARED_AES        = "ecdh_shared_key";
    public static final String KEY_PARTNER_EC_PUBLIC = "partner_ec_public_key";

    public static void ensureKeyExists(Context context) {
        // 1. AES key (AndroidKeyStore)
        try {
            SecretKey key = KeyManager.getKey();
            if (key == null) KeyManager.generateKey();
        } catch (Exception e) {
            try { KeyManager.generateKey(); } catch (Exception ignored) {}
        }

        // 2. EC key pair (EncryptedSharedPrefs)
        SharedPreferences prefs = SecurePrefs.get(context);
        String pubB64  = prefs.getString(KEY_EC_PUBLIC, null);
        String privB64 = prefs.getString(KEY_EC_PRIVATE, null);

        if (pubB64 == null || privB64 == null) {
            try {
                KeyPair kp = KeyManager.generateECKeyPair();
                String newPub  = ECDHHelper.encodePublicKey(kp.getPublic());
                String newPriv = Base64.encodeToString(
                        kp.getPrivate().getEncoded(), Base64.NO_WRAP);
                prefs.edit()
                     .putString(KEY_EC_PUBLIC,  newPub)
                     .putString(KEY_EC_PRIVATE, newPriv)
                     .apply();
            } catch (Exception ignored) {}
        }
    }

    public static void ensureKeyExists() {
        try {
            SecretKey key = KeyManager.getKey();
            if (key == null) KeyManager.generateKey();
        } catch (Exception e) {
            try { KeyManager.generateKey(); } catch (Exception ignored) {}
        }
    }

    public static PrivateKey getMyPrivateKey(Context context) {
        SharedPreferences prefs = SecurePrefs.get(context);
        String privB64 = prefs.getString(KEY_EC_PRIVATE, null);
        if (privB64 == null) return null;
        try {
            byte[] keyBytes = Base64.decode(privB64, Base64.NO_WRAP);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EC");
            return kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            return null;
        }
    }

    public static SecretKey getSharedKey(Context context) {
        SharedPreferences prefs = SecurePrefs.get(context);
        String b64 = prefs.getString(KEY_SHARED_AES, null);
        if (b64 == null) return null;
        byte[] raw = Base64.decode(b64, Base64.NO_WRAP);
        return new javax.crypto.spec.SecretKeySpec(raw, "AES");
    }

    public static String getMyPublicKeyB64(Context context) {
        return SecurePrefs.get(context).getString(KEY_EC_PUBLIC, null);
    }

    public static String getPartnerPublicKeyB64(Context context) {
        return SecurePrefs.get(context).getString(KEY_PARTNER_EC_PUBLIC, null);
    }
}
