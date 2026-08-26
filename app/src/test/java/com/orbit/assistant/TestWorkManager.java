package com.orbit.assistant;

import android.content.Context;

import androidx.work.Configuration;
import androidx.work.ListenableWorker;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WorkManager, ready for a Robolectric test that opens an Activity which schedules work.
 *
 * <p>Several Orbit screens schedule background work in {@code onCreate}, and WorkManager throws
 * unless it has been initialized for the test application. Whether it already has depends on which
 * test class ran first in the JVM, so this is written to be safe to call unconditionally: it
 * returns immediately if an instance already exists, and otherwise installs a minimal one.
 *
 * <p>Only background <em>execution</em> is stubbed. Everything under test still enqueues through
 * the real code path.
 */
final class TestWorkManager {
    private TestWorkManager() {}

    static void ensureInitialized(Context context) {
        try {
            WorkManager.getInstance(context);
            return;
        } catch (IllegalStateException notInitialized) {
            // Falls through to initialize it for this test application.
        }
        // Off the main thread: WorkManager keeps its queue in a database, and Room refuses main
        // thread access. Nothing under test waits on it.
        ExecutorService background = Executors.newSingleThreadExecutor();
        Configuration configuration = new Configuration.Builder()
                .setExecutor(background)
                .setTaskExecutor(background)
                .setWorkerFactory(new WorkerFactory() {
                    @Override public ListenableWorker createWorker(
                            Context appContext, String workerClassName, WorkerParameters params) {
                        return new Worker(appContext, params) {
                            @Override public Result doWork() {
                                return Result.success();
                            }
                        };
                    }
                })
                .build();
        WorkManager.initialize(context, configuration);
    }
}
