package com.duoshield.app.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.annotation.SuppressLint;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import com.duoshield.app.ChatMediaActivity;
import com.duoshield.app.R;

public class NotificationStyler {

    public static final String CH_MESSAGES = "duoshield_messages";
    public static final String CH_SILENT   = "duoshield_silent";
    private static final int   NOTIF_ID    = 1001;

    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel messages = new NotificationChannel(
            CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription("Incoming DuoShield messages");
        messages.enableVibration(true);
        nm.createNotificationChannel(messages);

        NotificationChannel silent = new NotificationChannel(
            CH_SILENT, "Silent", NotificationManager.IMPORTANCE_LOW);
        silent.setDescription("Background events");
        nm.createNotificationChannel(silent);
    }

    @SuppressLint("MissingPermission")
    public static void showMessage(Context ctx, String title, String body,
                                   String convId, String myUid) {
        try {
            Intent openIntent = new Intent(ctx, ChatMediaActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent openPi = PendingIntent.getActivity(ctx, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent markReadIntent = new Intent(ctx, MarkReadReceiver.class);
            markReadIntent.putExtra(MarkReadReceiver.EXTRA_CONV_ID, convId);
            markReadIntent.putExtra(MarkReadReceiver.EXTRA_MY_UID,  myUid);
            PendingIntent markReadPi = PendingIntent.getBroadcast(ctx, 1, markReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            RemoteInput remoteInput = new RemoteInput.Builder(MessageReplyReceiver.KEY_REPLY_TEXT)
                .setLabel("Reply").build();
            Intent replyIntent = new Intent(ctx, MessageReplyReceiver.class);
            replyIntent.putExtra(MessageReplyReceiver.EXTRA_CONV_ID, convId);
            replyIntent.putExtra(MessageReplyReceiver.EXTRA_MY_UID,  myUid);
            PendingIntent replyPi = PendingIntent.getBroadcast(ctx, 2, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_send, "Reply", replyPi)
                .addRemoteInput(remoteInput).build();

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_secure)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_tick_double_blue, "Mark Read", markReadPi)
                .addAction(replyAction)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body));

            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, builder.build());
        } catch (Exception ignored) {}
    }
}

