package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>Further rules make the boundary auditable rather than merely tight:
 *
 * <ul>
 *   <li><b>All or nothing.</b> {@link #validate} accepts exactly one action. {@link #validatePlan}
 *       (v0.8.2.0) accepts up to {@link #MAX_PLAN_ACTIONS}, each checked identically, and rejects the
 *       whole plan if any step fails, so nothing is ever partly obeyed.</li>
 *   <li><b>Unknown fields are a rejection too.</b> Since v0.8.2.0 an action may carry only the
 *       fields it reads.</li>
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
    /** A field that is neither forbidden nor one this action reads. Since v0.8.2.0. */
    public static final String REJECT_UNKNOWN_FIELD = "unknown-field";
    /** A multi-action plan that breaks a plan rule. Since v0.8.2.0. */
    public static final String REJECT_BAD_PLAN = "bad-plan";
    /** An action the user's own words do not ask for. Since v0.8.2.0. */
    public static final String REJECT_UNREQUESTED = "unrequested-action";

    /**
     * The one follow-up the model may name: "undo what Orbit just did".
     *
     * <p>Not an action. It carries no parameters, is never executed as written, and means only
     * "consult {@link RecentActionContext}", which holds what Orbit itself recorded. The model
     * classifies the sentence; Orbit decides what, if anything, it refers to.
     */
    public static final String UNDO_LAST = "UNDO_LAST";

    /** The most actions one local request may carry. Small, because each one changes the phone. */
    public static final int MAX_PLAN_ACTIONS = 3;

    /**
     * Every field each action may carry, aliases included. Anything else rejects the whole output.
     */
    static final java.util.Map<String, Set<String>> ALLOWED_PARAMS;
    static {
        java.util.Map<String, Set<String>> m = new java.util.HashMap<>();
        m.put("FLASHLIGHT", keys("on", "enabled", "state"));
        m.put("SET_BRIGHTNESS", keys("percent", "level", "value"));
        m.put("SET_VOLUME", keys("percent", "level", "value"));
        m.put("SET_DND", keys("enabled", "on", "state"));
        m.put("SET_RINGER_MODE", keys("mode"));
        m.put("MEDIA_CONTROL", keys("command"));
        m.put("SET_TIMER", keys("seconds", "duration", "length", "minutes", "label"));
        m.put("SET_ALARM", keys("hour", "hours", "minute", "minutes", "label"));
        m.put("OPEN_APP", keys("app", "name"));
        m.put("OPEN_SETTINGS", keys("page"));
        m.put(UNDO_LAST, keys());
        ALLOWED_PARAMS = Collections.unmodifiableMap(m);
    }

    /** The only keys an action object itself may have. */
    static final Set<String> ALLOWED_ROOT_KEYS = keys("action", "type", "params", "parameters");

    private static Set<String> keys(String... names) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
    }

    /**
     * Settings destinations the model may name, mapped to actions Orbit already has.
     *
     * <p>Each is a fixed Android settings screen opened by Orbit's existing executor; the model
     * picks a word from this list and nothing it writes becomes part of an Intent.
     */
    static final java.util.Map<String, String> SETTINGS_PAGES;
    static {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("main", "OPEN_SETTINGS");
        m.put("settings", "OPEN_SETTINGS");
        m.put("internet", "OPEN_INTERNET_PANEL");
        m.put("wifi", "OPEN_INTERNET_PANEL");
        m.put("wi-fi", "OPEN_INTERNET_PANEL");
        m.put("mobile data", "OPEN_INTERNET_PANEL");
        m.put("bluetooth", "OPEN_BLUETOOTH_SETTINGS");
        SETTINGS_PAGES = Collections.unmodifiableMap(m);
    }

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
        if (!onlyKeys(root, ALLOWED_ROOT_KEYS) || !onlyKeys(params, ALLOWED_PARAMS.get(type))) {
            return Validation.reject(REJECT_UNKNOWN_FIELD);
        }
        return build(type, params, apps);
    }

    /** Whether every key of an object is one of {@code allowed}. */
    private static boolean onlyKeys(JSONObject object, Set<String> allowed) {
        if (object == null) return true;
        if (allowed == null) return false;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null || !allowed.contains(key.trim().toLowerCase(Locale.US))) return false;
        }
        return true;
    }

    /** One validated plan: the actions Orbit built, in the order they will run. */
    public static final class Plan {
        public final List<AssistantReply.Action> actions;
        /** True when the model asked to undo the last change; {@link #actions} is then empty. */
        public final boolean undoLast;
        public final String rejection;
        public final String category;

        private Plan(List<AssistantReply.Action> actions, boolean undoLast, String rejection,
                     String category) {
            this.actions = Collections.unmodifiableList(actions);
            this.undoLast = undoLast;
            this.rejection = rejection == null ? "" : rejection;
            this.category = category == null ? "" : category;
        }

        public boolean accepted() { return rejection.isEmpty(); }

        static Plan reject(String reason) {
            return new Plan(new ArrayList<>(), false, reason, "");
        }
    }

    /** Actions that take the user to another screen, so at most one may run and it must be last. */
    static final Set<String> FOREGROUND_ACTIONS = keys("OPEN_APP", "OPEN_SETTINGS",
            "OPEN_INTERNET_PANEL", "OPEN_BLUETOOTH_SETTINGS", "SET_ALARM");

    /**
     * Validates a whole response that may hold one action, several, or an undo request.
     *
     * <p>All or nothing. Every action passes exactly the checks {@link #validate} applies to one,
     * and then the plan as a whole must obey four rules: no more than {@link #MAX_PLAN_ACTIONS}, no
     * target twice, at most one action that leaves Orbit's screen and only as the last step, and -
     * for more than one action - each must be something the user's own words mention. One failure
     * anywhere rejects the whole plan, so an invalid step can never ride along with valid ones.
     */
    public static Plan validatePlan(String rawOutput, AppResolver apps, String userText) {
        if (rawOutput == null || rawOutput.trim().isEmpty()) return Plan.reject(REJECT_EMPTY);
        if (rawOutput.length() > MAX_OUTPUT_CHARS) return Plan.reject(REJECT_TOO_LONG);
        String json = firstJsonObject(rawOutput);
        if (json.isEmpty()) return Plan.reject(REJECT_NOT_JSON);
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            return Plan.reject(REJECT_NOT_JSON);
        }

        JSONArray many = root.optJSONArray("actions");
        List<JSONObject> steps = new ArrayList<>();
        if (many != null) {
            // The wrapper itself may hold nothing but the list.
            if (root.length() != 1) return Plan.reject(REJECT_UNKNOWN_FIELD);
            if (many.length() == 0) return Plan.reject(REJECT_NO_ACTION);
            if (many.length() > MAX_PLAN_ACTIONS) return Plan.reject(REJECT_BAD_PLAN);
            for (int i = 0; i < many.length(); i++) {
                JSONObject step = many.optJSONObject(i);
                if (step == null) return Plan.reject(REJECT_NOT_JSON);
                steps.add(step);
            }
        } else {
            steps.add(root);
        }

        // The undo request, alone or not at all.
        for (JSONObject step : steps) {
            String type = step.optString("action", step.optString("type", "")).trim()
                    .toUpperCase(Locale.US);
            if (!UNDO_LAST.equals(type)) continue;
            if (steps.size() != 1) return Plan.reject(REJECT_BAD_PLAN);
            JSONObject params = step.optJSONObject("params");
            if (params == null) params = step.optJSONObject("parameters");
            if (hasForbiddenKey(step) || hasForbiddenKey(params)) {
                return Plan.reject(REJECT_FORBIDDEN_FIELD);
            }
            if (!onlyKeys(step, ALLOWED_ROOT_KEYS) || (params != null && params.length() > 0)) {
                return Plan.reject(REJECT_UNKNOWN_FIELD);
            }
            return new Plan(new ArrayList<>(), true, "", "undo");
        }

        List<AssistantReply.Action> actions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        StringBuilder categories = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            Validation one = validate(steps.get(i).toString(), apps);
            if (!one.accepted()) return Plan.reject(one.rejection);
            String type = one.action.type;
            String target = FOREGROUND_ACTIONS.contains(type) ? "foreground" : type;
            if (!seen.add(target)) return Plan.reject(REJECT_BAD_PLAN);
            if (FOREGROUND_ACTIONS.contains(type) && i != steps.size() - 1) {
                return Plan.reject(REJECT_BAD_PLAN);
            }
            if (steps.size() > 1 && !mentions(userText, type)) {
                return Plan.reject(REJECT_UNREQUESTED);
            }
            actions.add(one.action);
            if (categories.length() > 0) categories.append('+');
            categories.append(one.category);
        }
        return new Plan(actions, false, "", categories.toString());
    }

    /**
     * Words that must appear in the user's request before a multi-action plan may include a type.
     *
     * <p>A small model asked to split "dim the screen and pause the music" can add a third action
     * nobody asked for. Requiring each action's subject in the user's own words means an extra step
     * the sentence never mentions rejects the whole plan instead of running.
     */
    private static final java.util.Map<String, java.util.regex.Pattern> EVIDENCE;
    static {
        java.util.Map<String, java.util.regex.Pattern> m = new java.util.HashMap<>();
        m.put("FLASHLIGHT", evidence("flashlight|torch|light"));
        m.put("SET_BRIGHTNESS", evidence("bright\\w*|dim\\w*|screen|display"));
        m.put("SET_VOLUME", evidence("volume|loud\\w*|quiet\\w*|sound|mute"));
        m.put("SET_DND", evidence("do not disturb|dnd|disturb|focus|notifications?"));
        m.put("SET_RINGER_MODE", evidence("ringer|ring|silent|silence|vibrat\\w*"));
        m.put("MEDIA_CONTROL", evidence("music|song|track|play\\w*|pause|resume|skip|podcast|next|previous"));
        m.put("SET_TIMER", evidence("timer|countdown|seconds?|minutes?|hours?"));
        m.put("SET_ALARM", evidence("alarm|wake"));
        m.put("OPEN_APP", evidence("open|launch|start|pull up|bring up|fire up|go to"));
        m.put("OPEN_SETTINGS", evidence("settings?"));
        m.put("OPEN_INTERNET_PANEL", evidence("wi-?fi|internet|mobile data|network"));
        m.put("OPEN_BLUETOOTH_SETTINGS", evidence("bluetooth"));
        EVIDENCE = Collections.unmodifiableMap(m);
    }

    private static java.util.regex.Pattern evidence(String words) {
        return java.util.regex.Pattern.compile("\\b(?:" + words + ")\\b");
    }

    static boolean mentions(String userText, String type) {
        if (userText == null) return false;
        java.util.regex.Pattern p = EVIDENCE.get(type);
        if (p == null) return false;
        return p.matcher(LanguageNormalizer.canonical(userText)).find();
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
                    // An explicit whole number of seconds is taken as stated and never reparsed:
                    // the model said what it meant, and second-guessing it would be its own bug.
                    Integer seconds = readInt(params, "seconds", "duration", "length");
                    if (seconds == null) {
                        Integer minutes = readInt(params, "minutes");
                        if (minutes != null && minutes > MAX_TIMER_SECONDS / 60) {
                            return Validation.reject(REJECT_OUT_OF_RANGE);
                        }
                        if (minutes != null) seconds = minutes * 60;
                    }
                    // Only once neither field holds a plain integer: a model that answered "4
                    // minutes 30 seconds" or 4.5 minutes stated a real duration, and rejecting it
                    // outright sent the user back to a provider that had already been right.
                    if (seconds == null) seconds = parsedDuration(params);
                    if (seconds == null) return Validation.reject(REJECT_BAD_PARAMS);
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
                case "OPEN_SETTINGS": {
                    // A page is a word from a fixed list that maps onto an action Orbit already
                    // has. No page means the main Settings screen; an unknown page is a rejection,
                    // not a guess at the nearest screen.
                    String page = params.optString("page", "").trim().toLowerCase(Locale.US);
                    if (page.isEmpty()) return accept(type, new JSONObject(), "settings");
                    String mapped = SETTINGS_PAGES.get(page);
                    if (mapped == null) return Validation.reject(REJECT_BAD_PARAMS);
                    return accept(mapped, new JSONObject(), "OPEN_SETTINGS".equals(mapped)
                            ? "settings" : "settings-page");
                }
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

    /**
     * A duration a provider wrote in words or as a fraction, in seconds, or null.
     *
     * <p>Read through {@link DurationParser}, so a provider phrase is subject to exactly the same
     * arithmetic and the same ceiling as one the user typed. A bare number with no unit is not a
     * duration here: "5" could be seconds, minutes or a page number, and guessing is how a five
     * second timer becomes five hours.
     */
    private static Integer parsedDuration(JSONObject params) {
        for (String name : new String[]{"seconds", "duration", "length", "minutes"}) {
            Object value = params.opt(name);
            if (!(value instanceof String)) continue;
            long parsed = DurationParser.parseSeconds((String) value);
            if (parsed > 0L && parsed <= MAX_TIMER_SECONDS) return (int) parsed;
        }
        // A fractional count of minutes is still a duration; it is only not an integer.
        Object minutes = params.opt("minutes");
        if (minutes instanceof Number) {
            double value = ((Number) minutes).doubleValue();
            if (!Double.isNaN(value) && !Double.isInfinite(value) && value > 0d
                    && value * 60d <= MAX_TIMER_SECONDS) {
                return (int) Math.max(1L, Math.round(value * 60d));
            }
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
