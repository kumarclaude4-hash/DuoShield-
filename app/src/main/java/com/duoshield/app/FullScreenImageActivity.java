package com.duoshield.app;

import android.graphics.Matrix;
import android.graphics.PointF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import com.bumptech.glide.Glide;

public class FullScreenImageActivity extends BaseActivity {

    public static final String EXTRA_URL = "image_url";

    private ImageView imageView;
    private Matrix    matrix      = new Matrix();
    private Matrix    savedMatrix  = new Matrix();
    private float     scaleFactor = 1f;
    private float     minScale    = 1f;
    private float     maxScale    = 5f;

    private PointF  startPoint = new PointF();
    private PointF  midPoint   = new PointF();
    private static final int NONE  = 0, DRAG = 1, ZOOM = 2;
    private int mode = NONE;

    private ScaleGestureDetector scaleDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen_image);

        imageView = findViewById(R.id.fullScreenImage);
        String url = getIntent().getStringExtra(EXTRA_URL);

        if (url != null) {
            Glide.with(this).load(url).into(imageView);
        }

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                scaleFactor = Math.max(minScale, Math.min(scaleFactor * factor, maxScale));
                matrix.set(savedMatrix);
                matrix.postScale(scaleFactor, scaleFactor,
                    detector.getFocusX(), detector.getFocusY());
                imageView.setImageMatrix(matrix);
                return true;
            }
        });

        imageView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix);
                    startPoint.set(event.getX(), event.getY());
                    mode = DRAG;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    midPoint.set(
                        (event.getX(0) + event.getX(1)) / 2,
                        (event.getY(0) + event.getY(1)) / 2);
                    savedMatrix.set(matrix);
                    mode = ZOOM;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG) {
                        matrix.set(savedMatrix);
                        matrix.postTranslate(
                            event.getX() - startPoint.x,
                            event.getY() - startPoint.y);
                        imageView.setImageMatrix(matrix);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    mode = NONE;
                    break;
            }
            return true;
        });

        imageView.setOnClickListener(v -> finish());
    }
}
