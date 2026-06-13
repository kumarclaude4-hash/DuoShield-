package com.duoshield.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.duoshield.app.crypto.CryptoInitializer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import javax.crypto.SecretKey;

/**
 * Upload helper — Supabase Storage (private bucket, AES-256-GCM E2E encrypted).
 * Returns storage PATHS, never URLs.
 */
public class UploadHelper {

    public interface UploadCallback {
        void onProgress(int percent);
        void onSuccess(String storagePath);   // PATH only — store in Firestore
        void onFailure(Exception e);
    }

    /**
     * Compresses {@code uri} to JPEG 80%, AES-256-GCM encrypts, then uploads.
     * Delivers a storage path via {@code cb.onSuccess}.
     */
    public static void uploadImage(Context ctx, Uri uri, String convId, UploadCallback cb) {
        new Thread(() -> {
            try {
                InputStream is = ctx.getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (is != null) is.close();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] plain = baos.toByteArray();

                SecretKey key  = CryptoInitializer.getSharedKey(ctx);
                byte[] data    = SupabaseStorageHelper.encryptBeforeUpload(plain, key);
                String path    = "media/" + convId + "/images/" + System.currentTimeMillis() + ".jpg";
                String stored  = SupabaseStorageHelper.uploadFile(data, path, "image/jpeg", cb::onProgress);
                cb.onSuccess(stored);

            } catch (Exception e) { cb.onFailure(e); }
        }, "upload-image").start();
    }

    /**
     * Reads voice bytes from {@code filePath}, AES-256-GCM encrypts, uploads.
     * Delivers a storage path via {@code cb.onSuccess}.
     */
    public static void uploadVoice(Context ctx, String filePath, String convId, UploadCallback cb) {
        new Thread(() -> {
            try {
                byte[] plain  = readFile(new java.io.File(filePath));
                SecretKey key = CryptoInitializer.getSharedKey(ctx);
                byte[] data   = SupabaseStorageHelper.encryptBeforeUpload(plain, key);
                String path   = "media/" + convId + "/voice/" + System.currentTimeMillis() + ".3gp";
                String stored = SupabaseStorageHelper.uploadFile(data, path, "audio/3gpp", cb::onProgress);
                cb.onSuccess(stored);

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
