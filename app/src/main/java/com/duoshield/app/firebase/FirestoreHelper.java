package com.duoshield.app.firebase;

import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

public class FirestoreHelper {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public void saveMessage(String conversationId, String senderId, String content, long timestamp) {
        Map<String, Object> message = new HashMap<>();
        message.put("conversationId", conversationId);
        message.put("senderId", senderId);
        message.put("content", content);
        message.put("timestamp", timestamp);
        db.collection("messages").add(message);
    }
}