package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable schema-3 owner of every saved Deck and the active layout identity. */
public final class DeckCollection {
    public final String activeLayoutId;
    public final List<DeckLayout> layouts;

    public DeckCollection(String activeLayoutId, List<DeckLayout> layouts) {
        List<DeckLayout> copy = new ArrayList<>();
        if (layouts != null) {
            for (DeckLayout layout : layouts) if (layout != null) copy.add(layout);
        }
        this.layouts = Collections.unmodifiableList(copy);
        String active = activeLayoutId == null ? "" : activeLayoutId.trim();
        if (layout(active) == null && !copy.isEmpty()) active = copy.get(0).id;
        this.activeLayoutId = active;
    }

    public DeckLayout active() {
        DeckLayout active = layout(activeLayoutId);
        return active != null ? active : (layouts.isEmpty() ? null : layouts.get(0));
    }

    public DeckLayout layout(String id) {
        if (id == null) return null;
        for (DeckLayout layout : layouts) if (id.equals(layout.id)) return layout;
        return null;
    }
}
