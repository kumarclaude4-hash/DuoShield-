package com.duoshield.app.security;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.ui.PairingActivity;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class DuressManager {

    private static final String PREF_NAME      = "duoshield_prefs";
    private static final String KEY_DURESS_HASH = "duress_pin_hash";

    // ── Set duress PIN ────────────────────────────────────────────────────────

    public static void setDuressPin(Context context, String pin) {
        String hash = sha256(pin);
        if (hash == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
               .edit()
               .putString(KEY_DURESS_HASH, hash)
               .apply();
    }

    // ── Check if entered PIN is the duress PIN ────────────────────────────────

    public static boolean isDuressPin(Context context, String enteredPin) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String storedHash = prefs.getString(KEY_DURESS_HASH, null);
        if (storedHash == null) return false;
        String enteredHash = sha256(enteredPin);
        return storedHash.equals(enteredHash);
    }

    // ── Trigger silent wipe ───────────────────────────────────────────────────

    public static void triggerDuress(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String conversationId   = prefs.getString("conversation_id", null);

        // 1. Delete Room messages on background thread
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                AppDatabase.getInstance(context).messageDao().deleteAll(conversationId);
            } catch (Exception ignored) {}
        });

        // 2. Delete Firestore messages (same 100-doc chunk pattern as SelfDestructWorker)
        if (conversationId != null) {
            deleteFirestoreMessages(conversationId);
        }

        // 3. Clear ALL SharedPreferences — wipes pairing, keys, tokens, everything
        prefs.edit().clear().apply();

        // 4. Navigate silently to PairingActivity — no Toast, no log, no dialog
        Intent intent = new Intent(context, PairingActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    // ── Firestore delete in 100-doc chunks ────────────────────────────────────

    private static void deleteFirestoreMessages(String conversationId) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("conversations")
          .document(conversationId)
          .collection("messages")
          .get()
          .addOnSuccessListener(snapshot -> {
              if (snapshot == null || snapshot.isEmpty()) return;

              List<QueryDocumentSnapshot> docs = new ArrayList<>();
              for (QueryDocumentSnapshot doc : snapshot) {
                  docs.add(doc);
              }

              // Delete in batches of 100
              int batchSize = 100;
              for (int i = 0; i < docs.size(); i += batchSize) {
                  WriteBatch batch = db.batch();
                  List<QueryDocumentSnapshot> chunk =
                          docs.subList(i, Math.min(i + batchSize, docs.size()));
                  for (QueryDocumentSnapshot doc : chunk) {
                      batch.delete(doc.getReference());
                  }
                  batch.commit(); // fire-and-forget; duress must be fast
              }
          });
    }

    // ── SHA-256 helper ────────────────────────────────────────────────────────

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
