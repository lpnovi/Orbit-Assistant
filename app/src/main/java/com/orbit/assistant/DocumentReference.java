package com.orbit.assistant;

/** Safe, durable metadata naming one locally retained PDF. */
public final class DocumentReference {
    /** {@link #page} when the reference names the document rather than a place inside it. */
    public static final int WHOLE_DOCUMENT = -1;

    public final String path;
    public final String label;
    public final int pageCount;
    /**
     * The page this reference was made at, or {@link #WHOLE_DOCUMENT}.
     *
     * <p>Part of the reference rather than a field beside it, because "the PDF" and "page 5 of the
     * PDF" are two different things to have attached, and a turn that recorded the second has to
     * reopen at page 5 rather than at the beginning. Conversations saved before this existed
     * recorded no page and load as whole-document references, which is exactly what they were.
     */
    public final int page;

    public DocumentReference(String path, String label, int pageCount) {
        this(path, label, pageCount, WHOLE_DOCUMENT);
    }

    public DocumentReference(String path, String label, int pageCount, int page) {
        this.path = path == null ? "" : path.trim();
        this.label = label == null || label.trim().isEmpty() ? "PDF" : label.trim();
        this.pageCount = Math.max(0, pageCount);
        this.page = page >= 0 && page < this.pageCount ? page : WHOLE_DOCUMENT;
    }

    public boolean isUsable() { return !path.isEmpty() && pageCount > 0; }

    /** True when this reference is about one page in particular. */
    public boolean namesPage() { return page != WHOLE_DOCUMENT; }

    /** Which page the viewer should open at. */
    public int openAt() { return namesPage() ? page : 0; }

    /** "Page 5 of 388", or the document's own size when no page is named. */
    public String pageLabel() {
        if (pageCount <= 0) return "";
        if (namesPage()) return "Page " + (page + 1) + " of " + pageCount;
        return pageCount + (pageCount == 1 ? " page" : " pages");
    }
}
