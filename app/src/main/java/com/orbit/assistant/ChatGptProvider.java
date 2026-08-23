package com.orbit.assistant;

import android.content.Context;

/**
 * The existing ChatGPT-account backend behind the provider contract.
 *
 * <p>Deliberately a thin adapter: {@link ChatGptAuth} and {@link ChatGptClient} keep owning the
 * device-code sign-in, token refresh, Codex streaming, hosted search, and the action envelope
 * exactly as before the provider layer existed. This class only states capabilities, reports
 * sign-in status, and forwards requests.
 */
final class ChatGptProvider implements AiProvider {
    static final String SIGN_IN_ERROR =
            "Sign in with ChatGPT in Orbit settings first. No API key is required for ChatGPT-account mode.";

    private static final AiCapabilities CAPABILITIES = AiCapabilities.builder()
            .streaming(true)
            .deviceActions(true)
            .images(true)
            .offline(false)
            .needsCredentials(true)
            .reasoningLevels(true)
            .hostedWebSearch(true)
            .routinePlanning(true)
            .build();

    @Override public String id() { return Prefs.PROVIDER_CHATGPT; }

    @Override public String displayName() { return "ChatGPT"; }

    @Override public String description() {
        return "Your ChatGPT account answers with full streaming, device actions, attachments, and web search.";
    }

    @Override public AiCapabilities capabilities() { return CAPABILITIES; }

    @Override public Status status(Context context) {
        return ChatGptAuth.isSignedIn(context) ? Status.READY : Status.NEEDS_SETUP;
    }

    @Override public String statusDetail(Context context) {
        return ChatGptAuth.isSignedIn(context) ? "Connected" : "Sign-in required";
    }

    @Override public boolean selectable(Context context) { return true; }

    @Override public void send(Context context, AiRequest request,
                               AssistantClient.Callback callback) {
        if (!ChatGptAuth.isSignedIn(context)) {
            callback.onError(SIGN_IN_ERROR);
            return;
        }
        ChatGptClient.send(context, request.prompt, request.screenText, request.screenshot,
                request.history, request.intelligenceMode, request.explicitAttachment,
                request.notificationContext, request.memoryContext, request.trustedTaskContext,
                callback);
    }

    @Override public void plan(Context context, String planningPrompt, String intelligenceMode,
                               AssistantClient.PlanCallback callback) {
        if (!ChatGptAuth.isSignedIn(context)) {
            callback.onError(SIGN_IN_ERROR);
            return;
        }
        ChatGptClient.plan(context, planningPrompt, intelligenceMode, callback);
    }
}
