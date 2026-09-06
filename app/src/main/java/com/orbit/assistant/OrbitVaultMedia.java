package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * The pictures Orbit Vault owns, and the only place they live.
 *
 * <p>A photo arrives as a content URI belonging to Gallery, Files, or whichever app shared it. That
 * URI is a loan: the grant ends with the Activity that received it, the user can delete the original
 * tomorrow, and a Vault that pointed at one would quietly turn into a row of broken thumbnails. So
 * a saved picture is copied once, into Orbit's own private storage, and the copy is what the item
 * refers to from then on.
 *
 * <p>Bounded on the way in, using the same treatment Orbit already gives an attachment it keeps: a
 * long edge of {@value #MAX_PIXELS} and JPEG quality {@value #QUALITY}. That is comfortably enough
 * to look at full screen on a Galaxy S25 Ultra and enough for a future capability to work with,
 * while keeping a personal collection measured in megabytes rather than gigabytes.
 *
 * <p>Nothing here is world-readable, nothing needs a storage permission, and nothing is handed to
 * another app. Deleting the item that owns a file deletes the file.
 */
public final class OrbitVaultMedia {

    /** The longest edge a stored Vault picture may have. */
    public static final int MAX_PIXELS = 1600;
    /** JPEG quality for a stored Vault picture. */
    public static final int QUALITY = 82;

    private static final String DIRECTORY = "orbit_vault/media";

    private OrbitVaultMedia() {}

    /** Orbit's private directory for Vault pictures. Created on demand. */
    public static File directory(Context c) {
        return new File(c.getFilesDir(), DIRECTORY);
    }

    /**
     * Copies one picture into Orbit's own storage and returns its path, or empty on failure.
     *
     * <p>A failure here is never a partial save: the caller gets nothing back and does not write an
     * item, so the Vault cannot end up holding a row that points at a file that was never written.
     */
    public static String save(Context c, Bitmap bitmap) {
        if (c == null || bitmap == null) return "";
        Bitmap scaled = null;
        try {
            File dir = directory(c);
            if (!dir.exists() && !dir.mkdirs()) return "";
            File out = new File(dir, UUID.randomUUID() + ".jpg");
            Bitmap source = bitmap;
            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longest > MAX_PIXELS) {
                float scale = MAX_PIXELS / (float) longest;
                scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                source = scaled;
            }
            try (FileOutputStream stream = new FileOutputStream(out)) {
                if (!source.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)) {
                    out.delete();
                    return "";
                }
                stream.getFD().sync();
            }
            return out.getAbsolutePath();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (scaled != null && scaled != bitmap) scaled.recycle();
        }
    }

    /** Writes already-verified JPEG bytes as a fresh Vault picture. Used only by a restore. */
    static String writeBytes(Context c, byte[] bytes) {
        if (c == null || bytes == null || bytes.length == 0) return "";
        try {
            File dir = directory(c);
            if (!dir.exists() && !dir.mkdirs()) return "";
            File out = new File(dir, "restored-" + UUID.randomUUID() + ".jpg");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(bytes);
                stream.getFD().sync();
            }
            return out.getAbsolutePath();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** The stored picture, or null when the file is gone or no longer decodes. */
    public static Bitmap load(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        try {
            return BitmapFactory.decodeFile(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Whether this path is a file the Vault genuinely owns.
     *
     * <p>Canonicalised on both sides, so nothing that merely looks like a Vault path - a symlink, a
     * traversal, a path pasted into a restored backup - can talk the deleter into touching a file
     * belonging to conversations, to Orbit Local, or to the rest of the phone.
     */
    public static boolean owns(Context c, String path) {
        if (c == null || path == null || path.trim().isEmpty()) return false;
        try {
            String root = directory(c).getCanonicalFile().getPath() + File.separator;
            return new File(path).getCanonicalFile().getPath().startsWith(root);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Deletes one Vault-owned file. A path the Vault does not own is left alone. */
    public static boolean delete(Context c, String path) {
        if (!owns(c, path)) return false;
        try {
            File file = new File(path);
            return !file.exists() || file.delete();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Removes Vault pictures nothing refers to any more.
     *
     * <p>Ordinary deletion already removes the file an item owned, so this exists for the paths
     * that deletion cannot reach: a process killed between writing a file and writing the item that
     * names it, or a restore that replaces the whole collection at once. It only ever removes files
     * inside the Vault's own directory.
     */
    static int pruneOrphans(Context c, Set<String> keep) {
        if (c == null) return 0;
        File dir = directory(c);
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int removed = 0;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            if (keep != null && keep.contains(file.getAbsolutePath())) continue;
            if (file.delete()) removed++;
        }
        return removed;
    }
}
