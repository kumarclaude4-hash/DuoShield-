package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

public class FirebaseCostGuard {

    private static final String PREFS       = "duoshield_cost_guard";
    private static final int    MAX_READS   = 40_000;
    private static final int    MAX_WRITES  = 16_000;
    private static final int    MAX_DELETES = 16_000;
    private static final float  WARN_PCT    = 0.80f;

    private final SharedPreferences prefs;
    private final String            todayKey;
    private final Context           appContext;

    public FirebaseCostGuard(Context ctx) {
        appContext = ctx.getApplicationContext();
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        todayKey = String.valueOf(System.currentTimeMillis() / 86_400_000L);
        rolloverIfNeeded();
    }

    private void rolloverIfNeeded() {
        String stored = prefs.getString("day_key", "");
        if (!stored.equals(todayKey)) {
            prefs.edit()
                .putString("day_key", todayKey)
                .putInt("reads", 0).putInt("writes", 0).putInt("deletes", 0)
                .apply();
        }
    }

    public boolean canRead(int n) {
        int current = prefs.getInt("reads", 0);
        warnIfNearing("reads", current, MAX_READS);
        return current + n <= MAX_READS;
    }

    public boolean canWrite(int n) {
        int current = prefs.getInt("writes", 0);
        warnIfNearing("writes", current, MAX_WRITES);
        return current + n <= MAX_WRITES;
    }

    public boolean canDelete(int n) {
        int current = prefs.getInt("deletes", 0);
        warnIfNearing("deletes", current, MAX_DELETES);
        return current + n <= MAX_DELETES;
    }

    public void recordReads(int n)   { add("reads",   n); }
    public void recordWrites(int n)  { add("writes",  n); }
    public void recordDeletes(int n) { add("deletes", n); }

    private void add(String key, int n) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + n).apply();
    }

    private void warnIfNearing(String type, int current, int max) {
        boolean wasBelow = current < (int)(max * WARN_PCT);
        boolean isAtOrAbove = (current) >= (int)(max * WARN_PCT);
        if (wasBelow && isAtOrAbove) {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> Toast.makeText(appContext,
                "Warning: " + type + " quota at 80% for today", Toast.LENGTH_LONG).show());
        }
    }

    public int getReads()   { return prefs.getInt("reads",   0); }
    public int getWrites()  { return prefs.getInt("writes",  0); }
    public int getDeletes() { return prefs.getInt("deletes", 0); }
}
