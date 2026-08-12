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
        String q = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (q.isEmpty()) return null;
        try {
            if (q.equals("open settings") || q.equals("open my settings")) {
                return new ParsedCommand(action("OPEN_SETTINGS", new JSONObject()),
                        "Opening Settings.", "open Settings");
            }
            if (q.contains("flashlight") || q.contains("torch")) {
                boolean on = !(q.contains("off") || q.contains("disable") || q.contains("turn it off"));
                return new ParsedCommand(action("FLASHLIGHT", new JSONObject().put("on", on)),
                        on ? "Turning on the flashlight." : "Turning off the flashlight.",
                        on ? "turn on the flashlight" : "turn off the flashlight");
            }
            if (q.contains("do not disturb") || q.matches(".*\\b(dnd)\\b.*")) {
                boolean enabled = !(q.contains("off") || q.contains("disable") || q.contains("turn off"));
                return new ParsedCommand(action("SET_DND", new JSONObject().put("enabled", enabled)),
                        enabled ? "Turning on Do Not Disturb." : "Turning off Do Not Disturb.",
                        enabled ? "turn on Do Not Disturb" : "turn off Do Not Disturb");
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
            Matcher timer = Pattern.compile("(?:set (?:a )?)?timer(?: for)? (\\d+)\\s*(second|seconds|minute|minutes|hour|hours)").matcher(q);
            if (timer.find()) {
                long n = Long.parseLong(timer.group(1));
                String unit = timer.group(2);
                long seconds = unit.startsWith("hour") ? n * 3600 : unit.startsWith("minute") ? n * 60 : n;
                return new ParsedCommand(action("SET_TIMER", new JSONObject().put("seconds", seconds).put("label", "Orbit timer")),
                        "Setting a " + timer.group(1) + " " + unit + " timer.",
                        "set a " + timer.group(1) + " " + unit + " timer");
            }
            Matcher alarm = Pattern.compile("(?:set (?:an )?)?alarm(?: for| at)? (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(q);
            if (alarm.find()) {
                int hour = Integer.parseInt(alarm.group(1));
                int minute = alarm.group(2) == null ? 0 : Integer.parseInt(alarm.group(2));
                String ap = alarm.group(3);
                if ("pm".equals(ap) && hour < 12) hour += 12;
                if ("am".equals(ap) && hour == 12) hour = 0;
                return new ParsedCommand(action("SET_ALARM", new JSONObject().put("hour", hour).put("minute", minute).put("label", "Orbit alarm")),
                        "Opening your Clock app with that alarm.",
                        "set an alarm");
            }
            Matcher app = Pattern.compile("open (.+)").matcher(q);
            if (app.matches() && app.group(1).length() < 60) {
                String name = app.group(1).trim();
                return new ParsedCommand(action("OPEN_APP", new JSONObject().put("app", name)),
                        "Opening " + name + ".", "open " + name);
            }
        } catch (Exception ignored) {
        }
        return null;
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
