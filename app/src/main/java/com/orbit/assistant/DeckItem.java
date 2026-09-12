package com.orbit.assistant;

/** One ordered root item in a Deck layout. */
public abstract class DeckItem {
    public enum Kind { TILE, SECTION, FOLDER }

    public final String id;
    public final Kind kind;

    DeckItem(String id, Kind kind) {
        this.id = id == null || id.trim().isEmpty() ? DeckTile.newInstanceId() : id.trim();
        this.kind = kind;
    }
}
