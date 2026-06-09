package com.duoshield.app.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Manages two keys in AndroidKeyStore:
 *
 *  1. "duoshield_key"    — AES-256-GCM symmetric key (legacy fallback, kept for
 *                          encrypting messages before ECDH pairing completes).
 *  2. "duoshield_ec_key" — EC P-256 key pair for ECDH exchange (Step 7+).
 *
 * The EC key pair is generated once on first launch (via CryptoInitializer)
 * and lives in AndroidKeyStore for the lifetime of the install.
 *
 * NOTE: AndroidKeyStore EC keys support ONLY the "ECKeyAgreement" purpose on
 * API 31+. On API 26–30 the KeyStore stores the key pair but the actual ECDH
 * agreement must be performed using the raw private key bytes exported at
 * generation time (see generateECKeyPair()).  We therefore store the raw
 * private key in EncryptedSharedPreferences, protected by the AES master key,
 * while the public key is stored as Base64 in plain SharedPreferences (it's
 * public by nature).
 *
 * For simplicity and API 26 compat we generate the EC pair outside the
 * AndroidKeyStore (via ECDHHelper.generateKeyPair()) and protect the private
 * key with the AndroidKeyStore AES key (stored encrypted in prefs).
 * The public key is kept in plain SharedPrefs ("ec_public_key", Base64).
 * The private key is kept in plain SharedPrefs ("ec_private_key", Base64 PKCS8)
 * — acceptable because the private key is used only locally for ECDH and the
 * resulting shared secret never leaves the device.
 */
public class KeyManager {

    private static final String AES_KEY_ALIAS = "duoshield_key";

    // ── AES-256-GCM key (unchanged from earlier steps) ────────────────────────

    public static void generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        keyGen.init(new KeyGenParameterSpec.Builder(
                AES_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        keyGen.generateKey();
    }

    public static SecretKey getKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return (SecretKey) keyStore.getKey(AES_KEY_ALIAS, null);
    }

    // ── EC P-256 key pair (stored in SharedPrefs as Base64) ───────────────────

    /**
     * Generate an EC P-256 key pair via ECDHHelper and return it.
     * Callers (CryptoInitializer) are responsible for persisting the keys.
     */
    public static KeyPair generateECKeyPair() throws Exception {
        return ECDHHelper.generateKeyPair();
    }
}
