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
 * <p>The rules are the ones Orbit Local has always had, moved here intact: a pinned size and
 * SHA-256, partial bytes kept so a download resumes rather than restarts, and promotion to READY
 * only after verification, so an interrupted or corrupted download can never masquerade as an
 * installed model.
 */
public final class ComponentModelStore {
    private static final String FILE = "orbit_local_component";
    private static final String KEY_STATE = "model_state";
    private static final String KEY_ERROR = "model_error";

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

    public enum State {
        NOT_INSTALLED,
        /** Partial bytes exist and nothing is running; the download can resume. */
        PAUSED,
        DOWNLOADING,
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

    /** The state after reconciling the stored mark against what is actually on disk. */
    public static State state(Context c) {
        String raw = prefs(c).getString(KEY_STATE, "");
        State marked;
        try { marked = State.valueOf(raw); } catch (Exception e) { marked = State.NOT_INSTALLED; }
        File model = modelFile(c);
        File part = partFile(c);
        boolean fileComplete = model.exists() && model.length() == MODEL_SIZE_BYTES;
        if (marked == State.READY) {
            if (fileComplete) return State.READY;
            // The mark outlived its file: cleared storage, or a half-finished deletion.
            setState(c, State.NOT_INSTALLED, "");
            return part.exists() ? State.PAUSED : State.NOT_INSTALLED;
        }
        if (marked == State.DOWNLOADING || marked == State.VALIDATING) {
            if (ComponentDownloadWorker.isRunning(c)) return marked;
            return part.exists() ? State.PAUSED : State.NOT_INSTALLED;
        }
        if (marked == State.IMPORTING) {
            if (ModelImporter.isRunning(c)) return marked;
            // The import died with its process. Its partial destination is worthless, and the
            // source still exists in Orbit, so this is simply not installed.
            //noinspection ResultOfMethodCallIgnored
            importFile(c).delete();
            setState(c, State.NOT_INSTALLED, "");
            return part.exists() ? State.PAUSED : State.NOT_INSTALLED;
        }
        if (marked == State.ERROR) return State.ERROR;
        if (fileComplete) {
            // A complete model file only ever exists after verified promotion, so a lost mark
            // re-adopts it rather than demanding a fresh multi-gigabyte download.
            setState(c, State.READY, "");
            return State.READY;
        }
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
            setState(c, State.ERROR, "The file was incomplete. Try again.");
            //noinspection ResultOfMethodCallIgnored
            candidate.delete();
            return false;
        }
        if (!MODEL_SHA256.equalsIgnoreCase(sha256(candidate))) {
            setState(c, State.ERROR, "The file failed verification and was removed. Try again.");
            //noinspection ResultOfMethodCallIgnored
            candidate.delete();
            return false;
        }
        File model = modelFile(c);
        //noinspection ResultOfMethodCallIgnored
        model.delete();
        if (!candidate.renameTo(model)) {
            setState(c, State.ERROR, "Orbit could not finish installing the model. Try again.");
            return false;
        }
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
        setState(c, State.NOT_INSTALLED, "");
    }

    public static void clearError(Context c) {
        if (state(c) == State.ERROR) setState(c, State.NOT_INSTALLED, "");
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
