package com.orbit.assistant;

/**
 * Ownership of a single voice turn, shared by the Side-button overlay and the full-chat composer.
 *
 * <p>Android's {@link android.speech.SpeechRecognizer} keeps delivering callbacks for a moment
 * after {@code cancel()}, and it reads its listener field at dispatch time, so swapping listeners
 * per segment would not separate one turn from the next. The only reliable way to disown an
 * abandoned turn is a generation counter consulted when each callback actually runs.
 *
 * <p>{@link #begin()} opens a turn and returns its id. {@link #abandon()} closes it, after which
 * {@link #hasLiveTurn()} is false and every {@link #accepts(int)} for the old id fails, so a late
 * result cannot overwrite what the user has since typed, submit the abandoned utterance, or
 * restart listening. Posted work captures its id at schedule time and re-checks it when it runs.
 */
public final class VoiceHandoff {
    /** Id meaning "no voice turn". Never returned by {@link #begin()}. */
    public static final int NO_TURN = 0;

    private int issued = NO_TURN;
    private int live = NO_TURN;

    /** Opens a new voice turn, disowning any previous one, and returns the new turn's id. */
    public int begin() {
        live = ++issued;
        return live;
    }

    /** Disowns the live turn. Callbacks and posted work from it are ignored from here on. */
    public void abandon() {
        live = NO_TURN;
    }

    /** True while a voice turn owns the recognizer. */
    public boolean hasLiveTurn() {
        return live != NO_TURN;
    }

    /** The live turn's id, or {@link #NO_TURN}. Capture this when posting delayed voice work. */
    public int liveTurn() {
        return live;
    }

    /** True when {@code turn} is still the live turn, so its callback may act. */
    public boolean accepts(int turn) {
        return turn != NO_TURN && turn == live;
    }

    /**
     * The recognised text worth keeping when the user leaves voice for the keyboard. Mirrors how
     * a turn is read while listening, so handing over shows exactly what was on screen rather
     * than an emptier or fuller version of it.
     */
    public static String preservedDraft(String accumulated, String partial) {
        String base = accumulated == null ? "" : accumulated.trim();
        String tail = partial == null ? "" : partial.trim();
        if (base.isEmpty()) return tail;
        if (tail.isEmpty()) return base;
        return base + " " + tail;
    }

    /**
     * Whether tapping the composer should take the turn away from voice. Only a turn that is
     * actually running is taken over, so a tap on an idle composer keeps its ordinary behaviour,
     * and a turn already committed to submitting is left to finish.
     */
    public static boolean shouldTakeOver(boolean listening, boolean finishing) {
        return listening && !finishing;
    }
}
