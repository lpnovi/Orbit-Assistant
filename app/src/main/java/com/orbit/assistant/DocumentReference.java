package com.orbit.assistant;

/** Safe, durable metadata naming one locally retained PDF. */
public final class DocumentReference {
    public final String path;
    public final String label;
    public final int pageCount;

    public DocumentReference(String path, String label, int pageCount) {
        this.path = path == null ? "" : path.trim();
        this.label = label == null || label.trim().isEmpty() ? "PDF" : label.trim();
        this.pageCount = Math.max(0, pageCount);
    }

    public boolean isUsable() { return !path.isEmpty() && pageCount > 0; }
}
