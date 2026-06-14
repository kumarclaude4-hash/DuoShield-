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

        // Bug 4 fix: on Android 13+ (TIRAMISU), requestPermissions() is asynchronous.
        // The old code called requestPermissions() and then IMMEDIATELY called route(),
        // so route() ran before the user responded to the permission dialog. This could
        // cause FCM token registration to happen without POST_NOTIFICATIONS permission,
        // silently failing to show notifications on fresh installs.
        //
        // Fix: if the permission is not yet granted, request it and return. route() is
        // called from onRequestPermissionsResult() once the user responds. If permission
        // is already granted (or SDK < 33, where no runtime permission is needed), call
        // proceedAfterPermission() directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
                return; // wait for onRequestPermissionsResult before routing
            }
        }
        proceedAfterPermission();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            // Proceed regardless of grant/deny — DuoShield works without notifications,
            // but FCM-based push will be silent if denied on Android 13+.
            proceedAfterPermission();
        }
    }

    private void proceedAfterPermission() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            route();
        } else {
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
                          startActivity(new Intent(MainActivity.this, PairingActivity.class));
                      }
                  } else {
                      startActivity(new Intent(MainActivity.this, PairingActivity.class));
                  }
                  finish();
              })
              .addOnFailureListener(e -> {
                  startActivity(new Intent(MainActivity.this,
                          isPaired ? ConversationListActivity.class : PairingActivity.class));
                  finish();
              });
        } else {
            startActivity(new Intent(this, PairingActivity.class));
            finish();
        }
    }
}
