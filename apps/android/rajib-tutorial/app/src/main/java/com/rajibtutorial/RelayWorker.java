package com.rajibtutorial;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Delivers a relay alert (chat message + push) to Phone A. Sent at once; a WorkManager job with a network
 * requirement retries it if that first attempt fails or the app is killed before it finishes.
 */
public class RelayWorker extends Worker {
    private static final long GIVE_UP_MS = 24 * 60 * 60 * 1000L;

    public RelayWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    /** kind: "simcall" (ring Phone A), "simmissed", or "dm" (plain message). */
    static void send(Context c, String kind, String callId, String me, String to, String number, String text) {
        final Context app = c.getApplicationContext();
        long ts = System.currentTimeMillis();
        String key = me.compareTo(to) < 0 ? me + "-" + to : to + "-" + me;
        String id = "_tm_" + key + "_" + String.format(Locale.US, "%013d", ts) + "_"
            + Integer.toString(new Random().nextInt(1679616) + 1679616, 36).substring(1);
        final Data data = new Data.Builder()
            .putString("kind", kind).putString("call", callId).putString("me", me).putString("to", to)
            .putString("number", number).putString("text", text).putString("id", id).putLong("ts", ts).build();
        try {
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(RelayWorker.class)
                .setInputData(data)
                .setInitialDelay(20, TimeUnit.SECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
            WorkManager.getInstance(app).enqueue(req);
        } catch (Throwable ignored) { }
        new Thread(() -> {
            try { deliver(app, data); } catch (Exception ignored) { }
        }, "relay-send").start();
    }

    /** The push (which rings Phone A) and the chat message are sent independently; each is sent only once. */
    private static synchronized void deliver(Context c, Data in) throws Exception {
        android.content.SharedPreferences done = c.getSharedPreferences("rt_relay_sent", Context.MODE_PRIVATE);
        String id = in.getString("id");
        if (id == null) return;
        String kind = in.getString("kind"), call = in.getString("call"), me = in.getString("me"), to = in.getString("to");
        String number = in.getString("number"), text = in.getString("text");
        long ts = in.getLong("ts", 0);
        Exception failed = null;
        if (!done.contains(id + "p")) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("kind", kind);
            p.put("sender", me);
            p.put("senderName", "📲 Phone " + me);
            p.put("to", to);
            p.put("body", text);
            p.put("id", id);
            p.put("call", call);
            p.put("number", number);
            p.put("time", String.valueOf(ts));
            p.put("sig", RelayAuth.sign(RelayAuth.myCode(c), "alert", kind, me, to, call, number, String.valueOf(ts)));
            try {
                // a ring is useless after a minute; messages may wait for A to come online
                new AdminBridge(c, null).sendRelayPush(to, p, "simcall".equals(kind) ? "60s" : "86400s");
                done.edit().putBoolean(id + "p", true).apply();
                RelayCall.log(c, "Push sent to " + to + " ✅");
            } catch (Exception e) {
                failed = e;
                RelayCall.log(c, "Push failed ❌ " + e.getMessage());
            }
        }
        if (!done.contains(id + "d")) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("appId", DiscussionScreen.SYNC_APP_ID);
            d.put("from", me);
            d.put("fromName", me);
            d.put("to", to);
            d.put("toName", to);
            d.put("type", "text");
            d.put("text", text);
            d.put("url", "");
            d.put("fileName", "");
            d.put("ts", Long.valueOf(ts));
            d.put("up", Long.valueOf(System.currentTimeMillis()));
            try {
                Fs.set(id, d);
                done.edit().putBoolean(id + "d", true).apply();
            } catch (Exception e) {
                failed = e;
                RelayCall.log(c, "Chat message failed ❌ " + Fs.explain(e));
            }
        }
        if (done.getAll().size() > 200) done.edit().clear().apply();
        if (failed != null) throw failed;
    }

    @NonNull
    @Override
    public Result doWork() {
        if (System.currentTimeMillis() - getInputData().getLong("ts", 0) > GIVE_UP_MS) return Result.failure();
        try {
            deliver(getApplicationContext(), getInputData());
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
