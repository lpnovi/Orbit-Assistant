package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure bounded-window and stale-result policy shared by the renderer and its tests. */
public final class DocumentRenderWindow {
    private long generation;

    public long nextGeneration() { return ++generation; }
    public boolean accepts(long candidate) { return candidate == generation; }

    public static List<Integer> around(int page, int count) {
        if (count <= 0) return Collections.emptyList();
        int current = Math.max(0, Math.min(page, count - 1));
        List<Integer> pages = new ArrayList<>(3);
        if (current > 0) pages.add(current - 1);
        pages.add(current);
        if (current + 1 < count) pages.add(current + 1);
        return Collections.unmodifiableList(pages);
    }
}
