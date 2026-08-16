package com.orbit.assistant;

import android.view.View;

/**
 * Coordinates composer focus with Orbit's input-method attachment.
 *
 * <p>The rule this class exists to enforce: <b>a focus-gained callback must never run a path that
 * moves focus again.</b> In v0.7.3.5 the focus listener called a helper that cleared and
 * re-requested focus to force a fresh input connection. That re-entered the same listener, which
 * called the helper again, and the Side-button composer crashed with a StackOverflowError the
 * moment it was tapped.
 *
 * <p>The responsibilities are now separate. Focus arriving is only ever <em>observed</em>: it
 * records that typing has been claimed and refreshes the connection in place. Moving focus is
 * something only a tap or a deliberate release does, and each of those settles in one step.
 * Refreshing an existing connection goes through the input method manager rather than through
 * focus, which is what that API is for.
 */
public final class ComposerFocusCoordinator {
    /** The parts that touch the window and the input method, supplied by the host surface. */
    public interface ImeBridge {
        /** Let the window take the input method. Must not move focus. */
        void allowWindowToTakeIme();

        /**
         * Rebuild the input connection for the editor, which already holds focus. Must not move
         * focus: restartInput and showSoftInput are the supported way to do this.
         */
        void refreshInputConnection();

        /** The composer has become the typing target: yield voice and record ownership. */
        void onTypingClaimed();
    }

    private final View editor;
    private final View focusHolder;
    private final ImeBridge bridge;
    private boolean claiming;

    public ComposerFocusCoordinator(View editor, View focusHolder, ImeBridge bridge) {
        this.editor = editor;
        this.focusHolder = focusHolder;
        this.bridge = bridge;
    }

    /**
     * The editor's focus state changed. Focus has already arrived by the time this runs, so the
     * work here is purely to record it and refresh the connection — never to move focus.
     */
    public void onFocusChanged(boolean hasFocus) {
        if (!hasFocus) return;
        claimTyping();
    }

    /**
     * The user tapped the editor, or Orbit is deliberately handing them the keyboard.
     *
     * <p>When the editor does not hold focus, requesting it is enough: the focus callback claims
     * typing and refreshes the connection, so doing that work here as well would double it. When
     * the editor already holds focus there is no transition to wait for, and the connection is
     * refreshed directly.
     */
    public void onEditorTapped() {
        if (editor == null) return;
        bridge.allowWindowToTakeIme();
        if (!editor.hasFocus()) {
            editor.requestFocus();
            return;
        }
        claimTyping();
    }

    /**
     * Rebuilds the input connection for an editor that is already focused and already the user's
     * typing target, at a lifecycle boundary where the IME may have been left holding a stale one.
     *
     * <p>This is the safe half of what v0.7.3.5 tried to do by clearing and re-requesting focus.
     * Focus is never touched here, so it cannot re-enter the focus listener, and the caller owns
     * the allowance that stops it repeating.
     *
     * @return true when a refresh was actually issued.
     */
    public boolean revalidateWithoutMovingFocus() {
        if (editor == null || !editor.hasFocus()) return false;
        bridge.refreshInputConnection();
        return true;
    }

    /**
     * Parks focus away from the editor when Orbit puts the keyboard away, so that the next tap is
     * a real focus change rather than a no-op on an already-focused view.
     */
    public void release() {
        if (focusHolder != null) focusHolder.requestFocus();
        else if (editor != null) editor.clearFocus();
    }

    /** True while the editor is the focused typing target. */
    public boolean editorHasFocus() {
        return editor != null && editor.hasFocus();
    }

    private void claimTyping() {
        // Defense in depth. The sequence below moves no focus, so it cannot re-enter on its own;
        // this guard means a future bridge that did move focus would stop here instead of
        // recursing without bound.
        if (claiming) return;
        claiming = true;
        try {
            bridge.allowWindowToTakeIme();
            bridge.onTypingClaimed();
            bridge.refreshInputConnection();
        } finally {
            claiming = false;
        }
    }
}
