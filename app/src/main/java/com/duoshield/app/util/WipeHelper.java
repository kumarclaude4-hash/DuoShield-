package com.duoshield.app.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.SignInActivity;
import java.io.File;
import java.util.concurrent.Executors;

public class WipeHelper {

    public static void wipeAll(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String convId = prefs.getString("conversation_id", null);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (convId != null) {
                    AppDatabase.getInstance(ctx).messageDao().deleteAll(convId);
                }
            } catch (Exception ignored) {}
        });

        prefs.edit().clear().commit(); // synchronous — ensures prefs are cleared before SignInActivity reads them

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
