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

    /**
     * How a planning request is sent. Exists so the planning, repair, and failure paths can be
     * exercised against real provider response text in tests; production always uses
     * {@link AssistantClient#plan}.
     */
    interface Transport {
        void plan(Context context, String planningPrompt, AssistantClient.PlanCallback callback);
    }

    private static Transport transport = AssistantClient::plan;

    static void setTransport(Transport replacement) {
        transport = replacement == null ? AssistantClient::plan : replacement;
    }

    /** The response could not be read as a plan at all, even after one repair attempt. */
    static final String UNREADABLE_MESSAGE =
            "Orbit couldn't get a usable planning response. Try again.";
    /** The plan was read perfectly well, but nothing in it is something Orbit can do. */
    static final String UNSUPPORTED_MESSAGE =
            "Orbit understood the request but couldn't map it to supported Routine actions.";

    public static void build(Context context, String description, Callback callback) {
        if (callback == null) return;
        if (context == null || description == null || description.trim().isEmpty()) {
            callback.onError("Describe the routine you want Orbit to build.");
            return;
        }
        final boolean askedForAutomation = mentionsAutomation(description);

        transport.plan(context, prompt(context, description), new AssistantClient.PlanCallback() {
            @Override public void onText(String raw, String providerLabel) {
                RoutineDraft.Outcome outcome = RoutineDraft.parse(context, raw);
                if (outcome.draft != null) {
                    record(context, providerLabel, raw, outcome, false, "");
                    deliver(callback, outcome.draft, askedForAutomation);
                    return;
                }
                // A response Orbit could not read is worth exactly one focused repair. A response
                // it read fine that asked for unsupported things is a real answer, not a format
                // problem, so repeating the request would only waste a call.
                if (!outcome.isUnreadable()) {
                    record(context, providerLabel, raw, outcome, false, UNSUPPORTED_MESSAGE);
                    callback.onError(UNSUPPORTED_MESSAGE);
                    return;
                }
                repair(context, description, raw, outcome, providerLabel, askedForAutomation, callback);
            }

            @Override public void onError(String message) {
                String error = message == null || message.trim().isEmpty()
                        ? "Orbit could not build the routine." : message;
                record(context, "", "", null, false, error);
                callback.onError(error);
            }
        });
    }

    /** At most one repair request. Its own failure is final. */
    private static void repair(Context context, String description, String firstResponse,
                               RoutineDraft.Outcome firstOutcome, String providerLabel,
                               boolean askedForAutomation, Callback callback) {
        transport.plan(context, repairPrompt(context, description, firstResponse),
                new AssistantClient.PlanCallback() {
            @Override public void onText(String raw, String repairProvider) {
                RoutineDraft.Outcome repaired = RoutineDraft.parse(context, raw);
                if (repaired.draft != null) {
                    record(context, repairProvider, raw, repaired, true, "");
                    deliver(callback, repaired.draft, askedForAutomation);
                    return;
                }
                String error = repaired.isUnreadable() ? UNREADABLE_MESSAGE : UNSUPPORTED_MESSAGE;
                record(context, repairProvider, raw, repaired, true, error);
                callback.onError(error);
            }

            @Override public void onError(String message) {
                record(context, providerLabel, firstResponse, firstOutcome, true,
                        message == null || message.trim().isEmpty() ? UNREADABLE_MESSAGE : message);
                callback.onError(message == null || message.trim().isEmpty()
                        ? UNREADABLE_MESSAGE : message);
            }
        });
    }

    private static void deliver(Callback callback, RoutineDraft draft, boolean askedForAutomation) {
        // Only when automation was clearly requested but nothing could be drafted from it,
        // so the user is never left thinking the timing was silently ignored.
        String notice = askedForAutomation && !draft.hasTrigger() ? automationNotice() : "";
        callback.onDraft(draft, notice);
    }

    private static void record(Context context, String providerLabel, String raw,
                               RoutineDraft.Outcome outcome, boolean repairAttempted, String failure) {
        DiagnosticStore.recordRoutinePlan(context, providerLabel, raw, outcome, repairAttempted, failure);
    }

    /**
     * One focused correction request. It carries the invalid response and the schema, and nothing
     * else that was not already sent: no conversation, screen context, memories, or secrets.
     */
    static String repairPrompt(Context context, String description, String invalidResponse) {
        String previous = invalidResponse == null ? "" : invalidResponse.trim();
        if (previous.length() > 4000) previous = previous.substring(0, 4000);
        return "Your previous reply could not be read as an Orbit routine plan.\n\n"
                + "Previous reply:\n" + previous + "\n\n"
                + "Return the same intended routine as a single raw JSON object and nothing else. "
                + "No prose, no explanation, no code fence. Keep the steps the user actually asked "
                + "for; do not add, remove, or substitute any of them.\n\n"
                + prompt(context, description);
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
                .append("\"elseSteps\": 0-5, ")
                .append("\"startMinute\": 0-1439, \"endMinute\": 0-1439 for time, ")
                .append("\"locationName\": \"saved place label\" for location}.\n")
                .append("Branching: for \"otherwise\" or \"else\", set \"elseSteps\" to the number ")
                .append("of steps that run when the condition is false, and place exactly those ")
                .append("steps immediately after the ")
                .append("\"nextSteps\" steps. Layout: IF_CONDITION, then nextSteps steps, then ")
                .append("elseSteps steps, then any steps that always run. Exactly one path runs.\n\n")
                .append("Rules:\n")
                .append("- Use at most ").append(RoutineActionCatalog.MAX_STEPS).append(" steps, in the order they should run.\n")
                .append("- Use only the action types listed below. Never invent a type or a parameter.\n")
                .append("- If part of the request has no supported action, leave it out of steps and ")
                .append("describe it in \"unsupported\". Never approximate it with a different action.\n")
                .append("- If a required value is not stated and cannot be reasonably inferred, leave ")
                .append("that step out and say so in \"unsupported\".\n")
                .append("- One level of branching only. Never put an IF_CONDITION inside another ")
                .append("condition's steps, and never use loops, repeats, or more than one ")
                .append("otherwise per condition. Describe anything like that in \"unsupported\".\n")
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
