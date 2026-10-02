package com.orbit.assistant;

import android.content.Context;

/** Grok through xAI's official OpenAI-compatible inference API and account model catalog. */
final class XaiProvider implements AiProvider {
    private static final AiCapabilities CAPABILITIES = AiCapabilities.builder()
            .streaming(true).deviceActions(false).images(true).multipleImages(true)
            .offline(false).needsCredentials(true).reasoningLevels(true)
            .hostedWebSearch(false).routinePlanning(true).reasoningSummaries(false).build();

    @Override public String id() { return Prefs.PROVIDER_XAI; }
    @Override public String displayName() { return "xAI / Grok"; }
    @Override public String description() { return "Grok models through xAI's official developer API."; }
    @Override public AiCapabilities capabilities() { return CAPABILITIES; }
    @Override public Status status(Context context) {
        return SecureStore.hasXaiKey(context) ? Status.READY : Status.NEEDS_SETUP;
    }
    @Override public String statusDetail(Context context) {
        return SecureStore.hasXaiKey(context) ? "API key saved" : "API key required";
    }
    @Override public boolean selectable(Context context) { return true; }

    @Override public void send(Context context, AiRequest request, AssistantClient.Callback callback) {
        String key = SecureStore.loadXaiKey(context);
        if (key.isEmpty()) {
            callback.onError("Add an xAI API key in Settings > AI Providers first.");
            return;
        }
        ApiKeyProviderClient.send(context, id(), key, request, callback);
    }

    @Override public void plan(Context context, String prompt, AiSelection selection,
                               AssistantClient.PlanCallback callback) {
        String key = SecureStore.loadXaiKey(context);
        if (key.isEmpty()) { callback.onError("Add an xAI API key first."); return; }
        ApiKeyProviderClient.complete(context, id(), key, AiSelections.resolve(selection),
                "Return only the requested valid JSON. Do not add Markdown fences.", prompt,
                callback);
    }

    @Override public boolean supportsCompletion(Context context) { return SecureStore.hasXaiKey(context); }

    @Override public void complete(Context context, String instructions, String prompt,
                                   AssistantClient.PlanCallback callback) {
        String key = SecureStore.loadXaiKey(context);
        if (key.isEmpty()) { callback.onError("Add an xAI API key first."); return; }
        AiSelection selection = ModelLibraryStore.providerDefault(context, id());
        ApiKeyProviderClient.complete(context, id(), key, AiSelections.resolve(selection),
                instructions, prompt, callback);
    }
}
