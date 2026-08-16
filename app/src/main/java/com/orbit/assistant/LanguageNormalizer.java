package com.orbit.assistant;

import java.util.Locale;

/**
 * Small deterministic text tidying shared by Orbit's local language routers.
 *
 * <p>Every router previously did its own lowercasing and whitespace collapsing, and each one drew
 * a slightly different line, so a phrase that reached one router could miss another. This holds
 * the few normalizations they all want. It is deliberately plain string work: no model, no
 * network, no learned behaviour, and nothing that changes what a router decides — only the shape
 * of the text it decides on.
 */
public final class LanguageNormalizer {
    private LanguageNormalizer() {}

    /**
     * Lowercases, straightens curly apostrophes, drops sentence punctuation, and collapses
     * whitespace. Digits, {@code %}, {@code :} and {@code .} survive, because the device
     * commands read levels and clock times out of them.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.toLowerCase(Locale.US)
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"');
        value = value.replaceAll("[?!,;\"()\\[\\]]", " ");
        return value.replaceAll("\\s+", " ").trim();
    }

    /**
     * Expands everyday shorthand to the words the routers already match on.
     *
     * <p>"What notifs have I missed?" is the same request as "What notifications have I missed?";
     * only the spelling differed, and that alone used to send it to the hosted model, which then
     * correctly said it could not see the user's notifications.
     */
    public static String expandShorthand(String normalized) {
        if (normalized == null || normalized.isEmpty()) return "";
        String value = normalized;
        value = value.replaceAll("\\bnotifs\\b", "notifications");
        value = value.replaceAll("\\bnotif\\b", "notification");
        value = value.replaceAll("\\bnotifications'\\b", "notifications");
        value = value.replaceAll("\\bmsgs\\b", "messages");
        value = value.replaceAll("\\bmsg\\b", "message");
        value = value.replaceAll("\\bdnd\\b", "do not disturb");
        return value;
    }

    /** {@link #normalize} followed by {@link #expandShorthand}. */
    public static String canonical(String raw) {
        return expandShorthand(normalize(raw));
    }

    /**
     * Removes the wrappers people put around an instruction, so the routers can match the
     * instruction itself rather than every polite form of it.
     */
    public static String stripPoliteness(String normalized) {
        if (normalized == null) return "";
        String value = normalized.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String lead : new String[]{"please ", "hey orbit ", "ok orbit ", "okay orbit ",
                    "orbit ", "can you ", "could you ", "would you ", "will you ", "i want you to ",
                    "i'd like you to ", "go ahead and ", "just "}) {
                if (value.startsWith(lead)) {
                    value = value.substring(lead.length()).trim();
                    changed = true;
                }
            }
            for (String tail : new String[]{" please", " for me", " thanks", " thank you",
                    " right now", " real quick"}) {
                if (value.endsWith(tail)) {
                    value = value.substring(0, value.length() - tail.length()).trim();
                    changed = true;
                }
            }
        }
        return value;
    }

    /**
     * True when a sentence is asking <em>about</em> a subject rather than instructing Orbit to
     * act on it: "how do notifications work" is a question for the model, not a request to read
     * the user's notification history.
     *
     * <p>Deliberately narrow. Only forms that cannot reasonably be an instruction count, so
     * "can you turn on DND" and "what notifications did I miss" both stay actionable.
     */
    public static boolean isConceptualQuestion(String normalized) {
        if (normalized == null || normalized.isEmpty()) return false;
        String p = normalized;
        // "what is a …", "what does … mean", "how do … work", "why do apps …"
        if (p.matches("^(what|which)\\s+(is|are|was|were)\\b.*")) return true;
        if (p.matches("^what\\s+does\\b.*")) return true;
        if (p.matches("^(how|why)\\s+(do|does|did|is|are|can|would|should)\\b.*")) return true;
        if (p.matches("^(how|why)\\s+to\\b.*")) return true;
        if (p.matches("^(explain|define|tell me about|what's the difference)\\b.*")) return true;
        return false;
    }

    /**
     * Reads a small written number, returning -1 when the word is not one Orbit handles.
     *
     * <p>Only the counts that turn up in timers and alarms. Anything else is left alone rather
     * than guessed at.
     */
    public static int wordNumber(String word) {
        if (word == null) return -1;
        switch (word.trim()) {
            case "a": case "an": case "one": return 1;
            case "two": case "couple": case "a couple": return 2;
            case "three": return 3;
            case "four": return 4;
            case "five": return 5;
            case "six": return 6;
            case "seven": return 7;
            case "eight": return 8;
            case "nine": return 9;
            case "ten": return 10;
            case "eleven": return 11;
            case "twelve": return 12;
            case "fifteen": return 15;
            case "twenty": return 20;
            case "thirty": return 30;
            case "forty": return 40;
            case "forty five": case "forty-five": return 45;
            case "fifty": return 50;
            case "sixty": return 60;
            case "ninety": return 90;
            default: return -1;
        }
    }
}
