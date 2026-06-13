package com.duoshield.app.firebase;

import android.util.Log;
import com.duoshield.app.util.SupabaseStorageHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Voice-note helper — migrated from Firebase Storage to Supabase private bucket.
 * Returns storage paths; never public URLs.
 */
public class VoiceNoteHelper {

    private static final String TAG = "VoiceNoteHelper";

    public interface UploadCallback {
        void onSuccess(String storagePath);
        void onFailure(Exception e);
    }

    /**
     * Uploads the voice file at {@code filePath} to the private Supabase bucket.
     * The storage path is delivered via {@code cb.onSuccess}.
     *
     * @param filePath  Absolute path to the local .3gp recording.
     * @param convId    Conversation ID used to namespace the file.
     */
    public void uploadVoiceNote(String filePath, String convId, UploadCallback cb) {
        new Thread(() -> {
            try {
                File f = new File(filePath);
                if (!f.exists()) throw new java.io.IOException("File not found: " + filePath);

                byte[] data = readFile(f);
                data = SupabaseStorageHelper.encryptBeforeUpload(data);

                String path = "voice/" + convId + "/" + java.util.UUID.randomUUID() + ".3gp";
                String storagePath = SupabaseStorageHelper.uploadFile(data, path, "audio/3gpp", null);
                cb.onSuccess(storagePath);
            } catch (Exception e) {
                Log.w(TAG, "uploadVoiceNote failed: " + e.getMessage());
                cb.onFailure(e);
            }
        }, "voice-upload").start();
    }

    /**
     * Resolves a short-lived signed URL for {@code path}.
     * Generate just before playback; never cache or store the returned URL.
     */
    public void resolveSignedUrl(String path, SupabaseStorageHelper.SignedUrlCallback cb) {
        SupabaseStorageHelper.resolveSignedUrl(path, cb);
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
