package com.rajibtutorial;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Receives admin messages. When the app is in the background / swiped away, Android shows the
 * "notification" part of the FCM message by itself. This service covers the foreground case
 * and data-only messages.
 */
public class PushService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        PushManager.onToken(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage m) {
        String title = null, body = null;
        if (m.getNotification() != null) {
            title = m.getNotification().getTitle();
            body = m.getNotification().getBody();
        }
        if (title == null) title = m.getData().get("title");
        if (body == null) body = m.getData().get("body");
        if (title == null || title.isEmpty()) title = "Rajib Tutorial";
        if (body == null) body = "";

        PushManager.createChannel(this);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, PushManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setColor(0xFFE8365D)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(this).notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), b.build());
        } catch (SecurityException ignored) { }   // notification permission denied
    }
}
