package com.duoshield.app.util;

import android.content.Context;
import android.util.Log;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.HashMap;
import java.util.Map;

public class ConversationMetaUpdater {

    private static final String TAG = "ConversationMetaUpdater";

    public static void update(Context ctx, String convId, String senderUid,
                              String recipientUid, String preview) {
        if (convId == null) return;

        // Bug 20 fix: EncryptionHelper.encrypt() now returns null on failure instead of
        // silently returning plaintext. If encryption fails (key not ready, key mismatch,
        // cipher exception) we skip writing lastMessage rather than storing it in the clear.
        String encryptedPreview = EncryptionHelper.encrypt(ctx, preview != null ? preview : "");
        if (encryptedPreview == null) {
            Log.w(TAG, "update: skipping lastMessage write — encryption failed or key unavailable");
        }

        Map<String, Object> data = new HashMap<>();
        if (encryptedPreview != null) {
            data.put("lastMessage", encryptedPreview);
        }
        data.put("lastMessageTs", FieldValue.serverTimestamp());
        data.put("lastSenderId",  senderUid);
        if (recipientUid != null) {
            data.put("unread_" + recipientUid, FieldValue.increment(1));
        }
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .set(data, SetOptions.merge());
    }
}
