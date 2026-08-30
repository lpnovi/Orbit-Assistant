package com.orbit.assistant;

/**
 * Turns a provider's token-level reasoning-summary deltas into a status line a person can read.
 *
 * <p>A summary streams the way any model output does: a few characters at a time, many times a
 * second. Rendering that directly would give a status line that rewrites itself on every token,
 * which is visual noise rather than information, and would fire an accessibility announcement for
 * each one. This class stands between the stream and the UI and emits only when there is something
 * coherent and new to say.
 *
 * <p>Three rules produce that. Updates are spaced by at least {@link #MIN_INTERVAL_MS}, so a fast
 * stream cannot outrun the eye. A phrase must be either long enough to mean something or finished
 * at a real boundary before it is shown at all. And once a summary paragraph's opening line is
 * complete, that line is frozen for the rest of the paragraph: reasoning summaries typically open
 * with a short heading, so freezing it turns a growing paragraph into one stable phrase instead of
 * a caption that keeps rewriting itself.
 *
 * <p>Deliberately pure and clock-injected: no timers, no handlers, no Android types. It holds a
 * bounded buffer for one request's summary and is discarded with that request. Nothing here is
 * written anywhere — the text it returns goes to a view and nowhere else.
 */
final class ThinkingUpdateStream {

    /** The shortest gap between two visible status changes. */
    static final long MIN_INTERVAL_MS = 700L;
    /**
     * Below this a partial phrase is not worth showing on its own. It is ignored at a real
     * boundary, where a short phrase is a complete one rather than a fragment.
     */
    static final int MIN_EMIT_CHARS = 14;
    /** All that is ever retained of one summary paragraph. Nothing here grows without bound. */
    static final int MAX_BUFFER_CHARS = 600;

    private final StringBuilder buffer = new StringBuilder();
    /** The opening line, once the stream has proved it complete by moving past it. */
    private String frozenLine = "";
    private String lastEmitted = "";
    private long lastEmitAt = 0L;
    private boolean emittedAnything = false;

    /**
     * A new summary paragraph began. The buffer restarts, so the previous paragraph's text can
     * never blend into this one, but the emit spacing carries over: the throttle is about how fast
     * the user's eye is asked to move, not about where a paragraph happens to end.
     */
    void beginPart() {
        buffer.setLength(0);
        frozenLine = "";
    }

    /**
     * Accepts one delta and says what, if anything, should now be displayed.
     *
     * @return the phrase to show, or "" when this delta changes nothing the user should see yet.
     */
    String accept(String delta, long nowMs) {
        if (delta == null || delta.isEmpty()) return "";
        if (buffer.length() < MAX_BUFFER_CHARS) buffer.append(delta);
        return consider(nowMs, false);
    }

    /**
     * The paragraph finished. A complete phrase is worth showing even if it is short, but the
     * spacing still applies, so a burst of tiny paragraphs cannot flicker.
     */
    String finishPart(long nowMs) {
        return consider(nowMs, true);
    }

    private String consider(long nowMs, boolean complete) {
        String candidate = candidate();
        if (candidate.isEmpty()) return "";
        if (candidate.equals(lastEmitted)) return "";
        boolean boundary = complete || frozen() || endsAtBoundary();
        if (!boundary && candidate.length() < MIN_EMIT_CHARS) return "";
        // The first update of a request is immediate: waiting out an interval before the very
        // first thing Orbit has to say would only make the request feel slower.
        if (emittedAnything && nowMs - lastEmitAt < MIN_INTERVAL_MS) return "";
        lastEmitted = candidate;
        lastEmitAt = nowMs;
        emittedAnything = true;
        return candidate;
    }

    /** The display form of what has arrived so far: the opening line, sanitized and bounded. */
    private String candidate() {
        if (!frozenLine.isEmpty()) return frozenLine;
        String raw = buffer.toString();
        int newline = raw.indexOf('\n');
        if (newline >= 0) {
            String head = ThinkingUpdate.sanitize(raw.substring(0, newline));
            // A paragraph that opens with a blank line has not started yet; keep waiting rather
            // than freezing an empty heading.
            if (!head.isEmpty()) {
                frozenLine = head;
                return frozenLine;
            }
            // Drop the blank opening so the next line becomes the opening line.
            buffer.delete(0, newline + 1);
            raw = buffer.toString();
        }
        return ThinkingUpdate.sanitize(raw);
    }

    /** True once the opening line is settled and cannot change again for this paragraph. */
    private boolean frozen() { return !frozenLine.isEmpty(); }

    private boolean endsAtBoundary() {
        for (int i = buffer.length() - 1; i >= 0; i--) {
            char c = buffer.charAt(i);
            if (Character.isWhitespace(c)) continue;
            return c == '.' || c == '!' || c == '?' || c == ':' || c == ';';
        }
        return false;
    }
}
