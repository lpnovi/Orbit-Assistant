package com.orbit.assistant;

import android.graphics.Color;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Orbit's named colors, defined exactly once.
 *
 * <p>Before this class the same seven colors were written down four times: two key arrays, two
 * label arrays, and an {@code if} chain in {@code UiKit} that turned a key into a value. Four
 * copies agreed because somebody kept them agreeing, and the moment Theme Studio started shipping
 * presets of its own the drift showed: Nebula's accent was stored as {@code #8B7CFF}, which is
 * Violet to the byte, and the editor described it as a custom color because nothing knew the two
 * were the same. Orbit does not have an old palette and a Theme Studio palette. It has this one.
 *
 * <p>So a named color is a single entry here, and everything else is derived from it: the accent
 * menu, the bubble menu, the Theme Studio pickers, the built-in presets, and the migration that
 * puts a name back on a stored hex value. {@link #keyForColor} is what makes that last one
 * possible, and it is why a preset written as a raw hex can be recognised as the palette color it
 * always was rather than staying anonymous forever.
 *
 * <p>Dynamic is deliberately not an entry. It is not a color, it is an instruction to ask the
 * system for one, and folding it in here would let a hex value be canonicalised into a token whose
 * meaning changes when the user changes their wallpaper.
 */
public final class OrbitPalette {
    private OrbitPalette() {}

    /** One named Orbit color: the token it is stored as, the words shown for it, and its value. */
    public static final class Entry {
        public final String key;
        public final String label;
        public final int color;

        Entry(String key, String label, int color) {
            this.key = key;
            this.label = label;
            this.color = color;
        }
    }

    /** Follow the Samsung/Material system accent. A behaviour, not a color. */
    public static final String DYNAMIC = "dynamic";
    /** On a bubble: whatever Orbit's own design says this bubble is. */
    public static final String CLASSIC = "classic";
    /** On a bubble: follow the accent in force. */
    public static final String ACCENT = "accent";

    public static final String NOVA = "nova";

    /**
     * Orbit's named colors, in menu order.
     *
     * <p>These values are the ones Orbit has always shipped. Changing one changes the appearance of
     * every install that chose it, so they are moved only deliberately, never as a side effect of a
     * refactor.
     */
    private static final List<Entry> ENTRIES = Collections.unmodifiableList(java.util.Arrays.asList(
            new Entry("blurple", "Blurple", Color.rgb(88, 101, 242)),
            new Entry("violet", "Violet", Color.rgb(139, 124, 255)),
            new Entry("blue", "Blue", Color.rgb(80, 151, 255)),
            new Entry("mint", "Mint", Color.rgb(69, 204, 166)),
            new Entry("rose", "Rose", Color.rgb(244, 110, 150)),
            new Entry(NOVA, "Nova", Color.rgb(76, 0, 255)),
            new Entry("pastel_pink", "Pastel Pink", Color.rgb(255, 209, 220)),
            new Entry("pastel_blue", "Pastel Blue", Color.rgb(203, 229, 242))));

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** The value of a named color, or {@code fallback} when the key names none of them. */
    public static int colorFor(String key, int fallback) {
        Entry entry = entry(key);
        return entry == null ? fallback : entry.color;
    }

    public static Entry entry(String key) {
        if (key == null) return null;
        String trimmed = key.trim();
        for (Entry entry : ENTRIES) if (entry.key.equals(trimmed)) return entry;
        return null;
    }

    public static boolean isNamed(String key) {
        return entry(key) != null;
    }

    /** The words shown for a token, whether it is a named color, a behaviour, or a hex value. */
    public static String labelFor(String token) {
        if (token == null) return "Dynamic";
        if (DYNAMIC.equals(token.trim())) return "Dynamic";
        if (CLASSIC.equals(token.trim())) return "Classic";
        if (ACCENT.equals(token.trim())) return "Accent";
        Entry entry = entry(token);
        if (entry != null) return entry.label;
        String hex = OrbitTheme.parseHexToken(token);
        if (hex == null) return "Dynamic";
        // A hex value that happens to be one of Orbit's own colours is that colour, and calling it
        // custom is how the app came to look as though it had a palette and a second palette.
        Entry named = entry(keyForColor(OrbitTheme.hexTokenColor(hex)));
        return named != null ? named.label : "custom " + hex;
    }

    /**
     * The palette key naming this exact color, or null when no named color has that value.
     *
     * <p>Used by migration to give a stored {@code #RRGGBB} its name back. Exact equality only: a
     * value one channel away from Violet is a color somebody chose, not Violet spelled badly.
     */
    public static String keyForColor(int color) {
        int rgb = color & 0x00FFFFFF;
        for (Entry entry : ENTRIES) {
            if ((entry.color & 0x00FFFFFF) == rgb) return entry.key;
        }
        return null;
    }

    /** As {@link #keyForColor}, taking a {@code #RRGGBB} token. Null for anything else. */
    public static String keyForHexToken(String token) {
        String hex = OrbitTheme.parseHexToken(token);
        if (hex == null) return null;
        return keyForColor(OrbitTheme.hexTokenColor(hex));
    }

    /** The tokens the accent menu offers: Dynamic, then every named color. */
    public static String[] accentKeys() {
        String[] out = new String[ENTRIES.size() + 1];
        out[0] = DYNAMIC;
        for (int i = 0; i < ENTRIES.size(); i++) out[i + 1] = ENTRIES.get(i).key;
        return out;
    }

    public static String[] accentLabels() {
        String[] keys = accentKeys();
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) out[i] = labelFor(keys[i]);
        return out;
    }

    /** The tokens a conversation bubble may take: Classic and Accent, then every named color. */
    public static String[] bubbleKeys() {
        String[] out = new String[ENTRIES.size() + 2];
        out[0] = CLASSIC;
        out[1] = ACCENT;
        for (int i = 0; i < ENTRIES.size(); i++) out[i + 2] = ENTRIES.get(i).key;
        return out;
    }

    public static String[] bubbleLabels() {
        String[] keys = bubbleKeys();
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) out[i] = labelFor(keys[i]);
        return out;
    }

    /** The token a color is written as when it has a name, and its hex value when it does not. */
    public static String tokenFor(int color) {
        String named = keyForColor(color);
        return named != null ? named : OrbitTheme.colorToken(color);
    }

    /** Uppercase {@code #RRGGBB} for a named color, for documentation and diagnostics. */
    static String hexOf(String key) {
        Entry entry = entry(key);
        if (entry == null) return "";
        return String.format(Locale.US, "#%02X%02X%02X",
                Color.red(entry.color), Color.green(entry.color), Color.blue(entry.color));
    }
}
