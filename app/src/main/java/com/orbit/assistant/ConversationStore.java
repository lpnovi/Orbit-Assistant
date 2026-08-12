package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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

        public Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages) {
            this(id, title, updatedAt, messages, "");
        }

        public Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages, String intelligenceMode) {
            this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
            this.title = title == null || title.trim().isEmpty() ? "Untitled chat" : title.trim();
            this.updatedAt = updatedAt;
            this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
            this.intelligenceMode = intelligenceMode == null ? "" : intelligenceMode.trim();
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
                    h.attachmentPath,
                    h.attachmentKind,
                    h.attachmentLabel,
                    clip(h.attachmentText, 105000)));
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

        String computedTitle = titleFor(clipped);
        String finalTitle = existing != null && existing.title != null && !existing.title.trim().isEmpty()
                && !existing.title.equals(titleFor(existing.messages)) ? existing.title : computedTitle;
        all.add(new Conversation(wantedId, finalTitle, System.currentTimeMillis(), clipped, existing == null ? "" : existing.intelligenceMode));
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

    public static synchronized void delete(Context c, String id) {
        List<Conversation> all = readAll(c);
        all.removeIf(item -> item.id.equals(id));
        writeAll(c, all);
        ActionResultStore.clearConversation(c, id);
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

    public static synchronized void rename(Context c, String id, String title) {
        if (id == null || title == null || title.trim().isEmpty()) return;
        List<Conversation> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            all.set(i, new Conversation(item.id, title.trim(), System.currentTimeMillis(), item.messages, item.intelligenceMode));
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
            all.set(i, new Conversation(item.id, item.title, item.updatedAt, item.messages, normalized));
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
            all.set(i, new Conversation(item.id, "Untitled chat", System.currentTimeMillis(), new ArrayList<>(), item.intelligenceMode));
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
            all.set(x, new Conversation(existing.id, existing.title, System.currentTimeMillis(), messages, existing.intelligenceMode));
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
                                m.optString("attachmentPath", ""),
                                m.optString("attachmentKind", attached ? "screen" : ""),
                                m.optString("attachmentLabel", attached ? "Screen attached" : ""),
                                m.optString("attachmentText", ""),
                                m.optString("memoryUsage", ""),
                                m.optString("memorySuggestionText", ""),
                                m.optString("memorySuggestionCategory", "")));
                    }
                }
                result.add(new Conversation(o.optString("id"), o.optString("title"), o.optLong("updatedAt", 0), history, o.optString("intelligenceMode", "")));
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
                JSONArray msgs = new JSONArray();
                for (AssistantClient.History h : item.messages) {
                    msgs.put(new JSONObject()
                            .put("role", h.role)
                            .put("content", h.content)
                            .put("screenAttached", h.screenAttached)
                            .put("attachmentPath", safe(h.attachmentPath))
                            .put("attachmentKind", safe(h.attachmentKind))
                            .put("attachmentLabel", safe(h.attachmentLabel))
                            .put("attachmentText", safe(h.attachmentText))
                            .put("memoryUsage", safe(h.memoryUsage))
                            .put("memorySuggestionText", safe(h.memorySuggestionText))
                            .put("memorySuggestionCategory", safe(h.memorySuggestionCategory)));
                }
                o.put("messages", msgs);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
