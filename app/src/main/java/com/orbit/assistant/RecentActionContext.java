package com.orbit.assistant;

import android.os.SystemClock;

/**
 * The one device target Orbit most recently acted on, so a short follow-up can be resolved.
 *
 * <p>"Turn the brightness down." then "A little more." should mean brightness. The authority for
 * that comes from an action Orbit actually executed, not from anything a model said, and it is
 * deliberately narrow: one target, remembered briefly, and only used when the follow-up names no
 * target of its own.
 *
 * <p>If anything is unclear — no recent action, the memory has aged out, or the follow-up could
 * plausibly mean something else — this resolves to nothing and the request takes its normal path.
 * Guessing wrong here changes the user's phone.
 */
public final class RecentActionContext {
    /** How long a device action stays available as conversational context. */
    static final long WINDOW_MS = 90_000L;

    /** The kinds of follow-up Orbit can resolve. */
    public enum Target { BRIGHTNESS, VOLUME, FLASHLIGHT }

    private static Target target;
    private static long atElapsedMs;
    /** The level before the last change, when Orbit knows it, for "put it back". */
    private static int previousPercent = -1;
    private static boolean previousFlashlightOn;

    private RecentActionContext() {}

    /** Forgets everything. Used at the start of a fresh invocation and by tests. */
    public static synchronized void clear() {
        target = null;
        atElapsedMs = 0L;
        previousPercent = -1;
        previousFlashlightOn = false;
    }

    /**
     * Records a level change Orbit performed.
     *
     * @param previous the level before the change, or -1 when Orbit could not read it. Only a
     *                 real reading is stored, because reversal must never invent history.
     */
    public static synchronized void recordLevel(Target changed, int previous) {
        if (changed == null) return;
        target = changed;
        atElapsedMs = SystemClock.elapsedRealtime();
        previousPercent = previous >= 0 && previous <= 100 ? previous : -1;
    }

    /** Records a flashlight change Orbit performed. */
    public static synchronized void recordFlashlight(boolean nowOn) {
        target = Target.FLASHLIGHT;
        atElapsedMs = SystemClock.elapsedRealtime();
        previousFlashlightOn = !nowOn;
        previousPercent = -1;
    }

    /** The remembered target, or null when there is none or it has aged out. */
    public static synchronized Target current() {
        if (target == null) return null;
        if (SystemClock.elapsedRealtime() - atElapsedMs > WINDOW_MS) return null;
        return target;
    }

    /** The level before the last change, or -1 when Orbit does not actually know it. */
    public static synchronized int previousPercent() {
        return current() == null ? -1 : previousPercent;
    }

    /** The flashlight state before the last change. Only meaningful for a flashlight target. */
    public static synchronized boolean previousFlashlightOn() {
        return previousFlashlightOn;
    }

    /**
     * Whether a phrase is a bare follow-up: it asks for something, but names no target of its
     * own. Only these may borrow the remembered target.
     */
    public static boolean isBareFollowUp(String canonical) {
        if (canonical == null) return false;
        String q = canonical.trim();
        if (q.isEmpty()) return false;
        // Naming any target makes this an ordinary command, not a follow-up.
        if (q.matches(".*\\b(brightness|screen|display|volume|sound|audio|flashlight|torch)\\b.*")) {
            return false;
        }
        return true;
    }
}
