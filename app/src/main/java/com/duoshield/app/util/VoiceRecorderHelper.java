package com.duoshield.app.util;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VoiceRecorderHelper {

    private static final String TAG = "VoiceRecorderHelper";

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
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    public void stop() {
        if (handler != null && ampRunnable != null) {
            handler.removeCallbacks(ampRunnable);
        }

        boolean recordingStopped = false;
        if (recorder != null) {
            try {
                recorder.stop();
                recordingStopped = true;
            } catch (Exception e) {
                // IllegalStateException if stop() called before any data was recorded
                Log.w(TAG, "recorder.stop() failed (recording may be too short): " + e.getMessage());
            }
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }

        if (recordingStopped && listener != null) {
            listener.onStopped(outputPath, new ArrayList<>(amplitudes));
        } else if (!recordingStopped && listener != null) {
            listener.onError("Recording was too short or failed to stop.");
        }
    }

    public void cancel() {
        if (handler != null && ampRunnable != null) {
            handler.removeCallbacks(ampRunnable);
        }
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (outputPath != null) new File(outputPath).delete();
    }
}
