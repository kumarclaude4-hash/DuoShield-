package com.duoshield.app.util;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import com.duoshield.app.R;
import com.duoshield.app.models.Message;

public class MessageStatusHelper {

    public static void bind(ImageView tick, Message msg, String myUid) {
        if (tick == null) return;
        if (!myUid.equals(msg.getSender())) { tick.setVisibility(View.GONE); return; }
        tick.setVisibility(View.VISIBLE);
        String status = msg.getStatus();
        if ("read".equals(status)) {
            // ✓✓ blue — partner opened the chat and saw the message
            tick.setImageResource(R.drawable.ic_done_all);
            tick.setImageTintList(ColorStateList.valueOf(0xFF2AABB8));
        } else if ("delivered".equals(status) || msg.isDelivered()) {
            // ✓✓ grey — partner device received it
            tick.setImageResource(R.drawable.ic_done_all);
            tick.setImageTintList(ColorStateList.valueOf(0xFF90A4AE));
        } else {
            // ✓ grey — sent to server, partner not yet received ("sent", "pending", null)
            tick.setImageResource(R.drawable.ic_done);
            tick.setImageTintList(ColorStateList.valueOf(0xFF9E9E9E));
        }
    }
}
