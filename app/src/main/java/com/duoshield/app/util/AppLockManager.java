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

    public static boolean shouldLock(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("biometric_enabled", false)
                && !PinManager.hasPinSet(ctx)) return false;
        long bgTs = prefs.getLong(KEY_LOCK_TS, 0);
        if (bgTs == 0) return false;
        return (System.currentTimeMillis() - bgTs) > LOCK_TIMEOUT;
    }
}
