package com.duoshield.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Canvas-based amplitude visualiser used for both live recording
 * and static playback waveforms.
 *
 * Recording mode: call {@link #addAmplitude(int)} on each tick (0–32767).
 * Playback mode:  call {@link #setAmplitudes(List)} then {@link #setProgress(float)}
 *                 (0.0–1.0) to shade the played portion in the accent colour.
 */
public class WaveformView extends View {

    private static final int   MAX_BARS      = 60;
    private static final int   BAR_GAP_DP    = 2;
    private static final int   MIN_BAR_H_DP  = 4;
    private static final int   COLOR_PLAYED  = 0xFF2AABB8;   // teal accent
    private static final int   COLOR_UNPLAYED = 0xFF555555;  // grey

    private final Paint  paintPlayed   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint  paintUnplayed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Float> bars     = new ArrayList<>();   // normalised 0–1

    private float progress = 0f;  // 0–1, used in playback mode
    private float density;

    public WaveformView(Context ctx) { this(ctx, null); }
    public WaveformView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        paintPlayed.setColor(COLOR_PLAYED);
        paintUnplayed.setColor(COLOR_UNPLAYED);
    }

    /** Recording: push one raw amplitude sample (0–32767). */
    public void addAmplitude(int raw) {
        float norm = Math.min(1f, raw / 20000f);
        if (bars.size() >= MAX_BARS) bars.remove(0);
        bars.add(norm);
        invalidate();
    }

    /** Playback: provide the full normalised bar list at once. */
    public void setAmplitudes(List<Integer> rawList) {
        bars.clear();
        if (rawList == null || rawList.isEmpty()) {
            invalidate();
            return;
        }
        int maxAmp = 1;
        for (int v : rawList) if (v > maxAmp) maxAmp = v;
        for (int v : rawList) bars.add((float) v / maxAmp);
        invalidate();
    }

    /** Set playback progress fraction (0–1). Triggers redraw. */
    public void setProgress(float fraction) {
        progress = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    /** Reset to blank state. */
    public void clear() {
        bars.clear();
        progress = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float gap      = BAR_GAP_DP * density;
        float minBarH  = MIN_BAR_H_DP * density;
        float barCount = bars.isEmpty() ? MAX_BARS : bars.size();
        float barW     = (w - gap * (barCount - 1)) / barCount;
        if (barW < 1f) barW = 1f;

        int playedUpTo = (int) (progress * bars.size());

        for (int i = 0; i < bars.size(); i++) {
            float norm    = bars.get(i);
            float barH    = Math.max(minBarH, norm * h);
            float top     = (h - barH) / 2f;
            float left    = i * (barW + gap);
            float right   = left + barW;
            float bottom  = top + barH;

            Paint paint = (i < playedUpTo) ? paintPlayed : paintUnplayed;
            canvas.drawRoundRect(left, top, right, bottom, barW / 2f, barW / 2f, paint);
        }

        // If no data yet, draw a flat centre line
        if (bars.isEmpty()) {
            canvas.drawRect(0, h / 2f - density, w, h / 2f + density, paintUnplayed);
        }
    }
}
