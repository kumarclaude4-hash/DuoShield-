package com.duoshield.app.util;

import android.content.Context;
import com.duoshield.app.crypto.CryptoHelper;
import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.crypto.KeyManager;
import javax.crypto.SecretKey;

public class EncryptionHelper {

    public static String encrypt(Context ctx, String plaintext) {
        try {
            SecretKey key = getKey(ctx);
            if (key == null) return plaintext;
            return CryptoHelper.encrypt(plaintext, key);
        } catch (Exception e) { return plaintext; }
    }

    public static String decrypt(Context ctx, String ciphertext) {
        if (ciphertext == null) return null;
        try {
            SecretKey key = getKey(ctx);
            if (key == null) return ciphertext;
            return CryptoHelper.decrypt(ciphertext, key);
        } catch (Exception e) { return ciphertext; }
    }

    private static SecretKey getKey(Context ctx) throws Exception {
        SecretKey shared = CryptoInitializer.getSharedKey(ctx);
        if (shared != null) return shared;
        return KeyManager.getKey();
    }
}
