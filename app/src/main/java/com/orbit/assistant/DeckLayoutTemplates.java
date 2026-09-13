package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Three curated, user-neutral starting points for a new saved Deck. */
public final class DeckLayoutTemplates {
    public static final String ESSENTIALS = "essentials";
    public static final String FOCUS = "focus";
    public static final String COMMAND_CENTER = "command_center";

    public static final class Template {
        public final String id;
        public final String name;
        public final String description;

        private Template(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }

    private static final List<Template> TEMPLATES = Collections.unmodifiableList(Arrays.asList(
            new Template(ESSENTIALS, "Orbit Essentials", "Orbit's everyday destinations and device controls"),
            new Template(FOCUS, "Focus", "A quieter Deck for conversations, reminders, and routines"),
            new Template(COMMAND_CENTER, "Command Center", "A structured dashboard for Orbit tools and actions")
    ));

    private DeckLayoutTemplates() {}

    public static List<Template> all() {
        return TEMPLATES;
    }

    public static DeckLayout create(Context context, String templateId, String requestedName) {
        Template template = find(templateId);
        if (template == null) return null;
        List<DeckItem> items = new ArrayList<>();
        if (ESSENTIALS.equals(templateId)) essentials(context, items);
        else if (FOCUS.equals(templateId)) focus(context, items);
        else if (COMMAND_CENTER.equals(templateId)) commandCenter(context, items);
        String name = DeckLayout.sanitizeName(requestedName);
        if (name.isEmpty()) name = template.name;
        return new DeckLayout(DeckTile.newInstanceId(), name, items);
    }

    private static Template find(String id) {
        for (Template template : TEMPLATES) if (template.id.equals(id)) return template;
        return null;
    }

    private static void essentials(Context context, List<DeckItem> items) {
        items.add(new DeckSection(DeckTile.newInstanceId(), "Everyday"));
        addItem(context, items, DeckTileRegistry.TYPE_NEW_CHAT, DeckTile.Size.WIDE);
        addItem(context, items, DeckTileRegistry.TYPE_REMINDERS, DeckTile.Size.STANDARD);
        addItem(context, items, DeckTileRegistry.TYPE_MEMORIES, DeckTile.Size.STANDARD);
        addItem(context, items, DeckTileRegistry.TYPE_QUICK_CAPTURE, DeckTile.Size.WIDE);
        List<DeckTile> actions = new ArrayList<>();
        addTile(context, actions, DeckTileRegistry.TYPE_FLASHLIGHT, DeckTile.Size.STANDARD);
        addTile(context, actions, DeckTileRegistry.TYPE_MEDIA, DeckTile.Size.STANDARD);
        if (!actions.isEmpty()) {
            items.add(new DeckFolder(DeckTile.newInstanceId(), "Device", actions));
        }
    }

    private static void focus(Context context, List<DeckItem> items) {
        items.add(new DeckSection(DeckTile.newInstanceId(), "Focus"));
        addItem(context, items, DeckTileRegistry.TYPE_NEW_CHAT, DeckTile.Size.LARGE);
        addItem(context, items, DeckTileRegistry.TYPE_REMINDERS, DeckTile.Size.WIDE);
        addItem(context, items, DeckTileRegistry.TYPE_ROUTINES, DeckTile.Size.WIDE);
        addItem(context, items, DeckTileRegistry.TYPE_MEMORIES, DeckTile.Size.STANDARD);
    }

    private static void commandCenter(Context context, List<DeckItem> items) {
        items.add(new DeckSection(DeckTile.newInstanceId(), "Orbit"));
        addItem(context, items, DeckTileRegistry.TYPE_NEW_CHAT, DeckTile.Size.WIDE);
        addItem(context, items, DeckTileRegistry.TYPE_CAPABILITIES, DeckTile.Size.STANDARD);
        addItem(context, items, DeckTileRegistry.TYPE_SETTINGS, DeckTile.Size.STANDARD);
        addItem(context, items, DeckTileRegistry.TYPE_THEME_STUDIO, DeckTile.Size.WIDE);
        List<DeckTile> actions = new ArrayList<>();
        addTile(context, actions, DeckTileRegistry.TYPE_FLASHLIGHT, DeckTile.Size.STANDARD);
        addTile(context, actions, DeckTileRegistry.TYPE_MEDIA, DeckTile.Size.STANDARD);
        if (!actions.isEmpty()) {
            items.add(new DeckFolder(DeckTile.newInstanceId(), "Controls", actions));
        }
        addItem(context, items, DeckTileRegistry.TYPE_EXTENSIONS, DeckTile.Size.WIDE);
    }

    private static void addItem(Context context, List<DeckItem> items, String type, DeckTile.Size size) {
        DeckTile tile = offerable(context, type, size);
        if (tile != null) items.add(new DeckTileItem(tile));
    }

    private static void addTile(Context context, List<DeckTile> tiles, String type, DeckTile.Size size) {
        DeckTile tile = offerable(context, type, size);
        if (tile != null) tiles.add(tile);
    }

    private static DeckTile offerable(Context context, String type, DeckTile.Size size) {
        DeckTileRegistry.Definition definition = DeckTileRegistry.definition(type);
        if (!DeckTileRegistry.isOfferable(context, definition)) return null;
        return DeckTile.of(type, DeckTileRegistry.coerceSize(type, size));
    }
}
