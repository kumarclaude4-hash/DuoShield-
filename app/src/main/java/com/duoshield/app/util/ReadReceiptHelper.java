package com.duoshield.app.util;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

public class ReadReceiptHelper {

    public static void markAllRead(String convId, String myUid) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("conversations").document(convId)
          .collection("messages")
          .whereNotEqualTo("status", "read")
          .whereNotEqualTo("sender", myUid)
          .get()
          .addOnSuccessListener(snap -> {
              if (snap == null || snap.isEmpty()) return;
              WriteBatch batch = db.batch();
              int count = 0;
              for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                  batch.update(doc.getReference(), "status", "read");
                  if (++count == 450) { batch.commit(); batch = db.batch(); count = 0; }
              }
              if (count > 0) batch.commit();
          });
        db.collection("conversations").document(convId)
          .update("unread_" + myUid, 0L);
    }

    public static void markMessageRead(String convId, String messageId) {
        FirebaseFirestore.getInstance()
            .collection("conversations").document(convId)
            .collection("messages").document(messageId)
            .update("status", "read");
    }
}
