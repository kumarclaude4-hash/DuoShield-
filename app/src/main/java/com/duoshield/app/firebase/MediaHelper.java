package com.duoshield.app.firebase;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.util.SupabaseStorageHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import javax.crypto.SecretKey;

/**
 * Media helper — Supabase private bucket with AES-256-GCM E2E encryption.
 * Returns storage paths; never public URLs.
 */
public class MediaHelper {

    private static final String TAG = "MediaHelper";

    public interface UploadCallback {
        void onSuccess(String storagePath);
        void onFailure(Exception e);
    }

    /**
     * Encrypts and uploads a file from {@code fileUri} to the private bucket.
     *
     * @param ctx      Used to open the URI and retrieve the shared key.
     * @param fileUri  Source URI (image or video).
     * @param path     Storage path, e.g. {@code "media/chatId/uuid.jpg"}.
     * @param mimeType MIME type of the source file.
     */
    public void uploadMedia(Context ctx, Uri fileUri, String path,
                            String mimeType, UploadCallback cb) {
        new Thread(() -> {
            try {
                byte[] plain  = readUri(ctx, fileUri);
                SecretKey key = CryptoInitializer.getSharedKey(ctx);
                byte[] data   = SupabaseStorageHelper.encryptBeforeUpload(plain, key);
                String stored = SupabaseStorageHelper.uploadFile(data, path, mimeType, null);
                cb.onSuccess(stored);
            } catch (Exception e) {
                Log.w(TAG, "uploadMedia failed: " + e.getMessage());
                cb.onFailure(e);
            }
        }, "media-upload").start();
    }

    /**
     * Resolves, downloads, and decrypts media for {@code path}.
     * Delivers decrypted bytes on the main thread via {@code cb}.
     */
    public void loadMedia(Context ctx, String path, SupabaseStorageHelper.MediaCallback cb) {
        SecretKey key = CryptoInitializer.getSharedKey(ctx);
        SupabaseStorageHelper.loadMedia(path, key, cb);
    }

    private static byte[] readUri(Context ctx, Uri uri) throws java.io.IOException {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new java.io.IOException("Cannot open: " + uri);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally { is.close(); }
    }
}
