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
        releaseRecorder();
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
                    try {
                        int amp = recorder.getMaxAmplitude();
                        amplitudes.add(amp);
                        cb.onAmplitude(amp);
                        handler.postDelayed(this, 100);
                    } catch (RuntimeException e) {
                        String failedPath = outputPath;
                        releaseRecorder();
                        deleteFile(failedPath);
                        cb.onError(errorMessage(e));
                    }
                }
            };
            handler.post(ampRunnable);
        } catch (Exception e) {
            String failedPath = outputPath;
            releaseRecorder();
            deleteFile(failedPath);
            cb.onError(errorMessage(e));
        }
    }

    public void stop() {
        stopAmplitudeUpdates();
        String stoppedPath = outputPath;
        releaseRecorder();
        if (listener != null && stoppedPath != null) {
            listener.onStopped(stoppedPath, new ArrayList<>(amplitudes));
        }
    }

    public void cancel() {
        stopAmplitudeUpdates();
        String pathToDelete = outputPath;
        releaseRecorder();
        deleteFile(pathToDelete);
    }

    private void deleteFile(String path) {
        if (path != null) new File(path).delete();
    }

    private String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? "Recorder unavailable." : message;
    }

    private void stopAmplitudeUpdates() {
        if (handler != null && ampRunnable != null) handler.removeCallbacks(ampRunnable);
        handler = null;
        ampRunnable = null;
    }

    private void releaseRecorder() {
        stopAmplitudeUpdates();
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        outputPath = null;
    }
}
