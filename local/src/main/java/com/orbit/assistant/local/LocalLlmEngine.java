package com.orbit.assistant.local;

import android.content.Context;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
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
 * <p>A loaded model is expensive (seconds to load, roughly its size in memory), so one engine per
 * model is kept and reused across turns. Since v0.7.8.0 Beta 1 there can be two — the conversational
 * model and the much smaller action model — held independently, because swapping a 1.6 GB model out
 * and back every time somebody says "turn the flashlight off" would make the feature useless. Each
 * has its own engine, its own cancellation flag, and its own {@link #unload(ComponentModelSpec.Slot)},
 * so removing one model frees exactly its own memory.
 *
 * <p>Requests still run one at a time across both models. Local generation saturates the device,
 * and Orbit never issues concurrent turns for one conversation anyway.
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
    private static final Map<ComponentModelSpec.Slot, LlmInference> ENGINES =
            new EnumMap<>(ComponentModelSpec.Slot.class);
    private static final Map<ComponentModelSpec.Slot, String> LOADED_PATHS =
            new EnumMap<>(ComponentModelSpec.Slot.class);
    /** Set by a cancel request from Orbit; polled between tokens. One flag per model. */
    private static final Map<ComponentModelSpec.Slot, AtomicBoolean> CANCEL =
            new EnumMap<>(ComponentModelSpec.Slot.class);

    private LocalLlmEngine() {}

    static boolean isLoaded() {
        synchronized (LOCK) { return !ENGINES.isEmpty(); }
    }

    static boolean isLoaded(ComponentModelSpec.Slot slot) {
        synchronized (LOCK) { return ENGINES.get(slot) != null; }
    }

    private static AtomicBoolean cancelFlag(ComponentModelSpec.Slot slot) {
        synchronized (LOCK) {
            AtomicBoolean flag = CANCEL.get(slot);
            if (flag == null) {
                flag = new AtomicBoolean(false);
                CANCEL.put(slot, flag);
            }
            return flag;
        }
    }

    /** Asks every generation in progress to stop. */
    static void requestCancel() {
        for (ComponentModelSpec.Slot slot : ComponentModelSpec.Slot.values()) requestCancel(slot);
    }

    /** Asks the generation in progress for one model, if any, to stop. */
    static void requestCancel(ComponentModelSpec.Slot slot) {
        cancelFlag(slot).set(true);
    }

    /**
     * Runs one streaming generation on the conversational model.
     *
     * <p>Kept as the plain two-argument form the chat path has always called.
     */
    static void generate(Context context, String fullPrompt, StreamCallback callback) {
        generate(context, ComponentModelSpec.CHAT, fullPrompt, callback);
    }

    /**
     * Runs one streaming generation on the named model.
     *
     * <p>The callback is invoked on the engine thread; cancellation is polled between tokens and
     * stops the underlying generation, returning whatever text had already been produced through
     * {@link StreamCallback#onDone}.
     */
    static void generate(Context context, ComponentModelSpec spec, String fullPrompt,
                         StreamCallback callback) {
        AtomicBoolean cancelRequested = cancelFlag(spec.slot);
        cancelRequested.set(false);
        EXEC.execute(() -> {
            LlmInferenceSession session = null;
            try {
                LlmInference llm = ensureLoaded(context, spec);
                session = LlmInferenceSession.createFromOptions(llm,
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                                .setTopK(spec.slot == ComponentModelSpec.Slot.ACTION ? 1 : 40)
                                .setTopP(spec.slot == ComponentModelSpec.Slot.ACTION ? 1.0f : 0.95f)
                                // The action model is asked for one small JSON object. Sampling it
                                // creatively would only make a strict validator reject more of it,
                                // so it runs as close to greedy as the runtime allows.
                                .setTemperature(spec.slot == ComponentModelSpec.Slot.ACTION ? 0.0f : 0.6f)
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
                    if (cancelRequested.get() && cancelSent.compareAndSet(false, true)) {
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
                        if (!finished.get() && cancelRequested.get()
                                && cancelSent.compareAndSet(false, true)) {
                            try { activeSession.cancelGenerateResponseAsync(); } catch (Throwable ignored) {}
                        }
                    }
                    finished.set(true);
                }
                callback.onDone(text.toString());
            } catch (Throwable t) {
                // OutOfMemory and native failures both land here. Every engine is dropped rather
                // than only this one: running out of memory is a statement about the process, and
                // the next attempt should start from an empty one.
                unload();
                String message = t.getMessage();
                callback.onError(message == null || message.trim().isEmpty()
                        ? t.getClass().getSimpleName() : message);
            } finally {
                cancelRequested.set(false);
                if (session != null) {
                    try { session.close(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private static LlmInference ensureLoaded(Context context, ComponentModelSpec spec) {
        synchronized (LOCK) {
            File model = ComponentModelStore.modelFile(context, spec);
            String path = model.getAbsolutePath();
            LlmInference existing = ENGINES.get(spec.slot);
            if (existing != null && path.equals(LOADED_PATHS.get(spec.slot))) return existing;
            if (existing != null) {
                try { existing.close(); } catch (Throwable ignored) {}
                ENGINES.remove(spec.slot);
                LOADED_PATHS.remove(spec.slot);
            }
            LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    .setMaxTokens(spec.maxTokens)
                    .build();
            LlmInference engine =
                    LlmInference.createFromOptions(context.getApplicationContext(), options);
            ENGINES.put(spec.slot, engine);
            LOADED_PATHS.put(spec.slot, path);
            return engine;
        }
    }

    /** Frees every loaded model. Safe to call at any time. */
    static void unload() {
        for (ComponentModelSpec.Slot slot : ComponentModelSpec.Slot.values()) unload(slot);
    }

    /** Frees one loaded model, e.g. before deleting its file. Safe to call at any time. */
    static void unload(ComponentModelSpec.Slot slot) {
        synchronized (LOCK) {
            LlmInference engine = ENGINES.remove(slot);
            LOADED_PATHS.remove(slot);
            if (engine != null) {
                try { engine.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
