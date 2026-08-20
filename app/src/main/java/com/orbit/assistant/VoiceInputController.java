package com.orbit.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;

/** Pause-friendly Voice Beta behavior for Orbit-owned Activity composers. */
public final class VoiceInputController {
    public interface Callback {
        String currentComposerText();
        void onDraft(String text);
        void onSubmit(String text);
        void onStatus(String status);
        void onStateChanged(boolean listening, boolean finalizing, boolean speaking);
        void onPermissionNeeded();

        /**
         * Current microphone level in dB while listening, for presentation only. Optional, so a
         * surface that shows no audio-reactive feedback simply ignores it.
         */
        default void onAudioLevel(float rmsdB) {}
    }

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean listening;
    private boolean finalizing;
    private boolean speaking;
    private boolean manualFinish;
    private boolean active;
    /** Owns the current voice turn so an abandoned one cannot act through late callbacks. */
    private final VoiceHandoff voiceTurn = new VoiceHandoff();
    /** Identifies the utterance currently being spoken, so an interrupted one cannot act. */
    private int utteranceGeneration;
    private String liveUtteranceId = "";
    /** The reply Orbit last spoke, read only by the Smart follow-up decision. */
    private String lastSpokenReply = "";
    /**
     * The user reached for the keyboard, so no pending hands-free handover may take the composer
     * back. Cleared when a voice turn starts or a new reply begins speaking, both of which are
     * fresh opportunities rather than the one the user opted out of.
     */
    private boolean composerClaimedForTyping;
    private String originalText = "";
    private String accumulated = "";
    private String partial = "";
    private Runnable finalizeRunnable;

    public VoiceInputController(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
        initRecognition();
        initTts();
    }

    public boolean isListening() { return listening; }
    public boolean isSpeaking() { return speaking; }

    public void toggle() {
        if (speaking) {
            stopSpeaking();
            start();
        } else if (listening || active) {
            if (Prefs.voicePauseFriendly(context) && hasDraft()) {
                manualFinish = true;
                cancelFinalize();
                finalizing = true;
                notifyState();
                callback.onStatus("Finishing voice…");
                try { recognizer.stopListening(); }
                catch (Exception ignored) { finishVoice(); }
            } else {
                stop(false);
            }
        } else {
            start();
        }
    }

    public void start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
            callback.onPermissionNeeded();
            return;
        }
        originalText = safe(callback.currentComposerText()).trim();
        accumulated = "";
        partial = "";
        manualFinish = false;
        finalizing = false;
        active = true;
        composerClaimedForTyping = false;
        cancelFinalize();
        voiceTurn.begin();
        if (Prefs.haptics(context)) {
            try {
                Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) vibrator.vibrate(
                        VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) {}
        }
        startSegment(true);
    }

    public void stop(boolean keepDraft) {
        cancelFinalize();
        manualFinish = false;
        active = false;
        finalizing = false;
        voiceTurn.abandon();
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        listening = false;
        if (!keepDraft && !currentVoiceText().isEmpty()) callback.onDraft(combinedDraft());
        callback.onStatus("");
        notifyState();
    }

    /**
     * Hands the turn from voice to the keyboard because the user tapped the composer, matching
     * the Side-button overlay so both surfaces behave the same way.
     *
     * <p>Returns false when nothing was running, which is the common case: an ordinary tap on an
     * idle composer keeps its existing behaviour untouched. Recognised words stay in the composer
     * for editing, nothing is submitted, and no preference is changed.
     */
    public boolean handOffToTyping() {
        // Reaching for the keyboard claims the composer even when no turn is running. That case
        // matters most while Orbit is still speaking: there is no recognizer to tear down, but the
        // reply that is mid-sentence must not hand the microphone back once it finishes.
        composerClaimedForTyping = true;
        if (!VoiceHandoff.shouldTakeOver(listening || active, finalizing)) return false;
        String preserved = combinedDraft();
        cancelFinalize();
        manualFinish = false;
        active = false;
        finalizing = false;
        voiceTurn.abandon();
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        listening = false;
        accumulated = "";
        partial = "";
        if (!preserved.trim().isEmpty()) callback.onDraft(preserved);
        callback.onStatus("");
        notifyState();
        return true;
    }

    public void speak(String text) {
        if (!ttsReady || tts == null || text == null || text.trim().isEmpty()) return;
        try {
            lastSpokenReply = text;
            composerClaimedForTyping = false;
            liveUtteranceId = "orbit_chat_reply_" + (++utteranceGeneration);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, liveUtteranceId);
        } catch (Exception ignored) {}
    }

    /**
     * The hands-free handover, decided by {@link VoiceFollowUpPolicy} and then re-decided when the
     * delayed reopen actually runs, so a turn the user has since taken over cannot be revived.
     *
     * <p>Full chat asks exactly the same policy as the Side-button overlay; only the mechanics of
     * starting recognition differ. The utterance generation is captured here so a newer reply that
     * begins speaking in the gap disowns this handover.
     */
    private void scheduleVoiceFollowUp() {
        if (!followUpDecision().reopensMicrophone()) return;
        final int generationAtSchedule = utteranceGeneration;
        main.postDelayed(() -> {
            if (generationAtSchedule != utteranceGeneration) return;
            if (!followUpDecision().reopensMicrophone()) return;
            start();
        }, 220L);
    }

    /** Package-private so the wiring, not just the policy, can be driven in tests. */
    VoiceFollowUpPolicy.Decision followUpDecision() {
        // An interrupted utterance clears the live id; a turn that is already listening, still
        // finalising, or has been handed to the keyboard is not eligible to be reopened.
        boolean utteranceLive = liveUtteranceId.isEmpty() && !speaking;
        // Deliberately not voiceTurn.hasLiveTurn(): the non-pause-friendly submit path leaves the
        // turn owned, so testing it here would silently stop follow-ups for anyone with Voice Beta
        // pauses switched off. active/listening/finalizing are cleared by every submit path.
        boolean surfaceEligible = !listening && !finalizing && !active
                && !composerClaimedForTyping;
        return VoiceFollowUpPolicy.decide(Prefs.autoListen(context), Prefs.smartFollowUps(context),
                utteranceLive, surfaceEligible, lastSpokenReply);
    }

    /**
     * Stops speaking and disowns the utterance.
     *
     * <p>Clearing the live id matters as much as stopping the engine: an interrupted utterance
     * can still deliver its completion callback, and that callback is what would otherwise open
     * the microphone a second time behind a turn the user has already moved on from.
     */
    public void stopSpeaking() {
        liveUtteranceId = "";
        if (tts != null) try { tts.stop(); } catch (Exception ignored) {}
        speaking = false;
        notifyState();
    }

    public void destroy() {
        stop(true);
        stopSpeaking();
        if (recognizer != null) try { recognizer.destroy(); } catch (Exception ignored) {}
        if (tts != null) try { tts.shutdown(); } catch (Exception ignored) {}
        recognizer = null;
        tts = null;
    }

    private void initRecognition() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return;
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (!voiceTurn.hasLiveTurn()) return;
                    statusListening();
                }
                @Override public void onBeginningOfSpeech() {
                    if (!voiceTurn.hasLiveTurn()) return;
                    cancelFinalize();
                    statusListening();
                }
                @Override public void onRmsChanged(float rmsdB) {
                    // Presentation only; recognition is untouched by whether anyone listens.
                    if (callback != null && voiceTurn.hasLiveTurn()) callback.onAudioLevel(rmsdB);
                }
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    if (!voiceTurn.hasLiveTurn()) return;
                    callback.onStatus(Prefs.voicePauseFriendly(context)
                            ? "Listening · pause when you need" : "Finishing voice…");
                }
                @Override public void onError(int error) {
                    // Belongs to a turn the user has left; the composer is theirs now.
                    if (!voiceTurn.hasLiveTurn()) return;
                    listening = false;
                    notifyState();
                    if (!active || finalizing) return;
                    if (Prefs.voicePauseFriendly(context) && hasDraft()) {
                        if (manualFinish) finishVoice();
                        else {
                            scheduleFinalize();
                            restartSegment();
                        }
                    } else if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                            error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                            error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        active = false;
                        callback.onStatus("Voice input unavailable");
                        notifyState();
                    } else {
                        active = false;
                        callback.onStatus("");
                        notifyState();
                    }
                }
                @Override public void onResults(Bundle results) {
                    // An abandoned turn must not reach onDraft or onSubmit.
                    if (!voiceTurn.hasLiveTurn()) return;
                    listening = false;
                    notifyState();
                    if (!active || finalizing) return;
                    ArrayList<String> values = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    String segment = values == null || values.isEmpty() ? "" : safe(values.get(0)).trim();
                    if (Prefs.voicePauseFriendly(context)) {
                        appendSegment(segment);
                        partial = "";
                        callback.onDraft(combinedDraft());
                        if (manualFinish) finishVoice();
                        else if (hasDraft()) {
                            scheduleFinalize();
                            restartSegment();
                        }
                    } else if (!segment.isEmpty()) {
                        active = false;
                        callback.onStatus("");
                        callback.onSubmit(withOriginal(segment));
                    }
                }
                @Override public void onPartialResults(Bundle results) {
                    if (!voiceTurn.hasLiveTurn()) return;
                    ArrayList<String> values = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (values == null || values.isEmpty()) return;
                    partial = safe(values.get(0)).trim();
                    callback.onDraft(combinedDraft());
                    if (Prefs.voicePauseFriendly(context)) scheduleFinalize();
                    statusListening();
                }
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Exception ignored) { recognizer = null; }
    }

    private void startSegment(boolean initial) {
        if (!active) return;
        if (recognizer == null) {
            active = false;
            callback.onStatus("Speech recognition unavailable");
            notifyState();
            return;
        }
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            if (Prefs.voicePauseFriendly(context)) {
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6500L);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L);
            }
            recognizer.startListening(intent);
            listening = true;
            statusListening();
            notifyState();
        } catch (Exception ignored) {
            listening = false;
            if (hasDraft()) scheduleFinalize();
            else active = false;
            notifyState();
        }
    }

    private void restartSegment() {
        if (!active || manualFinish || finalizing) return;
        // Rechecked on arrival so a queued restart cannot reopen the microphone after the user
        // has taken the composer for typing.
        final int turn = voiceTurn.liveTurn();
        main.postDelayed(() -> {
            if (!voiceTurn.accepts(turn)) return;
            if (active && !listening && !manualFinish && !finalizing) startSegment(false);
        }, 180L);
    }

    private void appendSegment(String segment) {
        if (segment.isEmpty()) return;
        if (accumulated.isEmpty()) accumulated = segment;
        else if (!accumulated.endsWith(segment)) accumulated += " " + segment;
    }

    private String currentVoiceText() {
        return VoiceHandoff.preservedDraft(accumulated, partial);
    }

    private String combinedDraft() { return withOriginal(currentVoiceText()); }

    private String withOriginal(String voice) {
        String spoken = safe(voice).trim();
        if (originalText.isEmpty()) return spoken;
        if (spoken.isEmpty()) return originalText;
        return originalText + (originalText.endsWith(" ") ? "" : " ") + spoken;
    }

    private boolean hasDraft() { return !currentVoiceText().trim().isEmpty(); }

    private void scheduleFinalize() {
        cancelFinalize();
        if (!hasDraft() || manualFinish || finalizing) return;
        final int turn = voiceTurn.liveTurn();
        finalizeRunnable = () -> {
            if (!voiceTurn.accepts(turn)) return;
            finishVoice();
        };
        main.postDelayed(finalizeRunnable, 5200L);
    }

    private void cancelFinalize() {
        if (finalizeRunnable != null) main.removeCallbacks(finalizeRunnable);
        finalizeRunnable = null;
    }

    private void finishVoice() {
        String finalText = combinedDraft().trim();
        if (finalText.isEmpty()) {
            stop(true);
            return;
        }
        cancelFinalize();
        finalizing = true;
        active = false;
        // The turn has produced its message; anything still in flight belongs to no one.
        voiceTurn.abandon();
        if (recognizer != null) try { recognizer.cancel(); } catch (Exception ignored) {}
        listening = false;
        callback.onStatus("");
        notifyState();
        callback.onSubmit(finalText);
        main.postDelayed(() -> {
            finalizing = false;
            notifyState();
        }, 300L);
    }

    /** True when a completion callback belongs to the utterance Orbit is still speaking. */
    boolean isLiveUtterance(String utteranceId) {
        return utteranceId != null && !liveUtteranceId.isEmpty()
                && liveUtteranceId.equals(utteranceId);
    }

    private void statusListening() { callback.onStatus("Listening · pause when you need"); }

    private void notifyState() { callback.onStateChanged(listening, finalizing, speaking); }

    private void initTts() {
        try {
            tts = new TextToSpeech(context, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (!ttsReady) return;
                tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.03f);
                tts.setPitch(1f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        speaking = true;
                        main.post(() -> { callback.onStatus("Speaking · tap mic to interrupt"); notifyState(); });
                    }
                    @Override public void onDone(String utteranceId) {
                        speaking = false;
                        main.post(() -> {
                            // An utterance the user interrupted must not open the microphone.
                            if (!isLiveUtterance(utteranceId)) {
                                notifyState();
                                return;
                            }
                            liveUtteranceId = "";
                            callback.onStatus("");
                            notifyState();
                            scheduleVoiceFollowUp();
                        });
                    }
                    @Override public void onError(String utteranceId) {
                        speaking = false;
                        main.post(() -> { callback.onStatus(""); notifyState(); });
                    }
                });
            });
        } catch (Exception ignored) { tts = null; }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
