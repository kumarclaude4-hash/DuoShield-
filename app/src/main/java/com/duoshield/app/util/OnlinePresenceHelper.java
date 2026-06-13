package com.duoshield.app.util;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.HashMap;
import java.util.Map;

public class OnlinePresenceHelper {

    public static void setOnline(String convId, String myUid) {
        if (convId == null || myUid == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("online_" + myUid, true);
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .set(data, SetOptions.merge());
    }

    public static void setOffline(String convId, String myUid) {
        if (convId == null || myUid == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("online_"   + myUid, false);
        data.put("lastSeen_" + myUid, FieldValue.serverTimestamp());
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .set(data, SetOptions.merge());
    }
}
