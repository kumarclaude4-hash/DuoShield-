package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;

public class AppLockManager {

    private static final String PREFS        = "duoshield_prefs";
    private static final String KEY_LOCK_TS  = "app_lock_bg_ts";
    private static final long   LOCK_TIMEOUT = 3 * 60 * 1000L; // 3 minutes

    public static void init(Context ctx) {}

    public static void onAppBackgrounded(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putLong(KEY_LOCK_TS, System.currentTimeMillis()).apply();
    }

    public static void onAppForegrounded(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putLong(KEY_LOCK_TS, 0).apply();
    }

    /**
     * Bug 9 fix: shouldLock() now only returns true when a PIN is actually set.
     *
     * Previously, biometric_enabled alone could trigger shouldLock(), which would
     * launch LockScreenActivity — but LockScreenActivity immediately finishes() if
     * no PIN is set (hasPinSet() == false), creating a flash-and-dismiss loop.
     * Worse, if biometric fails and no PIN is set, the user has no fallback.
     *
     * Biometric is an authentication METHOD used inside LockScreenActivity, not an
     * independent lock trigger. A PIN is always required as the root credential;
     * biometric just provides a faster path to unlock.
     */
    public static boolean shouldLock(Context ctx) {
        // Require PIN — biometric is auth method, not lock trigger (Bug 9 fix)
        if (!PinManager.hasPinSet(ctx)) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long bgTs = prefs.getLong(KEY_LOCK_TS, 0);
        if (bgTs == 0) return false;
        return (System.currentTimeMillis() - bgTs) > LOCK_TIMEOUT;
    }
}
