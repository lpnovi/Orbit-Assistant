package com.orbit.assistant;

import android.content.Context;

import java.util.regex.Pattern;

/**
 * The deterministic calculator, wired into Orbit's provider-independent pipeline.
 *
 * <p>Sits beside {@link KitchenMathRouter} and follows exactly the same rule: arithmetic has one
 * right answer, so Orbit gives it itself, instantly, offline, and identically under every provider.
 * A round trip to a language model to be told what seventeen plus twenty-eight is helps nobody.
 *
 * <p>Its restraint is the interesting part. After the polite wrapper and the everyday lead-ins are
 * removed, what is left must be <em>nothing but</em> an expression: digits, operators, parentheses,
 * percent signs, and the single word {@code sqrt}. One stray letter and the message is a sentence
 * that happens to contain numbers, which is a question for the provider. "I spent 40 on lunch and
 * 12 on coffee" therefore never becomes "52".
 */
public final class CalculatorRouter {
    private CalculatorRouter() {}

    /** Past this, a message is prose that mentions numbers rather than a sum. */
    private static final int MAX_LENGTH = 120;

    /** The ways people ask for a sum before writing one. Removed, never required. */
    private static final Pattern LEAD_IN = Pattern.compile(
            "^(?:what(?:'s| is| are)?|whats|how much is|how many is|calculate|calc|compute|"
                    + "work out|figure out|solve|evaluate|equals|tell me)\\s+");

    /** Everything that is left once the words have become symbols. */
    private static final Pattern ARITHMETIC_ONLY = Pattern.compile("^[0-9 .+\\-*/^%()x]+$");

    /** At least one digit has to be in there for this to be a sum at all. */
    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");

    /** A percentage on its own is a figure, not a question. */
    private static final Pattern BARE_PERCENTAGE = Pattern.compile("^[0-9. ]+%$");

    // ---- Orbit pipeline entry points ------------------------------------------------------------

    /** Side-effect-free recognition, used to decide a request needs no network. */
    public static boolean canHandle(String raw) {
        return answer(raw) != null;
    }

    /** The reply Orbit gives, or null when this is a question for the AI provider. */
    public static AssistantReply tryHandle(Context context, String raw) {
        String text = answer(raw);
        return text == null ? null : new AssistantReply(text);
    }

    /**
     * The complete deterministic answer, or null.
     *
     * <p>Free of {@link Context} on purpose: arithmetic depends on nothing about the device, so it
     * can be exercised directly and cannot drift from what the router replies.
     */
    public static String answer(String raw) {
        String cleaned = clean(raw);
        if (cleaned.isEmpty()) return null;

        String expression = symbolize(cleaned);
        if (expression.isEmpty()) return null;
        if (!ARITHMETIC_ONLY.matcher(expression.replace("sqrt", " ")).matches()) return null;
        if (!HAS_DIGIT.matcher(expression).find()) return null;
        // "50%" is a figure someone stated, not a sum they asked Orbit to do.
        if (BARE_PERCENTAGE.matcher(expression).matches()) return null;

        OrbitCalculator.Outcome outcome = OrbitCalculator.evaluate(expression);
        if (outcome.hasValue()) {
            String result = OrbitCalculator.format(outcome.value);
            if (result.isEmpty()) return null;
            String display = display(cleaned);
            return result.startsWith("≈ ")
                    ? display + " ≈ " + result.substring(2)
                    : display + " = " + result;
        }
        switch (outcome.refusal) {
            case DIVIDE_BY_ZERO:
                // A real answer, and the only correct one. Never a number, and never a silent pass.
                return "Dividing by zero has no answer.";
            case TOO_LARGE:
                return "That is beyond the numbers Orbit works out on the phone. Ask a provider for it.";
            default:
                return null;
        }
    }

    // ---- normalization ---------------------------------------------------------------------------

    /**
     * The message with Orbit's shared tidying applied and the lead-in removed.
     *
     * <p>Stops at that: this is still the user's own wording, and it is what the answer echoes back.
     */
    static String clean(String raw) {
        String value = LanguageNormalizer.stripPoliteness(normalizeKeepingParentheses(raw));
        if (value.isEmpty() || value.length() > MAX_LENGTH) return "";
        value = LEAD_IN.matcher(value).replaceAll("");
        // The conceptual-question rule is applied to what is left, not to the whole message.
        // "What is 17 + 28" opens exactly like "what is a derivative", and only one of them is a
        // question about a subject; removing the lead-in first is what tells them apart. Anything
        // this router genuinely must not touch - "how do I calculate a percentage" - carries no
        // lead-in it recognises, so it still reaches this check intact.
        if (LanguageNormalizer.isConceptualQuestion(value)) return "";
        value = value.replaceAll("\\s*=\\s*$", "").trim();
        return value;
    }

    /**
     * The shared tidying, minus the one rule this router cannot use.
     *
     * <p>{@link LanguageNormalizer#normalize} strips brackets, which is right everywhere else in
     * Orbit and fatal here: it would turn {@code (14 + 6) / 4} into {@code 14 + 6 / 4} and answer
     * 15.5 with complete confidence. Parentheses are grammar in an expression, so they survive and
     * the rest of the rule is unchanged.
     */
    static String normalizeKeepingParentheses(String raw) {
        if (raw == null) return "";
        String value = raw.toLowerCase(java.util.Locale.US)
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"');
        value = value.replaceAll("[?!,;\"\\[\\]]", " ");
        return value.replaceAll("\\s+", " ").trim();
    }

    /** The user's own expression, tidied only for spacing, for the left of the answer. */
    static String display(String cleaned) {
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    /**
     * Everyday arithmetic words, written as the symbols the parser reads.
     *
     * <p>A closed list. Nothing here invents an operation the user did not write, and anything not
     * on it survives as a letter, which is what disqualifies the message a moment later.
     */
    static String symbolize(String cleaned) {
        String value = cleaned;
        value = value.replace("−", "-").replace("–", "-").replace("—", "-");
        value = value.replace("×", "*").replace("÷", "/");
        value = value.replaceAll("\\bsquare root of\\b", "sqrt ");
        value = value.replaceAll("\\bsquare root\\b", "sqrt ");
        value = value.replaceAll("\\bto the power of\\b", "^");
        value = value.replaceAll("\\bsquared\\b", "^2");
        value = value.replaceAll("\\bcubed\\b", "^3");
        value = value.replaceAll("\\bmultiplied by\\b", "*");
        value = value.replaceAll("\\bdivided by\\b", "/");
        value = value.replaceAll("\\btimes\\b", "*");
        value = value.replaceAll("\\bplus\\b", "+");
        value = value.replaceAll("\\bminus\\b", "-");
        value = value.replaceAll("\\bpercent of\\b", "% *");
        value = value.replaceAll("\\bpercent\\b", "%");
        // "17% of 84" is a multiplication, and the only place "of" means anything here.
        value = value.replaceAll("%\\s*of\\b", "% *");
        value = value.replaceAll("\\s+", " ").trim();
        return value;
    }
}
