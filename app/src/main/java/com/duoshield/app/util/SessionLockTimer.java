package com.duoshield.app.util;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.duoshield.app.LockScreenActivity;

public class SessionLockTimer {

    private static final long INACTIVITY_MS = 3 * 60 * 1000L;
    private final Handler  handler  = new Handler(Looper.getMainLooper());
    private final Activity activity;

    public SessionLockTimer(Activity activity) { this.activity = activity; }

    public void reset() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (!activity.isFinishing()) {
                activity.startActivity(new Intent(activity, LockScreenActivity.class));
            }
        }, INACTIVITY_MS);
    }

    public void stop() { handler.removeCallbacksAndMessages(null); }
}
