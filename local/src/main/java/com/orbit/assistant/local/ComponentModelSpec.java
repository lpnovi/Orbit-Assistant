package com.orbit.assistant.local;

import java.util.Locale;

/**
 * The identity of one model the component can hold, pinned byte for byte.
 *
 * <p>Until v0.7.8.0 the component held exactly one model and its identity was a handful of
 * constants. Beta 1 adds a second — a small action model that lives beside the chat model rather
 * than replacing it — so that identity becomes a value, and every part of the download, storage,
 * verification and removal machinery takes one of these instead of reaching for a global.
 *
 * <p>Two slots and no more. A slot is a role, not a file name: the chat model answers
 * conversations, the action model reads one short instruction and answers with a small JSON object,
 * and they are downloaded, stored, reported and deleted completely independently. Removing one
 * leaves the other exactly as it was.
 *
 * <p>Every field here is a pin. The URL, the exact byte count and the SHA-256 are what make an
 * interrupted or tampered download impossible to mistake for an installed model, and they are
 * checked before a file is ever promoted. Provenance and licence are recorded alongside them
 * because a model shipped to users is a dependency like any other.
 */
public final class ComponentModelSpec {

    /** What a model is for. Roles, not files. */
    public enum Slot { CHAT, ACTION }

    /**
     * Orbit Local's conversational model, unchanged since v0.7.7.5.
     *
     * <p>Qwen 2.5 1.5B Instruct, exported to LiteRT/MediaPipe {@code .task} by litert-community,
     * Apache-2.0, 8-bit quantised, 4096-token context.
     */
    public static final ComponentModelSpec CHAT = new ComponentModelSpec(
            Slot.CHAT,
            "qwen2.5-1.5b-instruct-q8",
            "Qwen 2.5 (1.5B)",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/"
                    + "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            1_598_556_720L,
            "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3",
            4096,
            "Apache-2.0",
            "litert-community/Qwen2.5-1.5B-Instruct");

    /**
     * The device-action model introduced in v0.7.8.0 Beta 1.
     *
     * <p>Qwen 2.5 0.5B Instruct, from the same publisher, the same export format, the same
     * Apache-2.0 licence and the same runtime as the chat model above — which is most of the reason
     * it was chosen. It is roughly a third of the size, its 1280-token context is far more than one
     * short instruction and a small JSON object need, and instruction-tuned Qwen models are
     * dependable at exactly this kind of tightly constrained structured output.
     *
     * <p>It is never asked to hold a conversation, and it is never trusted: whatever it writes goes
     * through Orbit's {@code LocalActionSchema} before anything can happen.
     */
    public static final ComponentModelSpec ACTION = new ComponentModelSpec(
            Slot.ACTION,
            "qwen2.5-0.5b-instruct-q8",
            "Qwen 2.5 (0.5B) actions",
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/"
                    + "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            546_660_344L,
            "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
            1280,
            "Apache-2.0",
            "litert-community/Qwen2.5-0.5B-Instruct");

    public final Slot slot;
    public final String id;
    public final String displayName;
    public final String downloadUrl;
    public final long sizeBytes;
    public final String sha256;
    /** The context window the packaged model was exported with. */
    public final int maxTokens;
    public final String license;
    /** Where the file comes from, as a repository name rather than a URL. */
    public final String provenance;

    private ComponentModelSpec(Slot slot, String id, String displayName, String downloadUrl,
                               long sizeBytes, String sha256, int maxTokens,
                               String license, String provenance) {
        this.slot = slot;
        this.id = id;
        this.displayName = displayName;
        this.downloadUrl = downloadUrl;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.maxTokens = maxTokens;
        this.license = license;
        this.provenance = provenance;
    }

    /** The file this model is stored as. Distinct per model, which is what keeps the slots apart. */
    public String fileName() {
        return id + ".task";
    }

    /** The preference-key prefix for this slot's state. Chat keeps the original keys. */
    String keyPrefix() {
        return slot == Slot.CHAT ? "model_" : "action_model_";
    }

    /** The unique background-work name for this slot's download. */
    String workName() {
        return slot == Slot.CHAT
                ? "orbit-local-component-model-download"
                : "orbit-local-component-action-model-download";
    }

    public static ComponentModelSpec forSlot(Slot slot) {
        return slot == Slot.ACTION ? ACTION : CHAT;
    }

    /** The slot named by a string, defaulting to the chat model rather than failing. */
    public static ComponentModelSpec forSlotName(String name) {
        return name != null && Slot.ACTION.name().equalsIgnoreCase(name.trim()) ? ACTION : CHAT;
    }

    @Override public String toString() {
        return String.format(Locale.US, "%s/%s", slot.name(), id);
    }
}
