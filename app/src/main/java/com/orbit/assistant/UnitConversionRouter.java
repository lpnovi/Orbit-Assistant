package com.orbit.assistant;

import android.content.Context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * General-purpose unit conversion, deterministic and offline.
 *
 * <p>The kitchen keeps its own router. {@link KitchenMathRouter} runs first and owns cooking volume,
 * cooking mass, oven temperatures and recipe scaling, with the presentation rules cooks need. This
 * one answers the dimensions cooking has no opinion about — length, speed, area and digital storage
 * — through {@link MeasureUnit}, which shares Orbit's one exact rational and shares no spelling with
 * the kitchen table at all.
 *
 * <p>Recognition is conservative for the same reason the kitchen router is. "A 5 foot table" is a
 * description; "5 feet in metres" is a request. The difference is an explicit connective and a
 * named target unit, and nothing short of that is intercepted.
 */
public final class UnitConversionRouter {
    private UnitConversionRouter() {}

    /** Past this, a message is a conversation that mentions a measurement. */
    private static final int MAX_LENGTH = 140;

    private static final String UNITS = MeasureUnit.aliasAlternation();
    private static final String AMOUNT = "(\\d+(?:\\.\\d+)?|\\.\\d+)";
    /** Words that mean "express this as". Short on purpose: an implied target is not a request. */
    private static final String CONNECTIVE = "in|to|into|as|equals";
    private static final String UNIT_TOKEN = "(?<![a-z])(" + UNITS + ")(?![a-z])";

    /** "5 feet in metres", "convert 12 mi to km", "1 GB in MiB". */
    private static final Pattern CONVERT_FORWARD = Pattern.compile(
            AMOUNT + "\\s*" + UNIT_TOKEN + "\\s+(?:" + CONNECTIVE + ")\\s+" + UNIT_TOKEN);

    /** "How many cm are in 3 inches?". */
    private static final Pattern CONVERT_HOW_MANY = Pattern.compile(
            "how (?:many|much)\\s+" + UNIT_TOKEN + "[^0-9]{0,20}" + AMOUNT + "\\s*" + UNIT_TOKEN);

    /**
     * Words that belong to Orbit's device commands or to the kitchen.
     *
     * <p>A message carrying one of them is about the phone or about cooking, and must never be
     * answered here even if it also contains something that looks like a measurement.
     */
    private static final Pattern OTHER_TERRITORY = Pattern.compile(
            "\\b(timer|timers|alarm|alarms|brightness|flashlight|torch|volume|ringer|"
                    + "do not disturb|remind|reminder|routine|routines|battery|charging)\\b");

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

    /** The complete deterministic answer, or null. Free of {@link Context} on purpose. */
    public static String answer(String raw) {
        String q = normalize(raw);
        if (q.isEmpty() || q.length() > MAX_LENGTH) return null;
        if (OTHER_TERRITORY.matcher(q).find()) return null;
        if (LanguageNormalizer.isConceptualQuestion(q)) return null;

        Matcher howMany = CONVERT_HOW_MANY.matcher(q);
        if (howMany.find()) return convert(howMany.group(2), howMany.group(3), howMany.group(1));
        Matcher forward = CONVERT_FORWARD.matcher(q);
        if (forward.find()) return convert(forward.group(1), forward.group(2), forward.group(3));
        return null;
    }

    private static String convert(String amountText, String fromText, String toText) {
        KitchenQuantity amount = KitchenQuantity.parse(amountText);
        MeasureUnit from = MeasureUnit.fromAlias(fromText);
        MeasureUnit to = MeasureUnit.fromAlias(toText);
        if (amount == null || from == null || to == null) return null;
        // "5 km in km" asks for nothing, and a length is not a speed. Neither is answered.
        if (from == to) return null;
        if (from.dimension() != to.dimension()) return null;
        return MeasureMath.conversionLine(amount, from, to);
    }

    // ---- normalization ---------------------------------------------------------------------------

    private static final Pattern WORD_NUMBER_BEFORE_UNIT = Pattern.compile(
            "\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|"
                    + "thirty|forty|fifty|sixty|ninety)\\s+(?=(?:" + UNITS + ")(?![a-z]))");

    /**
     * The shared tidying, plus the notation this router needs folded into plain words.
     *
     * <p>Superscript twos become the digit so {@code m²} and {@code m2} are one spelling, and a
     * small written count in front of a measure becomes a number so "five miles in km" is the same
     * request as "5 miles in km".
     */
    static String normalize(String raw) {
        String value = LanguageNormalizer.stripPoliteness(LanguageNormalizer.normalize(raw));
        if (value.isEmpty()) return "";
        value = value.replace("²", "2").replace("^2", "2");
        value = value.replace("=", " equals ");
        value = value.replaceAll("\\bsq\\.\\s*", "sq ");
        value = value.replaceAll("^convert\\s+", "");
        value = replaceWordNumbers(value);
        return value.replaceAll("\\s+", " ").trim();
    }

    /** "five miles" becomes "5 miles", but only directly in front of a measure. */
    private static String replaceWordNumbers(String value) {
        Matcher m = WORD_NUMBER_BEFORE_UNIT.matcher(value);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int number = LanguageNormalizer.wordNumber(m.group(1));
            m.appendReplacement(out, number > 0 ? number + " " : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
