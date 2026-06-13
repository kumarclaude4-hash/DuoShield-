package com.duoshield.app.pairing;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.crypto.ECDHHelper;
import com.duoshield.app.util.SecurePrefs;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;

/**
 * Permanent-connection manager.
 *
 * Replaces the temporary 6-digit pairing-code system with a stable
 * UserID-based connect flow:
 *
 *  1. User enters partner's DS-XXXXXXXX User ID.
 *  2. App resolves ID → Firebase UID via  identities/{userId}.uid
 *  3. App uploads own EC public key to     users/{myUid}.ecPublicKey
 *  4. App fetches partner EC public key from users/{partnerUid}.ecPublicKey
 *     (retries up to KEY_FETCH_RETRIES times, KEY_FETCH_DELAY_MS apart).
 *  5. ECDH shared key is derived and stored in EncryptedSharedPreferences.
 *  6. chatId = SHA-256( smaller_uid + "/" + larger_uid )  — deterministic,
 *     identical on both devices, no race condition possible.
 *  7. chats/{chatId} document is created/merged with participant UIDs.
 *  8. SharedPreferences are saved and onPaired() is called.
 *
 * Firestore path: chats/{chatId}/messages/{messageId}
 * (was: conversations/{conversationId}/messages/{messageId})
 */
public class PairingManager {

    private static final String PREFS_NAME      = "duoshield_prefs";
    private static final String KEY_IS_PAIRED   = "is_paired";
    private static final String KEY_CONV_ID     = "conversation_id";
    private static final String KEY_MY_UID      = "my_uid";
    private static final String KEY_PARTNER_UID = "partner_uid";

    private static final int  KEY_FETCH_RETRIES  = 3;
    private static final long KEY_FETCH_DELAY_MS = 1500L;

    private final Context         context;
    private final FirebaseFirestore db;

    public interface PairingCallback {
        void onPaired();
        void onError(String message);
    }

    public PairingManager(Context context) {
        this.context = context.getApplicationContext();
        this.db      = FirebaseFirestore.getInstance();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Connect to a partner by their DuoShield User ID (e.g. DS-A1B2C3D4).
     * Safe to call from the UI thread — all Firestore work is async.
     */
    public void connectByUserId(String partnerId, PairingCallback callback) {
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) {
            callback.onError("Not signed in. Please reopen the app.");
            return;
        }
        if (partnerId == null || partnerId.trim().isEmpty()) {
            callback.onError("Please enter your partner's User ID.");
            return;
        }
        String id = partnerId.trim().toUpperCase();
        if (!id.matches("DS-[0-9A-F]{8}")) {
            callback.onError("Invalid User ID format. Expected DS-XXXXXXXX (e.g. DS-A1B2C3D4).");
            return;
        }

        // Step 1 — resolve userId → Firebase UID
        db.collection("identities").document(id).get()
            .addOnSuccessListener(snap -> {
                if (!snap.exists()) {
                    callback.onError("User ID not found. Check the ID and try again.");
                    return;
                }
                String partnerUid = snap.getString("uid");
                if (partnerUid == null || partnerUid.isEmpty()) {
                    callback.onError("User ID not found. Check the ID and try again.");
                    return;
                }
                if (partnerUid.equals(myUid)) {
                    callback.onError("You cannot connect to yourself.");
                    return;
                }
                uploadMyKeyThenFetch(myUid, partnerUid, callback);
            })
            .addOnFailureListener(e -> {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (msg.contains("offline") || msg.contains("unavailable")) {
                    callback.onError("No connection. Check your internet and try again.");
                } else {
                    callback.onError("Failed to reach server. Please try again.");
                }
            });
    }

    // ── Step 2 — upload own EC public key, then kick off partner-key fetch ────

    private void uploadMyKeyThenFetch(String myUid, String partnerUid,
                                       PairingCallback callback) {
        SharedPreferences secure = SecurePrefs.get(context);
        String myPubB64 = secure.getString(CryptoInitializer.KEY_EC_PUBLIC, null);
        if (myPubB64 == null) {
            CryptoInitializer.ensureKeyExists(context);
            myPubB64 = secure.getString(CryptoInitializer.KEY_EC_PUBLIC, null);
        }
        if (myPubB64 == null) {
            callback.onError("Encryption key not ready. Please restart the app and try again.");
            return;
        }
        final String finalMyPub    = myPubB64;
        final SharedPreferences sp = secure;

        db.collection("users").document(myUid)
            .set(Collections.singletonMap("ecPublicKey", finalMyPub), SetOptions.merge())
            .addOnSuccessListener(v ->
                fetchPartnerKeyWithRetry(myUid, partnerUid, sp, callback, KEY_FETCH_RETRIES))
            .addOnFailureListener(e ->
                fetchPartnerKeyWithRetry(myUid, partnerUid, sp, callback, KEY_FETCH_RETRIES));
    }

    // ── Step 3 — fetch partner key (retry on null) ────────────────────────────

    private void fetchPartnerKeyWithRetry(String myUid, String partnerUid,
                                          SharedPreferences secure,
                                          PairingCallback callback, int retriesLeft) {
        db.collection("users").document(partnerUid).get()
            .addOnSuccessListener(doc -> {
                String partnerPubB64 = doc.getString("ecPublicKey");
                if (partnerPubB64 != null && !partnerPubB64.isEmpty()) {
                    finalizeConnection(myUid, partnerUid, partnerPubB64, secure, callback);
                } else if (retriesLeft > 0) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                        () -> fetchPartnerKeyWithRetry(
                                myUid, partnerUid, secure, callback, retriesLeft - 1),
                        KEY_FETCH_DELAY_MS);
                } else {
                    // Partner hasn't uploaded their key yet — connect anyway.
                    // ECDH will complete once both sides are online.
                    finalizeConnection(myUid, partnerUid, null, secure, callback);
                }
            })
            .addOnFailureListener(e ->
                finalizeConnection(myUid, partnerUid, null, secure, callback));
    }

    // ── Steps 4–7 — derive key, compute chatId, write Firestore, save prefs ──

    private void finalizeConnection(String myUid, String partnerUid,
                                    String partnerPubB64,
                                    SharedPreferences secure,
                                    PairingCallback callback) {
        // Step 4 — ECDH shared key derivation
        if (partnerPubB64 != null) {
            try {
                PrivateKey myPrivKey = CryptoInitializer.getMyPrivateKey(context);
                if (myPrivKey != null) {
                    SecretKey sharedKey  = ECDHHelper.deriveSharedKey(myPrivKey, partnerPubB64);
                    String    sharedB64  = Base64.encodeToString(
                            sharedKey.getEncoded(), Base64.NO_WRAP);
                    secure.edit()
                          .putString(CryptoInitializer.KEY_SHARED_AES,        sharedB64)
                          .putString(CryptoInitializer.KEY_PARTNER_EC_PUBLIC,  partnerPubB64)
                          .apply();
                }
            } catch (Exception ignored) {}
        }

        // Step 5 — deterministic chatId: SHA-256(smaller_uid/larger_uid)
        String chatId = buildChatId(myUid, partnerUid);

        // Step 6 — create / merge chats/{chatId}
        Map<String, Object> chatDoc = new HashMap<>();
        chatDoc.put("participants", Arrays.asList(myUid, partnerUid));
        db.collection("chats").document(chatId)
            .set(chatDoc, SetOptions.merge());

        // Step 7 — persist locally
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit()
               .putBoolean(KEY_IS_PAIRED,   true)
               .putString(KEY_CONV_ID,      chatId)
               .putString(KEY_MY_UID,       myUid)
               .putString(KEY_PARTNER_UID,  partnerUid)
               .apply();

        callback.onPaired();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Deterministic SHA-256 chat ID from two Firebase UIDs.
     * Sorts lexicographically so the result is identical regardless of
     * which user initiates the connection.
     */
    public static String buildChatId(String uidA, String uidB) {
        String a   = uidA.compareTo(uidB) < 0 ? uidA : uidB;
        String b   = uidA.compareTo(uidB) < 0 ? uidB  : uidA;
        String raw = a + "/" + b;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte bt : hash) hex.append(String.format("%02x", bt));
            return hex.toString();
        } catch (Exception e) {
            return raw.replaceAll("[^a-zA-Z0-9]", "");
        }
    }

    /** No-op — kept for source compatibility; room system is removed. */
    public void stopListening() {}
}
