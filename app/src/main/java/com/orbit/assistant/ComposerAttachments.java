package com.orbit.assistant;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one ordered collection of things the user has attached to the message they are writing.
 *
 * <p>Before v0.7.8.0 Beta 3 a composer held at most one {@link ComposerAttachment}, and that
 * single slot was the model everywhere: the tray drew one thing, the request carried one image,
 * and history recorded one path. Selecting four photos therefore could not be expressed at all,
 * so this replaces the slot rather than sitting beside it. There is deliberately no second
 * collection for Gallery, for Share, or for a "multi" mode: Gallery, Camera, File, Clipboard and
 * Share to Orbit all add to this one list, in the order the user chose them.
 *
 * <p>What it is <em>not</em> is screen context. A manual attachment is something the user shared
 * with one message and is used up by it; Attach screen by default and an app profile's Attach
 * policy are standing instructions that survive every send. Those live on their own state in each
 * surface and never enter this list, so generalising attachments cannot turn a standing policy
 * into a one-shot.
 *
 * <p>The instance is mutable while the user is composing and is snapshotted at Send. Nothing
 * downstream ever holds this object: {@link #snapshot()} hands out an immutable copy, so a request
 * in flight and the conversation's own record cannot be changed by someone removing a thumbnail
 * afterwards.
 */
public final class ComposerAttachments {

    /**
     * The most items one user turn may carry, everywhere.
     *
     * <p>One number, read by Gallery, by Share to Orbit, by the full app and by the Side-button
     * overlay, so the answer to how many can be attached cannot differ by route. Ten sits inside
     * what the current provider payload comfortably carries: the request builder already caps a
     * single image at 1800px and re-encodes it as JPEG, and only the newest few turns of history
     * contribute image bytes on top of that.
     *
     * <p>Standing screen policy is deliberately not counted against it. Policy screen context is
     * not something the user attached, and letting it consume a slot would make a preference
     * silently reduce how many photos a message can carry.
     */
    public static final int MAX_PER_TURN = 10;

    private final List<ComposerAttachment> items = new ArrayList<>();

    /** How adding a batch went, in counts the surfaces turn into one short line. */
    public static final class AddResult {
        /** How many the caller offered. */
        public final int offered;
        /** How many were actually added. */
        public final int accepted;
        /** How many were refused because the turn was already full. */
        public final int rejectedOverLimit;

        AddResult(int offered, int accepted, int rejectedOverLimit) {
            this.offered = offered;
            this.accepted = accepted;
            this.rejectedOverLimit = rejectedOverLimit;
        }

        public boolean hitLimit() { return rejectedOverLimit > 0; }
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public int size() { return items.size(); }

    public boolean isFull() { return items.size() >= MAX_PER_TURN; }

    /** How many more items this turn can still take. */
    public int remainingCapacity() { return Math.max(0, MAX_PER_TURN - items.size()); }

    /** The live ordered contents, read-only. */
    public List<ComposerAttachment> items() { return Collections.unmodifiableList(items); }

    /** The first attachment, or null. Only for the places that genuinely describe one thing. */
    public ComposerAttachment first() { return items.isEmpty() ? null : items.get(0); }

    /**
     * Appends a batch, keeping the first items that fit and reporting what would not.
     *
     * <p>Appending rather than replacing is the whole point: opening Gallery a second time adds to
     * what is already staged instead of throwing it away. Over the limit, the earliest items in
     * the offered order are the ones kept, and the count of what was refused is returned so the
     * surface can say so rather than quietly losing the rest.
     */
    public AddResult addAll(List<ComposerAttachment> incoming) {
        if (incoming == null || incoming.isEmpty()) return new AddResult(0, 0, 0);
        int accepted = 0;
        int rejected = 0;
        for (ComposerAttachment attachment : incoming) {
            if (attachment == null) continue;
            if (isFull()) { rejected++; continue; }
            items.add(attachment);
            accepted++;
        }
        return new AddResult(accepted + rejected, accepted, rejected);
    }

    /** Appends one attachment. Refused, and reported as refused, once the turn is full. */
    public AddResult add(ComposerAttachment attachment) {
        return addAll(attachment == null
                ? Collections.emptyList() : Collections.singletonList(attachment));
    }

    /**
     * Replaces everything with one attachment.
     *
     * <p>The one place a collection genuinely becomes a slot: a surface that is describing a
     * single deliberate replacement, and the tests that arm a composer the way the attachment menu
     * does.
     */
    public void replaceWith(ComposerAttachment attachment) {
        items.clear();
        if (attachment != null) items.add(attachment);
    }

    /** Kinds that are a capture of the phone's own screen rather than a file the user picked. */
    static boolean isScreenCapture(String kind) {
        return "screen".equals(kind) || "screen_selection".equals(kind);
    }

    /**
     * Adds a screen capture, superseding any screen capture already staged.
     *
     * <p>A photo and a screenshot accumulate because they are different things the user chose to
     * share. Two screen captures do not: both claim to be the phone's screen, and the second is
     * the user correcting the first rather than adding to it. Photos already staged are untouched,
     * so using the screen never costs the user a picture they picked.
     */
    public AddResult addScreenCapture(ComposerAttachment attachment) {
        items.removeIf(existing -> existing != null && isScreenCapture(existing.kind));
        return add(attachment);
    }

    /** True when a capture of the phone's screen is currently staged. */
    public boolean hasScreenCapture() {
        for (ComposerAttachment attachment : items) {
            if (attachment != null && isScreenCapture(attachment.kind)) return true;
        }
        return false;
    }

    /** Removes exactly the attachment with this id. True when something was removed. */
    public boolean remove(String id) {
        if (id == null || id.isEmpty()) return false;
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

    public void clear() { items.clear(); }

    /** An immutable ordered copy, for freezing at Send. */
    public List<ComposerAttachment> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    // ---- what a set of attachments becomes on the wire -----------------------------------------

    /**
     * The images of an ordered attachment set, in that order, with the gaps closed.
     *
     * <p>A text file or a clipboard note contributes no image and simply is not here; a PDF
     * contributes its rendered preview. Position in this list is not position in the set, and
     * nothing downstream should assume it is.
     */
    public static List<Bitmap> imagesOf(List<ComposerAttachment> attachments) {
        List<Bitmap> images = new ArrayList<>();
        if (attachments == null) return images;
        for (ComposerAttachment attachment : attachments) {
            if (attachment != null && attachment.image != null) images.add(attachment.image);
        }
        return images;
    }

    /**
     * One block of attachment context text for a whole set.
     *
     * <p>A single attachment produces exactly the text it always did, so nothing about a one-photo
     * message changes shape. Several are numbered and labelled so the model can tell which
     * description belongs to which item, and the labels used are Orbit's own tray labels - the
     * same untrusted-data framing the request builder wraps the whole block in still applies.
     */
    public static String contextTextOf(List<ComposerAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        boolean anyText = false;
        for (ComposerAttachment attachment : attachments) {
            if (attachment != null && !attachment.contextText.trim().isEmpty()) anyText = true;
        }
        if (!anyText) return "";
        if (attachments.size() == 1) return attachments.get(0).contextText;

        StringBuilder out = new StringBuilder();
        out.append("The user attached ").append(attachments.size())
                .append(" items to this message, listed in the order they attached them.");
        int position = 0;
        for (ComposerAttachment attachment : attachments) {
            position++;
            if (attachment == null || attachment.contextText.trim().isEmpty()) continue;
            out.append("\n\n--- Attachment ").append(position).append(" of ")
                    .append(attachments.size()).append(": ").append(attachment.label)
                    .append(" ---\n").append(attachment.contextText.trim());
        }
        return out.toString();
    }

    /**
     * The category Orbit reports and stores for a whole set.
     *
     * <p>One kind when they agree, "mixed" when they do not. Never a filename and never anything
     * read out of an attachment, so a line built from it stays privacy-safe.
     */
    public static String kindOf(List<ComposerAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        String kind = null;
        for (ComposerAttachment attachment : attachments) {
            if (attachment == null) continue;
            if (kind == null) kind = attachment.kind;
            else if (!kind.equals(attachment.kind)) return "mixed";
        }
        return kind == null ? "" : kind;
    }

    /** The tray label for a whole set: the single label, or a count. */
    public static String labelOf(List<ComposerAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        if (attachments.size() == 1) return attachments.get(0).label;
        return attachments.size() + " attachments";
    }
}
