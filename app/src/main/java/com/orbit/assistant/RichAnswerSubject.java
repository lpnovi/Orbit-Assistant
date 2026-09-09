package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the user actually asked about, as a handful of words, worked out on this device.
 *
 * <p><b>Why ranking needed this.</b> Beta 2 scored an image on its address, its host and the
 * page's own description - none of which knows what the question was. On a university publication
 * about a spider, the institution's logo and a photograph of the spider score almost identically,
 * because "is this a picture of the thing that was asked about" was never a question being asked.
 * It is now, and it is the single strongest signal in the ranking: an image captioned "female
 * northern black widow" beats one captioned "Virginia Tech" by a wide margin, without Orbit
 * knowing anything about either subject.
 *
 * <p><b>Local, and gone immediately.</b> These tokens are derived from the prompt on the device,
 * used for the length of one discovery attempt, and dropped. They are never sent anywhere - the
 * only things that leave the device are ordinary GETs for pages the answer already cited - they are
 * never persisted, and they are deliberately excluded from {@link RichAnswerTrace}, which records
 * technical stages rather than what somebody said. The diagnostics report shows that alt text
 * scored well; it never shows the words it matched.
 *
 * <p>Pure text. No network, no {@code Context}, no model call. Ranking images with a second AI
 * request would double the cost of every web answer to decide something a list of words decides
 * well enough.
 */
public final class RichAnswerSubject {

    /**
     * How many subject words are kept.
     *
     * <p>Six. A subject is a noun phrase - "northern black widow", "european robin", "eiffel
     * tower" - and past about six words a prompt has stopped naming a thing and started describing
     * a task, at which point extra tokens only add ways to match something irrelevant.
     */
    public static final int MAX_TOKENS = 6;

    /** The shortest word worth matching on. Two-letter words match everything. */
    private static final int MIN_TOKEN_CHARS = 3;

    /**
     * Words that are in the question without being what the question is about.
     *
     * <p>Three kinds, and all three matter. Ordinary English stop words; the vocabulary of asking
     * ("search", "describe", "show", "explain"); and the vocabulary of this feature in particular
     * ("photo", "image", "inline", "sourced"), which is the trap - a prompt that says "include one
     * useful sourced photo" would otherwise make "photo" a subject word and rank every image on
     * every page as a perfect match.
     */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "the", "and", "for", "with", "that", "this", "what", "which", "who", "whom", "whose",
            "how", "why", "when", "where", "are", "was", "were", "been", "being", "have", "has",
            "had", "does", "did", "doing", "you", "your", "yours", "can", "could", "would",
            "should", "will", "shall", "may", "might", "must", "into", "onto", "from", "about",
            "than", "then", "them", "they", "their", "there", "here", "some", "any", "all", "each",
            "its", "it's", "not", "but", "out", "off", "over", "under", "more", "most", "much",
            "very", "just", "also", "like", "likes", "such", "one", "two", "get", "got", "give",
            "make", "made", "use", "used", "using", "want", "need", "please", "thanks",
            "search", "searching", "web", "internet", "online", "google", "browser", "link",
            "describe", "description", "explain", "tell", "show", "showing", "shown", "display",
            "answer", "answers", "response", "reply", "orbit", "assistant",
            "picture", "pictures", "photo", "photos", "photograph", "photographs",
            // Beta 9. "Show me pics of a mallard duck" used to make "pic" a subject word, so any
            // candidate whose path contained it scored as a subject match - which is precisely the
            // evidence the new candidate gate leans on. A word for a picture is never the subject.
            "pic", "pics", "snapshot", "snapshots", "shot", "shots", "graphic", "graphics",
            "visual", "visuals", "different", "difference", "differences", "between",
            "image", "images", "inline", "sourced", "source", "sources", "caption", "captions",
            "look", "looks", "looking", "appearance", "including", "include", "included",
            "important", "useful", "good", "best", "trustworthy", "preferably", "features",
            "feature", "identify", "identifying", "identification", "info", "information",
            "details", "detail", "quick", "short", "brief", "list", "example", "examples"));

    private RichAnswerSubject() {}

    /**
     * The subject words of a prompt, in the order they were written, bounded and de-duplicated.
     *
     * <p>Order is kept because the first surviving words are almost always the subject: strip the
     * asking vocabulary out of "Search the web and describe what a Northern black widow looks
     * like" and what is left, in order, is {@code northern black widow}.
     */
    public static List<String> tokensOf(String prompt) {
        List<String> tokens = new ArrayList<>();
        if (prompt == null || prompt.isEmpty()) return tokens;
        Set<String> seen = new HashSet<>();
        // Anything that is not a letter or a digit separates words, so punctuation, quotes and
        // hyphenation all split rather than producing tokens nothing will ever match.
        for (String raw : prompt.toLowerCase(Locale.US).split("[^\\p{L}\\p{N}]+")) {
            if (tokens.size() >= MAX_TOKENS) break;
            String word = singular(raw);
            if (word.length() < MIN_TOKEN_CHARS || word.length() > 24) continue;
            if (STOP_WORDS.contains(word)) continue;
            // A bare number is a date, a quantity or a model year; it names nothing visual.
            if (word.chars().allMatch(Character::isDigit)) continue;
            if (seen.add(word)) tokens.add(word);
        }
        return tokens;
    }

    /**
     * How well a piece of page text describes the subject, as a bounded bonus.
     *
     * <p>Distinct tokens rather than occurrences, so a caption that repeats one word ten times does
     * not outrank one that names the whole subject. Capped, because this is one signal among
     * several and a long alt attribute that happens to contain every word should not be able to
     * drown out the fact that the image is 40 pixels wide.
     */
    public static int matchScore(List<String> tokens, String text) {
        if (tokens == null || tokens.isEmpty() || text == null || text.isEmpty()) return 0;
        String haystack = text.toLowerCase(Locale.US);
        int matched = 0;
        for (String token : tokens) if (containsWord(haystack, token)) matched++;
        return Math.min(MATCH_CAP, matched * PER_TOKEN);
    }

    /** What one matched subject word is worth. */
    static final int PER_TOKEN = 14;
    /** The most the subject signal can contribute. */
    static final int MATCH_CAP = 42;

    /** Whether any subject word appears in this text at all. */
    public static boolean matchesAny(List<String> tokens, String text) {
        return matchScore(tokens, text) > 0;
    }

    /**
     * Whether {@code token} appears in {@code haystack} as a word rather than inside another one.
     *
     * <p>Substring matching would let "art" match "Bartholomew" and "widow" match "widowmaker",
     * which is the difference between a signal and noise. A trailing plural or possessive still
     * matches, because "black widows" is the same subject.
     */
    static boolean containsWord(String haystack, String token) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(token, from);
            if (at < 0) return false;
            boolean startsCleanly = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int after = at + token.length();
            boolean endsCleanly = after >= haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(after))
                    // "widows", "widow's": one trailing plural letter is the same word.
                    || (haystack.charAt(after) == 's'
                        && (after + 1 >= haystack.length()
                            || !Character.isLetterOrDigit(haystack.charAt(after + 1))));
            if (startsCleanly && endsCleanly) return true;
            from = at + 1;
        }
    }

    /**
     * A crude singular, so "spiders" in a prompt matches "spider" in a caption.
     *
     * <p>Deliberately three rules rather than a stemmer. Over-stemming turns distinct subjects into
     * the same token and costs more accuracy than it buys, and a stemming library is a large
     * dependency for a feature that ranks photographs.
     */
    static String singular(String word) {
        if (word == null) return "";
        String text = word.trim();
        if (text.length() > 4 && text.endsWith("ies")) return text.substring(0, text.length() - 3) + "y";
        if (text.length() > 4 && text.endsWith("es")
                && (text.endsWith("ches") || text.endsWith("shes") || text.endsWith("xes"))) {
            return text.substring(0, text.length() - 2);
        }
        if (text.length() > 3 && text.endsWith("s") && !text.endsWith("ss")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }
}
