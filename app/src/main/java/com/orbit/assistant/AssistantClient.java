package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.List;

public final class AssistantClient {
    public interface Callback {
        default void onDelta(String text) {}
        /**
         * A short, safe statement about what is happening right now, for display only.
         *
         * <p>Observational by construction. It carries no reply, claims no completion, and a
         * provider that never calls it behaves exactly as it did before this existed. Anything a
         * surface does in response must be limited to what it shows.
         *
         * <p>Never persisted, never sent back to a model, and never mixed into the answer: an
         * update is live commentary on a request in flight and dies with it.
         */
        default void onThinking(ThinkingUpdate update) {}
        void onSuccess(AssistantReply reply);
        void onError(String message);
    }

    public static final class History {
        public final String role;
        public final String content;
        /** Kept for backward compatibility; true now means any attachment exists. */
        public final boolean screenAttached;
        public final String attachmentPath;
        public final String attachmentKind;
        public final String attachmentLabel;
        /** Local request context needed for regeneration of text/PDF/screen attachments. */
        public final String attachmentText;
        /** Snapshot of memories supplied to the AI for this assistant turn. */
        public final String memoryUsage;
        /** Optional user-approved memory suggestion attached to this assistant turn. */
        public final String memorySuggestionText;
        public final String memorySuggestionCategory;

        public History(String role, String content) {
            this(role, content, false, "", "", "", "", "", "", "");
        }

        public History(String role, String content, boolean screenAttached) {
            this(role, content, screenAttached, "", screenAttached ? "screen" : "",
                    screenAttached ? "Screen attached" : "", "", "", "", "");
        }

        public History(String role, String content, boolean screenAttached, String attachmentPath) {
            this(role, content, screenAttached, attachmentPath,
                    screenAttached ? "screen" : "",
                    screenAttached ? "Screen attached" : "", "", "", "", "");
        }

        public History(String role, String content, boolean attached, String attachmentPath,
                       String attachmentKind, String attachmentLabel, String attachmentText) {
            this(role, content, attached, attachmentPath, attachmentKind, attachmentLabel,
                    attachmentText, "", "", "");
        }

        public History(String role, String content, boolean attached, String attachmentPath,
                       String attachmentKind, String attachmentLabel, String attachmentText,
                       String memoryUsage, String memorySuggestionText,
                       String memorySuggestionCategory) {
            this.role = role;
            this.content = content;
            this.screenAttached = attached;
            this.attachmentPath = attachmentPath == null ? "" : attachmentPath;
            this.attachmentKind = attachmentKind == null ? "" : attachmentKind;
            this.attachmentLabel = attachmentLabel == null ? "" : attachmentLabel;
            this.attachmentText = attachmentText == null ? "" : attachmentText;
            this.memoryUsage = memoryUsage == null ? "" : memoryUsage.trim();
            this.memorySuggestionText = memorySuggestionText == null ? "" : memorySuggestionText.trim();
            this.memorySuggestionCategory = memorySuggestionCategory == null ? "" : memorySuggestionCategory.trim();
        }
    }

    private AssistantClient() {}

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot, List<History> history, Callback cb) {
        send(context, prompt, screenText, screenshot, history, Prefs.intelligenceMode(context), cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot, List<History> history,
                            String intelligenceMode, Callback cb) {
        send(context, prompt, screenText, screenshot, history, intelligenceMode, false, cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot, List<History> history,
                            String intelligenceMode, boolean explicitAttachment, Callback cb) {
        send(context, prompt, screenText, screenshot, history, intelligenceMode,
                explicitAttachment, "", cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot, List<History> history,
                            String intelligenceMode, boolean explicitAttachment,
                            String trustedTaskContext, Callback cb) {
        send(context, prompt, screenText, screenshot, history, intelligenceMode,
                explicitAttachment, trustedTaskContext, () -> false, cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot, List<History> history,
                            String intelligenceMode, boolean explicitAttachment,
                            String trustedTaskContext, java.util.function.BooleanSupplier cancelled,
                            Callback cb) {
        // Explicit Orbit Memory commands stay local, inspectable, and instant.
        AssistantReply memory = MemoryCommandRouter.tryHandle(context, prompt, history);
        if (memory != null) {
            cb.onSuccess(memory);
            return;
        }

        // Saved routines are local, deterministic Action Engine plans. Resolve them
        // before generic device commands or the network-backed assistant.
        AssistantReply routine = RoutineCommandRouter.tryHandle(context, prompt);
        if (routine != null) {
            cb.onSuccess(routine);
            return;
        }

        // Obvious phone commands stay instant and offline regardless of AI provider.
        AssistantReply local = LocalCommandRouter.tryHandle(context, prompt);
        if (local != null) {
            cb.onSuccess(local);
            return;
        }

        NotificationQueryHelper.Prepared notification =
                NotificationQueryHelper.prepare(context, prompt);
        if (notification.recognized && notification.localReply != null) {
            cb.onSuccess(notification.localReply);
            return;
        }
        String notificationContext = notification.context;

        // User-defined phrases are exact local aliases for saved Routines. Core
        // memory, explicit Routine syntax, device commands, and local notification
        // queries intentionally retain priority.
        AssistantReply customCommand = CustomCommandRouter.tryHandle(context, prompt);
        if (customCommand != null) {
            cb.onSuccess(customCommand);
            return;
        }

        // Kitchen arithmetic is arithmetic: a conversion or a scaled quantity has one right
        // answer, so Orbit gives it itself rather than paying a round trip to whichever provider
        // is active. The router is deliberately narrow and hands anything needing judgement -
        // substitutions, technique, food safety - straight through to the provider below.
        AssistantReply kitchen = KitchenMathRouter.tryHandle(context, prompt);
        if (kitchen != null) {
            cb.onSuccess(kitchen);
            return;
        }

        final MemoryStore.Selection memorySelection =
                MemoryStore.select(context, prompt, screenText, history);
        final MemoryStore.Suggestion memorySuggestion =
                MemoryStore.suggest(context, prompt);
        final Callback responseCallback = decorateMemoryMetadata(
                cb, memorySelection, memorySuggestion);

        String requestMode = Prefs.normalizeMode(intelligenceMode);
        if (Prefs.MODE_AUTO.equals(requestMode)) {
            AutoRouter.Decision auto = AutoRouter.route(context, prompt, screenText, screenshot,
                    history, explicitAttachment, notificationContext);
            requestMode = auto.mode;
            DiagnosticStore.recordAutoRouting(context, requestMode, auto.confidence, auto.reason,
                    Prefs.effectiveModelForMode(context, requestMode, prompt),
                    Prefs.effectiveReasoningForMode(context, requestMode, prompt));
        }

        // Thinking updates are decided once, here, and then travel with the request. Reading the
        // preference again inside a provider would let a setting change mid-flight alter a turn
        // that had already started.
        final boolean thinkingUpdates = Prefs.thinkingUpdates(context);
        if (thinkingUpdates) {
            // The first thing Orbit can honestly say. Screen context is claimed only when this
            // request genuinely carries it, so the line describes the request that was actually
            // built rather than the setting that might have allowed one.
            boolean usingScreen = (Prefs.screenContext(context) || explicitAttachment)
                    && screenText != null && !screenText.trim().isEmpty();
            responseCallback.onThinking(ThinkingUpdate.progress(usingScreen
                    ? ThinkingUpdate.Stage.SCREEN_CONTEXT : ThinkingUpdate.Stage.WORKING));
        }

        // Everything above is Orbit's provider-independent pipeline. From here the active
        // provider owns the request; its own send() reports readiness problems (sign-in,
        // missing local model, missing relay) through the callback in plain language.
        AiRequest request = AiRequest.builder()
                .prompt(prompt)
                .screenText(screenText)
                .screenshot(screenshot)
                .history(history)
                .intelligenceMode(requestMode)
                .explicitAttachment(explicitAttachment)
                .notificationContext(notificationContext)
                .memoryContext(memorySelection.promptContext)
                .trustedTaskContext(trustedTaskContext)
                .cancelled(cancelled)
                .thinkingUpdates(thinkingUpdates)
                .build();
        AiProviders.active(context).send(context, request, responseCallback);
    }

    /**
     * One focused planning request that goes straight to the configured provider.
     *
     * <p>Deliberately not {@link #send}: that resolves saved routines, device commands, custom
     * commands, and notification queries first, so a description such as "turn on the flashlight"
     * would be executed as a command instead of planned, and it also selects Memory to add to the
     * prompt. This path carries only the planning instruction — no history, screen text,
     * screenshot, notifications, or memories — and returns the model's raw reply for the caller to
     * validate.
     */
    /**
     * Receives a planning response as raw provider text.
     *
     * <p>Deliberately not {@link Callback}: the chat path parses a reply into Orbit's
     * {@code {"text","actions"}} envelope, which discards any other JSON shape. A planner returns
     * its own schema, so the planning path must hand back the complete response untouched and let
     * {@link RoutinePlanResponse} decide what it is.
     */
    public interface PlanCallback {
        void onText(String rawResponse, String providerLabel);
        void onError(String message);
    }

    /** The mode planning runs in. Auto is resolved locally so planning never re-routes itself. */
    static String planMode(Context context) {
        String mode = Prefs.normalizeMode(Prefs.intelligenceMode(context));
        return Prefs.MODE_AUTO.equals(mode) ? Prefs.MODE_BALANCED : mode;
    }

    public static void plan(Context context, String planningPrompt, PlanCallback cb) {
        if (context == null || cb == null) return;
        if (planningPrompt == null || planningPrompt.trim().isEmpty()) {
            cb.onError("Describe the routine you want Orbit to build.");
            return;
        }
        AiProviders.active(context).plan(context, planningPrompt, planMode(context), cb);
    }

    private static Callback decorateMemoryMetadata(Callback downstream,
                                                   MemoryStore.Selection selection,
                                                   MemoryStore.Suggestion suggestion) {
        final String usage = selection == null ? "" : selection.usageText;
        final String suggestedText = suggestion == null ? "" : suggestion.text;
        final String suggestedCategory = suggestion == null ? "" : suggestion.category;

        return new Callback() {
            @Override public void onDelta(String text) {
                downstream.onDelta(text);
            }

            @Override public void onThinking(ThinkingUpdate update) {
                // Passed straight through. Memory metadata belongs to the answer; a status line
                // is not an answer and is never decorated, stored, or remembered.
                downstream.onThinking(update);
            }

            @Override public void onSuccess(AssistantReply reply) {
                if (reply == null) {
                    downstream.onSuccess(new AssistantReply("", new java.util.ArrayList<>(),
                            usage, suggestedText, suggestedCategory));
                    return;
                }
                downstream.onSuccess(new AssistantReply(reply.text, reply.actions,
                        usage, suggestedText, suggestedCategory));
            }

            @Override public void onError(String message) {
                downstream.onError(message);
            }
        };
    }

}
