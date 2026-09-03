package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every kind of tile Orbit knows how to put on a Deck, declared once.
 *
 * <p>This is the file a future capability is added to. A new tile needs a definition here, a branch
 * in {@link DeckActionExecutor} saying how to run it, and — if it shows live state — a branch in
 * {@link DeckTileResolver}. Nothing else: the grid, the editor, the Add sheet, persistence and
 * accessibility all read this table rather than knowing tile names, which is what stops Deck
 * becoming one long switch statement spread across three Activities.
 *
 * <p>Type ids are stable storage identity and must never be renamed once shipped. Titles are
 * display text and may change freely, because nothing is ever saved by title.
 */
public final class DeckTileRegistry {

    // ---- type ids (storage identity: never rename) ------------------------------------------------

    public static final String TYPE_NEW_CHAT = "orbit.new_chat";
    public static final String TYPE_ROUTINES = "orbit.routines";
    public static final String TYPE_REMINDERS = "orbit.reminders";
    public static final String TYPE_MEMORIES = "orbit.memories";
    public static final String TYPE_CAPABILITIES = "orbit.capabilities";
    public static final String TYPE_EXTENSIONS = "orbit.extensions";
    public static final String TYPE_SETTINGS = "orbit.settings";
    public static final String TYPE_FLASHLIGHT = "action.flashlight";
    public static final String TYPE_MEDIA = "action.media";
    public static final String TYPE_ROUTINE = "routine";
    public static final String TYPE_APP = "app";
    public static final String TYPE_PROMPT = "prompt";

    /** The Add-tile groupings. Deliberately five, so the sheet stays one calm screen. */
    public enum Category {
        ORBIT("Orbit"),
        ACTIONS("Actions"),
        ROUTINES("Routines"),
        APPS("Apps"),
        PROMPT("Prompt");

        public final String label;
        Category(String label) { this.label = label; }
    }

    /**
     * One kind of tile.
     *
     * @param singleton    whether more than one may sit on a Deck. A destination is a place, so a
     *                     second copy of it is clutter; a Routine tile is a choice, so several are
     *                     the whole point.
     * @param configurable whether placing it requires the user to choose something first.
     */
    public static final class Definition {
        public final String type;
        public final String title;
        public final String description;
        public final int iconRes;
        public final EnumSet<DeckTile.Size> sizes;
        public final boolean singleton;
        public final boolean configurable;
        public final Category category;
        /** What TalkBack calls this kind of tile, read after the tile's own name. */
        public final String roleLabel;

        Definition(String type, String title, String description, int iconRes,
                   EnumSet<DeckTile.Size> sizes, boolean singleton, boolean configurable,
                   Category category, String roleLabel) {
            this.type = type;
            this.title = title;
            this.description = description;
            this.iconRes = iconRes;
            this.sizes = sizes;
            this.singleton = singleton;
            this.configurable = configurable;
            this.category = category;
            this.roleLabel = roleLabel;
        }

        public boolean supports(DeckTile.Size size) {
            return size != null && sizes.contains(size);
        }

        /** The size a tile of this kind falls back to when a stored one is not supported. */
        public DeckTile.Size defaultSize() {
            return sizes.contains(DeckTile.Size.STANDARD) ? DeckTile.Size.STANDARD : DeckTile.Size.WIDE;
        }
    }

    private static final EnumSet<DeckTile.Size> BOTH =
            EnumSet.of(DeckTile.Size.STANDARD, DeckTile.Size.WIDE);
    private static final EnumSet<DeckTile.Size> STANDARD_ONLY =
            EnumSet.of(DeckTile.Size.STANDARD);

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    private static void put(Definition definition) {
        if (DEFINITIONS.containsKey(definition.type)) {
            throw new IllegalStateException("duplicate Deck tile type: " + definition.type);
        }
        DEFINITIONS.put(definition.type, definition);
    }

    static {
        // ---- Orbit destinations: places the user already has, one tap closer -------------------
        put(new Definition(TYPE_NEW_CHAT, "New chat", "Start a new Orbit conversation",
                R.drawable.ic_deck_new_chat, BOTH, true, false, Category.ORBIT, "Orbit shortcut"));
        put(new Definition(TYPE_ROUTINES, "Routines", "Open your saved Routines",
                R.drawable.ic_routine_tile, BOTH, true, false, Category.ORBIT, "Orbit shortcut"));
        put(new Definition(TYPE_REMINDERS, "Reminders", "Open your Orbit reminders",
                R.drawable.ic_widget_reminder, BOTH, true, false, Category.ORBIT, "Orbit shortcut"));
        put(new Definition(TYPE_MEMORIES, "Memories", "What Orbit remembers about you",
                R.drawable.ic_deck_memory, BOTH, true, false, Category.ORBIT, "Orbit shortcut"));
        put(new Definition(TYPE_CAPABILITIES, "Capabilities", "What Orbit can do on this phone",
                R.drawable.ic_deck_capabilities, BOTH, true, false, Category.ORBIT, "Orbit shortcut"));
        put(new Definition(TYPE_EXTENSIONS, "Extensions", "Manage your Orbit extensions",
                R.drawable.ic_deck_extension, BOTH, true, false, Category.ORBIT, "Orbit shortcut"));
        put(new Definition(TYPE_SETTINGS, "Settings", "Open Orbit settings",
                R.drawable.ic_settings, BOTH, true, false, Category.ORBIT, "Orbit shortcut"));

        // ---- Direct device actions: immediate, and already implemented elsewhere ---------------
        put(new Definition(TYPE_FLASHLIGHT, "Flashlight", "Turn the flashlight on or off",
                R.drawable.ic_widget_flashlight, BOTH, true, false, Category.ACTIONS, "Action"));
        put(new Definition(TYPE_MEDIA, "Play / Pause", "Control whatever is playing",
                R.drawable.ic_deck_media, BOTH, true, false, Category.ACTIONS, "Action"));

        // ---- Configured instances: the same definition, placed as many times as wanted ---------
        put(new Definition(TYPE_ROUTINE, "Routine", "Run one of your saved Routines",
                R.drawable.ic_routine_tile, BOTH, false, true, Category.ROUTINES, "Routine"));
        // Standard only, deliberately. The wide slot exists to carry Orbit-owned secondary
        // information, and an app shortcut has none: the only extra text is the app's own label,
        // which is already the tile's title. A wide tile would be somebody else's icon and nothing
        // more, which is exactly the third-party visual takeover Deck is meant to avoid.
        put(new Definition(TYPE_APP, "App", "Launch an installed app",
                R.drawable.ic_deck_app, STANDARD_ONLY, false, true, Category.APPS, "App shortcut"));
        put(new Definition(TYPE_PROMPT, "Prompt", "Open a new chat with your text ready",
                R.drawable.ic_deck_sparkle, BOTH, false, true, Category.PROMPT, "Prompt shortcut"));
    }

    private DeckTileRegistry() {}

    /** The definition for a stored type, or null when this build has never heard of it. */
    public static Definition definition(String type) {
        return type == null ? null : DEFINITIONS.get(type);
    }

    public static boolean knows(String type) {
        return definition(type) != null;
    }

    /** Every definition in declaration order, which is the order the Add sheet lists them in. */
    public static List<Definition> all() {
        return Collections.unmodifiableList(new ArrayList<>(DEFINITIONS.values()));
    }

    public static List<Definition> inCategory(Category category) {
        List<Definition> out = new ArrayList<>();
        for (Definition definition : DEFINITIONS.values()) {
            if (definition.category == category) out.add(definition);
        }
        return out;
    }

    /** The categories that actually contain something, in declaration order. */
    public static List<Category> categories() {
        List<Category> out = new ArrayList<>();
        for (Category category : Category.values()) {
            if (!inCategory(category).isEmpty()) out.add(category);
        }
        return out;
    }

    /**
     * The size a stored tile may actually be shown at.
     *
     * <p>A layout can name a size its definition does not support — an older Orbit reading a newer
     * layout, or a definition that dropped a size — so every read passes through here rather than
     * trusting what was written.
     */
    public static DeckTile.Size coerceSize(String type, DeckTile.Size requested) {
        Definition definition = definition(type);
        if (definition == null) return requested == null ? DeckTile.Size.STANDARD : requested;
        if (definition.supports(requested)) return requested;
        return definition.defaultSize();
    }
}
