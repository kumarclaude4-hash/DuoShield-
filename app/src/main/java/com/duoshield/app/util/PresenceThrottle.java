package com.duoshield.app.util;

import android.os.Handler;
import android.os.Looper;
import com.google.firebase.firestore.FirebaseFirestore;

public class PresenceThrottle {

    private static final long DEBOUNCE_MS = 2000;
    private final Handler  handler = new Handler(Looper.getMainLooper());
    private final String   convId;
    private final String   myUid;
    private boolean        currentValue = false;

    public PresenceThrottle(String convId, String myUid) {
        this.convId = convId;
        this.myUid  = myUid;
    }

    public void setTyping(boolean typing) {
        if (typing == currentValue) return;
        currentValue = typing;
        handler.removeCallbacksAndMessages(null);
        if (typing) {
            write(true);
            handler.postDelayed(() -> { currentValue = false; write(false); }, 3000);
        } else {
            write(false);
        }
    }

    private void write(boolean value) {
        if (convId == null) return;
        FirebaseFirestore.getInstance()
            .collection("conversations").document(convId)
            .update("typing_" + myUid, value);
    }

    public void clear() {
        handler.removeCallbacksAndMessages(null);
        write(false);
    }
}
