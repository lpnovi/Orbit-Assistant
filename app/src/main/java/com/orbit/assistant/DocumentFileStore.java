package com.orbit.assistant;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/** Private, bounded storage for exact PDF bytes used by the native document viewer. */
public final class DocumentFileStore {
    /** Large enough for ordinary documents, finite enough that a hostile provider cannot fill disk. */
    static final long MAX_BYTES = 128L * 1024L * 1024L;

    private DocumentFileStore() {}

    public static String importPdf(Context context, Uri uri) throws Exception {
        if (context == null || uri == null) return "";
        File dir = new File(context.getFilesDir(), "orbit_documents");
        if (!dir.exists() && !dir.mkdirs()) return "";
        File out = new File(dir, UUID.randomUUID() + ".pdf");
        long written = 0L;
        boolean complete = false;
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream stream = new FileOutputStream(out)) {
            if (in == null) return "";
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count == 0) continue;
                written += count;
                if (written > MAX_BYTES) throw new IllegalArgumentException("PDF is too large");
                stream.write(buffer, 0, count);
            }
            stream.flush();
            complete = written > 0L;
        } finally {
            if (!complete) delete(out.getAbsolutePath());
        }
        return complete ? out.getAbsolutePath() : "";
    }

    public static void delete(String path) {
        if (path == null || path.trim().isEmpty()) return;
        try { new File(path).delete(); } catch (Exception ignored) {}
    }

    public static void deleteAll(List<DocumentReference> references) {
        if (references == null) return;
        for (DocumentReference reference : references) {
            if (reference != null) delete(reference.path);
        }
    }
}
