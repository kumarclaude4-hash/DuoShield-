package com.duoshield.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Upload helper — now backed by Supabase Storage (private bucket, signed URLs).
 * Firebase Storage has been fully removed from this class.
 */
public class UploadHelper {

    public interface UploadCallback {
        void onProgress(int percent);
        void onSuccess(String storagePath);   // returns PATH, not URL
        void onFailure(Exception e);
    }

    /**
     * Compresses {@code uri} to JPEG, encrypts (stub), then uploads to Supabase.
     * Delivers a storage path via {@code cb.onSuccess} — store this path in Firestore,
     * never the URL.
     */
    public static void uploadImage(Context ctx, Uri uri, String convId, UploadCallback cb) {
        new Thread(() -> {
            try {
                InputStream is = ctx.getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (is != null) is.close();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] data = baos.toByteArray();

                // Encryption placeholder — swap body when E2E media encryption is ready
                data = SupabaseStorageHelper.encryptBeforeUpload(data);

                String path = "media/" + convId + "/images/" + System.currentTimeMillis() + ".jpg";
                String storagePath = SupabaseStorageHelper.uploadFile(data, path, "image/jpeg", cb::onProgress);
                cb.onSuccess(storagePath);

            } catch (Exception e) { cb.onFailure(e); }
        }, "upload-image").start();
    }

    /**
     * Reads voice bytes from {@code filePath}, encrypts (stub), uploads to Supabase.
     * Delivers a storage path via {@code cb.onSuccess}.
     */
    public static void uploadVoice(Context ctx, String filePath, String convId, UploadCallback cb) {
        new Thread(() -> {
            try {
                java.io.File f = new java.io.File(filePath);
                byte[] data = readFile(f);
                data = SupabaseStorageHelper.encryptBeforeUpload(data);

                String path = "media/" + convId + "/voice/" + System.currentTimeMillis() + ".3gp";
                String storagePath = SupabaseStorageHelper.uploadFile(data, path, "audio/3gpp", cb::onProgress);
                cb.onSuccess(storagePath);

            } catch (Exception e) { cb.onFailure(e); }
        }, "upload-voice").start();
    }

    private static byte[] readFile(java.io.File f) throws java.io.IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }
}
