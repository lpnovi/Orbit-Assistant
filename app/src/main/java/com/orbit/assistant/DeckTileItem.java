package com.orbit.assistant;

/** Root structural wrapper for an executable Deck tile. */
public final class DeckTileItem extends DeckItem {
    public final DeckTile tile;

    public DeckTileItem(DeckTile tile) {
        super(tile == null ? null : tile.instanceId, Kind.TILE);
        this.tile = tile;
    }
}
