package com.rajibtutorial;

import android.app.PendingIntent;
import android.content.Context;
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
        String from = m.getData().get("sender");
        if (from == null) from = m.getData().get("from");
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

    // ── Telephone (1:1 messenger) ───────────────────────────
    private static long ageMs(RemoteMessage m) {
        try {
            String t = m.getData().get("time");
            if (t != null && !t.isEmpty()) return System.currentTimeMillis() - Long.parseLong(t);
        } catch (NumberFormatException ignored) { }
        return 0;
    }

    /** Chat message for me: tap opens that conversation. Skipped when Telephone is on screen (it polls by itself). */
    private void showDm(RemoteMessage m) {
        String from = nz(m.getData().get("sender"));
        String me = PushManager.dmMe(this);
        String to = nz(m.getData().get("to"));
        if (me.isEmpty() || from.isEmpty() || from.equals(me) || (!to.isEmpty() && !to.equals(me))) return;
        if (MessengerActivity.ON_SCREEN) MessengerActivity.poke();                 // open: show it right now
        if (MessengerActivity.ON_SCREEN && (MessengerActivity.CUR_PEER.isEmpty() || MessengerActivity.CUR_PEER.equals(from))) return;
        String name = nz(m.getData().get("senderName"));
        if (name.isEmpty()) name = from;
        String body = nz(m.getData().get("body"));
        PushManager.createChannel(this);
        int nid = 0x4D000000 | (from.hashCode() & 0xFFFFFF);
        Intent open = new Intent(this, MessengerActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MessengerActivity.EXTRA_PEER, from);
        PendingIntent pi = PendingIntent.getActivity(this, nid, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, PushManager.DM_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setColor(0xFF00A884)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setSubText("Telephone")
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi);
        try { NotificationManagerCompat.from(this).notify(nid, b.build()); } catch (SecurityException ignored) { }
    }

    /** Incoming call: full-screen intent → IncomingCallActivity over the lock screen (like WhatsApp). */
    private void showCall(RemoteMessage m) {
        String from = nz(m.getData().get("sender"));
        String me = PushManager.dmMe(this);
        String to = nz(m.getData().get("to"));
        String room = nz(m.getData().get("room"));
        if (me.isEmpty() || from.isEmpty() || from.equals(me) || room.isEmpty() || (!to.isEmpty() && !to.equals(me))) return;
        if (ageMs(m) > 40000) return;                                            // stale: the caller already gave up
        if (MessengerActivity.ON_SCREEN) { MessengerActivity.poke(); return; }    // Telephone is open: it rings in-app
        String name = nz(m.getData().get("senderName"));
        if (name.isEmpty()) name = from;
        boolean video = "1".equals(m.getData().get("video"));
        String doc = nz(m.getData().get("id"));
        PushManager.createChannel(this);
        int nid = 0x43000000 | (doc.hashCode() & 0xFFFFFF);

        Intent full = callIntent(this, doc, from, name, room, video, me, nid, null);
        PendingIntent fullPi = PendingIntent.getActivity(this, nid, full,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent accPi = PendingIntent.getActivity(this, nid + 1,
            callIntent(this, doc, from, name, room, video, me, nid, IncomingCallActivity.ACT_ACCEPT),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent decPi = PendingIntent.getActivity(this, nid + 2,
            callIntent(this, doc, from, name, room, video, me, nid, IncomingCallActivity.ACT_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, PushManager.CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setColor(0xFF00A884)
            .setContentTitle(name)
            .setContentText(video ? "Incoming video call" : "Incoming voice call")
            .setSubText("Telephone")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(40000)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            .setVibrate(new long[]{0, 800, 600, 800, 600, 800})
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(new NotificationCompat.Action.Builder(0, "Decline", decPi).setAuthenticationRequired(false).build())
            .addAction(new NotificationCompat.Action.Builder(0, "Accept", accPi).setAuthenticationRequired(false).build());
        try { NotificationManagerCompat.from(this).notify(nid, b.build()); } catch (SecurityException ignored) { }
        // Also start the screen directly when the system allows it (app was in the foreground state)
        try { startActivity(full); } catch (Throwable ignored) { }
    }

    /** Call relay: a SIM call is ringing (or was missed) on my paired Phone B. */
    private void showSimCall(RemoteMessage m, boolean ringing) {
        String from = nz(m.getData().get("sender"));
        String me = PushManager.dmMe(this);
        String to = nz(m.getData().get("to"));
        String call = nz(m.getData().get("call"));
        String number = nz(m.getData().get("number"));
        String time = nz(m.getData().get("time"));
        if (me.isEmpty() || from.isEmpty() || !to.equals(me) || call.isEmpty()) return;
        String code = RelayAuth.pairedCode(this, from);
        boolean trusted = RelayAuth.verify(code, m.getData().get("sig"),
            "alert", nz(m.getData().get("kind")), from, to, call, number, time);
        if (!trusted) {                                  // not paired (or wrong code): no call buttons
            final String why = code.isEmpty()
                ? "Telephone: pair relay phone " + from + " to get the call screen"
                : "Telephone: pairing code for " + from + " does not match";
            final android.content.Context app = getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(app, why, android.widget.Toast.LENGTH_LONG).show());
            showDm(m);
            return;
        }
        PushManager.createChannel(this);
        int nid = 0x53000000 | (call.hashCode() & 0xFFFFFF);
        if (!ringing) {
            NotificationManagerCompat.from(this).cancel(nid);
            showDm(m);
            return;
        }
        if (ageMs(m) > 5 * 60 * 1000L) return;
        Intent full = SimCallActivity.intent(this, from, call, number, nid, null);
        PendingIntent fullPi = PendingIntent.getActivity(this, nid, full,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent accPi = PendingIntent.getActivity(this, nid + 1,
            SimCallActivity.intent(this, from, call, number, nid, SimCallActivity.ACT_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cutPi = PendingIntent.getActivity(this, nid + 2,
            SimCallActivity.intent(this, from, call, number, nid, SimCallActivity.ACT_CUT),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, PushManager.CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setColor(0xFF00A884)
            .setContentTitle(number.isEmpty() ? "Unknown number" : number)
            .setContentText("SIM call on Phone " + from)
            .setSubText("Telephone")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setTimeoutAfter(60000)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            .setVibrate(new long[]{0, 800, 600, 800, 600, 800})
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(new NotificationCompat.Action.Builder(0, "Cut", cutPi).setAuthenticationRequired(false).build())
            .addAction(new NotificationCompat.Action.Builder(0, "Receive", accPi).setAuthenticationRequired(false).build());
        try { NotificationManagerCompat.from(this).notify(nid, b.build()); } catch (SecurityException ignored) { }
        try { startActivity(full); } catch (Throwable ignored) { }
    }

    private static Intent callIntent(Context c, String doc, String from, String name, String room,
                                     boolean video, String me, int nid, String action) {
        Intent i = new Intent(c, IncomingCallActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(IncomingCallActivity.EXTRA_DOC, doc)
            .putExtra(IncomingCallActivity.EXTRA_FROM, from)
            .putExtra(IncomingCallActivity.EXTRA_NAME, name)
            .putExtra(IncomingCallActivity.EXTRA_ROOM, room)
            .putExtra(IncomingCallActivity.EXTRA_VIDEO, video)
            .putExtra(IncomingCallActivity.EXTRA_TO, me)
            .putExtra(IncomingCallActivity.EXTRA_NID, nid);
        if (action != null) i.putExtra(IncomingCallActivity.EXTRA_ACTION, action);
        return i;
    }

    /** "Missed voice call" notification (call rang out without being answered). */
    static void notifyMissed(Context c, String from, String name, boolean video) {
        try {
            PushManager.createChannel(c);
            int nid = 0x4D000000 | (from.hashCode() & 0xFFFFFF);
            Intent open = new Intent(c, MessengerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MessengerActivity.EXTRA_PEER, from);
            PendingIntent pi = PendingIntent.getActivity(c, nid, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, PushManager.DM_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_push)
                .setColor(0xFFF15C6D)
                .setContentTitle(name)
                .setContentText(video ? "Missed video call" : "Missed voice call")
                .setSubText("Telephone")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setAutoCancel(true)
                .setContentIntent(pi);
            NotificationManagerCompat.from(c).notify(nid, b.build());
        } catch (Throwable ignored) { }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    @Override
    public void onNewToken(String token) {
        PushManager.onToken(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage m) {
        // New APK release pushed by CI -> show the "update available" notification (works with the app closed)
        if ("app_update".equals(m.getData().get("type"))) {
            UpdateChecker uc = new UpdateChecker(this);
            if (uc.notifyForTag(m.getData().get("tag"))) return;      // tag is in the push: no network needed
            for (int i = 0; i < 3; i++) {                 // GitHub may need a moment to serve the new release
                if (uc.checkAndNotifyBlocking()) break;
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
            return;
        }
        String title = null, body = null;
        if (m.getNotification() != null) {
            title = m.getNotification().getTitle();
            body = m.getNotification().getBody();
        }
        String kind = m.getData().get("kind");
        if ("tsig".equals(kind)) {                      // live signal: typing, read tick, reaction, delete…
            String sfrom = nz(m.getData().get("sender")), sto = nz(m.getData().get("to"));
            if (ageMs(m) <= 20000 && !sto.isEmpty() && sto.equals(PushManager.dmMe(this)) && MessengerActivity.ON_SCREEN) {
                MessengerActivity.onSignal(sfrom, nz(m.getData().get("body")));
            }
            return;
        }
        if ("dm".equals(kind)) { showDm(m); return; }
        if ("dmcall".equals(kind)) { showCall(m); return; }
        if ("relaycmd".equals(kind)) { RelayCall.onPushCommand(this, m.getData()); return; }
        if ("simcall".equals(kind) || "simmissed".equals(kind)) { showSimCall(m, "simcall".equals(kind)); return; }
        if ("chat".equals(kind)) { showChat(m); return; }
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

        PushManager.inboxAdd(this, mid, title, body, when);
        if (!AppSettings.notifications(this)) return;      // Settings → Notifications off: message stays in the inbox only

        // Detail page (full message)
        Intent detail = new Intent(this, NotificationDetailActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NotificationDetailActivity.EXTRA_TITLE, title)
            .putExtra(NotificationDetailActivity.EXTRA_BODY, body)
            .putExtra(NotificationDetailActivity.EXTRA_TIME, when);
        PendingIntent detailPi = PendingIntent.getActivity(this, nid, detail,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Message contains a web link -> one tap on the notification opens that link directly
        final String url = Links.firstUrl(body, title);
        PendingIntent pi = detailPi;
        if (url != null) {
            pi = PendingIntent.getActivity(this, nid ^ 0x2A5F, Links.viewIntent(url),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

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
        if (!AppSettings.notifySound(this)) b.setSilent(true);
        if (url != null) b.addAction(0, "View message", detailPi);
        try {
            NotificationManagerCompat.from(this).notify(nid, b.build());
        } catch (SecurityException ignored) { }   // notification permission denied
    }
}
