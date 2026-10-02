package com.orbit.assistant;

import android.content.Context;

/**
 * OpenRouter (0.8.3.0-beta.6+): many model families through one OpenRouter account.
 *
 * <p>Connected either by Sign in with OpenRouter ({@link OpenRouterAuth}, the primary path) or by
 * an API key the user enters, which earlier versions could already store. Both live in the same
 * encrypted {@link SecureStore} slot, so a key saved before this release keeps working untouched.
 *
 * <p>Requests go through {@link ApiKeyProviderClient} and {@link ProviderRequestMapper}, the same
 * transport Anthropic and xAI use, never through {@code ChatGptClient}. Which models exist, which
 * read images, their context windows and their strengths all come from OpenRouter's catalog via
 * {@link ProviderCatalogRepository}; nothing is inferred from a model's name.
 *
 * <p>Capabilities are deliberately conservative. Orbit's device actions are a structured protocol
 * that only some providers' models have been validated against, so OpenRouter models do not run
 * them; they answer in text, like Anthropic and xAI. OpenRouter can offer server-side web search,
 * but Orbit does not invoke it, so none is claimed. OpenRouter usage is billed to the user's
 * OpenRouter account, so Auto may use it only after the user switches it on in
 * {@link AutoPermissions}; connecting never does.
 */
final class OpenRouterProvider implements AiProvider {

    private static final AiCapabilities CAPABILITIES = AiCapabilities.builder()
            .streaming(true)
            .deviceActions(false)
            // Per model: only models whose catalog entry lists image input receive images.
            .images(true)
            .multipleImages(true)
            .offline(false)
            .needsCredentials(true)
            .reasoningLevels(true)
            .hostedWebSearch(false)
            .routinePlanning(true)
            // Reasoning is excluded from every request, so there is never a summary to show.
            .reasoningSummaries(false)
            .build();

    @Override public String id() { return Prefs.PROVIDER_OPENROUTER; }

    @Override public String displayName() { return "OpenRouter"; }

    @Override public String description() {
        return "Many AI models through one OpenRouter account. Usage is billed to your OpenRouter credits.";
    }

    @Override public AiCapabilities capabilities() { return CAPABILITIES; }

    @Override public Status status(Context context) {
        return SecureStore.hasOpenRouterKey(context) ? Status.READY : Status.NEEDS_SETUP;
    }

    @Override public String statusDetail(Context context) {
        String source = SecureStore.openRouterKeySource(context);
        if (SecureStore.OPENROUTER_SOURCE_OAUTH.equals(source)) return "Connected with OpenRouter";
        if (SecureStore.OPENROUTER_SOURCE_MANUAL.equals(source)) return "Connected with API key";
        return "Not connected";
    }

    @Override public boolean selectable(Context context) { return true; }

    @Override public void send(Context context, AiRequest request,
                               AssistantClient.Callback callback) {
        String key = SecureStore.loadOpenRouterKey(context);
        if (key.isEmpty()) {
            callback.onError("OpenRouter is not connected. Sign in with OpenRouter in Settings > AI Providers.");
            return;
        }
        ApiKeyProviderClient.send(context, id(), key, request, callback);
    }

    @Override public void plan(Context context, String planningPrompt, AiSelection selection,
                               AssistantClient.PlanCallback callback) {
        String key = SecureStore.loadOpenRouterKey(context);
        if (key.isEmpty()) { callback.onError("Connect OpenRouter first."); return; }
        ApiKeyProviderClient.complete(context, id(), key, AiSelections.resolve(selection),
                "Return only the requested valid JSON. Do not add Markdown fences.", planningPrompt,
                callback);
    }

    @Override public boolean supportsCompletion(Context context) {
        return SecureStore.hasOpenRouterKey(context);
    }

    @Override public void complete(Context context, String instructions, String prompt,
                                   AssistantClient.PlanCallback callback) {
        String key = SecureStore.loadOpenRouterKey(context);
        if (key.isEmpty()) { callback.onError("Connect OpenRouter first."); return; }
        AiSelection selection = ModelLibraryStore.providerDefault(context, id());
        ApiKeyProviderClient.complete(context, id(), key, AiSelections.resolve(selection),
                instructions, prompt, callback);
    }
}
