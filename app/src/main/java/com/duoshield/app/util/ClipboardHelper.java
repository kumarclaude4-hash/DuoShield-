package com.duoshield.app.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

public class ClipboardHelper {
    private static final long CLEAR_DELAY = 90_000L;

    public static void copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText("message", text);
        cm.setPrimaryClip(clip);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip();
            } else if (cm.hasPrimaryClip()) {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        }, CLEAR_DELAY);
    }
}
