package com.duoshield.app.crypto;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * ECDH key exchange + HKDF-based AES-256 key derivation.
 *
 * Flow:
 *   1. generateKeyPair()           — called once on first launch via CryptoInitializer
 *   2. getPublicKeyBase64()        — Base64 of X.509 encoded EC public key, posted to Firestore
 *   3. deriveSharedKey(myPrivate, partnerPublicBase64)
 *                                  — ECDH raw secret → HKDF-SHA256 → AES-256 SecretKey
 *   4. The returned SecretKey is passed directly to CryptoHelper.encrypt / decrypt
 *
 * Curve: P-256 (secp256r1) — widely supported on Android, NIST-approved.
 */
public class ECDHHelper {

    private static final String CURVE      = "secp256r1";
    private static final String KA_ALGO    = "ECDH";
    private static final String KEY_ALGO   = "EC";
    private static final String HASH_ALGO  = "SHA-256";
    private static final String HKDF_INFO  = "DuoShield-v1";

    // ── Key pair generation ───────────────────────────────────────────────────

    /** Generate a fresh EC P-256 key pair (NOT stored in AndroidKeyStore). */
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALGO);
        kpg.initialize(new ECGenParameterSpec(CURVE));
        return kpg.generateKeyPair();
    }

    /** Encode an EC public key to Base64 (X.509/SubjectPublicKeyInfo format). */
    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /** Decode a Base64 EC public key back to a PublicKey object. */
    public static PublicKey decodePublicKey(String base64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance(KEY_ALGO);
        return kf.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    // ── Shared secret derivation ──────────────────────────────────────────────

    /**
     * Perform ECDH and derive a 256-bit AES key using HKDF-SHA256.
     *
     * @param myPrivateKey         This device's EC private key
     * @param partnerPublicBase64  Partner's Base64-encoded EC public key from Firestore
     * @return AES-256 SecretKey ready for CryptoHelper.encrypt / decrypt
     */
    public static SecretKey deriveSharedKey(PrivateKey myPrivateKey,
                                            String partnerPublicBase64) throws Exception {
        PublicKey partnerPublicKey = decodePublicKey(partnerPublicBase64);

        // ECDH — produces raw shared secret (32 bytes for P-256)
        KeyAgreement ka = KeyAgreement.getInstance(KA_ALGO);
        ka.init(myPrivateKey);
        ka.doPhase(partnerPublicKey, true);
        byte[] rawSecret = ka.generateSecret();

        // HKDF-SHA256: extract + expand
        byte[] prk = hkdfExtract(rawSecret);
        byte[] okm = hkdfExpand(prk, HKDF_INFO.getBytes("UTF-8"), 32);

        return new SecretKeySpec(okm, "AES");
    }

    // ── HKDF-SHA256 (RFC 5869, no salt variant) ───────────────────────────────

    /** HKDF-Extract: HMAC-SHA256(salt=zeroes, ikm=rawSecret) */
    private static byte[] hkdfExtract(byte[] ikm) throws Exception {
        byte[] salt = new byte[32]; // zero-salt (per RFC 5869 §2.2 default)
        return hmacSha256(salt, ikm);
    }

    /** HKDF-Expand: produce `length` bytes of keying material */
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        // T(1) = HMAC-SHA256(prk, info || 0x01)  — one round suffices for 32 bytes
        byte[] input = Arrays.copyOf(info, info.length + 1);
        input[info.length] = 0x01;
        byte[] t1 = hmacSha256(prk, input);
        return Arrays.copyOf(t1, length);
    }

    /** HMAC-SHA256 using raw key bytes (not a Mac key object, avoids FIPS key-size constraints). */
    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        // Manual HMAC: H((key XOR opad) || H((key XOR ipad) || data))
        int blockSize = 64;
        if (key.length > blockSize) {
            key = MessageDigest.getInstance(HASH_ALGO).digest(key);
        }
        byte[] paddedKey = Arrays.copyOf(key, blockSize);

        byte[] ipad = new byte[blockSize];
        byte[] opad = new byte[blockSize];
        for (int i = 0; i < blockSize; i++) {
            ipad[i] = (byte) (paddedKey[i] ^ 0x36);
            opad[i] = (byte) (paddedKey[i] ^ 0x5C);
        }

        MessageDigest sha = MessageDigest.getInstance(HASH_ALGO);
        sha.update(ipad);
        sha.update(data);
        byte[] innerHash = sha.digest();

        sha.reset();
        sha.update(opad);
        sha.update(innerHash);
        return sha.digest();
    }
}
