package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

/**
 * Temporary local bridge containing the most recent underlying screen Orbit was
 * explicitly invoked over. It lets the full app's "Current screen" attachment
 * reuse context the assistant session already received, without screen-recording
 * permissions or capturing Orbit's own UI.
 */
public final class LastScreenStore {
    private static final String FILE = "orbit_last_screen";
    private static final long MAX_AGE_MS = 30L * 60L * 1000L;

    private LastScreenStore() {}

    public static final class Snapshot {
        public final String text, imagePath, packageName, appLabel;
        public final long capturedAt;

        Snapshot(String text, String imagePath, String packageName, String appLabel, long capturedAt) {
            this.text = text == null ? "" : text;
            this.imagePath = imagePath == null ? "" : imagePath;
            this.packageName = packageName == null ? "" : packageName;
            this.appLabel = appLabel == null ? "" : appLabel;
            this.capturedAt = capturedAt;
        }

        public Bitmap image() { return AttachmentStore.load(imagePath); }

        public String ageLabel() {
            long age = Math.max(0, System.currentTimeMillis() - capturedAt);
            long mins = age / 60000L;
            if (mins < 1) return "just now";
            if (mins == 1) return "1 min ago";
            return mins + " min ago";
        }
    }

    public static synchronized void begin(Context c, String pkg, String label) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        AttachmentStore.delete(p.getString("imagePath", ""));
        p.edit()
                .putString("text", "")
                .putString("imagePath", "")
                .putString("packageName", safe(pkg))
                .putString("appLabel", safe(label))
                .putLong("capturedAt", System.currentTimeMillis())
                .apply();
    }

    public static synchronized void updateText(Context c, String text, String pkg, String label) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        p.edit()
                .putString("text", safe(text))
                .putString("packageName", best(pkg, p.getString("packageName", "")))
                .putString("appLabel", best(label, p.getString("appLabel", "")))
                .putLong("capturedAt", System.currentTimeMillis())
                .apply();
    }

    public static synchronized void updateImage(Context c, Bitmap bitmap, String pkg, String label) {
        if (bitmap == null) return;
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String old = p.getString("imagePath", "");
        String path = AttachmentStore.saveRecentScreen(c, bitmap);
        if (path.isEmpty()) return;
        if (!old.equals(path)) AttachmentStore.delete(old);
        p.edit()
                .putString("imagePath", path)
                .putString("packageName", best(pkg, p.getString("packageName", "")))
                .putString("appLabel", best(label, p.getString("appLabel", "")))
                .putLong("capturedAt", System.currentTimeMillis())
                .apply();
    }

    public static synchronized Snapshot load(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        long time = p.getLong("capturedAt", 0);
        if (time <= 0 || System.currentTimeMillis() - time > MAX_AGE_MS) return null;
        String text = p.getString("text", "");
        String path = p.getString("imagePath", "");
        if ((text == null || text.trim().isEmpty()) &&
                (path == null || path.trim().isEmpty())) return null;
        return new Snapshot(text, path, p.getString("packageName", ""),
                p.getString("appLabel", ""), time);
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String best(String preferred, String fallback) {
        String p = safe(preferred);
        return p.isEmpty() ? safe(fallback) : p;
    }
}
