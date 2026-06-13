package com.duoshield.app.crypto;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;
import java.util.Base64;

public class CryptoHelper {
    private static final String ALGO     = "AES/GCM/NoPadding";
    private static final int    KEY_SIZE = 256;
    private static final int    IV_SIZE  = 12;
    private static final int    TAG_SIZE = 128;

    public static SecretKey generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(KEY_SIZE);
        return keyGen.generateKey();
    }

    public static String encrypt(String plaintext, SecretKey key) throws Exception {
        byte[] iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_SIZE, iv));
        byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    // ── Binary encrypt / decrypt (for media files) ───────────────────────────

    /**
     * Encrypts raw bytes with AES-256-GCM.
     * Output: [12-byte random IV | ciphertext + 16-byte auth tag]
     * Used by SupabaseStorageHelper before every media upload.
     */
    public static byte[] encryptBytes(byte[] plainData, SecretKey key) throws Exception {
        byte[] iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_SIZE, iv));
        byte[] cipherText = cipher.doFinal(plainData);
        byte[] out = new byte[IV_SIZE + cipherText.length];
        System.arraycopy(iv,         0, out, 0,       IV_SIZE);
        System.arraycopy(cipherText, 0, out, IV_SIZE, cipherText.length);
        return out;
    }

    /**
     * Decrypts AES-256-GCM ciphertext produced by {@link #encryptBytes}.
     * Input: [12-byte IV | ciphertext + auth tag]
     * Throws {@link javax.crypto.AEADBadTagException} if the data is tampered.
     */
    public static byte[] decryptBytes(byte[] encrypted, SecretKey key) throws Exception {
        if (encrypted.length < IV_SIZE + 1) {
            throw new IllegalArgumentException(
                    "Encrypted data too short (" + encrypted.length + " bytes)");
        }
        byte[] iv = new byte[IV_SIZE];
        System.arraycopy(encrypted, 0, iv, 0, IV_SIZE);
        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_SIZE, iv));
        try {
            return cipher.doFinal(encrypted, IV_SIZE, encrypted.length - IV_SIZE);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new RuntimeException("Media decryption failed: data corrupted or wrong key", e);
        }
    }

    // ── String encrypt / decrypt (for chat messages) ──────────────────────────

    public static String decrypt(String encrypted, SecretKey key) throws Exception {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encrypted);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Base64 encoded data", e);
        }

        if (decoded.length < IV_SIZE + 1) {
            throw new IllegalArgumentException(
                "Invalid encrypted data: too short (< " + (IV_SIZE + 1) + " bytes)");
        }

        byte[] iv = new byte[IV_SIZE];
        System.arraycopy(decoded, 0, iv, 0, iv.length);

        Cipher cipher = Cipher.getInstance(ALGO);
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_SIZE, iv));
            byte[] plainText = cipher.doFinal(decoded, iv.length, decoded.length - iv.length);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new RuntimeException("Decryption failed: data is corrupted or tampered with", e);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }
}
