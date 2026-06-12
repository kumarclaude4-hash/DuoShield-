package com.duoshield.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class UploadHelper {

    public interface UploadCallback {
        void onProgress(int percent);
        void onSuccess(String downloadUrl);
        void onFailure(Exception e);
    }

    public static void uploadImage(Context ctx, Uri uri, String convId, UploadCallback cb) {
        new Thread(() -> {
            try {
                InputStream is = ctx.getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] data = baos.toByteArray();

                String path = "conversations/" + convId + "/images/" + System.currentTimeMillis() + ".jpg";
                StorageReference ref = FirebaseStorage.getInstance().getReference().child(path);
                ref.putBytes(data)
                    .addOnProgressListener(snap -> {
                        int pct = (int)(100.0 * snap.getBytesTransferred() / snap.getTotalByteCount());
                        cb.onProgress(pct);
                    })
                    .addOnSuccessListener(snap -> ref.getDownloadUrl()
                        .addOnSuccessListener(dlUri -> cb.onSuccess(dlUri.toString()))
                        .addOnFailureListener(cb::onFailure))
                    .addOnFailureListener(cb::onFailure);
            } catch (Exception e) { cb.onFailure(e); }
        }).start();
    }

    public static void uploadVoice(Context ctx, String filePath, String convId, UploadCallback cb) {
        String path = "conversations/" + convId + "/voice/" + System.currentTimeMillis() + ".3gp";
        StorageReference ref = FirebaseStorage.getInstance().getReference().child(path);
        ref.putFile(android.net.Uri.fromFile(new java.io.File(filePath)))
            .addOnSuccessListener(snap -> ref.getDownloadUrl()
                .addOnSuccessListener(uri -> cb.onSuccess(uri.toString()))
                .addOnFailureListener(cb::onFailure))
            .addOnFailureListener(cb::onFailure);
    }
}
