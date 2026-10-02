package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How full the context window of the next request in a chat would be (0.8.3.0-beta.3+).
 *
 * <p><b>Measured, not modelled.</b> {@link #measure} assembles the request the chat would send next
 * with {@link ChatGptClient#requestBody} itself, in a measuring mode that reads no image files and
 * writes no Diagnostics, and counts what the builder places (see {@link ContextLedger}). It follows
 * the visible path only, sends each kept item once, and includes the draft in the composer and its
 * attachments, because that is what pressing Send would send. Hidden branches and hidden answer
 * versions are never part of it, by construction: they are not part of the path a request is
 * built from.
 *
 * <p><b>Always an estimate.</b> No provider tokenizer runs on the phone, so every figure is
 * approximate and shown with a "~". When Orbit does not know the selected model's context window
 * (Orbit Local fits each request to its own small window, for example) there is no percentage at
 * all rather than an invented one.
 */
final class ContextEstimate {
    /** How full the window is, in calm bands. */
    enum Level { UNKNOWN, NORMAL, FILLING, HIGH, CRITICAL }

    static final int FILLING_PERCENT = 70;
    static final int HIGH_PERCENT = 85;
    static final int CRITICAL_PERCENT = 95;
    /** From here Orbit offers to continue in a new chat. */
    static final int NOTICE_PERCENT = 90;

    /** Estimated tokens the next request would carry. */
    final int tokens;
    /** The model's context window, or 0 when Orbit does not know it. */
    final int limit;
    /** Estimated tokens per category, contributing categories only, display order. */
    final Map<ContextLedger.Category, Integer> breakdown;
    /** Earlier messages Orbit would not resend, because they fall outside its history window. */
    final int olderMessagesNotSent;
    /** True when the provider fits each request to a window of its own (Orbit Local). */
    final boolean fittedByProvider;
    /** The model this estimate is for, as the user reads it. */
    final String modelName;

    ContextEstimate(int tokens, int limit, Map<ContextLedger.Category, Integer> breakdown,
                    int olderMessagesNotSent, boolean fittedByProvider, String modelName) {
        this.tokens = Math.max(0, tokens);
        this.limit = Math.max(0, limit);
        this.breakdown = breakdown == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(breakdown));
        this.olderMessagesNotSent = Math.max(0, olderMessagesNotSent);
        this.fittedByProvider = fittedByProvider;
        this.modelName = modelName == null ? "" : modelName;
    }

    boolean knowsLimit() { return limit > 0; }

    /** Share of the window used, 0 to 100, or -1 when the window is unknown. Never above 100. */
    int percent() {
        if (!knowsLimit()) return -1;
        return (int) Math.min(100, Math.round(tokens * 100.0 / limit));
    }

    /** The exact fraction for drawing, 0 to 1, or 0 when unknown. */
    float fraction() {
        if (!knowsLimit()) return 0f;
        return (float) Math.min(1.0, tokens / (double) limit);
    }

    Level level() {
        int p = percent();
        if (p < 0) return Level.UNKNOWN;
        if (p >= CRITICAL_PERCENT) return Level.CRITICAL;
        if (p >= HIGH_PERCENT) return Level.HIGH;
        if (p >= FILLING_PERCENT) return Level.FILLING;
        return Level.NORMAL;
    }

    /** Whether to offer Continue in new chat. */
    boolean nearlyFull() { return knowsLimit() && percent() >= NOTICE_PERCENT; }

    /** "~38K / 128K · 30%", or "~3K tokens" when the window is unknown. */
    String summary() {
        if (!knowsLimit()) return approx(tokens) + " tokens";
        return approx(tokens) + " / " + exact(limit) + " · " + percent() + "%";
    }

    /** "~850", "~4.2K", "~38K", "~1.1M": always marked as an estimate. */
    static String approx(int value) {
        return "~" + compact(value);
    }

    /** "128K", "1.05M": a published figure, so no tilde. */
    static String exact(int value) {
        return compact(value);
    }

    private static String compact(int value) {
        if (value < 1000) return String.valueOf(value < 100 ? value : (value / 10) * 10);
        if (value < 10_000) return trim(String.format(Locale.US, "%.1f", value / 1000.0)) + "K";
        if (value < 1_000_000) return Math.round(value / 1000.0) + "K";
        return trim(String.format(Locale.US, "%.2f", value / 1_000_000.0)) + "M";
    }

    private static String trim(String number) {
        if (!number.contains(".")) return number;
        String out = number.replaceAll("0+$", "");
        return out.endsWith(".") ? out.substring(0, out.length() - 1) : out;
    }

    // ---- measuring -------------------------------------------------------------------------------

    /** What is sitting in the composer: the message, its attachments, and a quote, if any. */
    static final class Draft {
        static final Draft EMPTY = new Draft("", Collections.emptyList(), null);

        final String text;
        final List<ComposerAttachment> attachments;
        final QuotedMessage quote;

        Draft(String text, List<ComposerAttachment> attachments, QuotedMessage quote) {
            this.text = text == null ? "" : text.trim();
            this.attachments = attachments == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(attachments));
            this.quote = quote;
        }
    }

    /**
     * The estimate for the next request in {@code conversationId}, sent with {@code selection}.
     *
     * <p>Does disk reads (the conversation, memories, notification history for a notification
     * question) and is meant for a background thread. Never sends anything and never writes.
     */
    static ContextEstimate measure(Context c, String conversationId, AiSelection selection,
                                   Draft draft) {
        AiSelection resolved = AiSelections.resolve(selection);
        AiModelSpec spec = OrbitModelCatalog.spec(resolved.provider, resolved.model);
        int limit = spec == null ? 0 : spec.contextWindowTokens;
        String modelName = resolved.modelName();
        Draft d = draft == null ? Draft.EMPTY : draft;

        ConversationStore.Conversation chat = ConversationStore.load(c, conversationId);
        List<AssistantClient.History> history = chat == null
                ? new ArrayList<>() : new ArrayList<>(chat.messages);
        List<KeptContext> keptItems = chat == null ? Collections.emptyList() : chat.kept;

        String prompt = d.text;
        if (prompt.isEmpty() && !d.attachments.isEmpty()) {
            prompt = AttachmentPrompts.defaultPrompt(d.attachments);
        }
        String attachmentText = ComposerAttachments.contextTextOf(d.attachments);
        boolean explicit = !d.attachments.isEmpty();
        List<Bitmap> images = ComposerAttachments.imagesOf(d.attachments);
        AssistantClient.History draftTurn = null;
        if (!prompt.isEmpty()) {
            // The draft as the message it would become, so the builder treats it exactly as it
            // treats a message being answered: its quote and attachment travel with it, once.
            draftTurn = new AssistantClient.History("user", prompt, explicit,
                    Collections.emptyList(), ComposerAttachments.kindOf(d.attachments),
                    ComposerAttachments.labelOf(d.attachments), attachmentText, "", "", "", "")
                    .withQuote(d.quote);
            history.add(draftTurn);
        }
        KeptContext.Prepared kept = KeptContext.prepare(keptItems,
                draftTurn == null ? "" : ConversationBranches.fingerprint(draftTurn));

        String notificationContext = "";
        if (!prompt.isEmpty()) {
            NotificationQueryHelper.Prepared notifications =
                    NotificationQueryHelper.prepare(c, prompt, false);
            if (notifications.recognized && notifications.localReply == null) {
                notificationContext = notifications.context;
            }
        }
        String memory = Prefs.memoryEnabled(c) && !prompt.isEmpty()
                ? MemoryStore.select(c, prompt, attachmentText, history).promptContext : "";

        if (Prefs.PROVIDER_LOCAL.equals(resolved.provider)) {
            // Orbit Local fits every request to its own small window, so the honest figure is the
            // size of the prompt it would actually run, with no percentage of a window it manages.
            AiRequest request = AiRequest.builder().prompt(prompt).screenText(attachmentText)
                    .images(images).history(history).selection(resolved)
                    .explicitAttachment(explicit).notificationContext(notificationContext)
                    .memoryContext(memory).keptContext(kept).build();
            int local = 0;
            try {
                local = OrbitLocalProvider.buildPrompt(c, request).estimatedTokens;
            } catch (Exception ignored) {}
            return new ContextEstimate(local, 0, Collections.emptyMap(), 0, true, modelName);
        }

        ContextLedger ledger = new ContextLedger(true);
        for (ComposerAttachment attachment : d.attachments) {
            if (attachment == null) continue;
            ledger.splitCurrentAttachment(ContextLedger.categoryForKind(attachment.kind),
                    attachment.contextText.length());
        }
        try {
            ChatGptClient.requestBody(c, prompt, attachmentText, images, history, resolved,
                    explicit, notificationContext, memory, "", false, false, resolved.model,
                    kept, ledger);
        } catch (Exception failed) {
            return new ContextEstimate(0, limit, Collections.emptyMap(), 0, false, modelName);
        }
        return new ContextEstimate(ledger.total(), limit, ledger.breakdown(),
                ledger.olderMessagesNotSent, false, modelName);
    }
}
