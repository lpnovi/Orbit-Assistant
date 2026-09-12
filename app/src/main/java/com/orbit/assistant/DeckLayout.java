package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One complete, self-contained Deck, ready to become a saved-layout value in Beta 7. */
public final class DeckLayout {
    public static final String PRIMARY_ID = "primary";
    public final String id;
    public final List<DeckItem> items;

    public DeckLayout(String id, List<DeckItem> items) {
        this.id = id == null || id.trim().isEmpty() ? PRIMARY_ID : id.trim();
        this.items = Collections.unmodifiableList(items == null
                ? new ArrayList<>() : new ArrayList<>(items));
    }

    public List<DeckTile> rootTiles() {
        List<DeckTile> out = new ArrayList<>();
        for (DeckItem item : items) if (item instanceof DeckTileItem) out.add(((DeckTileItem) item).tile);
        return out;
    }

    public List<DeckTile> allTiles() {
        List<DeckTile> out = rootTiles();
        for (DeckItem item : items) if (item instanceof DeckFolder) out.addAll(((DeckFolder) item).tiles);
        return out;
    }

    public DeckFolder folder(String folderId) {
        for (DeckItem item : items) {
            if (item instanceof DeckFolder && item.id.equals(folderId)) return (DeckFolder) item;
        }
        return null;
    }
}
