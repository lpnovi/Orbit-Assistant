package com.orbit.assistant;

/** Page navigation state for the native PDF viewer. */
public final class DocumentViewerModel {
    private final int pageCount;
    private int page;

    public DocumentViewerModel(int pageCount, int initialPage) {
        this.pageCount = Math.max(0, pageCount);
        this.page = clamp(initialPage);
    }

    public int pageCount() { return pageCount; }
    public int page() { return page; }
    public boolean hasPrevious() { return page > 0; }
    public boolean hasNext() { return page + 1 < pageCount; }
    public String counterText() {
        return pageCount <= 0 ? "" : "Page " + (page + 1) + " of " + pageCount;
    }
    public boolean moveTo(int requested) {
        int next = clamp(requested);
        if (next == page) return false;
        page = next;
        return true;
    }
    public boolean next() { return moveTo(page + 1); }
    public boolean previous() { return moveTo(page - 1); }

    private int clamp(int value) {
        if (pageCount <= 0) return 0;
        if (value < 0) return 0;
        return Math.min(value, pageCount - 1);
    }
}
