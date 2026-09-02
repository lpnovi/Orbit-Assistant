package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the attachment viewer is showing, as an ordered list and a position in it.
 *
 * <p>Deliberately separate from every view, because the questions that matter here are not drawing
 * questions. Which image opens when the third thumbnail is tapped, what the counter says, what a
 * screen reader is told, what happens to the position when the middle item is removed, and whether
 * a PDF may enter an image viewer at all - none of those need a laid-out screen to be answered, and
 * all of them are the kind of thing that quietly goes wrong.
 *
 * <p>The ordering is the attachment ordering, unchanged. The viewer never sorts, never groups, and
 * never renumbers: an ordered set of photos is the same set in the same order whether it is being
 * drawn as a strip, sent to a model, or looked at one at a time.
 *
 * <p>This is a projection, never a second copy of the truth. For an unsent message
 * {@link ComposerAttachments} remains the one collection that knows what is attached, and removal
 * here is an instruction to it rather than a change made beside it.
 */
public final class AttachmentViewerModel {

    /** One thing the viewer can show. */
    public static final class Item {
        /**
         * How the owner of this image names it: a {@link ComposerAttachment#id} for something
         * still being composed, a stored file path for something already sent.
         */
        public final String id;
        /** Orbit's own attachment category. Never a MIME type read off an external Intent. */
        public final String kind;
        /** The attachment label, kept for documents and for nothing the viewer draws large. */
        public final String label;

        public Item(String id, String kind, String label) {
            this.id = id == null ? "" : id;
            this.kind = kind == null ? "" : kind;
            this.label = label == null ? "" : label;
        }
    }

    /**
     * Attachment kinds where the picture <em>is</em> the attachment.
     *
     * <p>The exclusion matters more than the inclusion. A PDF also carries a bitmap - Orbit renders
     * its first page as a preview - and a full-screen viewer that let a user pinch into page one of
     * a fifty page document while offering no way to reach page two would be a worse experience
     * than not opening at all. A text file and a clipboard note have no picture in the first place.
     * So documents keep the strip card they always had, and only the kinds whose whole content is
     * one image can be opened.
     */
    public static boolean isViewableImage(String kind) {
        if (kind == null) return false;
        String value = kind.trim();
        return "image".equals(value)
                || "camera".equals(value)
                || "screen".equals(value)
                || "screen_selection".equals(value);
    }

    /** True when this composer attachment can be opened full screen. */
    public static boolean isViewable(ComposerAttachment attachment) {
        return attachment != null && attachment.image != null && isViewableImage(attachment.kind);
    }

    private final List<Item> items = new ArrayList<>();
    private final boolean removable;
    private int index;

    /**
     * @param removable whether this viewer may remove what it is showing. True only for a message
     *                  the user is still writing; a turn that has already been sent is a record of
     *                  what happened and is never edited from here.
     */
    public AttachmentViewerModel(List<Item> items, int index, boolean removable) {
        if (items != null) {
            for (Item item : items) if (item != null) this.items.add(item);
        }
        this.removable = removable;
        this.index = clamp(index);
    }

    /** The images, in attachment order. */
    public List<Item> items() { return Collections.unmodifiableList(items); }

    public int size() { return items.size(); }

    public boolean isEmpty() { return items.isEmpty(); }

    public int index() { return index; }

    public boolean isRemovable() { return removable; }

    public Item current() { return isEmpty() ? null : items.get(index); }

    /** The item at a position, or null. */
    public Item at(int position) {
        return position < 0 || position >= items.size() ? null : items.get(position);
    }

    /**
     * Moves to a position, clamped. Returns true when the position actually changed.
     *
     * <p>Clamped rather than refused, because the callers are a restored instance state and a
     * settled page animation, and both would rather land somewhere real than throw.
     */
    public boolean moveTo(int position) {
        int next = clamp(position);
        if (next == index) return false;
        index = next;
        return true;
    }

    public boolean next() { return moveTo(index + 1); }

    public boolean previous() { return moveTo(index - 1); }

    public boolean hasNext() { return index < items.size() - 1; }

    public boolean hasPrevious() { return index > 0; }

    /**
     * The position of an item by id, or -1.
     *
     * <p>How a tap becomes an opening index. Position is resolved from identity rather than from
     * where the thumbnail sat in the strip, because those two are not the same list: a strip
     * holding a PDF and three photos draws four cards, and the viewer holds three images. Tapping
     * the second photo has to open the second photo.
     */
    public int indexOf(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) return i;
        }
        return -1;
    }

    /**
     * Removes the current item and settles on a sensible neighbour.
     *
     * <p>Removing the last item leaves the model empty, which is the viewer closing rather than an
     * error. Removing anything else keeps every remaining item in its original order and lands on
     * the one that took its place, or on the new last item when the end was removed.
     */
    public boolean removeCurrent() {
        if (!removable || isEmpty()) return false;
        items.remove(index);
        index = clamp(index);
        return true;
    }

    /** The counter, or empty when there is only one image and a counter would be noise. */
    public String counterText() {
        if (items.size() <= 1) return "";
        return (index + 1) + " of " + items.size();
    }

    /**
     * What a screen reader is told about the image on screen.
     *
     * <p>Always positional, including for a single image, because "Image 1 of 1" tells someone who
     * cannot see the screen that there is nothing to swipe to - which the hidden visual counter
     * cannot. A photo is never announced by its filename: a forty-character camera timestamp read
     * aloud is cost without information, and the position is what actually locates a person in a
     * set. A document keeps its name, because for a document the name is the information.
     */
    public String descriptionAt(int position) {
        Item item = at(position);
        if (item == null) return "";
        String place = "Image " + (position + 1) + " of " + items.size();
        if (isViewableImage(item.kind)) return place;
        return item.label.isEmpty() ? place : place + ", " + item.label;
    }

    public String currentDescription() { return descriptionAt(index); }

    /** What the remove control is called, so which image is being removed is never ambiguous. */
    public String removeDescription() {
        if (isEmpty()) return "Remove image";
        return items.size() <= 1
                ? "Remove this image"
                : "Remove image " + (index + 1) + " of " + items.size();
    }

    private int clamp(int position) {
        if (items.isEmpty()) return 0;
        if (position < 0) return 0;
        return position >= items.size() ? items.size() - 1 : position;
    }
}
