package com.duoshield.app;

import android.app.Application;
import android.util.Log;
import androidx.work.Configuration;
import com.duoshield.app.notifications.NotificationStyler;
import com.duoshield.app.util.AppLockManager;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;

public class DuoShieldApp extends Application implements Configuration.Provider {

    private static final String TAG = "DuoShieldApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // Enable Firestore offline persistence so writes survive brief connectivity
        // gaps and the app does not throw "client is offline" errors on first launch.
        try {
            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                    .build();
            FirebaseFirestore.getInstance().setFirestoreSettings(settings);
        } catch (Exception e) {
            Log.w(TAG, "Firestore persistence setup failed (may already be configured): " + e.getMessage());
        }

        NotificationStyler.createChannels(this);
        AppLockManager.init(this);
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build();
    }
}
