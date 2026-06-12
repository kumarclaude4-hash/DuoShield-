package com.duoshield.app.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Passphrase-based account recovery.
 *
 * Recovery code format: 4 groups of 8 uppercase hex chars, e.g.
 *   A1B2C3D4-E5F6G7H8-I9J0K1L2-M3N4O5P6  (128 bits of entropy)
 *
 *
 * Flow:
 *  Signup  → encryptLoginPassphrase(loginPass, recoveryPass) → store {enc, salt} in Firestore
 *  Reset   → decryptLoginPassphrase(enc, salt, recoveryPass) → old login pass →
 *            signIn(email, old) → updatePassword(new) → re-encrypt → update Firestore
 *
 * No email link, no OTP. Only the recovery passphrase unlocks the account.
 */
public class RecoveryHelper {

    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int KEY_BITS           = 256;
    private static final int SALT_BYTES         = 16;
    private static final int IV_BYTES           = 12;
    private static final int TAG_BITS           = 128;

    /**
     * Generate a random 128-bit recovery code formatted as 4 groups of 8 uppercase hex chars.
     * Example: A1B2C3D4-E5F6G7H8-I9J0K1L2-M3N4O5P6
     */
    public static String generateRecoveryCode() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(39);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    public static SecretKey deriveKey(String recoveryPassphrase, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(
                recoveryPassphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static String encryptLoginPassphrase(String loginPassphrase, String recoveryPassphrase)
            throws Exception {
        byte[] salt = generateSalt();
        SecretKey key = deriveKey(recoveryPassphrase, salt);

        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] cipherBytes = cipher.doFinal(loginPassphrase.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[SALT_BYTES + IV_BYTES + cipherBytes.length];
        System.arraycopy(salt,       0, combined, 0,                       SALT_BYTES);
        System.arraycopy(iv,         0, combined, SALT_BYTES,              IV_BYTES);
        System.arraycopy(cipherBytes,0, combined, SALT_BYTES + IV_BYTES,   cipherBytes.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * Returns the decrypted login passphrase, or throws if the recovery passphrase is wrong.
     */
    public static String decryptLoginPassphrase(String encryptedBlob, String recoveryPassphrase)
            throws Exception {
        byte[] combined = Base64.getDecoder().decode(encryptedBlob);
        if (combined.length <= SALT_BYTES + IV_BYTES) {
            throw new IllegalArgumentException("Malformed recovery blob.");
        }

        byte[] salt = new byte[SALT_BYTES];
        byte[] iv   = new byte[IV_BYTES];
        System.arraycopy(combined, 0,           salt, 0, SALT_BYTES);
        System.arraycopy(combined, SALT_BYTES,  iv,   0, IV_BYTES);

        SecretKey key = deriveKey(recoveryPassphrase, salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] plain = cipher.doFinal(combined, SALT_BYTES + IV_BYTES,
                combined.length - SALT_BYTES - IV_BYTES);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** SHA-256 hex of the normalised email — used as Firestore document ID. */
    public static String emailHash(String email) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(
                email.toLowerCase().trim().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
