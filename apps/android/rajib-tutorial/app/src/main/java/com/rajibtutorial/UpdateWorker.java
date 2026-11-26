package com.rajibtutorial;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Checks GitHub for a new APK release in the background, even when the app is closed
 * (like the Windows app's tray update check), and posts the "Update available" notification.
 * Survives app close and reboot (WorkManager); re-armed from PushManager.init and BootReceiver.
 */
public class UpdateWorker extends Worker {
    private static final String UNIQUE = "rt_bg_update_check";

    public UpdateWorker(@NonNull Context ctx, @NonNull WorkerParameters p) { super(ctx, p); }

    @NonNull @Override
    public Result doWork() {
        boolean ok = new UpdateChecker(getApplicationContext()).checkAndNotifyBlocking();
        return ok ? Result.success() : Result.retry();
    }

    /** Safe to call any number of times (UPDATE moves old 30-min jobs to the 15-min interval). */
    static void schedule(Context c) {
        try {
            Constraints net = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(UpdateWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(net)
                .build();
            WorkManager.getInstance(c.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req);
        } catch (Throwable ignored) { }
    }
}
