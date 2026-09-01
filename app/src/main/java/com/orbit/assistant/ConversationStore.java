package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Small on-device conversation archive. Nothing is synced by Orbit itself.
 * Conversations are stored in the app's private SharedPreferences as JSON.
 */
public final class ConversationStore {
    private static final String FILE = "orbit_conversations";
    private static final String KEY = "items_v1";
    private static final int MAX_CONVERSATIONS = 100;
    private static final int MAX_MESSAGES_PER_CHAT = 40;
    private static final int MAX_MESSAGE_CHARS = 12000;

    private ConversationStore() {}

    public static final class Conversation {
        public final String id;
        public final String title;
        public final long updatedAt;
        public final List<AssistantClient.History> messages;
        /** Empty means this chat follows the current global default until the user changes it. */
        public final String intelligenceMode;
        /**
         * Whether the user has pinned this chat to the top of Chats.
         *
         * <p>Absent from every conversation written before this release, and that is the whole
         * migration: a stored chat with no {@code pinned} key reads as false, which is what an
         * unpinned chat is. Nothing has to be rewritten to gain the field.
         */
        public final boolean pinned;

        public Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages) {
            this(id, title, updatedAt, messages, "");
        }

        public Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages, String intelligenceMode) {
            this(id, title, updatedAt, messages, intelligenceMode, false);
        }

        public Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages,
                            String intelligenceMode, boolean pinned) {
            this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
            this.title = title == null || title.trim().isEmpty() ? "Untitled chat" : title.trim();
            this.updatedAt = updatedAt;
            this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
            this.intelligenceMode = intelligenceMode == null ? "" : intelligenceMode.trim();
            this.pinned = pinned;
        }
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static synchronized void save(Context c, String id, List<AssistantClient.History> history) {
        if (!Prefs.historyEnabled(c) || history == null || !hasUserMessage(history)) return;
        List<Conversation> all = readAll(c);
        String wantedId = id == null || id.isEmpty() ? newId() : id;
        Conversation existing = null;
        for (Conversation item : all) {
            if (wantedId.equals(item.id)) {
                existing = item;
                break;
            }
        }
        all.removeIf(item -> wantedId.equals(item.id));

        List<AssistantClient.History> clipped = new ArrayList<>();
        int start = Math.max(0, history.size() - MAX_MESSAGES_PER_CHAT);
        for (int i = start; i < history.size(); i++) {
            AssistantClient.History h = history.get(i);
            if (h == null || h.content == null || h.content.trim().isEmpty()) continue;
            clipped.add(new AssistantClient.History(
                    "assistant".equalsIgnoreCase(h.role) ? "assistant" : "user",
                    clip(h.content, MAX_MESSAGE_CHARS),
                    h.screenAttached,
                    h.attachmentPaths,
                    h.attachmentKind,
                    h.attachmentLabel,
                    clip(h.attachmentText, 105000),
                    // Memory fields keep their existing save behaviour; only the stopped-turn
                    // anchor is added here, because losing it would move a mark off its turn.
                    "", "", "",
                    h.stoppedRequestId));
        }
        // A background response may be appended to disk after the assistant sheet
        // is hidden, while that old sheet still holds a shorter in-memory copy.
        // Never let a later lifecycle save erase that newer persisted suffix.
        // Orbit chats are append-only at the message level, so keeping the longer
        // stored copy is correct whenever the shorter copy is an exact prefix.
        if (existing != null && existing.messages.size() > clipped.size()
                && isExactPrefix(clipped, existing.messages)) {
            clipped = new ArrayList<>(existing.messages);
        }
        // A stop is recorded straight to disk by the request manager, which can happen while a
        // screen still holds an in-memory copy of the conversation from before it. Saving that
        // copy must not quietly rub the mark out, so stored anchors are carried back onto the
        // messages they belong to.
        if (existing != null) carryStoppedMarks(clipped, existing.messages);

        String computedTitle = titleFor(clipped);
        String finalTitle = existing != null && existing.title != null && !existing.title.trim().isEmpty()
                && !existing.title.equals(titleFor(existing.messages)) ? existing.title : computedTitle;
        all.add(new Conversation(wantedId, finalTitle, System.currentTimeMillis(), clipped,
                existing == null ? "" : existing.intelligenceMode, existing != null && existing.pinned));
        all.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        if (all.size() > MAX_CONVERSATIONS) all = new ArrayList<>(all.subList(0, MAX_CONVERSATIONS));
        writeAll(c, all);
    }

    public static synchronized List<Conversation> list(Context c) {
        List<Conversation> all = readAll(c);
        all.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return all;
    }

    public static synchronized Conversation load(Context c, String id) {
        if (id == null) return null;
        for (Conversation item : readAll(c)) if (id.equals(item.id)) return item;
        return null;
    }

    public static synchronized Conversation latest(Context c) {
        List<Conversation> all = list(c);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Removes a conversation and the private image files only it referred to.
     *
     * <p>Deleting a chat used to leave its stored attachment JPEGs behind forever, which was
     * tolerable while a turn held one small screenshot and is not once a turn can hold ten photos.
     * The files are collected from this conversation's own record first, then every other stored
     * conversation is checked, so a file a restored backup happens to share with another chat is
     * never removed out from under it.
     *
     * <p>This is the commit point, not the gesture. Chats defers the delete for the length of the
     * Undo window and calls here only once that window has genuinely closed, so an undone deletion
     * never reaches this method and nothing it could have destroyed is at risk.
     */
    public static synchronized void delete(Context c, String id) {
        List<Conversation> all = readAll(c);
        List<String> owned = new ArrayList<>();
        for (Conversation item : all) {
            if (item.id.equals(id)) owned.addAll(ownedAttachmentPaths(item));
        }
        all.removeIf(item -> item.id.equals(id));
        writeAll(c, all);
        ActionResultStore.clearConversation(c, id);

        if (owned.isEmpty()) return;
        Set<String> stillReferenced = new HashSet<>();
        for (Conversation item : all) stillReferenced.addAll(ownedAttachmentPaths(item));
        for (String path : owned) {
            if (!stillReferenced.contains(path)) AttachmentStore.delete(path);
        }
    }

    public static synchronized void appendMessage(Context c, String id, AssistantClient.History message) {
        if (!Prefs.historyEnabled(c) || id == null || id.isEmpty() || message == null ||
                message.content == null || message.content.trim().isEmpty()) return;
        Conversation existing = load(c, id);
        List<AssistantClient.History> messages = existing == null
                ? new ArrayList<>()
                : new ArrayList<>(existing.messages);
        messages.add(message);
        save(c, id, messages);
    }

    /**
     * Records that the user stopped {@code requestId} at this conversation's current end.
     *
     * <p>The one durable write behind a stopped mark, and the reason the mark stays where it
     * belongs. It anchors to the message that ends the stopped turn — the question itself when no
     * text arrived, or the partial answer when some did, since the manager persists that first.
     * Later turns are appended after this message, so they push the mark nowhere.
     *
     * <p>Nothing is written as content: only the anchor field changes, and no assistant message is
     * created. {@code updatedAt} is deliberately left alone, because stopping a reply is not new
     * activity and must not reorder the chat list.
     *
     * <p>Idempotent, and it never overwrites another turn's anchor: stopping the same request
     * twice, or a second request whose turn already carries a mark, changes nothing.
     *
     * @return true when this call is what recorded the mark.
     */
    public static synchronized boolean markTurnStopped(Context c, String id, String requestId) {
        if (c == null || id == null || id.isEmpty() || requestId == null) return false;
        String wanted = requestId.trim();
        if (wanted.isEmpty()) return false;
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            List<AssistantClient.History> messages = new ArrayList<>(existing.messages);
            if (messages.isEmpty()) return false;
            // Already recorded, in this process or an earlier one.
            for (AssistantClient.History h : messages) {
                if (h != null && wanted.equals(h.stoppedRequestId)) return false;
            }
            AssistantClient.History last = messages.get(messages.size() - 1);
            if (last == null || last.isStopped()) return false;
            messages.set(messages.size() - 1, last.withStoppedRequestId(wanted));
            all.set(x, new Conversation(existing.id, existing.title, existing.updatedAt, messages,
                    existing.intelligenceMode, existing.pinned));
            writeAll(c, all);
            return true;
        }
        return false;
    }

    /** Every request id this conversation has a stopped mark for, oldest turn first. */
    public static synchronized List<String> stoppedRequestIds(Context c, String id) {
        List<String> out = new ArrayList<>();
        Conversation existing = load(c, id);
        if (existing == null) return out;
        for (AssistantClient.History h : existing.messages) {
            if (h != null && h.isStopped()) out.add(h.stoppedRequestId);
        }
        return out;
    }

    /**
     * Pins or unpins a chat, leaving everything else about it alone.
     *
     * <p>Deliberately does not touch {@code updatedAt}: pinning is not activity, and moving a chat
     * to the top of Recent as a side effect of pinning it to the top of Pinned would be two
     * different reorderings for one gesture. Unpinning therefore returns the chat to exactly the
     * position in Recent it would have held all along.
     *
     * @return the resulting pinned state, or {@code false} when there is no such chat.
     */
    public static synchronized boolean setPinned(Context c, String id, boolean pinned) {
        if (id == null || id.trim().isEmpty()) return false;
        List<Conversation> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            if (item.pinned == pinned) return pinned;
            all.set(i, new Conversation(item.id, item.title, item.updatedAt, item.messages,
                    item.intelligenceMode, pinned));
            writeAll(c, all);
            return pinned;
        }
        return false;
    }

    /** True when this chat is pinned. False for an unknown chat, which is not an error. */
    public static synchronized boolean isPinned(Context c, String id) {
        Conversation item = load(c, id);
        return item != null && item.pinned;
    }

    public static synchronized void rename(Context c, String id, String title) {
        if (id == null || title == null || title.trim().isEmpty()) return;
        List<Conversation> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            all.set(i, new Conversation(item.id, title.trim(), System.currentTimeMillis(), item.messages,
                    item.intelligenceMode, item.pinned));
            break;
        }
        writeAll(c, all);
    }


    public static synchronized String modeFor(Context c, String id) {
        Conversation existing = load(c, id);
        if (existing == null || existing.intelligenceMode == null || existing.intelligenceMode.trim().isEmpty()) {
            return Prefs.intelligenceMode(c);
        }
        return Prefs.normalizeMode(existing.intelligenceMode);
    }

    public static synchronized void setMode(Context c, String id, String mode) {
        if (id == null || id.isEmpty()) return;
        String normalized = Prefs.normalizeMode(mode);
        List<Conversation> all = readAll(c);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            all.set(i, new Conversation(item.id, item.title, item.updatedAt, item.messages, normalized, item.pinned));
            found = true;
            break;
        }
        // Empty/new chats do not need a disk record yet. The caller keeps the mode
        // in memory and save() will preserve it once the first user message exists.
        if (found) writeAll(c, all);
    }

    public static synchronized void clearMessages(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        List<Conversation> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            all.set(i, new Conversation(item.id, "Untitled chat", System.currentTimeMillis(), new ArrayList<>(),
                    item.intelligenceMode, item.pinned));
            writeAll(c, all);
            ActionResultStore.clearConversation(c, id);
            return;
        }
    }

    public static synchronized List<AssistantClient.History> removeLastAssistantTurn(Context c, String id) {
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            List<AssistantClient.History> messages = new ArrayList<>(existing.messages);
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("assistant".equalsIgnoreCase(messages.get(i).role)) {
                    messages.remove(i);
                    ActionResultStore.removeAssistantIndex(c, id, i);
                    break;
                }
            }
            // This is an intentional edit, not a lifecycle save, so bypass the
            // stale-prefix protection used by save().
            all.set(x, new Conversation(existing.id, existing.title, System.currentTimeMillis(), messages,
                    existing.intelligenceMode, existing.pinned));
            writeAll(c, all);
            return messages;
        }
        return new ArrayList<>();
    }

    public static synchronized List<Conversation> search(Context c, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.US);
        if (q.isEmpty()) return list(c);
        List<Conversation> out = new ArrayList<>();
        for (Conversation item : list(c)) {
            if (item.title.toLowerCase(java.util.Locale.US).contains(q)) { out.add(item); continue; }
            boolean match = false;
            for (AssistantClient.History h : item.messages) {
                if (h != null && h.content != null && h.content.toLowerCase(java.util.Locale.US).contains(q)) { match = true; break; }
            }
            if (match) out.add(item);
        }
        return out;
    }

    public static synchronized void clear(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply();
        c.getSharedPreferences("orbit_action_results", Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** The raw stored form, for a test that checks what old data does and does not contain. */
    static String backupJsonForTest(Context c) { return backupJson(c); }

    static synchronized String backupJson(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]");
    }

    static synchronized boolean restoreBackupJson(Context c, String raw) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY, raw == null ? "[]" : raw).commit();
    }


    /**
     * Restores stopped anchors from the stored copy onto the messages being saved.
     *
     * <p>Orbit conversations are append-only and are clipped from the front, so the two lists are
     * windows onto one sequence and differ only by a shift. The shift is found by matching role and
     * content, and the longest fully agreeing alignment wins; an anchor is then copied only onto a
     * message that agrees with the one that carried it and does not already have an anchor of its
     * own. When no alignment agrees — an edit rewrote the tail, say — nothing is copied, because a
     * mark guessed onto the wrong message would be worse than a mark that is gone.
     *
     * <p>Position is used, never prompt text: identical questions asked twice are different
     * messages here, distinguished by everything around them and by the request id they carry.
     */
    private static void carryStoppedMarks(List<AssistantClient.History> incoming,
                                          List<AssistantClient.History> stored) {
        if (incoming == null || stored == null || incoming.isEmpty() || stored.isEmpty()) return;
        boolean anyStored = false;
        for (AssistantClient.History h : stored) if (h != null && h.isStopped()) { anyStored = true; break; }
        if (!anyStored) return;

        int bestShift = 0;
        int bestOverlap = 0;
        for (int shift = -(stored.size() - 1); shift < incoming.size(); shift++) {
            int overlap = 0;
            boolean agrees = true;
            for (int i = 0; i < stored.size(); i++) {
                int j = i + shift;
                if (j < 0 || j >= incoming.size()) continue;
                if (!sameMessage(stored.get(i), incoming.get(j))) { agrees = false; break; }
                overlap++;
            }
            if (agrees && overlap > bestOverlap) { bestOverlap = overlap; bestShift = shift; }
        }
        if (bestOverlap == 0) return;
        // One matching message is not an alignment, it is a coincidence — and with a question
        // asked twice it is a coincidence that would put the mark on the wrong occurrence.
        if (bestOverlap < 2 && incoming.size() > 1 && stored.size() > 1) return;

        for (int i = 0; i < stored.size(); i++) {
            AssistantClient.History from = stored.get(i);
            if (from == null || !from.isStopped()) continue;
            int j = i + bestShift;
            if (j < 0 || j >= incoming.size()) continue;
            AssistantClient.History to = incoming.get(j);
            if (to == null || to.isStopped()) continue;
            incoming.set(j, to.withStoppedRequestId(from.stoppedRequestId));
        }
    }

    /** Same place in the conversation, for alignment purposes: same speaker, same words. */
    private static boolean sameMessage(AssistantClient.History a, AssistantClient.History b) {
        if (a == null || b == null) return a == b;
        return safe(a.role).equalsIgnoreCase(safe(b.role)) && safe(a.content).equals(safe(b.content));
    }

    private static boolean isExactPrefix(List<AssistantClient.History> shorter, List<AssistantClient.History> longer) {
        if (shorter == null || longer == null || shorter.size() > longer.size()) return false;
        for (int i = 0; i < shorter.size(); i++) {
            AssistantClient.History a = shorter.get(i);
            AssistantClient.History b = longer.get(i);
            if (a == null || b == null) {
                if (a != b) return false;
                continue;
            }
            String ar = a.role == null ? "" : a.role;
            String br = b.role == null ? "" : b.role;
            String ac = a.content == null ? "" : a.content;
            String bc = b.content == null ? "" : b.content;
            if (!ar.equals(br) || !ac.equals(bc) || a.screenAttached != b.screenAttached ||
                    !safe(a.attachmentPath).equals(safe(b.attachmentPath)) ||
                    !safe(a.attachmentKind).equals(safe(b.attachmentKind)) ||
                    !safe(a.attachmentLabel).equals(safe(b.attachmentLabel)) ||
                    !safe(a.attachmentText).equals(safe(b.attachmentText)) ||
                    !safe(a.memoryUsage).equals(safe(b.memoryUsage)) ||
                    !safe(a.memorySuggestionText).equals(safe(b.memorySuggestionText)) ||
                    !safe(a.memorySuggestionCategory).equals(safe(b.memorySuggestionCategory))) return false;
        }
        return true;
    }

    private static boolean hasUserMessage(List<AssistantClient.History> history) {
        for (AssistantClient.History h : history) {
            if (h != null && "user".equalsIgnoreCase(h.role) && h.content != null && !h.content.trim().isEmpty()) return true;
        }
        return false;
    }

    private static String titleFor(List<AssistantClient.History> history) {
        for (AssistantClient.History h : history) {
            if (h != null && "user".equalsIgnoreCase(h.role) && h.content != null) {
                String s = h.content.trim().replaceAll("\\s+", " ");
                if (s.length() > 48) s = s.substring(0, 47).trim() + "…";
                if (!s.isEmpty()) return s;
            }
        }
        return "Untitled chat";
    }

    private static List<Conversation> readAll(Context c) {
        ArrayList<Conversation> result = new ArrayList<>();
        try {
            SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String raw = p.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONArray msgs = o.optJSONArray("messages");
                ArrayList<AssistantClient.History> history = new ArrayList<>();
                if (msgs != null) {
                    for (int j = 0; j < msgs.length(); j++) {
                        JSONObject m = msgs.optJSONObject(j);
                        if (m == null) continue;
                        String content = m.optString("content", "");
                        if (content.isEmpty()) continue;
                        boolean attached = m.optBoolean("screenAttached", false);
                        history.add(new AssistantClient.History(
                                "assistant".equals(m.optString("role")) ? "assistant" : "user",
                                content,
                                attached,
                                // A conversation written before v0.7.8.0 Beta 3 has no
                                // attachmentPaths array and simply reads back as the one path it
                                // always had. Nothing stored is rewritten and no migration runs.
                                readAttachmentPaths(m),
                                m.optString("attachmentKind", attached ? "screen" : ""),
                                m.optString("attachmentLabel", attached ? "Screen attached" : ""),
                                m.optString("attachmentText", ""),
                                m.optString("memoryUsage", ""),
                                m.optString("memorySuggestionText", ""),
                                m.optString("memorySuggestionCategory", ""),
                                m.optString("stoppedRequestId", "")));
                    }
                }
                // A chat stored before pinning existed simply has no "pinned" key, and false is
                // exactly what an unpinned chat means, so old data needs no migration step.
                result.add(new Conversation(o.optString("id"), o.optString("title"),
                        o.optLong("updatedAt", 0), history, o.optString("intelligenceMode", ""),
                        o.optBoolean("pinned", false)));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void writeAll(Context c, List<Conversation> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Conversation item : all) {
                JSONObject o = new JSONObject();
                o.put("id", item.id);
                o.put("title", item.title);
                o.put("updatedAt", item.updatedAt);
                o.put("intelligenceMode", item.intelligenceMode);
                // Written only when true, so an unpinned chat's record is byte-for-byte what it
                // was before pinning existed and a downgrade reads it back unchanged.
                if (item.pinned) o.put("pinned", true);
                JSONArray msgs = new JSONArray();
                for (AssistantClient.History h : item.messages) {
                    JSONObject message = new JSONObject();
                    // Both are written: attachmentPath keeps a turn readable by anything that only
                    // knows the old shape, and attachmentPaths is what a current Orbit reads. They
                    // can never disagree because History derives the first from the list.
                    if (h.attachmentPaths.size() > 1) {
                        JSONArray paths = new JSONArray();
                        for (String path : h.attachmentPaths) paths.put(path);
                        message.put("attachmentPaths", paths);
                    }
                    msgs.put(message
                            .put("role", h.role)
                            .put("content", h.content)
                            .put("screenAttached", h.screenAttached)
                            .put("attachmentPath", safe(h.attachmentPath))
                            .put("attachmentKind", safe(h.attachmentKind))
                            .put("attachmentLabel", safe(h.attachmentLabel))
                            .put("attachmentText", safe(h.attachmentText))
                            .put("memoryUsage", safe(h.memoryUsage))
                            .put("memorySuggestionText", safe(h.memorySuggestionText))
                            .put("memorySuggestionCategory", safe(h.memorySuggestionCategory))
                            .put("stoppedRequestId", safe(h.stoppedRequestId)));
                }
                o.put("messages", msgs);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply();
    }

    /**
     * A stored message's ordered image paths, whichever shape it was written in.
     *
     * <p>The array wins when present, and the single legacy field is the answer when it is not. A
     * record that somehow carries both keeps the array, because the array is a superset by
     * construction and the scalar is only ever its head.
     */
    private static List<String> readAttachmentPaths(JSONObject message) {
        List<String> paths = new ArrayList<>();
        JSONArray stored = message.optJSONArray("attachmentPaths");
        if (stored != null) {
            for (int i = 0; i < stored.length(); i++) {
                String path = stored.optString(i, "");
                if (path != null && !path.trim().isEmpty()) paths.add(path);
            }
        }
        if (paths.isEmpty()) {
            String legacy = message.optString("attachmentPath", "");
            if (legacy != null && !legacy.trim().isEmpty()) paths.add(legacy);
        }
        return paths;
    }

    /**
     * Every private image file one conversation owns.
     *
     * <p>Read from the conversation's own record rather than from the filesystem, so nothing that
     * belongs to another chat, to a pending request, or to the last-screen cache can be caught up
     * in a deletion.
     */
    private static List<String> ownedAttachmentPaths(Conversation conversation) {
        List<String> paths = new ArrayList<>();
        if (conversation == null) return paths;
        for (AssistantClient.History h : conversation.messages) {
            if (h != null) paths.addAll(h.attachmentPaths);
        }
        return paths;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
