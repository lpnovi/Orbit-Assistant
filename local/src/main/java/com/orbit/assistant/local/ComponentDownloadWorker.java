package com.orbit.assistant.local;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Downloads a model into the component, resumably, in the background.
 *
 * <p>Runs inside the component rather than inside Orbit, so the download does not depend on
 * Orbit's management screen — or Orbit's process — staying alive. Orbit watches progress by
 * asking the service, and renders it on the Orbit Local screen.
 *
 * <p>Bytes stream into a {@code .part} file that survives interruption, and a restart asks the
 * server to continue from what is already present. The finished file is checksum-verified before
 * it can be treated as installed.
 *
 * <p>Since v0.7.8.0 Beta 1 the worker carries a {@link ComponentModelSpec.Slot} in its input data
 * and enqueues under a unique name per slot, so the chat model and the action model download
 * independently: one can be paused while the other runs, and neither can pick up the other's
 * partial bytes. The lock below is still process-wide, which keeps two large downloads from
 * competing for the same disk on a phone.
 *
 * <h2>Two device failures this design exists for</h2>
 *
 * <p><b>Pause then Resume did nothing.</b> Enqueuing with {@link ExistingWorkPolicy#KEEP} against a
 * unique work name looks safe, but Pause cancelled that work asynchronously. A Resume arriving
 * before the cancellation settled found work WorkManager still considered live, kept it, and
 * enqueued nothing — then the cancellation landed and stopped the very download the user had just
 * asked for. Starting now {@link ExistingWorkPolicy#REPLACE}s, which WorkManager orders internally
 * against the pending cancel, and {@link #decideStart} makes a redundant tap a no-op instead of a
 * second competing download.
 *
 * <p><b>Every failure was reported as "Paused".</b> An exhausted retry, a truncated stream, and a
 * dead network all wrote {@code PAUSED}, which claims a person made a decision. Each now records
 * what actually happened through {@link ComponentModelStore#markStopped}, and only
 * {@link #pause} sets the pause request that {@code PAUSED} depends on.
 */
public final class ComponentDownloadWorker extends Worker {
    static final String UNIQUE_WORK = "orbit-local-component-model-download";
    static final String TAG_MODEL = "orbit-local-model";
    /** Which model this worker is downloading. Absent means the chat model, as it always was. */
    static final String INPUT_SLOT = "slot";
    private static final int BUFFER_BYTES = 128 * 1024;
    /** Attempts one enqueue gets before the download is reported as interrupted. */
    static final int MAX_ATTEMPTS = 3;
    /** How long a WorkManager query may take before its answer counts as unknown. */
    private static final long WORK_QUERY_TIMEOUT_MS = 1500L;

    /**
     * How a start enqueues.
     *
     * <p>REPLACE rather than KEEP, deliberately: a cancelled-but-not-yet-settled worker must never
     * be able to swallow a Resume the user asked for. WorkManager serialises the replacement
     * against the outstanding cancellation, which is exactly the guarantee KEEP could not give.
     */
    static final ExistingWorkPolicy START_POLICY = ExistingWorkPolicy.REPLACE;

    /**
     * One download loop at a time inside this process, per model.
     *
     * <p>REPLACE can briefly overlap an outgoing worker with its replacement, and two streams
     * appending to the same {@code .part} file would corrupt it. The replacement waits for the
     * outgoing one to notice {@link #isStopped()} and let go, rather than writing over it. The lock
     * is per slot, because a chat download and an action download touch different files and have no
     * reason to block each other.
     */
    private static final Map<ComponentModelSpec.Slot, ReentrantLock> DOWNLOAD_LOCKS =
            new EnumMap<>(ComponentModelSpec.Slot.class);
    private static final long LOCK_WAIT_SECONDS = 20L;

    private static synchronized ReentrantLock lockFor(ComponentModelSpec spec) {
        ReentrantLock lock = DOWNLOAD_LOCKS.get(spec.slot);
        if (lock == null) {
            lock = new ReentrantLock();
            DOWNLOAD_LOCKS.put(spec.slot, lock);
        }
        return lock;
    }

    /** What WorkManager was able to tell us — including that it could not tell us anything. */
    public enum WorkState {
        RUNNING,
        /** Enqueued and able to run: waiting its turn, not waiting on the user. */
        ENQUEUED,
        /** Enqueued, but the device has no usable connection yet. */
        WAITING_FOR_NETWORK,
        /** Nothing live. Proven, not assumed. */
        NONE,
        /**
         * The query timed out, threw, or returned nothing usable.
         *
         * <p>Deliberately its own answer rather than being folded into {@link #NONE}. Treating
         * "I could not find out" as "it is not running" is what let a Binder timeout present
         * itself to the user as a deliberate pause.
         */
        UNKNOWN
    }

    public ComponentDownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** The model this worker instance was enqueued for. */
    private ComponentModelSpec spec() {
        return ComponentModelSpec.forSlotName(getInputData().getString(INPUT_SLOT));
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    /** What a start request should do, on plain values. */
    enum StartDecision { IGNORED_READY, IGNORED_IMPORTING, NO_STORAGE, ALREADY_RUNNING, ENQUEUE }

    /**
     * The start decision itself, separated so every branch can be stated in a test.
     *
     * <p>A start on a download that is genuinely running is not a second download: it is a
     * redundant tap, and the only thing it needs to do is withdraw any standing pause request.
     */
    static StartDecision decideStart(boolean ready, boolean importing, boolean enoughStorage,
                                     WorkState work, boolean pauseRequested) {
        if (ready) return StartDecision.IGNORED_READY;
        if (importing) return StartDecision.IGNORED_IMPORTING;
        if (!enoughStorage) return StartDecision.NO_STORAGE;
        if (work == WorkState.RUNNING && !pauseRequested) return StartDecision.ALREADY_RUNNING;
        return StartDecision.ENQUEUE;
    }

    /** Starts or resumes the chat model download. */
    public static void start(Context c) { start(c, ComponentModelSpec.CHAT); }

    /** Starts or resumes one model's download. Safe to call repeatedly; never starts a second one. */
    public static void start(Context c, ComponentModelSpec spec) {
        Context app = c.getApplicationContext();
        ComponentModelStore.clearError(app, spec);
        StartDecision decision = decideStart(
                ComponentModelStore.isReady(app, spec),
                spec.slot == ComponentModelSpec.Slot.CHAT && ModelImporter.isRunning(app),
                ComponentModelStore.enoughStorageToDownload(app, spec),
                snapshot(app, spec),
                ComponentModelStore.pauseRequested(app, spec));
        if (decision == StartDecision.IGNORED_READY || decision == StartDecision.IGNORED_IMPORTING) {
            return;
        }

        // From here on the user has asked for this download to continue, so the pause request is
        // withdrawn whatever happens next. Leaving it standing through a refused start would let a
        // later resume come back as "Paused" for a pause nobody was still asking for — and it is
        // withdrawn before any worker exists, so a worker that starts at once cannot read a stale
        // request and stop itself on arrival.
        ComponentModelStore.clearPauseRequest(app, spec);

        if (decision == StartDecision.NO_STORAGE) {
            ComponentModelStore.recordFailure(app, spec, ComponentModelStore.FAILURE_STORAGE);
            ComponentModelStore.setState(app, spec, ComponentModelStore.State.ERROR,
                    "Not enough free storage for the model. Free some space and try again.");
            return;
        }
        if (decision == StartDecision.ALREADY_RUNNING) {
            // Already writing bytes. Withdrawing the pause request was the whole job.
            ComponentModelStore.setState(app, spec, ComponentModelStore.State.DOWNLOADING, "");
            return;
        }
        if (!enqueue(app, spec)) {
            ComponentModelStore.setState(app, spec, ComponentModelStore.State.ERROR,
                    "Orbit Local could not start the download on this device. Try again.");
            return;
        }
        ComponentModelStore.setState(app, spec, ComponentModelStore.State.QUEUED, "");
    }

    /** Enqueues the unique download, or reports that it could not be scheduled at all. */
    private static boolean enqueue(Context app, ComponentModelSpec spec) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ComponentDownloadWorker.class)
                    .setConstraints(constraints)
                    .setInputData(new Data.Builder().putString(INPUT_SLOT, spec.slot.name()).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 15L, TimeUnit.SECONDS)
                    .addTag(TAG_MODEL)
                    .build();
            WorkManager.getInstance(app).enqueueUniqueWork(spec.workName(), START_POLICY, work);
            return true;
        } catch (Throwable t) {
            // A WorkManager that will not schedule is a failure the user can act on, not an
            // exception thrown back across the service boundary at Orbit.
            ComponentModelStore.recordFailure(app, spec, ComponentModelStore.FAILURE_INSTALL);
            return false;
        }
    }

    /**
     * The user asked to stop. Partial bytes are kept so it can resume.
     *
     * <p>The pause request is recorded before the cancellation is dispatched, so the state is
     * already honest by the time anything else looks at it — and so the outgoing worker, whenever
     * it notices, reports a pause rather than an interruption.
     */
    public static void pause(Context c) { pause(c, ComponentModelSpec.CHAT); }

    public static void pause(Context c, ComponentModelSpec spec) {
        Context app = c.getApplicationContext();
        ComponentModelStore.requestPause(app, spec);
        cancel(app, spec);
        ComponentModelStore.setState(app, spec, ComponentModelStore.State.PAUSED, "");
    }

    /** Stops a running download without recording a pause. Partial bytes are kept. */
    public static void cancel(Context c) { cancel(c, ComponentModelSpec.CHAT); }

    public static void cancel(Context c, ComponentModelSpec spec) {
        try {
            WorkManager.getInstance(c.getApplicationContext()).cancelUniqueWork(spec.workName());
        } catch (Throwable ignored) {}
    }

    /** Stops a download and discards its partial bytes. */
    public static void cancelAndDiscard(Context c) { cancelAndDiscard(c, ComponentModelSpec.CHAT); }

    public static void cancelAndDiscard(Context c, ComponentModelSpec spec) {
        Context app = c.getApplicationContext();
        cancel(app, spec);
        //noinspection ResultOfMethodCallIgnored
        ComponentModelStore.partFile(app, spec).delete();
        ComponentModelStore.clearPauseRequest(app, spec);
        ComponentModelStore.recordFailure(app, spec, ComponentModelStore.FAILURE_NONE);
        ComponentModelStore.setState(app, spec, ComponentModelStore.State.NOT_INSTALLED, "");
    }

    // ---- what WorkManager knows -------------------------------------------------------------------

    /** What is live for the chat model's unique download. */
    static WorkState snapshot(Context c) { return snapshot(c, ComponentModelSpec.CHAT); }

    /**
     * What is live for one model's unique download, or {@link WorkState#UNKNOWN} when that cannot
     * be established. Never invents an answer it did not get.
     */
    static WorkState snapshot(Context c, ComponentModelSpec spec) {
        List<WorkInfo> infos;
        try {
            infos = WorkManager.getInstance(c.getApplicationContext())
                    .getWorkInfosForUniqueWork(spec.workName())
                    .get(WORK_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // A timeout, an interruption, a WorkManager that is not initialised in this process
            // yet, or a query that threw. All of them mean the same thing: we do not know.
            return WorkState.UNKNOWN;
        }
        if (infos == null) return WorkState.UNKNOWN;
        List<WorkInfo.State> states = new java.util.ArrayList<>(infos.size());
        for (WorkInfo info : infos) if (info != null) states.add(info.getState());
        return classify(states, hasUsableNetwork(c));
    }

    /**
     * The classification itself, on plain WorkManager states.
     *
     * <p>Takes the states rather than the {@link WorkInfo} objects so the whole mapping can be
     * stated in a test without constructing WorkManager's own result types.
     */
    static WorkState classify(List<WorkInfo.State> states, boolean networkAvailable) {
        if (states == null) return WorkState.UNKNOWN;
        boolean waiting = false;
        for (WorkInfo.State state : states) {
            if (state == WorkInfo.State.RUNNING) return WorkState.RUNNING;
            if (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED) waiting = true;
        }
        if (!waiting) return WorkState.NONE;
        return networkAvailable ? WorkState.ENQUEUED : WorkState.WAITING_FOR_NETWORK;
    }

    /**
     * Whether the device has a connection the download could use.
     *
     * <p>An unreadable answer counts as connected, so a platform that will not tell us can never
     * cause Orbit to blame the user's Wi-Fi for a download that is merely queued.
     */
    static boolean hasUsableNetwork(Context c) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return true;
            NetworkCapabilities capabilities =
                    manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (capabilities == null) return false;
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- the download itself ------------------------------------------------------------------------

    @NonNull @Override public Result doWork() {
        Context c = getApplicationContext();
        ComponentModelSpec spec = spec();
        // The file, not the reconciled state: asking for the state from inside the worker would
        // mean this worker querying WorkManager about itself, for an answer it already knows.
        if (ComponentModelStore.modelFileComplete(c, spec)) return Result.success();
        if (ComponentModelStore.pauseRequested(c, spec)) {
            // A worker that outlived the pause that cancelled it, or one that raced a REPLACE.
            ComponentModelStore.setState(c, spec, ComponentModelStore.State.PAUSED, "");
            return Result.success();
        }

        ReentrantLock lock = lockFor(spec);
        boolean held;
        try {
            held = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
        if (!held) {
            // The outgoing worker is still letting go of the .part file. Come back rather than
            // write into it alongside them.
            return Result.retry();
        }
        try {
            return download(c, spec);
        } finally {
            lock.unlock();
        }
    }

    private Result download(Context c, ComponentModelSpec spec) {
        ComponentModelStore.setState(c, spec, ComponentModelStore.State.DOWNLOADING, "");

        File part = ComponentModelStore.partFile(c, spec);
        long existing = part.exists() ? part.length() : 0L;
        if (existing > spec.sizeBytes) {
            // Bigger than the real file can be: stale bytes from an earlier failure.
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            existing = 0L;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(spec.downloadUrl).openConnection();
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
                return fail(c, spec, ComponentModelStore.FAILURE_HTTP,
                        "The model download was refused (HTTP " + code + "). Try again later.");
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, resuming)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                long written = existing;
                long lastCheck = 0L;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (isStopped()) return stopped(c, spec);
                    out.write(buffer, 0, read);
                    written += read;
                    long now = System.currentTimeMillis();
                    if (now - lastCheck > 3000) {
                        lastCheck = now;
                        long free = ComponentModelStore.freeStorageBytes(c);
                        if (free >= 0 && free < 100L * 1024 * 1024) {
                            return fail(c, spec, ComponentModelStore.FAILURE_STORAGE,
                                    "The device ran low on storage during the download. Free some space and resume.");
                        }
                    }
                    if (written > spec.sizeBytes) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        return fail(c, spec, ComponentModelStore.FAILURE_SIZE_MISMATCH,
                                "The downloaded data did not match the expected model. Try again.");
                    }
                }
            }
        } catch (Exception e) {
            if (isStopped()) return stopped(c, spec);
            if (getRunAttemptCount() + 1 < MAX_ATTEMPTS) {
                // Still worth another attempt. This is a wait, not a decision anyone made, so it
                // is described as one: WorkManager re-enqueues, and the state reads QUEUED or
                // "waiting for network" depending on why it could not reach the server.
                ComponentModelStore.recordFailure(c, spec, ComponentModelStore.FAILURE_NETWORK);
                ComponentModelStore.setState(c, spec, ComponentModelStore.State.QUEUED, "");
                return Result.retry();
            }
            ComponentModelStore.markStopped(c, spec, ComponentModelStore.FAILURE_NETWORK);
            return Result.failure();
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (part.length() != spec.sizeBytes) {
            // The stream ended early without an exception. Resumable, and nobody paused it.
            ComponentModelStore.markStopped(c, spec, ComponentModelStore.FAILURE_STREAM_ENDED_EARLY);
            return Result.failure();
        }

        ComponentModelStore.setState(c, spec, ComponentModelStore.State.VALIDATING, "");
        return ComponentModelStore.validateAndPromote(c, spec, part)
                ? Result.success() : Result.failure();
    }

    /** Stopped from outside: a pause, a replacement, or the system reclaiming the worker. */
    private Result stopped(Context c, ComponentModelSpec spec) {
        ComponentModelStore.markStopped(c, spec, ComponentModelStore.FAILURE_STOPPED);
        return Result.success();
    }

    private Result fail(Context c, ComponentModelSpec spec, String category, String message) {
        ComponentModelStore.recordFailure(c, spec, category);
        ComponentModelStore.setState(c, spec, ComponentModelStore.State.ERROR, message);
        return Result.failure();
    }
}
