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
     * Qwen 2.5 1.5B Instruct, 8-bit quantized, 4K context, packaged for Google's LiteRT/MediaPipe
     * LLM runtime by the litert-community project. Chosen because it is Apache-2.0 licensed,
     * publicly downloadable without an account, small enough for optional install, and strong
     * enough at instruction following for real assistant chat on a modern flagship.
     */
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

    public static File modelFile(Context c) { return new File(modelDir(c), MODEL_FILE_NAME); }

    static File partFile(Context c) { return new File(modelDir(c), MODEL_FILE_NAME + ".part"); }

    /** The state after reconciling the stored mark against what is actually on disk. */
    public static State state(Context c) {
        String raw = prefs(c).getString(KEY_STATE, "");
        State marked;
        try { marked = State.valueOf(raw); } catch (Exception e) { marked = State.NOT_INSTALLED; }
        File model = modelFile(c);
        File part = partFile(c);
        if (marked == State.READY) {
            if (model.exists() && model.length() == MODEL_SIZE_BYTES) return State.READY;
            // The mark outlived its file: someone cleared storage or deletion half-finished.
            setState(c, State.NOT_INSTALLED, "");
            return part.exists() ? State.PAUSED : State.NOT_INSTALLED;
        }
        if (marked == State.DOWNLOADING || marked == State.VALIDATING) {
            // Only the worker writes these. If it died with the process, partial bytes mean the
            // download can resume; nothing on disk means it never really started.
            if (LocalModelDownloadWorker.isRunning(c)) return marked;
            return part.exists() ? State.PAUSED : State.NOT_INSTALLED;
        }
        if (marked == State.ERROR) return State.ERROR;
        return part.exists() ? State.PAUSED : State.NOT_INSTALLED;
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

    /** Bytes present locally, whether partial or complete. */
    public static long downloadedBytes(Context c) {
        File model = modelFile(c);
        if (model.exists()) return model.length();
        File part = partFile(c);
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
    static boolean validateAndPromote(Context c) {
        File part = partFile(c);
        if (!part.exists() || part.length() != MODEL_SIZE_BYTES) {
            setState(c, State.ERROR, "The downloaded file was incomplete. Download it again.");
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        String digest = sha256(part);
        if (!MODEL_SHA256.equalsIgnoreCase(digest)) {
            setState(c, State.ERROR, "The downloaded file failed verification and was removed. Download it again.");
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        File model = modelFile(c);
        //noinspection ResultOfMethodCallIgnored
        model.delete();
        if (!part.renameTo(model)) {
            setState(c, State.ERROR, "Orbit could not finish installing the model. Try again.");
            return false;
        }
        setState(c, State.READY, "");
        return true;
    }

    /** Removes the model, partial bytes, and state. The download can be started again later. */
    public static void delete(Context c) {
        LocalModelDownloadWorker.cancel(c);
        LocalLlmEngine.unload();
        //noinspection ResultOfMethodCallIgnored
        modelFile(c).delete();
        //noinspection ResultOfMethodCallIgnored
        partFile(c).delete();
        setState(c, State.NOT_INSTALLED, "");
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
