package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One complete, self-contained named Deck. */
public final class DeckLayout {
    public static final String PRIMARY_ID = "primary";
    public static final String DEFAULT_NAME = "My Deck";
    public static final int MAX_NAME_LENGTH = 30;
    public final String id;
    public final String name;
    public final List<DeckItem> items;

    public DeckLayout(String id, List<DeckItem> items) {
        this(id, DEFAULT_NAME, items);
    }

    public DeckLayout(String id, String name, List<DeckItem> items) {
        this.id = id == null || id.trim().isEmpty() ? PRIMARY_ID : id.trim();
        String sanitized = sanitizeName(name);
        this.name = sanitized.isEmpty() ? DEFAULT_NAME : sanitized;
        this.items = Collections.unmodifiableList(items == null
                ? new ArrayList<>() : new ArrayList<>(items));
    }

    public DeckLayout withName(String value) {
        return new DeckLayout(id, value, items);
    }

    public DeckLayout withItems(List<DeckItem> value) {
        return new DeckLayout(id, name, value);
    }

    public static String sanitizeName(String raw) {
        String value = raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ').trim();
        while (value.contains("  ")) value = value.replace("  ", " ");
        if (value.length() > MAX_NAME_LENGTH) {
            value = value.substring(0, MAX_NAME_LENGTH).trim();
        }
        return value;
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
