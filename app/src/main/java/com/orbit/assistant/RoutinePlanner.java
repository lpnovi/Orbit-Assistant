package com.orbit.assistant;

import android.content.Context;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a plain description into a validated {@link RoutineDraft}.
 *
 * <p>The capability list handed to the planner is generated from
 * {@link RoutineActionCatalog} and the currently enabled extension actions, so there is no second
 * list of supported actions to drift out of step with the Action Engine. Only the description and
 * that safe capability metadata are sent: no conversation history, screen context, notifications,
 * memories, attachments, or extension secrets.
 */
public final class RoutinePlanner {
    /** Scheduling and condition wording, recognised so it can be reported rather than dropped. */
    private static final Pattern AUTOMATION_LANGUAGE = Pattern.compile(
            "(?i)\\b(every (day|morning|night|weekday|weekend|monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
            + "|each (day|morning|night|weekday)"
            + "|when i (arrive|get|leave|reach)"
            + "|when i'm (home|at|away)"
            + "|at \\d{1,2}(:\\d{2})?\\s*(am|pm)?\\b"
            + "|after \\d{1,2}\\s*(am|pm)"
            + "|before \\d{1,2}\\s*(am|pm)"
            + "|only when|if it is|if i am|schedule|automatically|remind me every)\\b");

    public interface Callback {
        void onDraft(RoutineDraft draft, String automationNotice);
        void onError(String message);
    }

    private RoutinePlanner() {}

    /** True when the description asks for timing or place-based automation. */
    static boolean mentionsAutomation(String description) {
        return description != null && AUTOMATION_LANGUAGE.matcher(description).find();
    }

    static String automationNotice() {
        return "The steps were created. Timing, location, and condition automation still needs to "
                + "be set up in Automatic triggers after you save the routine.";
    }

    public static void build(Context context, String description, Callback callback) {
        if (callback == null) return;
        if (context == null || description == null || description.trim().isEmpty()) {
            callback.onError("Describe the routine you want Orbit to build.");
            return;
        }
        final boolean askedForAutomation = mentionsAutomation(description);

        AssistantClient.plan(context, prompt(context, description), new AssistantClient.Callback() {
            @Override public void onSuccess(AssistantReply reply) {
                String text = reply == null ? "" : reply.text;
                RoutineDraft draft = RoutineDraft.fromPlannerJson(context, text);
                if (draft == null || !draft.hasSteps()) {
                    callback.onError("Orbit couldn't turn that into routine steps. Try naming the "
                            + "specific settings you want changed, such as \"turn on Do Not Disturb "
                            + "and set brightness to 30%\".");
                    return;
                }
                // Only when automation was clearly requested but nothing could be drafted from it,
                // so the user is never left thinking the timing was silently ignored.
                String notice = askedForAutomation && !draft.hasTrigger() ? automationNotice() : "";
                callback.onDraft(draft, notice);
            }

            @Override public void onError(String message) {
                callback.onError(message == null || message.trim().isEmpty()
                        ? "Orbit could not build the routine." : message);
            }
        });
    }

    /** The full planning instruction, including the generated capability list. */
    static String prompt(Context context, String description) {
        StringBuilder out = new StringBuilder();
        out.append("You are Orbit's routine planner. Convert the user's description into a saved ")
                .append("Orbit routine using ONLY the supported actions listed below.\n\n")
                .append("Reply with a single JSON object and nothing else. No prose, no code fence.\n")
                .append("{\n")
                .append("  \"name\": \"short routine name, at most ")
                .append(RoutineStore.MAX_NAME_LENGTH).append(" characters\",\n")
                .append("  \"steps\": [{\"type\": \"ACTION_TYPE\", \"params\": {…}}],\n")
                .append("  \"trigger\": null or a trigger object described below,\n")
                .append("  \"elseRequested\": true only if the user asked for an otherwise/else branch,\n")
                .append("  \"unsupported\": [\"short description of anything requested that no ")
                .append("supported action can do\"]\n")
                .append("}\n\n")
                .append("Triggers (optional, at most one):\n")
                .append("- Time: {\"type\": \"time\", \"recurrence\": \"once|daily|weekdays|weekends|weekly|custom\", ")
                .append("\"hour\": 0-23, \"minute\": 0-59, \"weekdayMask\": bitmask with Monday=1, Tuesday=2, ")
                .append("Wednesday=4, Thursday=8, Friday=16, Saturday=32, Sunday=64 (weekly only)}\n")
                .append("- Location: {\"type\": \"location\", \"transition\": \"arrive|leave\", ")
                .append("\"place\": \"saved place label\", \"radiusMeters\": 50-5000 optional}\n")
                .append(savedPlaces(context))
                .append("\nTrigger rules:\n")
                .append("- Only add a trigger if the user clearly asked for one.\n")
                .append("- Never guess a clock time. If the time is vague, such as \"in the morning\" ")
                .append("or \"before bed\", omit the trigger and say so in \"unsupported\".\n")
                .append("- Never guess a place. Use only a saved place label listed above; if the user ")
                .append("named somewhere else, still use their words in \"place\" so Orbit can ask.\n")
                .append("- Only use arrive or leave when the wording is clear.\n\n")
                .append("Conditions: to run steps only in certain circumstances, add an ")
                .append("IF_CONDITION step immediately before the steps it guards, with params ")
                .append("{\"mode\": \"time|location|time_and_location\", \"nextSteps\": 1-5, ")
                .append("\"startMinute\": 0-1439, \"endMinute\": 0-1439 for time, ")
                .append("\"locationName\": \"saved place label\" for location}.\n\n")
                .append("Rules:\n")
                .append("- Use at most ").append(RoutineActionCatalog.MAX_STEPS).append(" steps, in the order they should run.\n")
                .append("- Use only the action types listed below. Never invent a type or a parameter.\n")
                .append("- If part of the request has no supported action, leave it out of steps and ")
                .append("describe it in \"unsupported\". Never approximate it with a different action.\n")
                .append("- If a required value is not stated and cannot be reasonably inferred, leave ")
                .append("that step out and say so in \"unsupported\".\n")
                .append("- Do not invent an \"otherwise\" or \"else\" branch. If the user asks for one, ")
                .append("set \"elseRequested\": true and plan only the supported part.\n")
                .append("- Give the routine a short name such as Bedtime or Focus mode, not a sentence.\n\n")
                .append("Supported actions:\n")
                .append(capabilities(context))
                .append("\nUser description:\n")
                .append(description.trim());
        return out.toString();
    }

    /**
     * Safe action metadata generated from the live catalog. Extension entries carry only their
     * public identifiers and names — never configured values, tokens, endpoints, or headers.
     */
    static String capabilities(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("- SET_DND params {\"enabled\": true|false} — turn Do Not Disturb on or off\n");
        out.append("- SET_BRIGHTNESS params {\"percent\": 0-100} — set screen brightness\n");
        out.append("- SET_VOLUME params {\"percent\": 0-100} — set media volume\n");
        out.append("- FLASHLIGHT params {\"on\": true|false} — turn the flashlight on or off\n");
        out.append("- SET_TIMER params {\"seconds\": 1-86400, \"label\": \"optional\"} — start a timer\n");
        out.append("- SET_ALARM params {\"hour\": 0-23, \"minute\": 0-59, \"label\": \"optional\"} — set an alarm\n");
        out.append("- OPEN_APP params {\"app\": \"visible app name\"} — open an installed app\n");
        out.append("- OPEN_SETTINGS params {} — open Android settings\n");
        out.append("- OPEN_INTERNET_PANEL params {} — open the internet/Wi-Fi panel\n");
        out.append("- OPEN_BLUETOOTH_SETTINGS params {} — open Bluetooth settings\n");

        if (context == null) return out.toString();
        List<OrbitExtensionStore.ActionChoice> choices = OrbitExtensionStore.enabledActions(context);
        for (OrbitExtensionStore.ActionChoice choice : choices) {
            if (choice == null || choice.extension == null || choice.action == null) continue;
            // Identifiers and display names only. Endpoints, headers, configured values, and
            // Keystore-backed secrets are never part of this description.
            out.append("- EXTENSION_ACTION params {\"extensionId\": \"")
                    .append(safe(choice.extension.id)).append("\", \"actionId\": \"")
                    .append(safe(choice.action.id)).append("\", \"extensionName\": \"")
                    .append(safe(choice.extension.name)).append("\", \"actionName\": \"")
                    .append(safe(choice.action.name)).append("\"} — ")
                    .append(safe(choice.action.name)).append(" via ")
                    .append(safe(choice.extension.name)).append('\n');
        }
        return out.toString();
    }

    /**
     * Saved place labels only. Coordinates stay on the device: the planner returns a label and
     * Orbit resolves it locally against the saved place list.
     */
    static String savedPlaces(Context context) {
        if (context == null) return "";
        StringBuilder out = new StringBuilder();
        for (SavedPlaceStore.Place place : SavedPlaceStore.list(context)) {
            if (place == null || place.name == null || place.name.trim().isEmpty()) continue;
            if (out.length() == 0) out.append("Saved places you may use as \"place\": ");
            else out.append(", ");
            out.append(safe(place.name));
        }
        if (out.length() == 0) return "No saved places are set up yet.\n";
        return out.append('\n').toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\"", "").replace('\n', ' ').trim();
    }
}
