package com.orbit.assistant;

import android.graphics.Bitmap;

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
    public final Bitmap screenshot;
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

    private AiRequest(Builder b) {
        this.prompt = b.prompt == null ? "" : b.prompt;
        this.screenText = b.screenText == null ? "" : b.screenText;
        this.screenshot = b.screenshot;
        this.history = b.history == null ? Collections.emptyList() : b.history;
        this.intelligenceMode = b.intelligenceMode == null ? Prefs.MODE_BALANCED : b.intelligenceMode;
        this.explicitAttachment = b.explicitAttachment;
        this.notificationContext = b.notificationContext == null ? "" : b.notificationContext;
        this.memoryContext = b.memoryContext == null ? "" : b.memoryContext;
        this.trustedTaskContext = b.trustedTaskContext == null ? "" : b.trustedTaskContext;
        this.cancelled = b.cancelled == null ? () -> false : b.cancelled;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String prompt;
        private String screenText;
        private Bitmap screenshot;
        private List<AssistantClient.History> history;
        private String intelligenceMode;
        private boolean explicitAttachment;
        private String notificationContext;
        private String memoryContext;
        private String trustedTaskContext;
        private BooleanSupplier cancelled;

        public Builder prompt(String v) { prompt = v; return this; }
        public Builder screenText(String v) { screenText = v; return this; }
        public Builder screenshot(Bitmap v) { screenshot = v; return this; }
        public Builder history(List<AssistantClient.History> v) { history = v; return this; }
        public Builder intelligenceMode(String v) { intelligenceMode = v; return this; }
        public Builder explicitAttachment(boolean v) { explicitAttachment = v; return this; }
        public Builder notificationContext(String v) { notificationContext = v; return this; }
        public Builder memoryContext(String v) { memoryContext = v; return this; }
        public Builder trustedTaskContext(String v) { trustedTaskContext = v; return this; }
        public Builder cancelled(BooleanSupplier v) { cancelled = v; return this; }
        public AiRequest build() { return new AiRequest(this); }
    }
}
