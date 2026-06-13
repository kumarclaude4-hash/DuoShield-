package com.duoshield.app.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Supabase Storage helper — PRIVATE bucket, signed-URL access only.
 *
 * RULES (enforced here — never break them):
 *  1. The bucket is private. Public access is disabled at the Supabase level.
 *  2. Uploads return ONLY the file path. No public URL is ever generated or stored.
 *  3. Media is accessed exclusively through short-lived signed URLs (120 s).
 *
 * Firestore message doc structure (NEW — replaces "mediaUrl"):
 * {
 *   "type":      "image" | "video" | "voice",
 *   "path":      "media/<chatId>/<uuid>.jpg",   ← stored path, NOT a URL
 *   "mediaType": "image" | "video" | "voice",
 *   "senderId":  "...",
 *   "timestamp": ...
 * }
 *
 * Signed-URL request example (POST):
 *   POST https://swpwkwniyrvnsmqafnhz.supabase.co/storage/v1/object/sign/duoshield-media/<path>
 *   Authorization: Bearer <anon_key>
 *   apikey: <anon_key>
 *   Content-Type: application/json
 *   Body: {"expiresIn": 120}
 *
 * Encryption stubs (encryptBeforeUpload / decryptAfterDownload) are identity
 * functions. Swap the body with AES-256-GCM + ECDH shared key to add E2E
 * media encryption without changing the rest of the integration.
 */
public final class SupabaseStorageHelper {

    // ── Constants ─────────────────────────────────────────────────────────────
    public static final String SUPABASE_URL     = "https://swpwkwniyrvnsmqafnhz.supabase.co";
    public static final String SUPABASE_ANON_KEY= "sb_publishable_cKOQKw5gbnM_rgkelAOPZw_gkS_-2Fl";
    public static final String BUCKET_NAME      = "duoshield-media";

    public static final int  SIGNED_URL_TTL_SECS = 120;
    private static final int CONNECT_TIMEOUT_MS  = 15_000;
    private static final int READ_TIMEOUT_MS     = 60_000;
    private static final int BUFFER_SIZE         = 8_192;
    private static final String TAG              = "SupabaseStorage";

    private SupabaseStorageHelper() {}

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface ProgressCallback {
        /** Called on a background thread; post to main thread before touching UI. */
        void onProgress(int percent);
    }

    public interface SignedUrlCallback {
        void onSuccess(String signedUrl);
        void onFailure(Exception e);
    }

    // ── A. uploadFile ─────────────────────────────────────────────────────────

    /**
     * Uploads {@code data} to the private bucket at {@code path}.
     *
     * <p>Call {@link #encryptBeforeUpload} on the bytes first once the
     * encryption layer is wired in.
     *
     * @param data     Raw (or encrypted) bytes.
     * @param path     Storage path, e.g. {@code "media/chatId/uuid.jpg"}.
     * @param mimeType MIME type, e.g. {@code "image/jpeg"}.
     * @param cb       Optional progress callback (may be {@code null}).
     * @return         The file path (identical to {@code path}) on success.
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
     *
     * <p>The URL is valid for {@value #SIGNED_URL_TTL_SECS} seconds.
     * Never store the returned URL — generate a fresh one each time.
     *
     * @param path File path previously returned by {@link #uploadFile}.
     * @return     Absolute HTTPS signed URL.
     * @throws IOException on network or API error (including expired paths).
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
            // Supabase may return a relative path; make it absolute.
            return signedUrl.startsWith("http") ? signedUrl : SUPABASE_URL + signedUrl;
        } catch (Exception e) {
            throw new IOException("Cannot parse signed-URL response: " + response, e);
        }
    }

    // ── C. downloadFile ───────────────────────────────────────────────────────

    /**
     * Downloads raw bytes from a signed URL.
     *
     * <p>Pass the result through {@link #decryptAfterDownload} once the
     * encryption layer is active.
     *
     * @param signedUrl Temporary URL from {@link #createSignedUrl}.
     * @return          Raw (or encrypted) bytes.
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

    // ── Async signed-URL helper ───────────────────────────────────────────────

    /**
     * Resolves a signed URL on a background thread, delivers the result on
     * the main thread. Retries once on failure with a 500 ms back-off.
     *
     * <p>Safe to call from {@code RecyclerView.Adapter.onBindViewHolder}.
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
                        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                }
            }
        }, "supabase-sign-" + path.hashCode()).start();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code value} is a Supabase storage path
     * rather than a full HTTPS URL (legacy Firebase Storage messages).
     */
    public static boolean isSupabasePath(String value) {
        return value != null && !value.isEmpty() && !value.startsWith("https://");
    }

    // ── Encryption stubs ─────────────────────────────────────────────────────

    /**
     * Placeholder: encrypt bytes before upload.
     * Replace with AES-256-GCM using the ECDH-derived shared key.
     */
    public static byte[] encryptBeforeUpload(byte[] plainData) {
        return plainData; // TODO: AES-256-GCM encrypt with shared key from CryptoInitializer
    }

    /**
     * Placeholder: decrypt bytes after download.
     * Replace with AES-256-GCM decryption using the ECDH-derived shared key.
     */
    public static byte[] decryptAfterDownload(byte[] data) {
        return data; // TODO: AES-256-GCM decrypt with shared key from CryptoInitializer
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
