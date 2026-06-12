package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

/**
 * Returns an EncryptedSharedPreferences instance for storing crypto material
 * (EC key pair, ECDH shared key). Falls back to plain prefs if unavailable.
 */
public class SecurePrefs {

    private static final String FILE_NAME = "duoshield_secure_prefs";

    public static SharedPreferences get(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                FILE_NAME,
                masterKeyAlias,
                context.getApplicationContext(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fall back gracefully — app remains functional, just not hardware-encrypted
            return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        }
    }
}
