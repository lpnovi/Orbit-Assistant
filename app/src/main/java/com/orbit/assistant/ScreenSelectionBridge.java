package com.orbit.assistant;

import android.graphics.Bitmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Short-lived in-process result bridge for the VoiceInteractionSession caller. */
public final class ScreenSelectionBridge {
    private static final long STALE_MS = 30L * 60L * 1000L;
    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public final Bitmap image;
        public final boolean precise;
        public final boolean failed;

        Result(Bitmap image, boolean precise, boolean failed) {
            this.image = image;
            this.precise = precise;
            this.failed = failed;
        }
    }

    private static final class Entry {
        final Callback callback;
        final long createdAt;

        Entry(Callback callback) {
            this.callback = callback;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private static final Map<String, Entry> CALLBACKS = new HashMap<>();

    private ScreenSelectionBridge() {}

    public static synchronized String register(Callback callback) {
        CALLBACKS.entrySet().removeIf(entry ->
                System.currentTimeMillis() - entry.getValue().createdAt > STALE_MS);
        String token = UUID.randomUUID().toString();
        CALLBACKS.put(token, new Entry(callback));
        return token;
    }

    public static void deliver(String token, Bitmap image, boolean precise) {
        Entry entry;
        synchronized (ScreenSelectionBridge.class) {
            entry = CALLBACKS.remove(token);
        }
        if (entry != null) entry.callback.onResult(new Result(image, precise, false));
    }

    public static void fail(String token) {
        Entry entry;
        synchronized (ScreenSelectionBridge.class) {
            entry = CALLBACKS.remove(token);
        }
        if (entry != null) entry.callback.onResult(new Result(null, false, true));
    }

    public static synchronized void cancel(String token) {
        if (token != null) CALLBACKS.remove(token);
    }
}
