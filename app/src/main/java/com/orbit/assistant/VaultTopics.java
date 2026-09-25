package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The one spelling rule for Vault topics.
 *
 * <p>Topics are a light way to find things again, not a folder tree, so they are kept short and
 * few: lower case, one to three words, no punctuation beyond {@code &} and a hyphen. Everything
 * that stores, suggests, filters or compares a topic goes through {@link #normalize}, so "Recipes",
 * "#recipes" and " recipes " are one topic rather than three.
 *
 * <p>{@link #reuse} is what keeps AI suggestions from sprawling. A suggested topic that differs
 * from one the Vault already uses only by a trailing plural is folded into the existing one, so a
 * Vault does not slowly grow "recipe", "recipes" and "Recipes" side by side.
 */
final class VaultTopics {

    static final int MAX_CHARS = 24;
    static final int MAX_WORDS = 3;
    /** How many topics the user may confirm on one item. */
    static final int MAX_PER_ITEM = 5;

    private VaultTopics() {}

    static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("#")) value = value.substring(1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || cp == '&' || cp == '-') {
                out.appendCodePoint(cp);
            } else if (Character.isWhitespace(cp) || cp == '_' || cp == '/') {
                out.append(' ');
            }
        }
        String collapsed = out.toString().replaceAll("\\s+", " ").trim();
        collapsed = collapsed.replaceAll("^[-&\\s]+|[-&\\s]+$", "");
        if (collapsed.isEmpty()) return "";
        String[] words = collapsed.split(" ");
        if (words.length > MAX_WORDS) {
            StringBuilder first = new StringBuilder();
            for (int i = 0; i < MAX_WORDS; i++) {
                if (i > 0) first.append(' ');
                first.append(words[i]);
            }
            collapsed = first.toString();
        }
        if (collapsed.length() > MAX_CHARS) {
            collapsed = collapsed.substring(0, MAX_CHARS).trim();
        }
        return collapsed;
    }

    /** A proposed topic, spelled the way the Vault already spells it when it means the same. */
    static String reuse(String proposed, Collection<String> existing) {
        String topic = normalize(proposed);
        if (topic.isEmpty() || existing == null) return topic;
        for (String known : existing) {
            String k = normalize(known);
            if (k.equals(topic)) return k;
        }
        for (String known : existing) {
            String k = normalize(known);
            if (k.isEmpty()) continue;
            if (stem(k).equals(stem(topic))) return k;
        }
        return topic;
    }

    /** Confirmed topics for an item: normalised, unique, bounded. */
    static List<String> clean(Collection<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String topic : raw) {
            String t = normalize(topic);
            if (t.isEmpty() || out.contains(t)) continue;
            out.add(t);
            if (out.size() >= MAX_PER_ITEM) break;
        }
        return out;
    }

    private static String stem(String topic) {
        if (topic.endsWith("ies") && topic.length() > 4) {
            return topic.substring(0, topic.length() - 3) + "y";
        }
        if (topic.endsWith("es") && topic.length() > 4
                && (topic.endsWith("shes") || topic.endsWith("ches") || topic.endsWith("xes")
                || topic.endsWith("sses"))) {
            return topic.substring(0, topic.length() - 2);
        }
        if (topic.endsWith("s") && !topic.endsWith("ss") && topic.length() > 3) {
            return topic.substring(0, topic.length() - 1);
        }
        return topic;
    }
}
