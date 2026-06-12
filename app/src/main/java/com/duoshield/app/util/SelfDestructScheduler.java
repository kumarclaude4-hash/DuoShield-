package com.duoshield.app.util;

import android.content.Context;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.duoshield.app.db.SelfDestructWorker;
import java.util.concurrent.TimeUnit;

public class SelfDestructScheduler {

    private static final String WORK_TAG = "self_destruct_work";

    public static void schedule(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
            SelfDestructWorker.class, 15, TimeUnit.MINUTES)
            .addTag(WORK_TAG).build();
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(WORK_TAG);
    }
}
