package com.orbit.assistant;

import android.graphics.Bitmap;

/** One explicit composer attachment shared by Orbit's full chat and assistant sheet. */
public final class ComposerAttachment {
    public final String kind;
    public final String label;
    public final String contextText;
    public final Bitmap image;

    public ComposerAttachment(String kind, String label, String contextText, Bitmap image) {
        this.kind = kind == null ? "" : kind;
        this.label = label == null || label.trim().isEmpty() ? "Attachment" : label;
        this.contextText = contextText == null ? "" : contextText;
        this.image = image;
    }

    public boolean isVisual() { return image != null; }
}
