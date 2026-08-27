package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The request pipeline's own diagnostics, for the two duplication failures 0.7.7.7 closes:
 * one gesture producing two submissions, and one submission producing two answers.
 *
 * <p>Everything here is shape, never substance. What a person typed, what they said, what Orbit
 * answered, and which conversation any of it belongs to are all absent. A line records where a
 * gesture came from, whether the gate accepted it, and a shortened copy of Orbit's own randomly
 * generated request id, which is what makes a lifecycle readable end to end without identifying
 * anything.
 *
 * <p>Live events go into {@link ComposerTrace}, which is the buffer the hidden Diagnostics screen
 * already copies for typing problems. Running totals go into the diagnostics store so the report
 * can show them without the buffer needing to still hold the events.
 */
public final class RequestTrace {
    private static final String KEY_ACCEPTED = "submissions_accepted";
    private static final String KEY_SUPPRESSED = "submissions_suppressed";
    private static final String KEY_SOURCE = "submission_source";
    private static final String KEY_REASON = "submission_suppressed_reason";
    private static final String KEY_COMMITTED = "completions_committed";
    private static final String KEY_IGNORED = "completions_ignored";

    private RequestTrace() {}

    /** A send gesture, and what the gate decided about it. */
    static void submission(Context c, SubmissionGate.Decision decision) {
        if (decision == null) return;
        ComposerTrace.event("submit." + (decision.accepted ? "accepted" : "suppressed")
                + " src=" + safeToken(decision.source)
                + (decision.accepted ? "" : " reason=" + safeToken(decision.reason)));
        if (c == null) return;
        SharedPreferences prefs = DiagnosticStore.prefs(c);
        SharedPreferences.Editor edit = prefs.edit().putString(KEY_SOURCE, safeToken(decision.source));
        if (decision.accepted) {
            edit.putInt(KEY_ACCEPTED, prefs.getInt(KEY_ACCEPTED, 0) + 1);
        } else {
            edit.putInt(KEY_SUPPRESSED, prefs.getInt(KEY_SUPPRESSED, 0) + 1)
                    .putString(KEY_REASON, safeToken(decision.reason));
        }
        edit.apply();
    }

    /** One request's durable lifecycle: queued, running, completed, failed, cancelled. */
    public static void lifecycle(String requestId, String stage) {
        ComposerTrace.event("request." + safeToken(stage) + " id=" + shortId(requestId));
    }

    /** A surface attaching to or leaving a request it does not own. */
    public static void listener(String requestId, boolean attached) {
        ComposerTrace.event("request.listener-" + (attached ? "attach" : "detach")
                + " id=" + shortId(requestId));
    }

    /**
     * Whether an answer was actually written, or refused because this request had already
     * finished. The second case is the one that proves the completion invariant is holding.
     */
    static void completion(Context c, String requestId, boolean committed, String reason) {
        ComposerTrace.event("request." + (committed ? "completion-committed" : "completion-ignored")
                + " id=" + shortId(requestId)
                + (committed ? "" : " reason=" + safeToken(reason)));
        if (c == null) return;
        SharedPreferences prefs = DiagnosticStore.prefs(c);
        String key = committed ? KEY_COMMITTED : KEY_IGNORED;
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    /** Enough of a request id to follow one turn through the trace, and no more. */
    static String shortId(String requestId) {
        if (requestId == null || requestId.isEmpty()) return "none";
        String compact = requestId.replace("-", "");
        return compact.length() <= 8 ? compact : compact.substring(0, 8);
    }

    /**
     * Trace values are Orbit's own vocabulary, never user content. This enforces that even if a
     * future caller passes something unexpected.
     */
    private static String safeToken(String value) {
        if (value == null || value.trim().isEmpty()) return "none";
        String trimmed = value.trim();
        if (trimmed.length() > 32) trimmed = trimmed.substring(0, 32);
        return trimmed.replaceAll("[^A-Za-z0-9_.-]", "-");
    }
}
