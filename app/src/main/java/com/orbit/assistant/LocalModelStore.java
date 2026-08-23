package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * Owns Orbit Local's model files and their lifecycle.
 *
 * <p>One carefully chosen model is supported first. It is optional and downloaded on request —
 * never bundled into the APK — from a public, license-compatible source, and it is validated
 * against a pinned size and SHA-256 before it can ever be marked ready, so an interrupted or
 * corrupted download cannot masquerade as an installed model.
 *
 * <p>The state machine is deliberately reconciled against the filesystem on every read: a READY
 * mark without its file downgrades to NOT_INSTALLED, a DOWNLOADING mark from a dead process
 * becomes PAUSED when partial bytes exist, and partial bytes are kept so downloads resume
 * instead of restarting.
 */
public final class LocalModelStore {
    private static final String FILE = "orbit_local_ai";
    private static final String KEY_STATE = "model_state";
    private static final String KEY_ERROR = "model_error";

    /**
     * One downloadable local component: everything the store needs to download, verify, adopt,
     * and remove it. Orbit Local's near future holds more than one of these — a general chat
     * model today, a lightweight device-action/intent model later — so all file, state, and
     * validation logic is keyed by a spec rather than assuming a single global model. Each spec
     * keeps its own preference keys, so components never share or clobber state.
     */
    public static final class ModelSpec {
        public final String id;
        public final String displayName;
        public final String fileName;
        public final String downloadUrl;
        public final long sizeBytes;
        public final String sha256;
        public final int maxTokens;
        final String stateKey;
        final String errorKey;

        ModelSpec(String id, String displayName, String downloadUrl, long sizeBytes,
                  String sha256, int maxTokens, String stateKey, String errorKey) {
            this.id = id;
            this.displayName = displayName;
            this.fileName = id + ".task";
            this.downloadUrl = downloadUrl;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.maxTokens = maxTokens;
            this.stateKey = stateKey;
            this.errorKey = errorKey;
        }
    }

    /**
     * Qwen 2.5 1.5B Instruct, 8-bit quantized, 4K context, packaged for Google's LiteRT/MediaPipe
     * LLM runtime by the litert-community project. Chosen because it is Apache-2.0 licensed,
     * publicly downloadable without an account, small enough for optional install, and strong
     * enough at instruction following for real assistant chat on a modern flagship.
     *
     * <p>Its state keys keep the pre-spec names so an already-installed model is never orphaned
     * by an update; future components use "<key>:<model id>" keys.
     */
    public static final ModelSpec CHAT_MODEL = new ModelSpec(
            "qwen2.5-1.5b-instruct-q8",
            "Qwen 2.5 (1.5B)",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/"
                    + "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            1_598_556_720L,
            "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3",
            4096,
            KEY_STATE, KEY_ERROR);

    public static final String MODEL_ID = CHAT_MODEL.id;
    public static final String MODEL_DISPLAY_NAME = CHAT_MODEL.displayName;
    public static final String MODEL_FILE_NAME = CHAT_MODEL.fileName;
    public static final String MODEL_DOWNLOAD_URL = CHAT_MODEL.downloadUrl;
    public static final long MODEL_SIZE_BYTES = CHAT_MODEL.sizeBytes;
    public static final String MODEL_SHA256 = CHAT_MODEL.sha256;
    /** Context window the packaged model was exported with. */
    public static final int MODEL_MAX_TOKENS = CHAT_MODEL.maxTokens;
    /** Extra free space required beyond the model itself before a download may start. */
    public static final long STORAGE_MARGIN_BYTES = 500L * 1024 * 1024;

    public enum State {
        NOT_INSTALLED,
        /** Partial bytes exist and no download is running; the download can resume. */
        PAUSED,
        DOWNLOADING,
        /** Fully downloaded, checksum verification in progress. */
        VALIDATING,
        READY,
        ERROR
    }

    private LocalModelStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static File modelDir(Context c) {
        File dir = new File(c.getFilesDir(), "local-models");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    public static File modelFile(Context c) { return modelFile(c, CHAT_MODEL); }

    public static File modelFile(Context c, ModelSpec spec) {
        return new File(modelDir(c), spec.fileName);
    }

    static File partFile(Context c) { return partFile(c, CHAT_MODEL); }

    static File partFile(Context c, ModelSpec spec) {
        return new File(modelDir(c), spec.fileName + ".part");
    }

    /** The state after reconciling the stored mark against what is actually on disk. */
    public static State state(Context c) { return state(c, CHAT_MODEL); }

    public static State state(Context c, ModelSpec spec) {
        String raw = prefs(c).getString(spec.stateKey, "");
        State marked;
        try { marked = State.valueOf(raw); } catch (Exception e) { marked = State.NOT_INSTALLED; }
        File model = modelFile(c, spec);
        File part = partFile(c, spec);
        boolean fileComplete = model.exists() && model.length() == spec.sizeBytes;
        if (marked == State.READY) {
            if (fileComplete) return State.READY;
            // The mark outlived its file: someone cleared storage or deletion half-finished.
            setState(c, spec, State.NOT_INSTALLED, "");
            return part.exists() ? State.PAUSED : State.NOT_INSTALLED;
        }
        if (marked == State.DOWNLOADING || marked == State.VALIDATING) {
            // Only the worker writes these. If it died with the process, partial bytes mean the
            // download can resume; nothing on disk means it never really started.
            if (LocalModelDownloadWorker.isRunning(c)) return marked;
            return part.exists() ? State.PAUSED : State.NOT_INSTALLED;
        }
        if (marked == State.ERROR) return State.ERROR;
        if (fileComplete) {
            // A complete model file only ever exists after checksum promotion, so a lost mark
            // (cleared preferences, a future key migration) re-adopts it instead of demanding a
            // fresh multi-gigabyte download.
            setState(c, spec, State.READY, "");
            return State.READY;
        }
        return part.exists() ? State.PAUSED : State.NOT_INSTALLED;
    }

    public static boolean isReady(Context c) { return state(c) == State.READY; }

    public static String errorMessage(Context c) {
        return prefs(c).getString(CHAT_MODEL.errorKey, "");
    }

    static void setState(Context c, State state, String error) {
        setState(c, CHAT_MODEL, state, error);
    }

    static void setState(Context c, ModelSpec spec, State state, String error) {
        prefs(c).edit()
                .putString(spec.stateKey, state.name())
                .putString(spec.errorKey, error == null ? "" : error)
                .apply();
    }

    /** Bytes present locally, whether partial or complete. */
    public static long downloadedBytes(Context c) { return downloadedBytes(c, CHAT_MODEL); }

    public static long downloadedBytes(Context c, ModelSpec spec) {
        File model = modelFile(c, spec);
        if (model.exists()) return model.length();
        File part = partFile(c, spec);
        return part.exists() ? part.length() : 0L;
    }

    /** Free bytes on the volume holding Orbit's private files. */
    public static long freeStorageBytes(Context c) {
        try {
            StatFs stat = new StatFs(c.getFilesDir().getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception e) {
            return -1L;
        }
    }

    /** True when the volume can hold what is still missing plus a safety margin. */
    public static boolean enoughStorageToDownload(Context c) {
        long free = freeStorageBytes(c);
        if (free < 0) return true; // Unknowable here; the worker still fails safely on disk-full.
        long remaining = Math.max(0L, MODEL_SIZE_BYTES - downloadedBytes(c));
        return free >= remaining + STORAGE_MARGIN_BYTES;
    }

    /**
     * Verifies the fully downloaded part file and promotes it to the real model file. Called by
     * the download worker only. Any mismatch deletes the bytes rather than leaving a corrupted
     * file that could later be mistaken for a model.
     */
    static boolean validateAndPromote(Context c) { return validateAndPromote(c, CHAT_MODEL); }

    static boolean validateAndPromote(Context c, ModelSpec spec) {
        File part = partFile(c, spec);
        if (!part.exists() || part.length() != spec.sizeBytes) {
            setState(c, spec, State.ERROR, "The downloaded file was incomplete. Download it again.");
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        String digest = sha256(part);
        if (!spec.sha256.equalsIgnoreCase(digest)) {
            setState(c, spec, State.ERROR, "The downloaded file failed verification and was removed. Download it again.");
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        File model = modelFile(c, spec);
        //noinspection ResultOfMethodCallIgnored
        model.delete();
        if (!part.renameTo(model)) {
            setState(c, spec, State.ERROR, "Orbit could not finish installing the model. Try again.");
            return false;
        }
        setState(c, spec, State.READY, "");
        return true;
    }

    /**
     * Removes one model completely: its file, its partial bytes, any stray temp file carrying
     * its name, and its state — and nothing else. Conversations, preferences, other providers,
     * the shared runtime, and any other installed local component are untouched, and the model
     * can be downloaded again at any time.
     */
    public static void delete(Context c) { delete(c, CHAT_MODEL); }

    public static void delete(Context c, ModelSpec spec) {
        LocalModelDownloadWorker.cancel(c);
        LocalLlmEngine.unload();
        File[] files = modelDir(c).listFiles();
        if (files != null) {
            for (File file : files) {
                // Prefix match sweeps the model, its .part file, and any stray temp sibling.
                if (file.getName().startsWith(spec.fileName)) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
        setState(c, spec, State.NOT_INSTALLED, "");
    }

    /** Clears an error so the user can retry from a clean NOT_INSTALLED or PAUSED state. */
    public static void clearError(Context c) {
        if (state(c) == State.ERROR) setState(c, State.NOT_INSTALLED, "");
    }

    static String sha256(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Human-readable size such as "1.6 GB". */
    public static String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000L) return String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000L) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1_000.0);
        return bytes + " B";
    }
}
