package com.rajibtutorial;

import android.app.PendingIntent;
import android.content.Intent;
import android.media.RingtoneManager;

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

    /** Discussion message: skip own messages and when the chat is open; tap opens the Discussion. */
    private void showChat(RemoteMessage m) {
        String from = m.getData().get("from");
        String me = PushManager.discussionMe(this);
        if (me.isEmpty() || me.equals(from)) return;          // not subscribed, or my own message
        if (DiscussionScreen.isOpen()) return;                // already looking at the chat
        String title = m.getData().get("title");
        String body = m.getData().get("body");
        if (title == null || title.isEmpty()) title = "Discussion";
        if (body == null) body = "";
        PushManager.createChannel(this);
        String mid = m.getData().get("id");
        int nid = (mid != null && !mid.isEmpty()) ? (mid.hashCode() & 0x7FFFFFFF) : (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        Intent open = new Intent(this, NativeHomeActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NativeHomeActivity.EXTRA_OPEN, "discussion");
        PendingIntent pi = PendingIntent.getActivity(this, nid, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, PushManager.CHAT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setColor(0xFFE8365D)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setSubText("Discussion")
            .setGroup("rt_discussion")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(this).notify(nid, b.build());
        } catch (SecurityException ignored) { }
    }

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
        if ("chat".equals(m.getData().get("kind"))) { showChat(m); return; }
        if (title == null) title = m.getData().get("title");
        if (body == null) body = m.getData().get("body");
        if (title == null || title.isEmpty()) title = "Rajib Tutorial";
        if (body == null) body = "";

        PushManager.createChannel(this);
        String mid = m.getData().get("id");
        int nid = (mid != null && !mid.isEmpty())
            ? (mid.hashCode() & 0x7FFFFFFF)
            : (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        long when = System.currentTimeMillis();
        try {
            String t = m.getData().get("time");
            if (t != null && !t.isEmpty()) when = Long.parseLong(t);
        } catch (NumberFormatException ignored) { }

        // Tap -> overlay page with the full message
        Intent open = new Intent(this, NotificationDetailActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NotificationDetailActivity.EXTRA_TITLE, title)
            .putExtra(NotificationDetailActivity.EXTRA_BODY, body)
            .putExtra(NotificationDetailActivity.EXTRA_TIME, when);
        PendingIntent pi = PendingIntent.getActivity(this, nid, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, PushManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setColor(0xFFE8365D)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(when)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            // API < 26: sound/vibration come from here; API 26+: from the channel (rt_push_v3)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(PushManager.VIBE)
            .setOnlyAlertOnce(true)                        // same message id arriving twice alerts once
            .setAutoCancel(true)
            .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(this).notify(nid, b.build());
        } catch (SecurityException ignored) { }   // notification permission denied
    }
}
