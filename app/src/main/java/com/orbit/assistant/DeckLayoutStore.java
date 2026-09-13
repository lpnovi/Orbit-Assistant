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

/** Versioned, immediate-persistence storage for the user's saved Deck layouts. */
public final class DeckLayoutStore {
    private static final String FILE = "orbit_deck";
    private static final String KEY_LAYOUT = "deck_layout";

    public static final int SCHEMA_VERSION = 3;
    public static final int MAX_LAYOUTS = 10;
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
        return new DeckLayout(DeckLayout.PRIMARY_ID, DeckLayout.DEFAULT_NAME, items);
    }

    private static DeckCollection defaultCollection() {
        DeckLayout layout = defaultLayout();
        return new DeckCollection(layout.id, Collections.singletonList(layout));
    }

    public static synchronized boolean configured(Context c) {
        return c != null && prefs(c).contains(KEY_LAYOUT);
    }

    /** Reads storage and atomically migrates schema 1 or 2 to schema 3 once. */
    public static synchronized DeckCollection collection(Context c) {
        if (c == null) return defaultCollection();
        String raw = prefs(c).getString(KEY_LAYOUT, "");
        if (raw == null || raw.trim().isEmpty()) return defaultCollection();
        Parsed parsed = parse(raw);
        if (parsed == null) return defaultCollection();
        if (parsed.schema != SCHEMA_VERSION || parsed.repaired) {
            saveCollection(c, parsed.collection);
        }
        return parsed.collection;
    }

    /** The complete active Deck. Existing callers continue to operate on this value. */
    public static synchronized DeckLayout deck(Context c) {
        return collection(c).active();
    }

    public static synchronized List<DeckLayout> layouts(Context c) {
        return collection(c).layouts;
    }

    public static synchronized int layoutCount(Context c) {
        return collection(c).layouts.size();
    }

    public static synchronized String activeLayoutId(Context c) {
        return collection(c).activeLayoutId;
    }

    /** Legacy root-tile view retained for callers that do not need structural items. */
    public static synchronized List<DeckTile> layout(Context c) { return deck(c).rootTiles(); }
    public static synchronized List<DeckTile> allTiles(Context c) { return deck(c).allTiles(); }

    private static final class Parsed {
        final int schema;
        final DeckCollection collection;
        final boolean repaired;

        Parsed(int schema, DeckCollection collection, boolean repaired) {
            this.schema = schema;
            this.collection = collection;
            this.repaired = repaired;
        }
    }

    private static Parsed parse(String raw) {
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
                DeckLayout layout = new DeckLayout(
                        DeckLayout.PRIMARY_ID, DeckLayout.DEFAULT_NAME, items);
                return new Parsed(1, new DeckCollection(layout.id,
                        Collections.singletonList(layout)), false);
            }
            if (version == 2) {
                DeckLayout layout = layoutFromJson(root.optJSONObject("layout"), new HashSet<>());
                if (layout == null) return null;
                return new Parsed(2, new DeckCollection(layout.id,
                        Collections.singletonList(layout)), false);
            }
            if (version != SCHEMA_VERSION) return null;
            JSONArray rawLayouts = root.optJSONArray("layouts");
            if (rawLayouts == null) return null;
            List<DeckLayout> layouts = new ArrayList<>();
            Set<String> layoutIds = new HashSet<>();
            boolean repaired = false;
            for (int i = 0; i < rawLayouts.length() && layouts.size() < MAX_LAYOUTS; i++) {
                JSONObject rawLayout = rawLayouts.optJSONObject(i);
                if (rawLayout == null) { repaired = true; continue; }
                DeckLayout layout = layoutFromJson(rawLayout, layoutIds);
                if (layout == null) { repaired = true; continue; }
                // Persist every deterministic repair (duplicate or missing nested IDs, clamped
                // limits, sanitized names, and normalized values), not only a repaired layout ID.
                // The canonical JSON is then stable, so a second read is idempotent.
                if (!layoutToJson(layout).toString().equals(rawLayout.toString())) repaired = true;
                layouts.add(layout);
            }
            if (layouts.isEmpty()) return null;
            String active = root.optString("activeLayoutId", "").trim();
            boolean found = false;
            for (DeckLayout layout : layouts) if (layout.id.equals(active)) found = true;
            if (!found) {
                active = layouts.get(0).id;
                repaired = true;
            }
            return new Parsed(SCHEMA_VERSION, new DeckCollection(active, layouts), repaired);
        } catch (Exception e) {
            return null;
        }
    }

    private static DeckLayout layoutFromJson(JSONObject object, Set<String> layoutIds) {
        if (object == null) return null;
        JSONArray rawItems = object.optJSONArray("items");
        if (rawItems == null) return null;
        String rawId = object.optString("id", "").trim();
        String id = uniqueLayoutId(rawId, layoutIds);
        String name = DeckLayout.sanitizeName(object.optString("name", DeckLayout.DEFAULT_NAME));
        if (name.isEmpty()) name = DeckLayout.DEFAULT_NAME;
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
            } else if ("section".equals(kind)
                    && count(items, DeckItem.Kind.SECTION) < MAX_SECTIONS) {
                String itemId = repairId(item.optString("id", ""), ids, "section", i);
                items.add(new DeckSection(itemId, item.optString("title", "New section")));
            } else if ("folder".equals(kind)
                    && count(items, DeckItem.Kind.FOLDER) < MAX_FOLDERS) {
                String itemId = repairId(item.optString("id", ""), ids, "folder", i);
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
                items.add(new DeckFolder(itemId,
                        item.optString("title", "New folder"), children));
            }
        }
        return new DeckLayout(id, name, items);
    }

    private static String uniqueLayoutId(String raw, Set<String> ids) {
        String base = raw == null || raw.trim().isEmpty()
                ? DeckLayout.PRIMARY_ID : raw.trim();
        String candidate = base;
        int suffix = 1;
        while (!ids.add(candidate)) candidate = base + ":" + suffix++;
        return candidate;
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
        if (instanceId.isEmpty()) instanceId = "repaired:tile";
        DeckTile.Size stored = DeckTile.Size.fromKey(object.optString("size", "standard"));
        DeckTile.Size size = DeckTileRegistry.knows(type)
                ? DeckTileRegistry.coerceSize(type, stored) : stored;
        Map<String, String> config = new LinkedHashMap<>();
        JSONObject rawConfig = object.optJSONObject("config");
        if (rawConfig != null) {
            for (Iterator<String> it = rawConfig.keys(); it.hasNext();) {
                String key = it.next();
                if (rawConfig.isNull(key)) continue;
                config.put(key, rawConfig.optString(key, ""));
            }
        }
        return new DeckTile(instanceId, type, size, config,
                DeckTileAppearance.fromJson(object.optJSONObject("appearance")));
    }

    private static synchronized boolean saveCollection(Context c, DeckCollection collection) {
        if (c == null || !valid(collection)) return false;
        try {
            JSONArray layouts = new JSONArray();
            for (DeckLayout layout : collection.layouts) layouts.put(layoutToJson(layout));
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            root.put("activeLayoutId", collection.activeLayoutId);
            root.put("layouts", layouts);
            return prefs(c).edit().putString(KEY_LAYOUT, root.toString()).commit();
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject layoutToJson(DeckLayout layout) throws Exception {
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
        return new JSONObject().put("id", layout.id).put("name", layout.name).put("items", items);
    }

    private static JSONObject tileToJson(DeckTile tile) throws Exception {
        JSONObject object = new JSONObject();
        object.put("id", tile.instanceId);
        object.put("type", tile.type);
        object.put("size", tile.size.key());
        JSONObject config = new JSONObject();
        for (Map.Entry<String, String> entry : tile.configMap().entrySet()) {
            config.put(entry.getKey(), entry.getValue());
        }
        object.put("config", config);
        object.put("appearance", tile.appearance.toJson());
        return object;
    }

    private static boolean valid(DeckCollection collection) {
        if (collection == null || collection.layouts.isEmpty()
                || collection.layouts.size() > MAX_LAYOUTS) return false;
        Set<String> ids = new HashSet<>();
        boolean activeFound = false;
        for (DeckLayout layout : collection.layouts) {
            if (layout == null || !ids.add(layout.id) || !valid(layout)) return false;
            if (layout.id.equals(collection.activeLayoutId)) activeFound = true;
        }
        return activeFound;
    }

    private static boolean valid(DeckLayout layout) {
        if (layout == null || layout.id.isEmpty() || layout.items.size() > MAX_ITEMS) return false;
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

    /** Replaces only the active layout's content and preserves its saved name. */
    public static synchronized boolean saveLayout(Context c, DeckLayout layout) {
        return layout != null && saveLayout(c, activeLayoutId(c), layout);
    }

    public static synchronized boolean saveLayout(Context c, String expectedLayoutId,
                                                   DeckLayout layout) {
        if (c == null || layout == null || expectedLayoutId == null || !valid(layout)) return false;
        DeckCollection collection = collection(c);
        if (!expectedLayoutId.equals(collection.activeLayoutId)
                || !expectedLayoutId.equals(layout.id)) return false;
        List<DeckLayout> out = new ArrayList<>(collection.layouts.size());
        boolean replaced = false;
        for (DeckLayout stored : collection.layouts) {
            if (stored.id.equals(expectedLayoutId)) {
                out.add(new DeckLayout(stored.id, stored.name, layout.items));
                replaced = true;
            } else out.add(stored);
        }
        return replaced && saveCollection(c, new DeckCollection(collection.activeLayoutId, out));
    }

    /** Legacy replacement API: stores these as root tiles in the active layout. */
    public static synchronized boolean save(Context c, List<DeckTile> tiles) {
        String active = activeLayoutId(c);
        return save(c, active, tiles);
    }

    public static synchronized boolean save(Context c, String expectedLayoutId,
                                             List<DeckTile> tiles) {
        if (tiles == null || tiles.size() > MAX_TILES) return false;
        List<DeckItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (DeckTile tile : tiles) {
            if (tile != null) items.add(new DeckTileItem(repairTileId(tile, ids, index++)));
        }
        DeckLayout active = deck(c);
        return saveLayout(c, expectedLayoutId,
                new DeckLayout(expectedLayoutId, active.name, items));
    }

    // Saved-layout management is the only storage behavior that consults entitlement.

    private static boolean canManageLayouts(Context c) {
        return c != null && OrbitProEntitlement.hasPro(c);
    }

    public static synchronized DeckLayout createBlankLayout(Context c, String rawName) {
        if (!canManageLayouts(c)) return null;
        String name = DeckLayout.sanitizeName(rawName);
        if (name.isEmpty()) return null;
        return addAndActivate(c, new DeckLayout(DeckTile.newInstanceId(), name,
                Collections.emptyList()));
    }

    public static synchronized DeckLayout createTemplateLayout(
            Context c, String templateId, String rawName) {
        if (!canManageLayouts(c)) return null;
        DeckLayout layout = DeckLayoutTemplates.create(c, templateId, rawName);
        return layout == null ? null : addAndActivate(c, layout);
    }

    public static synchronized DeckLayout duplicateLayout(
            Context c, String sourceLayoutId, String rawName) {
        if (!canManageLayouts(c)) return null;
        DeckCollection collection = collection(c);
        if (collection.layouts.size() >= MAX_LAYOUTS) return null;
        DeckLayout source = collection.layout(sourceLayoutId);
        if (source == null) return null;
        String name = DeckLayout.sanitizeName(rawName);
        if (name.isEmpty()) name = DeckLayout.sanitizeName(source.name + " copy");
        DeckLayout copy = duplicateWithFreshIds(source, name);
        return addAndActivate(c, copy);
    }

    private static DeckLayout duplicateWithFreshIds(DeckLayout source, String name) {
        List<DeckItem> items = new ArrayList<>();
        for (DeckItem item : source.items) {
            if (item instanceof DeckTileItem) {
                items.add(new DeckTileItem(((DeckTileItem) item).tile.withNewInstanceId()));
            } else if (item instanceof DeckSection) {
                items.add(new DeckSection(DeckTile.newInstanceId(), ((DeckSection) item).title));
            } else if (item instanceof DeckFolder) {
                List<DeckTile> children = new ArrayList<>();
                for (DeckTile tile : ((DeckFolder) item).tiles) {
                    children.add(tile.withNewInstanceId());
                }
                items.add(new DeckFolder(DeckTile.newInstanceId(),
                        ((DeckFolder) item).title, children));
            }
        }
        return new DeckLayout(DeckTile.newInstanceId(), name, items);
    }

    private static DeckLayout addAndActivate(Context c, DeckLayout layout) {
        DeckCollection collection = collection(c);
        if (layout == null || collection.layouts.size() >= MAX_LAYOUTS || !valid(layout)) return null;
        List<DeckLayout> layouts = new ArrayList<>(collection.layouts);
        layouts.add(layout);
        return saveCollection(c, new DeckCollection(layout.id, layouts)) ? layout : null;
    }

    public static synchronized boolean renameLayout(Context c, String layoutId, String rawName) {
        if (!canManageLayouts(c)) return false;
        String name = DeckLayout.sanitizeName(rawName);
        if (name.isEmpty()) return false;
        DeckCollection collection = collection(c);
        List<DeckLayout> out = new ArrayList<>(collection.layouts.size());
        boolean changed = false;
        for (DeckLayout layout : collection.layouts) {
            if (layout.id.equals(layoutId)) {
                out.add(layout.withName(name));
                changed = true;
            } else out.add(layout);
        }
        return changed && saveCollection(c, new DeckCollection(collection.activeLayoutId, out));
    }

    public static synchronized boolean switchLayout(Context c, String layoutId) {
        if (!canManageLayouts(c)) return false;
        DeckCollection collection = collection(c);
        if (collection.layout(layoutId) == null) return false;
        return saveCollection(c, new DeckCollection(layoutId, collection.layouts));
    }

    public static synchronized boolean deleteLayout(Context c, String layoutId) {
        if (!canManageLayouts(c)) return false;
        DeckCollection collection = collection(c);
        if (collection.layouts.size() <= 1 || collection.layout(layoutId) == null) return false;
        List<DeckLayout> out = new ArrayList<>();
        for (DeckLayout layout : collection.layouts) {
            if (!layout.id.equals(layoutId)) out.add(layout);
        }
        String active = collection.activeLayoutId;
        if (layoutId.equals(active)) active = out.get(0).id;
        return saveCollection(c, new DeckCollection(active, out));
    }

    // Active-layout mutations. The expected-id variants reject stale UI callbacks.

    public static synchronized boolean add(Context c, DeckTile tile) {
        return add(c, activeLayoutId(c), tile);
    }

    public static synchronized boolean add(Context c, String expectedLayoutId, DeckTile tile) {
        if (c == null || tile == null) return false;
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        if (deck == null || deck.allTiles().size() >= MAX_TILES
                || deck.items.size() >= MAX_ITEMS) return false;
        List<DeckItem> items = new ArrayList<>(deck.items);
        items.add(new DeckTileItem(tile));
        return saveLayout(c, expectedLayoutId, deck.withItems(items));
    }

    public static synchronized boolean addSection(Context c, String title) {
        return addSection(c, activeLayoutId(c), title);
    }

    public static synchronized boolean addSection(Context c, String expectedLayoutId, String title) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        if (deck == null || count(deck.items, DeckItem.Kind.SECTION) >= MAX_SECTIONS
                || deck.items.size() >= MAX_ITEMS) return false;
        List<DeckItem> items = new ArrayList<>(deck.items);
        items.add(new DeckSection(DeckTile.newInstanceId(), title));
        return saveLayout(c, expectedLayoutId, deck.withItems(items));
    }

    public static synchronized boolean addFolder(Context c, String title) {
        return addFolder(c, activeLayoutId(c), title);
    }

    public static synchronized boolean addFolder(Context c, String expectedLayoutId, String title) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        if (deck == null || count(deck.items, DeckItem.Kind.FOLDER) >= MAX_FOLDERS
                || deck.items.size() >= MAX_ITEMS) return false;
        List<DeckItem> items = new ArrayList<>(deck.items);
        items.add(new DeckFolder(DeckTile.newInstanceId(), title, null));
        return saveLayout(c, expectedLayoutId, deck.withItems(items));
    }

    public static synchronized boolean remove(Context c, String instanceId) {
        return remove(c, activeLayoutId(c), instanceId);
    }

    public static synchronized boolean remove(Context c, String expectedLayoutId, String instanceId) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        if (deck == null) return false;
        List<DeckItem> items = new ArrayList<>();
        boolean removed = false;
        for (DeckItem original : deck.items) {
            DeckItem item = original;
            if (item instanceof DeckTileItem && item.id.equals(instanceId)) {
                removed = true;
                continue;
            }
            if (item instanceof DeckFolder) {
                List<DeckTile> children = new ArrayList<>(((DeckFolder) item).tiles);
                for (Iterator<DeckTile> it = children.iterator(); it.hasNext();) {
                    if (it.next().instanceId.equals(instanceId)) {
                        it.remove();
                        removed = true;
                        break;
                    }
                }
                item = ((DeckFolder) item).withTiles(children);
            }
            items.add(item);
        }
        return removed && saveLayout(c, expectedLayoutId, deck.withItems(items));
    }

    public static synchronized boolean removeSection(Context c, String sectionId) {
        return removeSection(c, activeLayoutId(c), sectionId);
    }

    public static synchronized boolean removeSection(
            Context c, String expectedLayoutId, String sectionId) {
        return removeStructural(c, expectedLayoutId, sectionId, false);
    }

    /** Folder removal is safe-only: children move to root at the folder's position. */
    public static synchronized boolean removeFolderMovingChildren(Context c, String folderId) {
        return removeFolderMovingChildren(c, activeLayoutId(c), folderId);
    }

    public static synchronized boolean removeFolderMovingChildren(
            Context c, String expectedLayoutId, String folderId) {
        return removeStructural(c, expectedLayoutId, folderId, true);
    }

    private static boolean removeStructural(
            Context c, String expectedLayoutId, String id, boolean folder) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        if (deck == null) return false;
        List<DeckItem> items = new ArrayList<>();
        boolean removed = false;
        for (DeckItem item : deck.items) {
            if (item.id.equals(id) && ((!folder && item instanceof DeckSection)
                    || (folder && item instanceof DeckFolder))) {
                removed = true;
                if (item instanceof DeckFolder) {
                    for (DeckTile tile : ((DeckFolder) item).tiles) {
                        items.add(new DeckTileItem(tile));
                    }
                }
            } else items.add(item);
        }
        return removed && saveLayout(c, expectedLayoutId, deck.withItems(items));
    }

    public static synchronized boolean renameItem(Context c, String id, String title) {
        return renameItem(c, activeLayoutId(c), id, title);
    }

    public static synchronized boolean renameItem(
            Context c, String expectedLayoutId, String id, String title) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        if (deck == null) return false;
        List<DeckItem> items = new ArrayList<>();
        boolean changed = false;
        for (DeckItem original : deck.items) {
            DeckItem item = original;
            if (item.id.equals(id) && item instanceof DeckSection) {
                item = ((DeckSection) item).withTitle(title);
                changed = true;
            } else if (item.id.equals(id) && item instanceof DeckFolder) {
                item = ((DeckFolder) item).withTitle(title);
                changed = true;
            }
            items.add(item);
        }
        return changed && saveLayout(c, expectedLayoutId, deck.withItems(items));
    }

    public static synchronized boolean resize(Context c, String instanceId, DeckTile.Size size) {
        return resize(c, activeLayoutId(c), instanceId, size);
    }

    public static synchronized boolean resize(
            Context c, String expectedLayoutId, String instanceId, DeckTile.Size size) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        DeckTile existing = findTile(deck, instanceId);
        DeckTileRegistry.Definition definition = existing == null
                ? null : DeckTileRegistry.definition(existing.type);
        if (definition == null || !definition.supports(size)) return false;
        return replaceTile(c, expectedLayoutId, deck, instanceId, existing.withSize(size));
    }

    public static synchronized boolean configure(
            Context c, String instanceId, String key, String value) {
        return configure(c, activeLayoutId(c), instanceId, key, value);
    }

    public static synchronized boolean configure(Context c, String expectedLayoutId,
                                                  String instanceId, String key, String value) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        DeckTile existing = findTile(deck, instanceId);
        return existing != null && replaceTile(c, expectedLayoutId, deck, instanceId,
                existing.withConfig(key, value));
    }

    public static synchronized boolean updateAppearance(
            Context c, String instanceId, DeckTileAppearance appearance) {
        return updateAppearance(c, activeLayoutId(c), instanceId, appearance);
    }

    public static synchronized boolean updateAppearance(Context c, String expectedLayoutId,
                                                         String instanceId,
                                                         DeckTileAppearance appearance) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        DeckTile existing = findTile(deck, instanceId);
        return existing != null && replaceTile(c, expectedLayoutId, deck, instanceId,
                existing.withAppearance(appearance));
    }

    private static DeckLayout activeDeck(Context c, String expectedLayoutId) {
        if (expectedLayoutId == null) return null;
        DeckCollection collection = collection(c);
        return expectedLayoutId.equals(collection.activeLayoutId) ? collection.active() : null;
    }

    private static DeckTile findTile(DeckLayout deck, String id) {
        if (deck == null || id == null) return null;
        for (DeckTile tile : deck.allTiles()) if (id.equals(tile.instanceId)) return tile;
        return null;
    }

    private static boolean replaceTile(Context c, String expectedLayoutId, DeckLayout deck,
                                       String id, DeckTile replacement) {
        if (deck == null) return false;
        List<DeckItem> items = new ArrayList<>();
        boolean changed = false;
        for (DeckItem original : deck.items) {
            DeckItem item = original;
            if (item instanceof DeckTileItem && item.id.equals(id)) {
                item = new DeckTileItem(replacement);
                changed = true;
            } else if (item instanceof DeckFolder) {
                List<DeckTile> children = new ArrayList<>();
                for (DeckTile tile : ((DeckFolder) item).tiles) {
                    if (tile.instanceId.equals(id)) {
                        children.add(replacement);
                        changed = true;
                    } else children.add(tile);
                }
                item = ((DeckFolder) item).withTiles(children);
            }
            items.add(item);
        }
        return changed && saveLayout(c, expectedLayoutId, deck.withItems(items));
    }

    /** Moves, never copies, one stable tile between root and one-level folders. */
    public static synchronized boolean moveTile(
            Context c, String tileId, String targetFolderId) {
        return moveTile(c, activeLayoutId(c), tileId, targetFolderId);
    }

    public static synchronized boolean moveTile(Context c, String expectedLayoutId,
                                                String tileId, String targetFolderId) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        DeckTile moving = findTile(deck, tileId);
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
                for (DeckTile tile : ((DeckFolder) item).tiles) {
                    if (!tile.instanceId.equals(tileId)) children.add(tile);
                }
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
        return saveLayout(c, expectedLayoutId, deck.withItems(stripped));
    }

    public static synchronized boolean applyItemOrder(Context c, List<String> orderedIds) {
        return applyItemOrder(c, activeLayoutId(c), orderedIds);
    }

    public static synchronized boolean applyItemOrder(
            Context c, String expectedLayoutId, List<String> orderedIds) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        if (deck == null || orderedIds == null) return false;
        Map<String, DeckItem> byId = new LinkedHashMap<>();
        for (DeckItem item : deck.items) byId.put(item.id, item);
        List<DeckItem> out = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String id : orderedIds) {
            DeckItem item = byId.get(id);
            if (item != null && placed.add(id)) out.add(item);
        }
        for (DeckItem item : deck.items) if (placed.add(item.id)) out.add(item);
        return saveLayout(c, expectedLayoutId, deck.withItems(out));
    }

    public static synchronized boolean applyFolderOrder(
            Context c, String folderId, List<String> orderedIds) {
        return applyFolderOrder(c, activeLayoutId(c), folderId, orderedIds);
    }

    public static synchronized boolean applyFolderOrder(Context c, String expectedLayoutId,
                                                        String folderId,
                                                        List<String> orderedIds) {
        DeckLayout deck = activeDeck(c, expectedLayoutId);
        if (deck == null || orderedIds == null) return false;
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
        for (DeckItem item : deck.items) {
            items.add(item.id.equals(folderId) ? folder.withTiles(out) : item);
        }
        return saveLayout(c, expectedLayoutId, deck.withItems(items));
    }

    /** Compatibility order method for flat schema-1-era callers. */
    public static synchronized boolean applyOrder(Context c, List<String> orderedIds) {
        String active = activeLayoutId(c);
        DeckLayout deck = activeDeck(c, active);
        if (deck.items.size() == deck.rootTiles().size()) {
            return applyItemOrder(c, active, orderedIds);
        }
        Map<String, DeckTileItem> tiles = new LinkedHashMap<>();
        for (DeckItem item : deck.items) {
            if (item instanceof DeckTileItem) tiles.put(item.id, (DeckTileItem) item);
        }
        List<DeckTileItem> ordered = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String id : orderedIds) {
            if (tiles.containsKey(id) && used.add(id)) ordered.add(tiles.get(id));
        }
        for (DeckTileItem tile : tiles.values()) if (used.add(tile.id)) ordered.add(tile);
        Iterator<DeckTileItem> replacements = ordered.iterator();
        List<DeckItem> out = new ArrayList<>();
        for (DeckItem item : deck.items) {
            out.add(item instanceof DeckTileItem ? replacements.next() : item);
        }
        return saveLayout(c, active, deck.withItems(out));
    }

    /** Reset affects only the active saved layout. */
    public static synchronized boolean reset(Context c) {
        String active = activeLayoutId(c);
        return reset(c, active);
    }

    public static synchronized boolean reset(Context c, String expectedLayoutId) {
        return save(c, expectedLayoutId, defaults());
    }

    public static synchronized boolean wouldDuplicate(Context c, DeckTile candidate) {
        return wouldDuplicate(allTiles(c), candidate);
    }

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
        for (DeckTile tile : allTiles(c)) {
            if (type != null && type.equals(tile.type)) return true;
        }
        return false;
    }

    public static synchronized Map<String, Integer> typeCounts(Context c) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DeckTile tile : allTiles(c)) {
            counts.put(tile.type, counts.containsKey(tile.type) ? counts.get(tile.type) + 1 : 1);
        }
        return counts;
    }

    public static synchronized int sectionCount(Context c) {
        return count(deck(c).items, DeckItem.Kind.SECTION);
    }

    public static synchronized int folderCount(Context c) {
        return count(deck(c).items, DeckItem.Kind.FOLDER);
    }

    public static synchronized int folderTileCount(Context c) {
        return allTiles(c).size() - layout(c).size();
    }

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
