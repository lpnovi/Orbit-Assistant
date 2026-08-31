package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The security boundary between the on-device action model and Orbit's action layer.
 *
 * <p>A local model is a text generator. What it produces is <em>untrusted input</em> in exactly the
 * same sense as a web page or a screenshot, and the fact that it runs on the user's own phone
 * changes nothing about that: a small model asked for JSON will sometimes produce prose, sometimes
 * produce the wrong field, and — given a prompt-injected screen or a strange sentence — sometimes
 * produce something nobody wanted. So nothing it writes is ever executed.
 *
 * <p>What actually happens is a translation. This class reads the model's output, checks that it
 * names one action from a small fixed allowlist, checks each parameter against a typed range, and
 * then <b>builds a fresh parameter object of its own</b> from those checked values. The object the
 * executor receives was written by Orbit, field by field, and can contain nothing else — so an
 * Intent action, a component name, a package, a URL, a file path, a class name or a shell string
 * has no route through here even in principle.
 *
 * <p>Three further rules make the boundary auditable rather than merely tight:
 *
 * <ul>
 *   <li><b>One action per turn.</b> Beta 1 allows a single action. An output carrying several is
 *       rejected outright rather than partly obeyed.</li>
 *   <li><b>Dangerous keys are a rejection, not something to ignore.</b> Silently dropping an
 *       {@code intent} field would let a model keep trying; seeing one means the whole output is
 *       distrusted.</li>
 *   <li><b>Out of range is a rejection, not a clamp.</b> A brightness of 200 is not a request for
 *       100%, it is evidence the model did not understand, and acting on it would be a guess.</li>
 * </ul>
 *
 * <p>Deliberately free of Android: an app name is resolved through {@link AppResolver}, so the
 * whole boundary can be exercised exhaustively in ordinary tests.
 */
public final class LocalActionSchema {

    /**
     * The initial Beta 1 allowlist: reversible, everyday, and already proven in Orbit.
     *
     * <p>Nothing that sends a message, places a call, opens a link, writes to a calendar, changes a
     * permission, or touches storage is here, and nothing will be added to it without the same
     * device validation this set is getting.
     */
    public static final Set<String> ALLOWED_ACTIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "FLASHLIGHT",
                    "SET_BRIGHTNESS",
                    "SET_VOLUME",
                    "SET_DND",
                    "SET_RINGER_MODE",
                    "MEDIA_CONTROL",
                    "SET_TIMER",
                    "SET_ALARM",
                    "OPEN_APP",
                    "OPEN_SETTINGS")));

    /**
     * Field names that have no business in a local action.
     *
     * <p>Their presence is treated as evidence rather than noise: none of them can reach the
     * executor whatever happens, so this exists to make a model that reaches for them visible in
     * Diagnostics instead of quietly ignored.
     */
    static final Set<String> FORBIDDEN_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // Stored already normalized: lower case, with separators removed, so
                    // "component_name", "componentName" and "component-name" are one entry.
                    "intent", "actionintent", "component", "componentname", "class",
                    "classname", "package", "packagename", "uri", "url", "link", "data",
                    "shell", "commandline", "cmd", "exec", "path", "file", "filepath",
                    "sql", "query", "number", "phone", "body", "message", "to", "recipient",
                    "flags", "extras")));

    /** The longest label Orbit will carry into the Clock app from a local model. */
    static final int MAX_LABEL = 40;
    /** The longest app name Orbit will even attempt to resolve. */
    static final int MAX_APP_NAME = 40;
    /** A day. Beyond that a "timer" is something the user meant as an alarm. */
    static final int MAX_TIMER_SECONDS = 24 * 60 * 60;
    /** The most raw model output Orbit will look at. A local action is a short JSON object. */
    static final int MAX_OUTPUT_CHARS = 2000;

    /** Resolves a spoken app name to an installed app's own label, or null when there is none. */
    public interface AppResolver {
        String resolve(String spokenName);
    }

    /** The outcome of validating one piece of model output. */
    public static final class Validation {
        /** The action Orbit built, or null when nothing survived validation. */
        public final AssistantReply.Action action;
        /** A short non-sensitive token naming why it was refused, or "" when it was accepted. */
        public final String rejection;
        /** The action category, for Diagnostics. Never a parameter value. */
        public final String category;

        private Validation(AssistantReply.Action action, String rejection, String category) {
            this.action = action;
            this.rejection = rejection == null ? "" : rejection;
            this.category = category == null ? "" : category;
        }

        public boolean accepted() { return action != null; }

        static Validation accept(AssistantReply.Action action, String category) {
            return new Validation(action, "", category);
        }

        static Validation reject(String reason) {
            return new Validation(null, reason, "");
        }
    }

    // ---- rejection reasons, as tokens safe to show in Diagnostics ---------------------------------

    public static final String REJECT_EMPTY = "empty";
    public static final String REJECT_TOO_LONG = "too-long";
    public static final String REJECT_NOT_JSON = "not-json";
    public static final String REJECT_NO_ACTION = "no-action";
    public static final String REJECT_UNKNOWN_ACTION = "unknown-action";
    public static final String REJECT_MULTIPLE_ACTIONS = "multiple-actions";
    public static final String REJECT_FORBIDDEN_FIELD = "forbidden-field";
    public static final String REJECT_BAD_PARAMS = "bad-params";
    public static final String REJECT_OUT_OF_RANGE = "out-of-range";
    public static final String REJECT_UNKNOWN_APP = "unknown-app";

    private LocalActionSchema() {}

    // ---- validation --------------------------------------------------------------------------------

    /** Validates one piece of raw model output. Never throws, and never executes anything. */
    public static Validation validate(String rawOutput, AppResolver apps) {
        if (rawOutput == null || rawOutput.trim().isEmpty()) return Validation.reject(REJECT_EMPTY);
        if (rawOutput.length() > MAX_OUTPUT_CHARS) return Validation.reject(REJECT_TOO_LONG);

        String json = firstJsonObject(rawOutput);
        if (json.isEmpty()) return Validation.reject(REJECT_NOT_JSON);

        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            return Validation.reject(REJECT_NOT_JSON);
        }

        // A model that answered with a list has not answered the question Beta 1 asked it.
        JSONArray many = root.optJSONArray("actions");
        if (many != null && many.length() != 1) return Validation.reject(REJECT_MULTIPLE_ACTIONS);
        if (many != null) {
            JSONObject only = many.optJSONObject(0);
            if (only == null) return Validation.reject(REJECT_NOT_JSON);
            root = only;
        }

        String type = root.optString("action", root.optString("type", "")).trim()
                .toUpperCase(Locale.US);
        if (type.isEmpty()) return Validation.reject(REJECT_NO_ACTION);
        if (!ALLOWED_ACTIONS.contains(type)) return Validation.reject(REJECT_UNKNOWN_ACTION);

        JSONObject params = root.optJSONObject("params");
        if (params == null) params = root.optJSONObject("parameters");
        if (params == null) params = new JSONObject();
        if (hasForbiddenKey(root) || hasForbiddenKey(params)) {
            return Validation.reject(REJECT_FORBIDDEN_FIELD);
        }
        return build(type, params, apps);
    }

    /**
     * Builds the action Orbit will run, from checked values only.
     *
     * <p>Every branch constructs a brand new {@link JSONObject}. The model's own object is read and
     * discarded; it is never forwarded, merged, or copied, which is what makes "the executor cannot
     * receive an unexpected field" a structural property rather than a promise.
     */
    private static Validation build(String type, JSONObject params, AppResolver apps) {
        try {
            switch (type) {
                case "FLASHLIGHT": {
                    Boolean on = readBoolean(params, "on", "enabled", "state");
                    if (on == null) return Validation.reject(REJECT_BAD_PARAMS);
                    return accept(type, new JSONObject().put("on", on.booleanValue()), "flashlight");
                }
                case "SET_BRIGHTNESS": {
                    Integer percent = readInt(params, "percent", "level", "value");
                    if (percent == null) return Validation.reject(REJECT_BAD_PARAMS);
                    if (percent < 0 || percent > 100) return Validation.reject(REJECT_OUT_OF_RANGE);
                    return accept(type, new JSONObject().put("percent", percent.intValue()), "brightness");
                }
                case "SET_VOLUME": {
                    Integer percent = readInt(params, "percent", "level", "value");
                    if (percent == null) return Validation.reject(REJECT_BAD_PARAMS);
                    if (percent < 0 || percent > 100) return Validation.reject(REJECT_OUT_OF_RANGE);
                    return accept(type, new JSONObject().put("percent", percent.intValue()), "volume");
                }
                case "SET_DND": {
                    Boolean enabled = readBoolean(params, "enabled", "on", "state");
                    if (enabled == null) return Validation.reject(REJECT_BAD_PARAMS);
                    return accept(type, new JSONObject().put("enabled", enabled.booleanValue()), "dnd");
                }
                case "SET_RINGER_MODE": {
                    String mode = params.optString("mode", "").trim().toLowerCase(Locale.US);
                    if (!"normal".equals(mode) && !"vibrate".equals(mode) && !"silent".equals(mode)) {
                        return Validation.reject(REJECT_BAD_PARAMS);
                    }
                    return accept(type, new JSONObject().put("mode", mode), "ringer");
                }
                case "MEDIA_CONTROL": {
                    MediaControl.Command command = MediaControl.parse(params.optString("command", ""));
                    if (command == null) return Validation.reject(REJECT_BAD_PARAMS);
                    return accept(type, new JSONObject().put("command", command.name()), "media");
                }
                case "SET_TIMER": {
                    Integer seconds = readInt(params, "seconds", "duration", "length");
                    if (seconds == null) {
                        Integer minutes = readInt(params, "minutes");
                        if (minutes == null) return Validation.reject(REJECT_BAD_PARAMS);
                        if (minutes > MAX_TIMER_SECONDS / 60) return Validation.reject(REJECT_OUT_OF_RANGE);
                        seconds = minutes * 60;
                    }
                    if (seconds <= 0 || seconds > MAX_TIMER_SECONDS) {
                        return Validation.reject(REJECT_OUT_OF_RANGE);
                    }
                    String label = safeLabel(params.optString("label", ""), "Orbit timer");
                    return accept(type, new JSONObject()
                            .put("seconds", seconds.intValue()).put("label", label), "timer");
                }
                case "SET_ALARM": {
                    Integer hour = readInt(params, "hour", "hours");
                    Integer minute = readInt(params, "minute", "minutes");
                    if (hour == null) return Validation.reject(REJECT_BAD_PARAMS);
                    if (minute == null) minute = 0;
                    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                        return Validation.reject(REJECT_OUT_OF_RANGE);
                    }
                    String label = safeLabel(params.optString("label", ""), "Orbit alarm");
                    return accept(type, new JSONObject()
                            .put("hour", hour.intValue()).put("minute", minute.intValue())
                            .put("label", label), "alarm");
                }
                case "OPEN_APP": {
                    String wanted = params.optString("app", params.optString("name", "")).trim();
                    if (wanted.isEmpty() || wanted.length() > MAX_APP_NAME) {
                        return Validation.reject(REJECT_BAD_PARAMS);
                    }
                    // The resolver is the gate. A name that does not correspond to an app already
                    // installed on this phone never reaches the executor, so a generated string
                    // cannot become a package to launch.
                    String resolved = apps == null ? null : apps.resolve(wanted);
                    if (resolved == null || resolved.trim().isEmpty()) {
                        return Validation.reject(REJECT_UNKNOWN_APP);
                    }
                    return accept(type, new JSONObject().put("app", resolved.trim()), "app");
                }
                case "OPEN_SETTINGS":
                    return accept(type, new JSONObject(), "settings");
                default:
                    return Validation.reject(REJECT_UNKNOWN_ACTION);
            }
        } catch (Exception e) {
            return Validation.reject(REJECT_BAD_PARAMS);
        }
    }

    private static Validation accept(String type, JSONObject params, String category) {
        return Validation.accept(new AssistantReply.Action(type, params, false), category);
    }

    // ---- typed reading ---------------------------------------------------------------------------

    /**
     * A boolean, however the model wrote it.
     *
     * <p>Small models answer {@code true}, {@code "true"}, {@code "on"}, and {@code 1} more or less
     * interchangeably. All four are the same intent and are read as such; anything else is not a
     * boolean and produces null rather than a default.
     */
    static Boolean readBoolean(JSONObject params, String... names) {
        for (String name : names) {
            if (!params.has(name)) continue;
            Object value = params.opt(name);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) {
                int number = ((Number) value).intValue();
                if (number == 0) return Boolean.FALSE;
                if (number == 1) return Boolean.TRUE;
                return null;
            }
            if (value instanceof String) {
                String text = ((String) value).trim().toLowerCase(Locale.US);
                if ("true".equals(text) || "on".equals(text) || "yes".equals(text)
                        || "enable".equals(text) || "enabled".equals(text)) return Boolean.TRUE;
                if ("false".equals(text) || "off".equals(text) || "no".equals(text)
                        || "disable".equals(text) || "disabled".equals(text)) return Boolean.FALSE;
            }
            return null;
        }
        return null;
    }

    /** An integer, or null. A decimal, a range, or a word is not an integer and is refused. */
    static Integer readInt(JSONObject params, String... names) {
        for (String name : names) {
            if (!params.has(name)) continue;
            Object value = params.opt(name);
            if (value instanceof Integer) return (Integer) value;
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (number != Math.rint(number)) return null;
                if (number > Integer.MAX_VALUE || number < Integer.MIN_VALUE) return null;
                return (int) number;
            }
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (!text.matches("-?\\d{1,9}")) return null;
                try { return Integer.valueOf(text); } catch (NumberFormatException e) { return null; }
            }
            return null;
        }
        return null;
    }

    /**
     * A label the Clock app can show, or the Orbit default.
     *
     * <p>Trimmed to length and stripped of anything that is not ordinary text, because this is the
     * one place a generated string is carried through to another app at all.
     */
    static String safeLabel(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceAll("[^\\p{L}\\p{N} '\\-&.,]", "").trim();
        if (value.isEmpty()) return fallback;
        return value.length() <= MAX_LABEL ? value : value.substring(0, MAX_LABEL).trim();
    }

    // ---- the untrusted text itself ----------------------------------------------------------------

    /** Whether any key in an object is one Orbit refuses to see, case- and separator-insensitive. */
    static boolean hasForbiddenKey(JSONObject object) {
        if (object == null) return false;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null) continue;
            String normalized = key.toLowerCase(Locale.US).replace("_", "").replace("-", "");
            if (FORBIDDEN_KEYS.contains(normalized) || FORBIDDEN_KEYS.contains(key.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first balanced JSON object in a piece of model output, or "".
     *
     * <p>Small instruct models routinely wrap their answer in a sentence or a fenced code block.
     * Reaching for the object rather than demanding the whole response be JSON is the difference
     * between a usable path and one that rejects most correct answers — and it costs nothing,
     * because what is found is then validated exactly as strictly either way.
     */
    static String firstJsonObject(String raw) {
        if (raw == null) return "";
        int start = raw.indexOf('{');
        if (start < 0) return "";
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(start, i + 1);
            }
        }
        return "";
    }
}
