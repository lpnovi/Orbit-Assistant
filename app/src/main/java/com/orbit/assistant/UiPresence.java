package com.orbit.assistant;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Process-local visibility tracker so background completion alerts stay quiet while Orbit is on screen. */
public final class UiPresence {
    private static final Set<Object> VISIBLE = Collections.newSetFromMap(new WeakHashMap<>());
    private UiPresence() {}

    public static synchronized void enter(Object owner) { if (owner != null) VISIBLE.add(owner); }
    public static synchronized void leave(Object owner) { if (owner != null) VISIBLE.remove(owner); }
    public static synchronized boolean isVisible() { return !VISIBLE.isEmpty(); }

    /**
     * Forgets every registered owner.
     *
     * <p>Production never calls this: owners are held weakly and released by their own onPause.
     * Tests of background behaviour need it, because a screen another test opened and never paused
     * stays registered until it is collected, which would otherwise make "is Orbit on screen"
     * depend on the order the suite happened to run in.
     */
    static synchronized void clearForTests() { VISIBLE.clear(); }
}
