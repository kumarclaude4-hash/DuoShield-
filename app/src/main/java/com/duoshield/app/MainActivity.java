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
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        route();
                    } else {
                        Toast.makeText(this,
                            "Connection failed. Check your internet and try again.",
                            Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
        }
    }

    private void route() {
        // 1. Ensure both AES key (AndroidKeyStore) and EC key pair exist
        CryptoInitializer.ensureKeyExists(this);

        // 2. Notification channel
        NotificationHelper.createChannel(this);

        // 3. Refresh FCM token
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    prefs.edit().putString("fcm_token", token).apply();
                    String myUid = prefs.getString("my_uid", null);
                    if (myUid != null) {
                        FirebaseFirestore.getInstance()
                                .collection("users")
                                .document(myUid)
                                .set(Collections.singletonMap("fcmToken", token),
                                     SetOptions.merge());
                    }
                });

        boolean isPaired = prefs.getBoolean("is_paired", false);
        Intent intent = isPaired
            ? new Intent(this, ChatMediaActivity.class)
            : new Intent(this, PairingActivity.class);

        startActivity(intent);
        finish();
    }
}
