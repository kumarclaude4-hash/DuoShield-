package com.duoshield.app.util;

import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

public class EditMessageHelper {
    private static final long EDIT_WINDOW_MS = 48 * 60 * 60 * 1000L;

    public static boolean canEdit(long timestamp, String sender, String myUid) {
        return myUid.equals(sender)
            && (System.currentTimeMillis() - timestamp) < EDIT_WINDOW_MS;
    }

    public static void editMessage(String convId, String messageId, String newText) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("text",   newText);
        updates.put("edited", true);
        FirebaseFirestore.getInstance()
            .collection("conversations").document(convId)
            .collection("messages").document(messageId)
            .update(updates);
    }
}
