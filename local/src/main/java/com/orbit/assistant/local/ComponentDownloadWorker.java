package com.orbit.assistant.local;

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
 * Downloads the model into the component, resumably, in the background.
 *
 * <p>Runs inside the component rather than inside Orbit, so the download does not depend on
 * Orbit's management screen — or Orbit's process — staying alive. Orbit watches progress by
 * asking the service, and renders it on the Orbit Local screen exactly as before.
 *
 * <p>Bytes stream into a {@code .part} file that survives interruption, and a restart asks the
 * server to continue from what is already present. The finished file is checksum-verified before
 * it can be treated as installed.
 */
public final class ComponentDownloadWorker extends Worker {
    static final String UNIQUE_WORK = "orbit-local-component-model-download";
    private static final int BUFFER_BYTES = 128 * 1024;

    public ComponentDownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Starts or resumes the download. No-op when already running or installed. */
    public static void start(Context c) {
        if (ComponentModelStore.isReady(c)) return;
        if (ModelImporter.isRunning(c)) return;
        ComponentModelStore.clearError(c);
        if (!ComponentModelStore.enoughStorageToDownload(c)) {
            ComponentModelStore.setState(c, ComponentModelStore.State.ERROR,
                    "Not enough free storage for the model. Free some space and try again.");
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ComponentDownloadWorker.class)
                .setConstraints(constraints)
                .addTag("orbit-local-model")
                .build();
        WorkManager.getInstance(c.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, work);
        ComponentModelStore.setState(c, ComponentModelStore.State.DOWNLOADING, "");
    }

    /** Stops a running download. Partial bytes are kept so it can resume. */
    public static void cancel(Context c) {
        try {
            WorkManager.getInstance(c.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK);
        } catch (Throwable ignored) {}
    }

    /** Stops a download and discards its partial bytes. */
    public static void cancelAndDiscard(Context c) {
        cancel(c);
        //noinspection ResultOfMethodCallIgnored
        ComponentModelStore.partFile(c).delete();
        ComponentModelStore.setState(c, ComponentModelStore.State.NOT_INSTALLED, "");
    }

    static boolean isRunning(Context c) {
        try {
            List<WorkInfo> infos = WorkManager.getInstance(c.getApplicationContext())
                    .getWorkInfosForUniqueWork(UNIQUE_WORK)
                    .get(750, java.util.concurrent.TimeUnit.MILLISECONDS);
            for (WorkInfo info : infos) {
                WorkInfo.State s = info.getState();
                if (s == WorkInfo.State.ENQUEUED || s == WorkInfo.State.RUNNING) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @NonNull @Override public Result doWork() {
        Context c = getApplicationContext();
        if (ComponentModelStore.isReady(c)) return Result.success();
        ComponentModelStore.setState(c, ComponentModelStore.State.DOWNLOADING, "");

        File part = ComponentModelStore.partFile(c);
        long existing = part.exists() ? part.length() : 0L;
        if (existing > ComponentModelStore.MODEL_SIZE_BYTES) {
            // Bigger than the real file can be: stale bytes from an earlier failure.
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            existing = 0L;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ComponentModelStore.MODEL_DOWNLOAD_URL).openConnection();
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
                long lastCheck = 0L;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (isStopped()) {
                        ComponentModelStore.setState(c, ComponentModelStore.State.PAUSED, "");
                        return Result.success();
                    }
                    out.write(buffer, 0, read);
                    written += read;
                    long now = System.currentTimeMillis();
                    if (now - lastCheck > 3000) {
                        lastCheck = now;
                        long free = ComponentModelStore.freeStorageBytes(c);
                        if (free >= 0 && free < 100L * 1024 * 1024) {
                            return fail(c, "The device ran low on storage during the download. Free some space and resume.");
                        }
                    }
                    if (written > ComponentModelStore.MODEL_SIZE_BYTES) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        return fail(c, "The downloaded data did not match the expected model. Try again.");
                    }
                }
            }
        } catch (Exception e) {
            if (isStopped()) {
                ComponentModelStore.setState(c, ComponentModelStore.State.PAUSED, "");
                return Result.success();
            }
            if (getRunAttemptCount() < 2) {
                ComponentModelStore.setState(c, ComponentModelStore.State.DOWNLOADING, "");
                return Result.retry();
            }
            ComponentModelStore.setState(c, ComponentModelStore.State.PAUSED, "");
            return Result.success();
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (part.length() != ComponentModelStore.MODEL_SIZE_BYTES) {
            // The stream ended early without an exception; leave it resumable.
            ComponentModelStore.setState(c, ComponentModelStore.State.PAUSED, "");
            return Result.success();
        }

        ComponentModelStore.setState(c, ComponentModelStore.State.VALIDATING, "");
        return ComponentModelStore.validateAndPromote(c, part) ? Result.success() : Result.failure();
    }

    private Result fail(Context c, String message) {
        ComponentModelStore.setState(c, ComponentModelStore.State.ERROR, message);
        return Result.failure();
    }
}
