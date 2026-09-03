package com.orbit.assistant;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a list of selected URIs into attachments, one at a time.
 *
 * <p>Sequential on purpose. A photo from a modern Samsung sensor is tens of megapixels, and
 * {@link AttachmentLoader} downsamples each one to 1800px on the long edge while decoding - but the
 * decode itself still needs room for the source. Ten of those in parallel is an out-of-memory
 * crash on a phone that would have handled all ten in sequence without noticing. So there is one
 * decode alive at a time, on whatever background thread the caller already owns, and no executor
 * is created here at all.
 *
 * <p>There is deliberately no second image decoder in Orbit. Every item goes through
 * {@link AttachmentLoader#load}, which is where downsampling, JPEG re-encoding, PDF handling, the
 * text-size ceiling and the untrusted-data framing already live; adding a faster path for batches
 * would mean maintaining two answers to "what is safe to read".
 *
 * <p>One bad item is not the batch. A photo the provider will not open, a file type Orbit does not
 * support, or a URI whose permission expired costs that item and nothing else: the rest still
 * arrive, in order, and the count of what failed is reported rather than swallowed.
 */
public final class AttachmentBatchLoader {

    /** Told after each item so a caller can show progress. Never given file contents. */
    public interface Progress {
        /** {@code index} is 1-based over {@code total}. */
        void onItem(int index, int total);
    }

    private AttachmentBatchLoader() {}

    public static AttachmentBatch load(Context context, List<Uri> uris, String sourceLabel) {
        return load(context, uris, sourceLabel, ComposerAttachments.MAX_PER_TURN, null);
    }

    /**
     * Loads at most {@code capacity} items from {@code uris}, in order.
     *
     * @param capacity how many the composer can still take. Items past it are counted as selected
     *                 and reported to the caller, never quietly dropped, and are not read at all -
     *                 decoding an image that cannot be attached would be pure waste.
     */
    public static AttachmentBatch load(Context context, List<Uri> uris, String sourceLabel,
                                       int capacity, Progress progress) {
        if (context == null || uris == null || uris.isEmpty()) return AttachmentBatch.cancelled();

        int selected = uris.size();
        int usable = Math.max(0, Math.min(capacity, selected));
        List<ComposerAttachment> loaded = new ArrayList<>();
        int failed = 0;
        String firstError = "";

        for (int i = 0; i < usable; i++) {
            Uri uri = uris.get(i);
            if (progress != null) progress.onItem(i + 1, usable);
            AttachmentLoader.Result result;
            try {
                result = AttachmentLoader.load(context, uri);
            } catch (Exception failure) {
                // A provider that throws mid-batch is an item that failed, not a batch that died.
                failed++;
                continue;
            }
            if (result == null || !result.ok()) {
                failed++;
                if (firstError.isEmpty() && result != null && !result.error.isEmpty()) {
                    firstError = result.error;
                }
                continue;
            }
            loaded.add(new ComposerAttachment(kindFor(sourceLabel, result.kind),
                    labelFor(sourceLabel, result.label), result.contextText, result.image,
                    result.document));
        }

        // Everything past the capacity is still something the user selected, so it is counted as
        // selected and as rejected; the composer's own limit reporting turns that into a sentence.
        int overCapacity = selected - usable;
        int rejected = failed + overCapacity;
        String error = loaded.isEmpty()
                ? (firstError.isEmpty() ? "Orbit could not read the selected attachments." : firstError)
                : "";
        return AttachmentBatch.of(loaded, selected, rejected, error);
    }

    private static String kindFor(String sourceLabel, String resultKind) {
        return "Camera".equals(sourceLabel) ? "camera" : resultKind;
    }

    private static String labelFor(String sourceLabel, String resultLabel) {
        if ("Camera".equals(sourceLabel)) return "Camera photo";
        if ("Clipboard".equals(sourceLabel)) return "Clipboard · " + resultLabel;
        return resultLabel;
    }
}
