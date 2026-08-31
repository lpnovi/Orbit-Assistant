package com.orbit.assistant;

import java.util.Locale;

/**
 * Whether a reply-drafting turn produced something sendable, or a question back to the user.
 *
 * <h2>The bug this exists to end</h2>
 *
 * <p>A device test drafted a reply to a Discord group chat. Orbit could not tell which participant
 * the user was, so it correctly asked: <em>"Which participant are you in this chat, Little Lu or
 * Nimpy Impy?"</em> The overlay put <b>Copy</b>, <b>Use in chat</b> and <b>Regenerate</b> underneath
 * it — offering to send Orbit's own question to the group.
 *
 * <p>Those are two different kinds of output and the UI had no way to tell them apart, because
 * nothing ever said which one it was. So the model is asked to say, on a contract Orbit writes and
 * Orbit strips.
 *
 * <h2>Why not a heuristic</h2>
 *
 * <p>The obvious rule — "it ends in a question mark, so it is a question" — is wrong in the one case
 * that matters. <em>"Do you still want to go tonight?"</em> is a perfectly good drafted message.
 * Length, leading "which"/"who", and phrase matching all fail the same way. There is no reliable
 * signal in the prose, so the classification is carried explicitly instead of guessed.
 *
 * <h2>Failing safe</h2>
 *
 * <p>{@link Kind#UNKNOWN} is what an unmarked reply gets, and it is treated as "not known to be
 * sendable": no insert control appears. That is deliberately the conservative direction. The cost of
 * being wrong the other way is Orbit offering to send a question it asked the user into somebody
 * else's conversation, and Copy and Regenerate still work in every case.
 */
public final class ReplyDraftOutcome {

    /** What the assistant turn actually is. */
    public enum Kind {
        /** A message the user could send. Only this may offer an insert control. */
        DRAFT,
        /** A question Orbit is asking the user. Never sendable. */
        CLARIFICATION,
        /** No marker, so Orbit does not know. Treated as not sendable. */
        UNKNOWN
    }

    /**
     * The markers, in a form no ordinary sentence produces.
     *
     * <p>Bracketed rather than a bare {@code DRAFT:} prefix, because a legitimate drafted message
     * can begin "Draft: …" and a legitimate clarification can begin "Clarification needed …".
     */
    static final String DRAFT_MARKER = "[[ORBIT_DRAFT]]";
    static final String CLARIFY_MARKER = "[[ORBIT_ASK]]";

    public final Kind kind;
    /** The reply with the marker removed. This is what the user reads and what Orbit stores. */
    public final String text;

    private ReplyDraftOutcome(Kind kind, String text) {
        this.kind = kind;
        this.text = text == null ? "" : text;
    }

    public boolean isSendableDraft() { return kind == Kind.DRAFT; }

    /**
     * Reads and removes the marker.
     *
     * <p>Tolerant about where the marker sits, because a model will occasionally put a newline or a
     * space in front of it, and strict about what counts as one. Anything unmarked comes back as
     * {@link Kind#UNKNOWN} with its text untouched.
     */
    public static ReplyDraftOutcome parse(String raw) {
        if (raw == null) return new ReplyDraftOutcome(Kind.UNKNOWN, "");
        String value = raw.trim();
        if (value.isEmpty()) return new ReplyDraftOutcome(Kind.UNKNOWN, "");

        String upper = value.toUpperCase(Locale.US);
        if (upper.startsWith(DRAFT_MARKER)) {
            return new ReplyDraftOutcome(Kind.DRAFT, value.substring(DRAFT_MARKER.length()).trim());
        }
        if (upper.startsWith(CLARIFY_MARKER)) {
            return new ReplyDraftOutcome(Kind.CLARIFICATION,
                    value.substring(CLARIFY_MARKER.length()).trim());
        }
        return new ReplyDraftOutcome(Kind.UNKNOWN, value);
    }

    /**
     * Removes a marker wherever it appears, without classifying.
     *
     * <p>A safety net for any path that renders text without going through {@link #parse}: a marker
     * must never reach the screen or the stored conversation, whatever else happens.
     */
    public static String strip(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String value = raw;
        for (String marker : new String[]{DRAFT_MARKER, CLARIFY_MARKER}) {
            int at = indexOfIgnoreCase(value, marker);
            while (at >= 0) {
                value = value.substring(0, at) + value.substring(at + marker.length());
                at = indexOfIgnoreCase(value, marker);
            }
        }
        return value.trim();
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toUpperCase(Locale.US).indexOf(needle.toUpperCase(Locale.US));
    }

    /**
     * The contract, written into the trusted task context for a reply-drafting turn.
     *
     * <p>Trusted task context rather than the screen or the user's own words: it is Orbit-authored,
     * per-request, already persisted with the request, and already reaches every provider. The
     * instruction is deliberately short and mechanical, because the only thing being asked for is a
     * prefix.
     */
    public static String contractInstruction() {
        return "This is a reply-drafting turn. Begin your response with exactly one marker and "
                + "nothing before it. Use " + DRAFT_MARKER + " when what follows is the message the "
                + "user should send in the conversation on their screen. Use " + CLARIFY_MARKER
                + " when you are instead asking the user a question before you can draft it, for "
                + "example about which participant they are. The marker is removed before the user "
                + "sees the reply, so write the reply exactly as you otherwise would after it. Never "
                + "use both, and never put a marker anywhere except the very start.";
    }
}
