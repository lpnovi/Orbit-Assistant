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
    /**
     * Several images can travel inside one user message.
     *
     * <p>Narrower than {@link #images} and deliberately separate from it. "Can send a picture" and
     * "can send four pictures the model will compare" are different facts, and a provider that
     * answers true here must genuinely place every image in the same turn. False is not a failure
     * mode: it means Orbit sends the first image and tells both the user and the model that it
     * did, which is the honest outcome. Nothing may claim this to avoid the extra sentence.
     */
    public final boolean multipleImages;
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
    /**
     * The provider can stream reasoning <em>summaries</em> that are meant to be shown to the user.
     *
     * <p>Narrow on purpose. This is not "the model reasons" and not "reasoning exists somewhere in
     * the response protocol": it is "this provider publishes a short, user-facing description of
     * its own work, and Orbit may display it". A provider that only carries hidden reasoning, or
     * carries it encrypted, declares false and Orbit falls back to describing its own execution
     * instead. Nothing in Orbit reads private reasoning either way.
     */
    public final boolean reasoningSummaries;
    /**
     * The provider reports which web pages an answer actually consulted, in structured form.
     *
     * <p>The capability Rich Answers is gated on, and deliberately narrower than
     * {@link #hostedWebSearch}. Searching the web is one thing; telling Orbit <em>which pages the
     * answer used</em> is another, and only the second is enough to put a picture under an answer
     * and say where it came from. A provider that can browse but cannot report its sources must
     * declare false here, and its answers stay text - which is a complete answer, not a degraded
     * one.
     *
     * <p>Nothing may claim this to obtain the feature. A picture attributed to a page the answer
     * did not read would be Orbit stating something it does not know.
     */
    public final boolean richWebMedia;

    private AiCapabilities(Builder b) {
        this.streaming = b.streaming;
        this.deviceActions = b.deviceActions;
        this.images = b.images;
        this.multipleImages = b.multipleImages && b.images;
        this.offline = b.offline;
        this.needsCredentials = b.needsCredentials;
        this.reasoningLevels = b.reasoningLevels;
        this.hostedWebSearch = b.hostedWebSearch;
        this.routinePlanning = b.routinePlanning;
        this.reasoningSummaries = b.reasoningSummaries;
        // A provider cannot report the sources of a search it cannot run, so the two are bound
        // together here rather than trusted to agree at each declaration site.
        this.richWebMedia = b.richWebMedia && b.hostedWebSearch;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean streaming;
        private boolean deviceActions;
        private boolean images;
        private boolean multipleImages;
        private boolean offline;
        private boolean needsCredentials;
        private boolean reasoningLevels;
        private boolean hostedWebSearch;
        private boolean routinePlanning;
        private boolean reasoningSummaries;
        private boolean richWebMedia;

        public Builder streaming(boolean v) { streaming = v; return this; }
        public Builder deviceActions(boolean v) { deviceActions = v; return this; }
        public Builder images(boolean v) { images = v; return this; }
        public Builder multipleImages(boolean v) { multipleImages = v; return this; }
        public Builder offline(boolean v) { offline = v; return this; }
        public Builder needsCredentials(boolean v) { needsCredentials = v; return this; }
        public Builder reasoningLevels(boolean v) { reasoningLevels = v; return this; }
        public Builder hostedWebSearch(boolean v) { hostedWebSearch = v; return this; }
        public Builder routinePlanning(boolean v) { routinePlanning = v; return this; }
        public Builder reasoningSummaries(boolean v) { reasoningSummaries = v; return this; }
        public Builder richWebMedia(boolean v) { richWebMedia = v; return this; }
        public AiCapabilities build() { return new AiCapabilities(this); }
    }
}
