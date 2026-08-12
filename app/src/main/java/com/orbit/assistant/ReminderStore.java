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

/** Durable local store for one-time Orbit reminders. */
public final class ReminderStore {
    private static final String PREFS = "orbit_reminders_v1";
    private static final String KEY = "items";
    private static final int MAX_ITEMS = 100;

    public static final class Item {
        public final String id;
        public final String message;
        public final long triggerAt;
        public final long createdAt;

        public Item(String id, String message, long triggerAt, long createdAt) {
            this.id = id == null ? "" : id;
            this.message = message == null ? "" : message.trim();
            this.triggerAt = triggerAt;
            this.createdAt = createdAt;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("message", message)
                    .put("triggerAt", triggerAt)
                    .put("createdAt", createdAt);
        }

        static Item fromJson(JSONObject o) {
            if (o == null) return null;
            String id = o.optString("id", "").trim();
            String message = o.optString("message", "").trim();
            long triggerAt = o.optLong("triggerAt", 0L);
            long createdAt = o.optLong("createdAt", 0L);
            if (id.isEmpty() || message.isEmpty() || triggerAt <= 0L) return null;
            return new Item(id, message, triggerAt, createdAt);
        }
    }

    private ReminderStore() {}

    public static Item create(String message, long triggerAt) {
        return new Item(UUID.randomUUID().toString(), message, triggerAt, System.currentTimeMillis());
    }

    public static synchronized List<Item> list(Context c) {
        ArrayList<Item> out = new ArrayList<>();
        if (c == null) return out;
        String raw = prefs(c).getString(KEY, "[]");
        try {
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                Item item = Item.fromJson(a.optJSONObject(i));
                if (item != null) out.add(item);
            }
        } catch (Exception ignored) {}
        Collections.sort(out, Comparator.comparingLong(item -> item.triggerAt));
        return out;
    }

    public static synchronized Item get(Context c, String id) {
        if (id == null || id.trim().isEmpty()) return null;
        for (Item item : list(c)) if (id.equals(item.id)) return item;
        return null;
    }

    public static synchronized void upsert(Context c, Item item) {
        if (c == null || item == null || item.id.isEmpty() || item.message.isEmpty() || item.triggerAt <= 0L) return;
        ArrayList<Item> items = new ArrayList<>(list(c));
        boolean replaced = false;
        for (int i = 0; i < items.size(); i++) {
            if (item.id.equals(items.get(i).id)) {
                items.set(i, item);
                replaced = true;
                break;
            }
        }
        if (!replaced) items.add(item);
        Collections.sort(items, Comparator.comparingLong(x -> x.triggerAt));
        while (items.size() > MAX_ITEMS) items.remove(items.size() - 1);
        write(c, items);
    }

    public static synchronized boolean remove(Context c, String id) {
        if (c == null || id == null || id.trim().isEmpty()) return false;
        ArrayList<Item> items = new ArrayList<>(list(c));
        boolean changed = items.removeIf(item -> id.equals(item.id));
        if (changed) write(c, items);
        return changed;
    }

    private static void write(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        if (items != null) {
            for (Item item : items) {
                try { a.put(item.toJson()); } catch (Exception ignored) {}
            }
        }
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
