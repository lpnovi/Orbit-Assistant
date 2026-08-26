package com.orbit.assistant.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The component owns the model, because the component is what runs it.
 *
 * <p>This is the architectural point of the whole module: the package that performs inference is
 * the package that holds the file, so removing Orbit Local removes both together and neither can
 * outlive the other. Orbit itself never touches these bytes — it asks about them across the
 * service interface.
 *
 * <p>The rules are the ones Orbit Local has always had: a pinned size and SHA-256, partial bytes
 * kept so a download resumes rather than restarts, and promotion to READY only after verification,
 * so an interrupted or corrupted download can never masquerade as an installed model.
 *
 * <h2>What the states mean, since v0.7.7.6</h2>
 *
 * <p>A device recording showed Orbit reporting <em>Paused</em> while the {@code .part} file was
 * still growing. The cause was in the reconciliation below: it treated "could not prove WorkManager
 * is running" as "the user paused this". A Binder timeout, a WorkManager query failure, a process
 * transition, or a plain race all collapsed into the one word that says a person made a decision.
 *
 * <p>So {@link State#PAUSED} is now backed by {@link #KEY_PAUSE_REQUESTED} and nothing else: it
 * appears when, and only when, someone asked for it. Everything that used to borrow it has its own
 * honest state — {@link State#QUEUED}, {@link State#WAITING_FOR_NETWORK}, {@link
 * State#INTERRUPTED} — and genuine uncertainty keeps the last known state rather than inventing a
 * decision nobody made.
 */
public final class ComponentModelStore {
    private static final String FILE = "orbit_local_component";
    private static final String KEY_STATE = "model_state";
    private static final String KEY_ERROR = "model_error";
    /** The one thing that makes {@link State#PAUSED} true. Set by a person, never by a failure. */
    private static final String KEY_PAUSE_REQUESTED = "model_pause_requested";
    /** A short non-sensitive token naming why the last attempt stopped. Diagnostics only. */
    private static final String KEY_LAST_FAILURE = "model_last_failure";

    /** Model id, file name, source, and the values every byte is checked against. */
    public static final String MODEL_ID = "qwen2.5-1.5b-instruct-q8";
    public static final String MODEL_DISPLAY_NAME = "Qwen 2.5 (1.5B)";
    public static final String MODEL_FILE_NAME = MODEL_ID + ".task";
    public static final String MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/"
                    + "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task";
    public static final long MODEL_SIZE_BYTES = 1_598_556_720L;
    public static final String MODEL_SHA256 =
            "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3";
    /** Context window the packaged model was exported with. */
    public static final int MODEL_MAX_TOKENS = 4096;
    /** Extra free space required beyond the model itself before a download may start. */
    public static final long STORAGE_MARGIN_BYTES = 500L * 1024 * 1024;

    // ---- why an attempt stopped, in tokens safe to show in Diagnostics -------------------------

    public static final String FAILURE_NONE = "";
    public static final String FAILURE_NETWORK = "network";
    public static final String FAILURE_HTTP = "http";
    public static final String FAILURE_STREAM_ENDED_EARLY = "stream-ended-early";
    public static final String FAILURE_STOPPED = "stopped";
    public static final String FAILURE_STORAGE = "storage";
    public static final String FAILURE_SIZE_MISMATCH = "size-mismatch";
    public static final String FAILURE_CHECKSUM = "checksum";
    public static final String FAILURE_INSTALL = "install";

    public enum State {
        NOT_INSTALLED,
        /** Work exists and is waiting its turn. Nobody paused anything. */
        QUEUED,
        /** Work exists and is waiting for the device to come back online. */
        WAITING_FOR_NETWORK,
        DOWNLOADING,
        /**
         * Stopped before finishing for a reason nobody chose — a dropped connection, a killed
         * process, a truncated stream. Resumable, and never described to the user as a pause.
         */
        INTERRUPTED,
        /** The user asked for this. The only state that carries that meaning. */
        PAUSED,
        /** Fully present, checksum verification in progress. */
        VALIDATING,
        /** An existing Orbit model is being copied in through a file descriptor. */
        IMPORTING,
        READY,
        ERROR
    }

    private ComponentModelStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static File modelDir(Context c) {
        File dir = new File(c.getFilesDir(), "local-models");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    public static File modelFile(Context c) {
        return new File(modelDir(c), MODEL_FILE_NAME);
    }

    public static File partFile(Context c) {
        return new File(modelDir(c), MODEL_FILE_NAME + ".part");
    }

    /** Where an import is assembled before it has earned the right to be the model. */
    public static File importFile(Context c) {
        return new File(modelDir(c), MODEL_FILE_NAME + ".import");
    }

    // ---- the explicit pause request ---------------------------------------------------------------

    /** True only after someone actually asked for a pause, and until they ask to resume. */
    public static boolean pauseRequested(Context c) {
        return prefs(c).getBoolean(KEY_PAUSE_REQUESTED, false);
    }

    static void requestPause(Context c) {
        prefs(c).edit().putBoolean(KEY_PAUSE_REQUESTED, true).apply();
    }

    static void clearPauseRequest(Context c) {
        prefs(c).edit().putBoolean(KEY_PAUSE_REQUESTED, false).apply();
    }

    /** The short token naming why the last attempt stopped, or "" when nothing has. */
    public static String lastFailure(Context c) {
        return prefs(c).getString(KEY_LAST_FAILURE, FAILURE_NONE);
    }

    static void recordFailure(Context c, String category) {
        prefs(c).edit().putString(KEY_LAST_FAILURE, category == null ? FAILURE_NONE : category)
                .apply();
    }

    // ---- reading the state ------------------------------------------------------------------------

    /** A model file that is present and exactly the pinned size. Cheap, and asks nobody. */
    static boolean modelFileComplete(Context c) {
        File model = modelFile(c);
        return model.exists() && model.length() == MODEL_SIZE_BYTES;
    }

    /**
     * The last WorkManager answer {@link #state} actually consulted, for reporting only.
     *
     * <p>Exists so the status Bundle can carry the work state to Orbit's Diagnostics without
     * paying for a second cross-process query on a call Orbit polls. Never used to decide
     * anything — {@link #state} is the only thing that reads WorkManager for a decision, and it
     * reads it fresh.
     */
    private static volatile ComponentDownloadWorker.WorkState lastWorkState =
            ComponentDownloadWorker.WorkState.UNKNOWN;

    public static ComponentDownloadWorker.WorkState lastObservedWorkState() {
        return lastWorkState;
    }

    /** The state after reconciling the stored mark against what is actually on disk and running. */
    public static State state(Context c) {
        State marked = markedState(c);
        boolean fileComplete = modelFileComplete(c);
        boolean partExists = partFile(c).exists();
        boolean pauseRequested = pauseRequested(c);
        // Asking WorkManager costs a cross-process query with a timeout, so it is asked only when
        // the answer can change the outcome. An installed model, a recorded error, a live import,
        // and a device with nothing downloaded at all are all decided without it.
        ComponentDownloadWorker.WorkState work = needsWorkState(marked, fileComplete, partExists)
                ? ComponentDownloadWorker.snapshot(c)
                : ComponentDownloadWorker.WorkState.UNKNOWN;
        lastWorkState = work;

        State resolved = reconcile(marked, work, ModelImporter.isRunning(c),
                pauseRequested, fileComplete, partExists);

        // Two marks are corrected on disk as well as in the answer, because they describe
        // something that is no longer true and would otherwise be re-read forever.
        if (marked == State.READY && !fileComplete) {
            setState(c, resolved, "");
        } else if (marked == State.IMPORTING && resolved != State.IMPORTING) {
            // The import died with its process. Its partial destination is worthless, and the
            // source still exists in Orbit, so nothing of value is being discarded here.
            //noinspection ResultOfMethodCallIgnored
            importFile(c).delete();
            setState(c, resolved, "");
        } else if (marked != State.READY && resolved == State.READY) {
            // A complete model file only ever exists after verified promotion, so a lost mark
            // re-adopts it rather than demanding a fresh multi-gigabyte download.
            setState(c, State.READY, "");
        }
        return resolved;
    }

    /** Whether the WorkManager query can still change the answer. */
    static boolean needsWorkState(State marked, boolean fileComplete, boolean partExists) {
        switch (marked) {
            case READY:
                return !fileComplete;
            case ERROR:
            case IMPORTING:
                return false;
            case NOT_INSTALLED:
                // Nothing downloaded and nothing installed: there is no work to ask about, and a
                // worker that had started would have marked QUEUED or DOWNLOADING already.
                return fileComplete || partExists;
            default:
                return true;
        }
    }

    private static State markedState(Context c) {
        String raw = prefs(c).getString(KEY_STATE, "");
        try {
            return State.valueOf(raw);
        } catch (Exception e) {
            return State.NOT_INSTALLED;
        }
    }

    /**
     * The whole reconciliation, on plain values.
     *
     * <p>Pure on purpose. The bug this replaces was a decision buried between a Binder call and a
     * file check, where it could only be reasoned about by running a 1.6 GB download on a phone.
     * Every rule below is now something a test can state and pin.
     *
     * <p>The invariant it exists to hold: {@link State#PAUSED} is returned if and only if
     * {@code pauseRequested} is true. No timeout, query failure, missing worker, or unknown
     * WorkManager answer can produce it.
     */
    static State reconcile(State marked, ComponentDownloadWorker.WorkState work,
                           boolean importerRunning, boolean pauseRequested,
                           boolean fileComplete, boolean partExists) {
        switch (marked) {
            case READY:
                if (fileComplete) return State.READY;
                // The mark outlived its file: cleared storage, or a half-finished deletion.
                return stopped(pauseRequested, partExists);
            case VALIDATING:
                // Verification runs inside the worker and can take a while on a large file.
                // Anything short of proof that it is gone keeps saying so.
                if (work == ComponentDownloadWorker.WorkState.RUNNING) return State.VALIDATING;
                if (work == ComponentDownloadWorker.WorkState.UNKNOWN) return State.VALIDATING;
                if (fileComplete) return State.VALIDATING;
                return stopped(pauseRequested, partExists);
            case DOWNLOADING:
            case QUEUED:
            case WAITING_FOR_NETWORK:
                return fromWork(marked, work, pauseRequested, partExists);
            case IMPORTING:
                return importerRunning ? State.IMPORTING : stopped(pauseRequested, partExists);
            case PAUSED:
            case INTERRUPTED:
                // Both are settled, stopped states, and which one shows is decided by the pause
                // request alone — so a resumed download can never leave a stale "Paused" behind.
                // A worker still running against a standing pause request is one that has not
                // noticed yet; the person's decision is what gets shown, immediately.
                if (!pauseRequested && work == ComponentDownloadWorker.WorkState.RUNNING) {
                    return State.DOWNLOADING;
                }
                return stopped(pauseRequested, partExists);
            case ERROR:
                return State.ERROR;
            default:
                if (fileComplete) return State.READY;
                if (work == ComponentDownloadWorker.WorkState.RUNNING) return State.DOWNLOADING;
                return stopped(pauseRequested, partExists);
        }
    }

    /** What an in-flight download looks like, given what WorkManager was able to tell us. */
    private static State fromWork(State marked, ComponentDownloadWorker.WorkState work,
                                  boolean pauseRequested, boolean partExists) {
        switch (work) {
            case RUNNING:
                return State.DOWNLOADING;
            case WAITING_FOR_NETWORK:
                return State.WAITING_FOR_NETWORK;
            case ENQUEUED:
                return State.QUEUED;
            case UNKNOWN:
                // The honest answer to "I could not find out" is the last thing that was true,
                // not a claim that the user paused something.
                return marked;
            default:
                return stopped(pauseRequested, partExists);
        }
    }

    /** A stopped download is paused only if a person asked for that. Otherwise it was interrupted. */
    private static State stopped(boolean pauseRequested, boolean partExists) {
        if (!partExists) return State.NOT_INSTALLED;
        return pauseRequested ? State.PAUSED : State.INTERRUPTED;
    }

    public static boolean isReady(Context c) { return state(c) == State.READY; }

    public static String errorMessage(Context c) {
        return prefs(c).getString(KEY_ERROR, "");
    }

    static void setState(Context c, State state, String error) {
        prefs(c).edit()
                .putString(KEY_STATE, state.name())
                .putString(KEY_ERROR, error == null ? "" : error)
                .apply();
    }

    /**
     * Records a download that stopped without finishing.
     *
     * <p>The single place that decides between "the user paused this" and "this was interrupted",
     * so no failure path can reach for the wrong word on its own.
     */
    static void markStopped(Context c, String failureCategory) {
        if (pauseRequested(c)) {
            setState(c, State.PAUSED, "");
            return;
        }
        recordFailure(c, failureCategory);
        setState(c, partFile(c).exists() ? State.INTERRUPTED : State.NOT_INSTALLED, "");
    }

    /** Bytes present locally, whether partial, importing, or complete. */
    public static long downloadedBytes(Context c) {
        File model = modelFile(c);
        if (model.exists()) return model.length();
        File importing = importFile(c);
        if (importing.exists()) return importing.length();
        File part = partFile(c);
        return part.exists() ? part.length() : 0L;
    }

    /** Every byte this component is responsible for, model and partials together. */
    public static long totalModelBytes(Context c) {
        long total = 0L;
        File[] files = modelDir(c).listFiles();
        if (files != null) for (File file : files) total += Math.max(0L, file.length());
        return total;
    }

    public static long freeStorageBytes(Context c) {
        try {
            return new StatFs(c.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            return -1L;
        }
    }

    public static boolean enoughStorageToDownload(Context c) {
        return enoughStorage(freeStorageBytes(c), Math.max(0L, MODEL_SIZE_BYTES - downloadedBytes(c)));
    }

    /** Whether an incoming copy of the model could fit alongside whatever is already here. */
    public static boolean enoughStorageToImport(Context c) {
        return enoughStorage(freeStorageBytes(c), MODEL_SIZE_BYTES);
    }

    /**
     * The storage decision itself, on plain numbers.
     *
     * <p>An unreadable free-space figure deliberately allows the attempt: refusing on the strength
     * of a number the platform would not give us would block a perfectly capable device, and the
     * download still fails safely if the disk really is full.
     */
    static boolean enoughStorage(long freeBytes, long neededBytes) {
        if (freeBytes < 0) return true;
        return freeBytes >= neededBytes + STORAGE_MARGIN_BYTES;
    }

    /**
     * Verifies a fully present candidate file and promotes it to the real model.
     *
     * <p>The only way a file becomes the model. Any mismatch deletes the bytes rather than
     * leaving something that could later be mistaken for a working model.
     */
    static boolean validateAndPromote(Context c, File candidate) {
        if (!candidate.exists() || candidate.length() != MODEL_SIZE_BYTES) {
            recordFailure(c, FAILURE_SIZE_MISMATCH);
            setState(c, State.ERROR, "The file was incomplete. Try again.");
            //noinspection ResultOfMethodCallIgnored
            candidate.delete();
            return false;
        }
        if (!MODEL_SHA256.equalsIgnoreCase(sha256(candidate))) {
            recordFailure(c, FAILURE_CHECKSUM);
            setState(c, State.ERROR, "The file failed verification and was removed. Try again.");
            //noinspection ResultOfMethodCallIgnored
            candidate.delete();
            return false;
        }
        File model = modelFile(c);
        //noinspection ResultOfMethodCallIgnored
        model.delete();
        if (!candidate.renameTo(model)) {
            recordFailure(c, FAILURE_INSTALL);
            setState(c, State.ERROR, "Orbit could not finish installing the model. Try again.");
            return false;
        }
        clearPauseRequest(c);
        recordFailure(c, FAILURE_NONE);
        setState(c, State.READY, "");
        return true;
    }

    /**
     * Removes the model completely: its file, partial bytes, any stray sibling, and its state.
     * The component itself stays installed, so another model can be downloaded later.
     */
    public static void delete(Context c) {
        ComponentDownloadWorker.cancel(c);
        ModelImporter.cancel(c);
        LocalLlmEngine.unload();
        File[] files = modelDir(c).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(MODEL_FILE_NAME)) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
        clearPauseRequest(c);
        recordFailure(c, FAILURE_NONE);
        setState(c, State.NOT_INSTALLED, "");
    }

    public static void clearError(Context c) {
        if (markedState(c) == State.ERROR) setState(c, State.NOT_INSTALLED, "");
    }

    public static String sha256(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
