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
    private static final String KEY_TRIGGER = "trigger";
    private static final int MAX_WARNINGS = 8;
    private static final int MAX_WARNING_LENGTH = 120;

    public final String name;
    public final List<AssistantReply.Action> actions;
    /** Requests Orbit could not represent, shown to the user instead of being invented. */
    public final List<String> warnings;
    /** Proposed automatic trigger. Never scheduled; applied only after an explicit save. */
    public final RoutineTriggerDraft trigger;

    RoutineDraft(String name, List<AssistantReply.Action> actions, List<String> warnings) {
        this(name, actions, warnings, null);
    }

    RoutineDraft(String name, List<AssistantReply.Action> actions, List<String> warnings,
                 RoutineTriggerDraft trigger) {
        this.name = RoutineStore.sanitizeName(name);
        this.actions = actions == null ? new ArrayList<>() : new ArrayList<>(actions);
        this.warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
        this.trigger = trigger;
    }

    public boolean hasTrigger() {
        return trigger != null;
    }

    public boolean hasSteps() {
        return !actions.isEmpty();
    }

    /**
     * What a planner response turned into. The planner needs to tell a response it could not read
     * from one it read perfectly well that asked for things Orbit cannot do, because only the
     * first is worth a repair attempt and they deserve different messages.
     */
    public static final class Outcome {
        /** The usable draft, or null when nothing survived validation. */
        public final RoutineDraft draft;
        /** Description of the response shape, for diagnostics. */
        public final String shape;
        /** True when a plan object was found at all. */
        public final boolean planFound;
        /** True when that plan object actually carried a steps array. */
        public final boolean stepsArrayFound;
        public final int stepsReturned;
        public final int stepsAccepted;
        /** Action types as returned, after normalisation. */
        public final List<String> returnedTypes;
        /** "TYPE — reason" for each step that did not survive validation. */
        public final List<String> rejected;

        Outcome(RoutineDraft draft, String shape, boolean planFound, boolean stepsArrayFound,
                int stepsReturned, int stepsAccepted, List<String> returnedTypes,
                List<String> rejected) {
            this.draft = draft;
            this.shape = shape == null ? "" : shape;
            this.planFound = planFound;
            this.stepsArrayFound = stepsArrayFound;
            this.stepsReturned = stepsReturned;
            this.stepsAccepted = stepsAccepted;
            this.returnedTypes = returnedTypes == null ? new ArrayList<>() : returnedTypes;
            this.rejected = rejected == null ? new ArrayList<>() : rejected;
        }

        /**
         * True when the response could not be read as a plan at all. Only this is worth one
         * repair attempt; a plan Orbit understood but cannot perform is a real answer.
         */
        public boolean isUnreadable() {
            return !planFound || !stepsArrayFound;
        }
    }

    /**
     * Validates untrusted planner output into a draft, or returns null when nothing usable
     * remains. Unknown action types and out-of-range parameters are dropped with a warning so a
     * partly understood request still produces the steps that were valid.
     */
    public static RoutineDraft fromPlannerJson(Context context, String json) {
        return parse(context, json).draft;
    }

    /**
     * Reads a raw planner response. Provider formatting variations are unwrapped and normalised by
     * {@link RoutinePlanResponse} first; every resulting step then passes exactly the same
     * {@link RoutineActionCatalog} gate a hand-added step passes, so normalisation can only change
     * how a step is spelled, never whether Orbit is willing to run it.
     */
    public static Outcome parse(Context context, String raw) {
        RoutinePlanResponse response = RoutinePlanResponse.read(raw);
        if (!response.hasPlan()) {
            return new Outcome(null, response.shape, false, false, 0, 0, null, null);
        }
        JSONObject root = response.plan;
        List<String> returnedTypes = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        List<String> warnings = new ArrayList<>();
        JSONArray unsupported = root.optJSONArray("unsupported");
        if (unsupported != null) {
            for (int i = 0; i < unsupported.length() && warnings.size() < MAX_WARNINGS; i++) {
                String value = clip(unsupported.optString(i, ""));
                if (!value.isEmpty()) warnings.add(value);
            }
        }

        List<AssistantReply.Action> actions = new ArrayList<>();
        // Where each accepted step sat in the planner's own list. An ELSE path is positional, so
        // this is what lets a branch be checked against the steps the planner actually counted.
        List<Integer> plannedAt = new ArrayList<>();
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
                returnedTypes.add(type.isEmpty() ? "(none)" : type);
                if (RoutineActionCatalog.IF_CONDITION.equals(type)) {
                    params = resolveConditionPlace(context, params, warnings);
                }

                AssistantReply.Action action = new AssistantReply.Action(type, params, false);
                // The same gate a hand-added step passes. Nothing reaches a draft on the strength
                // of the planner having named it.
                if (!RoutineActionCatalog.isValid(action) || !isAllowedType(context, action)) {
                    rejected.add((type.isEmpty() ? "(none)" : type) + " — "
                            + (RoutineActionCatalog.isSupported(type)
                                    ? "parameters failed validation" : "unsupported action type"));
                    addWarning(warnings, "Orbit couldn't add: "
                            + describeRejected(step.optString("describe", ""), type));
                    continue;
                }
                actions.add(action);
                plannedAt.add(i);
            }
        }
        reconcileBranches(actions, plannedAt, warnings);

        if (actions.isEmpty()) {
            return new Outcome(null, response.shape, true, response.hasStepsArray(),
                    returnedTypes.size(), 0, returnedTypes, rejected);
        }

        // Validated locally, including resolving any saved-place label. Nothing is scheduled.
        RoutineTriggerDraft trigger =
                RoutineTriggerDraft.fromJson(context, root.optJSONObject("trigger"), warnings);
        JSONArray extraTriggers = root.optJSONArray("additionalTriggers");
        if (extraTriggers != null && extraTriggers.length() > 0) {
            // One trigger is drafted in this release; a second is reported rather than dropped.
            addWarning(warnings, "Only the first automatic trigger was drafted. Add the others in "
                    + "Automatic triggers.");
        }
        // Kept for a planner that still answers in the pre-v0.7.5.0 shape: branching is supported
        // now, so this only matters when one was asked for and none survived into the draft.
        if (root.optBoolean("elseRequested", false) && !hasBranch(actions)) {
            addWarning(warnings, "Orbit couldn't build the \"otherwise\" branch. Add it in the "
                    + "IF step before saving.");
        }

        String name = root.optString(KEY_NAME, "").trim();
        if (name.isEmpty()) name = "New routine";
        return new Outcome(new RoutineDraft(name, actions, warnings, trigger), response.shape,
                true, response.hasStepsArray(), returnedTypes.size(), actions.size(),
                returnedTypes, rejected);
    }

    /**
     * Types a draft may contain. IF conditions are allowed from v0.7.3.1 and go through exactly
     * the same catalog validation as any other step, so the planner cannot express a condition the
     * editor could not already store and the engine could not already run.
     */
    private static boolean isAllowedType(Context context, AssistantReply.Action action) {
        String type = action.type == null ? "" : action.type.toUpperCase(java.util.Locale.US);
        if (RoutineActionCatalog.EXTENSION_ACTION.equals(type)) {
            // Only an extension action the normal editor would currently offer.
            return context != null && OrbitExtensionStore.resolveEnabledAction(context,
                    action.params == null ? "" : action.params.optString("extensionId", ""),
                    action.params == null ? "" : action.params.optString("actionId", "")) != null;
        }
        return RoutineActionCatalog.isSupported(type);
    }

    /**
     * Turns a saved-place label on a location condition into the coordinates the condition model
     * stores. The planner is only ever told place labels, never coordinates, so this lookup has to
     * happen here on the device. A label Orbit does not recognise is left unresolved, which means
     * the condition fails validation and is reported rather than pointed at a guessed location.
     */
    private static JSONObject resolveConditionPlace(Context context, JSONObject params,
                                                    List<String> warnings) {
        String mode = params.optString("mode", "").trim().toLowerCase(java.util.Locale.US);
        boolean needsPlace = RoutineConditionEvaluator.MODE_LOCATION.equals(mode)
                || RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(mode);
        if (!needsPlace || params.has("latitude") || params.has("longitude")) return params;

        String label = params.optString("locationName", "").trim();
        SavedPlaceStore.Place place = RoutineTriggerDraft.findPlace(context, label);
        if (place == null) {
            if (!label.isEmpty()) {
                addWarning(warnings, "Choose a location for \"" + label + "\" before saving");
            }
            return params;
        }
        try {
            JSONObject resolved = new JSONObject(params.toString());
            resolved.put("locationName", place.name);
            resolved.put("latitude", place.latitude);
            resolved.put("longitude", place.longitude);
            if (!resolved.has("radiusMeters")) resolved.put("radiusMeters", CONDITION_RADIUS_METERS);
            return resolved;
        } catch (Exception ignored) {
            return params;
        }
    }

    /** Matches the default a hand-built location condition gets in the editor. */
    private static final int CONDITION_RADIUS_METERS = 150;

    static boolean hasBranch(List<AssistantReply.Action> actions) {
        if (actions == null) return false;
        for (AssistantReply.Action action : actions) if (RoutineBranch.hasElse(action)) return true;
        return false;
    }

    /**
     * Drops any ELSE declaration Orbit cannot reproduce exactly.
     *
     * <p>An ELSE path is positional: it is the steps immediately after the IF path. If one of the
     * planned steps failed validation and was left out, the steps that follow the condition are no
     * longer the ones the planner counted, and rebuilding the branch from the shifted list would
     * quietly move an action from one path to the other. Orbit reports that instead, and the draft
     * still opens in the editor where the branch can be set by hand.
     *
     * @param plannedAt the planner index each accepted step came from, parallel to {@code actions}
     */
    private static void reconcileBranches(List<AssistantReply.Action> actions,
                                          List<Integer> plannedAt, List<String> warnings) {
        if (actions == null || plannedAt == null || actions.size() != plannedAt.size()) return;
        boolean stripped = false;
        for (int k = 0; k < actions.size(); k++) {
            AssistantReply.Action condition = actions.get(k);
            int branchSteps = RoutineBranch.elseSteps(condition);
            if (branchSteps <= 0) continue;
            int span = RoutineBranch.trueSteps(condition) + branchSteps;
            boolean intact = true;
            for (int n = 1; n <= span; n++) {
                if (k + n >= actions.size()
                        || plannedAt.get(k + n) != plannedAt.get(k) + n) {
                    intact = false;
                    break;
                }
            }
            if (intact) continue;
            actions.set(k, withoutElse(condition));
            stripped = true;
        }
        // Last line of defence: anything still malformed, such as nesting the planner was told not
        // to produce, loses its branching rather than reaching the editor as an unsaveable routine.
        if (!RoutineBranch.structureValid(actions)) {
            for (int k = 0; k < actions.size(); k++) {
                if (RoutineBranch.hasElse(actions.get(k))) {
                    actions.set(k, withoutElse(actions.get(k)));
                    stripped = true;
                }
            }
        }
        if (stripped) {
            addWarning(warnings, "Orbit couldn't build the \"otherwise\" branch from that plan. "
                    + "Add it in the IF step before saving.");
        }
    }

    private static AssistantReply.Action withoutElse(AssistantReply.Action condition) {
        AssistantReply.Action copy = RoutineActionCatalog.copy(condition);
        if (copy != null && copy.params != null) copy.params.remove(RoutineBranch.KEY_ELSE_STEPS);
        return copy;
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
            JSONObject root = new JSONObject()
                    .put(KEY_SCHEMA, SCHEMA)
                    .put(KEY_NAME, name)
                    .put(KEY_STEPS, steps)
                    .put(KEY_WARNINGS, warned);
            if (trigger != null) root.put(KEY_TRIGGER, trigger.toJson());
            return root.toString();
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
            List<Integer> plannedAt = new ArrayList<>();
            JSONArray steps = root.optJSONArray(KEY_STEPS);
            if (steps == null) return null;
            for (int i = 0; i < steps.length() && actions.size() < RoutineActionCatalog.MAX_STEPS; i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                AssistantReply.Action action = new AssistantReply.Action(
                        step.optString(KEY_TYPE, ""), step.optJSONObject(KEY_PARAMS), false);
                if (!RoutineActionCatalog.isValid(action) || !isAllowedType(context, action)) continue;
                actions.add(action);
                plannedAt.add(i);
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
            // The editor trusts nothing that was handed to it, branch geometry included.
            reconcileBranches(actions, plannedAt, warnings);
            RoutineTriggerDraft trigger =
                    RoutineTriggerDraft.fromPayload(context, root.optJSONObject(KEY_TRIGGER));
            return new RoutineDraft(root.optString(KEY_NAME, ""), actions, warnings, trigger);
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
