package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where Orbit's active theme and the user's saved themes actually live.
 *
 * <p>The most important decision in Theme Studio is one that is easy to miss reading it: the active
 * theme is <em>not</em> stored anywhere new. It is stored in the same four preference keys Orbit
 * has always used — accent, AMOLED, user bubble, assistant bubble — plus two more for the surface
 * and background the editor adds. Everything that reads appearance today keeps reading exactly what
 * it read before, and there is no second source of truth that can drift out of step with the first.
 *
 * <p>That is also why migration is nearly nothing. An existing install's appearance already <em>is</em>
 * a valid theme; upgrading only has to record that the canonical model has been established, which
 * is a single boolean write. There is no conversion pass to get wrong, nothing to lose, and running
 * it twice cannot do anything the first run did not. {@link #migrateLegacyAppearance} is written so
 * that this stays true even if the schema later grows fields that genuinely do need converting.
 *
 * <p>Saved presets are separate, because they are a collection rather than a setting: they live in
 * a versioned JSON file in the app's private storage. They are deliberately local-only — not in the
 * preference backup, not in diagnostics, not synced anywhere.
 */
public final class OrbitThemeStore {
    private OrbitThemeStore() {}

    /** The presets file. Versioned so a later Beta can add fields without guessing. */
    private static final String PRESETS_FILE = "orbit_themes.json";
    private static final int PRESETS_SCHEMA = 1;

    /** How many themes one person may save. High enough never to be met by hand. */
    public static final int MAX_CUSTOM_PRESETS = 60;

    // ---- active theme --------------------------------------------------------------------------

    /**
     * The theme Orbit is currently drawing itself with.
     *
     * <p>Assembled from the live preference values every time rather than cached, because those
     * same keys are still written directly by Settings, by onboarding, and by the appearance
     * backup, and a cache would be a way for this to disagree with them.
     */
    public static OrbitTheme active(Context c) {
        if (c == null) return OrbitTheme.orbitDefault();
        migrateLegacyAppearance(c);
        SharedPreferences p = Prefs.get(c);
        String id = p.getString(Prefs.THEME_ID, OrbitTheme.ID_DEFAULT);
        String name = p.getString(Prefs.THEME_NAME, "");
        if (name.trim().isEmpty()) {
            OrbitTheme builtIn = OrbitTheme.builtIn(id);
            name = builtIn != null ? builtIn.name : "Custom theme";
        }
        return new OrbitTheme(
                id,
                name,
                OrbitTheme.isBuiltInId(id),
                p.getString(Prefs.ACCENT, OrbitTheme.DYNAMIC),
                p.getString(Prefs.USER_BUBBLE_COLOR, OrbitTheme.CLASSIC),
                p.getString(Prefs.ASSISTANT_BUBBLE_COLOR, OrbitTheme.CLASSIC),
                p.getString(Prefs.THEME_SURFACE, OrbitTheme.CLASSIC),
                p.getString(Prefs.THEME_BACKGROUND, OrbitTheme.CLASSIC),
                p.getBoolean(Prefs.AMOLED_MODE, false));
    }

    /**
     * Makes {@code theme} the appearance Orbit draws with.
     *
     * <p>Committed synchronously: the caller's very next act is to rebuild its own view hierarchy
     * from {@code UiKit}'s constants, and reading a preference that has not landed yet would show
     * the previous theme for one frame.
     */
    public static void applyActive(Context c, OrbitTheme theme) {
        if (c == null || theme == null) return;
        Prefs.get(c).edit()
                .putString(Prefs.THEME_ID, theme.id)
                .putString(Prefs.THEME_NAME, theme.name)
                .putInt(Prefs.THEME_SCHEMA, OrbitTheme.SCHEMA)
                .putString(Prefs.ACCENT, theme.accent)
                .putString(Prefs.USER_BUBBLE_COLOR, theme.userBubble)
                .putString(Prefs.ASSISTANT_BUBBLE_COLOR, theme.assistantBubble)
                .putString(Prefs.THEME_SURFACE, theme.surface)
                .putString(Prefs.THEME_BACKGROUND, theme.background)
                .putBoolean(Prefs.AMOLED_MODE, theme.amoled)
                .commit();
        UiKit.syncTheme(c);
    }

    /**
     * Brings this install's stored appearance up to the current theme schema.
     *
     * <p>Stepwise and guarded per step, rather than one block that runs when the stamp is old. The
     * difference is not academic. The step that establishes the model has to decide what to call
     * the appearance it finds, and re-running that on an install which has since created a theme
     * of its own would rename "Midnight" back to "Your theme" and unlink it from the preset the
     * user saved. Each step therefore runs only for installs that have not had it, and an install
     * already at the current schema does nothing at all.
     *
     * <p>Every step is a rewrite of values already present into the same values in canonical form,
     * so running the whole thing twice, or after a restored preference backup has removed the
     * stamp, cannot produce a different appearance than running it once.
     */
    static void migrateLegacyAppearance(Context c) {
        SharedPreferences p = Prefs.get(c);
        int schema = p.getInt(Prefs.THEME_SCHEMA, 0);
        if (schema >= OrbitTheme.SCHEMA) return;
        if (schema < 1) establishThemeModel(p);
        if (schema < 2) canonicalisePaletteTokens(p);
        p.edit().putInt(Prefs.THEME_SCHEMA, OrbitTheme.SCHEMA).commit();
    }

    /**
     * Schema 1: name the appearance this install already had.
     *
     * <p>Deliberately additive. The accent, AMOLED state and bubble colours a user chose in an
     * earlier release are already the theme, so this must not touch them — the visual result after
     * an update is not merely close to what was there before, it is the same values. All it records
     * is which theme the existing appearance corresponds to, so that Theme Studio can show a preset
     * as selected rather than showing nothing.
     */
    private static void establishThemeModel(SharedPreferences p) {
        String accent = p.getString(Prefs.ACCENT, OrbitTheme.DYNAMIC);
        String userBubble = p.getString(Prefs.USER_BUBBLE_COLOR, OrbitTheme.CLASSIC);
        String assistantBubble = p.getString(Prefs.ASSISTANT_BUBBLE_COLOR, OrbitTheme.CLASSIC);
        boolean amoled = p.getBoolean(Prefs.AMOLED_MODE, false);

        // Surface and background did not exist before Theme Studio, so an upgrading install is by
        // definition using Orbit's own and these read as classic. They are still read rather than
        // assumed, because a restored preference backup can put a custom surface back in place
        // without the schema stamp that accompanied it, and assuming classic there would relabel
        // somebody's theme as Orbit Default while leaving its actual colours alone.
        OrbitTheme existing = new OrbitTheme(OrbitTheme.ID_DEFAULT, "", true,
                accent, userBubble, assistantBubble,
                p.getString(Prefs.THEME_SURFACE, OrbitTheme.CLASSIC),
                p.getString(Prefs.THEME_BACKGROUND, OrbitTheme.CLASSIC),
                amoled);

        // If what the user already had happens to be one of Orbit's presets, say so, so the
        // gallery opens with their theme selected instead of appearing to have lost it.
        OrbitTheme match = null;
        for (OrbitTheme candidate : OrbitTheme.builtIns()) {
            if (candidate.sameColours(existing)) { match = candidate; break; }
        }

        SharedPreferences.Editor e = p.edit()
                .putString(Prefs.THEME_ID, match != null ? match.id : Prefs.THEME_ID_CUSTOM)
                .putString(Prefs.THEME_NAME, match != null ? match.name : "Your theme");
        // Written explicitly rather than left absent so that the six tokens of a theme are always
        // all present together, and a partial read can never mix a stored value with a default.
        if (!p.contains(Prefs.THEME_SURFACE)) e.putString(Prefs.THEME_SURFACE, OrbitTheme.CLASSIC);
        if (!p.contains(Prefs.THEME_BACKGROUND)) {
            e.putString(Prefs.THEME_BACKGROUND, OrbitTheme.CLASSIC);
        }
        e.commit();
    }

    /**
     * Schema 2: give a stored hex value its palette name back.
     *
     * <p>Beta 1 shipped presets whose accents were written as raw hex, and three of them were
     * Orbit's own Violet, Blue and Mint to the byte. Nothing knew that, so the editor described
     * Orbit's own colour as custom and the preset that used it never showed as selected. The colour
     * does not move here — only the way it is written — and only an exact match is rewritten, so a
     * value one channel away from Violet stays the value somebody picked.
     *
     * <p>Dynamic is untouched by construction: it is not a colour and has no hex form, so nothing
     * can be canonicalised into it.
     */
    private static void canonicalisePaletteTokens(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit();
        boolean any = false;
        for (String key : new String[]{
                Prefs.ACCENT, Prefs.USER_BUBBLE_COLOR, Prefs.ASSISTANT_BUBBLE_COLOR}) {
            String stored = p.getString(key, null);
            String named = OrbitPalette.keyForHexToken(stored);
            if (named == null || named.equals(stored)) continue;
            e.putString(key, named);
            any = true;
        }
        if (any) e.commit();
    }

    // ---- saved presets -------------------------------------------------------------------------

    /** Orbit's presets first, then the user's own in the order they were saved. */
    public static List<OrbitTheme> allPresets(Context c) {
        List<OrbitTheme> out = new ArrayList<>(OrbitTheme.builtIns());
        out.addAll(customPresets(c));
        return out;
    }

    /** Only the themes this person saved. Empty when the file is missing or unreadable. */
    public static List<OrbitTheme> customPresets(Context c) {
        List<OrbitTheme> out = new ArrayList<>();
        if (c == null) return out;
        JSONObject root = readFile(c);
        if (root == null) return out;
        JSONArray themes = root.optJSONArray("themes");
        if (themes == null) return out;
        for (int i = 0; i < themes.length(); i++) {
            OrbitTheme theme = OrbitTheme.fromJson(themes.optJSONObject(i));
            // A built-in id in the custom file would shadow a shipped preset. Drop it rather than
            // let a corrupted or hand-edited file make an immutable theme look deletable.
            if (theme == null || OrbitTheme.isBuiltInId(theme.id)) continue;
            out.add(theme.builtIn ? theme.asCustomNamed(theme.name) : theme);
        }
        return out;
    }

    public static OrbitTheme preset(Context c, String id) {
        if (id == null) return null;
        OrbitTheme builtIn = OrbitTheme.builtIn(id);
        if (builtIn != null) return builtIn;
        for (OrbitTheme theme : customPresets(c)) if (theme.id.equals(id)) return theme;
        return null;
    }

    /**
     * Saves {@code theme} as one of the user's own presets and returns what was stored.
     *
     * <p>A theme whose id is already in the file is updated in place; anything else is appended as
     * a new one. A built-in is never updated: saving from a shipped preset produces a copy the user
     * owns, which is what {@link OrbitTheme#asCustomNamed} is for.
     *
     * <p>Returns null when the store is full or the write failed, so a caller can say so rather
     * than report a save that did not happen.
     */
    public static OrbitTheme savePreset(Context c, OrbitTheme theme) {
        if (c == null || theme == null) return null;
        OrbitTheme stored = theme.builtIn || OrbitTheme.isBuiltInId(theme.id)
                ? theme.asCustomNamed(theme.name) : theme;
        List<OrbitTheme> presets = customPresets(c);
        int existing = indexOf(presets, stored.id);
        if (existing >= 0) {
            presets.set(existing, stored);
        } else {
            if (presets.size() >= MAX_CUSTOM_PRESETS) return null;
            presets.add(stored);
        }
        return write(c, presets) ? stored : null;
    }

    /** Renames one saved preset. Built-ins are refused; a blank name becomes Orbit's fallback. */
    public static boolean renamePreset(Context c, String id, String newName) {
        if (c == null || OrbitTheme.isBuiltInId(id)) return false;
        List<OrbitTheme> presets = customPresets(c);
        int index = indexOf(presets, id);
        if (index < 0) return false;
        presets.set(index, presets.get(index).withName(newName));
        return write(c, presets);
    }

    /** Copies one preset — built-in or custom — into a new theme the user owns. */
    public static OrbitTheme duplicatePreset(Context c, String id) {
        OrbitTheme source = preset(c, id);
        if (source == null) return null;
        return savePreset(c, source.asCustomNamed(copyName(c, source.name)));
    }

    /**
     * Deletes one saved preset. Built-ins are refused, always, whatever the caller asks for.
     *
     * <p>Deleting the theme that is currently applied does not change what Orbit looks like. The
     * active theme is stored as its own set of values, not as a pointer into this file, so removing
     * the preset removes the entry in the gallery and nothing else.
     */
    public static boolean deletePreset(Context c, String id) {
        if (c == null || OrbitTheme.isBuiltInId(id)) return false;
        List<OrbitTheme> presets = customPresets(c);
        int index = indexOf(presets, id);
        if (index < 0) return false;
        presets.remove(index);
        return write(c, presets);
    }

    /** How many themes the user has saved. The one theme fact Diagnostics is allowed to report. */
    public static int customPresetCount(Context c) {
        return customPresets(c).size();
    }

    /** "Nebula" already exists, so the copy is "Nebula 2", then "Nebula 3". */
    private static String copyName(Context c, String base) {
        List<OrbitTheme> presets = allPresets(c);
        for (int n = 2; n < 100; n++) {
            String candidate = OrbitTheme.normalizeName(base + " " + n);
            boolean taken = false;
            for (OrbitTheme theme : presets) {
                if (theme.name.equalsIgnoreCase(candidate)) { taken = true; break; }
            }
            if (!taken) return candidate;
        }
        return OrbitTheme.normalizeName(base);
    }

    private static int indexOf(List<OrbitTheme> presets, String id) {
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    // ---- file --------------------------------------------------------------------------------

    private static File file(Context c) {
        return new File(c.getFilesDir(), PRESETS_FILE);
    }

    private static JSONObject readFile(Context c) {
        File file = file(c);
        if (!file.exists()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[(int) Math.min(raf.length(), 1_500_000L)];
            raf.readFully(bytes);
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            // A file from a schema Orbit does not understand is left alone rather than parsed
            // optimistically. The user sees no saved themes, and nothing is destroyed.
            int schema = root.optInt("schema", 0);
            if (schema <= 0 || schema > PRESETS_SCHEMA) return null;
            return root;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean write(Context c, List<OrbitTheme> presets) {
        try {
            JSONArray array = new JSONArray();
            for (OrbitTheme theme : presets) array.put(theme.toJson());
            JSONObject root = new JSONObject();
            root.put("format", OrbitTheme.FORMAT + ".library");
            root.put("schema", PRESETS_SCHEMA);
            root.put("themes", array);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(file(c))) {
                out.write(bytes);
                out.getFD().sync();
            }
            return true;
        } catch (IOException | RuntimeException | Error e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Removes every saved preset. Tests only; nothing in the app deletes the whole library. */
    static void clearForTests(Context c) {
        List<OrbitTheme> none = Collections.emptyList();
        write(c, none);
    }
}
