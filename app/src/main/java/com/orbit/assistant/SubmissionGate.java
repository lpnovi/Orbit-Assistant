package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single place a user's send gesture becomes an accepted Orbit submission.
 *
 * <p>Two separate real-device failures led here. In full chat one prompt was recorded as being
 * submitted twice about 450 ms apart, because {@code submit()} had no gate of any kind: the send
 * button, the keyboard's Send key, and the voice controller's final transcript could each start a
 * turn, and nothing stopped a second one from starting immediately after the first. The Side-button
 * overlay happened to be protected only because it checked a {@code busy} flag it kept for its own
 * UI, which is not the same thing as a submission rule.
 *
 * <p>Three defences, in order of how structural they are:
 *
 * <ol>
 *   <li>An in-flight claim. Between accepting a submission and enqueuing its request, no other
 *       submission for that conversation is accepted at all. This closes the button/keyboard race
 *       and any re-entrant callback.</li>
 *   <li>A durable identity check. If the conversation already has a queued or running request
 *       carrying the same prompt, this one is the same turn arriving twice. This survives Activity
 *       recreation and process death, because it reads {@link PendingRequestStore} rather than
 *       memory.</li>
 *   <li>A short timing guard, {@link #DUPLICATE_WINDOW_MS}. Secondary only, and deliberately
 *       short-lived: sending the same words again later is something people genuinely do, and must
 *       keep working.</li>
 * </ol>
 *
 * <p>No prompt text is stored beyond a normalised copy held in memory for the length of the timing
 * window, and none of it is ever written to diagnostics.
 */
public final class SubmissionGate {

    /** How long an identical prompt is treated as the same gesture rather than a new intention. */
    public static final long DUPLICATE_WINDOW_MS = 1500L;

    public static final String SOURCE_BUTTON = "button";
    public static final String SOURCE_IME = "ime";
    public static final String SOURCE_VOICE = "voice";
    public static final String SOURCE_OVERLAY = "overlay";
    public static final String SOURCE_OVERLAY_VOICE = "overlay-voice";

    public static final String REASON_IN_FLIGHT = "already-submitting";
    public static final String REASON_ACTIVE_REQUEST = "identical-request-active";
    public static final String REASON_WINDOW = "duplicate-window";
    public static final String REASON_EMPTY = "empty";

    /** The gate's answer for one send gesture. */
    public static final class Decision {
        public final boolean accepted;
        public final String reason;
        public final String source;

        Decision(boolean accepted, String reason, String source) {
            this.accepted = accepted;
            this.reason = reason == null ? "" : reason;
            this.source = source == null ? "" : source;
        }
    }

    private static final class Recent {
        String normalized = "";
        long acceptedAt;
        boolean claimed;
    }

    private static final Map<String, Recent> STATE = new HashMap<>();

    private SubmissionGate() {}

    // ---- the gate ---------------------------------------------------------------------------

    /**
     * Offers one send gesture. An accepted decision holds the conversation's claim, which the
     * caller must release with {@link #settle} once the request has been enqueued.
     */
    public static synchronized Decision offer(Context c, String conversationId, String text,
                                              String source) {
        return offer(activePrompts(c, conversationId), conversationId, text, source,
                System.currentTimeMillis(), c);
    }

    /** The rule itself, with the durable state supplied, so it can be reasoned about directly. */
    public static synchronized Decision offer(List<String> activePrompts, String conversationId,
                                              String text, String source, long now) {
        return offer(activePrompts, conversationId, text, source, now, null);
    }

    private static synchronized Decision offer(List<String> activePrompts, String conversationId,
                                               String text, String source, long now, Context c) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return record(c, new Decision(false, REASON_EMPTY, source));

        String key = conversationId == null ? "" : conversationId;
        Recent recent = STATE.computeIfAbsent(key, ignored -> new Recent());

        if (recent.claimed) {
            return record(c, new Decision(false, REASON_IN_FLIGHT, source));
        }
        if (activePrompts != null) {
            for (String active : activePrompts) {
                if (normalize(active).equals(normalized)) {
                    return record(c, new Decision(false, REASON_ACTIVE_REQUEST, source));
                }
            }
        }
        if (normalized.equals(recent.normalized)
                && now - recent.acceptedAt >= 0
                && now - recent.acceptedAt < DUPLICATE_WINDOW_MS) {
            return record(c, new Decision(false, REASON_WINDOW, source));
        }

        recent.normalized = normalized;
        recent.acceptedAt = now;
        recent.claimed = true;
        return record(c, new Decision(true, "", source));
    }

    /**
     * Releases the conversation's claim once its request exists.
     *
     * <p>Always called after an accepted submission, including when enqueuing failed, so a
     * conversation can never be left permanently unable to send.
     */
    public static synchronized void settle(String conversationId) {
        Recent recent = STATE.get(conversationId == null ? "" : conversationId);
        if (recent != null) recent.claimed = false;
    }

    /** True while a submission has been accepted but its request has not been created yet. */
    public static synchronized boolean isClaimed(String conversationId) {
        Recent recent = STATE.get(conversationId == null ? "" : conversationId);
        return recent != null && recent.claimed;
    }

    static synchronized void resetForTest() { STATE.clear(); }

    // ---- helpers ----------------------------------------------------------------------------

    /** The prompts of every request still queued or running in this conversation. */
    private static List<String> activePrompts(Context c, String conversationId) {
        List<String> prompts = new ArrayList<>();
        if (c == null || conversationId == null) return prompts;
        for (PendingRequestStore.Item item
                : PendingRequestStore.activeForConversation(c, conversationId)) {
            if (item != null) prompts.add(item.prompt);
        }
        return prompts;
    }

    /**
     * Case- and whitespace-insensitive identity. Two gestures that produce the same words are the
     * same gesture, whether the keyboard or the speech recogniser produced them.
     */
    static String normalize(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
    }

    private static Decision record(Context c, Decision decision) {
        RequestTrace.submission(c, decision);
        return decision;
    }
}
