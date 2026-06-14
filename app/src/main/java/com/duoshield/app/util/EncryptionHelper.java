package com.duoshield.app.util;

import android.content.Context;
import android.util.Log;
import com.duoshield.app.crypto.CryptoHelper;
import com.duoshield.app.crypto.CryptoInitializer;
import javax.crypto.SecretKey;

/**
 * Bug 14 fix: removed the AndroidKeyStore (KeyManager) fallback from getKey().
 *   Previously, if the ECDH shared key was null, getKey() fell back to the
 *   device-local AndroidKeyStore key. This meant conversation previews (lastMessage)
 *   could be encrypted with a different key than the one used for message bodies,
 *   causing AEADBadTagException during decryption and silently showing raw ciphertext.
 *   All content must be encrypted exclusively with the ECDH shared key.
 *
 * Bug 20 fix: encrypt() no longer silently returns the plaintext on failure.
 *   Previously, any crypto exception (corrupted key, cipher init failure, etc.)
 *   returned the original plaintext, which was then stored unencrypted in Firestore
 *   without the caller or the user being aware.
 *   Now encrypt() returns null on failure — callers MUST check for null and abort
 *   the operation rather than sending plaintext.
 *
 *   decrypt() still returns the raw ciphertext on failure (showing an encrypted
 *   blob is preferable to crashing the UI).
 */
public class EncryptionHelper {

    private static final String TAG = "EncryptionHelper";

    /**
     * Encrypts {@code plaintext} with the ECDH shared key.
     *
     * @return The Base64-encoded ciphertext, or {@code null} if the key is
     *         unavailable or encryption fails. Callers must treat {@code null}
     *         as a hard failure and must NOT store the plaintext instead.
     */
    public static String encrypt(Context ctx, String plaintext) {
        try {
            SecretKey key = getKey(ctx);
            if (key == null) {
                Log.w(TAG, "encrypt: ECDH shared key not available — skipping");
                return null;
            }
            return CryptoHelper.encrypt(plaintext, key);
        } catch (Exception e) {
            Log.e(TAG, "encrypt: CryptoHelper.encrypt() threw — aborting, NOT falling back to plaintext", e);
            return null;
        }
    }

    /**
     * Decrypts {@code ciphertext} with the ECDH shared key.
     *
     * @return The decrypted plaintext, or the original {@code ciphertext} string
     *         if the key is unavailable or decryption fails (show-as-is fallback).
     */
    public static String decrypt(Context ctx, String ciphertext) {
        if (ciphertext == null) return null;
        try {
            SecretKey key = getKey(ctx);
            if (key == null) return ciphertext;
            return CryptoHelper.decrypt(ciphertext, key);
        } catch (Exception e) {
            Log.w(TAG, "decrypt failed — returning raw value", e);
            return ciphertext;
        }
    }

    /**
     * Bug 14 fix: returns only the ECDH shared key; never falls back to the
     * AndroidKeyStore device key. Returns null when pairing is not complete.
     */
    private static SecretKey getKey(Context ctx) throws Exception {
        return CryptoInitializer.getSharedKey(ctx);
    }
}
