package com.orbit.assistant;

/**
 * Geometry for the Side-button overlay's pull-open gesture.
 *
 * <p>The overlay's conversation area carries the stretch: the sheet itself is bottom-anchored and
 * sized to its contents, so giving the conversation more height pulls the sheet's top edge upward
 * while its bottom stays put, and the header, composer, and handle keep their natural sizes. These
 * are pure functions of the current finger offset so the geometry is driven directly by touch
 * rather than by an animation, and so the mapping can be tested on its own.
 */
final class OverlayStretch {
    /** Resting corner radius of the compact sheet, in dp. */
    static final float SHEET_CORNER_DP = 30f;
    /** How much of the corner rounding is traded away by the time the sheet is fully open. */
    private static final float CORNER_FLATTEN = 0.88f;

    private OverlayStretch() {}

    /**
     * Conversation height for a finger that has moved {@code dy} pixels from where it started,
     * negative being upward.
     *
     * <p>Upward movement maps one-to-one onto extra height, so the top edge tracks the finger
     * instead of lagging behind it, and simply clamps once the sheet has nowhere left to grow.
     * Downward movement returns to the resting height, which is what lets the sheet contract again
     * mid-gesture and prevents it from latching open after it has once been pulled up.
     */
    static int stretchedHeight(int baseHeight, int maxHeight, float dy) {
        if (baseHeight <= 0) return baseHeight;
        int ceiling = Math.max(baseHeight, maxHeight);
        if (dy >= 0f) return baseHeight;
        long grown = baseHeight + Math.round(-dy);
        if (grown > ceiling) return ceiling;
        return (int) grown;
    }

    /** How far open the sheet is, 0 at rest and 1 fully pulled open. */
    static float progress(int baseHeight, int maxHeight, int currentHeight) {
        int span = maxHeight - baseHeight;
        if (span <= 0) return currentHeight > baseHeight ? 1f : 0f;
        float raw = (currentHeight - baseHeight) / (float) span;
        return Math.max(0f, Math.min(1f, raw));
    }

    /**
     * Corner radius in dp for the given progress, easing the compact sheet toward the squared-off
     * geometry of the full chat it is being pulled into.
     */
    static float cornerRadiusDp(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        return SHEET_CORNER_DP * (1f - CORNER_FLATTEN * clamped);
    }

    /**
     * Largest conversation height that still fits on screen, given everything else the sheet has
     * to show. Never smaller than the resting height, so a cramped screen simply does not stretch.
     */
    static int maxConversationHeight(int rootHeight, int sheetOtherHeight, int verticalMargins,
                                     int baseHeight) {
        int available = rootHeight - sheetOtherHeight - verticalMargins;
        return Math.max(baseHeight, available);
    }
}
