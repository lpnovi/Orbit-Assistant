package com.orbit.assistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Read Aloud: Orbit reads one assistant answer through Android's own text-to-speech engine and
 * the voices installed on the phone. No service, no account, no network of Orbit's own, and
 * nothing to do with the microphone.
 *
 * <p>One reader for the whole app, so two answers can never talk over each other: starting a
 * second one replaces the first. Everything here runs on the main thread; the engine's callbacks
 * hop onto it and are ignored unless they belong to the current run.
 *
 * <p>Android has no pause for speech, so an answer is queued in short pieces
 * ({@link ReadAloudText#chunks}) and Pause remembers the piece being spoken: Resume starts that
 * sentence or paragraph again rather than the whole answer.
 */
final class ReadAloud {
    interface Listener { void onReadAloudChanged(); }

    static final int IDLE = 0, STARTING = 1, READING = 2, PAUSED = 3;
    static final String NO_ENGINE_MESSAGE =
            "Read Aloud needs a text-to-speech engine. Install or turn one on in Android's settings.";
    static final String SYSTEM_VOICE_LABEL = "System voice";
    static final String SAMPLE = "This is how Orbit will read answers aloud.";
    static final String FAILED_MESSAGE = "Read Aloud stopped: the speech engine reported a problem.";
    /** How long an idle engine is kept before it is released. */
    private static final long RELEASE_AFTER_MS = 60_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static Context app;
    private static TextToSpeech tts;
    private static boolean ready;
    private static int state = IDLE;
    /** The raw answer being read; it is how a message knows the reader is on it. */
    private static String reading = "";
    private static Object owner;
    private static List<String> queue = Collections.emptyList();
    private static int index;
    /** Bumped whenever the queue is replaced, paused or stopped, which disowns older callbacks. */
    private static int run;
    private static boolean pausedByFocus;
    private static AudioFocusRequest focus;
    private static final Runnable release = ReadAloud::releaseEngine;
    /** Callers waiting for the engine to finish binding. */
    private static final List<java.util.function.Consumer<Boolean>> waiting = new ArrayList<>();

    private ReadAloud() {}

    static int state() { return state; }
    static boolean isActive() { return state != IDLE; }
    /** True while this exact answer is being read or is paused. */
    static boolean isReading(String raw) { return state != IDLE && raw != null && raw.equals(reading); }

    static void addListener(Listener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }

    /** Read aloud on an answer that is being read stops it; on any other answer, reads that one. */
    static void toggle(Context c, String raw, Object startedBy) {
        if (isReading(raw)) stop(); else start(c, raw, startedBy);
    }

    /** Reads this answer from its start, replacing whatever was being read. */
    static void start(Context c, String raw, Object startedBy) {
        if (c == null) return;
        List<String> pieces = ReadAloudText.chunks(raw);
        if (pieces.isEmpty()) return;
        app = c.getApplicationContext();
        run++;
        if (tts != null) try { tts.stop(); } catch (Exception ignored) {}
        reading = raw;
        owner = startedBy;
        queue = pieces;
        index = 0;
        pausedByFocus = false;
        state = STARTING;
        main.removeCallbacks(release);
        changed();
        whenReady(ReadAloud::speakFromIndex);
    }

    /** Pauses and lets other audio have the speaker back until Resume. */
    static void pause() {
        pauseKeepingFocus();
        if (state == PAUSED) abandonFocus();
    }

    private static void pauseKeepingFocus() {
        if (state != READING && state != STARTING) return;
        run++;
        if (tts != null) try { tts.stop(); } catch (Exception ignored) {}
        state = PAUSED;
        changed();
    }

    static void resume() {
        if (state != PAUSED) return;
        pausedByFocus = false;
        state = STARTING;
        run++;
        changed();
        whenReady(ReadAloud::speakFromIndex);
    }

    /** Stops now. Safe from anywhere on the main thread, whether or not anything is being read. */
    static void stop() {
        run++;
        if (tts != null) try { tts.stop(); } catch (Exception ignored) {}
        abandonFocus();
        boolean was = state != IDLE;
        state = IDLE;
        reading = "";
        owner = null;
        queue = Collections.emptyList();
        index = 0;
        pausedByFocus = false;
        if (tts != null) {
            main.removeCallbacks(release);
            main.postDelayed(release, RELEASE_AFTER_MS);
        }
        if (was) changed();
    }

    /** Stops only what this surface started, so closing one never silences another. */
    static void stopOwnedBy(Object startedBy) {
        if (startedBy != null && startedBy == owner) stop();
    }

    /**
     * The installed voices for the phone's language, for Settings. Null when there is no engine.
     * Answers on the main thread, possibly later: the engine binds asynchronously.
     */
    static void voices(Context c, java.util.function.Consumer<List<Voice>> result) {
        app = c.getApplicationContext();
        main.removeCallbacks(release);
        whenEngine(ok -> {
            List<Voice> out = new ArrayList<>();
            if (!ok) { result.accept(null); return; }
            try {
                String language = Locale.getDefault().getLanguage();
                for (Voice v : tts.getVoices()) {
                    if (v.getLocale() != null && language.equals(v.getLocale().getLanguage())
                            && (v.getFeatures() == null || !v.getFeatures().contains(
                            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) out.add(v);
                }
                Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
            } catch (Exception ignored) {}
            result.accept(out);
            if (state == IDLE) main.postDelayed(release, RELEASE_AFTER_MS);
        });
    }

    /** What Settings calls a voice: engines name them like "en-us-x-sfg-local", which nobody should read. */
    static String voiceLabel(Voice v, int number) {
        return v.getLocale().getDisplayName() + " · voice " + number
                + (v.isNetworkConnectionRequired() ? " · needs internet" : "");
    }

    // ---- engine -------------------------------------------------------------------------------

    private static void whenReady(Runnable then) {
        final int mine = run;
        whenEngine(ok -> {
            if (mine != run) return;
            if (ok) then.run(); else fail(NO_ENGINE_MESSAGE);
        });
    }

    private static void whenEngine(java.util.function.Consumer<Boolean> then) {
        if (tts != null && ready) { then.accept(true); return; }
        // Still binding from an earlier request: everyone waiting hears the one answer.
        waiting.add(then);
        if (tts != null) return;
        try {
            final TextToSpeech[] made = new TextToSpeech[1];
            made[0] = new TextToSpeech(app, status -> main.post(() -> {
                if (tts != made[0]) return;
                ready = status == TextToSpeech.SUCCESS;
                if (ready) tts.setOnUtteranceProgressListener(PROGRESS); else releaseEngine();
                engineAnswered(ready);
            }));
            tts = made[0];
        } catch (Exception e) {
            tts = null;
            engineAnswered(false);
        }
    }

    private static void engineAnswered(boolean ok) {
        List<java.util.function.Consumer<Boolean>> all = new ArrayList<>(waiting);
        waiting.clear();
        for (java.util.function.Consumer<Boolean> each : all) each.accept(ok);
    }

    private static void speakFromIndex() {
        try {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setSpeechRate(Prefs.readAloudRate(app));
            tts.setPitch(1f);
            applyVoice();
            requestFocus();
            for (int i = index; i < queue.size(); i++) {
                int result = tts.speak(queue.get(i),
                        i == index ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                        null, run + ":" + i);
                if (result != TextToSpeech.SUCCESS) { fail(FAILED_MESSAGE); return; }
            }
        } catch (Exception e) {
            fail(FAILED_MESSAGE);
        }
    }

    /** The chosen voice if it is still installed; otherwise the phone's language, or the engine's own default. */
    private static void applyVoice() {
        String wanted = Prefs.readAloudVoice(app);
        if (!wanted.isEmpty()) {
            try {
                for (Voice v : tts.getVoices()) {
                    if (wanted.equals(v.getName())) { tts.setVoice(v); return; }
                }
            } catch (Exception ignored) {}
        }
        try {
            Voice standard = tts.getDefaultVoice();
            if (standard != null) tts.setVoice(standard);
            if (tts.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE) {
                tts.setLanguage(Locale.getDefault());
            }
        } catch (Exception ignored) {}
    }

    private static void fail(String message) {
        stop();
        if (app != null) Toast.makeText(app, message, Toast.LENGTH_LONG).show();
    }

    private static void releaseEngine() {
        main.removeCallbacks(release);
        if (state != IDLE && ready) return;
        if (tts != null) try { tts.shutdown(); } catch (Exception ignored) {}
        tts = null;
        ready = false;
    }

    private static void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.onReadAloudChanged();
    }

    /** {@code "<run>:<piece>"} for a callback of the current run, else -1. */
    private static int pieceOf(String utteranceId) {
        if (utteranceId == null) return -1;
        int colon = utteranceId.indexOf(':');
        try {
            if (colon > 0 && Integer.parseInt(utteranceId.substring(0, colon)) == run) {
                return Integer.parseInt(utteranceId.substring(colon + 1));
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    private static final UtteranceProgressListener PROGRESS = new UtteranceProgressListener() {
        @Override public void onStart(String id) {
            main.post(() -> {
                int piece = pieceOf(id);
                if (piece < 0 || state == IDLE || state == PAUSED) return;
                index = piece;
                if (state != READING) { state = READING; changed(); }
            });
        }
        @Override public void onDone(String id) {
            main.post(() -> {
                int piece = pieceOf(id);
                if (piece >= 0 && piece == queue.size() - 1 && state != PAUSED) stop();
            });
        }
        @Override public void onError(String id) {
            main.post(() -> { if (pieceOf(id) >= 0 && state != PAUSED) fail(FAILED_MESSAGE); });
        }
    };

    // ---- audio focus --------------------------------------------------------------------------

    private static final AudioManager.OnAudioFocusChangeListener FOCUS = change -> main.post(() -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            stop();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // A call or another app's short sound: wait for it, then carry on.
            if (state == READING || state == STARTING) { pauseKeepingFocus(); pausedByFocus = true; }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && pausedByFocus) {
            resume();
        }
    });

    private static void requestFocus() {
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(FOCUS, main)
                    .build();
        }
        try { audio.requestAudioFocus(focus); } catch (Exception ignored) {}
    }

    private static void abandonFocus() {
        if (focus == null || app == null) return;
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) try { audio.abandonAudioFocusRequest(focus); } catch (Exception ignored) {}
    }

    /** The engine, for tests that need to answer its initialisation. */
    static TextToSpeech engineForTest() { return tts; }

    /** Back to nothing. For tests. */
    static void resetForTest() {
        stop();
        main.removeCallbacksAndMessages(null);
        listeners.clear();
        waiting.clear();
        tts = null;
        ready = false;
        focus = null;
    }
}
