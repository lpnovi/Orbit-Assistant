package com.orbit.assistant;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Downloads Orbit Local's model in the background, resumably.
 *
 * <p>Bytes stream into a {@code .part} file that survives interruption; a restart asks the
 * server to continue from the bytes already present rather than starting over. The finished
 * file is checksum-verified by {@link LocalModelStore#validateAndPromote} before it can ever be
 * treated as an installed model, and a user cancellation keeps the partial bytes so the
 * download can resume later. Nothing here runs on the main thread.
 */
public final class LocalModelDownloadWorker extends Worker {
    static final String UNIQUE_WORK = "orbit-local-model-download";
    private static final int BUFFER_BYTES = 128 * 1024;

    public LocalModelDownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Starts or resumes the model download. No-op when it is already running or installed. */
    public static void start(Context c) {
        if (LocalModelStore.isReady(c)) return;
        LocalModelStore.clearError(c);
        if (!LocalModelStore.enoughStorageToDownload(c)) {
            LocalModelStore.setState(c, LocalModelStore.State.ERROR,
                    "Not enough free storage. The model needs about "
                            + LocalModelStore.formatBytes(LocalModelStore.MODEL_SIZE_BYTES
                            + LocalModelStore.STORAGE_MARGIN_BYTES) + " available.");
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(LocalModelDownloadWorker.class)
                .setConstraints(constraints)
                .addTag("orbit-local-model")
                .build();
        WorkManager.getInstance(c.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, work);
        LocalModelStore.setState(c, LocalModelStore.State.DOWNLOADING, "");
    }

    /** Stops a running download. Partial bytes are kept so it can resume. */
    public static void cancel(Context c) {
        try {
            WorkManager.getInstance(c.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK);
        } catch (Throwable ignored) {}
    }

    /** True while the unique download work is enqueued or running. */
    static boolean isRunning(Context c) {
        try {
            List<WorkInfo> infos = WorkManager.getInstance(c.getApplicationContext())
                    .getWorkInfosForUniqueWork(UNIQUE_WORK).get(750, java.util.concurrent.TimeUnit.MILLISECONDS);
            for (WorkInfo info : infos) {
                WorkInfo.State s = info.getState();
                if (s == WorkInfo.State.ENQUEUED || s == WorkInfo.State.RUNNING) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @NonNull @Override public Result doWork() {
        Context c = getApplicationContext();
        if (LocalModelStore.isReady(c)) return Result.success();
        LocalModelStore.setState(c, LocalModelStore.State.DOWNLOADING, "");

        File part = LocalModelStore.partFile(c);
        long existing = part.exists() ? part.length() : 0L;
        if (existing > LocalModelStore.MODEL_SIZE_BYTES) {
            // Bigger than the real file can be: stale bytes from some earlier failure.
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            existing = 0L;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LocalModelStore.MODEL_DOWNLOAD_URL).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");

            int code = conn.getResponseCode();
            boolean resuming = code == 206 && existing > 0;
            if (code == 200 && existing > 0) {
                // The server ignored the range request; start the file over.
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                existing = 0L;
            } else if (!resuming && code != 200) {
                return fail(c, "The model download was refused (HTTP " + code + "). Try again later.");
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, resuming)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                long written = existing;
                long lastStateWrite = 0L;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (isStopped()) {
                        // User cancellation: keep the bytes, resume later.
                        LocalModelStore.setState(c, LocalModelStore.State.PAUSED, "");
                        return Result.success();
                    }
                    out.write(buffer, 0, read);
                    written += read;
                    long now = System.currentTimeMillis();
                    if (now - lastStateWrite > 3000) {
                        lastStateWrite = now;
                        if (LocalModelStore.freeStorageBytes(c) >= 0
                                && LocalModelStore.freeStorageBytes(c) < 100L * 1024 * 1024) {
                            return fail(c, "The device ran low on storage during the download. Free some space and resume.");
                        }
                    }
                    if (written > LocalModelStore.MODEL_SIZE_BYTES) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        return fail(c, "The downloaded data did not match the expected model. Try again.");
                    }
                }
            }
        } catch (Exception e) {
            if (isStopped()) {
                LocalModelStore.setState(c, LocalModelStore.State.PAUSED, "");
                return Result.success();
            }
            // Transient network trouble: one automatic retry, then a visible paused state that
            // the user can resume manually. Partial bytes are always kept.
            if (getRunAttemptCount() < 2) {
                LocalModelStore.setState(c, LocalModelStore.State.DOWNLOADING, "");
                return Result.retry();
            }
            LocalModelStore.setState(c, LocalModelStore.State.PAUSED, "");
            return Result.success();
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (part.length() != LocalModelStore.MODEL_SIZE_BYTES) {
            // The stream ended early without an exception; leave it resumable.
            LocalModelStore.setState(c, LocalModelStore.State.PAUSED, "");
            return Result.success();
        }

        LocalModelStore.setState(c, LocalModelStore.State.VALIDATING, "");
        boolean promoted = LocalModelStore.validateAndPromote(c);
        return promoted ? Result.success() : Result.failure();
    }

    private Result fail(Context c, String message) {
        LocalModelStore.setState(c, LocalModelStore.State.ERROR, message);
        return Result.failure();
    }
}
