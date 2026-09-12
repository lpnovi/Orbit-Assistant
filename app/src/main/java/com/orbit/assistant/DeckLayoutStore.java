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

/** Versioned, immediate-persistence storage for one complete Deck layout. */
public final class DeckLayoutStore {
    private static final String FILE = "orbit_deck";
    private static final String KEY_LAYOUT = "deck_layout";

    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_TILES = 32;
    public static final int MAX_SECTIONS = 12;
    public static final int MAX_FOLDERS = 12;
    public static final int MAX_ITEMS = MAX_TILES + MAX_SECTIONS + MAX_FOLDERS;

    private DeckLayoutStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

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

    private static DeckLayout defaultLayout() {
        List<DeckItem> items = new ArrayList<>();
        for (DeckTile tile : defaults()) items.add(new DeckTileItem(tile));
        return new DeckLayout(DeckLayout.PRIMARY_ID, items);
    }

    public static synchronized boolean configured(Context c) {
        return c != null && prefs(c).contains(KEY_LAYOUT);
    }

    /** Reads and, for schema 1 only, atomically migrates the user's layout in place. */
    public static synchronized DeckLayout deck(Context c) {
        if (c == null) return defaultLayout();
        String raw = prefs(c).getString(KEY_LAYOUT, "");
        if (raw == null || raw.trim().isEmpty()) return defaultLayout();
        Parsed parsed = parseDeck(raw);
        if (parsed == null) return defaultLayout();
        if (parsed.schema == 1) saveLayout(c, parsed.layout);
        return parsed.layout;
    }

    /** Legacy root-tile view retained for callers that do not need structural items. */
    public static synchronized List<DeckTile> layout(Context c) { return deck(c).rootTiles(); }
    public static synchronized List<DeckTile> allTiles(Context c) { return deck(c).allTiles(); }

    private static final class Parsed {
        final int schema;
        final DeckLayout layout;
        Parsed(int schema, DeckLayout layout) { this.schema = schema; this.layout = layout; }
    }

    private static Parsed parseDeck(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            int version = root.optInt("version", 1);
            if (version == 1 || root.optJSONArray("tiles") != null) {
                JSONArray tiles = root.optJSONArray("tiles");
                if (tiles == null) return null;
                List<DeckItem> items = new ArrayList<>();
                Set<String> ids = new HashSet<>();
                for (int i = 0; i < tiles.length() && items.size() < MAX_TILES; i++) {
                    DeckTile tile = tileFromJson(tiles.optJSONObject(i));
                    if (tile == null) continue;
                    items.add(new DeckTileItem(repairTileId(tile, ids, i)));
                }
                return new Parsed(1, new DeckLayout(DeckLayout.PRIMARY_ID, items));
            }
            if (version != SCHEMA_VERSION) return null;
            JSONObject layout = root.optJSONObject("layout");
            if (layout == null) return null;
            JSONArray rawItems = layout.optJSONArray("items");
            if (rawItems == null) return null;
            List<DeckItem> items = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            int tilesSeen = 0;
            for (int i = 0; i < rawItems.length() && items.size() < MAX_ITEMS; i++) {
                JSONObject item = rawItems.optJSONObject(i);
                if (item == null) continue;
                String kind = item.optString("kind", "tile");
                if ("tile".equals(kind) && tilesSeen < MAX_TILES) {
                    DeckTile tile = tileFromJson(item.optJSONObject("tile"));
                    if (tile == null) continue;
                    items.add(new DeckTileItem(repairTileId(tile, ids, i)));
                    tilesSeen++;
                } else if ("section".equals(kind) && count(items, DeckItem.Kind.SECTION) < MAX_SECTIONS) {
                    String id = repairId(item.optString("id", ""), ids, "section", i);
                    items.add(new DeckSection(id, item.optString("title", "New section")));
                } else if ("folder".equals(kind) && count(items, DeckItem.Kind.FOLDER) < MAX_FOLDERS) {
                    String id = repairId(item.optString("id", ""), ids, "folder", i);
                    List<DeckTile> children = new ArrayList<>();
                    JSONArray rawChildren = item.optJSONArray("tiles");
                    if (rawChildren != null) {
                        for (int j = 0; j < rawChildren.length() && j < DeckFolder.MAX_CHILDREN
                                && tilesSeen < MAX_TILES; j++) {
                            DeckTile tile = tileFromJson(rawChildren.optJSONObject(j));
                            if (tile == null) continue;
                            children.add(repairTileId(tile, ids, i * 100 + j));
                            tilesSeen++;
                        }
                    }
                    items.add(new DeckFolder(id, item.optString("title", "New folder"), children));
                }
            }
            return new Parsed(SCHEMA_VERSION, new DeckLayout(
                    layout.optString("id", DeckLayout.PRIMARY_ID), items));
        } catch (Exception e) {
            return null;
        }
    }

    private static DeckTile repairTileId(DeckTile tile, Set<String> ids, int index) {
        if (ids.add(tile.instanceId)) return tile;
        String id = repairId(tile.instanceId, ids, "tile", index);
        return new DeckTile(id, tile.type, tile.size, tile.configMap(), tile.appearance);
    }

    private static String repairId(String raw, Set<String> ids, String kind, int index) {
        String base = raw == null || raw.trim().isEmpty() ? "repaired:" + kind : raw.trim();
        String candidate = base;
        int suffix = index;
        while (!ids.add(candidate)) candidate = base + ":" + suffix++;
        return candidate;
    }

    private static int count(List<DeckItem> items, DeckItem.Kind kind) {
        int count = 0;
        for (DeckItem item : items) if (item.kind == kind) count++;
        return count;
    }

    private static DeckTile tileFromJson(JSONObject object) {
        if (object == null) return null;
        String type = object.optString("type", "").trim();
        if (type.isEmpty()) return null;
        String instanceId = object.optString("id", "").trim();
        DeckTile.Size stored = DeckTile.Size.fromKey(object.optString("size", "standard"));
        DeckTile.Size size = DeckTileRegistry.knows(type)
                ? DeckTileRegistry.coerceSize(type, stored) : stored;
        Map<String, String> config = new LinkedHashMap<>();
        JSONObject rawConfig = object.optJSONObject("config");
        if (rawConfig != null) {
            for (Iterator<String> it = rawConfig.keys(); it.hasNext();) {
                String key = it.next();
                String value = rawConfig.optString(key, "");
                if (!value.isEmpty()) config.put(key, value);
            }
        }
        return new DeckTile(instanceId, type, size, config,
                DeckTileAppearance.fromJson(object.optJSONObject("appearance")));
    }

    public static synchronized boolean saveLayout(Context c, DeckLayout layout) {
        if (c == null || layout == null || !valid(layout)) return false;
        try {
            JSONArray items = new JSONArray();
            for (DeckItem item : layout.items) {
                JSONObject object = new JSONObject();
                if (item instanceof DeckTileItem) {
                    object.put("kind", "tile");
                    object.put("tile", tileToJson(((DeckTileItem) item).tile));
                } else if (item instanceof DeckSection) {
                    object.put("kind", "section");
                    object.put("id", item.id);
                    object.put("title", ((DeckSection) item).title);
                } else if (item instanceof DeckFolder) {
                    object.put("kind", "folder");
                    object.put("id", item.id);
                    object.put("title", ((DeckFolder) item).title);
                    JSONArray children = new JSONArray();
                    for (DeckTile tile : ((DeckFolder) item).tiles) children.put(tileToJson(tile));
                    object.put("tiles", children);
                }
                items.put(object);
            }
            JSONObject value = new JSONObject();
            value.put("id", layout.id);
            value.put("items", items);
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            root.put("layout", value);
            return prefs(c).edit().putString(KEY_LAYOUT, root.toString()).commit();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean valid(DeckLayout layout) {
        if (layout.items.size() > MAX_ITEMS) return false;
        Set<String> ids = new HashSet<>();
        int tiles = 0, sections = 0, folders = 0;
        for (DeckItem item : layout.items) {
            if (item == null || !ids.add(item.id)) return false;
            if (item instanceof DeckTileItem) tiles++;
            else if (item instanceof DeckSection) sections++;
            else if (item instanceof DeckFolder) {
                folders++;
                DeckFolder folder = (DeckFolder) item;
                if (folder.tiles.size() > DeckFolder.MAX_CHILDREN) return false;
                for (DeckTile tile : folder.tiles) {
                    if (tile == null || !ids.add(tile.instanceId)) return false;
                    tiles++;
                }
            } else return false;
        }
        return tiles <= MAX_TILES && sections <= MAX_SECTIONS && folders <= MAX_FOLDERS;
    }

    private static JSONObject tileToJson(DeckTile tile) throws Exception {
        JSONObject object = new JSONObject();
        object.put("id", tile.instanceId);
        object.put("type", tile.type);
        object.put("size", tile.size.key());
        JSONObject config = new JSONObject();
        for (Map.Entry<String, String> entry : tile.configMap().entrySet()) config.put(entry.getKey(), entry.getValue());
        object.put("config", config);
        object.put("appearance", tile.appearance.toJson());
        return object;
    }

    /** Legacy replacement API: stores these as root tiles in this layout. */
    public static synchronized boolean save(Context c, List<DeckTile> tiles) {
        if (tiles == null || tiles.size() > MAX_TILES) return false;
        List<DeckItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (DeckTile tile : tiles) if (tile != null) items.add(new DeckTileItem(repairTileId(tile, ids, index++)));
        return saveLayout(c, new DeckLayout(DeckLayout.PRIMARY_ID, items));
    }

    public static synchronized boolean add(Context c, DeckTile tile) {
        if (c == null || tile == null) return false;
        DeckLayout deck = deck(c);
        if (deck.allTiles().size() >= MAX_TILES || deck.items.size() >= MAX_ITEMS) return false;
        List<DeckItem> items = new ArrayList<>(deck.items);
        items.add(new DeckTileItem(tile));
        return saveLayout(c, new DeckLayout(deck.id, items));
    }

    public static synchronized boolean addSection(Context c, String title) {
        DeckLayout deck = deck(c);
        if (count(deck.items, DeckItem.Kind.SECTION) >= MAX_SECTIONS || deck.items.size() >= MAX_ITEMS) return false;
        List<DeckItem> items = new ArrayList<>(deck.items);
        items.add(new DeckSection(DeckTile.newInstanceId(), title));
        return saveLayout(c, new DeckLayout(deck.id, items));
    }

    public static synchronized boolean addFolder(Context c, String title) {
        DeckLayout deck = deck(c);
        if (count(deck.items, DeckItem.Kind.FOLDER) >= MAX_FOLDERS || deck.items.size() >= MAX_ITEMS) return false;
        List<DeckItem> items = new ArrayList<>(deck.items);
        items.add(new DeckFolder(DeckTile.newInstanceId(), title, null));
        return saveLayout(c, new DeckLayout(deck.id, items));
    }

    public static synchronized boolean remove(Context c, String instanceId) {
        DeckLayout deck = deck(c);
        List<DeckItem> items = new ArrayList<>();
        boolean removed = false;
        for (DeckItem original : deck.items) {
            DeckItem item = original;
            if (item instanceof DeckTileItem && item.id.equals(instanceId)) { removed = true; continue; }
            if (item instanceof DeckFolder) {
                List<DeckTile> children = new ArrayList<>(((DeckFolder) item).tiles);
                for (Iterator<DeckTile> it = children.iterator(); it.hasNext();) {
                    if (it.next().instanceId.equals(instanceId)) { it.remove(); removed = true; break; }
                }
                item = ((DeckFolder) item).withTiles(children);
            }
            items.add(item);
        }
        return removed && saveLayout(c, new DeckLayout(deck.id, items));
    }

    public static synchronized boolean removeSection(Context c, String sectionId) {
        return removeStructural(c, sectionId, false);
    }

    /** Folder removal is safe-only: children move back to the root at the folder's position. */
    public static synchronized boolean removeFolderMovingChildren(Context c, String folderId) {
        return removeStructural(c, folderId, true);
    }

    private static boolean removeStructural(Context c, String id, boolean folder) {
        DeckLayout deck = deck(c);
        List<DeckItem> items = new ArrayList<>();
        boolean removed = false;
        for (DeckItem item : deck.items) {
            if (item.id.equals(id) && ((!folder && item instanceof DeckSection) || (folder && item instanceof DeckFolder))) {
                removed = true;
                if (item instanceof DeckFolder) for (DeckTile tile : ((DeckFolder) item).tiles) items.add(new DeckTileItem(tile));
            } else items.add(item);
        }
        return removed && saveLayout(c, new DeckLayout(deck.id, items));
    }

    public static synchronized boolean renameItem(Context c, String id, String title) {
        DeckLayout deck = deck(c);
        List<DeckItem> items = new ArrayList<>();
        boolean changed = false;
        for (DeckItem original : deck.items) {
            DeckItem item = original;
            if (item.id.equals(id) && item instanceof DeckSection) { item = ((DeckSection) item).withTitle(title); changed = true; }
            else if (item.id.equals(id) && item instanceof DeckFolder) { item = ((DeckFolder) item).withTitle(title); changed = true; }
            items.add(item);
        }
        return changed && saveLayout(c, new DeckLayout(deck.id, items));
    }

    public static synchronized boolean resize(Context c, String instanceId, DeckTile.Size size) {
        DeckTile existing = findTile(c, instanceId);
        DeckTileRegistry.Definition definition = existing == null ? null : DeckTileRegistry.definition(existing.type);
        if (definition == null || !definition.supports(size)) return false;
        return replaceTile(c, instanceId, existing.withSize(size));
    }

    public static synchronized boolean configure(Context c, String instanceId, String key, String value) {
        DeckTile existing = findTile(c, instanceId);
        return existing != null && replaceTile(c, instanceId, existing.withConfig(key, value));
    }

    public static synchronized boolean updateAppearance(Context c, String instanceId, DeckTileAppearance appearance) {
        DeckTile existing = findTile(c, instanceId);
        return existing != null && replaceTile(c, instanceId, existing.withAppearance(appearance));
    }

    private static DeckTile findTile(Context c, String id) {
        if (id == null) return null;
        for (DeckTile tile : allTiles(c)) if (id.equals(tile.instanceId)) return tile;
        return null;
    }

    private static boolean replaceTile(Context c, String id, DeckTile replacement) {
        DeckLayout deck = deck(c);
        List<DeckItem> items = new ArrayList<>();
        boolean changed = false;
        for (DeckItem original : deck.items) {
            DeckItem item = original;
            if (item instanceof DeckTileItem && item.id.equals(id)) { item = new DeckTileItem(replacement); changed = true; }
            else if (item instanceof DeckFolder) {
                List<DeckTile> children = new ArrayList<>();
                for (DeckTile tile : ((DeckFolder) item).tiles) {
                    if (tile.instanceId.equals(id)) { children.add(replacement); changed = true; }
                    else children.add(tile);
                }
                item = ((DeckFolder) item).withTiles(children);
            }
            items.add(item);
        }
        return changed && saveLayout(c, new DeckLayout(deck.id, items));
    }

    /** Moves, never copies, one stable tile between root and one-level folders. */
    public static synchronized boolean moveTile(Context c, String tileId, String targetFolderId) {
        DeckLayout deck = deck(c);
        DeckTile moving = findTile(c, tileId);
        if (moving == null) return false;
        if (targetFolderId != null) {
            DeckFolder target = deck.folder(targetFolderId);
            if (target == null || target.tiles.size() >= DeckFolder.MAX_CHILDREN) return false;
        }
        List<DeckItem> stripped = new ArrayList<>();
        for (DeckItem original : deck.items) {
            DeckItem item = original;
            if (item instanceof DeckTileItem && item.id.equals(tileId)) continue;
            if (item instanceof DeckFolder) {
                List<DeckTile> children = new ArrayList<>();
                for (DeckTile tile : ((DeckFolder) item).tiles) if (!tile.instanceId.equals(tileId)) children.add(tile);
                item = ((DeckFolder) item).withTiles(children);
            }
            stripped.add(item);
        }
        if (targetFolderId == null) stripped.add(new DeckTileItem(moving));
        else {
            for (int i = 0; i < stripped.size(); i++) {
                DeckItem item = stripped.get(i);
                if (item instanceof DeckFolder && item.id.equals(targetFolderId)) {
                    List<DeckTile> children = new ArrayList<>(((DeckFolder) item).tiles);
                    children.add(moving);
                    stripped.set(i, ((DeckFolder) item).withTiles(children));
                    break;
                }
            }
        }
        return saveLayout(c, new DeckLayout(deck.id, stripped));
    }

    public static synchronized boolean applyItemOrder(Context c, List<String> orderedIds) {
        DeckLayout deck = deck(c);
        Map<String, DeckItem> byId = new LinkedHashMap<>();
        for (DeckItem item : deck.items) byId.put(item.id, item);
        List<DeckItem> out = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String id : orderedIds) {
            DeckItem item = byId.get(id);
            if (item != null && placed.add(id)) out.add(item);
        }
        for (DeckItem item : deck.items) if (placed.add(item.id)) out.add(item);
        return saveLayout(c, new DeckLayout(deck.id, out));
    }

    public static synchronized boolean applyFolderOrder(Context c, String folderId, List<String> orderedIds) {
        DeckLayout deck = deck(c);
        DeckFolder folder = deck.folder(folderId);
        if (folder == null) return false;
        Map<String, DeckTile> byId = new LinkedHashMap<>();
        for (DeckTile tile : folder.tiles) byId.put(tile.instanceId, tile);
        List<DeckTile> out = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String id : orderedIds) {
            DeckTile tile = byId.get(id);
            if (tile != null && placed.add(id)) out.add(tile);
        }
        for (DeckTile tile : folder.tiles) if (placed.add(tile.instanceId)) out.add(tile);
        List<DeckItem> items = new ArrayList<>();
        for (DeckItem item : deck.items) items.add(item.id.equals(folderId) ? folder.withTiles(out) : item);
        return saveLayout(c, new DeckLayout(deck.id, items));
    }

    /** Compatibility order method for flat schema-1-era callers. */
    public static synchronized boolean applyOrder(Context c, List<String> orderedIds) {
        DeckLayout deck = deck(c);
        if (deck.items.size() == deck.rootTiles().size()) return applyItemOrder(c, orderedIds);
        Map<String, DeckTileItem> tiles = new LinkedHashMap<>();
        for (DeckItem item : deck.items) if (item instanceof DeckTileItem) tiles.put(item.id, (DeckTileItem) item);
        List<DeckTileItem> ordered = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String id : orderedIds) if (tiles.containsKey(id) && used.add(id)) ordered.add(tiles.get(id));
        for (DeckTileItem tile : tiles.values()) if (used.add(tile.id)) ordered.add(tile);
        Iterator<DeckTileItem> replacements = ordered.iterator();
        List<DeckItem> out = new ArrayList<>();
        for (DeckItem item : deck.items) out.add(item instanceof DeckTileItem ? replacements.next() : item);
        return saveLayout(c, new DeckLayout(deck.id, out));
    }

    public static synchronized boolean reset(Context c) { return save(c, defaults()); }

    public static synchronized boolean wouldDuplicate(Context c, DeckTile candidate) { return wouldDuplicate(allTiles(c), candidate); }

    static boolean wouldDuplicate(List<DeckTile> existing, DeckTile candidate) {
        if (existing == null || candidate == null) return false;
        DeckTileRegistry.Definition definition = DeckTileRegistry.definition(candidate.type);
        for (DeckTile tile : existing) {
            if (definition != null && definition.singleton) {
                if (candidate.type.equals(tile.type)) return true;
            } else if (candidate.duplicateKey().equals(tile.duplicateKey())) return true;
        }
        return false;
    }

    public static synchronized boolean contains(Context c, String type) {
        for (DeckTile tile : allTiles(c)) if (type != null && type.equals(tile.type)) return true;
        return false;
    }

    public static synchronized Map<String, Integer> typeCounts(Context c) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DeckTile tile : allTiles(c)) counts.put(tile.type, counts.containsKey(tile.type) ? counts.get(tile.type) + 1 : 1);
        return counts;
    }

    public static synchronized int sectionCount(Context c) { return count(deck(c).items, DeckItem.Kind.SECTION); }
    public static synchronized int folderCount(Context c) { return count(deck(c).items, DeckItem.Kind.FOLDER); }
    public static synchronized int folderTileCount(Context c) { return allTiles(c).size() - layout(c).size(); }

    public static synchronized void clearForTest(Context c) {
        if (c != null) prefs(c).edit().remove(KEY_LAYOUT).commit();
    }

    static void writeRawForTest(Context c, String raw) {
        if (c != null) prefs(c).edit().putString(KEY_LAYOUT, raw).commit();
    }

    static String rawForTest(Context c) { return prefs(c).getString(KEY_LAYOUT, ""); }

    static List<DeckTile> tiles(DeckTile... tiles) {
        return Collections.unmodifiableList(Arrays.asList(tiles));
    }
}
