package com.orbit.assistant;

/**
 * Who owns Orbit's composer, and whether its input connection still needs revalidating.
 *
 * <p>Two things that look the same have to be tracked separately. <b>Typing intent</b> is the
 * user's decision to stay in typing mode; it survives sending, thinking, streaming and response
 * rendering, and ends only when they choose otherwise. <b>Editor focus</b> is what the view
 * reports, and it is not evidence of a working keyboard: on a physical Samsung device the editor
 * reported focus and the keyboard animated its keys while the characters went nowhere, because
 * the input connection behind it had gone stale.
 *
 * <p>The stale connection comes from {@code submit()} replacing the editor's contents with
 * {@code setText("")} while the IME still holds a connection, and possibly a composing region,
 * against the old text. Nothing in that path tells the IME its view of the editor changed. So
 * this class also tracks how many in-place revalidations a turn is still allowed, which is what
 * keeps the fix from becoming an unbounded restart loop.
 */
public final class ComposerImeState {
    /**
     * A turn crosses two boundaries where the connection can be dropped: clearing the composer
     * on send, and the response completing after streaming and action cards have rendered.
     */
    private static final int MAX_REVALIDATIONS_PER_TURN = 2;

    private boolean typing;
    private boolean keyboardVisible;
    private int revalidationsUsed = MAX_REVALIDATIONS_PER_TURN;

    /** A genuinely new assistant invocation: nothing owns the editor yet. */
    public void reset() {
        typing = false;
        keyboardVisible = false;
        revalidationsUsed = MAX_REVALIDATIONS_PER_TURN;
    }

    /** The composer has taken the input method because the user is typing in it. */
    public void attach() {
        typing = true;
    }

    /** Orbit has put the keyboard away and parked input focus off the editor. */
    public void release() {
        typing = false;
        revalidationsUsed = MAX_REVALIDATIONS_PER_TURN;
    }

    /** True while the user intends to keep typing, regardless of what the editor reports. */
    public boolean isTyping() {
        return typing;
    }

    /** Last observed keyboard visibility. */
    public boolean isKeyboardVisible() {
        return keyboardVisible;
    }

    /**
     * Whether submitting should release the keyboard and editor focus.
     *
     * <p>False for a typed turn, so the editor and its keyboard carry straight into the next
     * message.
     */
    public boolean shouldReleaseOnSubmit(boolean voiceRequest) {
        return voiceRequest || !typing;
    }

    /**
     * Records observed keyboard visibility and reports whether this looks like the user putting
     * the keyboard away themselves.
     *
     * <p>When Orbit hides the keyboard it clears typing intent first, so the visibility change
     * that follows is not mistaken for the user's own decision.
     */
    public boolean onKeyboardVisibilityChanged(boolean visible) {
        boolean was = keyboardVisible;
        keyboardVisible = visible;
        if (visible || !was || !typing) return false;
        // The keyboard went away while the user was still meant to be typing, and Orbit did not
        // do it. That is a deliberate dismissal, and it ends the typing session.
        typing = false;
        revalidationsUsed = MAX_REVALIDATIONS_PER_TURN;
        return true;
    }

    /** A request has been submitted; this turn may revalidate its connection again. */
    public void beginTurn() {
        revalidationsUsed = 0;
    }

    /**
     * Whether an in-place connection refresh is warranted right now, consuming one of the turn's
     * allowance. False once the allowance is spent, or whenever the user is not typing, so a
     * dismissed keyboard is never reopened and no path can loop.
     */
    public boolean shouldRevalidate() {
        if (!typing) return false;
        if (revalidationsUsed >= MAX_REVALIDATIONS_PER_TURN) return false;
        revalidationsUsed++;
        return true;
    }

    /** Remaining in-place refreshes for this turn, for tests and diagnostics. */
    public int revalidationsRemaining() {
        return Math.max(0, MAX_REVALIDATIONS_PER_TURN - revalidationsUsed);
    }
}
