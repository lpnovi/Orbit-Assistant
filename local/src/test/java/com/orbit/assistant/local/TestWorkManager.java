package com.orbit.assistant.local;

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
 * WorkManager, ready for a Robolectric test that exercises the download's lifecycle.
 *
 * <p>The component's Pause and Resume are WorkManager operations, so the tests that cover them run
 * against a real WorkManager rather than a stand-in for one. Whether it has already been
 * initialised depends on which test class ran first in the JVM, so this is safe to call
 * unconditionally.
 *
 * <p>Only background <em>execution</em> is stubbed: no test here downloads 1.6 GB. Everything under
 * test still enqueues, cancels, and is queried through the real code path.
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

    /**
     * A queue with nothing of Orbit Local's left in it.
     *
     * <p>Robolectric shares one WorkManager singleton across test classes in a JVM, so a class that
     * enqueues a download leaves it there for whatever runs next — and a state machine asserted
     * against "no live work" would then pass or fail on class ordering. Every test class that reads
     * a download state starts from here instead, and cancellation is waited on rather than fired and
     * hoped for.
     */
    static void resetQueue(Context context) {
        ensureInitialized(context);
        for (ComponentModelSpec.Slot slot : ComponentModelSpec.Slot.values()) {
            try {
                WorkManager.getInstance(context)
                        .cancelUniqueWork(ComponentModelSpec.forSlot(slot).workName())
                        .getResult().get();
            } catch (Exception ignored) {
                // Nothing to cancel, or a queue that will not answer. Either way there is no live
                // work this test could be affected by.
            }
        }
    }
}
