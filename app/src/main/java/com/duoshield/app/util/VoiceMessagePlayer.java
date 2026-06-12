package com.duoshield.app.util;

import android.media.MediaPlayer;

public class VoiceMessagePlayer {

    public interface PlayerListener {
        void onStart(int durationMs);
        void onProgress(int posMs);
        void onComplete();
        void onError(String msg);
    }

    private MediaPlayer player;
    private android.os.Handler handler;
    private Runnable progressRunnable;
    private PlayerListener listener;

    public void play(String url, PlayerListener cb) {
        this.listener = cb;
        release();
        try {
            player = new MediaPlayer();
            player.setDataSource(url);
            player.prepareAsync();
            player.setOnPreparedListener(mp -> {
                mp.start();
                cb.onStart(mp.getDuration());
                handler = new android.os.Handler(android.os.Looper.getMainLooper());
                progressRunnable = new Runnable() {
                    @Override public void run() {
                        if (player != null && player.isPlaying()) {
                            cb.onProgress(player.getCurrentPosition());
                            handler.postDelayed(this, 200);
                        }
                    }
                };
                handler.post(progressRunnable);
            });
            player.setOnCompletionListener(mp -> { cb.onComplete(); release(); });
            player.setOnErrorListener((mp, what, extra) -> { cb.onError("Playback error"); release(); return true; });
        } catch (Exception e) { cb.onError(e.getMessage()); }
    }

    public void pause()  { if (player != null && player.isPlaying()) player.pause(); }
    public void resume() { if (player != null && !player.isPlaying()) player.start(); }

    public void release() {
        if (handler != null && progressRunnable != null) handler.removeCallbacks(progressRunnable);
        try { if (player != null) { player.stop(); player.release(); player = null; } } catch (Exception ignored) {}
    }
}
