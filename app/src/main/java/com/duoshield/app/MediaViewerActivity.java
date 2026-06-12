package com.duoshield.app;

import android.os.Bundle;
import android.widget.ImageView;
import com.bumptech.glide.Glide;

public class MediaViewerActivity extends BaseActivity {

    public static final String EXTRA_URL = "media_url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Media");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        ImageView imageView = findViewById(R.id.mediaImageView);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url != null) {
            Glide.with(this).load(url).into(imageView);
        }

        imageView.setOnClickListener(v -> {
            Intent intent = new android.content.Intent(this, FullScreenImageActivity.class);
            intent.putExtra(FullScreenImageActivity.EXTRA_URL, url);
            startActivity(intent);
        });
    }
}
