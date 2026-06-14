package com.duoshield.app.notifications;

import android.content.SharedPreferences;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Collections;

public class DuoShieldMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        // Save token locally and push to Firestore so the partner can address this device
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();

        // Bug 3b fix: use FirebaseAuth directly — SharedPrefs "my_uid" may not be written
        // yet on a fresh install when the FCM token first arrives.
        String myUid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if (myUid == null) myUid = prefs.getString("my_uid", null);
        if (myUid != null) {
            FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(myUid)
                    .set(Collections.singletonMap("fcmToken", token), SetOptions.merge());
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        // Respect the user's notification preference
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        boolean notificationsEnabled = prefs.getBoolean("notifications_enabled", true);
        if (!notificationsEnabled) return;

        String title = "DuoShield";
        String body  = "New message";

        // Prefer the notification payload; fall back to data payload
        if (remoteMessage.getNotification() != null) {
            String n = remoteMessage.getNotification().getTitle();
            String b = remoteMessage.getNotification().getBody();
            if (n != null && !n.isEmpty()) title = n;
            if (b != null && !b.isEmpty()) body  = b;
        } else {
            String d = remoteMessage.getData().get("title");
            String b = remoteMessage.getData().get("body");
            if (d != null && !d.isEmpty()) title = d;
            if (b != null && !b.isEmpty()) body  = b;
        }

        NotificationHelper.showNotification(this, title, body);
    }
}
