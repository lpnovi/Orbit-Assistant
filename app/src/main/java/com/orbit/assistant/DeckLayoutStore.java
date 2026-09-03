package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The user's Deck, stored locally and versioned from the first release that had one.
 *
 * <p>Two rules shape this file. The first is that My Deck belongs to the user: nothing here ever
 * reorders tiles on its own, and nothing ever quietly restores the defaults over a layout somebody
 * deliberately emptied. That is why "never configured" and "configured to be empty" are different
 * states rather than both being "no tiles" — the presence of the stored key is the difference.
 *
 * <p>The second is that a layout must survive Orbit changing around it. Every read is defensive:
 * malformed JSON yields the defaults rather than an exception, a tile whose type this build has
 * never heard of is kept rather than dropped, a size a definition no longer supports is coerced
 * back into range, and duplicate instance ids are repaired deterministically. A future Orbit adding
 * a field cannot be broken by this one, because unknown configuration keys are read and written
 * back untouched.
 */
public final class DeckLayoutStore {

    private static final String FILE = "orbit_deck";
    private static final String KEY_LAYOUT = "deck_layout";

    /** Bumped only when the stored shape changes in a way a reader must know about. */
    public static final int SCHEMA_VERSION = 1;

    /** A ceiling, not a target. Deep enough that nobody meets it, shallow enough to stay a Deck. */
    public static final int MAX_TILES = 32;

    private DeckLayoutStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- defaults ---------------------------------------------------------------------------------

    /**
     * The Deck somebody sees the first time they open it.
     *
     * <p>Seven tiles, chosen because each one is a place a person actually goes, not because seven
     * fills the grid neatly — though it does: one wide tile above three full rows. New chat leads
     * because it is what Orbit is for.
     */
    public static List<DeckTile> defaults() {
        List<DeckTile> out = new ArrayList<>();
        out.add(DeckTile.fixed(DeckTileRegistry.TYPE_NEW_CHAT, DeckTile.Size.WIDE));
        out.add(DeckTile.fixed(DeckTileRegistry.TYPE_ROUTINES, DeckTile.Size.STANDARD));
        out.add(DeckTile.fixed(DeckTileRegistry.TYPE_REMINDERS, DeckTile.Size.STANDARD));
        out.add(DeckTile.fixed(DeckTileRegistry.TYPE_MEMORIES, DeckTile.Size.STANDARD));
        out.add(DeckTile.fixed(DeckTileRegistry.TYPE_FLASHLIGHT, DeckTile.Size.STANDARD));
        out.add(DeckTile.fixed(DeckTileRegistry.TYPE_MEDIA, DeckTile.Size.STANDARD));
        out.add(DeckTile.fixed(DeckTileRegistry.TYPE_CAPABILITIES, DeckTile.Size.STANDARD));
        return out;
    }

    /** Whether the user has ever changed their Deck. False means they are still on the defaults. */
    public static synchronized boolean configured(Context c) {
        return c != null && prefs(c).contains(KEY_LAYOUT);
    }

    // ---- reading ----------------------------------------------------------------------------------

    /** The tiles to draw, in the user's own order. */
    public static synchronized List<DeckTile> layout(Context c) {
        if (c == null) return defaults();
        String raw = prefs(c).getString(KEY_LAYOUT, "");
        if (raw == null || raw.trim().isEmpty()) return defaults();
        List<DeckTile> parsed = parse(raw);
        return parsed == null ? defaults() : parsed;
    }

    /**
     * Reads a stored layout, or null when it is not readable at all.
     *
     * <p>Separated from {@link #layout} and left package-visible so the difference between "this
     * is an empty Deck" and "this JSON is rubbish" can be asserted directly.
     */
    static List<DeckTile> parse(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray tiles = root.optJSONArray("tiles");
            if (tiles == null) return null;
            List<DeckTile> out = new ArrayList<>();
            Set<String> seenInstanceIds = new HashSet<>();
            for (int i = 0; i < tiles.length() && out.size() < MAX_TILES; i++) {
                DeckTile tile = tileFromJson(tiles.optJSONObject(i));
                if (tile == null) continue;
                // A duplicated instance id would make two tiles indistinguishable to every
                // operation that addresses one by id. The first keeps the id; later ones are
                // re-issued rather than dropped, so no configured tile is ever lost to a repair.
                if (!seenInstanceIds.add(tile.instanceId)) tile = tile.withNewInstanceId();
                out.add(tile);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static DeckTile tileFromJson(JSONObject object) {
        if (object == null) return null;
        String type = object.optString("type", "").trim();
        if (type.isEmpty()) return null;
        String instanceId = object.optString("id", "").trim();
        DeckTile.Size stored = DeckTile.Size.fromKey(object.optString("size", "standard"));
        // An unknown type keeps whatever size it was written with: this build cannot know which
        // sizes it supports, and guessing would corrupt a layout a newer Orbit will read back.
        DeckTile.Size size = DeckTileRegistry.knows(type)
                ? DeckTileRegistry.coerceSize(type, stored) : stored;

        Map<String, String> config = new LinkedHashMap<>();
        JSONObject rawConfig = object.optJSONObject("config");
        if (rawConfig != null) {
            for (Iterator<String> it = rawConfig.keys(); it.hasNext(); ) {
                String key = it.next();
                String value = rawConfig.optString(key, "");
                if (!value.isEmpty()) config.put(key, value);
            }
        }
        return new DeckTile(instanceId, type, size, config);
    }

    // ---- writing ----------------------------------------------------------------------------------

    /**
     * Replaces the whole layout.
     *
     * <p>Every mutation funnels through here and commits synchronously, which is what lets Deck
     * persist edits as they happen instead of holding a dirty editor. One tap, one atomic write:
     * leaving the screen at any moment can never lose a change, so Back stays ordinary navigation.
     */
    public static synchronized boolean save(Context c, List<DeckTile> tiles) {
        if (c == null || tiles == null) return false;
        List<DeckTile> bounded = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (DeckTile tile : tiles) {
            if (tile == null || bounded.size() >= MAX_TILES) continue;
            bounded.add(ids.add(tile.instanceId) ? tile : tile.withNewInstanceId());
        }
        try {
            JSONArray array = new JSONArray();
            for (DeckTile tile : bounded) array.put(tileToJson(tile));
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            root.put("tiles", array);
            return prefs(c).edit().putString(KEY_LAYOUT, root.toString()).commit();
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject tileToJson(DeckTile tile) throws Exception {
        JSONObject object = new JSONObject();
        object.put("id", tile.instanceId);
        object.put("type", tile.type);
        object.put("size", tile.size.key());
        JSONObject config = new JSONObject();
        // Written back whole, including keys this build does not recognise.
        for (Map.Entry<String, String> entry : tile.configMap().entrySet()) {
            config.put(entry.getKey(), entry.getValue());
        }
        object.put("config", config);
        return object;
    }

    // ---- mutations --------------------------------------------------------------------------------

    /** Appends a tile. Returns false when the Deck is full. */
    public static synchronized boolean add(Context c, DeckTile tile) {
        if (c == null || tile == null) return false;
        List<DeckTile> tiles = new ArrayList<>(layout(c));
        if (tiles.size() >= MAX_TILES) return false;
        tiles.add(tile);
        return save(c, tiles);
    }

    public static synchronized boolean remove(Context c, String instanceId) {
        if (c == null || instanceId == null) return false;
        List<DeckTile> tiles = new ArrayList<>(layout(c));
        boolean removed = false;
        for (Iterator<DeckTile> it = tiles.iterator(); it.hasNext(); ) {
            if (instanceId.equals(it.next().instanceId)) { it.remove(); removed = true; break; }
        }
        return removed && save(c, tiles);
    }

    /** Resizes one tile, refusing a size its definition does not offer. */
    public static synchronized boolean resize(Context c, String instanceId, DeckTile.Size size) {
        if (c == null || instanceId == null || size == null) return false;
        List<DeckTile> tiles = new ArrayList<>(layout(c));
        for (int i = 0; i < tiles.size(); i++) {
            DeckTile tile = tiles.get(i);
            if (!instanceId.equals(tile.instanceId)) continue;
            DeckTileRegistry.Definition definition = DeckTileRegistry.definition(tile.type);
            if (definition == null || !definition.supports(size)) return false;
            if (tile.size == size) return true;
            tiles.set(i, tile.withSize(size));
            return save(c, tiles);
        }
        return false;
    }

    /** Writes one configuration value on one tile. */
    public static synchronized boolean configure(Context c, String instanceId,
                                                 String key, String value) {
        if (c == null || instanceId == null) return false;
        List<DeckTile> tiles = new ArrayList<>(layout(c));
        for (int i = 0; i < tiles.size(); i++) {
            DeckTile tile = tiles.get(i);
            if (!instanceId.equals(tile.instanceId)) continue;
            tiles.set(i, tile.withConfig(key, value));
            return save(c, tiles);
        }
        return false;
    }

    /**
     * Rewrites the order from a full list of instance ids.
     *
     * <p>Modelled on {@code RoutineStore.applyOrder}: unknown ids are ignored and any tile the
     * caller left out keeps its relative position at the end, so a stale list from a drag that
     * raced a removal can never delete a tile.
     */
    public static synchronized boolean applyOrder(Context c, List<String> orderedIds) {
        if (c == null || orderedIds == null) return false;
        List<DeckTile> tiles = new ArrayList<>(layout(c));
        if (tiles.isEmpty()) return false;
        Map<String, DeckTile> byId = new LinkedHashMap<>();
        for (DeckTile tile : tiles) byId.put(tile.instanceId, tile);

        List<DeckTile> out = new ArrayList<>(tiles.size());
        Set<String> placed = new HashSet<>();
        for (String id : orderedIds) {
            DeckTile tile = byId.get(id);
            if (tile != null && placed.add(id)) out.add(tile);
        }
        for (DeckTile tile : tiles) if (!placed.contains(tile.instanceId)) out.add(tile);
        return save(c, out);
    }

    /** Puts the default Deck back. Touches nothing outside this store. */
    public static synchronized boolean reset(Context c) {
        return save(c, defaults());
    }

    // ---- duplicates -------------------------------------------------------------------------------

    /**
     * Whether adding {@code candidate} would repeat something already on the Deck.
     *
     * <p>Singleton kinds may appear once, so a second one is always a duplicate. Configured kinds
     * may repeat freely, so only an identical configuration counts — two Routine tiles for the same
     * Routine, not two Routine tiles.
     */
    public static synchronized boolean wouldDuplicate(Context c, DeckTile candidate) {
        if (c == null || candidate == null) return false;
        return wouldDuplicate(layout(c), candidate);
    }

    static boolean wouldDuplicate(List<DeckTile> existing, DeckTile candidate) {
        if (existing == null || candidate == null) return false;
        DeckTileRegistry.Definition definition = DeckTileRegistry.definition(candidate.type);
        for (DeckTile tile : existing) {
            if (definition != null && definition.singleton) {
                if (candidate.type.equals(tile.type)) return true;
            } else if (candidate.duplicateKey().equals(tile.duplicateKey())) {
                return true;
            }
        }
        return false;
    }

    /** Whether one singleton kind is already placed, used to grey it out in the Add sheet. */
    public static synchronized boolean contains(Context c, String type) {
        if (c == null || type == null) return false;
        for (DeckTile tile : layout(c)) if (type.equals(tile.type)) return true;
        return false;
    }

    // ---- diagnostics ------------------------------------------------------------------------------

    /**
     * How many tiles of each type, for Diagnostics.
     *
     * <p>Counts only. What a Prompt tile says, which Routine a Routine tile runs and which app an
     * App tile launches deliberately never leave this store.
     */
    public static synchronized Map<String, Integer> typeCounts(Context c) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DeckTile tile : layout(c)) {
            Integer previous = counts.get(tile.type);
            counts.put(tile.type, previous == null ? 1 : previous + 1);
        }
        return counts;
    }

    /** Wipes Deck's stored layout entirely, returning the user to the unconfigured defaults. */
    public static synchronized void clearForTest(Context c) {
        if (c != null) prefs(c).edit().remove(KEY_LAYOUT).commit();
    }

    /** Writes a layout string verbatim, so malformed input can be exercised. */
    static void writeRawForTest(Context c, String raw) {
        if (c != null) prefs(c).edit().putString(KEY_LAYOUT, raw).commit();
    }

    static List<DeckTile> tiles(DeckTile... tiles) {
        return Collections.unmodifiableList(Arrays.asList(tiles));
    }
}
