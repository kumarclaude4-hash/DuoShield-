package com.duoshield.app.util;

import android.content.Context;
import com.duoshield.app.models.Message;

public class ForwardMessageHelper {
    public static void forward(Context ctx, Message msg, String convId,
                               String myUid, String partnerUid) {
        String text = msg.getText() != null ? msg.getText() : "";
        MessageBuilder.sendTextMessage(ctx, convId, myUid, partnerUid,
            "[Forwarded] " + text, null, null);
    }
}
