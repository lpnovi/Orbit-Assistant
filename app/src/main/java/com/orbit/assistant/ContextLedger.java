package com.orbit.assistant;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one assembled request contains, by category, as the request builder writes it.
 *
 * <p>The context meter never keeps its own model of a conversation. It asks the real request builder
 * ({@link ChatGptClient#requestBody}) to assemble the next request in a measuring mode, and the
 * builder records here each piece of text and each image exactly as it places it: the same history
 * window, the same truncation, the same attachments re-sent from earlier turns, the same kept
 * context, counted once. Anything the builder leaves out is therefore never counted.
 *
 * <p><b>Approximate on purpose.</b> Orbit does not run the provider's tokenizer. {@link #tokensFor}
 * is a documented estimate - about four characters per token for plain text, more for other
 * scripts, and a fixed allowance per image - and every figure shown from it carries a "~".
 *
 * <p>Holds amounts only. It never stores the text it is told about, so nothing a ledger is shown in
 * can reveal Orbit's instructions, a memory, or a document's contents.
 */
final class ContextLedger {
    /** The groups the breakdown can show. Order is display order. */
    enum Category {
        CONVERSATION("Conversation"),
        DOCUMENTS("Documents"),
        VAULT("Vault"),
        SCREEN("Screen"),
        IMAGES("Images"),
        NOTIFICATIONS("Notifications"),
        MEMORY("Memory"),
        KEPT("Kept in this chat"),
        INSTRUCTIONS("Orbit's instructions"),
        OTHER("Other");

        final String label;
        Category(String label) { this.label = label; }
    }

    /** The estimate for one image the model will look at. A middle value for a phone screenshot. */
    static final int TOKENS_PER_IMAGE = 1100;
    /** Message framing the provider adds around each turn, roughly. */
    static final int TOKENS_PER_MESSAGE = 4;

    /** True when the builder should count images rather than read and encode them. */
    final boolean measureOnly;
    private final Map<Category, Double> tokens = new EnumMap<>(Category.class);
    /** How the current message's attachment text divides between categories, by share. */
    private final Map<Category, Integer> currentSplit = new LinkedHashMap<>();
    /** Earlier messages the request builder left out because they are outside its window. */
    int olderMessagesNotSent;
    int messagesSent;

    ContextLedger(boolean measureOnly) {
        this.measureOnly = measureOnly;
    }

    /**
     * An estimate of the tokens in {@code text}: a quarter of a token per ASCII character and most
     * of one for anything else, rounded up. Close to GPT tokenizers on English prose, deliberately
     * generous for other scripts, and never presented as exact.
     */
    static int tokensFor(String text) {
        if (text == null || text.isEmpty()) return 0;
        double total = 0;
        for (int i = 0; i < text.length(); i++) total += text.charAt(i) < 128 ? 0.25 : 0.7;
        return (int) Math.ceil(total);
    }

    void text(Category category, String text) {
        if (text == null || text.isEmpty() || category == null) return;
        add(category, tokensFor(text));
    }

    void images(int count) {
        if (count > 0) add(Category.IMAGES, (double) count * TOKENS_PER_IMAGE);
    }

    void message() {
        messagesSent++;
        add(Category.OTHER, TOKENS_PER_MESSAGE);
    }

    /** Declares how the current message's attachment text divides, e.g. a PDF and a Vault note. */
    void splitCurrentAttachment(Category category, int chars) {
        if (category == null || chars <= 0) return;
        Integer before = currentSplit.get(category);
        currentSplit.put(category, (before == null ? 0 : before) + chars);
    }

    /** Records the current message's attachment block, divided as declared, else as {@code fallback}. */
    void currentAttachment(String block, Category fallback) {
        int total = tokensFor(block);
        if (total <= 0) return;
        int declared = 0;
        for (int chars : currentSplit.values()) declared += chars;
        if (declared <= 0) {
            add(fallback, total);
            return;
        }
        for (Map.Entry<Category, Integer> share : currentSplit.entrySet()) {
            add(share.getKey(), total * (share.getValue() / (double) declared));
        }
    }

    private void add(Category category, double amount) {
        Double before = tokens.get(category);
        tokens.put(category, (before == null ? 0 : before) + amount);
    }

    /** The estimated total, in tokens. */
    int total() {
        double sum = 0;
        for (double value : tokens.values()) sum += value;
        return (int) Math.ceil(sum);
    }

    /** Every category that contributes, in display order, rounded. Zero categories are absent. */
    Map<Category, Integer> breakdown() {
        Map<Category, Integer> out = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            Double value = tokens.get(category);
            if (value == null) continue;
            int rounded = (int) Math.round(value);
            if (rounded > 0) out.put(category, rounded);
        }
        return out;
    }

    /** The category an attachment kind's text belongs to. Never a filename, only Orbit's kind. */
    static Category categoryForKind(String attachmentKind) {
        String kind = attachmentKind == null ? "" : attachmentKind.trim();
        switch (kind) {
            case "pdf":
            case "pdf_page":
            case "file_text":
                return Category.DOCUMENTS;
            case "screen":
            case "screen_selection":
                return Category.SCREEN;
            case "vault":
                return Category.VAULT;
            case "image":
            case "camera":
                return Category.IMAGES;
            default:
                return Category.OTHER;
        }
    }
}
