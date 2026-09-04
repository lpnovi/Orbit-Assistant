package com.orbit.assistant;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * One PDF page rendered once, small enough to keep and to send.
 *
 * <p>Ask Orbit used to hand the model extracted text alone, which is right for prose and poor for
 * everything a textbook actually puts on a page: figures, graphs, tables whose meaning is in their
 * layout, and scans with no text to extract at all. A reader looking at a chart and asking "what's
 * this?" was describing something Orbit could not see.
 *
 * <p>So the exact page travels as an image as well, through the same request path any photo takes.
 * Not a second attachment — the user staged one page and sees one card — and not a second wire
 * format: it is a {@link Bitmap} like every other image Orbit sends, so a provider without vision
 * simply does not carry it and nothing has to be special-cased along the way.
 *
 * <p>{@link #MAX_PX} is the whole bounding policy. A page rendered at print resolution would be
 * tens of megabytes held in a composer for as long as the user is typing, which is the sort of
 * thing that gets an app killed in the background. The cap sits just under the 1800px Orbit already
 * applies to photographs, which is comfortably enough to read body text and identify a figure.
 */
public final class PdfPageImage {

    /** The longest edge of a rendered page, in pixels. */
    public static final int MAX_PX = 1400;
    /** Belt and braces against a page whose aspect ratio is pathological. */
    private static final long MAX_PIXELS = 3_000_000L;

    private PdfPageImage() {}

    /**
     * Renders one page, or returns null when it cannot be rendered.
     *
     * <p>Null is an ordinary outcome, not an error to report: a page that will not render means
     * Orbit sends the page's text alone, which is what it did before this existed.
     */
    public static Bitmap render(String path, int page) {
        if (path == null || path.trim().isEmpty() || page < 0) return null;
        File file = new File(path);
        if (!file.exists()) return null;
        Bitmap bitmap = null;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            if (page >= renderer.getPageCount()) return null;
            try (PdfRenderer.Page source = renderer.openPage(page)) {
                int[] size = boundedSize(source.getWidth(), source.getHeight());
                bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                // PDF pages are transparent where nothing is drawn. Without a white ground the
                // page reaches the model as light text on black, which is not what the user saw.
                new Canvas(bitmap).drawColor(Color.WHITE);
                source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        } catch (Exception error) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return null;
        }
    }

    /** The rendered size for a page of these proportions, inside both ceilings. */
    static int[] boundedSize(int pageWidth, int pageHeight) {
        int width = Math.max(1, pageWidth);
        int height = Math.max(1, pageHeight);
        float scale = MAX_PX / (float) Math.max(width, height);
        if (scale > 1f) scale = 1f;
        int outWidth = Math.max(1, Math.round(width * scale));
        int outHeight = Math.max(1, Math.round(height * scale));
        long pixels = (long) outWidth * outHeight;
        if (pixels > MAX_PIXELS) {
            double shrink = Math.sqrt(MAX_PIXELS / (double) pixels);
            outWidth = Math.max(1, (int) Math.floor(outWidth * shrink));
            outHeight = Math.max(1, (int) Math.floor(outHeight * shrink));
        }
        return new int[]{outWidth, outHeight};
    }
}
