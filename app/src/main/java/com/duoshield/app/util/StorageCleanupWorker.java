package com.duoshield.app.util;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.File;

public class StorageCleanupWorker extends Worker {

    private static final long MAX_CACHE_BYTES = 50L * 1024 * 1024; // 50 MB

    public StorageCleanupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override
    public Result doWork() {
        try {
            File cache = getApplicationContext().getCacheDir();
            long size = dirSize(cache);
            if (size > MAX_CACHE_BYTES) trimDir(cache, size - MAX_CACHE_BYTES);
            return Result.success();
        } catch (Exception e) { return Result.retry(); }
    }

    private long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            size += f.isDirectory() ? dirSize(f) : f.length();
        }
        return size;
    }

    private void trimDir(File dir, long toFree) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long freed = 0;
        for (File f : files) {
            if (freed >= toFree) break;
            freed += f.length();
            f.delete();
        }
    }
}
