package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

/** App-private temporary files used only while the shared screen editor is open. */
public final class ScreenSelectionStore {
    public static final String EXTRA_SOURCE_PATH = "screen_selection_source";
    public static final String EXTRA_RESULT_PATH = "screen_selection_result";
    public static final String EXTRA_APP_PACKAGE = "screen_selection_app_package";
    public static final String EXTRA_APP_LABEL = "screen_selection_app_label";
    public static final String EXTRA_AGE_LABEL = "screen_selection_age_label";
    public static final String EXTRA_CALLBACK_TOKEN = "screen_selection_callback_token";
    public static final String EXTRA_PRECISE = "screen_selection_precise";

    private static final String DIRECTORY = "orbit_screen_selection";
    private static final long STALE_MS = 24L * 60L * 60L * 1000L;

    private ScreenSelectionStore() {}

    public static Intent editorIntent(Context context, String sourcePath, String packageName,
                                      String appLabel, String ageLabel, String callbackToken) {
        Intent intent = new Intent(context, ScreenSelectionActivity.class)
                .putExtra(EXTRA_SOURCE_PATH, sourcePath == null ? "" : sourcePath)
                .putExtra(EXTRA_APP_PACKAGE, packageName == null ? "" : packageName)
                .putExtra(EXTRA_APP_LABEL, appLabel == null ? "" : appLabel)
                .putExtra(EXTRA_AGE_LABEL, ageLabel == null ? "" : ageLabel);
        if (callbackToken != null && !callbackToken.isEmpty()) {
            intent.putExtra(EXTRA_CALLBACK_TOKEN, callbackToken);
        }
        return intent;
    }

    public static String saveSource(Context context, Bitmap bitmap) {
        return save(context, bitmap, "source");
    }

    public static String saveResult(Context context, Bitmap bitmap) {
        return save(context, bitmap, "result");
    }

    private static String save(Context context, Bitmap bitmap, String prefix) {
        if (context == null || bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return "";
        }
        cleanupStale(context);
        try {
            File directory = directory(context);
            if (!directory.exists() && !directory.mkdirs()) return "";
            File file = new File(directory, prefix + "-" + UUID.randomUUID() + ".png");
            try (FileOutputStream stream = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    file.delete();
                    return "";
                }
            }
            return file.getAbsolutePath();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static Bitmap load(Context context, String path) {
        File file = safeFile(context, path);
        if (file == null || !file.isFile()) return null;
        try { return BitmapFactory.decodeFile(file.getAbsolutePath()); }
        catch (Exception ignored) { return null; }
    }

    public static void delete(Context context, String path) {
        File file = safeFile(context, path);
        if (file == null) return;
        try { file.delete(); } catch (Exception ignored) { }
    }

    public static void cleanupStale(Context context) {
        if (context == null) return;
        File directory = directory(context);
        File[] files = directory.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - STALE_MS;
        for (File file : files) {
            if (file != null && file.isFile() && file.lastModified() < cutoff) {
                try { file.delete(); } catch (Exception ignored) { }
            }
        }
    }

    private static File directory(Context context) {
        return new File(context.getCacheDir(), DIRECTORY);
    }

    private static File safeFile(Context context, String path) {
        if (context == null || path == null || path.trim().isEmpty()) return null;
        try {
            File root = directory(context).getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            String prefix = root.getPath() + File.separator;
            return file.getPath().startsWith(prefix) ? file : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
