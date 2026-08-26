package com.orbit.assistant.local;

/**
 * Streamed output from one Orbit Local generation.
 *
 * <p>Oneway throughout: the component must never block on Orbit's process, and a slow or dead
 * client must not be able to stall inference. Exactly one of {@link #onDone} or {@link #onError}
 * ends a generation.
 */
oneway interface IOrbitLocalCallback {

    /**
     * Cumulative text so far, matching how Orbit's delta pipeline already expects partials.
     */
    void onPartial(String cumulativeText);

    /** Generation finished normally, including when it was stopped early by the user. */
    void onDone(String fullText);

    /** Generation failed. The message is shown as an Orbit Local error, never a cloud fallback. */
    void onError(String message);
}
