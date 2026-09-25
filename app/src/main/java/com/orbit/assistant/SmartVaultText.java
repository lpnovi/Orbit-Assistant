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

    /** An excerpt chosen to explain a match, and whether it contains the query as typed. */
    static final class Match {
        static final Match NONE = new Match("", false);
        final String excerpt;
        /** True only when the excerpt contains the whole query, not just some of its words. */
        final boolean exact;

        Match(String excerpt, boolean exact) {
            this.excerpt = excerpt;
            this.exact = exact;
        }
    }

    /**
     * The passage of {@code text} that best explains why it matched {@code query}
     * (v0.8.1.0-beta.2).
     *
     * <p>In order: the query itself, ignoring case and line breaks, so "orbit purple" finds a
     * screenshot line reading "ORBIT\nPURPLE 7294"; otherwise the tightest place where the most
     * different query words occur together; otherwise nothing. Beta 1 took the first place any one
     * word occurred, so a screenshot whose header said "Orbit" showed the header rather than the
     * line the user was looking for.
     *
     * @param terms the query's search terms, as {@link #terms} made them
     */
    static Match matchExcerpt(String text, String query, List<String> terms, int width) {
        if (text == null || text.isEmpty()) return Match.NONE;
        String flat = text.replaceAll("\\s+", " ").trim();
        String lower = flat.toLowerCase(Locale.ROOT);
        String phrase = query == null ? ""
                : query.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (phrase.length() >= 2) {
            int at = lower.indexOf(phrase);
            if (at >= 0) return new Match(window(flat, at, at + phrase.length(), width), true);
        }
        if (terms == null || terms.isEmpty()) return Match.NONE;

        // Every place a query word begins a word of the text, in text order.
        List<int[]> hits = new ArrayList<>();
        for (int t = 0; t < terms.size(); t++) {
            String term = terms.get(t);
            if (term == null || term.isEmpty()) continue;
            int from = 0;
            while (true) {
                int found = lower.indexOf(term, from);
                if (found < 0) break;
                if (found == 0 || !Character.isLetterOrDigit(lower.charAt(found - 1))) {
                    hits.add(new int[]{found, t, found + term.length()});
                }
                from = found + 1;
            }
        }
        if (hits.isEmpty()) return Match.NONE;
        hits.sort((a, b) -> Integer.compare(a[0], b[0]));

        // The window holding the most distinct terms, then the tightest, then the earliest.
        int span = Math.max(1, width * 2 / 3);
        int bestStart = -1, bestEnd = -1, bestDistinct = 0, bestSpan = Integer.MAX_VALUE;
        for (int i = 0; i < hits.size(); i++) {
            Set<Integer> seen = new HashSet<>();
            int end = hits.get(i)[2];
            for (int j = i; j < hits.size() && hits.get(j)[0] - hits.get(i)[0] <= span; j++) {
                seen.add(hits.get(j)[1]);
                end = Math.max(end, hits.get(j)[2]);
                int distinct = seen.size();
                int length = end - hits.get(i)[0];
                if (distinct > bestDistinct || (distinct == bestDistinct && length < bestSpan)) {
                    bestDistinct = distinct;
                    bestSpan = length;
                    bestStart = hits.get(i)[0];
                    bestEnd = end;
                }
            }
        }
        return new Match(window(flat, bestStart, bestEnd, width), false);
    }

    /** About {@code width} characters of {@code flat} holding [start, end), cut at spaces. */
    private static String window(String flat, int start, int end, int width) {
        int from = Math.max(0, start - width / 3);
        if (end - from > width) from = start;
        int to = Math.min(flat.length(), Math.max(from + width, end));
        if (from > 0) {
            int space = flat.indexOf(' ', from);
            if (space > 0 && space < start) from = space + 1;
        }
        if (to < flat.length()) {
            int space = flat.lastIndexOf(' ', to);
            if (space >= end) to = space;
        }
        return (from > 0 ? "…" : "") + flat.substring(from, to).trim()
                + (to < flat.length() ? "…" : "");
    }
}
