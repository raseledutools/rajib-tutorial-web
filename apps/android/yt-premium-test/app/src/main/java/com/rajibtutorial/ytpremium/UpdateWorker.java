package com.rajibtutorial.ytpremium;

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

/** Checks GitHub for a new APK every ~15 min, even while the app is closed, and posts the "update available" notification. */
public class UpdateWorker extends Worker {
    public UpdateWorker(@NonNull Context ctx, @NonNull WorkerParameters p) { super(ctx, p); }

    @NonNull @Override
    public Result doWork() {
        return Updater.checkAndNotify(getApplicationContext()) ? Result.success() : Result.retry();
    }

    static void schedule(Context c) {
        try {
            Constraints net = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(UpdateWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(net).build();
            WorkManager.getInstance(c.getApplicationContext())
                .enqueueUniquePeriodicWork("ytp_bg_update_check", ExistingPeriodicWorkPolicy.UPDATE, req);
        } catch (Throwable ignored) { }
    }
}
