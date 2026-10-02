package com.orbit.assistant;

import android.content.Context;

/** Anthropic Claude through the official Messages API and a user-supplied API key. */
final class AnthropicProvider implements AiProvider {
    private static final AiCapabilities CAPABILITIES = AiCapabilities.builder()
            .streaming(true).deviceActions(false).images(true).multipleImages(true)
            .offline(false).needsCredentials(true).reasoningLevels(true)
            .hostedWebSearch(false).routinePlanning(true).reasoningSummaries(false).build();

    @Override public String id() { return Prefs.PROVIDER_ANTHROPIC; }
    @Override public String displayName() { return "Anthropic Claude"; }
    @Override public String description() { return "Claude models through Anthropic's official developer API."; }
    @Override public AiCapabilities capabilities() { return CAPABILITIES; }
    @Override public Status status(Context context) {
        return SecureStore.hasAnthropicKey(context) ? Status.READY : Status.NEEDS_SETUP;
    }
    @Override public String statusDetail(Context context) {
        return SecureStore.hasAnthropicKey(context) ? "API key saved" : "API key required";
    }
    @Override public boolean selectable(Context context) { return true; }

    @Override public void send(Context context, AiRequest request, AssistantClient.Callback callback) {
        String key = SecureStore.loadAnthropicKey(context);
        if (key.isEmpty()) {
            callback.onError("Add an Anthropic API key in Settings > AI Providers first.");
            return;
        }
        ApiKeyProviderClient.send(context, id(), key, request, callback);
    }

    @Override public void plan(Context context, String prompt, AiSelection selection,
                               AssistantClient.PlanCallback callback) {
        String key = SecureStore.loadAnthropicKey(context);
        if (key.isEmpty()) { callback.onError("Add an Anthropic API key first."); return; }
        ApiKeyProviderClient.complete(context, id(), key, AiSelections.resolve(selection),
                "Return only the requested valid JSON. Do not add Markdown fences.", prompt,
                callback);
    }

    @Override public boolean supportsCompletion(Context context) {
        return SecureStore.hasAnthropicKey(context);
    }

    @Override public void complete(Context context, String instructions, String prompt,
                                   AssistantClient.PlanCallback callback) {
        String key = SecureStore.loadAnthropicKey(context);
        if (key.isEmpty()) { callback.onError("Add an Anthropic API key first."); return; }
        AiSelection selection = ModelLibraryStore.providerDefault(context, id());
        ApiKeyProviderClient.complete(context, id(), key, AiSelections.resolve(selection),
                instructions, prompt, callback);
    }
}
