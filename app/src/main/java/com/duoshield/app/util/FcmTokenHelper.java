package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.HashMap;
import java.util.Map;

public class FcmTokenHelper {

    public static void register(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String myUid = prefs.getString("my_uid", null);
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            prefs.edit().putString("fcm_token", token).apply();
            if (myUid != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("token",     token);
                data.put("platform",  "android");
                data.put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
                FirebaseFirestore.getInstance()
                    .collection("users").document(myUid)
                    .set(data, SetOptions.merge());
            }
        });
    }

    public static void unregister(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String myUid = prefs.getString("my_uid", null);
        if (myUid == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("token", "");
        FirebaseFirestore.getInstance()
            .collection("users").document(myUid)
            .set(data, SetOptions.merge());
        prefs.edit().remove("fcm_token").apply();
    }
}
