package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class PinManager {

    private static final String PREFS   = "duoshield_prefs";
    private static final String KEY_PIN = "app_pin_hash";

    public static void setPin(Context ctx, String pin) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_PIN, sha256(pin)).apply();
    }

    public static boolean hasPinSet(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getString(KEY_PIN, null) != null;
    }

    public static boolean verifyPin(Context ctx, String entered) {
        String stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                           .getString(KEY_PIN, null);
        if (stored == null) return false;
        return stored.equals(sha256(entered));
    }

    public static void clearPin(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().remove(KEY_PIN).apply();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
