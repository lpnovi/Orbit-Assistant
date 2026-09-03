package com.orbit.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One tile on the user's Deck: what kind it is, how big it is, and what it was configured with.
 *
 * <p>Deliberately split from {@link DeckTileRegistry.Definition}. The definition is the <i>kind</i>
 * of tile Orbit knows how to build ("a Routine tile"); an instance is the one the user actually
 * placed ("Goodnight, wide"). Keeping them apart is what lets the same definition appear three
 * times with three different configurations, and what lets a future Orbit add a field to a
 * definition without touching anything the user has saved.
 *
 * <p>Identity is the stable {@link #type} string and the tile's own {@link #instanceId}, never a
 * display string. A Routine renamed from "Goodnight" to "Bed" is the same tile; two tiles both
 * called "Work" are not.
 *
 * <p>Configuration is a plain string map rather than typed fields on purpose. It survives a
 * round-trip through storage even when this build has never heard of a key in it, so a layout
 * written by a newer Orbit is not quietly stripped of everything this one does not understand.
 */
public final class DeckTile {

    /** How much of the grid one tile occupies. Deliberately two choices, not free resizing. */
    public enum Size {
        /** One grid column. */
        STANDARD,
        /** Two grid columns, and the only size that earns a second line of metadata. */
        WIDE;

        public String key() { return this == WIDE ? "wide" : "standard"; }

        public static Size fromKey(String key) {
            return "wide".equals(key) ? WIDE : STANDARD;
        }
    }

    /**
     * Whether a configured tile can actually do its job right now.
     *
     * <p>A tile the user placed is never silently deleted, so these three states are how Orbit says
     * "this used to work" without throwing the user's layout away.
     */
    public enum Availability {
        /** Ready to run. */
        AVAILABLE,
        /**
         * Known, but not usable at this moment: a permission is missing, or a device feature is
         * absent. Tapping it should route to whatever grants it.
         */
        UNAVAILABLE,
        /**
         * The thing it points at is gone: the Routine was deleted, the app was uninstalled, or this
         * build has no definition for its type. Tapping it offers reconfiguration or removal.
         */
        UNRESOLVED
    }

    // ---- configuration keys ----------------------------------------------------------------------

    /** Saved Routine id for {@link DeckTileRegistry#TYPE_ROUTINE}. */
    public static final String CONFIG_ROUTINE_ID = "routineId";
    /** Package name for {@link DeckTileRegistry#TYPE_APP}. */
    public static final String CONFIG_PACKAGE = "package";
    /** Composer text for {@link DeckTileRegistry#TYPE_PROMPT}. Never sent on its own. */
    public static final String CONFIG_PROMPT = "prompt";
    /** Curated icon key for a Prompt tile, from {@link DeckIcons}. */
    public static final String CONFIG_ICON = "icon";
    /** A user-chosen name shown instead of the definition's title. */
    public static final String CONFIG_TITLE = "title";

    /** Longest tile name Orbit will store, so a label cannot push a grid tile out of shape. */
    public static final int MAX_TITLE_LENGTH = 24;
    /** Longest prompt Orbit will store for one tile. */
    public static final int MAX_PROMPT_LENGTH = 2000;

    public final String instanceId;
    public final String type;
    public final Size size;
    private final Map<String, String> config;

    DeckTile(String instanceId, String type, Size size, Map<String, String> config) {
        this.instanceId = instanceId == null || instanceId.trim().isEmpty()
                ? newInstanceId() : instanceId.trim();
        this.type = type == null ? "" : type.trim();
        this.size = size == null ? Size.STANDARD : size;
        Map<String, String> copy = new LinkedHashMap<>();
        if (config != null) {
            for (Map.Entry<String, String> entry : config.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        this.config = Collections.unmodifiableMap(copy);
    }

    public static String newInstanceId() {
        return UUID.randomUUID().toString();
    }

    /** A tile of {@code type} with no configuration yet. */
    public static DeckTile of(String type, Size size) {
        return new DeckTile(newInstanceId(), type, size, null);
    }

    public static DeckTile of(String type, Size size, Map<String, String> config) {
        return new DeckTile(newInstanceId(), type, size, config);
    }

    /**
     * A tile whose instance id is derived from its type rather than generated.
     *
     * <p>Used only for the default Deck, and load-bearing rather than cosmetic. Reading an
     * unconfigured Deck builds the defaults fresh each time, so if those ids were random then the
     * id a caller had just read would not be the id the next read produced, and removing or
     * reordering a tile on a Deck nobody had customised yet would silently match nothing. Deriving
     * them from the type makes the default Deck the same Deck every time it is built.
     */
    static DeckTile fixed(String type, Size size) {
        return new DeckTile("default:" + type, type, size, null);
    }

    public String config(String key) {
        String value = config.get(key);
        return value == null ? "" : value;
    }

    public boolean hasConfig(String key) {
        return !config(key).isEmpty();
    }

    /** The whole configuration, including keys this build does not understand. */
    public Map<String, String> configMap() {
        return config;
    }

    public DeckTile withSize(Size newSize) {
        return new DeckTile(instanceId, type, newSize, config);
    }

    /**
     * The same tile with one configuration value replaced.
     *
     * <p>Every unrelated key is carried across untouched, including any this build did not write,
     * so editing a tile's name cannot strip a field a newer Orbit stored beside it.
     */
    public DeckTile withConfig(String key, String value) {
        if (key == null) return this;
        Map<String, String> next = new LinkedHashMap<>(config);
        if (value == null || value.isEmpty()) next.remove(key);
        else next.put(key, value);
        return new DeckTile(instanceId, type, size, next);
    }

    /** A copy under a fresh instance id, used when the same kind of tile is added twice. */
    DeckTile withNewInstanceId() {
        return new DeckTile(newInstanceId(), type, size, config);
    }

    /**
     * What distinguishes two tiles of the same type for duplicate detection.
     *
     * <p>Two Routine tiles pointing at the same Routine are a duplicate; two Prompt tiles with
     * different text are not. Built from configuration rather than from the display name, so
     * renaming a tile cannot turn a duplicate into a non-duplicate.
     */
    String duplicateKey() {
        if (DeckTileRegistry.TYPE_ROUTINE.equals(type)) return type + ":" + config(CONFIG_ROUTINE_ID);
        if (DeckTileRegistry.TYPE_APP.equals(type)) return type + ":" + config(CONFIG_PACKAGE);
        if (DeckTileRegistry.TYPE_PROMPT.equals(type)) return type + ":" + config(CONFIG_PROMPT);
        return type;
    }

    /** Trims a user-supplied tile name to something a tile can actually render. */
    public static String sanitizeTitle(String raw) {
        if (raw == null) return "";
        String trimmed = raw.replace('\n', ' ').replace('\r', ' ').trim();
        while (trimmed.contains("  ")) trimmed = trimmed.replace("  ", " ");
        if (trimmed.length() > MAX_TITLE_LENGTH) trimmed = trimmed.substring(0, MAX_TITLE_LENGTH).trim();
        return trimmed;
    }

    /** Trims a user-supplied prompt. Newlines survive: a prompt may legitimately have them. */
    public static String sanitizePrompt(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_PROMPT_LENGTH) trimmed = trimmed.substring(0, MAX_PROMPT_LENGTH).trim();
        return trimmed;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DeckTile)) return false;
        DeckTile that = (DeckTile) other;
        return instanceId.equals(that.instanceId) && type.equals(that.type)
                && size == that.size && config.equals(that.config);
    }

    @Override public int hashCode() {
        return instanceId.hashCode() * 31 + type.hashCode();
    }
}
