package com.orbit.assistant;

import android.graphics.Bitmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Short-lived in-process result bridge for the VoiceInteractionSession caller. */
public final class ScreenSelectionBridge {
    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public final Bitmap image;
        public final boolean precise;

        Result(Bitmap image, boolean precise) {
            this.image = image;
            this.precise = precise;
        }
    }

    private static final Map<String, Callback> CALLBACKS = new HashMap<>();

    private ScreenSelectionBridge() {}

    public static synchronized String register(Callback callback) {
        String token = UUID.randomUUID().toString();
        CALLBACKS.put(token, callback);
        return token;
    }

    public static void deliver(String token, Bitmap image, boolean precise) {
        Callback callback;
        synchronized (ScreenSelectionBridge.class) {
            callback = CALLBACKS.remove(token);
        }
        if (callback != null) callback.onResult(new Result(image, precise));
    }

    public static synchronized void cancel(String token) {
        if (token != null) CALLBACKS.remove(token);
    }
}
