package com.orbit.assistant;

import android.graphics.Bitmap;

import java.util.concurrent.atomic.AtomicLong;

/** One explicit composer attachment shared by Orbit's full chat and assistant sheet. */
public final class ComposerAttachment {
    private static final AtomicLong SEQUENCE = new AtomicLong(1L);

    /**
     * Identity for one attachment inside a composer, unique for the life of the process.
     *
     * <p>A composer can now hold several attachments at once, and removing one has to mean
     * removing exactly that one. Nothing else here is usable as identity: two photos from the same
     * Samsung burst share a label, two files copied from different folders share a filename, and a
     * decoded Bitmap can be the identical object twice over. So the id is generated, never derived
     * from anything the user supplied and never shown to them.
     */
    public final String id;
    public final String kind;
    public final String label;
    public final String contextText;
    public final Bitmap image;
    /** Exact local PDF bytes for the native viewer, or null for non-document context. */
    public final DocumentReference document;
    /**
     * A short second line for the card, or empty.
     *
     * <p>"Page 5 of 388" under a document's title, rather than glued onto it with a separator. A
     * page attachment has two facts to state and squeezing them into one ellipsized line is what
     * made the page context read as an abstract label instead of as something attached.
     */
    public final String detail;
    /**
     * How much of the attachment's content Orbit actually managed to load.
     *
     * <p>Recorded rather than inferred, because the wording Orbit uses when the user sends a
     * document with no message of their own has to match what was really loaded. Saying "analyze
     * this PDF preview" about a document whose full text was extracted is untrue in the direction
     * that matters: it understates what Orbit has, and invites the model to hedge about pages it
     * can actually read.
     */
    public static final String CONTENT_UNKNOWN = "";
    /** Every page's text was extracted and is in the request. */
    public static final String CONTENT_FULL_TEXT = "full_text";
    /** Text was extracted but the attachment limit cut it short. */
    public static final String CONTENT_PARTIAL_TEXT = "partial_text";
    /** Little or no extractable text; only a rendering was attached. */
    public static final String CONTENT_VISUAL_ONLY = "visual_only";
    public final String contentState;

    public ComposerAttachment(String kind, String label, String contextText, Bitmap image) {
        this(kind, label, contextText, image, null, "", CONTENT_UNKNOWN);
    }

    public ComposerAttachment(String kind, String label, String contextText, Bitmap image,
                              DocumentReference document) {
        this(kind, label, contextText, image, document, "", CONTENT_UNKNOWN);
    }

    public ComposerAttachment(String kind, String label, String contextText, Bitmap image,
                              DocumentReference document, String detail, String contentState) {
        this.id = "att-" + SEQUENCE.getAndIncrement();
        this.kind = kind == null ? "" : kind;
        this.label = label == null || label.trim().isEmpty() ? "Attachment" : label;
        this.contextText = contextText == null ? "" : contextText;
        this.image = image;
        this.document = document;
        this.detail = detail == null ? "" : detail.trim();
        this.contentState = contentState == null ? CONTENT_UNKNOWN : contentState;
    }

    public boolean isVisual() { return image != null; }
    public boolean isDocument() { return document != null && document.isUsable(); }
    public boolean hasDetail() { return !detail.isEmpty(); }

    /** True when this attachment is one exact page rather than a whole document. */
    public boolean isDocumentPage() {
        return "pdf_page".equals(kind) || (isDocument() && document.namesPage());
    }

    /**
     * One exact page, staged from the document viewer.
     *
     * <p>Carries the page's text, the page as an image, and the reference needed to reopen the
     * document at that page — one attachment holding everything, because the user attached one
     * thing. Splitting the visual half into a second card would show them a mysterious image
     * alongside the page they actually chose.
     */
    public static ComposerAttachment documentPage(DocumentPageContext page) {
        if (page == null || !page.isUsable()) return null;
        return new ComposerAttachment("pdf_page", page.displayLabel(), page.requestContext(),
                page.pageImage, page.document, page.pageLabel(),
                page.hasText() ? CONTENT_FULL_TEXT : CONTENT_VISUAL_ONLY);
    }
}
