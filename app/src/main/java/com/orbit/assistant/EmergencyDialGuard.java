package com.orbit.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The boundary between Orbit deciding to dial an emergency number and Android actually being asked
 * to.
 *
 * <p>It exists because of something a real device did. A model answering a question about personal
 * safety wrote sensible advice and also returned a {@code DIAL} action for 911 with
 * {@code requiresConfirmation} false, and Orbit did what it was told: the dialer opened, on its
 * own, with 911 in it. Nothing had gone wrong technically - every layer behaved exactly as
 * designed - and that is the point. "Call 911 if you are in danger" is advice; opening a dialer is
 * an act. A pipeline in which prose can become the act, with no human in between, is wrong however
 * correct each of its parts is.
 *
 * <p>So this is a hard gate rather than a policy, and it lives in the shared action layer rather
 * than in one screen or one provider. {@link DeviceActionExecutor} will not construct a dialer
 * Intent for a protected number without a grant issued here, and a grant is only ever issued by a
 * person tapping a confirmation. That means it holds for the cloud provider, for Orbit Local, for
 * the deterministic routers, for a saved Routine, for a widget, for a Quick Settings tile, and for
 * anything added later that reaches the executor - none of them can bypass it, because none of
 * them is asked.
 *
 * <p>What it deliberately does <em>not</em> do is make Orbit less able to help. The assistant may
 * still say "call 911" as often and as plainly as it should; recommending help and performing an
 * external action are different things, and separating them is the whole design. The dialer still
 * opens with {@link android.content.Intent#ACTION_DIAL}, never {@code ACTION_CALL}, so even after
 * confirmation the call itself belongs to Android's dialer and to the user.
 */
public final class EmergencyDialGuard {

    /** Not a protected number. */
    public static final String CATEGORY_NONE = "";
    /** Emergency services. */
    public static final String CATEGORY_EMERGENCY = "emergency";
    /** A crisis or suicide-prevention line. */
    public static final String CATEGORY_CRISIS = "crisis";

    /**
     * The protected numbers, by exact normalized digits.
     *
     * <p>Deliberately short and deliberately honest. This is not a worldwide emergency-number
     * database and Orbit does not claim one: it is the set Orbit has actually implemented and
     * tested, which for this release is the two that matter most on the device it runs on. The map
     * is the extension point - adding a country's number later is one entry and one test, and
     * nothing else in the pipeline has to change.
     */
    private static final Map<String, String> PROTECTED;
    static {
        Map<String, String> numbers = new LinkedHashMap<>();
        numbers.put("911", CATEGORY_EMERGENCY);
        numbers.put("988", CATEGORY_CRISIS);
        PROTECTED = Collections.unmodifiableMap(numbers);
    }

    private EmergencyDialGuard() {}

    /**
     * A dialable number reduced to the digits a phone would actually dial.
     *
     * <p>Formatting is noise: {@code 911}, {@code 9-1-1}, {@code 9 1 1} and {@code (911)} are one
     * number written four ways, and a gate that only recognised the plainest of them would be a
     * gate in name only. A leading {@code +} is kept as part of the number's identity so an
     * international number is never mistaken for a short code.
     *
     * <p>What survives normalization is compared <em>whole</em>. That is the other half of getting
     * this right: {@code 1911} contains "911" and is not 911, and a substring test would route an
     * ordinary number into an emergency confirmation - which is its own kind of harm, because a
     * gate that fires on innocent numbers is a gate people learn to tap through.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.toLowerCase(Locale.US).startsWith("tel:")) value = value.substring(4);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else if (ch == '+' && digits.length() == 0) {
                digits.append(ch);
            } else if (ch == '-' || ch == ' ' || ch == '.' || ch == '(' || ch == ')'
                    || ch == ' ' || ch == '‑' || ch == '–' || ch == '/') {
                // Ordinary separators people and models write. Skipped, never treated as digits.
                continue;
            } else {
                // Anything else - a letter, a pause character, a wildcard - means this is not a
                // plain number, and guessing at what it dials would be worse than not guessing.
                return "";
            }
        }
        return digits.toString();
    }

    /** The category of a written number, or {@link #CATEGORY_NONE}. Whole-number match only. */
    public static String categoryFor(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return CATEGORY_NONE;
        String category = PROTECTED.get(normalized);
        return category == null ? CATEGORY_NONE : category;
    }

    public static boolean isProtected(String raw) {
        return !CATEGORY_NONE.equals(categoryFor(raw));
    }

    /** Every protected number Orbit currently implements. For Diagnostics and tests. */
    public static Map<String, String> protectedNumbers() { return PROTECTED; }

    // ---- what an action means -------------------------------------------------------------------

    /** True for a DIAL action naming a protected number. */
    public static boolean isProtectedDialAction(AssistantReply.Action action) {
        return !CATEGORY_NONE.equals(categoryForAction(action));
    }

    /** The protected category of a DIAL action, or {@link #CATEGORY_NONE}. */
    public static String categoryForAction(AssistantReply.Action action) {
        return categoryFor(numberForAction(action));
    }

    /**
     * The number a DIAL action would dial, or empty for any other action.
     *
     * <p>Only {@code DIAL} carries a literal number. {@code DIAL_CONTACT} names a person, and what
     * that resolves to is not known until the executor reads the contact - so that case is caught
     * at the executor, on the resolved number, rather than guessed at here.
     */
    public static String numberForAction(AssistantReply.Action action) {
        if (action == null) return "";
        if (!"DIAL".equalsIgnoreCase(action.type.trim())) return "";
        if (action.params == null) return "";
        return action.params.optString("number", "");
    }

    /**
     * Whether an action must be confirmed no matter what asked for it.
     *
     * <p>Read by {@link AssistantReply.Action} itself, so the requirement is a property of the
     * action rather than something each execution path has to remember. A model that omits
     * {@code requiresConfirmation}, a restored backup, a hand-built action, or a saved Routine all
     * arrive at the same answer.
     */
    public static boolean alwaysConfirms(String type, org.json.JSONObject params) {
        if (type == null || !"DIAL".equalsIgnoreCase(type.trim())) return false;
        if (params == null) return false;
        return isProtected(params.optString("number", ""));
    }

    // ---- the grant ------------------------------------------------------------------------------

    /**
     * The one live confirmation, or null.
     *
     * <p>There is at most one, because there is at most one confirmation on screen. Arming a new
     * one supersedes any older one outright, which is what makes a dialog left over from a previous
     * turn harmless: its Confirm finds itself superseded and does nothing at all.
     *
     * <p>In memory and never persisted, so a process death or an Activity recreation leaves no
     * grant behind. The default state is always "not confirmed", which is the only safe default a
     * gate like this can have.
     */
    private static volatile Confirmation armed;

    /** Granted permission to dial exactly one protected number exactly once. */
    private static volatile String grantedNumber = "";

    /**
     * One pending confirmation, bound to the action, the number, and the turn that produced it.
     */
    public static final class Confirmation {
        /** The action being confirmed. Identity, so a different action's dialog cannot answer. */
        private final AssistantReply.Action action;
        /** Who armed it: a conversation or overlay invocation id. */
        private final String owner;
        /** The exact normalized number, so a grant can never widen to a different one. */
        public final String number;
        /** "emergency" or "crisis". */
        public final String category;
        private boolean settled;

        private Confirmation(AssistantReply.Action action, String owner, String number,
                             String category) {
            this.action = action;
            this.owner = owner == null ? "" : owner;
            this.number = number;
            this.category = category;
        }

        /** The number as it should be read out and shown. Orbit never reformats it. */
        public String displayNumber() { return number; }

        /**
         * Issues the single grant this confirmation is worth.
         *
         * @return true only for the first call on a confirmation that is still current. A duplicate
         *         callback, a double tap, or a dialog superseded by a newer turn all return false
         *         and issue nothing.
         */
        public synchronized boolean confirm() {
            if (settled) return false;
            settled = true;
            synchronized (EmergencyDialGuard.class) {
                if (armed != this) return false;
                grantedNumber = number;
                armed = null;
            }
            return true;
        }

        /** Withdraws the confirmation without issuing anything. Cancelling grants nothing, ever. */
        public synchronized void cancel() {
            settled = true;
            synchronized (EmergencyDialGuard.class) {
                if (armed == this) armed = null;
            }
        }

        public boolean isFor(AssistantReply.Action other) { return action == other; }

        public String owner() { return owner; }
    }

    /**
     * Arms a confirmation for a protected DIAL, superseding any confirmation still outstanding.
     *
     * @param owner the conversation or overlay invocation this confirmation belongs to, so a
     *              Beta report can tell where it happened and a stale one is visibly stale.
     * @return the confirmation, or null when this action is not a protected dial.
     */
    public static Confirmation arm(AssistantReply.Action action, String owner) {
        String number = normalize(numberForAction(action));
        String category = categoryFor(number);
        if (CATEGORY_NONE.equals(category)) return null;
        Confirmation confirmation = new Confirmation(action, owner, number, category);
        synchronized (EmergencyDialGuard.class) {
            // Whatever was outstanding is now stale by definition: a newer turn has produced a
            // newer question, and only one of them can be the one the user is looking at.
            armed = confirmation;
            grantedNumber = "";
        }
        return confirmation;
    }

    /**
     * Spends the grant for one dial, if there is one for exactly this number.
     *
     * <p>Called by the executor immediately before it would build the Intent, and it is the whole
     * gate: no grant means no Intent. One-shot, so a callback that fires twice dials once, and the
     * number must match exactly, so a grant for 988 cannot be spent on 911.
     */
    public static synchronized boolean consumeGrant(String rawNumber) {
        String normalized = normalize(rawNumber);
        if (normalized.isEmpty() || grantedNumber.isEmpty()) return false;
        if (!grantedNumber.equals(normalized)) return false;
        grantedNumber = "";
        return true;
    }

    /** True while a confirmation is waiting for an answer. */
    public static synchronized boolean hasArmedConfirmation() { return armed != null; }

    /** Forgets every pending confirmation and grant. Used at lifecycle edges and by tests. */
    public static synchronized void reset() {
        armed = null;
        grantedNumber = "";
    }

    // ---- what the user is asked -----------------------------------------------------------------

    /**
     * The confirmation's title.
     *
     * <p>Plain and specific. The number is stated because that is the fact the user is being asked
     * about, and there is no urgency language, no warning styling, and no drama: someone reaching
     * this may be having the worst evening of their life, and a calm question is the respectful
     * thing to put in front of them.
     */
    public static String titleFor(String number) {
        return "Call " + number + "?";
    }

    /** The confirmation's body. Says exactly what the button will do, and no more. */
    public static String messageFor(String number) {
        return "Do you want to open the dialer for " + number + "?";
    }

    /** The affirmative button. Names the real consequence: a dialer opens, not a call is placed. */
    public static String confirmLabel() { return "Open dialer"; }

    public static String cancelLabel() { return "Cancel"; }
}
