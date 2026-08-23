package com.orbit.assistant;

/**
 * What one {@link AiProvider} can actually do, stated explicitly.
 *
 * <p>Surfaces read these flags instead of comparing provider ids, so adding a provider never
 * means hunting for scattered name checks. A provider must not claim a capability it does not
 * really have; UI copy and request routing both trust these values.
 */
public final class AiCapabilities {
    /** Partial answers stream to the UI while the model is still generating. */
    public final boolean streaming;
    /** The provider can return Orbit's device-action envelope (timers, flashlight, and so on). */
    public final boolean deviceActions;
    /** Screenshots and image attachments can be sent with a request. */
    public final boolean images;
    /** Requests work with no network connection. */
    public final boolean offline;
    /** Sign-in or an API key is required before the provider can answer. */
    public final boolean needsCredentials;
    /** Fast/Balanced/Deep map to genuinely different models or reasoning effort. */
    public final boolean reasoningLevels;
    /** A hosted web-search tool can be offered for current-information questions. */
    public final boolean hostedWebSearch;
    /** Routine planning requests produce reliable structured output. */
    public final boolean routinePlanning;

    private AiCapabilities(Builder b) {
        this.streaming = b.streaming;
        this.deviceActions = b.deviceActions;
        this.images = b.images;
        this.offline = b.offline;
        this.needsCredentials = b.needsCredentials;
        this.reasoningLevels = b.reasoningLevels;
        this.hostedWebSearch = b.hostedWebSearch;
        this.routinePlanning = b.routinePlanning;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean streaming;
        private boolean deviceActions;
        private boolean images;
        private boolean offline;
        private boolean needsCredentials;
        private boolean reasoningLevels;
        private boolean hostedWebSearch;
        private boolean routinePlanning;

        public Builder streaming(boolean v) { streaming = v; return this; }
        public Builder deviceActions(boolean v) { deviceActions = v; return this; }
        public Builder images(boolean v) { images = v; return this; }
        public Builder offline(boolean v) { offline = v; return this; }
        public Builder needsCredentials(boolean v) { needsCredentials = v; return this; }
        public Builder reasoningLevels(boolean v) { reasoningLevels = v; return this; }
        public Builder hostedWebSearch(boolean v) { hostedWebSearch = v; return this; }
        public Builder routinePlanning(boolean v) { routinePlanning = v; return this; }
        public AiCapabilities build() { return new AiCapabilities(this); }
    }
}
