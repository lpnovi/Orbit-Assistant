package com.orbit.assistant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Short-lived in-process result bridge from an Android picker back to OrbitSession. */
public final class AttachmentBridge {
    private static final long STALE_MS = 30L * 60L * 1000L;
    public interface Callback { void onResult(ComposerAttachment attachment, String error); }
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

    public static void deliver(String token, ComposerAttachment attachment, String error) {
        Entry entry;
        synchronized (AttachmentBridge.class) { entry = CALLBACKS.remove(token); }
        if (entry != null) entry.callback.onResult(attachment, error == null ? "" : error);
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
