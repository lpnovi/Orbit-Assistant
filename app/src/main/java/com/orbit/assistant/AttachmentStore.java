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
}
