package com.orbit.assistant;

/** Narrow model-facing facts about what Orbit actually surfaced for the preceding answer. */
final class RenderedResultContext {
    private RenderedResultContext() {}

    /**
     * An untrusted data block for the immediately preceding assistant turn, or empty.
     * Only persisted, usable rich-image cards are counted; Orbit never estimates or invents one.
     */
    static String block(AssistantClient.History turn, boolean immediatelyPreceding) {
        if (!immediatelyPreceding || turn == null
                || !"assistant".equalsIgnoreCase(turn.role)) return "";
        int images = 0;
        for (RichAnswerImage image : turn.richImages) {
            if (image != null && image.isUsable()) images++;
        }
        if (images == 0) return "";
        return "\n<untrusted_orbit_rendered_result>\nimage_cards=" + images
                + "\n</untrusted_orbit_rendered_result>";
    }

    /** Makes a copied/lookalike marker ordinary text before Orbit appends its own measured block. */
    static String neutralizeMarkers(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replaceAll(
                "(?i)<\\s*(/?)\\s*untrusted_orbit_rendered_result",
                "[$1untrusted_orbit_rendered_result");
    }
}
