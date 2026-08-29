package com.orbit.assistant;

/**
 * Which code path tried to finish a request.
 *
 * <p>Beta 1's request-id guard made a duplicate completion harmless, and on the device it worked:
 * one completion attempt was refused and no second answer appeared. But the diagnostics could only
 * say that <em>something</em> had been refused, which is not enough to find out what. This names
 * the caller so the next occurrence identifies itself.
 *
 * <p>The list is short because the architecture is: {@link OrbitRequestManager#completeIfNotCancelled}
 * is the only gate, and only {@link OrbitRequestWorker} calls it. Listeners are deliberately not
 * represented here — a surface attaching to a request is purely observational and has no path to
 * persistence at all, so an "attached listener" completion is not a category that exists. Nothing
 * is invented for a path Orbit does not have; {@link #UNKNOWN} exists so a future caller that
 * forgets to identify itself is still visible rather than silently mislabelled.
 *
 * <p>These are Orbit's own tokens. None of them derives from anything the user typed, said, or
 * was told.
 */
public enum CompletionSource {

    /** The worker persisting an answer: a model reply, or a locally answered one such as weather. */
    WORKER_RESPONSE("worker-response"),

    /** The worker persisting a visible failure after its retries were exhausted. */
    WORKER_ERROR("worker-error"),

    /** A caller that did not identify itself. Should not appear; worth seeing if it does. */
    UNKNOWN("unknown");

    /** Short, stable, diagnostics-safe name. */
    public final String token;

    CompletionSource(String token) {
        this.token = token;
    }
}
