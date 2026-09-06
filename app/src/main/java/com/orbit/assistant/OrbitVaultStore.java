package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The durable local Vault: what the user saved, in the order they want to see it.
 *
 * <p>Text and metadata live in one private JSON document; pictures live as private files that
 * {@link OrbitVaultMedia} owns and this store points at. The split is deliberate. Image bytes in a
 * preferences file would be read and rewritten in full on every single save, and a personal
 * collection would make that slower every week; a path is a few dozen characters and the picture is
 * opened only when something actually draws it.
 *
 * <p>Everything here is local and offline. Nothing in this file contacts a provider, builds a
 * prompt, touches Memory, or writes a conversation. Saving works with no account, no network, and
 * no AI configured, which is the whole point of a Vault.
 */
public final class OrbitVaultStore {

    private static final String FILE = "orbit_vault";
    private static final String KEY = "items_v1";

    /**
     * How many items one device may keep.
     *
     * <p>A ceiling rather than a target. It bounds the JSON document that is read on every open and
     * written on every save, and it bounds what a backup has to carry; a personal scrapbook that
     * genuinely reaches it is far past the point where Beta 1's flat list is the right shape.
     */
    public static final int MAX_ITEMS = 300;

    /** The order the Vault list is shown in. Newest first is the default. */
    public enum Sort {
        NEWEST("newest", "Newest first"),
        OLDEST("oldest", "Oldest first");

        /** Stored identity: never rename. */
        public final String id;
        public final String label;

        Sort(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Sort fromId(String value) {
            for (Sort sort : values()) if (sort.id.equals(value)) return sort;
            return NEWEST;
        }
    }

    private OrbitVaultStore() {}

    // ---- reading ---------------------------------------------------------------------------------

    /** Everything saved, newest first. */
    public static synchronized List<OrbitVaultItem> list(Context c) {
        return list(c, Sort.NEWEST);
    }

    public static synchronized List<OrbitVaultItem> list(Context c, Sort sort) {
        List<OrbitVaultItem> items = readAll(c);
        sort(items, sort);
        return items;
    }

    /**
     * Local search across everything the user can see.
     *
     * <p>Case-insensitive substring matching over the title, the body, the URL, and the source
     * label, decided entirely on this device. No provider is asked, no embedding is computed, and
     * nothing leaves the phone: a search on a plane returns the same answer as a search at home.
     */
    public static synchronized List<OrbitVaultItem> search(Context c, String query, Sort sort) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.US);
        if (needle.isEmpty()) return list(c, sort);
        List<OrbitVaultItem> matches = new ArrayList<>();
        for (OrbitVaultItem item : readAll(c)) {
            if (item.searchHaystack().contains(needle)) matches.add(item);
        }
        sort(matches, sort);
        return matches;
    }

    public static synchronized OrbitVaultItem get(Context c, String id) {
        if (id == null || id.trim().isEmpty()) return null;
        for (OrbitVaultItem item : readAll(c)) if (id.equals(item.id)) return item;
        return null;
    }

    public static synchronized int count(Context c) {
        return readAll(c).size();
    }

    // ---- saving ----------------------------------------------------------------------------------

    /**
     * Saves a note, or the address it turns out to be.
     *
     * <p>Classification happens here rather than at each call site, so text typed into Quick
     * Capture, text pasted from the clipboard, and text shared from another app all become the same
     * kind of item when they are the same thing. A paragraph is a note; one bare http or https
     * address is a link.
     */
    public static synchronized OrbitVaultItem saveText(Context c, String title, String body,
                                                       String source) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) return null;
        String link = OrbitVaultItem.singleLinkOrEmpty(text);
        return insert(c, link.isEmpty() ? OrbitVaultItem.TYPE_TEXT : OrbitVaultItem.TYPE_LINK,
                title, link.isEmpty() ? text : link, source, "");
    }

    /** Saves an address the caller has already decided is a link. */
    public static synchronized OrbitVaultItem saveLink(Context c, String title, String url,
                                                       String source) {
        String link = OrbitVaultItem.singleLinkOrEmpty(url);
        if (link.isEmpty()) return null;
        return insert(c, OrbitVaultItem.TYPE_LINK, title, link, source, "");
    }

    /**
     * Copies a picture into Orbit's own storage and saves an item pointing at the copy.
     *
     * <p>The copy is written first. If it fails, nothing is added at all rather than an item
     * pointing at a picture that does not exist.
     */
    public static synchronized OrbitVaultItem saveImage(Context c, Bitmap bitmap, String title,
                                                        String source) {
        if (bitmap == null) return null;
        String path = OrbitVaultMedia.save(c, bitmap);
        if (path.isEmpty()) return null;
        OrbitVaultItem saved = insert(c, OrbitVaultItem.TYPE_IMAGE, title, "", source, path);
        if (saved == null) OrbitVaultMedia.delete(c, path);
        return saved;
    }

    /**
     * Saves exactly the reply the user was looking at.
     *
     * <p>Only the visible text: no hidden provider metadata, no reasoning, no tool internals, no
     * request identity, no attached screen context, and nothing from the rest of the conversation.
     * A conversation stays a conversation; this is one answer the user picked out of it.
     *
     * <p>Two deliberate saves of the same reply produce two items. Saving is an act, not a state,
     * and quietly refusing the second would leave the user pressing a control that appears to do
     * nothing.
     */
    public static synchronized OrbitVaultItem saveOrbitReply(Context c, String visibleReply) {
        String text = visibleReply == null ? "" : visibleReply.trim();
        if (text.isEmpty()) return null;
        return insert(c, OrbitVaultItem.TYPE_ORBIT_REPLY, "", text, "Orbit answer", "");
    }

    private static OrbitVaultItem insert(Context c, String type, String title, String body,
                                         String source, String mediaPath) {
        if (c == null) return null;
        long now = System.currentTimeMillis();
        OrbitVaultItem item = new OrbitVaultItem(UUID.randomUUID().toString(), type, title, body,
                source, mediaPath, now, now);
        if (!OrbitVaultItem.isStorable(item)) return null;
        List<OrbitVaultItem> all = readAll(c);
        all.add(0, item);
        // The oldest items go first when the ceiling is reached, and their pictures go with them.
        while (all.size() > MAX_ITEMS) {
            OrbitVaultItem dropped = all.remove(all.size() - 1);
            OrbitVaultMedia.delete(c, dropped.mediaPath);
        }
        writeAll(c, all);
        return item;
    }

    // ---- editing ---------------------------------------------------------------------------------

    /** Renames one item. Available for every kind, including a saved answer. */
    public static synchronized boolean updateTitle(Context c, String id, String title) {
        OrbitVaultItem existing = get(c, id);
        if (existing == null) return false;
        return replace(c, new OrbitVaultItem(existing.id, existing.type, title, existing.body,
                existing.source, existing.mediaPath, existing.createdAt, System.currentTimeMillis()));
    }

    /**
     * Rewrites a note the user wrote.
     *
     * <p>Refused for anything whose body is a record rather than a draft. A link's address and a
     * saved answer's text are what they are; changing either would leave the user with something
     * that still calls itself a link, or a quotation, and is neither.
     */
    public static synchronized boolean updateText(Context c, String id, String title, String body) {
        OrbitVaultItem existing = get(c, id);
        if (existing == null || !existing.bodyIsEditable()) return false;
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) return false;
        return replace(c, new OrbitVaultItem(existing.id, existing.type, title, text,
                existing.source, existing.mediaPath, existing.createdAt, System.currentTimeMillis()));
    }

    private static boolean replace(Context c, OrbitVaultItem updated) {
        if (!OrbitVaultItem.isStorable(updated)) return false;
        List<OrbitVaultItem> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).id.equals(updated.id)) continue;
            all.set(i, updated);
            writeAll(c, all);
            return true;
        }
        return false;
    }

    // ---- deletion --------------------------------------------------------------------------------

    /**
     * Removes one item, and the picture it owned.
     *
     * <p>There is no Trash in Beta 1, so the confirmation is the safeguard and the removal is real.
     * A picture is deleted only when no remaining item still refers to it and only when it is
     * genuinely inside the Vault's own directory: deleting a note can never reach a file, and
     * deleting an image can never reach a conversation's attachment.
     */
    public static synchronized boolean delete(Context c, String id) {
        if (c == null || id == null || id.trim().isEmpty()) return false;
        List<OrbitVaultItem> all = readAll(c);
        OrbitVaultItem removed = null;
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).id.equals(id)) continue;
            removed = all.remove(i);
            break;
        }
        if (removed == null) return false;
        writeAll(c, all);
        if (!removed.mediaPath.isEmpty() && !stillReferenced(all, removed.mediaPath)) {
            OrbitVaultMedia.delete(c, removed.mediaPath);
        }
        return true;
    }

    private static boolean stillReferenced(List<OrbitVaultItem> remaining, String path) {
        for (OrbitVaultItem item : remaining) if (path.equals(item.mediaPath)) return true;
        return false;
    }

    // ---- backup ----------------------------------------------------------------------------------

    /** The stored document exactly as it is on disk, for {@link OrbitBackupManager}. */
    static synchronized String backupJson(Context c) {
        return prefs(c).getString(KEY, "[]");
    }

    /**
     * Replaces the Vault with a validated backup document.
     *
     * <p>Committed rather than applied, so a restore that reports success has genuinely reached the
     * disk before the rest of the restore is allowed to depend on it.
     *
     * <p>It deliberately deletes nothing. A restore that replaces this collection may still be
     * rolled back a moment later, and the pictures the previous collection owned are exactly what
     * that rollback needs; removing them here would make the rollback restore rows pointing at
     * files this method had just destroyed. Clearing up is {@link #pruneUnreferencedMedia}'s job,
     * and it runs only once the whole restore has committed.
     */
    static synchronized boolean restoreBackupJson(Context c, String raw) {
        if (c == null) return false;
        return prefs(c).edit().putString(KEY, raw == null ? "[]" : raw).commit();
    }

    /**
     * Removes Vault pictures no saved item refers to any more.
     *
     * <p>Called after a restore has fully committed, when the previous collection's files really
     * are unreachable. Ordinary use never needs it: deleting an item already deletes the picture it
     * owned.
     */
    static synchronized int pruneUnreferencedMedia(Context c) {
        if (c == null) return 0;
        Set<String> keep = new HashSet<>();
        for (OrbitVaultItem item : readAll(c)) {
            if (!item.mediaPath.isEmpty()) keep.add(item.mediaPath);
        }
        return OrbitVaultMedia.pruneOrphans(c, keep);
    }

    // ---- storage ---------------------------------------------------------------------------------

    /**
     * Everything on disk that is still readable.
     *
     * <p>A row that will not parse is skipped rather than fatal, and a document that will not parse
     * at all reads as an empty Vault. Losing one damaged row is recoverable; refusing to open the
     * screen is not.
     */
    private static List<OrbitVaultItem> readAll(Context c) {
        List<OrbitVaultItem> out = new ArrayList<>();
        if (c == null) return out;
        try {
            JSONArray stored = new JSONArray(prefs(c).getString(KEY, "[]"));
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < stored.length(); i++) {
                OrbitVaultItem item = OrbitVaultItem.fromJson(stored.optJSONObject(i));
                if (item == null || !seen.add(item.id)) continue;
                out.add(item);
                if (out.size() >= MAX_ITEMS) break;
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return out;
    }

    private static void writeAll(Context c, List<OrbitVaultItem> items) {
        JSONArray out = new JSONArray();
        for (OrbitVaultItem item : items) {
            try {
                if (OrbitVaultItem.isStorable(item)) out.put(item.toJson());
            } catch (Exception ignored) {
                // One unwritable row is dropped; the rest of the Vault is still saved.
            }
        }
        prefs(c).edit().putString(KEY, out.toString()).apply();
    }

    private static void sort(List<OrbitVaultItem> items, Sort order) {
        Sort chosen = order == null ? Sort.NEWEST : order;
        items.sort((a, b) -> {
            int byTime = chosen == Sort.OLDEST
                    ? Long.compare(a.createdAt, b.createdAt)
                    : Long.compare(b.createdAt, a.createdAt);
            // Two things saved in the same millisecond still need one stable order, or the list
            // would reshuffle itself between two identical reads.
            return byTime != 0 ? byTime : a.id.compareTo(b.id);
        });
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
