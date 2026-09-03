package com.orbit.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * The small curated set of icons a user may put on their own tile.
 *
 * <p>Ten choices, not an icon library. A Prompt tile is a personal shortcut, so it earns a way to
 * be recognised at a glance, but a picker with hundreds of glyphs would be a different feature and
 * would drag a dependency in behind it. These are Orbit's own vectors in Orbit's own line weight,
 * which is why they sit next to the built-in tiles without looking borrowed.
 *
 * <p>Keys are storage identity and never change. Labels are what the picker reads out.
 */
public final class DeckIcons {

    public static final String DEFAULT_KEY = "sparkle";

    private static final Map<String, Integer> ICONS = new LinkedHashMap<>();
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    private static void put(String key, int res, String label) {
        ICONS.put(key, res);
        LABELS.put(key, label);
    }

    static {
        put("sparkle", R.drawable.ic_deck_sparkle, "Sparkle");
        put("chat", R.drawable.ic_deck_chat, "Chat");
        put("brain", R.drawable.ic_deck_brain, "Brain");
        put("school", R.drawable.ic_deck_school, "Study");
        put("work", R.drawable.ic_deck_work, "Work");
        put("heart", R.drawable.ic_deck_heart, "Heart");
        put("list", R.drawable.ic_deck_list, "List");
        put("star", R.drawable.ic_deck_star, "Star");
        put("code", R.drawable.ic_deck_code, "Code");
        put("note", R.drawable.ic_deck_note, "Note");
    }

    private DeckIcons() {}

    /** The drawable for a stored key, falling back to the default rather than to nothing. */
    public static int resFor(String key) {
        Integer res = ICONS.get(key == null ? "" : key);
        return res == null ? ICONS.get(DEFAULT_KEY) : res;
    }

    public static String labelFor(String key) {
        String label = LABELS.get(key == null ? "" : key);
        return label == null ? LABELS.get(DEFAULT_KEY) : label;
    }

    public static boolean knows(String key) {
        return key != null && ICONS.containsKey(key);
    }

    /** Every key, in picker order. */
    public static List<String> keys() {
        return Collections.unmodifiableList(new ArrayList<>(ICONS.keySet()));
    }
}
