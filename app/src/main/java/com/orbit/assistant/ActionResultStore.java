package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/** Persists visible action-result cards separately from chat text. */
public final class ActionResultStore {
    private static final String FILE = "orbit_action_results";
    private static final int MAX_PER_CONVERSATION = 80;

    public static final class Entry {
        public final String id;
        public final int assistantIndex;
        public final AssistantReply.Action action;
        public final String status;
        public final String message;
        public final boolean success;
        public final int stepIndex;
        public final int totalSteps;
        public final long updatedAt;

        Entry(String id, int assistantIndex, AssistantReply.Action action, String status,
              String message, boolean success, int stepIndex, int totalSteps, long updatedAt) {
            this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
            this.assistantIndex = assistantIndex;
            this.action = action;
            this.status = status == null ? DeviceActionExecutor.STATUS_FAILED : status;
            this.message = message == null ? "" : message;
            this.success = success;
            this.stepIndex = stepIndex;
            this.totalSteps = totalSteps;
            this.updatedAt = updatedAt;
        }
    }

    private ActionResultStore() {}

    public static synchronized Entry record(Context c, String conversationId, int assistantIndex,
                                            AssistantReply.Action action,
                                            DeviceActionExecutor.Result result,
                                            int stepIndex, int totalSteps) {
        if (c == null || conversationId == null || conversationId.isEmpty() || action == null || result == null) return null;
        List<Entry> entries = loadAll(c, conversationId);
        Entry entry = new Entry(UUID.randomUUID().toString(), assistantIndex,
                cloneAction(action), result.status, result.message, result.success,
                stepIndex, totalSteps, System.currentTimeMillis());
        entries.add(entry);
        if (entries.size() > MAX_PER_CONVERSATION) {
            entries = new ArrayList<>(entries.subList(entries.size() - MAX_PER_CONVERSATION, entries.size()));
        }
        saveAll(c, conversationId, entries);
        return entry;
    }

    public static synchronized List<Entry> forAssistant(Context c, String conversationId, int assistantIndex) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : loadAll(c, conversationId)) if (e.assistantIndex == assistantIndex) out.add(e);
        out.sort((a, b) -> Integer.compare(a.stepIndex, b.stepIndex));
        return out;
    }

    public static synchronized Entry replace(Context c, String conversationId, String entryId,
                                             AssistantReply.Action action,
                                             DeviceActionExecutor.Result result) {
        if (c == null || conversationId == null || entryId == null || action == null || result == null) return null;
        List<Entry> entries = loadAll(c, conversationId);
        Entry replacement = null;
        for (int i = 0; i < entries.size(); i++) {
            Entry old = entries.get(i);
            if (!entryId.equals(old.id)) continue;
            replacement = new Entry(old.id, old.assistantIndex, cloneAction(action), result.status,
                    result.message, result.success, old.stepIndex, old.totalSteps, System.currentTimeMillis());
            entries.set(i, replacement);
            break;
        }
        if (replacement != null) saveAll(c, conversationId, entries);
        return replacement;
    }

    public static synchronized void clearConversation(Context c, String conversationId) {
        if (c == null || conversationId == null) return;
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(conversationId).apply();
    }

    public static synchronized void removeAssistantIndex(Context c, String conversationId, int assistantIndex) {
        List<Entry> entries = loadAll(c, conversationId);
        entries.removeIf(e -> e.assistantIndex == assistantIndex);
        saveAll(c, conversationId, entries);
    }

    static synchronized JSONObject backupSnapshot(Context c) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> entry : c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getAll().entrySet()) {
            if (!(entry.getValue() instanceof String)) throw new IllegalStateException("Invalid action-result store");
            out.put(entry.getKey(), new JSONArray((String) entry.getValue()));
        }
        return out;
    }

    static synchronized boolean restoreBackupSnapshot(Context c, JSONObject values) {
        if (values == null) return false;
        try {
            SharedPreferences.Editor e = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear();
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONArray entries = values.optJSONArray(key);
                if (key.trim().isEmpty() || entries == null) return false;
                e.putString(key, entries.toString());
            }
            return e.commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<Entry> loadAll(Context c, String conversationId) {
        ArrayList<Entry> out = new ArrayList<>();
        try {
            SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(conversationId, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONObject actionObj = o.optJSONObject("action");
                if (actionObj == null) continue;
                AssistantReply.Action action = new AssistantReply.Action(
                        actionObj.optString("type", ""), actionObj.optJSONObject("params"),
                        actionObj.optBoolean("requiresConfirmation", false));
                out.add(new Entry(o.optString("id", ""), o.optInt("assistantIndex", -1), action,
                        o.optString("status", DeviceActionExecutor.STATUS_FAILED),
                        o.optString("message", ""), o.optBoolean("success", false),
                        o.optInt("stepIndex", 0), o.optInt("totalSteps", 1),
                        o.optLong("updatedAt", 0L)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void saveAll(Context c, String conversationId, List<Entry> entries) {
        if (c == null || conversationId == null) return;
        JSONArray arr = new JSONArray();
        try {
            if (entries != null) {
                for (Entry e : entries) {
                    if (e == null || e.action == null) continue;
                    JSONObject action = new JSONObject()
                            .put("type", e.action.type)
                            .put("params", e.action.params)
                            .put("requiresConfirmation", e.action.requiresConfirmation);
                    arr.put(new JSONObject()
                            .put("id", e.id)
                            .put("assistantIndex", e.assistantIndex)
                            .put("action", action)
                            .put("status", e.status)
                            .put("message", e.message)
                            .put("success", e.success)
                            .put("stepIndex", e.stepIndex)
                            .put("totalSteps", e.totalSteps)
                            .put("updatedAt", e.updatedAt));
                }
            }
        } catch (Exception ignored) {}
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(conversationId, arr.toString()).apply();
    }

    private static AssistantReply.Action cloneAction(AssistantReply.Action action) {
        if (action == null) return null;
        try {
            return new AssistantReply.Action(action.type,
                    action.params == null ? new JSONObject() : new JSONObject(action.params.toString()),
                    action.requiresConfirmation);
        } catch (Exception ignored) {
            return new AssistantReply.Action(action.type, new JSONObject(), action.requiresConfirmation);
        }
    }
}
