package com.orbit.assistant;

/** A full-width marker; it owns no tiles, so removing it cannot remove content. */
public final class DeckSection extends DeckItem {
    public static final int MAX_TITLE_LENGTH = 32;
    public final String title;

    public DeckSection(String id, String title) {
        super(id, Kind.SECTION);
        this.title = sanitizeTitle(title);
    }

    public DeckSection withTitle(String value) { return new DeckSection(id, value); }

    public static String sanitizeTitle(String raw) {
        String value = raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ').trim();
        while (value.contains("  ")) value = value.replace("  ", " ");
        if (value.length() > MAX_TITLE_LENGTH) value = value.substring(0, MAX_TITLE_LENGTH).trim();
        return value.isEmpty() ? "New section" : value;
    }
}
