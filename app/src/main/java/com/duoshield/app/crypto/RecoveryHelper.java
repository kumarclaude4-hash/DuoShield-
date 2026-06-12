package com.duoshield.app.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Passphrase-based account recovery. No email link. No OTP.
 *
 * User ID format  : DS-XXXXXXXX  (e.g. DS-A1B2C3D4) — 32-bit random, shown in profile
 * Recovery code   : XXXXXXXX-XXXXXXXX-XXXXXXXX-XXXXXXXX — 128-bit random, shown once at signup
 *
 * Credential blob : AES-GCM( JSON{"e":"<email>","p":"<passphrase>"}, PBKDF2(recoveryCode) )
 *   Stored at     : Firestore recovery/{uid}  (keyed by Firebase UID, not by email)
 *   Lookup path   : identities/{userId} → {uid} → recovery/{uid}
 *
 * Reset flow:
 *   1. User enters User ID + recovery code + new passphrase
 *   2. App fetches identities/{userId} → uid
 *   3. App fetches recovery/{uid} → encrypted blob
 *   4. Decrypts blob → {email, oldPassphrase}
 *   5. signInWithEmailAndPassword(email, oldPassphrase)
 *   6. user.updatePassword(newPassphrase)
 *   7. Re-encrypts {email, newPassphrase} → updates recovery/{uid}
 */
public class RecoveryHelper {

    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int KEY_BITS           = 256;
    private static final int SALT_BYTES         = 16;
    private static final int IV_BYTES           = 12;
    private static final int TAG_BITS           = 128;

    // ── ID generation ────────────────────────────────────────────────────────

    /**
     * Generates a human-readable anonymous user identifier.
     * Format: DS-XXXXXXXX  (8 uppercase hex chars = 32 bits of randomness)
     * Example: DS-A1B2C3D4
     */
    public static String generateUserId() {
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder("DS-");
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /**
     * Generates a 128-bit random recovery code as 4 groups of 8 uppercase hex chars.
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

    // ── Key derivation ───────────────────────────────────────────────────────

    private static byte[] generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static SecretKey deriveKey(String recoveryCode, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(
                recoveryCode.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(keyBytes, "AES");
    }

    // ── Credential blob ──────────────────────────────────────────────────────

    /**
     * Encrypts {email, passphrase} together into a single portable blob.
     * Blob layout: [16-byte salt][12-byte IV][AES-GCM ciphertext of JSON]
     * All encoded as Base64.
     */
    public static String encryptCredentials(String email, String passphrase,
                                             String recoveryCode) throws Exception {
        byte[] salt = generateSalt();
        SecretKey key = deriveKey(recoveryCode, salt);

        // Minimal JSON — avoids pulling in a JSON library
        String json = "{\"e\":" + jsonString(email) + ",\"p\":" + jsonString(passphrase) + "}";
        byte[] plainBytes = json.getBytes(StandardCharsets.UTF_8);

        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] cipherBytes = cipher.doFinal(plainBytes);

        byte[] combined = new byte[SALT_BYTES + IV_BYTES + cipherBytes.length];
        System.arraycopy(salt,       0, combined, 0,                     SALT_BYTES);
        System.arraycopy(iv,         0, combined, SALT_BYTES,            IV_BYTES);
        System.arraycopy(cipherBytes,0, combined, SALT_BYTES + IV_BYTES, cipherBytes.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * Returns String[]{email, passphrase} or throws if the recovery code is wrong.
     */
    public static String[] decryptCredentials(String encryptedBlob,
                                               String recoveryCode) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encryptedBlob);
        if (combined.length <= SALT_BYTES + IV_BYTES) {
            throw new IllegalArgumentException("Malformed recovery blob.");
        }

        byte[] salt = new byte[SALT_BYTES];
        byte[] iv   = new byte[IV_BYTES];
        System.arraycopy(combined, 0,          salt, 0, SALT_BYTES);
        System.arraycopy(combined, SALT_BYTES, iv,   0, IV_BYTES);

        SecretKey key = deriveKey(recoveryCode, salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] plain = cipher.doFinal(combined, SALT_BYTES + IV_BYTES,
                combined.length - SALT_BYTES - IV_BYTES);

        String json = new String(plain, StandardCharsets.UTF_8);
        // Parse minimal JSON {"e":"...","p":"..."}
        String email      = extractJsonString(json, "e");
        String passphrase = extractJsonString(json, "p");
        if (email == null || passphrase == null) {
            throw new IllegalArgumentException("Credential blob is malformed.");
        }
        return new String[]{email, passphrase};
    }

    // ── JSON helpers (no external lib needed) ────────────────────────────────

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Extracts the string value for a given key from a simple flat JSON object. */
    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"')  break;
            end++;
        }
        return json.substring(start, end)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
