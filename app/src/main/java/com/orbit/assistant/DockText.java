package com.orbit.assistant;

/**
 * The words Orbit Dock's small answer preview shows: plain text, one paragraph, no Markdown marks.
 * While an answer streams the preview follows its newest words; once it is finished it shows how
 * the answer opens, and a tap opens the whole thing.
 */
final class DockText {
    /** About three preview lines on a phone. The view ellipsizes whatever still overflows. */
    static final int TAIL_CHARS = 160;
    static final String ELLIPSIS = String.valueOf((char) 0x2026);

    private DockText() {}

    /** The finished answer (or a stopped one), from its start. */
    static String opening(String raw) {
        return plain(raw);
    }

    /** The newest words of an answer still being written, led by an ellipsis once it is long. */
    static String tail(String raw) {
        if (raw == null) return "";
        // Only the end is ever shown, so only the end is cleaned: a long answer costs the same
        // per update as a short one.
        String recent = raw.length() > TAIL_CHARS * 4 ? raw.substring(raw.length() - TAIL_CHARS * 4) : raw;
        String text = plain(recent);
        if (text.length() <= TAIL_CHARS && recent.length() == raw.length()) return text;
        if (text.length() > TAIL_CHARS) text = text.substring(text.length() - TAIL_CHARS);
        // Start on a whole word.
        int space = text.indexOf(' ');
        if (space > 0 && space < 24) text = text.substring(space + 1);
        return ELLIPSIS + text;
    }

    private static String plain(String raw) {
        return OrbitMarkdown.toSpeechText(SourceLinkUtil.displayText(raw))
                .replaceAll("\\s+", " ").trim();
    }
}
