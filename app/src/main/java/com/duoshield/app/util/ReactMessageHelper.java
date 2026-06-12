package com.duoshield.app.util;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

public class ReactMessageHelper {
    public static void react(String convId, String msgId, String emoji, String myUid) {
        Map<String, Object> update = new HashMap<>();
        update.put("reactions." + emoji, FieldValue.arrayUnion(myUid));
        update.put("reaction", emoji);
        FirebaseFirestore.getInstance()
            .collection("conversations").document(convId)
            .collection("messages").document(msgId)
            .update(update);
    }
}
