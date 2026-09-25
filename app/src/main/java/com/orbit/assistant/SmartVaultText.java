package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The text rules Smart Vault's index is built from: words for keyword search, and passages for
 * meaning search.
 *
 * <p>Pure Java and deterministic. The same item always produces the same passages in the same
 * order, which is what lets a stored vector be matched back to the words it came from without
 * storing those words twice.
 */
final class SmartVaultText {

    /** Roughly one screen of reading per passage: small enough to be about one thing. */
    static final int PASSAGE_CHARS = 700;
    static final int PASSAGE_OVERLAP = 120;
    /** Bounds the work one very long item can cause. The rest still counts for keyword search. */
    static final int MAX_PASSAGES = 24;

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "did", "do", "does",
            "for", "from", "had", "has", "have", "how", "i", "if", "in", "into", "is", "it",
            "its", "me", "my", "of", "on", "or", "our", "so", "that", "the", "their", "them",
            "then", "there", "these", "they", "this", "to", "was", "we", "were", "what", "when",
            "where", "which", "who", "why", "will", "with", "you", "your", "about", "any",
            "saved", "save", "vault", "find", "show", "thing", "things", "stuff", "that's"));

    private SmartVaultText() {}

    /**
     * The words a text contributes to keyword search: lower case, split on anything that is not a
     * letter or digit, stopwords dropped, and a light plural fold so "recipes" finds "recipe".
     */
    static List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp);
            } else if (cp == '\'' || cp == '’') {
                // "don't" stays one word; a closing quote simply ends one.
                if (word.length() > 0 && i < lower.length()
                        && Character.isLetter(lower.codePointAt(i))) {
                    continue;
                }
                add(word, out);
            } else {
                add(word, out);
            }
        }
        add(word, out);
        return out;
    }

    private static void add(StringBuilder word, List<String> out) {
        if (word.length() == 0) return;
        String w = word.toString();
        word.setLength(0);
        if (w.length() < 2 && !Character.isDigit(w.charAt(0))) return;
        if (STOPWORDS.contains(w)) return;
        out.add(fold(w));
    }

    /** A deliberately small plural fold. Anything cleverer starts merging unrelated words. */
    static String fold(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses")
                || w.endsWith("xes"))) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")
                && !w.endsWith("is")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** How often each term appears. */
    static Map<String, Integer> counts(List<String> terms) {
        Map<String, Integer> out = new HashMap<>();
        for (String t : terms) out.merge(t, 1, Integer::sum);
        return out;
    }

    /**
     * Overlapping windows over a text, cut at whitespace where one is near.
     *
     * <p>The overlap means a sentence that straddles a boundary is still whole in one of the two
     * passages either side of it.
     */
    static List<String> passages(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) return out;
        int start = 0;
        while (start < flat.length() && out.size() < MAX_PASSAGES) {
            int end = Math.min(flat.length(), start + PASSAGE_CHARS);
            if (end < flat.length()) {
                int space = flat.lastIndexOf(' ', end);
                if (space > start + PASSAGE_CHARS / 2) end = space;
            }
            out.add(flat.substring(start, end).trim());
            if (end >= flat.length()) break;
            int next = end - PASSAGE_OVERLAP;
            if (next <= start) next = end;
            int space = flat.indexOf(' ', next);
            start = space > 0 && space < end ? space + 1 : next;
        }
        return out;
    }

    /**
     * A short excerpt of {@code text} around the first place any of {@code needles} occurs, or
     * empty. Used to show why a result matched when the match is in words the card does not show.
     */
    static String excerpt(String text, List<String> needles, int width) {
        if (text == null || text.isEmpty() || needles == null || needles.isEmpty()) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        String lower = flat.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String needle : needles) {
            if (needle == null || needle.length() < 2) continue;
            int found = lower.indexOf(needle.toLowerCase(Locale.ROOT));
            if (found >= 0 && (at < 0 || found < at)) at = found;
        }
        if (at < 0) return "";
        int from = Math.max(0, at - width / 3);
        int to = Math.min(flat.length(), from + width);
        if (from > 0) {
            int space = flat.indexOf(' ', from);
            if (space > 0 && space < at) from = space + 1;
        }
        if (to < flat.length()) {
            int space = flat.lastIndexOf(' ', to);
            if (space > at) to = space;
        }
        return (from > 0 ? "…" : "") + flat.substring(from, to).trim()
                + (to < flat.length() ? "…" : "");
    }
}
