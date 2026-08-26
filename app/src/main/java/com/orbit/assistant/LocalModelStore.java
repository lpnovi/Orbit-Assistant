package com.orbit.assistant;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * What Orbit still knows about local models, now that the component owns them.
 *
 * <p>Before v0.7.7.5 Orbit downloaded, verified, stored, and ran the model itself. Running it now
 * belongs to the optional Orbit Local component, and so does the file — the package that performs
 * inference is the package that holds the model.
 *
 * <p>What is left here is the part Orbit alone can answer. Some phones already carry a ~1.6 GB
 * model downloaded by an older Orbit, sitting in Orbit's own sandbox where the component cannot
 * reach it. Deleting that silently and asking for the download again would be indefensible, so
 * this class detects it, describes it, hands it over for migration, and removes it only once the
 * component has confirmed a verified copy of its own.
 */
public final class LocalModelStore {

    /** Identity of the model an older Orbit may have stored. Unchanged, so it is still found. */
    public static final String MODEL_ID = "qwen2.5-1.5b-instruct-q8";
    public static final String MODEL_DISPLAY_NAME = "Qwen 2.5 (1.5B)";
    public static final String MODEL_FILE_NAME = MODEL_ID + ".task";
    public static final long MODEL_SIZE_BYTES = 1_598_556_720L;
    public static final String MODEL_SHA256 =
            "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3";
    /** Extra free space required beyond the model itself before a download may start. */
    public static final long STORAGE_MARGIN_BYTES = 500L * 1024 * 1024;

    private LocalModelStore() {}

    /** Where an older Orbit stored its model, inside Orbit's own private files. */
    static File legacyModelDir(Context c) {
        return new File(c.getFilesDir(), "local-models");
    }

    public static File legacyModelFile(Context c) {
        return new File(legacyModelDir(c), MODEL_FILE_NAME);
    }

    /**
     * True when this device carries a complete model from an older Orbit.
     *
     * <p>Size only, deliberately: hashing 1.6 GB on every screen open would be absurd. The
     * component re-verifies the full SHA-256 of whatever it receives before promoting it, so the
     * cheap check here can never cause a corrupted model to be trusted.
     */
    public static boolean hasLegacyModel(Context c) {
        File model = legacyModelFile(c);
        return model.isFile() && model.length() == MODEL_SIZE_BYTES;
    }

    /** Every byte an older Orbit left behind, including abandoned partial downloads. */
    public static long legacyBytes(Context c) {
        long total = 0L;
        File[] files = legacyModelDir(c).listFiles();
        if (files != null) for (File file : files) total += Math.max(0L, file.length());
        return total;
    }

    /** Partial bytes from an old interrupted download, with no complete model beside them. */
    public static boolean hasLegacyLeftovers(Context c) {
        return !hasLegacyModel(c) && legacyBytes(c) > 0L;
    }

    /**
     * Removes Orbit's own copy of the model and anything beside it.
     *
     * <p>Only ever called after the component reports a verified READY model of its own, or on an
     * explicit, confirmed user choice to discard it. Never as a side effect of anything else.
     */
    public static void deleteLegacy(Context c) {
        File dir = legacyModelDir(c);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    /** Free bytes on the volume holding Orbit's private files. */
    public static long freeStorageBytes(Context c) {
        try {
            return new StatFs(c.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Whether a legacy model can be copied into the component without filling the device.
     *
     * <p>A copy needs room for a second complete model until the original is removed, which is
     * exactly why the low-storage path exists: Orbit offers to replace rather than move, instead
     * of starting a transfer that cannot finish.
     */
    public static boolean enoughStorageToMigrate(Context c) {
        return enoughStorageToMigrate(freeStorageBytes(c));
    }

    /**
     * The decision itself, on a plain free-space figure.
     *
     * <p>An unreadable reading allows the attempt: refusing on the strength of a number the
     * platform would not give us would block a capable device, and the copy still fails safely —
     * leaving the original untouched — if the disk really is full.
     */
    static boolean enoughStorageToMigrate(long freeBytes) {
        if (freeBytes < 0) return true;
        return freeBytes >= MODEL_SIZE_BYTES + STORAGE_MARGIN_BYTES;
    }

    /** Full verification of the legacy file. Expensive; used only before a migration. */
    static boolean legacyModelVerifies(Context c) {
        File model = legacyModelFile(c);
        if (!model.isFile() || model.length() != MODEL_SIZE_BYTES) return false;
        return MODEL_SHA256.equalsIgnoreCase(sha256(model));
    }

    static String sha256(File file) {
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

    /** Human-readable size such as "1.6 GB". */
    public static String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000L) return String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000L) return String.format(Locale.US, "%.0f KB", bytes / 1_000.0);
        return bytes + " B";
    }
}
