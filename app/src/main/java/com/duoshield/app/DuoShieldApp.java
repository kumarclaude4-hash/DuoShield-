package com.duoshield.app;

import android.app.Application;
import androidx.work.Configuration;
import com.duoshield.app.notifications.NotificationStyler;
import com.duoshield.app.util.AppLockManager;

public class DuoShieldApp extends Application implements Configuration.Provider {

    @Override
    public void onCreate() {
        super.onCreate();
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
