package com.duoshield.app.util;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VoiceRecorderHelper {

    public interface RecorderListener {
        void onAmplitude(int amplitude);
        void onStopped(String filePath, List<Integer> amplitudes);
        void onError(String msg);
    }

    private MediaRecorder recorder;
    private String outputPath;
    private final List<Integer> amplitudes = new ArrayList<>();
    private android.os.Handler handler;
    private Runnable ampRunnable;
    private RecorderListener listener;

    public void start(Context ctx, RecorderListener cb) {
        this.listener = cb;
        try {
            File out = new File(ctx.getCacheDir(), "voice_" + System.currentTimeMillis() + ".3gp");
            outputPath = out.getAbsolutePath();
            amplitudes.clear();

            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(ctx) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(outputPath);
            recorder.prepare();
            recorder.start();

            handler = new android.os.Handler(android.os.Looper.getMainLooper());
            ampRunnable = new Runnable() {
                @Override public void run() {
                    if (recorder == null) return;
                    int amp = recorder.getMaxAmplitude();
                    amplitudes.add(amp);
                    cb.onAmplitude(amp);
                    handler.postDelayed(this, 100);
                }
            };
            handler.post(ampRunnable);
        } catch (Exception e) { cb.onError(e.getMessage()); }
    }

    public void stop() {
        if (handler != null && ampRunnable != null) handler.removeCallbacks(ampRunnable);
        try {
            if (recorder != null) { recorder.stop(); recorder.release(); recorder = null; }
            if (listener != null) listener.onStopped(outputPath, new ArrayList<>(amplitudes));
        } catch (Exception ignored) {}
    }

    public void cancel() {
        if (handler != null && ampRunnable != null) handler.removeCallbacks(ampRunnable);
        try { if (recorder != null) { recorder.stop(); recorder.release(); recorder = null; } } catch (Exception ignored) {}
        if (outputPath != null) new File(outputPath).delete();
    }
}
