package com.orbit.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The single decision behind Smart follow-ups: after Orbit has finished speaking a reply, should
 * the microphone reopen on its own?
 *
 * <p>Both surfaces ask this same class, so the Side-button overlay and full chat can never drift
 * into two different ideas of what a conversational reply looks like. What differs between them is
 * only the mechanics of starting recognition, which stay where they already are.
 *
 * <p>Nothing here talks to a provider. The decision is made from the reply Orbit has already
 * produced and the preferences already on the device, so Smart follow-ups adds no second model
 * call, no extra billing, and no dependence on the network being up when a turn ends.
 *
 * <h2>Ownership always wins</h2>
 * The semantic half of this class answers one question: does this reply expect the user to answer?
 * That is a prerequisite, never a permission. An utterance that was interrupted or replaced, and a
 * surface that is no longer eligible, are refused before the text is even looked at - a reply that
 * clearly asks "What time should I set it for?" still must not revive a voice turn the user has
 * already moved on from.
 *
 * <h2>Uncertainty means silence</h2>
 * Reopening the microphone is more intrusive than letting the user tap it, so anything that is not
 * clearly a request for an answer is treated as a finished reply.
 */
public final class VoiceFollowUpPolicy {
    private VoiceFollowUpPolicy() {}

    /** Why the microphone did or did not reopen. A closed set: it can carry no conversation text. */
    public enum Decision {
        /** Hands-free voice follow-ups is off, so nothing reopens the microphone. */
        MASTER_OFF("master_off", false),
        /** Smart follow-ups is off, so every eligible spoken reply reopens it, exactly as before. */
        SMART_OFF_LEGACY("smart_off_legacy", true),
        /** Smart follow-ups is on and this reply is waiting for the user to answer. */
        EXPECTS_REPLY("expects_reply", true),
        /** Smart follow-ups is on and this reply said everything it had to say. */
        COMPLETE_REPLY("complete_reply", false),
        /** The utterance was interrupted or superseded, so its completion may not act. */
        UTTERANCE_NOT_LIVE("utterance_not_live", false),
        /** The surface moved on: hidden, busy, already listening, or handed over to typing. */
        SURFACE_NOT_ELIGIBLE("surface_not_eligible", false);

        private final String reason;
        private final boolean reopens;

        Decision(String reason, boolean reopens) {
            this.reason = reason;
            this.reopens = reopens;
        }

        /** Whether the microphone should reopen. */
        public boolean reopensMicrophone() { return reopens; }

        /** A stable, closed identifier for diagnostics. Never contains user content. */
        public String reason() { return reason; }
    }

    /**
     * The whole decision, ownership first.
     *
     * <p>Deliberately ordered so that lifecycle facts are consulted before preferences and
     * preferences before text. Callers pass live state, which is what lets the same call be made
     * again from delayed work: re-asking with the state as it is when the runnable actually runs is
     * how an abandoned transition is stopped from opening the microphone behind the user.
     *
     * @param handsFreeEnabled the Hands-free voice follow-ups master preference
     * @param smartEnabled     the Smart follow-ups preference, meaningful only when the master is on
     * @param utteranceLive    this completion belongs to the utterance Orbit is still speaking
     * @param surfaceEligible  the surface can still start a voice turn right now
     * @param spokenReply      the reply that was just spoken
     */
    public static Decision decide(boolean handsFreeEnabled, boolean smartEnabled,
                                  boolean utteranceLive, boolean surfaceEligible,
                                  String spokenReply) {
        if (!utteranceLive) return Decision.UTTERANCE_NOT_LIVE;
        if (!surfaceEligible) return Decision.SURFACE_NOT_ELIGIBLE;
        if (!handsFreeEnabled) return Decision.MASTER_OFF;
        if (!smartEnabled) return Decision.SMART_OFF_LEGACY;
        return expectsReply(spokenReply) ? Decision.EXPECTS_REPLY : Decision.COMPLETE_REPLY;
    }

    // ---- The semantic half ------------------------------------------------------------------

    /** Openers that make a sentence a question even when the punctuation did not survive. */
    private static final String[] QUESTION_WORDS = {
            "what", "which", "who", "whom", "whose", "when", "where", "why", "how"
    };

    /** Auxiliaries that open a yes/no question. Only consulted when punctuation is missing. */
    private static final String[] QUESTION_AUXILIARIES = {
            "do", "does", "did", "can", "could", "will", "would", "should", "shall",
            "is", "are", "was", "were", "am", "have", "has", "had", "may", "might"
    };

    /**
     * Language that offers further help rather than waiting for an answer. Orbit is not blocked on
     * any of these, so the microphone stays shut even when one of them ends in a question mark.
     */
    private static final String[] CLOSING_OFFERS = {
            "if you want", "if you'd like", "if you would like", "if you like", "if you prefer",
            "let me know if", "just let me know", "just say", "feel free", "anything else",
            "something else", "happy to help", "hope that helps", "let me know when you"
    };

    /** Imperatives that genuinely hand the turn back, as opposed to offering to keep going. */
    private static final String[] REQUEST_LEADS = {
            "tell me ", "tell us ", "let me know ", "say "
    };

    /**
     * Whether this finished reply is waiting for the user to answer.
     *
     * <p>Only the last thing Orbit actually said is considered. A question Orbit asks and then
     * answers itself is rhetorical, and a reply that ends on a statement has finished, however
     * many question marks appeared earlier in it. Questions inside quotes, code, links, and
     * blockquotes are removed first, because Orbit is not waiting on those either.
     */
    public static boolean expectsReply(String spokenReply) {
        if (spokenReply == null) return false;
        String cleaned = removeNonSpokenSources(spokenReply);
        List<String> sentences = sentences(cleaned);
        if (sentences.isEmpty()) return false;

        String last = sentences.get(sentences.size() - 1).toLowerCase(Locale.US).trim();
        if (last.isEmpty()) return false;
        if (containsAny(last, CLOSING_OFFERS)) return false;

        String core = stripLeadingPoliteness(last);
        if (isRequestImperative(core)) return true;

        boolean endsWithQuestion = last.endsWith("?");
        if (endsWithQuestion) return true;

        // Voice and provider output sometimes drops the final mark. A sentence that ends with no
        // terminator at all, and opens the way a question opens, is still a question. A sentence
        // that ends in a full stop has declared itself a statement and is taken at its word.
        boolean hasTerminator = last.endsWith(".") || last.endsWith("!");
        return !hasTerminator && (startsWithWord(core, QUESTION_WORDS)
                || startsWithWord(core, QUESTION_AUXILIARIES));
    }

    /**
     * Removes the parts of a reply Orbit is quoting rather than asking: fenced and inline code,
     * links, quoted speech, and blockquotes. A question mark inside any of them belongs to the
     * source material, not to the turn.
     */
    private static String removeNonSpokenSources(String text) {
        String out = text;
        out = out.replaceAll("(?s)```.*?```", " ");
        out = out.replaceAll("(?s)`[^`]*`", " ");
        out = out.replaceAll("(?i)\\b(?:https?://|www\\.)\\S+", " ");
        out = out.replaceAll("(?s)\"[^\"]*\"", " ");
        out = out.replaceAll("(?s)“[^”]*”", " ");
        out = out.replaceAll("(?m)^\\s*>.*$", " ");
        return out;
    }

    /** Splits into sentences, treating a line break as an ending in its own right. */
    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                addIfPresent(out, current);
                continue;
            }
            current.append(c);
            if (c == '.' || c == '!' || c == '?') {
                // Keep a run of terminators with its sentence, so "..." and "?!" stay intact.
                while (i + 1 < text.length() && isTerminator(text.charAt(i + 1))) {
                    current.append(text.charAt(++i));
                }
                addIfPresent(out, current);
            }
        }
        addIfPresent(out, current);
        return out;
    }

    private static boolean isTerminator(char c) { return c == '.' || c == '!' || c == '?'; }

    private static void addIfPresent(List<String> out, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) out.add(value);
        current.setLength(0);
    }

    private static String stripLeadingPoliteness(String sentence) {
        String out = sentence;
        for (String lead : new String[]{"please ", "so ", "okay, ", "ok, ", "alright, "}) {
            if (out.startsWith(lead)) out = out.substring(lead.length()).trim();
        }
        return out;
    }

    /**
     * Whether the sentence tells the user to supply something, rather than mentioning that they
     * could. "Tell me which one you want" is a handover; "let me know if you need more" is not,
     * which is why the lead has to be followed by an actual question word.
     */
    private static boolean isRequestImperative(String core) {
        for (String lead : REQUEST_LEADS) {
            if (core.startsWith(lead)) {
                return startsWithWord(core.substring(lead.length()).trim(), QUESTION_WORDS);
            }
        }
        return core.startsWith("choose ") || core.startsWith("pick ")
                || core.startsWith("select ") || core.startsWith("confirm");
    }

    private static boolean startsWithWord(String text, String[] words) {
        for (String word : words) {
            if (text.equals(word)) return true;
            if (text.startsWith(word) && text.length() > word.length()) {
                char next = text.charAt(word.length());
                if (!Character.isLetterOrDigit(next) && next != '\'') return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String[] needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
