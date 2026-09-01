package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complete outcome of one attachment selection, whatever the user selected and however it went.
 *
 * <p>One picker trip resolves to exactly one of these, once. That is deliberate: delivering a
 * callback per selected photo would make "the picker finished" unanswerable, and the cancelled
 * picker that used to leave Orbit convinced a selection was still in flight was exactly that kind
 * of bug. A batch always says how many were selected, how many became attachments, and how many
 * did not, so a partial success is reportable instead of looking like either a success or a
 * failure.
 */
public final class AttachmentBatch {
    /** Successfully loaded attachments, in the order the user selected them. */
    public final List<ComposerAttachment> attachments;
    /** How many items the picker offered, before anything was read. */
    public final int selected;
    /** How many failed to load or were of a type Orbit cannot attach. */
    public final int rejected;
    /** A short, privacy-safe message, or empty. Never a path, a URI, or file contents. */
    public final String error;
    /** True when the user backed out and chose nothing. Not an error. */
    public final boolean cancelled;

    private AttachmentBatch(List<ComposerAttachment> attachments, int selected, int rejected,
                            String error, boolean cancelled) {
        this.attachments = attachments == null
                ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(attachments));
        this.selected = Math.max(0, selected);
        this.rejected = Math.max(0, rejected);
        this.error = error == null ? "" : error;
        this.cancelled = cancelled;
    }

    public static AttachmentBatch of(List<ComposerAttachment> attachments, int selected,
                                     int rejected, String error) {
        return new AttachmentBatch(attachments, selected, rejected, error, false);
    }

    /** The user dismissed the picker. Nothing was selected and nothing went wrong. */
    public static AttachmentBatch cancelled() {
        return new AttachmentBatch(Collections.emptyList(), 0, 0, "", true);
    }

    /** Nothing could be attached, and here is why in one short line. */
    public static AttachmentBatch failed(String message) {
        return new AttachmentBatch(Collections.emptyList(), 0, 0, message, false);
    }

    public int accepted() { return attachments.size(); }

    public boolean isEmpty() { return attachments.isEmpty(); }

    /**
     * One line describing what happened, for a toast or the overlay's status row.
     *
     * <p>Counts and Orbit's own words only. The single-attachment case reads exactly as it always
     * did, because the overwhelmingly common outcome should not start announcing arithmetic.
     */
    public String summary() {
        if (cancelled) return "";
        if (attachments.isEmpty()) return error;
        String head = attachments.size() == 1
                ? attachments.get(0).label + " attached"
                : attachments.size() + " attachments added";
        if (rejected <= 0) return head;
        return head + " · " + rejected + (rejected == 1 ? " couldn't be read" : " couldn't be read");
    }
}
