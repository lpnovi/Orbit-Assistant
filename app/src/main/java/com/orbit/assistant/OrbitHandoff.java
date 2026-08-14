package com.orbit.assistant;

import android.os.Handler;
import android.os.Looper;

/**
 * One-shot rendezvous between the Side-button overlay and the chat it expands into.
 *
 * <p>The overlay must not blank until the destination has actually drawn, or the user sees the
 * window behind it for a frame. This carries a single callback that the destination fires once it
 * is on screen, and it is cleared the moment it runs, so nothing survives to affect a later
 * navigation. A timeout guarantees the overlay is released even if the destination never reports,
 * so the handoff can never leave a stuck overlay window.
 */
final class OrbitHandoff {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Upper bound on how long the overlay waits before releasing itself anyway. */
    private static final long FAILSAFE_MS = 900L;

    private static Runnable pending;
    private static Runnable failsafe;

    private OrbitHandoff() {}

    /** Registers the overlay's dismissal, to run when the destination draws or the failsafe fires. */
    static synchronized void expectDestination(Runnable onReady) {
        cancel();
        if (onReady == null) return;
        pending = onReady;
        failsafe = () -> destinationDrawn();
        MAIN.postDelayed(failsafe, FAILSAFE_MS);
    }

    /** Called by the destination once its first frame is on screen. Safe to call spuriously. */
    static synchronized void destinationDrawn() {
        Runnable ready = pending;
        pending = null;
        if (failsafe != null) {
            MAIN.removeCallbacks(failsafe);
            failsafe = null;
        }
        if (ready != null) MAIN.post(ready);
    }

    static synchronized void cancel() {
        pending = null;
        if (failsafe != null) {
            MAIN.removeCallbacks(failsafe);
            failsafe = null;
        }
    }

    static synchronized boolean isPending() {
        return pending != null;
    }
}
