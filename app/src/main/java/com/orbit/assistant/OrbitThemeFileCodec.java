package com.orbit.assistant;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The one place an Orbit theme crosses the boundary between a file and the app.
 *
 * <p>Theme Studio has always been able to serialise a theme — {@link OrbitTheme#toJson} predates
 * this class, and the saved-preset library has used it since Beta 1. What it could not do was read
 * one back from somewhere Orbit does not control. That is a genuinely different problem, and it is
 * why this is a class rather than a method on the activity: a file chosen from Android's picker is
 * untrusted input, and the checks that make it safe belong together, in front of the model, where
 * they can be read and tested as a unit instead of scattered through a screen.
 *
 * <p>What comes out of {@link #decode} is a plain {@link OrbitTheme} the user owns. Nothing else can
 * come out of it. A theme file is six appearance decisions and a name; it carries no intents, no
 * URLs, no permissions, no provider configuration and no identity Orbit will honour. Built-in
 * status in particular is an Orbit decision, never a claim a file may make, so {@code builtIn} and
 * {@code id} are read past rather than trusted — see {@link #decode} for why that matters.
 *
 * <p>Everything rejected is rejected with one of two short sentences. Parser exceptions never reach
 * the user: knowing that a file failed at character 41 helps nobody choose a different file.
 */
public final class OrbitThemeFileCodec {

    private OrbitThemeFileCodec() {}

    /**
     * What Android is told an exported theme is.
     *
     * <p>A theme file is JSON and says so. Orbit does not invent a private MIME type for it,
     * because a type no other app knows makes the file harder to move around and buys nothing:
     * {@link #decode} trusts the document's own {@code format} field, never the type or the name a
     * provider reports.
     */
    public static final String MIME_TYPE = "application/json";

    /** The types the import picker will offer, in the order providers tend to report them. */
    public static final String[] IMPORT_MIME_TYPES = {
            "application/json", "text/plain", "application/octet-stream"};

    /** The tail of an exported filename. Recognisable to a person; not evidence to the parser. */
    public static final String FILE_SUFFIX = ".orbit-theme.json";

    /**
     * The largest external file Orbit will look inside.
     *
     * <p>A real theme is around three hundred bytes. Sixty-four kilobytes is far past anything
     * Theme Studio can produce even with the longest name it allows, and still small enough that a
     * mistaken pick — a photo, a database, an export from something else entirely — is refused
     * before it is read into memory rather than after.
     */
    public static final int MAX_FILE_BYTES = 64 * 1024;

    /** The oldest theme document this build can still read. Beta 1 wrote schema 1. */
    public static final int MIN_SUPPORTED_SCHEMA = 1;

    private static final String NOT_A_THEME = "This isn't a supported Orbit theme.";
    private static final String FROM_THE_FUTURE = "This theme was made for a newer version of Orbit.";
    private static final String TOO_LARGE = "This file is too large to be an Orbit theme.";

    /**
     * A file Orbit will not open, and the sentence to show for it.
     *
     * <p>The message is the whole payload. There is no cause chained onto it and no stack to
     * unwrap, so there is no route by which a parser's own words reach a dialog.
     */
    public static final class ThemeFileException extends Exception {
        /** True when the file is a real Orbit theme from a schema this build predates. */
        public final boolean newerVersion;

        ThemeFileException(String message, boolean newerVersion) {
            super(message);
            this.newerVersion = newerVersion;
        }
    }

    private static ThemeFileException unsupported() {
        return new ThemeFileException(NOT_A_THEME, false);
    }

    private static ThemeFileException newerVersion() {
        return new ThemeFileException(FROM_THE_FUTURE, true);
    }

    private static ThemeFileException tooLarge() {
        return new ThemeFileException(TOO_LARGE, false);
    }

    // ---- export ----------------------------------------------------------------------------------

    /**
     * One theme as the bytes of a portable theme file.
     *
     * <p>Deliberately {@link OrbitTheme#toJson} and nothing else. A second serialiser written for
     * export would be a second thing to keep in step with the model, and the first time the two
     * disagreed the app would be exporting files it could not itself read. Indented because a theme
     * file is small and somebody will open one in a text editor.
     *
     * <p>Returns "" only if the document could not be built at all, which for a theme — six
     * non-null strings, two booleans and no numbers — cannot happen. The caller still checks, so
     * that the impossible case is a refusal rather than an empty file on disk.
     */
    public static String encode(OrbitTheme theme) {
        if (theme == null) return "";
        try {
            return theme.toJson().toString(2);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * A filename a person will recognise in their Downloads folder.
     *
     * <p>Built from the theme's name so the file says what it is, and reduced to characters every
     * document provider on Android handles the same way: a name with a slash, a colon or an emoji
     * in it is a name some picker will quietly mangle or refuse. The suffix is fixed, so two
     * exports of differently named themes never collide.
     */
    public static String fileNameFor(OrbitTheme theme) {
        return fileNameFor(theme == null ? null : theme.name);
    }

    public static String fileNameFor(String themeName) {
        String name = OrbitTheme.normalizeName(themeName);
        StringBuilder out = new StringBuilder(name.length());
        boolean lastWasSeparator = true;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean plain = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9');
            if (plain) {
                out.append(ch);
                lastWasSeparator = false;
            } else if (!lastWasSeparator) {
                out.append('-');
                lastWasSeparator = true;
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.deleteCharAt(out.length() - 1);
        }
        String stem = out.toString();
        if (stem.isEmpty()) stem = "Orbit-theme";
        return stem + FILE_SUFFIX;
    }

    // ---- import ----------------------------------------------------------------------------------

    /**
     * Reads at most {@link #MAX_FILE_BYTES} from a chosen document, refusing anything longer.
     *
     * <p>The bound is applied while reading rather than after. A picker can hand back a stream of
     * any length, including one that never ends, and a check on the finished array is a check that
     * runs too late to be a limit at all.
     */
    public static byte[] readBounded(InputStream input) throws ThemeFileException {
        if (input == null) throw unsupported();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FILE_BYTES) throw tooLarge();
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (ThemeFileException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw unsupported();
        }
    }

    /** The theme in a chosen document, or a refusal. Closes nothing; the caller owns the stream. */
    public static OrbitTheme read(InputStream input) throws ThemeFileException {
        return decode(readBounded(input));
    }

    public static OrbitTheme decode(byte[] bytes) throws ThemeFileException {
        if (bytes == null || bytes.length == 0) throw unsupported();
        if (bytes.length > MAX_FILE_BYTES) throw tooLarge();
        return decode(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * The theme a document describes, as a custom theme this user owns.
     *
     * <p>Five things are checked before the model is allowed to see anything, in this order,
     * because each one makes the next meaningful: the text is bounded, it is JSON, it is an object
     * whose {@code format} is exactly Orbit's, its schema is one this build understands, and it
     * carries the whole set of appearance fields rather than a subset that would silently pick up
     * defaults. Only then are the values read, and they are read through
     * {@link OrbitTheme#custom}, which normalises every one of them — so a truncated hex value or a
     * token invented by hand becomes Orbit's default for that slot instead of a crash.
     *
     * <p>{@code id} and {@code builtIn} are present in every file Orbit writes and are ignored in
     * every file Orbit reads. That is the whole of the identity rule: a file describes an
     * appearance, and what it is called in this install, whether it is one of Orbit's own, and
     * which saved theme it is, are decided here rather than there. It also means importing a file
     * twice produces two themes rather than one theme overwritten, and that no file can be crafted
     * to land on top of Nova AMOLED.
     */
    public static OrbitTheme decode(String raw) throws ThemeFileException {
        if (raw == null) throw unsupported();
        // A UTF-8 BOM is legal in a file and is not legal at the start of a JSON document. Editors
        // add one without being asked, so a theme somebody opened and saved again is still a theme.
        String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        text = text.trim();
        if (text.isEmpty()) throw unsupported();
        if (text.length() > MAX_FILE_BYTES) throw tooLarge();

        JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (Exception e) {
            throw unsupported();
        }

        // Exactly Orbit's format identifier, compared against the parsed value rather than a
        // coerced string, so neither a missing key nor the preset library's own
        // "orbit.theme.library" can pass for a single theme.
        if (!OrbitTheme.FORMAT.equals(json.opt("format"))) throw unsupported();

        int schema = json.optInt("schema", 0);
        if (schema < MIN_SUPPORTED_SCHEMA) throw unsupported();
        if (schema > OrbitTheme.SCHEMA) throw newerVersion();

        for (String field : REQUIRED_FIELDS) {
            if (!json.has(field) || json.isNull(field)) throw unsupported();
        }

        return OrbitTheme.custom(
                json.optString("name", ""),
                json.optString("accent", OrbitTheme.DYNAMIC),
                json.optString("userBubble", OrbitTheme.CLASSIC),
                json.optString("assistantBubble", OrbitTheme.CLASSIC),
                json.optString("surface", OrbitTheme.CLASSIC),
                json.optString("background", OrbitTheme.CLASSIC),
                json.optBoolean("amoled", false));
    }

    /**
     * The fields a theme document must actually contain.
     *
     * <p>Presence is structural and is required; the values themselves are not, because the model
     * normalises those and refusing a file over one odd token would reject a theme a person can
     * plainly see is a theme. What this stops is the other case: a document that announces itself
     * as an Orbit theme and then describes nothing, which would import as a silent copy of Orbit
     * Default under somebody else's name.
     */
    private static final String[] REQUIRED_FIELDS = {
            "name", "accent", "userBubble", "assistantBubble", "surface", "background", "amoled"};

    /** A one-line description of an imported theme, for the confirmation that precedes adding it. */
    public static String describe(OrbitTheme theme) {
        if (theme == null) return "";
        return String.format(Locale.US, "Accent %s · Background %s",
                OrbitPalette.labelFor(theme.accent),
                theme.amoled ? "AMOLED true black" : surfaceLabel(theme.background));
    }

    private static String surfaceLabel(String token) {
        return OrbitTheme.isHexToken(token) ? "custom " + token : "Orbit default";
    }
}
