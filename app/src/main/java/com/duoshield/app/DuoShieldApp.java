package com.duoshield.app;

import android.app.Application;
import android.util.Log;
import androidx.work.Configuration;
import com.duoshield.app.notifications.NotificationStyler;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.StorageCleanupWorker;
import com.duoshield.app.util.TempFileCleaner;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;
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

        // Delete decrypted temp media files (voice_*.3gp, vid_*.mp4) older than 5 min.
        // Runs every 15 minutes in the background; KEEP policy avoids re-queuing on each launch.
        TempFileCleaner.schedule(this);

        // Trim the app cache to 50 MB once per day (Bug 16 — was never scheduled).
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "StorageCleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            new PeriodicWorkRequest.Builder(StorageCleanupWorker.class, 1, TimeUnit.DAYS).build());
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build();
    }
}
