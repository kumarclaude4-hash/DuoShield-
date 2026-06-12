package com.duoshield.app.util;

import android.os.Handler;
import android.os.Looper;

public class TypingThrottle {
    private static final long DEBOUNCE = 2000;
    private final Handler  handler = new Handler(Looper.getMainLooper());
    private final Runnable stopCb;
    private boolean active = false;

    public interface TypingListener { void onTypingStart(); void onTypingStop(); }

    public TypingThrottle(TypingListener listener) {
        stopCb = () -> { active = false; listener.onTypingStop(); };
    }

    public void onKeyStroke() {
        handler.removeCallbacks(stopCb);
        handler.postDelayed(stopCb, DEBOUNCE);
    }

    public void clear() { handler.removeCallbacks(stopCb); }
}
