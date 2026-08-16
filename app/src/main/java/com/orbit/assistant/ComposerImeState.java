package com.orbit.assistant;

/**
 * Who owns Orbit's composer and the input method during one Side-button invocation.
 *
 * <p>The Side-button sheet is a single long-lived window, so the editor's input connection has to
 * survive an unlimited number of typed turns. Tearing it down on every send left the editor
 * looking focused while the IME had already let go: the keyboard reappeared on the next tap and
 * animated its keys, but the characters had nowhere to land, so a chat accepted exactly one typed
 * message.
 *
 * <p>The rule is simply that a typed turn keeps what the user set up. Only a voice turn, or a
 * submission made while the composer was not the active target, puts the keyboard away.
 */
public final class ComposerImeState {
    private boolean typing;

    /** A genuinely new assistant invocation: nothing owns the editor yet. */
    public void reset() {
        typing = false;
    }

    /** The composer has taken the input method because the user is typing in it. */
    public void attach() {
        typing = true;
    }

    /** Orbit has put the keyboard away and parked input focus off the editor. */
    public void release() {
        typing = false;
    }

    /** True while the composer is the user's active typing target. */
    public boolean isTyping() {
        return typing;
    }

    /**
     * Whether submitting should release the keyboard and editor focus.
     *
     * <p>False for a typed turn, so the editor and its live input connection carry straight into
     * the next message.
     */
    public boolean shouldReleaseOnSubmit(boolean voiceRequest) {
        return voiceRequest || !typing;
    }
}
