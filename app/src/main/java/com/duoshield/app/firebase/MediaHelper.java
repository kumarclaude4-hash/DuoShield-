package com.duoshield.app.firebase;

import android.net.Uri;
import android.util.Log;
import com.duoshield.app.util.SupabaseStorageHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Media helper — migrated from Firebase Storage to Supabase private bucket.
 * Returns storage paths; never public URLs.
 */
public class MediaHelper {

    private static final String TAG = "MediaHelper";

    public interface UploadCallback {
        void onSuccess(String storagePath);
        void onFailure(Exception e);
    }

    /**
     * Uploads a file from {@code fileUri} to the Supabase private bucket.
     * {@code path} is the desired storage path, e.g. {@code "media/chatId/uuid.jpg"}.
     * {@code mimeType} must match the file content.
     */
    public void uploadMedia(android.content.Context ctx, Uri fileUri,
                            String path, String mimeType, UploadCallback cb) {
        new Thread(() -> {
            try {
                byte[] data = readUri(ctx, fileUri);
                data = SupabaseStorageHelper.encryptBeforeUpload(data);
                String storagePath = SupabaseStorageHelper.uploadFile(data, path, mimeType, null);
                cb.onSuccess(storagePath);
            } catch (Exception e) {
                Log.w(TAG, "uploadMedia failed: " + e.getMessage());
                cb.onFailure(e);
            }
        }, "media-upload").start();
    }

    /**
     * Resolves a short-lived signed URL for {@code path} from the private bucket.
     * Use the URL immediately; never cache or store it.
     */
    public void resolveSignedUrl(String path, SupabaseStorageHelper.SignedUrlCallback cb) {
        SupabaseStorageHelper.resolveSignedUrl(path, cb);
    }

    private static byte[] readUri(android.content.Context ctx, Uri uri) throws java.io.IOException {
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
