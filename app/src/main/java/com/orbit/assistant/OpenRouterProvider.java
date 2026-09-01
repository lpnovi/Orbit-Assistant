package com.orbit.assistant;

import android.content.Context;

/**
 * OpenRouter, as a configuration shell.
 *
 * <p>The provider architecture is ready for it and the API key can already be stored securely,
 * but its chat path deliberately does not run in this release: shipping a half-working chat
 * provider would be worse than shipping none. {@link #selectable} therefore stays false, which
 * keeps the router from ever sending a real turn here, and the management UI presents it as
 * experimental setup for an upcoming 0.7.7 update.
 */
final class OpenRouterProvider implements AiProvider {

    private static final AiCapabilities CAPABILITIES = AiCapabilities.builder()
            .streaming(true)
            .deviceActions(true)
            .images(true)
            // Chat does not run here at all, so there is nothing to claim.
            .multipleImages(false)
            .offline(false)
            .needsCredentials(true)
            .reasoningLevels(true)
            .hostedWebSearch(false)
            .routinePlanning(true)
            // Chat does not run here at all, so there is no stream to carry a summary and
            // nothing to claim. Unchanged by this release: OpenRouter stays setup-only.
            .reasoningSummaries(false)
            .build();

    @Override public String id() { return Prefs.PROVIDER_OPENROUTER; }

    @Override public String displayName() { return "OpenRouter"; }

    @Override public String description() {
        return "Many model families through one account. Chat arrives in an upcoming Orbit update.";
    }

    @Override public AiCapabilities capabilities() { return CAPABILITIES; }

    @Override public Status status(Context context) {
        return Status.COMING_SOON;
    }

    @Override public String statusDetail(Context context) {
        return SecureStore.hasOpenRouterKey(context)
                ? "Key saved · chat arrives in an upcoming update"
                : "Experimental · setup only";
    }

    @Override public boolean selectable(Context context) { return false; }

    @Override public void send(Context context, AiRequest request,
                               AssistantClient.Callback callback) {
        callback.onError("OpenRouter chat is not available yet. Choose ChatGPT or Orbit Local as the active provider.");
    }

    @Override public void plan(Context context, String planningPrompt, String intelligenceMode,
                               AssistantClient.PlanCallback callback) {
        callback.onError("OpenRouter is not available yet. Choose ChatGPT as the active provider.");
    }
}
