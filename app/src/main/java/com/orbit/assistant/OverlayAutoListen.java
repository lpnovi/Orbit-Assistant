package com.orbit.assistant;

/**
 * Lifecycle decisions for "Start listening when overlay opens".
 *
 * <p>Kept out of {@link OrbitSession} only so the rules can be tested without a real
 * {@code SpeechRecognizer} or a live {@code VoiceInteractionSession}. This class holds no
 * voice implementation of its own: when it says yes, {@code OrbitSession} calls the same
 * {@code startListening()} the microphone button already uses.
 */
public final class OverlayAutoListen {
    private OverlayAutoListen() {}

    /**
     * Whether a single overlay show should automatically begin the first voice turn.
     *
     * <p>Only a genuinely fresh external assistant invocation qualifies. Orbit resuming its own
     * still-open session — Screen Selection and the attachment/Gallery picker both return through
     * the internal-resume path — is never treated as a new invocation, so returning from those
     * flows cannot suddenly start the microphone. {@code alreadyHandled} is the per-invocation
     * guard that keeps a second lifecycle callback for the same visible sheet from starting twice.
     *
     * @param enabled        the Start listening when overlay opens preference
     * @param internalResume this show is Orbit resuming its own session, not a new invocation
     * @param alreadyHandled this same visible invocation already made the decision
     */
    public static boolean shouldStartForShow(boolean enabled, boolean internalResume,
                                             boolean alreadyHandled) {
        return enabled && !internalResume && !alreadyHandled;
    }

    /**
     * Whether the overlay should pull focus into its text composer as it opens.
     *
     * <p>Voice takes priority over the editor: when this show is starting the microphone, Orbit
     * leaves the composer alone rather than summoning a keyboard behind the listening UI. With
     * auto-listen off the answer is exactly Orbit's existing behaviour, so keyboard-aware
     * invocation is unchanged for everyone who does not turn the new setting on.
     *
     * @param autoListenStarting this show is about to start listening automatically
     * @param handsFreeFollowUps the existing hands-free voice follow-ups preference
     * @param keyboardAware      the existing keyboard-aware assistant invocation preference
     */
    public static boolean shouldFocusComposer(boolean autoListenStarting,
                                              boolean handsFreeFollowUps,
                                              boolean keyboardAware) {
        if (autoListenStarting) return false;
        return !handsFreeFollowUps && !keyboardAware;
    }
}
