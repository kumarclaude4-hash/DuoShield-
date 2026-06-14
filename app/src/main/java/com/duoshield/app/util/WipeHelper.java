package com.duoshield.app.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.SignInActivity;
import java.io.File;
import java.util.concurrent.Executors;

public class WipeHelper {

    private static final String TAG = "WipeHelper";

    public static void wipeAll(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String convId = prefs.getString("conversation_id", null);

        // Bug 11 fix: wipe EncryptedSharedPreferences FIRST so crypto keys are
        // destroyed before anything else. A forensic extraction after wipe must
        // not be able to recover the EC private key or the ECDH shared AES key.
        try {
            SecurePrefs.get(ctx).edit().clear().commit();
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear SecurePrefs during wipe", e);
        }

        // Delete the Room database file directly — ensures complete erasure even
        // if the DAO deleteAll() call on the background thread is slow.
        try {
            ctx.deleteDatabase("duoshield_db");
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete Room DB file during wipe", e);
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (convId != null) {
                    AppDatabase.getInstance(ctx).messageDao().deleteAll(convId);
                }
            } catch (Exception ignored) {}
        });

        // Clear plain prefs last (synchronous, ensures SignInActivity reads empty prefs)
        prefs.edit().clear().commit();

        try {
            File cache = ctx.getCacheDir();
            deleteDir(cache);
        } catch (Exception ignored) {}

        Intent i = new Intent(ctx, SignInActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(i);
    }

    private static void deleteDir(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }
}
