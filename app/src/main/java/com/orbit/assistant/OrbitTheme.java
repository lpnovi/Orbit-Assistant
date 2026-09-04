package com.orbit.assistant;

import android.graphics.Color;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One complete Orbit appearance, as a value.
 *
 * <p>Orbit already had an appearance system: an accent key, an AMOLED flag and two bubble-colour
 * keys, each read straight out of {@code Prefs} by whatever needed it. Theme Studio does not
 * replace that and deliberately does not introduce a second engine beside it. This class is the
 * same four things named once, plus the two the editor adds — surface and background — so that a
 * theme can be previewed, saved, named and applied as a single object instead of four unrelated
 * preferences that happen to be edited on the same screen.
 *
 * <p>Every colour field is a <em>token string</em>, not an {@code int}. A token is one of:
 *
 * <ul>
 *   <li>a catalogue key Orbit already understood — {@code dynamic}, {@code violet}, {@code mint} —
 *       which keeps following the system accent or the shipped palette;</li>
 *   <li>{@code classic}, meaning "whatever Orbit's own design says here", which is how a theme
 *       stays correct when Orbit's defaults are retuned in a later release;</li>
 *   <li>{@code accent}, on a bubble, meaning "follow the accent of this theme";</li>
 *   <li>an explicit {@code #RRGGBB}.</li>
 * </ul>
 *
 * <p>That is what makes migration from the four legacy preferences a straight read rather than a
 * conversion: the values users already had are already valid tokens. It is also what keeps derived
 * colours out of storage. A saved theme holds the handful of decisions a person made; every other
 * colour Orbit draws is computed from those, in {@code UiKit}, every time.
 *
 * <p>Instances are immutable and always valid. Anything unparseable — a truncated hex value, a null
 * name, a token from a newer schema — is normalised at construction, so no caller anywhere has to
 * defend against a malformed theme.
 */
public final class OrbitTheme {

    /**
     * The storage schema. Bumped only when the meaning of an existing field changes; adding a new
     * token with a safe default does not need it, because an older file simply lacks the key.
     */
    public static final int SCHEMA = 1;

    /**
     * Format identifier written into every serialised theme.
     *
     * <p>Nothing in this release reads it: Beta 1 has no import. It is written now so that the file
     * a later Beta exports and the file this Beta already stores are the same shape, and an import
     * parser can reject somebody else's JSON without guessing.
     */
    public static final String FORMAT = "orbit.theme";

    // ---- token vocabulary --------------------------------------------------------------------

    /** "Whatever Orbit's own design says here." */
    public static final String CLASSIC = "classic";
    /** On a bubble: follow this theme's accent. */
    public static final String ACCENT = "accent";
    /** On the accent: follow the system/Material accent, as Orbit has always done. */
    public static final String DYNAMIC = "dynamic";

    // ---- identity ----------------------------------------------------------------------------

    /** Stable storage identity. Never derived from the display name, which the user may reuse. */
    public final String id;
    public final String name;
    public final int schema;
    /** True for the presets Orbit ships, which cannot be renamed, edited in place, or deleted. */
    public final boolean builtIn;

    // ---- the six decisions -------------------------------------------------------------------

    public final String accent;
    public final String userBubble;
    public final String assistantBubble;
    public final String surface;
    public final String background;
    public final boolean amoled;

    OrbitTheme(String id, String name, boolean builtIn, String accent, String userBubble,
               String assistantBubble, String surface, String background, boolean amoled) {
        this.id = normalizeId(id);
        this.name = normalizeName(name);
        this.builtIn = builtIn;
        this.schema = SCHEMA;
        this.accent = normalizeAccent(accent);
        this.userBubble = normalizeBubble(userBubble);
        this.assistantBubble = normalizeBubble(assistantBubble);
        this.surface = normalizeSurface(surface);
        this.background = normalizeSurface(background);
        this.amoled = amoled;
    }

    /** A custom theme with a freshly generated identity. */
    public static OrbitTheme custom(String name, String accent, String userBubble,
                                    String assistantBubble, String surface, String background,
                                    boolean amoled) {
        return new OrbitTheme(newId(), name, false, accent, userBubble, assistantBubble,
                surface, background, amoled);
    }

    public static String newId() {
        return "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // ---- derived copies ----------------------------------------------------------------------

    public OrbitTheme withAccent(String value) {
        return new OrbitTheme(id, name, builtIn, value, userBubble, assistantBubble,
                surface, background, amoled);
    }

    public OrbitTheme withUserBubble(String value) {
        return new OrbitTheme(id, name, builtIn, accent, value, assistantBubble,
                surface, background, amoled);
    }

    public OrbitTheme withAssistantBubble(String value) {
        return new OrbitTheme(id, name, builtIn, accent, userBubble, value,
                surface, background, amoled);
    }

    public OrbitTheme withSurface(String value) {
        return new OrbitTheme(id, name, builtIn, accent, userBubble, assistantBubble,
                value, background, amoled);
    }

    public OrbitTheme withBackground(String value) {
        return new OrbitTheme(id, name, builtIn, accent, userBubble, assistantBubble,
                surface, value, amoled);
    }

    public OrbitTheme withAmoled(boolean value) {
        return new OrbitTheme(id, name, builtIn, accent, userBubble, assistantBubble,
                surface, background, value);
    }

    public OrbitTheme withName(String value) {
        return new OrbitTheme(id, value, builtIn, accent, userBubble, assistantBubble,
                surface, background, amoled);
    }

    /**
     * The same colours as a new, editable, custom theme.
     *
     * <p>Used both by Duplicate and by "save this draft as a preset" after starting from a built-in
     * one: what comes out is always a theme the user owns, never a second copy of a shipped preset
     * pretending to be editable.
     */
    public OrbitTheme asCustomNamed(String newName) {
        return new OrbitTheme(newId(), newName, false, accent, userBubble, assistantBubble,
                surface, background, amoled);
    }

    /** True when the two of these describe the same appearance, ignoring identity and name. */
    public boolean sameColours(OrbitTheme other) {
        return other != null
                && accent.equals(other.accent)
                && userBubble.equals(other.userBubble)
                && assistantBubble.equals(other.assistantBubble)
                && surface.equals(other.surface)
                && background.equals(other.background)
                && amoled == other.amoled;
    }

    /**
     * Whether this theme leaves Orbit's own card ramp and page background alone.
     *
     * <p>{@code UiKit} takes a shortcut for exactly this case and uses its shipped constants
     * verbatim rather than re-deriving them, so that a default install after this release renders
     * identically to one before it.
     */
    public boolean usesClassicSurfaces() {
        return CLASSIC.equals(surface) && CLASSIC.equals(background);
    }

    // ---- serialisation -----------------------------------------------------------------------

    public JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject();
        out.put("format", FORMAT);
        out.put("schema", SCHEMA);
        out.put("id", id);
        out.put("name", name);
        out.put("builtIn", builtIn);
        out.put("accent", accent);
        out.put("userBubble", userBubble);
        out.put("assistantBubble", assistantBubble);
        out.put("surface", surface);
        out.put("background", background);
        out.put("amoled", amoled);
        return out;
    }

    /**
     * Reads a stored theme, or null when the object is missing or is not one of ours.
     *
     * <p>Anything that survives that check is then normalised field by field, so a file corrupted
     * in a way JSON cannot detect — a hex value truncated to four digits, a name of ten thousand
     * characters — degrades to a usable theme instead of a crash on the next launch.
     */
    public static OrbitTheme fromJson(JSONObject json) {
        if (json == null) return null;
        int schema = json.optInt("schema", 0);
        if (schema <= 0 || schema > SCHEMA) return null;
        String id = json.optString("id", "");
        if (id.trim().isEmpty()) return null;
        return new OrbitTheme(
                id,
                json.optString("name", ""),
                json.optBoolean("builtIn", false),
                json.optString("accent", DYNAMIC),
                json.optString("userBubble", CLASSIC),
                json.optString("assistantBubble", CLASSIC),
                json.optString("surface", CLASSIC),
                json.optString("background", CLASSIC),
                json.optBoolean("amoled", false));
    }

    // ---- token handling ----------------------------------------------------------------------

    /** {@code #RRGGBB} in upper case, or null when this is not a hex token. */
    public static String parseHexToken(String token) {
        if (token == null) return null;
        String value = token.trim();
        if (value.length() != 7 || value.charAt(0) != '#') return null;
        for (int i = 1; i < 7; i++) {
            char ch = value.charAt(i);
            boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F');
            if (!hex) return null;
        }
        return value.toUpperCase(Locale.US);
    }

    public static boolean isHexToken(String token) {
        return parseHexToken(token) != null;
    }

    /** The opaque colour a hex token names. Only call this when {@link #isHexToken} is true. */
    public static int hexTokenColor(String token) {
        String value = parseHexToken(token);
        if (value == null) return Color.BLACK;
        return Color.rgb(
                Integer.parseInt(value.substring(1, 3), 16),
                Integer.parseInt(value.substring(3, 5), 16),
                Integer.parseInt(value.substring(5, 7), 16));
    }

    /** An opaque colour written back as the token that stores it. Alpha is deliberately dropped. */
    public static String colorToken(int color) {
        return String.format(Locale.US, "#%02X%02X%02X",
                Color.red(color), Color.green(color), Color.blue(color));
    }

    /** How long a theme name may be. Long enough for a real name, short enough to render. */
    public static final int MAX_NAME_LENGTH = 40;

    /**
     * A display name that will actually fit on a preset card.
     *
     * <p>Blank becomes "Custom theme" rather than an empty card. Duplicates are allowed on purpose:
     * two themes may legitimately be called "Night", and identity has never come from the name.
     */
    public static String normalizeName(String value) {
        String trimmed = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) return "Custom theme";
        return trimmed.length() <= MAX_NAME_LENGTH ? trimmed
                : trimmed.substring(0, MAX_NAME_LENGTH).trim();
    }

    private static String normalizeId(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? newId() : trimmed;
    }

    private static String normalizeAccent(String value) {
        String hex = parseHexToken(value);
        if (hex != null) return hex;
        String trimmed = value == null ? "" : value.trim();
        for (String key : UiKit.accentKeys()) if (key.equals(trimmed)) return key;
        return DYNAMIC;
    }

    private static String normalizeBubble(String value) {
        String hex = parseHexToken(value);
        if (hex != null) return hex;
        String trimmed = value == null ? "" : value.trim();
        for (String key : UiKit.bubbleColorKeys()) if (key.equals(trimmed)) return key;
        return CLASSIC;
    }

    private static String normalizeSurface(String value) {
        String hex = parseHexToken(value);
        return hex != null ? hex : CLASSIC;
    }

    // ---- built-in presets --------------------------------------------------------------------

    /** Storage ids for the shipped presets. Stable: a saved active theme refers to one by id. */
    public static final String ID_DEFAULT = "orbit.default";
    public static final String ID_AMOLED = "orbit.amoled";
    public static final String ID_NEBULA = "orbit.nebula";
    public static final String ID_TIDE = "orbit.tide";
    public static final String ID_EMBER = "orbit.ember";
    public static final String ID_MOSS = "orbit.moss";
    public static final String ID_BLURPLE = "orbit.blurple";

    private static final List<OrbitTheme> BUILT_IN = buildBuiltIns();

    private static List<OrbitTheme> buildBuiltIns() {
        List<OrbitTheme> out = new ArrayList<>();
        // Orbit as it ships. Every token classic, so this preset is defined by Orbit's design
        // rather than by a snapshot of it, and stays correct if those defaults are ever retuned.
        out.add(new OrbitTheme(ID_DEFAULT, "Orbit Default", true,
                DYNAMIC, CLASSIC, CLASSIC, CLASSIC, CLASSIC, false));
        // The same thing on a true-black page. Surfaces stay classic on purpose: AMOLED is about
        // the large lit area, not about flattening every card into the background.
        out.add(new OrbitTheme(ID_AMOLED, "Orbit AMOLED", true,
                DYNAMIC, CLASSIC, CLASSIC, CLASSIC, CLASSIC, true));
        out.add(new OrbitTheme(ID_NEBULA, "Nebula", true,
                "#8B7CFF", "#3A2E63", "#1F1930", "#1A1626", "#0B0714", false));
        out.add(new OrbitTheme(ID_TIDE, "Tide", true,
                "#5097FF", "#1E3A5C", "#16202E", "#141C28", "#070B12", false));
        out.add(new OrbitTheme(ID_EMBER, "Ember", true,
                "#FF8A5B", "#4A2A1F", "#2A1D1A", "#241A18", "#120B0A", false));
        out.add(new OrbitTheme(ID_MOSS, "Moss", true,
                "#45CCA6", "#1E4038", "#182722", "#16211D", "#08110E", false));
        // Orbit's classic ramp with the blurple accent carried through the conversation.
        out.add(new OrbitTheme(ID_BLURPLE, "Blurple", true,
                "blurple", "#2B3060", CLASSIC, CLASSIC, CLASSIC, false));
        return Collections.unmodifiableList(out);
    }

    /** Every preset Orbit ships, in gallery order. */
    public static List<OrbitTheme> builtIns() {
        return BUILT_IN;
    }

    public static OrbitTheme builtIn(String id) {
        for (OrbitTheme theme : BUILT_IN) if (theme.id.equals(id)) return theme;
        return null;
    }

    public static boolean isBuiltInId(String id) {
        return builtIn(id) != null;
    }

    /** Orbit's own appearance. The fallback for anything that cannot be read. */
    public static OrbitTheme orbitDefault() {
        return BUILT_IN.get(0);
    }
}
