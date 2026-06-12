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
