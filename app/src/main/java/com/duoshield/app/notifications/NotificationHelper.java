package com.duoshield.app.notifications;

import android.content.Context;

public class NotificationHelper {

    public static final String CHANNEL_ID = NotificationStyler.CH_MESSAGES;

    public static void createChannel(Context ctx) {
        NotificationStyler.createChannels(ctx);
    }

    public static void showNotification(Context ctx, String title, String body) {
        android.content.SharedPreferences prefs =
            ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String convId = prefs.getString("conversation_id", "");
        String myUid  = prefs.getString("my_uid", "");
        NotificationStyler.showMessage(ctx, title, body, convId, myUid);
    }
}
