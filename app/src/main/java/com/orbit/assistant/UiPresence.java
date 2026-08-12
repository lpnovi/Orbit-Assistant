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
}
