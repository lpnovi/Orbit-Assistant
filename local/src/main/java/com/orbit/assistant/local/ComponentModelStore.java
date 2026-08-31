package com.orbit.assistant.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The component owns the models, because the component is what runs them.
 *
 * <p>This is the architectural point of the whole module: the package that performs inference is
 * the package that holds the files, so removing Orbit Local removes both together and neither can
 * outlive the other. Orbit itself never touches these bytes — it asks about them across the
 * service interface.
 *
 * <p>The rules are the ones Orbit Local has always had: a pinned size and SHA-256, partial bytes
 * kept so a download resumes rather than restarts, and promotion to READY only after verification,
 * so an interrupted or corrupted download can never masquerade as an installed model.
 *
 * <h2>Two models, since v0.7.8.0 Beta 1</h2>
 *
 * <p>Everything below is now keyed by {@link ComponentModelSpec.Slot}. The chat model keeps its
 * original preference keys and its original file name, so an existing install is recognised
 * unchanged and nothing has to be re-downloaded; the action model gets its own keys, its own file,
 * its own background work and its own state. They share only free space and this class. Deleting
 * one is a complete removal of one, and the other never notices.
 *
 * <p>The no-argument methods are the chat model, kept so every existing caller and test still says
 * exactly what it meant.
 *
 * <h2>What the states mean, since v0.7.7.6</h2>
 *
 * <p>A device recording showed Orbit reporting <em>Paused</em> while the {@code .part} file was
 * still growing. The cause was in the reconciliation below: it treated "could not prove WorkManager
 * is running" as "the user paused this". A Binder timeout, a WorkManager query failure, a process
 * transition, or a plain race all collapsed into the one word that says a person made a decision.
 *
 * <p>So {@link State#PAUSED} is backed by an explicit pause request and nothing else: it appears
 * when, and only when, someone asked for it. Everything that used to borrow it has its own honest
 * state — {@link State#QUEUED}, {@link State#WAITING_FOR_NETWORK}, {@link State#INTERRUPTED} — and
 * genuine uncertainty keeps the last known state rather than inventing a decision nobody made.
 */
public final class ComponentModelStore {
    private static final String FILE = "orbit_local_component";

    /** Identity of the conversational model, kept as constants for the callers that name it. */
    public static final String MODEL_ID = ComponentModelSpec.CHAT.id;
    public static final String MODEL_DISPLAY_NAME = ComponentModelSpec.CHAT.displayName;
    public static final String MODEL_FILE_NAME = ComponentModelSpec.CHAT.fileName();
    public static final String MODEL_DOWNLOAD_URL = ComponentModelSpec.CHAT.downloadUrl;
    public static final long MODEL_SIZE_BYTES = ComponentModelSpec.CHAT.sizeBytes;
    public static final String MODEL_SHA256 = ComponentModelSpec.CHAT.sha256;
    /** Context window the packaged chat model was exported with. */
    public static final int MODEL_MAX_TOKENS = ComponentModelSpec.CHAT.maxTokens;
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

    private static String key(ComponentModelSpec spec, String name) {
        return spec.keyPrefix() + name;
    }

    public static File modelDir(Context c) {
        File dir = new File(c.getFilesDir(), "local-models");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    public static File modelFile(Context c) { return modelFile(c, ComponentModelSpec.CHAT); }

    public static File modelFile(Context c, ComponentModelSpec spec) {
        return new File(modelDir(c), spec.fileName());
    }

    public static File partFile(Context c) { return partFile(c, ComponentModelSpec.CHAT); }

    public static File partFile(Context c, ComponentModelSpec spec) {
        return new File(modelDir(c), spec.fileName() + ".part");
    }

    /** Where an import is assembled before it has earned the right to be the model. */
    public static File importFile(Context c) { return importFile(c, ComponentModelSpec.CHAT); }

    public static File importFile(Context c, ComponentModelSpec spec) {
        return new File(modelDir(c), spec.fileName() + ".import");
    }

    // ---- the explicit pause request ---------------------------------------------------------------

    /** True only after someone actually asked for a pause, and until they ask to resume. */
    public static boolean pauseRequested(Context c) {
        return pauseRequested(c, ComponentModelSpec.CHAT);
    }

    public static boolean pauseRequested(Context c, ComponentModelSpec spec) {
        return prefs(c).getBoolean(key(spec, "pause_requested"), false);
    }

    static void requestPause(Context c, ComponentModelSpec spec) {
        prefs(c).edit().putBoolean(key(spec, "pause_requested"), true).apply();
    }

    static void clearPauseRequest(Context c, ComponentModelSpec spec) {
        prefs(c).edit().putBoolean(key(spec, "pause_requested"), false).apply();
    }

    /** The short token naming why the last attempt stopped, or "" when nothing has. */
    public static String lastFailure(Context c) { return lastFailure(c, ComponentModelSpec.CHAT); }

    public static String lastFailure(Context c, ComponentModelSpec spec) {
        return prefs(c).getString(key(spec, "last_failure"), FAILURE_NONE);
    }

    static void recordFailure(Context c, String category) {
        recordFailure(c, ComponentModelSpec.CHAT, category);
    }

    static void recordFailure(Context c, ComponentModelSpec spec, String category) {
        prefs(c).edit().putString(key(spec, "last_failure"),
                category == null ? FAILURE_NONE : category).apply();
    }

    // ---- reading the state ------------------------------------------------------------------------

    /** A model file that is present and exactly the pinned size. Cheap, and asks nobody. */
    static boolean modelFileComplete(Context c) {
        return modelFileComplete(c, ComponentModelSpec.CHAT);
    }

    static boolean modelFileComplete(Context c, ComponentModelSpec spec) {
        File model = modelFile(c, spec);
        return model.exists() && model.length() == spec.sizeBytes;
    }

    /**
     * The last WorkManager answer {@link #state} actually consulted, per slot, for reporting only.
     *
     * <p>Exists so the status Bundle can carry the work state to Orbit's Diagnostics without
     * paying for a second cross-process query on a call Orbit polls. Never used to decide
     * anything — {@link #state} is the only thing that reads WorkManager for a decision, and it
     * reads it fresh.
     */
    private static final Map<ComponentModelSpec.Slot, ComponentDownloadWorker.WorkState>
            LAST_WORK_STATE = new EnumMap<>(ComponentModelSpec.Slot.class);

    public static ComponentDownloadWorker.WorkState lastObservedWorkState() {
        return lastObservedWorkState(ComponentModelSpec.CHAT);
    }

    public static synchronized ComponentDownloadWorker.WorkState lastObservedWorkState(
            ComponentModelSpec spec) {
        ComponentDownloadWorker.WorkState state = LAST_WORK_STATE.get(spec.slot);
        return state == null ? ComponentDownloadWorker.WorkState.UNKNOWN : state;
    }

    private static synchronized void rememberWorkState(ComponentModelSpec spec,
                                                       ComponentDownloadWorker.WorkState state) {
        LAST_WORK_STATE.put(spec.slot, state);
    }

    /** The state after reconciling the stored mark against what is actually on disk and running. */
    public static State state(Context c) { return state(c, ComponentModelSpec.CHAT); }

    public static State state(Context c, ComponentModelSpec spec) {
        State marked = markedState(c, spec);
        boolean fileComplete = modelFileComplete(c, spec);
        boolean partExists = partFile(c, spec).exists();
        boolean pauseRequested = pauseRequested(c, spec);
        // Asking WorkManager costs a cross-process query with a timeout, so it is asked only when
        // the answer can change the outcome. An installed model, a recorded error, a live import,
        // and a device with nothing downloaded at all are all decided without it.
        ComponentDownloadWorker.WorkState work = needsWorkState(marked, fileComplete, partExists)
                ? ComponentDownloadWorker.snapshot(c, spec)
                : ComponentDownloadWorker.WorkState.UNKNOWN;
        rememberWorkState(spec, work);

        // Only the chat model is ever imported: the import path exists to rescue a model an older
        // Orbit downloaded, and no older Orbit ever had an action model.
        boolean importing = spec.slot == ComponentModelSpec.Slot.CHAT && ModelImporter.isRunning(c);
        State resolved = reconcile(marked, work, importing, pauseRequested, fileComplete, partExists);

        // Two marks are corrected on disk as well as in the answer, because they describe
        // something that is no longer true and would otherwise be re-read forever.
        if (marked == State.READY && !fileComplete) {
            setState(c, spec, resolved, "");
        } else if (marked == State.IMPORTING && resolved != State.IMPORTING) {
            // The import died with its process. Its partial destination is worthless, and the
            // source still exists in Orbit, so nothing of value is being discarded here.
            //noinspection ResultOfMethodCallIgnored
            importFile(c, spec).delete();
            setState(c, spec, resolved, "");
        } else if (marked != State.READY && resolved == State.READY) {
            // A complete model file only ever exists after verified promotion, so a lost mark
            // re-adopts it rather than demanding a fresh multi-gigabyte download.
            setState(c, spec, State.READY, "");
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

    private static State markedState(Context c, ComponentModelSpec spec) {
        String raw = prefs(c).getString(key(spec, "state"), "");
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

    public static boolean isReady(Context c) { return isReady(c, ComponentModelSpec.CHAT); }

    public static boolean isReady(Context c, ComponentModelSpec spec) {
        return state(c, spec) == State.READY;
    }

    public static String errorMessage(Context c) { return errorMessage(c, ComponentModelSpec.CHAT); }

    public static String errorMessage(Context c, ComponentModelSpec spec) {
        return prefs(c).getString(key(spec, "error"), "");
    }

    static void setState(Context c, State state, String error) {
        setState(c, ComponentModelSpec.CHAT, state, error);
    }

    static void setState(Context c, ComponentModelSpec spec, State state, String error) {
        prefs(c).edit()
                .putString(key(spec, "state"), state.name())
                .putString(key(spec, "error"), error == null ? "" : error)
                .apply();
    }

    /**
     * Records a download that stopped without finishing.
     *
     * <p>The single place that decides between "the user paused this" and "this was interrupted",
     * so no failure path can reach for the wrong word on its own.
     */
    static void markStopped(Context c, String failureCategory) {
        markStopped(c, ComponentModelSpec.CHAT, failureCategory);
    }

    static void markStopped(Context c, ComponentModelSpec spec, String failureCategory) {
        if (pauseRequested(c, spec)) {
            setState(c, spec, State.PAUSED, "");
            return;
        }
        recordFailure(c, spec, failureCategory);
        setState(c, spec, partFile(c, spec).exists() ? State.INTERRUPTED : State.NOT_INSTALLED, "");
    }

    /** Bytes present locally for one model, whether partial, importing, or complete. */
    public static long downloadedBytes(Context c) {
        return downloadedBytes(c, ComponentModelSpec.CHAT);
    }

    public static long downloadedBytes(Context c, ComponentModelSpec spec) {
        File model = modelFile(c, spec);
        if (model.exists()) return model.length();
        File importing = importFile(c, spec);
        if (importing.exists()) return importing.length();
        File part = partFile(c, spec);
        return part.exists() ? part.length() : 0L;
    }

    /**
     * Every byte one model is responsible for, complete file and partials together.
     *
     * <p>Counted by file-name prefix rather than by listing the whole directory, so the two slots
     * report their own storage and neither is ever credited with the other's.
     */
    public static long totalModelBytes(Context c) {
        return totalModelBytes(c, ComponentModelSpec.CHAT);
    }

    public static long totalModelBytes(Context c, ComponentModelSpec spec) {
        long total = 0L;
        File[] files = modelDir(c).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(spec.fileName())) total += Math.max(0L, file.length());
            }
        }
        return total;
    }

    /** Every byte the component's model directory holds, whichever model it belongs to. */
    public static long allModelBytes(Context c) {
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
        return enoughStorageToDownload(c, ComponentModelSpec.CHAT);
    }

    public static boolean enoughStorageToDownload(Context c, ComponentModelSpec spec) {
        return enoughStorage(freeStorageBytes(c),
                Math.max(0L, spec.sizeBytes - downloadedBytes(c, spec)));
    }

    /** Whether an incoming copy of the chat model could fit alongside whatever is already here. */
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
     * <p>The only way a file becomes a model. Any mismatch deletes the bytes rather than
     * leaving something that could later be mistaken for a working model.
     */
    static boolean validateAndPromote(Context c, File candidate) {
        return validateAndPromote(c, ComponentModelSpec.CHAT, candidate);
    }

    static boolean validateAndPromote(Context c, ComponentModelSpec spec, File candidate) {
        if (!candidate.exists() || candidate.length() != spec.sizeBytes) {
            recordFailure(c, spec, FAILURE_SIZE_MISMATCH);
            setState(c, spec, State.ERROR, "The file was incomplete. Try again.");
            //noinspection ResultOfMethodCallIgnored
            candidate.delete();
            return false;
        }
        if (!spec.sha256.equalsIgnoreCase(sha256(candidate))) {
            recordFailure(c, spec, FAILURE_CHECKSUM);
            setState(c, spec, State.ERROR, "The file failed verification and was removed. Try again.");
            //noinspection ResultOfMethodCallIgnored
            candidate.delete();
            return false;
        }
        File model = modelFile(c, spec);
        //noinspection ResultOfMethodCallIgnored
        model.delete();
        if (!candidate.renameTo(model)) {
            recordFailure(c, spec, FAILURE_INSTALL);
            setState(c, spec, State.ERROR, "Orbit could not finish installing the model. Try again.");
            return false;
        }
        clearPauseRequest(c, spec);
        recordFailure(c, spec, FAILURE_NONE);
        setState(c, spec, State.READY, "");
        return true;
    }

    /**
     * Removes one model completely: its file, partial bytes, any stray sibling, and its state.
     *
     * <p>The component itself stays installed, and so does the other model. The sweep is by this
     * model's own file-name prefix, which is what makes "delete the action model" leave a 1.6 GB
     * chat model untouched beside it.
     */
    public static void delete(Context c) { delete(c, ComponentModelSpec.CHAT); }

    public static void delete(Context c, ComponentModelSpec spec) {
        ComponentDownloadWorker.cancel(c, spec);
        if (spec.slot == ComponentModelSpec.Slot.CHAT) ModelImporter.cancel(c);
        LocalLlmEngine.unload(spec.slot);
        File[] files = modelDir(c).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(spec.fileName())) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
        clearPauseRequest(c, spec);
        recordFailure(c, spec, FAILURE_NONE);
        setState(c, spec, State.NOT_INSTALLED, "");
    }

    public static void clearError(Context c) { clearError(c, ComponentModelSpec.CHAT); }

    public static void clearError(Context c, ComponentModelSpec spec) {
        if (markedState(c, spec) == State.ERROR) setState(c, spec, State.NOT_INSTALLED, "");
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
