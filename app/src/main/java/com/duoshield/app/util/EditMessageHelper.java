package com.duoshield.app.util;

import android.content.Context;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

public class EditMessageHelper {
    private static final long EDIT_WINDOW_MS = 48 * 60 * 60 * 1000L;

    public static boolean canEdit(long timestamp, String sender, String myUid) {
        return myUid.equals(sender)
            && (System.currentTimeMillis() - timestamp) < EDIT_WINDOW_MS;
    }

    public static void editMessage(Context ctx, String convId, String messageId, String newText) {
        String encrypted = EncryptionHelper.encrypt(ctx, newText);
        Map<String, Object> updates = new HashMap<>();
        updates.put("text",   encrypted);
        updates.put("edited", true);
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .collection("messages").document(messageId)
            .update(updates);
    }
}
