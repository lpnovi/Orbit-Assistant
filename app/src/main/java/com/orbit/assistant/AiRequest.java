package com.orbit.assistant;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * One normalized conversation request, provider-agnostic.
 *
 * <p>Everything Orbit's pipeline prepares for a turn — prompt, screen context, attachments,
 * history, memory, notification context, and the resolved intelligence mode — travels in this
 * one value so every {@link AiProvider} receives the same shape. Providers use the parts their
 * {@link AiCapabilities} support and ignore the rest.
 */
public final class AiRequest {
    public final String prompt;
    public final String screenText;
    /**
     * The first image of this turn, or null.
     *
     * <p>Kept because a provider that genuinely supports one image should read one field rather
     * than remember to take the head of a list. It is always the first element of {@link #images}.
     */
    public final Bitmap screenshot;
    /**
     * Every image this turn carries, in the order the user attached them.
     *
     * <p>One list, whether it holds a screen capture, one photo, or ten. A provider whose
     * {@link AiCapabilities#multipleImages} is true sends all of them inside one user message; one
     * whose is false sends the first and states plainly that it did, because silently keeping only
     * attachment number one is indistinguishable to the user from Orbit losing the rest.
     */
    public final List<Bitmap> images;
    public final List<AssistantClient.History> history;
    /** Already resolved: never {@link Prefs#MODE_AUTO} by the time a provider sees it. */
    public final String intelligenceMode;
    public final boolean explicitAttachment;
    public final String notificationContext;
    public final String memoryContext;
    public final String trustedTaskContext;
    /**
     * True once the user has stopped this request. Providers poll it between streamed tokens so
     * a cancelled local generation stops burning battery; cloud providers may ignore it because
     * {@link OrbitRequestManager} already refuses their late results.
     */
    public final BooleanSupplier cancelled;
    /**
     * True when the user has Thinking updates on, so a provider may ask for reasoning summaries.
     *
     * <p>Carried on the request rather than read from preferences inside a provider, because the
     * answer must be the one that was true when this turn was submitted: a request already in
     * flight keeps the behaviour it started with even if the setting is changed underneath it.
     * Providers that would pay for summaries in request parameters, cost, or latency ask for them
     * only when this is true.
     */
    public final boolean thinkingUpdates;

    private AiRequest(Builder b) {
        this.prompt = b.prompt == null ? "" : b.prompt;
        this.screenText = b.screenText == null ? "" : b.screenText;
        List<Bitmap> resolvedImages = new ArrayList<>();
        if (b.images != null) {
            for (Bitmap image : b.images) if (image != null) resolvedImages.add(image);
        }
        // A caller that still sets one screenshot is describing a one-image turn, so it becomes
        // that list rather than a separate concept the providers would each have to merge.
        if (resolvedImages.isEmpty() && b.screenshot != null) resolvedImages.add(b.screenshot);
        this.images = Collections.unmodifiableList(resolvedImages);
        this.screenshot = resolvedImages.isEmpty() ? null : resolvedImages.get(0);
        this.history = b.history == null ? Collections.emptyList() : b.history;
        this.intelligenceMode = b.intelligenceMode == null ? Prefs.MODE_BALANCED : b.intelligenceMode;
        this.explicitAttachment = b.explicitAttachment;
        this.notificationContext = b.notificationContext == null ? "" : b.notificationContext;
        this.memoryContext = b.memoryContext == null ? "" : b.memoryContext;
        this.trustedTaskContext = b.trustedTaskContext == null ? "" : b.trustedTaskContext;
        this.cancelled = b.cancelled == null ? () -> false : b.cancelled;
        this.thinkingUpdates = b.thinkingUpdates;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String prompt;
        private String screenText;
        private Bitmap screenshot;
        private List<Bitmap> images;
        private List<AssistantClient.History> history;
        private String intelligenceMode;
        private boolean explicitAttachment;
        private String notificationContext;
        private String memoryContext;
        private String trustedTaskContext;
        private BooleanSupplier cancelled;
        private boolean thinkingUpdates;

        public Builder prompt(String v) { prompt = v; return this; }
        public Builder screenText(String v) { screenText = v; return this; }
        public Builder screenshot(Bitmap v) { screenshot = v; return this; }
        public Builder images(List<Bitmap> v) { images = v; return this; }
        public Builder history(List<AssistantClient.History> v) { history = v; return this; }
        public Builder intelligenceMode(String v) { intelligenceMode = v; return this; }
        public Builder explicitAttachment(boolean v) { explicitAttachment = v; return this; }
        public Builder notificationContext(String v) { notificationContext = v; return this; }
        public Builder memoryContext(String v) { memoryContext = v; return this; }
        public Builder trustedTaskContext(String v) { trustedTaskContext = v; return this; }
        public Builder cancelled(BooleanSupplier v) { cancelled = v; return this; }
        public Builder thinkingUpdates(boolean v) { thinkingUpdates = v; return this; }
        public AiRequest build() { return new AiRequest(this); }
    }
}
