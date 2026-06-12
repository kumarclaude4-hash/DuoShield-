package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;

public class FirebaseCostGuard {

    private static final String PREFS      = "duoshield_cost_guard";
    private static final int    MAX_READS  = 40_000;
    private static final int    MAX_WRITES = 16_000;
    private static final int    MAX_DELETES= 16_000;

    private final SharedPreferences prefs;
    private final String            todayKey;

    public FirebaseCostGuard(Context ctx) {
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
        return prefs.getInt("reads", 0) + n <= MAX_READS;
    }

    public boolean canWrite(int n) {
        return prefs.getInt("writes", 0) + n <= MAX_WRITES;
    }

    public boolean canDelete(int n) {
        return prefs.getInt("deletes", 0) + n <= MAX_DELETES;
    }

    public void recordReads(int n)  { add("reads",   n); }
    public void recordWrites(int n) { add("writes",  n); }
    public void recordDeletes(int n){ add("deletes", n); }

    private void add(String key, int n) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + n).apply();
    }

    public int getReads()   { return prefs.getInt("reads",   0); }
    public int getWrites()  { return prefs.getInt("writes",  0); }
    public int getDeletes() { return prefs.getInt("deletes", 0); }
}
