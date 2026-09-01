package com.orbit.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What Orbit is allowed to say about an action, given how far that action has actually got.
 *
 * <p>This exists because of a sentence a real device produced. Asked to dial 911, Orbit answered
 * "Opening the dialer for 911. If you can, stay on the line..." and <em>then</em> showed the
 * confirmation. Every part behaved correctly - the gate held, no Intent was built, nothing was
 * dialled without permission - and the user was still told something untrue about their own phone
 * at the exact moment truthfulness mattered most. Someone reading that sentence in an emergency
 * would reasonably put the phone to their ear.
 *
 * <p>The cause is a gap rather than a bug: prose and execution were produced by different things
 * and never compared. A model writes its answer before Orbit decides an action needs confirming,
 * so it narrates the action it asked for, not the one that happened. Nothing downstream knew that
 * the sentence and the action had drifted apart.
 *
 * <p>So an action has states here, and each state has language that belongs to it:
 *
 * <ul>
 *   <li><b>proposed / awaiting confirmation</b> - the user has been asked and has not answered.
 *       Only conditional language is true: "I can open the dialer for 911."</li>
 *   <li><b>confirmed</b> - the user said yes; the executor has not run yet.</li>
 *   <li><b>executed</b> - Android has the Intent. Past tense becomes true here and nowhere
 *       earlier: "Opened the dialer for 911."</li>
 *   <li><b>cancelled</b> - nothing happened, and nothing may be said as though it did.</li>
 * </ul>
 *
 * <p>The correction is applied to protected dials only. An ordinary action that confirms - a
 * calendar write, a routine - is already described by its own card, and rewriting every reply in
 * the app to hedge about actions would make Orbit worse at the ninety-nine per cent of things
 * where the wording was never wrong.
 */
public final class ActionNarration {

    /** Proposed and waiting on a person. Nothing has been done. */
    public static final String STATE_AWAITING_CONFIRMATION = "awaiting_confirmation";
    /** The person said yes. The executor has not necessarily run. */
    public static final String STATE_CONFIRMED = "confirmed";
    /** Android has been handed the Intent. */
    public static final String STATE_EXECUTED = "executed";
    /** The person said no, or walked away. */
    public static final String STATE_CANCELLED = "cancelled";

    /**
     * First-person claims that an external action has already been carried out.
     *
     * <p>Matched against lower-cased sentences, and deliberately narrow. "Call 911 if you are in
     * danger" and "Calling 911 is the right thing to do" are advice and must survive untouched -
     * the whole point of the emergency design is that Orbit keeps giving that advice freely. What
     * is caught is Orbit describing its own completed act, which is the only thing that can be
     * false before a confirmation.
     */
    private static final String[] EXECUTION_CLAIMS = {
            "opening the dialer",
            "opening the phone dialer",
            "opening your dialer",
            "opening up the dialer",
            "opened the dialer",
            "dialer opened",
            "dialer is open",
            "i'm opening",
            "i am opening",
            "im opening",
            "i've opened",
            "i have opened",
            "i opened",
            "i'm dialing", "i am dialing", "i'm dialling", "i am dialling",
            "i've dialed", "i have dialed", "i dialed", "i've dialled", "i dialled",
            "i'm calling", "i am calling", "i've called", "i have called",
            "i'm placing the call", "i am placing the call", "placing the call",
            "starting the call", "i'm starting the call",
    };

    private ActionNarration() {}

    /** The only true thing to say about a protected dial nobody has confirmed yet. */
    public static String awaitingConfirmationText(String number) {
        return "I can open the dialer for " + number + ". Confirm below.";
    }

    /** Said after Android has actually been handed the Intent, and never before. */
    public static String dialerOpenedText(String number) {
        return "Opened the dialer for " + number;
    }

    /**
     * Wraps a callback so no reply can claim a protected dial already happened.
     *
     * <p>Placed on the one path every answer travels - deterministic routers, Orbit Local, and the
     * cloud provider all deliver through here - rather than on a screen. That is the difference
     * between fixing this and hiding it: a correction made in full chat would leave the Side-button
     * overlay saying the untrue thing, and a correction made in both would still miss the next
     * surface. Placed here, a provider Orbit has not written yet inherits it.
     */
    public static AssistantClient.Callback guard(AssistantClient.Callback downstream) {
        if (downstream == null) return null;
        return new AssistantClient.Callback() {
            @Override public void onDelta(String text) { downstream.onDelta(text); }

            @Override public void onThinking(ThinkingUpdate update) {
                downstream.onThinking(update);
            }

            @Override public void onSuccess(AssistantReply reply) {
                downstream.onSuccess(withTruthfulActionState(reply));
            }

            @Override public void onError(String message) { downstream.onError(message); }
        };
    }

    /**
     * The same reply, with any claim that a still-unconfirmed protected dial has happened removed.
     *
     * <p>Surgical rather than wholesale. The offending sentence goes and the rest of the answer -
     * which in a safety reply is the part that actually helps - is left exactly as written. A
     * conditional sentence replaces it only when something was removed, so an answer that was
     * already careful is not padded with a line the confirmation card is about to say anyway.
     */
    public static AssistantReply withTruthfulActionState(AssistantReply reply) {
        if (reply == null) return null;
        List<String> numbers = awaitingProtectedNumbers(reply);
        if (numbers.isEmpty()) return reply;
        String corrected = withoutExecutionClaims(reply.text, numbers.get(0));
        if (corrected.equals(reply.text == null ? "" : reply.text)) return reply;
        return new AssistantReply(corrected, reply.actions, reply.memoryUsage,
                reply.suggestedMemoryText, reply.suggestedMemoryCategory);
    }

    /** The protected numbers this reply is about to ask the user to confirm, in order. */
    public static List<String> awaitingProtectedNumbers(AssistantReply reply) {
        Set<String> numbers = new LinkedHashSet<>();
        if (reply == null || reply.actions == null) return new ArrayList<>(numbers);
        for (AssistantReply.Action action : reply.actions) {
            if (!EmergencyDialGuard.isProtectedDialAction(action)) continue;
            String number = EmergencyDialGuard.normalize(
                    EmergencyDialGuard.numberForAction(action));
            if (!number.isEmpty()) numbers.add(number);
        }
        return new ArrayList<>(numbers);
    }

    /**
     * Drops sentences claiming the dialer is open, and states the conditional instead.
     *
     * <p>Sentence-level because that is the unit a claim lives in. Editing words inside a sentence
     * produces the sort of half-rewritten prose that reads as broken; dropping the sentence leaves
     * an answer that still reads as though a person wrote it.
     */
    public static String withoutExecutionClaims(String text, String number) {
        String original = text == null ? "" : text;
        List<String> kept = new ArrayList<>();
        boolean removedAny = false;
        for (String sentence : sentences(original)) {
            if (claimsExecution(sentence)) {
                removedAny = true;
                continue;
            }
            kept.add(sentence);
        }
        if (!removedAny) return original;
        StringBuilder rebuilt = new StringBuilder();
        for (String sentence : kept) rebuilt.append(sentence);
        String remainder = rebuilt.toString().trim();
        String conditional = awaitingConfirmationText(number);
        return remainder.isEmpty() ? conditional : conditional + " " + remainder;
    }

    /** True for a sentence that describes the dial as already done or under way. */
    public static boolean claimsExecution(String sentence) {
        if (sentence == null) return false;
        String lower = sentence.toLowerCase(Locale.US);
        for (String claim : EXECUTION_CLAIMS) {
            if (lower.contains(claim)) return true;
        }
        return false;
    }

    /**
     * Splits prose into sentences, keeping the whitespace that followed each one.
     *
     * <p>Keeping the trailing whitespace is what lets the surviving sentences be concatenated back
     * into text that still has its paragraph breaks. A newline ends a sentence as surely as a full
     * stop does, so a bulleted or line-broken answer is not treated as one enormous sentence in
     * which a single claim would condemn the whole reply.
     */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            boolean terminator = ch == '.' || ch == '!' || ch == '?' || ch == '\n';
            if (!terminator) {
                i++;
                continue;
            }
            int end = i + 1;
            // Swallow "?!", "..." and the run of spaces or newlines that separates sentences, so
            // the next one starts at its first real character.
            while (end < text.length() && isTrailing(text.charAt(end))) end++;
            out.add(text.substring(start, end));
            start = end;
            i = end;
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }

    private static boolean isTrailing(char ch) {
        return ch == '.' || ch == '!' || ch == '?' || Character.isWhitespace(ch);
    }
}
