package com.duoshield.app.security;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.ui.PairingActivity;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class DuressManager {

    private static final String PREF_NAME        = "duoshield_prefs";
    private static final String KEY_DURESS_HASH  = "duress_pin_hash";
    private static final int    ITERATIONS       = 310_000;
    private static final int    KEY_LEN          = 256;

    public static void setDuressPin(Context context, String pin) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(pin, salt);
            String stored = bytesToHex(salt) + ":" + bytesToHex(hash);
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                   .edit().putString(KEY_DURESS_HASH, stored).apply();
        } catch (Exception ignored) {}
    }

    public static boolean isDuressPin(Context context, String enteredPin) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_DURESS_HASH, null);
        if (stored == null) return false;
        int sep = stored.indexOf(':');
        if (sep < 0) return false;
        try {
            byte[] salt = hexToBytes(stored.substring(0, sep));
            byte[] expected = hexToBytes(stored.substring(sep + 1));
            byte[] actual = pbkdf2(enteredPin, salt);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) { return false; }
    }

    public static void triggerDuress(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String conversationId   = prefs.getString("conversation_id", null);

        // 1. Atomically wipe ALL SharedPreferences FIRST — synchronous commit
        prefs.edit().clear().commit();

        // 2. Delete Room messages on background thread
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                AppDatabase.getInstance(context).messageDao().deleteAll(conversationId);
            } catch (Exception ignored) {}
        });

        // 3. Delete Firestore messages
        if (conversationId != null) {
            deleteFirestoreMessages(conversationId);
        }

        // 4. Navigate silently to PairingActivity — no Toast, no log, no dialog
        Intent intent = new Intent(context, PairingActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    private static void deleteFirestoreMessages(String conversationId) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("chats")
          .document(conversationId)
          .collection("messages")
          .get()
          .addOnSuccessListener(snapshot -> {
              if (snapshot == null || snapshot.isEmpty()) return;

              List<QueryDocumentSnapshot> docs = new ArrayList<>();
              for (QueryDocumentSnapshot doc : snapshot) docs.add(doc);

              int batchSize = 100;
              for (int i = 0; i < docs.size(); i += batchSize) {
                  WriteBatch batch = db.batch();
                  List<QueryDocumentSnapshot> chunk =
                          docs.subList(i, Math.min(i + batchSize, docs.size()));
                  for (QueryDocumentSnapshot doc : chunk) batch.delete(doc.getReference());
                  batch.commit();
              }
          });
    }

    private static byte[] pbkdf2(String pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LEN);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return out;
    }
}
