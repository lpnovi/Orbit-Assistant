package com.orbit.assistant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived in-process result bridge from an Android picker back to the surface that opened it.
 *
 * <p>The token is ownership. A picker runs in its own Activity, in another task, and can outlive
 * the overlay invocation or the conversation that asked for it; a result that comes back to a
 * composer that has moved on would attach photos to the wrong message. So a batch is delivered to
 * exactly one registration, that registration is removed as it is delivered, and a token whose
 * owner is gone resolves to nothing at all.
 *
 * <p>One picker trip resolves once, with a whole {@link AttachmentBatch}. Delivering per selected
 * item would leave "has the picker finished" unanswerable, which is the shape of the bug where a
 * cancelled picker left Orbit convinced a selection was still in flight and refused every later
 * attempt.
 */
public final class AttachmentBridge {
    private static final long STALE_MS = 30L * 60L * 1000L;

    public interface Callback {
        /** Called once, with everything one picker trip produced. */
        void onResult(AttachmentBatch batch);
    }

    private static final class Entry {
        final Callback callback;
        final long createdAt = System.currentTimeMillis();
        Entry(Callback callback) { this.callback = callback; }
    }

    private static final Map<String, Entry> CALLBACKS = new HashMap<>();
    private AttachmentBridge() {}

    public static synchronized String register(Callback callback) {
        CALLBACKS.entrySet().removeIf(entry ->
                System.currentTimeMillis() - entry.getValue().createdAt > STALE_MS);
        String token = UUID.randomUUID().toString();
        CALLBACKS.put(token, new Entry(callback));
        return token;
    }

    /**
     * Hands a batch to whoever registered {@code token}, at most once.
     *
     * <p>The removal is what makes a second delivery - a duplicated lifecycle callback, a picker
     * that finishes twice - a no-op rather than a second set of attachments on the composer.
     */
    public static void deliver(String token, AttachmentBatch batch) {
        Entry entry;
        synchronized (AttachmentBridge.class) { entry = CALLBACKS.remove(token); }
        if (entry == null) return;
        entry.callback.onResult(batch == null ? AttachmentBatch.cancelled() : batch);
    }

    public static synchronized void cancel(String token) {
        if (token != null) CALLBACKS.remove(token);
    }

    /**
     * Whether a token is still awaiting a result. Delivering or cancelling removes it, so a token
     * that is no longer registered proves its picker flow has already finished.
     */
    public static synchronized boolean isPending(String token) {
        return token != null && !token.isEmpty() && CALLBACKS.containsKey(token);
    }
}
