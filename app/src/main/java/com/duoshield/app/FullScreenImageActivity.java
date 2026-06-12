package com.duoshield.app;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.ImageButton;
import android.widget.Toast;
import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.Executors;

public class FullScreenImageActivity extends BaseActivity {

    public static final String EXTRA_URL = "image_url";

    private PhotoView photoView;
    private String imageUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen_image);

        // PhotoView handles pinch-zoom natively — no custom matrix/touch listener needed (#19)
        photoView = findViewById(R.id.photo_view);
        imageUrl  = getIntent().getStringExtra(EXTRA_URL);
        if (imageUrl != null && photoView != null) {
            Glide.with(this).load(imageUrl).into(photoView);
        }

        ImageButton btnClose = findViewById(R.id.btn_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());

        // Wire Save button (#19)
        ImageButton btnSave = findViewById(R.id.btn_save);
        if (btnSave != null) btnSave.setOnClickListener(v -> saveImageToGallery());

        // Wire Share button (#19)
        ImageButton btnShare = findViewById(R.id.btn_share);
        if (btnShare != null) btnShare.setOnClickListener(v -> shareImage());
    }

    private void saveImageToGallery() {
        if (imageUrl == null) return;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                InputStream in = new URL(imageUrl).openStream();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "duoshield_" + System.currentTimeMillis() + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DuoShield");
                }
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                    }
                }
                in.close();
                runOnUiThread(() -> Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void shareImage() {
        if (imageUrl == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, imageUrl);
        startActivity(Intent.createChooser(share, "Share image"));
    }
}
