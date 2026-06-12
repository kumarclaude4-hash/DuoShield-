package com.duoshield.app.util;

import android.content.Context;
import com.duoshield.app.models.Message;

public class ForwardMessageHelper {

    public static void forward(Context ctx, Message msg, String convId,
                               String myUid, String partnerUid) {
        // Decrypt the stored ciphertext first, then re-encrypt fresh for the target conversation
        String plaintext = "";
        if (msg.getText() != null && !msg.getText().isEmpty()) {
            if (msg.isEncrypted()) {
                plaintext = EncryptionHelper.decrypt(ctx, msg.getText());
            } else {
                plaintext = msg.getText();
            }
        }
        MessageBuilder.sendTextMessage(ctx, convId, myUid, partnerUid,
            "[Forwarded] " + plaintext, null, null);
    }
}
