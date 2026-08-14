package com.orbit.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A proposed routine that has not been saved and cannot be run.
 *
 * <p>Deliberately separate from {@link RoutineStore.Routine}: a draft has no id, no storage, and
 * no execution path. Planner output is untrusted, so every step here has been re-validated against
 * {@link RoutineActionCatalog} exactly as a manually added step would be — a draft can only
 * contain actions the user could already have added by hand. Anything the planner asked for that
 * Orbit cannot represent is kept as a warning rather than becoming a placeholder step.
 */
public final class RoutineDraft {
    /** Bumped if the draft payload shape ever changes. */
    static final int SCHEMA = 1;
    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_NAME = "name";
    private static final String KEY_STEPS = "steps";
    private static final String KEY_TYPE = "type";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_WARNINGS = "warnings";
    private static final int MAX_WARNINGS = 8;
    private static final int MAX_WARNING_LENGTH = 120;

    public final String name;
    public final List<AssistantReply.Action> actions;
    /** Requests Orbit could not represent, shown to the user instead of being invented. */
    public final List<String> warnings;

    RoutineDraft(String name, List<AssistantReply.Action> actions, List<String> warnings) {
        this.name = RoutineStore.sanitizeName(name);
        this.actions = actions == null ? new ArrayList<>() : new ArrayList<>(actions);
        this.warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
    }

    public boolean hasSteps() {
        return !actions.isEmpty();
    }

    /**
     * Validates untrusted planner output into a draft, or returns null when nothing usable
     * remains. Unknown action types and out-of-range parameters are dropped with a warning so a
     * partly understood request still produces the steps that were valid.
     */
    public static RoutineDraft fromPlannerJson(Context context, String json) {
        if (json == null || json.trim().isEmpty()) return null;
        JSONObject root;
        try {
            root = new JSONObject(extractObject(json));
        } catch (Exception ignored) {
            return null;
        }

        List<String> warnings = new ArrayList<>();
        JSONArray unsupported = root.optJSONArray("unsupported");
        if (unsupported != null) {
            for (int i = 0; i < unsupported.length() && warnings.size() < MAX_WARNINGS; i++) {
                String value = clip(unsupported.optString(i, ""));
                if (!value.isEmpty()) warnings.add(value);
            }
        }

        List<AssistantReply.Action> actions = new ArrayList<>();
        JSONArray steps = root.optJSONArray(KEY_STEPS);
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                if (actions.size() >= RoutineActionCatalog.MAX_STEPS) {
                    addWarning(warnings, "Only the first " + RoutineActionCatalog.MAX_STEPS
                            + " steps were kept");
                    break;
                }
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                String type = step.optString(KEY_TYPE, "").trim().toUpperCase(java.util.Locale.US);
                JSONObject params = step.optJSONObject(KEY_PARAMS);
                if (params == null) params = new JSONObject();

                AssistantReply.Action action = new AssistantReply.Action(type, params, false);
                // The same gate a hand-added step passes. Nothing reaches a draft on the strength
                // of the planner having named it.
                if (!RoutineActionCatalog.isValid(action) || !isAllowedType(context, action)) {
                    addWarning(warnings, "Orbit couldn't add: "
                            + describeRejected(step.optString("describe", ""), type));
                    continue;
                }
                actions.add(action);
            }
        }

        if (actions.isEmpty()) return null;
        String name = root.optString(KEY_NAME, "").trim();
        if (name.isEmpty()) name = "New routine";
        return new RoutineDraft(name, actions, warnings);
    }

    /**
     * Types a draft may contain. Conditions are excluded for now: recognising scheduling and
     * condition wording is handled as a warning in this release rather than being generated.
     */
    private static boolean isAllowedType(Context context, AssistantReply.Action action) {
        String type = action.type == null ? "" : action.type.toUpperCase(java.util.Locale.US);
        if (RoutineActionCatalog.IF_CONDITION.equals(type)) return false;
        if (RoutineActionCatalog.EXTENSION_ACTION.equals(type)) {
            // Only an extension action the normal editor would currently offer.
            return context != null && OrbitExtensionStore.resolveEnabledAction(context,
                    action.params == null ? "" : action.params.optString("extensionId", ""),
                    action.params == null ? "" : action.params.optString("actionId", "")) != null;
        }
        return RoutineActionCatalog.isSupported(type);
    }

    private static String describeRejected(String described, String type) {
        String value = clip(described);
        if (!value.isEmpty()) return value;
        return type.isEmpty() ? "an unsupported action" : type.toLowerCase(java.util.Locale.US)
                .replace('_', ' ');
    }

    private static void addWarning(List<String> warnings, String message) {
        if (warnings.size() >= MAX_WARNINGS) return;
        String value = clip(message);
        if (!value.isEmpty() && !warnings.contains(value)) warnings.add(value);
    }

    private static String clip(String value) {
        String out = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (out.length() > MAX_WARNING_LENGTH) out = out.substring(0, MAX_WARNING_LENGTH).trim() + "…";
        return out;
    }

    /** Tolerates a model that wraps its JSON in prose or a code fence. */
    private static String extractObject(String raw) {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return text;
        return text.substring(start, end + 1);
    }

    /** Compact payload for handing an unsaved draft to the editor. */
    public String toPayload() {
        try {
            JSONArray steps = new JSONArray();
            for (AssistantReply.Action action : actions) {
                steps.put(new JSONObject()
                        .put(KEY_TYPE, action.type)
                        .put(KEY_PARAMS, new JSONObject(
                                action.params == null ? "{}" : action.params.toString())));
            }
            JSONArray warned = new JSONArray();
            for (String warning : warnings) warned.put(warning);
            return new JSONObject()
                    .put(KEY_SCHEMA, SCHEMA)
                    .put(KEY_NAME, name)
                    .put(KEY_STEPS, steps)
                    .put(KEY_WARNINGS, warned)
                    .toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Re-validates a payload on arrival, so the editor trusts nothing that was passed to it. */
    public static RoutineDraft fromPayload(Context context, String payload) {
        if (payload == null || payload.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(payload);
            if (root.optInt(KEY_SCHEMA, -1) != SCHEMA) return null;
            List<AssistantReply.Action> actions = new ArrayList<>();
            JSONArray steps = root.optJSONArray(KEY_STEPS);
            if (steps == null) return null;
            for (int i = 0; i < steps.length() && actions.size() < RoutineActionCatalog.MAX_STEPS; i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                AssistantReply.Action action = new AssistantReply.Action(
                        step.optString(KEY_TYPE, ""), step.optJSONObject(KEY_PARAMS), false);
                if (!RoutineActionCatalog.isValid(action) || !isAllowedType(context, action)) continue;
                actions.add(action);
            }
            if (actions.isEmpty()) return null;
            List<String> warnings = new ArrayList<>();
            JSONArray warned = root.optJSONArray(KEY_WARNINGS);
            if (warned != null) {
                for (int i = 0; i < warned.length() && warnings.size() < MAX_WARNINGS; i++) {
                    String value = clip(warned.optString(i, ""));
                    if (!value.isEmpty()) warnings.add(value);
                }
            }
            return new RoutineDraft(root.optString(KEY_NAME, ""), actions, warnings);
        } catch (Exception ignored) {
            return null;
        }
    }

    public List<String> stepSummaries() {
        List<String> out = new ArrayList<>();
        for (AssistantReply.Action action : actions) out.add(RoutineActionCatalog.title(action));
        return Collections.unmodifiableList(out);
    }
}
