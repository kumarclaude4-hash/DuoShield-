package com.duoshield.app.util;

import com.duoshield.app.models.Message;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;
import java.util.List;

public class DeliveryReceiptHelper {

    public static void markDelivered(String convId, List<Message> messages, String myUid) {
        if (messages == null || messages.isEmpty()) return;
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        int count = 0;
        for (Message m : messages) {
            if (!myUid.equals(m.getSender()) && !"delivered".equals(m.getStatus())
                    && !"read".equals(m.getStatus())) {
                batch.update(
                    db.collection("conversations").document(convId)
                      .collection("messages").document(m.getId()),
                    "status", "delivered");
                if (++count == 450) {
                    batch.commit();
                    batch = db.batch();
                    count = 0;
                }
            }
        }
        if (count > 0) batch.commit();
    }
}
