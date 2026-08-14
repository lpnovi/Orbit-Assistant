package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Local, private persistence for Orbit routines. */
public final class RoutineStore {
    private static final String FILE = "orbit_routines";
    private static final String KEY = "routines_v1";
    public static final int MAX_ROUTINES = 50;
    public static final int MAX_NAME_LENGTH = 40;

    public static final class Routine {
        public final String id;
        public final String name;
        public final List<AssistantReply.Action> actions;
        public final long createdAt;
        public final long updatedAt;
        public final long lastRunAt;
        public final boolean pinned;

        public Routine(String id, String name, List<AssistantReply.Action> actions,
                       long createdAt, long updatedAt, long lastRunAt) {
            this(id, name, actions, createdAt, updatedAt, lastRunAt, false);
        }

        public Routine(String id, String name, List<AssistantReply.Action> actions,
                       long createdAt, long updatedAt, long lastRunAt, boolean pinned) {
            this.id = cleanId(id);
            this.name = sanitizeName(name);
            this.actions = copyActions(actions);
            this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
            this.updatedAt = updatedAt <= 0 ? this.createdAt : updatedAt;
            this.lastRunAt = Math.max(0L, lastRunAt);
            this.pinned = pinned;
        }

        public Routine withNameAndActions(String newName, List<AssistantReply.Action> newActions) {
            return new Routine(id, newName, newActions, createdAt, System.currentTimeMillis(),
                    lastRunAt, pinned);
        }

        public Routine withPinned(boolean newPinned) {
            return new Routine(id, name, actions, createdAt, updatedAt, lastRunAt, newPinned);
        }
    }

    private RoutineStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Saved routines in the order they are stored. Mutations work from this so the underlying
     * order is never disturbed by how the list happens to be displayed.
     */
    private static List<Routine> stored(Context c) {
        if (c == null) return Collections.emptyList();
        String raw = prefs(c).getString(KEY, "[]");
        List<Routine> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length() && out.size() < MAX_ROUTINES; i++) {
                Routine routine = fromJson(arr.optJSONObject(i));
                if (routine != null) out.add(routine);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Saved routines with pinned ones first. The partition is stable, so each group keeps its
     * stored relative order and pinning only lifts a routine above the unpinned ones. Ordering
     * lives here rather than in the Routines screen so every surface that lists routines agrees,
     * and so pinning never has to touch a routine's actions or its stored position.
     */
    public static synchronized List<Routine> list(Context c) {
        List<Routine> routines = stored(c);
        List<Routine> out = new ArrayList<>(routines.size());
        for (Routine routine : routines) if (routine.pinned) out.add(routine);
        for (Routine routine : routines) if (!routine.pinned) out.add(routine);
        return out;
    }

    /** Pins or unpins one routine without touching its actions, triggers, or run history. */
    public static synchronized boolean setPinned(Context c, String id, boolean pinned) {
        if (c == null || id == null || id.trim().isEmpty()) return false;
        List<Routine> routines = new ArrayList<>(stored(c));
        for (int i = 0; i < routines.size(); i++) {
            Routine routine = routines.get(i);
            if (!id.equals(routine.id)) continue;
            if (routine.pinned == pinned) return true;
            routines.set(i, routine.withPinned(pinned));
            return write(c, routines);
        }
        return false;
    }

    public static synchronized Routine findById(Context c, String id) {
        if (id == null || id.trim().isEmpty()) return null;
        for (Routine routine : stored(c)) if (routine.id.equals(id)) return routine;
        return null;
    }

    public static synchronized Routine findByName(Context c, String name) {
        String wanted = normalizeName(name);
        if (wanted.isEmpty()) return null;
        for (Routine routine : stored(c)) {
            if (normalizeName(routine.name).equals(wanted)) return routine;
        }
        return null;
    }

    public static synchronized boolean nameExists(Context c, String name, String exceptId) {
        String wanted = normalizeName(name);
        if (wanted.isEmpty()) return false;
        for (Routine routine : stored(c)) {
            if (exceptId != null && exceptId.equals(routine.id)) continue;
            if (normalizeName(routine.name).equals(wanted)) return true;
        }
        return false;
    }

    public static synchronized Routine create(String name, List<AssistantReply.Action> actions) {
        long now = System.currentTimeMillis();
        return new Routine(UUID.randomUUID().toString(), name, actions, now, now, 0L);
    }

    /**
     * Inserts or replaces a routine in place. commit() is intentional here so a
     * user pressing Save cannot leave the Activity before the routine is durable.
     */
    public static synchronized boolean upsert(Context c, Routine routine) {
        if (c == null || !validRoutine(routine)) return false;
        List<Routine> routines = new ArrayList<>(stored(c));
        int replace = -1;
        for (int i = 0; i < routines.size(); i++) {
            if (routines.get(i).id.equals(routine.id)) {
                replace = i;
                break;
            }
        }
        if (replace < 0 && routines.size() >= MAX_ROUTINES) return false;
        if (nameExistsIn(routines, routine.name, routine.id)) return false;
        if (replace >= 0) routines.set(replace, routine);
        else routines.add(routine);
        boolean written = write(c, routines);
        if (written) OrbitWidgets.updateAll(c);
        return written;
    }

    public static synchronized boolean delete(Context c, String id) {
        if (c == null || id == null) return false;
        List<Routine> routines = new ArrayList<>(stored(c));
        boolean removed = false;
        for (int i = routines.size() - 1; i >= 0; i--) {
            if (id.equals(routines.get(i).id)) {
                routines.remove(i);
                removed = true;
            }
        }
        if (!removed) return false;
        RoutineTriggerScheduler.cancelForRoutine(c, id);
        RoutineTriggerStore.deleteForRoutine(c, id);
        boolean written = write(c, routines);
        if (written && id.equals(Prefs.quickSettingsRoutineId(c))) {
            Prefs.setQuickSettingsRoutineId(c, "");
            QuickSettingsTiles.refreshRoutineTile(c);
        }
        if (written) OrbitWidgets.updateAll(c);
        return written;
    }

    public static synchronized void markRun(Context c, String id) {
        if (c == null || id == null) return;
        List<Routine> routines = new ArrayList<>(stored(c));
        boolean changed = false;
        for (int i = 0; i < routines.size(); i++) {
            Routine r = routines.get(i);
            if (!id.equals(r.id)) continue;
            routines.set(i, new Routine(r.id, r.name, r.actions, r.createdAt, r.updatedAt,
                    System.currentTimeMillis(), r.pinned));
            changed = true;
            break;
        }
        if (changed) write(c, routines);
    }

    public static List<AssistantReply.Action> copyActions(List<AssistantReply.Action> source) {
        List<AssistantReply.Action> out = new ArrayList<>();
        if (source == null) return out;
        for (AssistantReply.Action action : source) {
            AssistantReply.Action copy = RoutineActionCatalog.copy(action);
            if (copy != null) out.add(copy);
        }
        return out;
    }

    static synchronized String backupJson(Context c) {
        return prefs(c).getString(KEY, "[]");
    }

    static synchronized boolean restoreBackupJson(Context c, String raw) {
        boolean restored = prefs(c).edit().putString(KEY, raw == null ? "[]" : raw).commit();
        if (restored) OrbitWidgets.updateAll(c);
        return restored;
    }

    public static String sanitizeName(String name) {
        String value = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (value.length() > MAX_NAME_LENGTH) value = value.substring(0, MAX_NAME_LENGTH).trim();
        return value;
    }

    private static String normalizeName(String name) {
        return sanitizeName(name).toLowerCase(Locale.US);
    }

    private static boolean validRoutine(Routine routine) {
        if (routine == null || routine.id.isEmpty() || routine.name.isEmpty()) return false;
        if (routine.actions.isEmpty() || routine.actions.size() > RoutineActionCatalog.MAX_STEPS) return false;
        for (AssistantReply.Action action : routine.actions) {
            if (!RoutineActionCatalog.isValid(action)) return false;
        }
        return true;
    }

    private static boolean nameExistsIn(List<Routine> routines, String name, String exceptId) {
        String wanted = normalizeName(name);
        for (Routine r : routines) {
            if (exceptId != null && exceptId.equals(r.id)) continue;
            if (normalizeName(r.name).equals(wanted)) return true;
        }
        return false;
    }

    private static boolean write(Context c, List<Routine> routines) {
        JSONArray arr = new JSONArray();
        if (routines != null) {
            for (Routine routine : routines) {
                JSONObject obj = toJson(routine);
                if (obj != null) arr.put(obj);
            }
        }
        return prefs(c).edit().putString(KEY, arr.toString()).commit();
    }

    private static JSONObject toJson(Routine routine) {
        if (!validRoutine(routine)) return null;
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", routine.id);
            obj.put("name", routine.name);
            obj.put("createdAt", routine.createdAt);
            obj.put("updatedAt", routine.updatedAt);
            obj.put("lastRunAt", routine.lastRunAt);
            // Only written when set, so routine data from before pinning existed and
            // backups taken from it stay byte-identical until something is pinned.
            if (routine.pinned) obj.put("pinned", true);
            JSONArray actions = new JSONArray();
            for (AssistantReply.Action action : routine.actions) {
                JSONObject a = new JSONObject();
                a.put("type", action.type);
                a.put("params", new JSONObject(action.params == null ? "{}" : action.params.toString()));
                a.put("requiresConfirmation", action.requiresConfirmation);
                actions.put(a);
            }
            obj.put("actions", actions);
            return obj;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Routine fromJson(JSONObject obj) {
        if (obj == null) return null;
        try {
            String id = cleanId(obj.optString("id", ""));
            String name = sanitizeName(obj.optString("name", ""));
            if (id.isEmpty() || name.isEmpty()) return null;
            JSONArray arr = obj.optJSONArray("actions");
            if (arr == null || arr.length() == 0 || arr.length() > RoutineActionCatalog.MAX_STEPS) return null;
            List<AssistantReply.Action> actions = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a == null) return null;
                AssistantReply.Action action = new AssistantReply.Action(
                        a.optString("type", ""), a.optJSONObject("params"),
                        a.optBoolean("requiresConfirmation", false));
                // Never silently drop a damaged/unknown step. A partially loaded
                // automation could execute a materially different routine.
                if (!RoutineActionCatalog.isValid(action)) return null;
                actions.add(action);
            }
            return new Routine(id, name, actions,
                    obj.optLong("createdAt", System.currentTimeMillis()),
                    obj.optLong("updatedAt", System.currentTimeMillis()),
                    obj.optLong("lastRunAt", 0L),
                    obj.optBoolean("pinned", false));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String cleanId(String id) {
        return id == null ? "" : id.trim();
    }
}
