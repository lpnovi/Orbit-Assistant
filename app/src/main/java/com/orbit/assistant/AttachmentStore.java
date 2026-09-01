package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

/** Private on-device storage for optional screen thumbnails and temporary request images. */
public final class AttachmentStore {
    private AttachmentStore() {}

    public static String saveHistoryScreen(Context c, Bitmap bitmap) {
        if (bitmap == null || !Prefs.saveScreenThumbnails(c)) return "";
        return save(c, bitmap, "history", 900, 72);
    }

    public static String savePendingScreen(Context c, Bitmap bitmap) {
        if (bitmap == null) return "";
        return save(c, bitmap, "pending", 1280, 78);
    }

    /** Explicit user attachments are always retained privately for local chat history. */
    public static String saveHistoryAttachment(Context c, Bitmap bitmap) {
        if (bitmap == null) return "";
        return save(c, bitmap, "history", 1280, 80);
    }

    /** Most recent assistant-captured screen, used by the full app's Current screen action. */
    public static String saveRecentScreen(Context c, Bitmap bitmap) {
        if (bitmap == null) return "";
        return save(c, bitmap, "recent_screen", 1280, 78);
    }

    private static String save(Context c, Bitmap bitmap, String dirName, int maxPx, int quality) {
        try {
            File dir = new File(c.getFilesDir(), "orbit_attachments/" + dirName);
            if (!dir.exists() && !dir.mkdirs()) return "";
            File outFile = new File(dir, UUID.randomUUID().toString() + ".jpg");
            Bitmap source = bitmap;
            if (bitmap.getWidth() > maxPx || bitmap.getHeight() > maxPx) {
                float scale = Math.min(maxPx / (float) bitmap.getWidth(), maxPx / (float) bitmap.getHeight());
                source = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            }
            try (FileOutputStream stream = new FileOutputStream(outFile)) {
                if (!source.compress(Bitmap.CompressFormat.JPEG, quality, stream)) return "";
            }
            return outFile.getAbsolutePath();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static Bitmap load(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        try { return BitmapFactory.decodeFile(path); }
        catch (Exception ignored) { return null; }
    }

    public static void delete(String path) {
        if (path == null || path.trim().isEmpty()) return;
        try { new File(path).delete(); } catch (Exception ignored) {}
    }

    public static void deleteAll(java.util.List<String> paths) {
        if (paths == null) return;
        for (String path : paths) delete(path);
    }

    /**
     * Freezes an ordered set of images for one request, dropping the ones that fail to save.
     *
     * <p>Order in equals order out for everything that survives. An image that cannot be written
     * is simply not in the result: the request is still sent, with one fewer picture, rather than
     * being abandoned or silently reordered.
     */
    public static java.util.List<String> savePendingScreens(Context c, java.util.List<Bitmap> images) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        if (images == null) return paths;
        for (Bitmap image : images) {
            String path = savePendingScreen(c, image);
            if (!path.isEmpty()) paths.add(path);
        }
        return paths;
    }

    /** As above, for the copies a conversation keeps. */
    public static java.util.List<String> saveHistoryAttachments(Context c, java.util.List<Bitmap> images) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        if (images == null) return paths;
        for (Bitmap image : images) {
            String path = saveHistoryAttachment(c, image);
            if (!path.isEmpty()) paths.add(path);
        }
        return paths;
    }

    /** Loads an ordered set of stored images, skipping any that will no longer decode. */
    public static java.util.List<Bitmap> loadAll(java.util.List<String> paths) {
        java.util.List<Bitmap> images = new java.util.ArrayList<>();
        if (paths == null) return images;
        for (String path : paths) {
            Bitmap image = load(path);
            if (image != null) images.add(image);
        }
        return images;
    }
}
