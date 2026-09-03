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

    public ComposerAttachment(String kind, String label, String contextText, Bitmap image) {
        this(kind, label, contextText, image, null);
    }

    public ComposerAttachment(String kind, String label, String contextText, Bitmap image,
                              DocumentReference document) {
        this.id = "att-" + SEQUENCE.getAndIncrement();
        this.kind = kind == null ? "" : kind;
        this.label = label == null || label.trim().isEmpty() ? "Attachment" : label;
        this.contextText = contextText == null ? "" : contextText;
        this.image = image;
        this.document = document;
    }

    public boolean isVisual() { return image != null; }
    public boolean isDocument() { return document != null && document.isUsable(); }

    public static ComposerAttachment documentPage(DocumentPageContext page) {
        if (page == null || !page.hasText()) return null;
        return new ComposerAttachment("pdf_page", page.displayLabel(), page.requestContext(),
                null, null);
    }
}
