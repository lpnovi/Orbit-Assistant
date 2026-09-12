package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One-level organizational container. Children are action tiles only. */
public final class DeckFolder extends DeckItem {
    public static final int MAX_TITLE_LENGTH = 32;
    public static final int MAX_CHILDREN = 24;

    public final String title;
    public final List<DeckTile> tiles;

    public DeckFolder(String id, String title, List<DeckTile> tiles) {
        super(id, Kind.FOLDER);
        this.title = sanitizeTitle(title);
        List<DeckTile> copy = new ArrayList<>();
        if (tiles != null) {
            for (DeckTile tile : tiles) if (tile != null) copy.add(tile);
        }
        this.tiles = Collections.unmodifiableList(copy);
    }

    public DeckFolder withTitle(String value) { return new DeckFolder(id, value, tiles); }
    public DeckFolder withTiles(List<DeckTile> value) { return new DeckFolder(id, title, value); }

    public static String sanitizeTitle(String raw) {
        String value = raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ').trim();
        while (value.contains("  ")) value = value.replace("  ", " ");
        if (value.length() > MAX_TITLE_LENGTH) value = value.substring(0, MAX_TITLE_LENGTH).trim();
        return value.isEmpty() ? "New folder" : value;
    }
}
