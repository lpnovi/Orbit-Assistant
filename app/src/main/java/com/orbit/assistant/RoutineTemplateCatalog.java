package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Curated built-in starting points that instantiate ordinary editable Routines. */
public final class RoutineTemplateCatalog {
    public static final class Template {
        public final String id;
        public final String displayName;
        public final String description;
        public final String category;
        public final String suggestedRoutineName;
        public final List<AssistantReply.Action> actions;
        public final String recommendedCommandPhrase;
        public final String customizationNote;

        private Template(String id, String displayName, String description, String category,
                         String suggestedRoutineName, List<AssistantReply.Action> actions,
                         String recommendedCommandPhrase, String customizationNote) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.category = category;
            this.suggestedRoutineName = suggestedRoutineName;
            this.actions = Collections.unmodifiableList(RoutineStore.copyActions(actions));
            this.recommendedCommandPhrase = recommendedCommandPhrase == null
                    ? "" : recommendedCommandPhrase.trim();
            this.customizationNote = customizationNote == null ? "" : customizationNote.trim();
        }
    }

    private static final List<Template> TEMPLATES = buildCatalog();

    private RoutineTemplateCatalog() {}

    public static List<Template> list() {
        return TEMPLATES;
    }

    public static String actionSummary(Template template) {
        if (template == null || template.actions.isEmpty()) return "No steps";
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < template.actions.size(); i++) {
            if (i > 0) summary.append(" • ");
            summary.append(shortActionLabel(template.actions.get(i)));
        }
        return summary.toString();
    }

    public static List<String> capabilityNotes(Context context, Template template) {
        Set<String> notes = new LinkedHashSet<>();
        if (template == null) return new ArrayList<>();
        for (AssistantReply.Action action : template.actions) {
            if (action == null || action.type == null) continue;
            if (RoutineActionCatalog.SET_DND.equals(action.type)) {
                notes.add(OrbitPermissionHelper.hasDndAccess(context)
                        ? "Do Not Disturb access: ready"
                        : "Do Not Disturb access may be requested when this Routine runs");
            } else if (RoutineActionCatalog.SET_BRIGHTNESS.equals(action.type)) {
                notes.add(OrbitPermissionHelper.canWriteSystemSettings(context)
                        ? "Modify system settings access: ready"
                        : "Modify system settings access may be requested for brightness");
            } else if (RoutineActionCatalog.OPEN_INTERNET_PANEL.equals(action.type) ||
                    RoutineActionCatalog.OPEN_BLUETOOTH_SETTINGS.equals(action.type)) {
                notes.add("Android opens this control in the foreground for you to finish");
            }
        }
        return new ArrayList<>(notes);
    }

    public static String uniqueRoutineName(Context context, String suggested) {
        String base = RoutineStore.sanitizeName(suggested);
        if (base.isEmpty()) base = "New routine";
        if (!RoutineStore.nameExists(context, base, null)) return base;
        for (int number = 2; number < 1000; number++) {
            String suffix = " " + number;
            int maxBase = Math.max(1, RoutineStore.MAX_NAME_LENGTH - suffix.length());
            String root = base.length() > maxBase ? base.substring(0, maxBase).trim() : base;
            String candidate = RoutineStore.sanitizeName(root + suffix);
            if (!RoutineStore.nameExists(context, candidate, null)) return candidate;
        }
        return RoutineStore.sanitizeName("Routine " + System.currentTimeMillis() % 100000);
    }

    private static List<Template> buildCatalog() {
        List<Template> templates = new ArrayList<>();
        templates.add(template("gaming", "Gaming mode",
                "Set up your phone for a focused gaming session.", "LEISURE", "Gaming mode",
                actions(dnd(true), brightness(55), volume(65)), "gaming time",
                "Add your preferred game as an Open app step in the editor."));
        templates.add(template("bedtime", "Bedtime",
                "Quiet notifications and lower the screen and media levels.", "DAILY", "Bedtime",
                actions(dnd(true), brightness(20), volume(20)), "bedtime", ""));
        templates.add(template("morning", "Morning",
                "Restore a brighter, ready-for-the-day phone setup.", "DAILY", "Morning",
                actions(dnd(false), brightness(65), volume(45)), "good morning", ""));
        templates.add(template("focus", "Focus mode",
                "Reduce interruptions while keeping the screen comfortable.", "FOCUS", "Focus mode",
                actions(dnd(true), brightness(50), volume(20)), "focus time",
                "Add a work or focus app as an Open app step if you want one launched."));
        templates.add(template("movie", "Movie mode",
                "Dim the screen, quiet interruptions, and set a comfortable media level.",
                "LEISURE", "Movie mode",
                actions(dnd(true), brightness(30), volume(55)), "movie mode",
                "Add your preferred streaming app as an Open app step in the editor."));
        templates.add(template("leaving_home", "Leaving home",
                "Restore everyday levels and open Android's Internet controls for a quick check.",
                "LOCATION", "Leaving home",
                actions(dnd(false), brightness(60), simple(RoutineActionCatalog.OPEN_INTERNET_PANEL)),
                "leaving home", "Add an automatic location trigger after reviewing the Routine."));
        templates.add(template("arriving_home", "Arriving home",
                "Restore normal notifications and media, then show Internet controls.",
                "LOCATION", "Arriving home",
                actions(dnd(false), volume(45), simple(RoutineActionCatalog.OPEN_INTERNET_PANEL)),
                "arriving home", "Add an automatic location trigger after reviewing the Routine."));
        templates.add(template("battery_saver", "Battery saver",
                "Use lower display and media levels for a lighter device setup.", "DEVICE",
                "Battery saver", actions(brightness(20), volume(20)), "save battery",
                "Orbit does not currently toggle Android Battery Saver itself; this template uses only supported actions."));
        templates.add(template("driving", "Driving",
                "Reduce interruptions, raise media volume, and open Bluetooth settings.", "TRAVEL",
                "Driving", actions(dnd(true), volume(70),
                        simple(RoutineActionCatalog.OPEN_BLUETOOTH_SETTINGS)), "driving mode",
                "Android opens Bluetooth settings in the foreground for you to finish."));
        templates.add(template("study", "Work / study",
                "Create a quieter setup for focused work or study.", "FOCUS", "Study mode",
                actions(dnd(true), brightness(45), volume(15)), "study time",
                "Add your preferred work or study app as an Open app step in the editor."));
        return Collections.unmodifiableList(templates);
    }

    private static Template template(String id, String displayName, String description,
                                     String category, String routineName,
                                     List<AssistantReply.Action> actions, String phrase,
                                     String customizationNote) {
        return new Template(id, displayName, description, category, routineName, actions,
                phrase, customizationNote);
    }

    private static List<AssistantReply.Action> actions(AssistantReply.Action... values) {
        List<AssistantReply.Action> out = new ArrayList<>();
        if (values != null) Collections.addAll(out, values);
        return out;
    }

    private static AssistantReply.Action dnd(boolean enabled) {
        return action(RoutineActionCatalog.SET_DND, new JSONObject(), "enabled", enabled);
    }

    private static AssistantReply.Action brightness(int percent) {
        return action(RoutineActionCatalog.SET_BRIGHTNESS, new JSONObject(), "percent", percent);
    }

    private static AssistantReply.Action volume(int percent) {
        return action(RoutineActionCatalog.SET_VOLUME, new JSONObject(), "percent", percent);
    }

    private static AssistantReply.Action simple(String type) {
        return new AssistantReply.Action(type, new JSONObject(), false);
    }

    private static AssistantReply.Action action(String type, JSONObject params, String key,
                                                Object value) {
        try { params.put(key, value); } catch (Exception ignored) {}
        return new AssistantReply.Action(type, params, false);
    }

    private static String shortActionLabel(AssistantReply.Action action) {
        if (action == null || action.type == null) return "Action";
        if (RoutineActionCatalog.SET_DND.equals(action.type)) {
            return "DND " + (action.params != null && action.params.optBoolean("enabled", true)
                    ? "On" : "Off");
        }
        if (RoutineActionCatalog.SET_BRIGHTNESS.equals(action.type)) return "Brightness";
        if (RoutineActionCatalog.SET_VOLUME.equals(action.type)) return "Media volume";
        if (RoutineActionCatalog.OPEN_INTERNET_PANEL.equals(action.type)) return "Internet panel";
        if (RoutineActionCatalog.OPEN_BLUETOOTH_SETTINGS.equals(action.type)) return "Bluetooth settings";
        return RoutineActionCatalog.labelForType(action.type);
    }
}
