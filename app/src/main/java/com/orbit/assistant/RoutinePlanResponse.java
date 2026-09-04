package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Reads the raw planner response into the plan object Orbit expects.
 *
 * <p>This layer exists because a provider response is not guaranteed to be the bare object the
 * planning prompt asked for. It may be fenced, wrapped in a sentence, or delivered inside Orbit's
 * ordinary chat envelope ({@code {"text":…,"actions":[…]}}) when the transport applies its own
 * response format. All of those carry the same intent, so they are unwrapped here.
 *
 * <p>Normalisation is deliberately narrow and deterministic: known equivalent spellings of a
 * supported action type, known equivalent parameter names, and unambiguous value forms such as
 * {@code "30%"}. It never widens what Orbit can do. Every normalised step is still handed to
 * {@link RoutineActionCatalog#isValid} afterwards, so an unrecognised type simply stays
 * unrecognised and is reported rather than executed.
 */
public final class RoutinePlanResponse {
    /** Description of what arrived, for diagnostics only. */
    public final String shape;
    /** The normalised plan object, or null when no plan could be read. */
    public final JSONObject plan;

    private RoutinePlanResponse(String shape, JSONObject plan) {
        this.shape = shape;
        this.plan = plan;
    }

    public boolean hasPlan() {
        return plan != null;
    }

    /** True when a plan object was found and it actually carried a steps array. */
    public boolean hasStepsArray() {
        return plan != null && plan.optJSONArray("steps") != null;
    }

    public static RoutinePlanResponse read(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new RoutinePlanResponse("empty", null);

        StringBuilder shape = new StringBuilder();
        String text = raw.trim();
        String stripped = stripFence(text);
        if (!stripped.equals(text)) shape.append("fenced+");
        text = stripped;

        String candidate = isolate(text);
        if (candidate.isEmpty()) return new RoutinePlanResponse(shape + "no JSON object", null);
        if (candidate.length() < text.length()) shape.append("prose+");

        JSONObject object;
        try {
            object = new JSONObject(candidate);
        } catch (Exception ignored) {
            return new RoutinePlanResponse(shape + "unparsable JSON", null);
        }

        // Orbit's chat envelope. The plan is normally inside the text field; when the transport
        // produced ordinary chat actions instead, those are the same {type, params} shape a step
        // uses, so they are carried across and validated like any other step.
        if (!object.has("steps") && (object.has("text") || object.has("actions"))) {
            JSONObject unwrapped = unwrapEnvelope(object);
            if (unwrapped == null) return new RoutinePlanResponse(shape + "chat envelope, no plan", null);
            return new RoutinePlanResponse(shape + "chat envelope", normalize(unwrapped));
        }
        return new RoutinePlanResponse(shape + "plan object", normalize(object));
    }

    private static JSONObject unwrapEnvelope(JSONObject envelope) {
        String inner = envelope.optString("text", "");
        String nested = isolate(stripFence(inner.trim()));
        if (!nested.isEmpty()) {
            try {
                JSONObject plan = new JSONObject(nested);
                if (plan.optJSONArray("steps") != null) return plan;
            } catch (Exception ignored) {}
        }
        JSONArray actions = envelope.optJSONArray("actions");
        if (actions == null || actions.length() == 0) return null;
        try {
            return new JSONObject().put("steps", actions);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Removes a leading/trailing Markdown code fence, keeping the content between them. */
    static String stripFence(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("```")) return text;
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) return text;
        return text.substring(firstNewline + 1, lastFence).trim();
    }

    /**
     * The first complete JSON object in the text. Brace counting is string-aware, so prose after
     * the object, or a brace inside a quoted value, cannot truncate or extend the result the way
     * a first-brace-to-last-brace scan can.
     */
    static String isolate(String value) {
        String text = value == null ? "" : value;
        for (int start = text.indexOf('{'); start >= 0; start = text.indexOf('{', start + 1)) {
            int depth = 0;
            boolean inString = false;
            boolean escape = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (escape) escape = false;
                    else if (c == '\\') escape = true;
                    else if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return text.substring(start, i + 1);
            }
            // Unterminated from here (a truncated response); no later start can close either.
            return "";
        }
        return "";
    }

    /** Canonicalises the plan in place-safe fashion, returning a new object. */
    static JSONObject normalize(JSONObject root) {
        JSONObject out = new JSONObject();
        try {
            copyIfPresent(root, out, "name", "routineName", "title");
            copyIfPresent(root, out, "unsupported", "unsupportedRequests");
            copyIfPresent(root, out, "trigger", "automaticTrigger");
            copyIfPresent(root, out, "additionalTriggers", "extraTriggers");
            if (root.has("elseRequested")) out.put("elseRequested", asBoolean(root.opt("elseRequested"), false));

            JSONArray steps = root.optJSONArray("steps");
            if (steps == null) steps = root.optJSONArray("actions");
            if (steps == null) return out;

            JSONArray normalized = new JSONArray();
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                JSONObject fixed = normalizeStep(step);
                if (fixed != null) normalized.put(fixed);
            }
            out.put("steps", normalized);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject normalizeStep(JSONObject step) {
        try {
            String type = canonicalType(firstString(step, "type", "action", "actionType", "name"));
            JSONObject params = step.optJSONObject("params");
            if (params == null) params = step.optJSONObject("parameters");
            if (params == null) params = inlineParams(step);

            JSONObject out = new JSONObject().put("type", type)
                    .put("params", canonicalParams(type, params));
            String describe = step.optString("describe", "");
            if (!describe.isEmpty()) out.put("describe", describe);
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** A step that carried its values directly rather than in a params object. */
    private static JSONObject inlineParams(JSONObject step) {
        JSONObject params = new JSONObject();
        java.util.Iterator<String> keys = step.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("type".equals(key) || "action".equals(key) || "actionType".equals(key)
                    || "describe".equals(key) || "name".equals(key)) continue;
            try { params.put(key, step.opt(key)); } catch (Exception ignored) {}
        }
        return params;
    }

    /**
     * Known equivalent spellings only. Casing, spaces, and hyphens are canonicalised, and a short
     * explicit alias table covers the names providers actually use for Orbit's own actions. There
     * is no fuzzy matching: anything not listed keeps its name and is rejected by the catalog.
     */
    static String canonicalType(String value) {
        String type = (value == null ? "" : value.trim())
                .toUpperCase(Locale.US).replace(' ', '_').replace('-', '_');
        while (type.contains("__")) type = type.replace("__", "_");
        switch (type) {
            case "DND":
            case "DO_NOT_DISTURB":
            case "SET_DO_NOT_DISTURB":
            case "TOGGLE_DND":
                return RoutineActionCatalog.SET_DND;
            case "BRIGHTNESS":
            case "SCREEN_BRIGHTNESS":
            case "SET_SCREEN_BRIGHTNESS":
                return RoutineActionCatalog.SET_BRIGHTNESS;
            case "VOLUME":
            case "MEDIA_VOLUME":
            case "SET_MEDIA_VOLUME":
                return RoutineActionCatalog.SET_VOLUME;
            case "TORCH":
            case "SET_FLASHLIGHT":
            case "TOGGLE_FLASHLIGHT":
                return RoutineActionCatalog.FLASHLIGHT;
            case "TIMER":
            case "START_TIMER":
                return RoutineActionCatalog.SET_TIMER;
            case "ALARM":
            case "SET_ALARM_CLOCK":
                return RoutineActionCatalog.SET_ALARM;
            case "LAUNCH_APP":
            case "OPEN_APPLICATION":
                return RoutineActionCatalog.OPEN_APP;
            case "IF":
            case "CONDITION":
                return RoutineActionCatalog.IF_CONDITION;
            default:
                return type;
        }
    }

    /** Parameter names and value forms that mean exactly one thing for the given action. */
    private static JSONObject canonicalParams(String type, JSONObject params) {
        JSONObject in = params == null ? new JSONObject() : params;
        JSONObject out = new JSONObject();
        try {
            switch (type) {
                case RoutineActionCatalog.SET_BRIGHTNESS:
                case RoutineActionCatalog.SET_VOLUME: {
                    int percent = asPercent(first(in, "percent", "brightness", "volume", "level",
                            "value", "percentage", "amount"));
                    if (percent >= 0) out.put("percent", percent);
                    return out;
                }
                case RoutineActionCatalog.SET_DND: {
                    out.put("enabled", asBoolean(first(in, "enabled", "on", "state", "value",
                            "dnd", "active"), true));
                    return out;
                }
                case RoutineActionCatalog.FLASHLIGHT: {
                    out.put("on", asBoolean(first(in, "on", "enabled", "state", "value"), true));
                    return out;
                }
                case RoutineActionCatalog.SET_TIMER: {
                    // An explicit number is taken as stated. Only when neither field holds one is
                    // the text read as a duration, so "4 minutes 30 seconds" in a planned routine
                    // reaches the same arithmetic every other Orbit timer goes through.
                    int seconds = asInt(first(in, "seconds", "duration", "durationSeconds"), -1);
                    if (seconds < 0) {
                        int minutes = asInt(first(in, "minutes", "durationMinutes"), -1);
                        if (minutes > 0) seconds = minutes * 60;
                    }
                    if (seconds < 0) {
                        long parsed = DurationParser.parseSeconds(
                                asString(first(in, "seconds", "duration", "durationSeconds",
                                        "minutes", "durationMinutes")));
                        if (parsed > 0L) seconds = (int) parsed;
                    }
                    if (seconds >= 0) out.put("seconds", seconds);
                    copyString(in, out, "label", "name", "title");
                    return out;
                }
                case RoutineActionCatalog.SET_ALARM: {
                    int hour = asInt(first(in, "hour", "hours"), -1);
                    int minute = asInt(first(in, "minute", "minutes"), -1);
                    String clock = asString(first(in, "time", "at"));
                    if ((hour < 0 || minute < 0) && clock.matches("\\d{1,2}:\\d{2}")) {
                        String[] parts = clock.split(":");
                        hour = asInt(parts[0], -1);
                        minute = asInt(parts[1], -1);
                    }
                    if (hour >= 0) out.put("hour", hour);
                    if (minute >= 0) out.put("minute", minute);
                    copyString(in, out, "label", "name", "title");
                    return out;
                }
                case RoutineActionCatalog.OPEN_APP: {
                    String app = asString(first(in, "app", "appName", "application", "name"));
                    if (!app.isEmpty()) out.put("app", app);
                    return out;
                }
                case RoutineActionCatalog.IF_CONDITION: {
                    copyString(in, out, "mode");
                    int next = asInt(first(in, "nextSteps", "steps", "count"), -1);
                    if (next >= 0) out.put("nextSteps", next);
                    // Written only when a branch was actually asked for, so a condition without an
                    // ELSE normalises to exactly the params it did before v0.7.5.0.
                    int otherwise = asInt(first(in, "elseSteps", "otherwiseSteps", "elseCount"), -1);
                    if (otherwise > 0) out.put(RoutineBranch.KEY_ELSE_STEPS, otherwise);
                    int start = asInt(first(in, "startMinute", "fromMinute"), -1);
                    int end = asInt(first(in, "endMinute", "toMinute"), -1);
                    if (start >= 0) out.put("startMinute", start);
                    if (end >= 0) out.put("endMinute", end);
                    String place = asString(first(in, "locationName", "place", "location", "placeName"));
                    if (!place.isEmpty()) out.put("locationName", place);
                    return out;
                }
                default:
                    // Extension actions and parameterless actions are passed through untouched:
                    // their identifiers must match exactly and are validated by the catalog.
                    return new JSONObject(in.toString());
            }
        } catch (Exception ignored) {
            return out;
        }
    }

    private static Object first(JSONObject source, String... keys) {
        for (String key : keys) {
            Object value = source.opt(key);
            if (value != null && value != JSONObject.NULL) return value;
        }
        return null;
    }

    private static String firstString(JSONObject source, String... keys) {
        return asString(first(source, keys));
    }

    private static void copyIfPresent(JSONObject source, JSONObject target, String canonical,
                                      String... aliases) throws Exception {
        Object value = source.opt(canonical);
        if (value == null || value == JSONObject.NULL) {
            for (String alias : aliases) {
                Object candidate = source.opt(alias);
                if (candidate != null && candidate != JSONObject.NULL) { value = candidate; break; }
            }
        }
        if (value != null && value != JSONObject.NULL) target.put(canonical, value);
    }

    private static void copyString(JSONObject source, JSONObject target, String canonical,
                                   String... aliases) throws Exception {
        String value = asString(first(source, merge(canonical, aliases)));
        if (!value.isEmpty()) target.put(canonical, value);
    }

    private static String[] merge(String first, String[] rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private static String asString(Object value) {
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
    }

    /** Accepts 30, "30", "30%", and an unambiguous 0-1 fraction. Anything else stays rejected. */
    static int asPercent(Object value) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number > 0d && number < 1d) return (int) Math.round(number * 100d);
            return (int) Math.round(number);
        }
        String text = asString(value).replace("%", "").trim();
        if (text.isEmpty()) return -1;
        try {
            double number = Double.parseDouble(text);
            if (number > 0d && number < 1d) return (int) Math.round(number * 100d);
            return (int) Math.round(number);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        String text = asString(value);
        if (text.isEmpty()) return fallback;
        try { return (int) Math.round(Double.parseDouble(text)); }
        catch (Exception ignored) { return fallback; }
    }

    /** Only unambiguous boolean spellings; anything else keeps the action's own default. */
    static boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0d;
        String text = asString(value).toLowerCase(Locale.US);
        switch (text) {
            case "true": case "on": case "yes": case "enable": case "enabled": case "1":
                return true;
            case "false": case "off": case "no": case "disable": case "disabled": case "0":
                return false;
            default:
                return fallback;
        }
    }
}
