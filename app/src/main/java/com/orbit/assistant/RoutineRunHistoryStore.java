package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Local, private record of recent routine executions.
 *
 * <p>Entries are written by the existing execution paths from the completion callback they
 * already implement, so this observes runs rather than taking part in them. Only concise,
 * already user-facing text is stored: routine and action names plus Orbit's own result message.
 * Action parameters, extension endpoints, headers, and configured secrets are never recorded.
 */
public final class RoutineRunHistoryStore {
    private static final String FILE = "orbit_routine_history";
    private static final String KEY = "runs_v1";
    /** Matches the bound Orbit already uses for stored action results. */
    static final int MAX_ENTRIES = 80;
    private static final int MAX_NAME_CHARS = 60;
    private static final int MAX_REASON_CHARS = 160;

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_TRIGGER = "trigger";
    public static final String SOURCE_WIDGET = "widget";

    public static final class Entry {
        public final String id;
        public final String routineId;
        public final String routineName;
        public final String source;
        public final long runAt;
        public final boolean success;
        public final int completedSteps;
        public final int totalSteps;
        /** 1-based step that failed, or 0 when the run completed. */
        public final int failedStep;
        public final String failedAction;
        public final String reason;

        Entry(String id, String routineId, String routineName, String source, long runAt,
              boolean success, int completedSteps, int totalSteps, int failedStep,
              String failedAction, String reason) {
            this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
            this.routineId = routineId == null ? "" : routineId;
            this.routineName = clip(routineName, MAX_NAME_CHARS);
            this.source = SOURCE_TRIGGER.equals(source) || SOURCE_WIDGET.equals(source)
                    ? source : SOURCE_MANUAL;
            this.runAt = runAt <= 0 ? System.currentTimeMillis() : runAt;
            this.success = success;
            this.completedSteps = Math.max(0, completedSteps);
            this.totalSteps = Math.max(0, totalSteps);
            this.failedStep = success ? 0 : Math.max(0, failedStep);
            this.failedAction = success ? "" : clip(failedAction, MAX_NAME_CHARS);
            this.reason = success ? "" : clip(reason, MAX_REASON_CHARS);
        }

        /** "Completed 3 of 3 steps" / "Failed at action 2: Discord Webhook". */
        public String headline() {
            if (success) {
                return totalSteps <= 0 ? "Completed"
                        : "Completed " + completedSteps + " of " + totalSteps
                        + (totalSteps == 1 ? " step" : " steps");
            }
            if (failedStep <= 0) return "Stopped before finishing";
            String label = failedAction.isEmpty() ? "" : ": " + failedAction;
            return "Failed at action " + failedStep + label;
        }

        public String sourceLabel() {
            if (SOURCE_TRIGGER.equals(source)) return "Automatic trigger";
            if (SOURCE_WIDGET.equals(source)) return "Widget or tile";
            return "Run manually";
        }
    }

    private RoutineRunHistoryStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Records one finished run. {@code failedIndex} is the zero-based step that failed, or -1 when
     * the run completed. The failed action is passed as an object so its label is exact; only that
     * label is stored, never the action's parameters.
     */
    public static synchronized void record(Context c, String routineId, String routineName,
                                           String source, boolean success, int completedSteps,
                                           int totalSteps, int failedIndex,
                                           AssistantReply.Action failedAction, String reason) {
        if (c == null) return;
        try {
            String label = failedAction == null ? "" : RoutineActionCatalog.title(failedAction);
            int failedStep = failedIndex < 0 ? 0 : failedIndex + 1;
            List<Entry> entries = new ArrayList<>(stored(c));
            entries.add(new Entry(null, routineId, routineName, source, System.currentTimeMillis(),
                    success, completedSteps, totalSteps, failedStep, label, reason));
            if (entries.size() > MAX_ENTRIES) {
                entries = new ArrayList<>(entries.subList(entries.size() - MAX_ENTRIES, entries.size()));
            }
            write(c, entries);
        } catch (Exception ignored) {
            // History is diagnostic only; it must never interrupt a routine run.
        }
    }

    /** Recent runs, newest first. */
    public static synchronized List<Entry> list(Context c) {
        List<Entry> entries = new ArrayList<>(stored(c));
        Collections.reverse(entries);
        return entries;
    }

    public static synchronized boolean isEmpty(Context c) {
        return stored(c).isEmpty();
    }

    /** Removes every recorded run. Routines, pins, triggers, and actions are untouched. */
    public static synchronized boolean clear(Context c) {
        if (c == null) return false;
        return prefs(c).edit().remove(KEY).commit();
    }

    private static List<Entry> stored(Context c) {
        if (c == null) return Collections.emptyList();
        String raw = prefs(c).getString(KEY, "[]");
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length() && out.size() < MAX_ENTRIES; i++) {
                Entry entry = fromJson(arr.optJSONObject(i));
                if (entry != null) out.add(entry);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean write(Context c, List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry entry : entries) {
            JSONObject obj = toJson(entry);
            if (obj != null) arr.put(obj);
        }
        return prefs(c).edit().putString(KEY, arr.toString()).commit();
    }

    private static JSONObject toJson(Entry entry) {
        if (entry == null) return null;
        try {
            return new JSONObject()
                    .put("id", entry.id)
                    .put("routineId", entry.routineId)
                    .put("routineName", entry.routineName)
                    .put("source", entry.source)
                    .put("runAt", entry.runAt)
                    .put("success", entry.success)
                    .put("completedSteps", entry.completedSteps)
                    .put("totalSteps", entry.totalSteps)
                    .put("failedStep", entry.failedStep)
                    .put("failedAction", entry.failedAction)
                    .put("reason", entry.reason);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Entry fromJson(JSONObject obj) {
        if (obj == null) return null;
        try {
            String name = clip(obj.optString("routineName", ""), MAX_NAME_CHARS);
            if (name.isEmpty()) return null;
            return new Entry(obj.optString("id", ""), obj.optString("routineId", ""), name,
                    obj.optString("source", SOURCE_MANUAL), obj.optLong("runAt", 0L),
                    obj.optBoolean("success", false), obj.optInt("completedSteps", 0),
                    obj.optInt("totalSteps", 0), obj.optInt("failedStep", 0),
                    obj.optString("failedAction", ""), obj.optString("reason", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String clip(String value, int max) {
        String out = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (out.length() > max) out = out.substring(0, max).trim() + "…";
        return out;
    }
}
