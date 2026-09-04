package com.orbit.assistant;

/**
 * What an attachment is called on screen, and what a screen reader is told about it.
 *
 * <p>Separated from the strip that draws it because the two questions are different. A photo
 * picked from Gallery arrives carrying a filename its owner never chose and never reads -
 * {@code Screenshot_20260830_211455_Orbit Assistant.jpg} - and on a strip that has to hold up to
 * ten items that name is pure cost: it is forty characters wide, it looks identical to the name
 * beside it, and it pushes the next thumbnail off the screen. The thumbnail already says what the
 * photo is. So a photo gets a short ordinal instead, and the space goes back to showing more
 * attachments.
 *
 * <p>A document is the opposite case and is treated as the opposite case. {@code syllabus.pdf} is
 * the only thing distinguishing it from {@code timetable.pdf}, there is no preview that could
 * carry that meaning instead, and replacing it with "File 1" would take information away rather
 * than noise. Documents keep their real names; they are only stopped from taking the whole strip.
 *
 * <p>Nothing here mutates an attachment. {@link ComposerAttachment#label} stays exactly what the
 * provider reported, because history, the text sent to the model, diagnostics, and the file itself
 * all still need the real name - only the strip's own caption is shortened.
 */
public final class AttachmentLabels {

    private AttachmentLabels() {}

    /**
     * True for an attachment whose filename carries nothing the thumbnail does not.
     *
     * <p>Deliberately keyed on the kind rather than on "has a bitmap". A screen capture also has a
     * bitmap, but its label is Orbit's own short caption - "Screen · Gmail" - which is both brief
     * and genuinely informative, so it is left alone.
     */
    public static boolean isPhoto(ComposerAttachment attachment) {
        if (attachment == null) return false;
        return "image".equals(attachment.kind) || "camera".equals(attachment.kind);
    }

    /** True for an attachment whose real filename is the only thing identifying it. */
    public static boolean isDocument(ComposerAttachment attachment) {
        if (attachment == null) return false;
        return "pdf".equals(attachment.kind) || "file_text".equals(attachment.kind);
    }

    /**
     * The caption drawn on the card.
     *
     * <p>Photos become {@code Photo 1}, {@code Photo 2}, {@code Photo 3} - numbered by their place
     * in the strip, so the caption and the ordering a user can see always agree. Everything else
     * keeps the label it arrived with.
     */
    public static String displayLabel(ComposerAttachment attachment, int position) {
        if (attachment == null) return "";
        if (isPhoto(attachment)) return "Photo " + position;
        return attachment.label;
    }

    /**
     * What a screen reader reads when it reaches the card.
     *
     * <p>The visible caption is shortened; this is not. A photo is announced as a photo and by its
     * place in the set, which is what a person navigating the strip actually needs - "Photo
     * attachment 2 of 4" locates them, where a read-aloud timestamp filename would not. A document
     * keeps its name, because for a document the name is the information.
     */
    public static String cardDescription(ComposerAttachment attachment, int position, int total) {
        if (attachment == null) return "";
        String place = total <= 1 ? "" : " " + position + " of " + total;
        if (isPhoto(attachment)) return "Photo attachment" + place;
        // A page attachment is announced as the two things it is: which document, and where in it.
        // The card's second line is not read separately, so it is spoken here instead.
        if (attachment.isDocumentPage()) {
            return "PDF page attachment" + place + ", " + attachment.label
                    + (attachment.hasDetail() ? ", " + attachment.detail : "");
        }
        if (isDocument(attachment)) {
            return leadingTypeWord(attachment) + " attachment" + place + ", " + attachment.label;
        }
        return total <= 1
                ? "Attachment: " + attachment.label
                : "Attachment " + position + " of " + total + ": " + attachment.label;
    }

    /** What the remove control is called, so "which one am I removing" is never ambiguous. */
    public static String removeDescription(ComposerAttachment attachment, int position, int total) {
        if (attachment == null) return "Remove attachment";
        String place = total <= 1 ? "" : " " + position;
        if (isPhoto(attachment)) return "Remove photo attachment" + place;
        if (attachment.isDocumentPage()) {
            return "Remove PDF page attachment" + place + ", " + attachment.label
                    + (attachment.hasDetail() ? ", " + attachment.detail : "");
        }
        if (isDocument(attachment)) {
            return "Remove " + typeWord(attachment) + " attachment" + place
                    + ", " + attachment.label;
        }
        return total <= 1
                ? "Remove " + attachment.label
                : "Remove " + attachment.label + ", attachment " + position + " of " + total;
    }

    /** "PDF" and "file" are what people call these; the internal kind names are never shown. */
    private static String leadingTypeWord(ComposerAttachment attachment) {
        return "pdf".equals(attachment.kind) ? "PDF" : "File";
    }

    private static String typeWord(ComposerAttachment attachment) {
        return "pdf".equals(attachment.kind) ? "PDF" : "file";
    }
}
