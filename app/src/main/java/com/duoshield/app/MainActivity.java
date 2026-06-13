package com.duoshield.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.notifications.NotificationHelper;
import com.duoshield.app.ui.PairingActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.Collections;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_POST_NOTIFICATIONS = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(0xFFFFFFFF);
        ProgressBar spinner = new ProgressBar(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        splash.addView(spinner, params);
        setContentView(splash);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
            }
        }

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            route();
        } else {
            // Not signed in → go to sign-in screen
            startActivity(new Intent(this, SignInActivity.class));
            finish();
        }
    }

    private void route() {
        // 1. Ensure crypto keys exist
        CryptoInitializer.ensureKeyExists(this);

        // 2. Notification channels
        NotificationHelper.createChannel(this);

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid != null) {
            prefs.edit().putString("my_uid", myUid).apply();
        }

        // 3. Refresh FCM token + upload EC public key so partners can always find it
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    prefs.edit().putString("fcm_token", token).apply();
                    if (myUid != null) {
                        java.util.Map<String, Object> updates = new java.util.HashMap<>();
                        updates.put("fcmToken", token);
                        // Always publish the EC public key so pairing partners can find it
                        String ecPub = CryptoInitializer.getMyPublicKeyB64(this);
                        if (ecPub != null) updates.put("ecPublicKey", ecPub);
                        db.collection("users").document(myUid).set(updates, SetOptions.merge());
                    }
                });

        // 4. Route: if paired locally, go straight to chat; otherwise restore from Firestore
        boolean isPaired = prefs.getBoolean("is_paired", false);
        String convId = prefs.getString("conversation_id", null);

        if (isPaired && convId != null) {
            startActivity(new Intent(this, ConversationListActivity.class));
            finish();
            return;
        }

        // Try to restore pairing from Firestore (handles re-login on same device)
        if (myUid != null) {
            db.collection("chats")
              .whereArrayContains("participants", myUid)
              .limit(1)
              .get()
              .addOnSuccessListener(chats -> {
                  if (!chats.isEmpty()) {
                      com.google.firebase.firestore.DocumentSnapshot chatDoc =
                              chats.getDocuments().get(0);
                      String chatId = chatDoc.getId();
                      @SuppressWarnings("unchecked")
                      java.util.List<String> parts =
                              (java.util.List<String>) chatDoc.get("participants");
                      String partnerUid = null;
                      if (parts != null) {
                          for (String p : parts) {
                              if (!p.equals(myUid)) { partnerUid = p; break; }
                          }
                      }
                      if (partnerUid != null) {
                          prefs.edit()
                              .putBoolean("is_paired", true)
                              .putString("conversation_id", chatId)
                              .putString("partner_uid", partnerUid)
                              .apply();
                          startActivity(new Intent(MainActivity.this, ConversationListActivity.class));
                      } else {
                          startActivity(new Intent(MainActivity.this,
                                  com.duoshield.app.ui.PairingActivity.class));
                      }
                  } else {
                      startActivity(new Intent(MainActivity.this,
                              com.duoshield.app.ui.PairingActivity.class));
                  }
                  finish();
              })
              .addOnFailureListener(e -> {
                  startActivity(new Intent(MainActivity.this,
                          isPaired ? ConversationListActivity.class
                                   : com.duoshield.app.ui.PairingActivity.class));
                  finish();
              });
        } else {
            startActivity(new Intent(this, com.duoshield.app.ui.PairingActivity.class));
            finish();
        }
    }
}
