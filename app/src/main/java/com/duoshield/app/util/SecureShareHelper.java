package com.duoshield.app.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;

public class SecureShareHelper {

    public static void shareImage(Context ctx, String imageUrl) {
        new Thread(() -> {
            File out = new File(ctx.getCacheDir(), "share_" + System.currentTimeMillis() + ".jpg");
            // Use try-with-resources so both streams are always closed, even on exception
            try (InputStream in = new URL(imageUrl).openStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            } catch (Exception ignored) {
                return;
            }
            try {
                Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".provider", out);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/jpeg");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(intent, "Share Image")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
        }).start();
    }
}
