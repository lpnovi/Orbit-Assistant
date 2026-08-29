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
    private static final String KEY_IGNORED_DUPLICATE = "completions_ignored_duplicate";
    private static final String KEY_LAST_IGNORED = "completion_ignored_detail";
    private static final String KEY_LAST_IGNORED_AT = "completion_ignored_at";

    /** The request had already been answered. This is the case worth investigating. */
    static final String REASON_ALREADY_COMPLETED = "already-completed";
    /** The user stopped the request. Expected, and not a duplicate of anything. */
    static final String REASON_CANCELLED = "cancelled";

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

    /** An answer that was actually written. */
    static void completionCommitted(Context c, String requestId, CompletionSource source,
                                    int workAttempt) {
        ComposerTrace.event("request.completion-committed"
                + " id=" + shortId(requestId)
                + " src=" + token(source)
                + attemptEvent(workAttempt));
        if (c == null) return;
        SharedPreferences prefs = DiagnosticStore.prefs(c);
        prefs.edit().putInt(KEY_COMMITTED, prefs.getInt(KEY_COMMITTED, 0) + 1).apply();
    }

    /**
     * A completion that was refused, and enough about it to find out why next time.
     *
     * <p>Beta 1 counted these as one number labelled "already terminal", which turned out to be
     * two unrelated events wearing one label. Refusing a completion because the user pressed Stop
     * is ordinary and expected; refusing one because the request had already been answered is the
     * defence-in-depth case that says a second completion attempt really happened. A single
     * counter mixing them could never tell anyone which had occurred, so the duplicate case now
     * has its own count and the most recent refusal records where it came from.
     *
     * <p>Shape only, as everywhere else in this file: a shortened copy of Orbit's own random
     * request id, the name of the calling code path, WorkManager's attempt number, and the state
     * the request was already in. No prompt, no answer, no conversation, no calendar contents.
     */
    static void completionIgnored(Context c, String requestId, CompletionSource source,
                                  int workAttempt, String priorState, String reason) {
        ComposerTrace.event("request.completion-ignored"
                + " id=" + shortId(requestId)
                + " src=" + token(source)
                + attemptEvent(workAttempt)
                + " was=" + safeToken(priorState)
                + " reason=" + safeToken(reason));
        if (c == null) return;
        SharedPreferences prefs = DiagnosticStore.prefs(c);
        SharedPreferences.Editor edit = prefs.edit()
                .putInt(KEY_IGNORED, prefs.getInt(KEY_IGNORED, 0) + 1)
                .putString(KEY_LAST_IGNORED, token(source)
                        + " · req " + shortId(requestId)
                        + " · was " + safeToken(priorState)
                        + attemptSuffix(workAttempt)
                        + " · " + safeToken(reason))
                .putLong(KEY_LAST_IGNORED_AT, System.currentTimeMillis());
        if (REASON_ALREADY_COMPLETED.equals(reason)) {
            edit.putInt(KEY_IGNORED_DUPLICATE, prefs.getInt(KEY_IGNORED_DUPLICATE, 0) + 1);
        }
        edit.apply();
    }

    /** WorkManager's own retry counter, when the caller is a worker and therefore has one. */
    private static String attemptSuffix(int workAttempt) {
        return workAttempt < 0 ? "" : " · attempt " + workAttempt;
    }

    /** The same number in the live trace buffer's {@code key=value} vocabulary. */
    private static String attemptEvent(int workAttempt) {
        return workAttempt < 0 ? "" : " attempt=" + workAttempt;
    }

    private static String token(CompletionSource source) {
        return source == null ? CompletionSource.UNKNOWN.token : source.token;
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
