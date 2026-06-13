package com.duoshield.app.firebase;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

public class FirestoreHelper {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    /**
     * Saves a message to the conversation's messages subcollection.
     * Includes selfDestructAt so Cloud Functions can sweep expired messages
     * server-side even when both devices are offline.
     */
    public void saveMessage(String conversationId, String senderId,
                            String content, long timestamp, Context context) {
        SharedPreferences prefs = context.getSharedPreferences("duoshield_prefs",
                                                                Context.MODE_PRIVATE);
        long ttlMinutes = prefs.getLong("self_destruct_minutes", 60L);

        Map<String, Object> message = new HashMap<>();
        message.put("conversationId", conversationId);
        message.put("sender",         senderId);
        message.put("content",        content);
        message.put("timestamp",      timestamp);
        message.put("selfDestructAt", timestamp + ttlMinutes * 60_000L);

        db.collection("chats")
          .document(conversationId)
          .collection("messages")
          .add(message)
          .addOnFailureListener(e ->
              android.util.Log.w("FirestoreHelper", "saveMessage failed: " + e.getMessage()));
    }
}
