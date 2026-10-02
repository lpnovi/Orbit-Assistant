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
            .multipleImages(true)
            .offline(false)
            .needsCredentials(true)
            .reasoningLevels(true)
            .hostedWebSearch(true)
            // The account-backed Codex path publishes its hosted-search calls as stream events
            // carrying the pages it consulted, which is what a sourced picture has to be able to
            // point at. Orbit reads those events; it does not infer sources from answer text.
            .richWebMedia(true)
            .routinePlanning(true)
            // The ChatGPT path speaks a Responses-shaped event stream, which defines
            // user-facing reasoning-summary events. Whether the account backend behind it
            // honours a summary request is observed at runtime by ReasoningSummarySupport;
            // this states only that the protocol carries them and Orbit knows how to read one.
            .reasoningSummaries(true)
            .build();

    @Override public String id() { return Prefs.PROVIDER_CHATGPT; }

    @Override public String displayName() { return "ChatGPT"; }

    @Override public String description() {
        return "Your ChatGPT account. Currently Orbit's fullest feature set.";
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
        ChatGptClient.send(context, request.prompt, request.screenText, request.images,
                request.history, request.selection, request.explicitAttachment,
                request.notificationContext, request.memoryContext, request.trustedTaskContext,
                request.keptContext,
                request.thinkingUpdates, callback);
    }

    @Override public void plan(Context context, String planningPrompt, AiSelection selection,
                               AssistantClient.PlanCallback callback) {
        if (!ChatGptAuth.isSignedIn(context)) {
            callback.onError(SIGN_IN_ERROR);
            return;
        }
        ChatGptClient.plan(context, planningPrompt, selection, callback);
    }

    @Override public boolean supportsCompletion(Context context) {
        return ChatGptAuth.isSignedIn(context);
    }

    /**
     * Always {@link AiSelections#SMART_VAULT_ENRICHMENT} (GPT-6 Luna, Low): a title and three
     * topics never need a deep model, and this runs unattended for many items. A deliberate,
     * documented internal choice rather than the chat's selection.
     */
    @Override public void complete(Context context, String instructions, String prompt,
                                   AssistantClient.PlanCallback callback) {
        if (!ChatGptAuth.isSignedIn(context)) {
            callback.onError(SIGN_IN_ERROR);
            return;
        }
        ChatGptClient.complete(context, instructions, prompt,
                AiSelections.SMART_VAULT_ENRICHMENT, callback);
    }
}
