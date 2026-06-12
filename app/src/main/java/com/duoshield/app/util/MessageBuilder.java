package com.duoshield.app.util;

import android.content.Context;
import com.duoshield.app.crypto.CryptoHelper;
import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.crypto.SecretKey;

public class MessageBuilder {

    public static void sendTextMessage(Context ctx, String convId, String myUid,
                                       String partnerUid, String text,
                                       String replyToId, String replyToText) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                SecretKey key = CryptoInitializer.getSharedKey(ctx);
                String encrypted = text;
                boolean enc = false;
                if (key != null) {
                    encrypted = CryptoHelper.encrypt(text, key);
                    enc = true;
                }

                String msgId = UUID.randomUUID().toString();
                long now = System.currentTimeMillis();

                Message local = new Message();
                local.setId(msgId);
                local.setConversationId(convId);
                local.setSender(myUid);
                local.setText(text);
                local.setTimestamp(now);
                local.setStatus("pending");
                AppDatabase.getInstance(ctx).messageDao().insert(local);

                Map<String, Object> doc = new HashMap<>();
                doc.put("sender",     myUid);
                doc.put("text",       encrypted);
                doc.put("type",       "text");
                doc.put("timestamp",  FieldValue.serverTimestamp());
                doc.put("status",     "sent");
                doc.put("encrypted",  enc);
                if (replyToId != null) {
                    doc.put("replyToId",   replyToId);
                    doc.put("replyToText", replyToText != null ? replyToText : "");
                }

                FirebaseFirestore.getInstance()
                    .collection("conversations").document(convId)
                    .collection("messages").document(msgId)
                    .set(doc)
                    .addOnSuccessListener(v -> {
                        AppDatabase.getInstance(ctx).messageDao().updateStatus(msgId, "sent");
                        ConversationMetaUpdater.update(convId, myUid, partnerUid,
                            text.length() > 80 ? text.substring(0, 80) : text);
                    });

            } catch (Exception ignored) {}
        });
    }
}
