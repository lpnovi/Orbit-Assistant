package com.orbit.assistant;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One serialized PdfRenderer session. Every page handle and bitmap has an explicit owner. */
public final class PdfRenderEngine {
    public interface Callback {
        void onOpened(int pageCount);
        void onRendered(long generation, int page, Bitmap bitmap);
        void onOpenError(String message);
        void onRenderError(long generation, int page, String message);
    }

    private static final int MIN_WIDTH = 600;
    private static final int MAX_WIDTH = 2400;
    private static final long MAX_PIXELS = 10_000_000L;

    private final String path;
    private final Callback callback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;

    public PdfRenderEngine(String path, Callback callback) {
        this.path = path == null ? "" : path;
        this.callback = callback;
    }

    public void open() {
        executor.execute(() -> {
            try {
                descriptor = ParcelFileDescriptor.open(new File(path),
                        ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(descriptor);
                int count = renderer.getPageCount();
                post(() -> callback.onOpened(count));
            } catch (Exception error) {
                closeResources();
                post(() -> callback.onOpenError("Orbit could not open this PDF."));
            }
        });
    }

    public void render(long generation, int pageIndex, int requestedWidth) {
        executor.execute(() -> {
            if (closed || renderer == null || pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                post(() -> callback.onRenderError(generation, pageIndex,
                        "That page is unavailable."));
                return;
            }
            Bitmap bitmap = null;
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, requestedWidth));
                double ratio = page.getHeight() / (double) Math.max(1, page.getWidth());
                int height = Math.max(1, (int) Math.round(width * ratio));
                if (height > MAX_WIDTH * 2 || (long) width * height > MAX_PIXELS) {
                    double factor = Math.sqrt(MAX_PIXELS / (double) Math.max(1L, (long) width * height));
                    width = Math.max(1, (int) Math.floor(width * factor));
                    height = Math.max(1, (int) Math.floor(height * factor));
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                new Canvas(bitmap).drawColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                Bitmap complete = bitmap;
                post(() -> callback.onRendered(generation, pageIndex, complete));
            } catch (Exception error) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                post(() -> callback.onRenderError(generation, pageIndex,
                        "Orbit could not render that page."));
            }
        });
    }

    public void close() {
        if (closed) return;
        closed = true;
        executor.execute(this::closeResources);
        executor.shutdown();
    }

    private void post(Runnable runnable) {
        if (closed || callback == null) return;
        main.post(() -> { if (!closed) runnable.run(); });
    }

    private void closeResources() {
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        renderer = null;
        try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) {}
        descriptor = null;
    }
}
