package com.duoshield.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.VideoView;

public class MediaViewerActivity extends BaseActivity {

    public static final String EXTRA_URL = "media_url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        ImageButton btnClose = findViewById(R.id.btn_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());

        ProgressBar progress = findViewById(R.id.progress);
        VideoView   videoView = findViewById(R.id.video_view);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url != null && videoView != null) {
            videoView.setVideoURI(Uri.parse(url));
            videoView.setOnPreparedListener(mp -> {
                if (progress != null) progress.setVisibility(View.GONE);
                mp.start();
            });
            videoView.setOnErrorListener((mp, what, extra) -> {
                if (progress != null) progress.setVisibility(View.GONE);
                return false;
            });
            videoView.start();
        } else {
            if (progress != null) progress.setVisibility(View.GONE);
        }
    }

    @Override protected void onPause()  { super.onPause(); }
    @Override protected void onResume() { super.onResume(); }
}
