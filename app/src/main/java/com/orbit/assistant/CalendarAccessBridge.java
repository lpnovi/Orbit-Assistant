package com.orbit.assistant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived in-process result bridge from Android's Calendar permission prompt back to whichever
 * Orbit surface asked for it.
 *
 * <p>Deliberately the same shape as {@link AttachmentBridge}: a token, one callback, delivered
 * exactly once. Nothing durable is stored, which is the safe direction to fail in. If the process
 * dies while the prompt is open, the pending approval is simply lost and nothing is written; the
 * user repeats the request rather than finding events that appeared without them.
 */
public final class CalendarAccessBridge {
    private static final long STALE_MS = 10L * 60L * 1000L;

    public interface Callback { void onResult(boolean granted); }

    private static final class Entry {
        final Callback callback;
        final long createdAt = System.currentTimeMillis();
        Entry(Callback callback) { this.callback = callback; }
    }

    private static final Map<String, Entry> CALLBACKS = new HashMap<>();

    private CalendarAccessBridge() {}

    public static synchronized String register(Callback callback) {
        CALLBACKS.entrySet().removeIf(entry ->
                System.currentTimeMillis() - entry.getValue().createdAt > STALE_MS);
        String token = UUID.randomUUID().toString();
        CALLBACKS.put(token, new Entry(callback));
        return token;
    }

    public static void deliver(String token, boolean granted) {
        Entry entry;
        synchronized (CalendarAccessBridge.class) { entry = CALLBACKS.remove(token); }
        if (entry != null) entry.callback.onResult(granted);
    }

    /** Whether a token is still awaiting a result; delivering or cancelling removes it. */
    public static synchronized boolean isPending(String token) {
        return token != null && !token.isEmpty() && CALLBACKS.containsKey(token);
    }
}
