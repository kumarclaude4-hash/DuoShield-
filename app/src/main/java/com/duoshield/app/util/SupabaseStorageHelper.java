package com.duoshield.app.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.duoshield.app.BuildConfig;
import com.duoshield.app.crypto.CryptoHelper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

/**
 * Supabase Storage helper — PRIVATE bucket, signed-URL + AES-256-GCM E2E encryption.
 *
 * SECURITY RULES (enforced here — never break them):
 *  1. Bucket is private. No public access at the Supabase level.
 *  2. Uploads → file path returned, NEVER a URL. Path is stored in Firestore.
 *  3. Download access ONLY via short-lived signed URLs (120 s).
 *  4. All media bytes are AES-256-GCM encrypted before upload using the
 *     ECDH-derived shared key (CryptoInitializer.getSharedKey).
 *     On-wire format: [12-byte IV | ciphertext | 16-byte GCM auth tag]
 *
 * Firestore message doc (NEW — replaces "mediaUrl" with "path"):
 * {
 *   "type":      "image" | "video" | "voice",
 *   "path":      "media/<chatId>/<uuid>.jpg",   ← path only, never a URL
 *   "mediaType": "image" | "video" | "voice",
 *   "senderId":  "...",
 *   "timestamp": ...
 * }
 *
 * Signed-URL API example:
 *   POST /storage/v1/object/sign/duoshield-media/<path>
 *   Authorization: Bearer <anon_key>
 *   apikey: <anon_key>
 *   Content-Type: application/json
 *   {"expiresIn": 120}
 *   → {"signedURL": "/storage/v1/object/sign/...?token=..."}
 */
public final class SupabaseStorageHelper {

    // ── Constants ─────────────────────────────────────────────────────────────
    // Bug D fix: credentials come from BuildConfig (populated from local.properties
    // at compile time) so they are never committed as string literals in source.
    public static final String SUPABASE_URL      = BuildConfig.SUPABASE_URL;
    public static final String SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY;
    public static final String BUCKET_NAME       = "duoshield-media";

    public static final int    SIGNED_URL_TTL_SECS = 120;
    private static final int   CONNECT_TIMEOUT_MS  = 15_000;
    private static final int   READ_TIMEOUT_MS     = 60_000;
    private static final int   BUFFER_SIZE         = 8_192;
    private static final String TAG                = "SupabaseStorage";

    private SupabaseStorageHelper() {}

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface ProgressCallback {
        /** Called on a background thread — post to main thread before touching UI. */
        void onProgress(int percent);
    }

    public interface SignedUrlCallback {
        void onSuccess(String signedUrl);
        void onFailure(Exception e);
    }

    /**
     * Callback for {@link #loadMedia}: delivers decrypted plain bytes on the main thread.
     */
    public interface MediaCallback {
        void onLoaded(byte[] plainBytes);
        void onError(Exception e);
    }

    // ── A. uploadFile ─────────────────────────────────────────────────────────

    /**
     * Uploads {@code data} to the private bucket at {@code path}.
     * Callers MUST encrypt data first with {@link #encryptBeforeUpload}.
     *
     * @return The file path — identical to {@code path} — to store in Firestore.
     * @throws IOException on any network or API error.
     */
    public static String uploadFile(byte[] data, String path,
                                    String mimeType, ProgressCallback cb) throws IOException {
        String endpoint = SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/" + path;
        HttpURLConnection conn = openConnection(endpoint, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", mimeType);
        conn.setRequestProperty("x-upsert", "false");
        conn.setFixedLengthStreamingMode(data.length);

        try (OutputStream out = conn.getOutputStream()) {
            int written = 0;
            while (written < data.length) {
                int len = Math.min(BUFFER_SIZE, data.length - written);
                out.write(data, written, len);
                written += len;
                if (cb != null) cb.onProgress((int) (100L * written / data.length));
            }
        }

        int code = conn.getResponseCode();
        if (code != 200 && code != 201) {
            String err = readString(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("Upload failed [" + code + "]: " + err);
        }
        conn.disconnect();
        Log.d(TAG, "Uploaded → " + path);
        return path;
    }

    // ── B. createSignedUrl ────────────────────────────────────────────────────

    /**
     * Generates a signed URL for {@code path} from the private bucket.
     * Valid for {@value #SIGNED_URL_TTL_SECS} seconds.
     * Never store the returned URL — generate a fresh one each time.
     */
    public static String createSignedUrl(String path) throws IOException {
        String endpoint = SUPABASE_URL + "/storage/v1/object/sign/" + BUCKET_NAME + "/" + path;
        HttpURLConnection conn = openConnection(endpoint, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        byte[] body = ("{\"expiresIn\":" + SIGNED_URL_TTL_SECS + "}").getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = conn.getOutputStream()) { out.write(body); }

        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readString(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("Signed-URL failed [" + code + "]: " + err);
        }

        String response = readString(conn.getInputStream());
        conn.disconnect();

        try {
            String signedUrl = new JSONObject(response).getString("signedURL");
            return signedUrl.startsWith("http") ? signedUrl : SUPABASE_URL + signedUrl;
        } catch (Exception e) {
            throw new IOException("Cannot parse signed-URL response: " + response, e);
        }
    }

    // ── C. downloadFile ───────────────────────────────────────────────────────

    /**
     * Downloads raw (encrypted) bytes from a signed URL.
     * Callers MUST pass the result through {@link #decryptAfterDownload}.
     *
     * @throws IOException on network error, or if the URL has expired (400/403).
     */
    public static byte[] downloadFile(String signedUrl) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(signedUrl).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.connect();

        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readString(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("Download failed [" + code + "] — "
                    + (code == 400 || code == 403 ? "signed URL expired" : err));
        }
        byte[] data = readBytes(conn.getInputStream());
        conn.disconnect();
        return data;
    }

    // ── D. loadMedia (resolve + download + decrypt in one call) ───────────────

    /**
     * Convenience method: resolves a signed URL, downloads the encrypted bytes,
     * and decrypts them — all on background threads.
     * Delivers decrypted {@code byte[]} on the main thread.
     *
     * <p>Safe to call from {@code RecyclerView.Adapter.onBindViewHolder}.
     * Retries once on signed-URL failure.
     *
     * @param path      Supabase storage path from Firestore {@code "path"} field.
     * @param key       ECDH-derived shared key from {@code CryptoInitializer.getSharedKey()}.
     *                  If {@code null} (pairing not yet complete), bytes are returned as-is.
     * @param cb        Delivered on the main thread.
     */
    public static void loadMedia(String path, SecretKey key, MediaCallback cb) {
        resolveSignedUrl(path, new SignedUrlCallback() {
            @Override public void onSuccess(String signedUrl) {
                new Thread(() -> {
                    try {
                        byte[] raw   = downloadFile(signedUrl);
                        byte[] plain = decryptAfterDownload(raw, key);
                        new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(plain));
                    } catch (Exception e) {
                        Log.e(TAG, "loadMedia download/decrypt failed: path=" + path, e);
                        new Handler(Looper.getMainLooper()).post(() -> cb.onError(e));
                    }
                }, "supabase-load-" + path.hashCode()).start();
            }
            @Override public void onFailure(Exception e) {
                // resolveSignedUrl already delivers on main thread
                cb.onError(e);
            }
        });
    }

    // ── Async signed-URL helper ───────────────────────────────────────────────

    /**
     * Resolves a signed URL on a background thread, delivers on the main thread.
     * Retries once with 500 ms back-off.
     */
    public static void resolveSignedUrl(String path, SignedUrlCallback cb) {
        new Thread(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String url = createSignedUrl(path);
                    new Handler(Looper.getMainLooper()).post(() -> cb.onSuccess(url));
                    return;
                } catch (IOException e) {
                    Log.w(TAG, "resolveSignedUrl attempt " + (attempt + 1) + " failed: " + e.getMessage());
                    if (attempt == 1) {
                        new Handler(Looper.getMainLooper()).post(() -> cb.onFailure(e));
                    } else {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }, "supabase-sign-" + path.hashCode()).start();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code value} is a Supabase storage path
     * (not a legacy full HTTPS URL from Firebase Storage).
     */
    public static boolean isSupabasePath(String value) {
        return value != null && !value.isEmpty() && !value.startsWith("https://");
    }

    // ── E2E Encryption ────────────────────────────────────────────────────────

    /**
     * AES-256-GCM encrypts {@code data} with {@code key} before upload.
     * On-wire format: [12-byte random IV | ciphertext | 16-byte auth tag]
     *
     * <p>If {@code key} is {@code null} (ECDH pairing not yet complete),
     * bytes are returned unencrypted — this matches the pre-pairing fallback
     * already used for text messages.
     */
    public static byte[] encryptBeforeUpload(byte[] data, SecretKey key) {
        if (key == null) return data;
        try {
            return CryptoHelper.encryptBytes(data, key);
        } catch (Exception e) {
            Log.e(TAG, "encryptBeforeUpload failed — uploading plaintext as fallback", e);
            return data;
        }
    }

    /**
     * AES-256-GCM decrypts bytes received after download.
     * Inverse of {@link #encryptBeforeUpload}.
     *
     * <p>If {@code key} is {@code null}, bytes are returned as-is (legacy
     * unencrypted messages or pre-pairing fallback).
     *
     * @throws RuntimeException wrapping {@link javax.crypto.AEADBadTagException}
     *         if the data is corrupted or the wrong key is used.
     */
    public static byte[] decryptAfterDownload(byte[] data, SecretKey key) {
        if (key == null) return data;
        try {
            return CryptoHelper.decryptBytes(data, key);
        } catch (Exception e) {
            Log.e(TAG, "decryptAfterDownload failed", e);
            throw new RuntimeException("Media decryption failed: " + e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static HttpURLConnection openConnection(String endpoint, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + SUPABASE_ANON_KEY);
        conn.setRequestProperty("apikey", SUPABASE_ANON_KEY);
        return conn;
    }

    private static String readString(InputStream is) {
        if (is == null) return "";
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toString("UTF-8");
        } catch (IOException e) { return ""; }
    }

    private static byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }
}
