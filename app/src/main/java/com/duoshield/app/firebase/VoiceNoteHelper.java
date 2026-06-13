package com.duoshield.app.firebase;

import android.content.Context;
import android.util.Log;

import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.util.SupabaseStorageHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

import javax.crypto.SecretKey;

/**
 * Voice-note helper — Supabase private bucket with AES-256-GCM E2E encryption.
 * Returns storage paths; never public URLs.
 */
public class VoiceNoteHelper {

    private static final String TAG = "VoiceNoteHelper";

    public interface UploadCallback {
        void onSuccess(String storagePath);
        void onFailure(Exception e);
    }

    /**
     * Encrypts and uploads the voice file at {@code filePath} to the private bucket.
     * Delivers a storage path via {@code cb.onSuccess}.
     *
     * @param ctx      Used to retrieve the ECDH shared key.
     * @param filePath Absolute path to the local .3gp recording.
     * @param convId   Conversation ID used to namespace the file.
     */
    public void uploadVoiceNote(Context ctx, String filePath, String convId, UploadCallback cb) {
        new Thread(() -> {
            try {
                File f = new File(filePath);
                if (!f.exists()) throw new java.io.IOException("File not found: " + filePath);

                byte[] plain  = readFile(f);
                SecretKey key = CryptoInitializer.getSharedKey(ctx);
                byte[] data   = SupabaseStorageHelper.encryptBeforeUpload(plain, key);
                String path   = "voice/" + convId + "/" + java.util.UUID.randomUUID() + ".3gp";
                String stored = SupabaseStorageHelper.uploadFile(data, path, "audio/3gpp", null);
                cb.onSuccess(stored);

            } catch (Exception e) {
                Log.w(TAG, "uploadVoiceNote failed: " + e.getMessage());
                cb.onFailure(e);
            }
        }, "voice-upload").start();
    }

    /**
     * Resolves, downloads, and decrypts a voice note at {@code path}.
     * Delivers decrypted bytes on the main thread.
     */
    public void loadVoiceNote(Context ctx, String path, SupabaseStorageHelper.MediaCallback cb) {
        SecretKey key = CryptoInitializer.getSharedKey(ctx);
        SupabaseStorageHelper.loadMedia(path, key, cb);
    }

    private static byte[] readFile(File f) throws java.io.IOException {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }
}
