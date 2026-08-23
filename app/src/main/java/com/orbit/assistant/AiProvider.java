package com.orbit.assistant;

import android.content.Context;

/**
 * One AI backend Orbit can talk to.
 *
 * <p>This is the seam the 0.7.7 line is built on: chat, the Side-button overlay, Routines,
 * device actions, and background completion all reach a model through this contract instead of
 * through a specific client class. A provider owns its own transport, authentication, prompt
 * shape, and streaming; the rest of Orbit only sees {@link AiRequest} in and
 * {@link AssistantClient.Callback} out.
 *
 * <p>What a provider can and cannot do is declared once in {@link AiCapabilities} rather than
 * inferred from its id around the app. Surfaces ask {@code capabilities()} questions such as
 * "can this stream?", "does this work offline?", or "does this need credentials?" and never
 * need provider-specific knowledge.
 */
public interface AiProvider {

    /** How ready this provider is to serve a request right now. */
    enum Status {
        /** Configured and usable. */
        READY,
        /** Exists and is selectable, but needs sign-in or configuration first. */
        NEEDS_SETUP,
        /** Orbit Local without its model installed, or similar missing local resources. */
        NOT_INSTALLED,
        /** Present as a preview; its chat path intentionally does not run yet. */
        COMING_SOON,
        /** This device cannot run the provider at all. */
        UNSUPPORTED
    }

    /** Stable internal id, also the value stored in {@link Prefs#PROVIDER}. */
    String id();

    /** Short user-facing name, e.g. "ChatGPT" or "Orbit Local". */
    String displayName();

    /** One-line user-facing description for provider management UI. */
    String description();

    /** Fixed capability metadata. Never varies with sign-in state; that is {@link #status}. */
    AiCapabilities capabilities();

    Status status(Context context);

    /** Short human status line for management UI, e.g. "Connected" or "Model not installed". */
    String statusDetail(Context context);

    /**
     * True when the user may make this the active provider. A provider shipped as a setup-only
     * preview returns false so Orbit never routes chat to a backend that cannot answer.
     */
    boolean selectable(Context context);

    /**
     * Runs one normal conversation turn. Implementations report progress and results through the
     * callback and must never throw to the caller; a provider that is not ready answers with a
     * clear {@code onError} instead.
     */
    void send(Context context, AiRequest request, AssistantClient.Callback callback);

    /**
     * Runs one focused Routine-planning request, returning the raw model response for the caller
     * to validate. Providers whose models cannot plan reliably answer {@code onError} with a
     * plain explanation of which provider to use instead.
     */
    void plan(Context context, String planningPrompt, String intelligenceMode,
              AssistantClient.PlanCallback callback);
}
