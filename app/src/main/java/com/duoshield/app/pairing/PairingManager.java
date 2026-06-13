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
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import javax.crypto.SecretKey;

public class PairingManager {

    private static final String PREFS_NAME      = "duoshield_prefs";
    private static final String KEY_IS_PAIRED   = "is_paired";
    private static final String KEY_CONV_ID     = "conversation_id";
    private static final String KEY_MY_UID      = "my_uid";
    private static final String KEY_PARTNER_UID = "partner_uid";

    private static final long   ROOM_EXPIRY_MS  = 10 * 60 * 1000L;
    private static final int    RETRY_DELAY_MS  = 2000;

    private final Context context;
    private final FirebaseFirestore db;
    private ListenerRegistration roomListener;

    public interface PairingCallback {
        void onCodeGenerated(String code);
        void onWaitingForPartner();
        void onPaired();
        void onError(String message);
    }

    public PairingManager(Context context) {
        this.context = context.getApplicationContext();
        this.db = FirebaseFirestore.getInstance();
    }

    // ── Create room ───────────────────────────────────────────────────────────

    public void createRoom(PairingCallback callback) {
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) {
            callback.onError("Not signed in. Please reopen the app.");
            return;
        }
        String code = generateCode();
        attemptCreateRoom(code, myUid, callback, false);
    }

    private void attemptCreateRoom(String code, String myUid,
                                    PairingCallback callback, boolean isRetry) {
        long now = System.currentTimeMillis();
        Map<String, Object> room = new HashMap<>();
        room.put("creatorUid", myUid);
        room.put("joinerUid",  "");
        room.put("status",     "waiting");
        room.put("createdAt",  now);
        room.put("expiresAt",  now + ROOM_EXPIRY_MS);

        db.collection("rooms").document(code)
            .set(room)
            .addOnSuccessListener(aVoid -> {
                callback.onCodeGenerated(code);
                callback.onWaitingForPartner();
                listenForPartner(code, myUid, callback);
            })
            .addOnFailureListener(e -> {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                // Retry once if the connection wasn't ready yet
                if (!isRetry && (msg.contains("offline") || msg.contains("unavailable")
                        || msg.contains("connect"))) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                        () -> attemptCreateRoom(code, myUid, callback, true),
                        RETRY_DELAY_MS);
                } else {
                    callback.onError(isRetry
                        ? "Cannot reach server. Check your internet connection."
                        : "Failed to create pairing room. Please try again.");
                }
            });
    }

    private void listenForPartner(String code, String myUid, PairingCallback callback) {
        DocumentReference roomRef = db.collection("rooms").document(code);
        roomListener = roomRef.addSnapshotListener((snapshot, e) -> {
            if (e != null || snapshot == null || !snapshot.exists()) return;
            String status    = snapshot.getString("status");
            String joinerUid = snapshot.getString("joinerUid");
            if ("paired".equals(status) && joinerUid != null && !joinerUid.isEmpty()) {
                stopListening();
                String convId = buildConversationId(code, myUid, joinerUid);
                savePrefs(myUid, joinerUid, convId);
                cleanupRoom(code);
                deriveAndStoreSharedKey(myUid, joinerUid, callback);
            }
        });
    }

    // ── Join room ─────────────────────────────────────────────────────────────

    public void joinRoom(String code, PairingCallback callback) {
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) {
            callback.onError("Not signed in. Please reopen the app.");
            return;
        }
        if (code == null || code.length() != 6) {
            callback.onError("Please enter a valid 6-digit code.");
            return;
        }

        DocumentReference roomRef = db.collection("rooms").document(code);
        roomRef.get()
            .addOnSuccessListener(snapshot -> {
                if (!snapshot.exists()) {
                    callback.onError("Invalid code. Room not found.");
                    return;
                }
                String status     = snapshot.getString("status");
                String creatorUid = snapshot.getString("creatorUid");
                Long expiresAt    = snapshot.getLong("expiresAt");
                if (creatorUid == null || creatorUid.isEmpty()) {
                    callback.onError("Invalid room data. Ask your contact to generate a new code.");
                    return;
                }
                if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
                    callback.onError("This code has expired. Ask your contact to generate a new one.");
                    cleanupRoom(code);
                    return;
                }
                if ("paired".equals(status)) {
                    callback.onError("This code has already been used.");
                    return;
                }
                if (myUid.equals(creatorUid)) {
                    callback.onError("You cannot join your own room.");
                    return;
                }

                Map<String, Object> update = new HashMap<>();
                update.put("joinerUid", myUid);
                update.put("status",    "paired");

                roomRef.update(update)
                    .addOnSuccessListener(aVoid -> {
                        String convId = buildConversationId(code, creatorUid, myUid);
                        savePrefs(myUid, creatorUid, convId);
                        cleanupRoom(code);
                        deriveAndStoreSharedKey(myUid, creatorUid, callback);
                    })
                    .addOnFailureListener(e -> callback.onError(
                            "Failed to join. Check your internet connection and try again."));
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

    // ── ECDH key exchange ─────────────────────────────────────────────────────

    private void deriveAndStoreSharedKey(String myUid, String partnerUid,
                                          PairingCallback callback) {
        SharedPreferences secure = SecurePrefs.get(context);
        String myPublicKeyB64 = secure.getString(CryptoInitializer.KEY_EC_PUBLIC, null);

        if (myPublicKeyB64 == null) {
            CryptoInitializer.ensureKeyExists(context);
            myPublicKeyB64 = secure.getString(CryptoInitializer.KEY_EC_PUBLIC, null);
        }
        if (myPublicKeyB64 == null) { callback.onPaired(); return; }

        final String finalMyPubB64 = myPublicKeyB64;

        db.collection("users").document(myUid)
          .set(Collections.singletonMap("ecPublicKey", finalMyPubB64), SetOptions.merge())
          .addOnSuccessListener(v -> {
              db.collection("users").document(partnerUid).get()
                .addOnSuccessListener(doc -> {
                    String partnerPubB64 = doc.getString("ecPublicKey");
                    if (partnerPubB64 != null && !partnerPubB64.isEmpty()) {
                        storeSharedKey(partnerPubB64, secure);
                    }
                    callback.onPaired();
                })
                .addOnFailureListener(e -> callback.onPaired());
          })
          .addOnFailureListener(e -> callback.onPaired());
    }

    private void storeSharedKey(String partnerPubB64, SharedPreferences secure) {
        try {
            PrivateKey myPrivKey = CryptoInitializer.getMyPrivateKey(context);
            if (myPrivKey == null) return;
            SecretKey sharedKey = ECDHHelper.deriveSharedKey(myPrivKey, partnerPubB64);
            String sharedB64 = Base64.encodeToString(sharedKey.getEncoded(), Base64.NO_WRAP);
            secure.edit()
                  .putString(CryptoInitializer.KEY_SHARED_AES, sharedB64)
                  .putString(CryptoInitializer.KEY_PARTNER_EC_PUBLIC, partnerPubB64)
                  .apply();
        } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String generateCode() {
        int num = 100000 + new Random().nextInt(900000);
        return String.valueOf(num);
    }

    private String buildConversationId(String code, String creatorUid, String joinerUid) {
        String a = creatorUid.compareTo(joinerUid) < 0 ? creatorUid : joinerUid;
        String b = creatorUid.compareTo(joinerUid) < 0 ? joinerUid  : creatorUid;
        String raw = code + "|" + a + "|" + b;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte byt : hash) hex.append(String.format("%02x", byt));
            return hex.toString();
        } catch (Exception e) {
            return raw.replaceAll("[^a-zA-Z0-9]", "");
        }
    }

    private void savePrefs(String myUid, String partnerUid, String convId) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit()
               .putBoolean(KEY_IS_PAIRED,   true)
               .putString(KEY_CONV_ID,      convId)
               .putString(KEY_MY_UID,       myUid)
               .putString(KEY_PARTNER_UID,  partnerUid)
               .apply();

        Map<String, Object> convData = new HashMap<>();
        convData.put("participants", java.util.Arrays.asList(myUid, partnerUid));
        db.collection("conversations").document(convId)
          .set(convData, SetOptions.merge());
    }

    private void cleanupRoom(String code) {
        db.collection("rooms").document(code).delete();
    }

    public void stopListening() {
        if (roomListener != null) { roomListener.remove(); roomListener = null; }
    }
}

