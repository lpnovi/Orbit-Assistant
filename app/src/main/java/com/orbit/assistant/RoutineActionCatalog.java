package com.orbit.assistant;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

/**
 * Small, deterministic action catalog for saved Orbit routines.
 *
 * Routines deliberately expose actions that are predictable when replayed later.
 * Communication/composer actions remain outside the routine builder until Orbit has
 * a dedicated safety/confirmation model for reusable outbound actions.
 */
public final class RoutineActionCatalog {
    public static final int MAX_STEPS = 20;

    public static final String IF_CONDITION = "IF_CONDITION";
    public static final String OPEN_APP = "OPEN_APP";
    public static final String SET_BRIGHTNESS = "SET_BRIGHTNESS";
    public static final String SET_DND = "SET_DND";
    public static final String SET_VOLUME = "SET_VOLUME";
    public static final String FLASHLIGHT = "FLASHLIGHT";
    public static final String SET_TIMER = "SET_TIMER";
    public static final String SET_ALARM = "SET_ALARM";
    public static final String OPEN_SETTINGS = "OPEN_SETTINGS";
    public static final String OPEN_INTERNET_PANEL = "OPEN_INTERNET_PANEL";
    public static final String OPEN_BLUETOOTH_SETTINGS = "OPEN_BLUETOOTH_SETTINGS";
    public static final String EXTENSION_ACTION = "EXTENSION_ACTION";

    public static final String[] TYPES = {
            IF_CONDITION,
            OPEN_APP,
            SET_BRIGHTNESS,
            SET_DND,
            SET_VOLUME,
            FLASHLIGHT,
            SET_TIMER,
            SET_ALARM,
            OPEN_SETTINGS,
            OPEN_INTERNET_PANEL,
            OPEN_BLUETOOTH_SETTINGS
    };

    public static final String[] LABELS = {
            "If condition",
            "Open app",
            "Brightness",
            "Do Not Disturb",
            "Media volume",
            "Flashlight",
            "Timer",
            "Alarm",
            "Open Settings",
            "Internet panel",
            "Bluetooth settings"
    };

    private RoutineActionCatalog() {}

    public static boolean isSupported(String type) {
        if (type == null) return false;
        if (EXTENSION_ACTION.equalsIgnoreCase(type)) return true;
        for (String candidate : TYPES) if (candidate.equalsIgnoreCase(type)) return true;
        return false;
    }

    public static boolean isConfigurable(String type) {
        if (type == null) return false;
        return !(OPEN_SETTINGS.equals(type) || OPEN_INTERNET_PANEL.equals(type) ||
                OPEN_BLUETOOTH_SETTINGS.equals(type));
    }

    public static String labelForType(String type) {
        if (type == null) return "Action";
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i].equalsIgnoreCase(type)) return LABELS[i];
        }
        return titleCase(type.replace('_', ' '));
    }

    public static String title(AssistantReply.Action action) {
        if (action == null) return "Action";
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        String type = action.type == null ? "" : action.type.toUpperCase(Locale.US);
        switch (type) {
            case IF_CONDITION: {
                String mode = RoutineConditionEvaluator.mode(action);
                String branch = RoutineBranch.hasElse(action) ? " · with ELSE" : "";
                if (RoutineConditionEvaluator.MODE_LOCATION.equals(mode)) {
                    String name = clean(p.optString("locationName", ""));
                    return "If location" + (name.isEmpty() ? "" : " · " + name) + branch;
                }
                String window = minuteLabel(p.optInt("startMinute", 0)) + "–" + minuteLabel(p.optInt("endMinute", 0));
                if (RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(mode)) {
                    String name = clean(p.optString("locationName", ""));
                    return "If time + location · " + window + (name.isEmpty() ? "" : " · " + name) + branch;
                }
                return "If time · " + window + branch;
            }
            case OPEN_APP:
                String app = clean(p.optString("app", p.optString("package", "")));
                return app.isEmpty() ? "Open app" : "Open " + app;
            case SET_BRIGHTNESS:
                return "Brightness · " + clampPercent(p.optInt("percent", 50)) + "%";
            case SET_DND:
                return "Do Not Disturb · " + (p.optBoolean("enabled", true) ? "On" : "Off");
            case SET_VOLUME:
                return "Media volume · " + clampPercent(p.optInt("percent", 50)) + "%";
            case FLASHLIGHT:
                return "Flashlight · " + (p.optBoolean("on", true) ? "On" : "Off");
            case SET_TIMER:
                return "Timer · " + durationLabel(Math.max(1, p.optInt("seconds", 60)));
            case SET_ALARM:
                return "Alarm · " + timeLabel(p.optInt("hour", 8), p.optInt("minute", 0));
            case OPEN_SETTINGS:
                return "Open Settings";
            case OPEN_INTERNET_PANEL:
                return "Open Internet panel";
            case OPEN_BLUETOOTH_SETTINGS:
                return "Open Bluetooth settings";
            case EXTENSION_ACTION: {
                String actionName = clean(p.optString("actionName", ""));
                return actionName.isEmpty() ? "Extension action" : actionName;
            }
            default:
                return labelForType(type);
        }
    }

    public static String summary(AssistantReply.Action action) {
        if (action == null) return "";
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        String type = action.type == null ? "" : action.type.toUpperCase(Locale.US);
        switch (type) {
            case IF_CONDITION: {
                int next = RoutineConditionEvaluator.gatedSteps(action);
                int otherwise = RoutineBranch.elseSteps(action);
                String target = next == 1 ? "the next step" : "the next " + next + " steps";
                if (otherwise > 0) {
                    // With an ELSE the interesting fact is that exactly one path runs, so the
                    // summary describes the split rather than repeating the condition mode.
                    String elseTarget = otherwise == 1
                            ? "the step after" : "the " + otherwise + " steps after";
                    return "Runs " + target + " when true, otherwise " + elseTarget
                            + ", then continues the routine";
                }
                String mode = RoutineConditionEvaluator.mode(action);
                if (RoutineConditionEvaluator.MODE_LOCATION.equals(mode)) {
                    int radius = Math.round((float) p.optDouble("radiusMeters", 200d));
                    return "Runs " + target + " only while inside the saved " + radiusLabel(radius) + " area";
                }
                if (RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(mode)) {
                    return "Runs " + target + " only when both the time and location match";
                }
                return "Runs " + target + " only during the saved time window";
            }
            case OPEN_APP:
                return "Launches the selected app";
            case SET_BRIGHTNESS:
                return "Sets screen brightness to " + clampPercent(p.optInt("percent", 50)) + "%";
            case SET_DND:
                return p.optBoolean("enabled", true) ? "Enables Do Not Disturb" : "Disables Do Not Disturb";
            case SET_VOLUME:
                return "Sets media volume to " + clampPercent(p.optInt("percent", 50)) + "%";
            case FLASHLIGHT:
                return p.optBoolean("on", true) ? "Turns the flashlight on" : "Turns the flashlight off";
            case SET_TIMER: {
                String label = clean(p.optString("label", ""));
                String base = "Starts a "
                        + durationModifierLabel(Math.max(1, p.optInt("seconds", 60))) + " timer";
                return label.isEmpty() || "Orbit timer".equals(label) ? base : base + " · " + label;
            }
            case SET_ALARM: {
                String label = clean(p.optString("label", ""));
                String base = "Sets an alarm for " + timeLabel(p.optInt("hour", 8), p.optInt("minute", 0));
                return label.isEmpty() || "Orbit alarm".equals(label) ? base : base + " · " + label;
            }
            case OPEN_SETTINGS:
                return "Opens Android Settings";
            case OPEN_INTERNET_PANEL:
                return "Opens Android's Internet controls";
            case OPEN_BLUETOOTH_SETTINGS:
                return "Opens Bluetooth settings";
            case EXTENSION_ACTION: {
                String extensionName = clean(p.optString("extensionName", ""));
                return extensionName.isEmpty()
                        ? "Runs a saved declarative extension action"
                        : "Extension · " + extensionName;
            }
            default:
                return "Saved Orbit action";
        }
    }

    public static AssistantReply.Action copy(AssistantReply.Action action) {
        if (action == null) return null;
        JSONObject params;
        try { params = new JSONObject(action.params == null ? "{}" : action.params.toString()); }
        catch (Exception ignored) { params = new JSONObject(); }
        return new AssistantReply.Action(action.type, params, action.requiresConfirmation);
    }

    public static boolean isValid(AssistantReply.Action action) {
        if (action == null || !isSupported(action.type)) return false;
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        String type = action.type.toUpperCase(Locale.US);
        switch (type) {
            case IF_CONDITION: {
                String mode = RoutineConditionEvaluator.mode(action);
                int next = p.optInt("nextSteps", 1);
                if (next < 1 || next > 5) return false;
                // Absent means no ELSE path, which is every condition written before v0.7.5.0.
                int otherwise = p.optInt(RoutineBranch.KEY_ELSE_STEPS, 0);
                if (otherwise < 0 || otherwise > RoutineBranch.MAX_BRANCH_STEPS) return false;
                if (RoutineConditionEvaluator.MODE_TIME.equals(mode) ||
                        RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(mode)) {
                    int start = p.optInt("startMinute", -1);
                    int end = p.optInt("endMinute", -1);
                    if (start < 0 || start > 1439 || end < 0 || end > 1439) return false;
                }
                if (RoutineConditionEvaluator.MODE_LOCATION.equals(mode) ||
                        RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(mode)) {
                    double lat = p.optDouble("latitude", Double.NaN);
                    double lon = p.optDouble("longitude", Double.NaN);
                    double radius = p.optDouble("radiusMeters", -1d);
                    if (Double.isNaN(lat) || lat < -90d || lat > 90d ||
                            Double.isNaN(lon) || lon < -180d || lon > 180d ||
                            radius < RoutineTriggerStore.MIN_LOCATION_RADIUS_METERS ||
                            radius > RoutineTriggerStore.MAX_LOCATION_RADIUS_METERS) return false;
                }
                return true;
            }
            case OPEN_APP:
                return !clean(p.optString("app", p.optString("package", ""))).isEmpty();
            case SET_BRIGHTNESS:
            case SET_VOLUME:
                return p.has("percent") && p.optInt("percent", -1) >= 0 && p.optInt("percent", 101) <= 100;
            case SET_TIMER:
                return p.optInt("seconds", 0) > 0;
            case SET_ALARM:
                return p.optInt("hour", -1) >= 0 && p.optInt("hour", 24) <= 23 &&
                        p.optInt("minute", -1) >= 0 && p.optInt("minute", 60) <= 59;
            case EXTENSION_ACTION:
                return hasOnlyExtensionKeys(p) &&
                        validReference(p.optString("extensionId", ""), 80) &&
                        validReference(p.optString("actionId", ""), 64) &&
                        clean(p.optString("extensionName", "")).length() <= 60 &&
                        clean(p.optString("actionName", "")).length() <= 60 &&
                        validStoredActionParameters(p.optJSONObject("actionParameters"));
            default:
                return true;
        }
    }

    public static AssistantReply.Action extensionAction(OrbitExtension extension,
                                                        OrbitExtension.Action action) {
        return extensionAction(extension, action, new JSONObject());
    }

    public static AssistantReply.Action extensionAction(OrbitExtension extension,
            OrbitExtension.Action action, JSONObject actionParameters) {
        if (extension == null || action == null) return null;
        JSONObject params = new JSONObject();
        try {
            params.put("extensionId", extension.id);
            params.put("actionId", action.id);
            // Display-only snapshots keep a removed action understandable. Execution
            // always resolves the stable IDs against the current validated manifest.
            params.put("extensionName", extension.name);
            params.put("actionName", action.name);
            if (actionParameters != null && actionParameters.length() > 0) {
                params.put("actionParameters", new JSONObject(actionParameters.toString()));
            }
        } catch (Exception ignored) {}
        return new AssistantReply.Action(EXTENSION_ACTION, params, false);
    }

    private static boolean hasOnlyExtensionKeys(JSONObject params) {
        Iterator<String> keys = params.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!("extensionId".equals(key) || "actionId".equals(key) ||
                    "extensionName".equals(key) || "actionName".equals(key) ||
                    "actionParameters".equals(key))) return false;
        }
        return true;
    }

    private static boolean validStoredActionParameters(JSONObject parameters) {
        if (parameters == null) return true;
        if (parameters.length() > OrbitExtensionV2.MAX_PARAMETERS ||
                parameters.toString().getBytes(StandardCharsets.UTF_8).length >
                        OrbitExtension.MAX_POST_BODY_BYTES) return false;
        Iterator<String> keys = parameters.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.matches("[a-z0-9][a-z0-9_-]{1,63}") || sensitiveParameterId(key))
                return false;
            Object value = parameters.opt(key);
            if (!(value instanceof String) || ((String) value).length() > OrbitExtensionV2.MAX_TEXT_LENGTH)
                return false;
        }
        return true;
    }

    private static boolean sensitiveParameterId(String id) {
        String lower = id.toLowerCase(Locale.US);
        return lower.contains("secret") || lower.contains("password") ||
                lower.contains("credential") || lower.contains("token") ||
                lower.contains("api_key") || lower.contains("apikey");
    }

    public static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    public static String timeLabel(int hour24, int minute) {
        int h = Math.max(0, Math.min(23, hour24));
        int m = Math.max(0, Math.min(59, minute));
        String ap = h >= 12 ? "PM" : "AM";
        int display = h % 12;
        if (display == 0) display = 12;
        return String.format(Locale.US, "%d:%02d %s", display, m, ap);
    }

    public static String minuteLabel(int minuteOfDay) {
        int safe = Math.max(0, Math.min(1439, minuteOfDay));
        return timeLabel(safe / 60, safe % 60);
    }

    private static String radiusLabel(int radiusMeters) {
        if (radiusMeters >= 1000 && radiusMeters % 1000 == 0) return (radiusMeters / 1000) + " km";
        return radiusMeters + " m";
    }

    /**
     * A duration standing on its own: "20 minutes", "4 minutes 30 seconds".
     *
     * <p>A duration that does not sit naturally in one unit is written in the units it actually
     * spans. Falling back to "270 seconds" was technically true and unreadable, and it is the same
     * category of mistake as showing a 4-minute-30 timer as "4m". Which durations count as
     * single-unit is decided once, by {@link DurationParser}, so this and the modifier form beneath
     * it can never disagree about whether 90 seconds is a minute and a half.
     */
    public static String durationLabel(int seconds) {
        int safe = Math.max(1, seconds);
        int single = DurationParser.naturalSingleUnit(safe);
        StringBuilder out = new StringBuilder();
        if (single > 0) {
            appendPart(out, safe / single,
                    single == 3600 ? "hour" : single == 60 ? "minute" : "second");
            return out.toString();
        }
        appendPart(out, safe / 3600, "hour");
        appendPart(out, (safe % 3600) / 60, "minute");
        appendPart(out, safe % 60, "second");
        return out.toString();
    }

    /** One part of a spanning duration, pluralized, skipped entirely when it is zero. */
    private static void appendPart(StringBuilder out, int count, String singularUnit) {
        if (count <= 0) return;
        if (out.length() > 0) out.append(' ');
        out.append(count).append(' ').append(singularUnit);
        if (count != 1) out.append('s');
    }

    /**
     * The same duration written as a modifier in front of a noun: "20-minute timer", "1-hour
     * timer". English hyphenates a counted unit in that position and keeps it singular, which is
     * the opposite of {@link #durationLabel(int)}: a duration standing on its own is still "20
     * minutes". Composing the standalone form with a noun is what produced "20 minutes timer".
     *
     * <p>A duration spanning units is delegated to {@link DurationParser#spokenModifier(long)},
     * which owns the one rule for saying a mixed duration out loud.
     */
    public static String durationModifierLabel(int seconds) {
        return DurationParser.spokenModifier(Math.max(1, seconds));
    }

    /**
     * The grammar rule itself, for callers that already know the count and unit the user said and
     * must not restate it in different terms. Kept beside {@link #durationLabel(int)} so the two
     * forms cannot drift apart again in separate files.
     *
     * @param count        how many of the unit
     * @param singularUnit the unit in its singular form: "second", "minute", "hour"
     */
    public static String durationModifier(long count, String singularUnit) {
        return count + "-" + singularUnit;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static boolean validReference(String value, int maxLength) {
        String clean = value == null ? "" : value.trim();
        return !clean.isEmpty() && clean.length() <= maxLength &&
                clean.matches("[a-z0-9][a-z0-9._-]+");
    }

    private static String titleCase(String s) {
        if (s == null || s.trim().isEmpty()) return "Action";
        String[] parts = s.toLowerCase(Locale.US).trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
