package com.duoshield.app.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import com.duoshield.app.util.MessageBuilder;
import com.google.firebase.auth.FirebaseAuth;

public class MessageReplyReceiver extends BroadcastReceiver {

    public static final String KEY_REPLY_TEXT = "reply_text";
    public static final String EXTRA_CONV_ID  = "conv_id";
    public static final String EXTRA_MY_UID   = "my_uid";

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
        if (remoteInput == null) return;

        CharSequence replyText = remoteInput.getCharSequence(KEY_REPLY_TEXT);
        if (replyText == null || replyText.toString().trim().isEmpty()) return;

        String convId = intent.getStringExtra(EXTRA_CONV_ID);
        String myUid  = intent.getStringExtra(EXTRA_MY_UID);
        if (convId == null || myUid == null) return;

        SharedPreferences prefs = context.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String partnerUid = prefs.getString("partner_uid", "");

        MessageBuilder.sendTextMessage(context, convId, myUid, partnerUid,
            replyText.toString().trim(), null, null);

        NotificationManagerCompat.from(context).cancel(MarkReadReceiver.NOTIF_ID);
    }
}
