package com.duoshield.app.util;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.HashMap;
import java.util.Map;

public class ConversationMetaUpdater {

    public static void update(String convId, String senderUid,
                              String recipientUid, String preview) {
        if (convId == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("lastMessage",   preview);
        data.put("lastMessageTs", FieldValue.serverTimestamp());
        data.put("lastSenderId",  senderUid);
        data.put("unread_" + recipientUid, FieldValue.increment(1));
        FirebaseFirestore.getInstance()
            .collection("conversations").document(convId)
            .set(data, SetOptions.merge());
    }
}
