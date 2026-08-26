package com.orbit.assistant.local;

import android.content.Context;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orbit Local's inference runtime: a thin, defensive wrapper around Google's MediaPipe/LiteRT
 * LLM Inference API.
 *
 * <p>This class, and the native libraries behind it, are the entire reason the optional component
 * exists. They used to live inside every Orbit installation whether or not local AI was ever
 * enabled; now they ship only to people who chose to install Orbit Local.
 *
 * <p>The loaded model is expensive (seconds to load, roughly its size in memory), so one engine
 * is kept per process and reused across turns. Requests run one at a time: local generation
 * saturates the device, and Orbit never issues concurrent turns for one conversation anyway.
 */
final class LocalLlmEngine {

    interface StreamCallback {
        /** Cumulative text so far, matching how Orbit's delta pipeline expects partials. */
        void onPartial(String cumulativeText);
        void onDone(String fullText);
        void onError(String message);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orbit-local-llm");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private static final Object LOCK = new Object();
    private static LlmInference engine;
    private static String loadedModelPath;
    /** Set by a cancel request from Orbit; polled between tokens. */
    private static final AtomicBoolean CANCEL_REQUESTED = new AtomicBoolean(false);

    private LocalLlmEngine() {}

    static boolean isLoaded() {
        synchronized (LOCK) { return engine != null; }
    }

    /** Asks the generation in progress, if any, to stop. */
    static void requestCancel() {
        CANCEL_REQUESTED.set(true);
    }

    /**
     * Runs one streaming generation. The callback is invoked on the engine thread; cancellation is
     * polled between tokens and stops the underlying generation, returning whatever text had
     * already been produced through {@link StreamCallback#onDone}.
     */
    static void generate(Context context, String fullPrompt, StreamCallback callback) {
        CANCEL_REQUESTED.set(false);
        EXEC.execute(() -> {
            LlmInferenceSession session = null;
            try {
                LlmInference llm = ensureLoaded(context);
                session = LlmInferenceSession.createFromOptions(llm,
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                                .setTopK(40)
                                .setTopP(0.95f)
                                .setTemperature(0.6f)
                                .build());
                session.addQueryChunk(fullPrompt);

                StringBuilder text = new StringBuilder();
                AtomicBoolean finished = new AtomicBoolean(false);
                AtomicBoolean cancelSent = new AtomicBoolean(false);
                final LlmInferenceSession activeSession = session;
                final Object doneSignal = new Object();

                activeSession.generateResponseAsync((partial, done) -> {
                    if (finished.get()) return;
                    if (partial != null && !partial.isEmpty()) {
                        text.append(partial);
                        if (!cancelSent.get()) callback.onPartial(text.toString());
                    }
                    if (CANCEL_REQUESTED.get() && cancelSent.compareAndSet(false, true)) {
                        try { activeSession.cancelGenerateResponseAsync(); } catch (Throwable ignored) {}
                    }
                    if (done) {
                        synchronized (doneSignal) {
                            finished.set(true);
                            doneSignal.notifyAll();
                        }
                    }
                });

                synchronized (doneSignal) {
                    long deadline = System.currentTimeMillis() + 10 * 60_000L;
                    while (!finished.get() && System.currentTimeMillis() < deadline) {
                        doneSignal.wait(1000L);
                        if (!finished.get() && CANCEL_REQUESTED.get()
                                && cancelSent.compareAndSet(false, true)) {
                            try { activeSession.cancelGenerateResponseAsync(); } catch (Throwable ignored) {}
                        }
                    }
                    finished.set(true);
                }
                callback.onDone(text.toString());
            } catch (Throwable t) {
                // OutOfMemory and native failures both land here; the engine is dropped so the
                // next attempt starts clean instead of reusing a wedged runtime.
                unload();
                String message = t.getMessage();
                callback.onError(message == null || message.trim().isEmpty()
                        ? t.getClass().getSimpleName() : message);
            } finally {
                CANCEL_REQUESTED.set(false);
                if (session != null) {
                    try { session.close(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private static LlmInference ensureLoaded(Context context) {
        synchronized (LOCK) {
            File model = ComponentModelStore.modelFile(context);
            String path = model.getAbsolutePath();
            if (engine != null && path.equals(loadedModelPath)) return engine;
            if (engine != null) {
                try { engine.close(); } catch (Throwable ignored) {}
                engine = null;
                loadedModelPath = null;
            }
            LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    .setMaxTokens(ComponentModelStore.MODEL_MAX_TOKENS)
                    .build();
            engine = LlmInference.createFromOptions(context.getApplicationContext(), options);
            loadedModelPath = path;
            return engine;
        }
    }

    /** Frees the loaded model, e.g. before deleting its file. Safe to call at any time. */
    static void unload() {
        synchronized (LOCK) {
            if (engine != null) {
                try { engine.close(); } catch (Throwable ignored) {}
            }
            engine = null;
            loadedModelPath = null;
        }
    }
}
