package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalCommandRouter {
    private LocalCommandRouter() {}

    /** Side-effect-free recognition used to keep Custom Commands from shadowing core device commands. */
    public static boolean canHandle(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        List<String> parts = splitIntoCommandParts(raw.trim());
        if (parts.size() > 1) {
            for (String part : parts) if (parseSingleCommand(part) == null) return false;
            return true;
        }
        return parseSingleCommand(raw.trim()) != null;
    }

    public static AssistantReply tryHandle(Context context, String raw) {
        if (raw == null) return null;
        String normalized = raw.trim();
        if (normalized.isEmpty()) return null;

        List<String> parts = splitIntoCommandParts(normalized);
        if (parts.size() > 1) {
            List<AssistantReply.Action> actions = new ArrayList<>();
            List<String> spoken = new ArrayList<>();
            for (String part : parts) {
                ParsedCommand parsed = parseSingleCommand(part);
                if (parsed == null) return null;
                actions.add(parsed.action);
                spoken.add(parsed.spokenLabel);
            }
            if (!actions.isEmpty()) {
                return new AssistantReply(chainIntro(spoken), actions);
            }
        }

        ParsedCommand single = parseSingleCommand(normalized);
        if (single == null) return null;
        List<AssistantReply.Action> a = new ArrayList<>();
        a.add(single.action);
        return new AssistantReply(single.responseText, a);
    }

    private static ParsedCommand parseSingleCommand(String raw) {
        // Shared tidying, then the polite wrapper people put around a spoken instruction, so the
        // matchers below see the instruction itself rather than every way of asking for it.
        String q = LanguageNormalizer.stripPoliteness(LanguageNormalizer.canonical(raw));
        if (q.isEmpty()) return null;
        // A question about how something works is never a device command.
        if (LanguageNormalizer.isConceptualQuestion(q)) return null;
        try {
            if (q.equals("open settings") || q.equals("open my settings")
                    || q.equals("open my phone settings") || q.equals("open phone settings")
                    || q.equals("open android settings") || q.equals("open the settings")) {
                return new ParsedCommand(action("OPEN_SETTINGS", new JSONObject()),
                        "Opening Settings.", "open Settings");
            }
            if (q.contains("flashlight") || q.contains("torch")) {
                boolean on = !(q.contains("off") || q.contains("disable") || q.contains("turn it off"));
                return new ParsedCommand(action("FLASHLIGHT", new JSONObject().put("on", on)),
                        on ? "Turning on the flashlight." : "Turning off the flashlight.",
                        on ? "turn on the flashlight" : "turn off the flashlight");
            }
            // "dnd" has already been expanded to "do not disturb" by the shared normalizer, so
            // every spelling of the feature reaches one matcher.
            if (q.contains("do not disturb")) {
                boolean off = q.matches(".*\\b(off|disable|disabled|stop|end|cancel|exit)\\b.*");
                boolean enabled = !off;
                return new ParsedCommand(action("SET_DND", new JSONObject().put("enabled", enabled)),
                        enabled ? "Turning on Do Not Disturb." : "Turning off Do Not Disturb.",
                        enabled ? "turn on Do Not Disturb" : "turn off Do Not Disturb");
            }
            // A bare follow-up borrows the target Orbit last acted on, when there is exactly one
            // and the phrase names none of its own. Resolved before anything else so the rest of
            // the parsing sees an ordinary, fully-specified command.
            ParsedCommand followUp = parseRecentActionFollowUp(q);
            if (followUp != null) return followUp;

            // Relative requests are resolved first so clear relative grammar - "lower brightness
            // by 10%" - can never be read by the absolute matchers below as a level of 10%.
            // RelativeLevelCommand refuses anything naming a level with "to"/"at", so an
            // absolute command still falls straight through to them.
            RelativeLevelCommand relative = RelativeLevelCommand.parse(q);
            if (relative != null) {
                JSONObject params = new JSONObject();
                if (relative.absolute) params.put("percent", relative.percent);
                else params.put("delta", relative.delta);
                return new ParsedCommand(action(relative.actionType(), params),
                        relative.confirmation(), relative.summary());
            }
            Matcher brightness = Pattern.compile("(?:set|change|make|put|lower|raise|increase|decrease)?\\s*(?:my\\s+)?brightness(?:\\s*(?:to|at))?\\s*(\\d{1,3})\\s*%?").matcher(q);
            if (brightness.find()) {
                int percent = clampPercent(Integer.parseInt(brightness.group(1)));
                return new ParsedCommand(action("SET_BRIGHTNESS", new JSONObject().put("percent", percent)),
                        "Setting brightness to " + percent + "%.",
                        "set brightness to " + percent + "%");
            }
            Matcher lowerBrightness = Pattern.compile("(?:lower|decrease|dim) (?:my\\s+)?brightness(?:\\s+to)?\\s*(\\d{1,3})\\s*%?").matcher(q);
            if (lowerBrightness.find()) {
                int percent = clampPercent(Integer.parseInt(lowerBrightness.group(1)));
                return new ParsedCommand(action("SET_BRIGHTNESS", new JSONObject().put("percent", percent)),
                        "Lowering brightness to " + percent + "%.",
                        "lower brightness to " + percent + "%");
            }
            Matcher volume = Pattern.compile("(?:set|change|make|put|lower|raise|increase|decrease) (?:my\\s+)?(?:media\\s+)?volume(?:\\s*(?:to|at))?\\s*(\\d{1,3})\\s*%?").matcher(q);
            if (volume.find()) {
                int percent = clampPercent(Integer.parseInt(volume.group(1)));
                return new ParsedCommand(action("SET_VOLUME", new JSONObject().put("percent", percent)),
                        "Setting media volume to " + percent + "%.",
                        "set media volume to " + percent + "%");
            }
            if (q.contains("timer")) {
                ParsedCommand parsedTimer = parseTimer(q);
                if (parsedTimer != null) return parsedTimer;
            }
            ParsedCommand parsedAlarm = parseAlarm(q);
            if (parsedAlarm != null) return parsedAlarm;

            // Everyday ways of saying "launch this app". Anchored at the start so a sentence that
            // merely mentions opening something is not treated as an instruction.
            Matcher app = Pattern.compile(
                    "^(?:open|launch|start|run|bring up|pull up|fire up|take me to|go to)\\s+(.+)$")
                    .matcher(q);
            if (app.matches()) {
                String name = cleanAppName(app.group(1));
                if (!name.isEmpty() && name.length() < 60 && !looksLikeSentence(name)) {
                    return new ParsedCommand(action("OPEN_APP", new JSONObject().put("app", name)),
                            "Opening " + name + ".", "open " + name);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Resolves a short follow-up against the device target Orbit last acted on.
     *
     * <p>Only fires when the phrase names no target itself and something was acted on recently.
     * The operation still has to be one the remembered target supports, and "put it back" only
     * works when Orbit holds a real previous reading — it never guesses a level it did not
     * observe.
     */
    private static ParsedCommand parseRecentActionFollowUp(String q) throws org.json.JSONException {
        RecentActionContext.Target target = RecentActionContext.current();
        if (target == null) return null;
        if (!RecentActionContext.isBareFollowUp(q)) return null;

        boolean restore = q.matches("^(?:put|set|turn|change)\\s+(?:it|that)\\s+back$")
                || q.equals("put it back") || q.equals("undo that") || q.equals("revert that");
        if (restore) {
            if (target == RecentActionContext.Target.FLASHLIGHT) {
                boolean previous = RecentActionContext.previousFlashlightOn();
                return new ParsedCommand(
                        action("FLASHLIGHT", new JSONObject().put("on", previous)),
                        previous ? "Turning the flashlight back on." : "Turning the flashlight back off.",
                        previous ? "turn on the flashlight" : "turn off the flashlight");
            }
            int previous = RecentActionContext.previousPercent();
            // Orbit does not know where it was, so it does not pretend to.
            if (previous < 0) return null;
            String type = target == RecentActionContext.Target.BRIGHTNESS
                    ? "SET_BRIGHTNESS" : "SET_VOLUME";
            String noun = target == RecentActionContext.Target.BRIGHTNESS
                    ? "brightness" : "media volume";
            return new ParsedCommand(action(type, new JSONObject().put("percent", previous)),
                    "Putting " + noun + " back to " + previous + "%.",
                    "set " + noun + " back to " + previous + "%");
        }

        if (target == RecentActionContext.Target.FLASHLIGHT) {
            // Only an explicit on/off follow-up applies to a flashlight.
            if (q.matches("^(?:turn|switch)\\s+(?:it|that)\\s+off$") || q.equals("off")) {
                return new ParsedCommand(action("FLASHLIGHT", new JSONObject().put("on", false)),
                        "Turning off the flashlight.", "turn off the flashlight");
            }
            if (q.matches("^(?:turn|switch)\\s+(?:it|that)\\s+on$") || q.equals("on")) {
                return new ParsedCommand(action("FLASHLIGHT", new JSONObject().put("on", true)),
                        "Turning on the flashlight.", "turn on the flashlight");
            }
            return null;
        }

        // A level follow-up: reuse the ordinary relative grammar by naming the remembered target,
        // so magnitudes, extremes and exact deltas all behave exactly as they do when spoken in
        // full.
        String noun = target == RecentActionContext.Target.BRIGHTNESS ? "brightness" : "volume";
        RelativeLevelCommand resolved = RelativeLevelCommand.parse(q + " " + noun);
        if (resolved == null) {
            // "a little more" / "a bit less" carry a direction only in relation to what came
            // before, so they are mapped onto the same relative grammar explicitly.
            String direction = q.matches(".*\\b(more|higher|louder|brighter|up)\\b.*") ? "raise"
                    : q.matches(".*\\b(less|lower|quieter|dimmer|down)\\b.*") ? "lower" : null;
            if (direction == null) return null;
            resolved = RelativeLevelCommand.parse(direction + " " + noun + " " + q);
            if (resolved == null) return null;
        }
        JSONObject params = new JSONObject();
        if (resolved.absolute) params.put("percent", resolved.percent);
        else params.put("delta", resolved.delta);
        return new ParsedCommand(action(resolved.actionType(), params),
                resolved.confirmation(), resolved.summary());
    }

    /** Words that name a unit Orbit's timer action understands. */
    private static final Pattern TIMER_AFTER = Pattern.compile(
            "timer\\s*(?:for|of)?\\s*(\\d+|[a-z ]+?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b");
    /** "10 minute timer", "30 second timer" — the count said before the word. */
    private static final Pattern TIMER_BEFORE = Pattern.compile(
            "\\b(\\d+|[a-z]+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\s+timer\\b");

    private static ParsedCommand parseTimer(String q) throws org.json.JSONException {
        Matcher m = TIMER_AFTER.matcher(q);
        String count = null;
        String unit = null;
        if (m.find()) {
            count = m.group(1);
            unit = m.group(2);
        } else {
            m = TIMER_BEFORE.matcher(q);
            if (m.find()) {
                count = m.group(1);
                unit = m.group(2);
            }
        }
        if (count == null || unit == null) return null;

        long n = readCount(count);
        if (n <= 0) return null;

        long seconds = unit.startsWith("hour") || unit.startsWith("hr") ? n * 3600
                : unit.startsWith("min") ? n * 60 : n;
        if (seconds <= 0) return null;

        String spokenUnit = unit.startsWith("hour") || unit.startsWith("hr")
                ? (n == 1 ? "hour" : "hours")
                : unit.startsWith("min") ? (n == 1 ? "minute" : "minutes")
                : (n == 1 ? "second" : "seconds");
        return new ParsedCommand(
                action("SET_TIMER", new JSONObject().put("seconds", seconds).put("label", "Orbit timer")),
                "Setting a " + n + " " + spokenUnit + " timer.",
                "set a " + n + " " + spokenUnit + " timer");
    }

    /** Digits, or one of the small written numbers the shared normalizer knows. */
    private static long readCount(String value) {
        String v = value == null ? "" : value.trim();
        if (v.matches("\\d+")) {
            try { return Long.parseLong(v); } catch (Exception ignored) { return -1; }
        }
        return LanguageNormalizer.wordNumber(v);
    }

    /** Day words Orbit's alarm action cannot represent, so it must not pretend otherwise. */
    private static final Pattern ALARM_DATE_WORDS = Pattern.compile(
            "\\b(tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "weekday|weekdays|weekend|every day|everyday|daily|next week|in \\d+ days?)\\b");
    private static final Pattern ALARM_TIME = Pattern.compile(
            "(?:alarm|wake me(?: up)?)\\s*(?:for|at)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b");

    private static ParsedCommand parseAlarm(String q) throws org.json.JSONException {
        if (!q.contains("alarm") && !q.contains("wake me")) return null;

        // The SET_ALARM action carries only an hour and a minute. Silently dropping "tomorrow"
        // would set an alarm for a different day than the user asked for and then report success,
        // so a request Orbit cannot represent is left to the normal assistant path instead.
        if (ALARM_DATE_WORDS.matcher(q).find()) return null;

        Matcher m = ALARM_TIME.matcher(q);
        if (!m.find()) return null;

        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (hour > 23 || minute > 59) return null;
        String ap = m.group(3);
        if ("pm".equals(ap) && hour < 12) hour += 12;
        if ("am".equals(ap) && hour == 12) hour = 0;
        return new ParsedCommand(
                action("SET_ALARM", new JSONObject().put("hour", hour).put("minute", minute)
                        .put("label", "Orbit alarm")),
                "Opening your Clock app with that alarm.", "set an alarm");
    }

    /** Trims filler that trails an app name in ordinary speech. */
    private static String cleanAppName(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replaceAll("^(?:the|my|up)\\s+", "");
        name = name.replaceAll("\\s+(?:app|application)$", "");
        return name.trim();
    }

    /**
     * Guards the app matcher against swallowing a sentence. "start a 10 minute timer" and
     * "open the pod bay doors and explain why" are not app names.
     */
    private static boolean looksLikeSentence(String name) {
        if (name.matches(".*\\b(?:timer|alarm|brightness|volume|flashlight|do not disturb)\\b.*")) {
            return true;
        }
        return name.split("\\s+").length > 4;
    }

    private static List<String> splitIntoCommandParts(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String normalized = raw.trim().replaceAll("\\s+", " ");
        // Voice transcription often drops punctuation/conjunctions between commands,
        // for example: "set media volume to 25% turn on my flashlight and open YouTube".
        // Insert an implicit boundary after a percentage when another clear device
        // command immediately follows so the later command cannot swallow the first.
        normalized = normalized.replaceAll(
                "(?i)(\\d{1,3}\\s*%?)\\s+(?=(?:turn|open|set|change|make|put|lower|raise|increase|decrease|dim)\\b)",
                "$1 | ");
        String[] pieces = normalized.split("(?i)\\s*(?:\\||,| then | and then |\\band\\b)\\s*");
        for (String piece : pieces) {
            String part = piece == null ? "" : piece.trim();
            if (part.isEmpty()) continue;
            out.add(part);
        }
        return out;
    }

    private static String chainIntro(List<String> spoken) {
        if (spoken == null || spoken.isEmpty()) return "Working on it.";
        if (spoken.size() == 1) return "Okay, I'll " + spoken.get(0) + ".";
        StringBuilder b = new StringBuilder("Okay, I'll ");
        for (int i = 0; i < spoken.size(); i++) {
            if (i > 0) b.append(i == spoken.size() - 1 ? ", and " : ", ");
            b.append(spoken.get(i));
        }
        b.append('.');
        return b.toString();
    }

    private static AssistantReply.Action action(String type, JSONObject params) {
        return new AssistantReply.Action(type, params, false);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static final class ParsedCommand {
        final AssistantReply.Action action;
        final String responseText;
        final String spokenLabel;

        ParsedCommand(AssistantReply.Action action, String responseText, String spokenLabel) {
            this.action = action;
            this.responseText = responseText;
            this.spokenLabel = spokenLabel;
        }
    }
}
