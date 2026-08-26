package com.orbit.assistant;

import android.content.Context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic cooking answers Orbit gives itself, without asking any AI provider.
 *
 * <p>Sits beside {@link LocalCommandRouter}, {@link MemoryCommandRouter} and the notification
 * queries in Orbit's provider-independent pipeline: whichever provider is active — ChatGPT, Orbit
 * Local, the relay, or a later one — a conversion that is pure arithmetic is answered here,
 * instantly, offline, and identically in full chat and the Side-button overlay.
 *
 * <p>Its most important property is restraint. A recipe question, a substitution question, a
 * food-safety question or "how do I make risotto" all need real reasoning, and this router must
 * hand every one of them straight to the provider. It therefore recognises only complete
 * conversion and scaling grammar — an amount, a unit Orbit measures with, and an explicit request
 * to convert or scale it. Anything short of that is left alone, on the same rule the rest of
 * Orbit's local routing follows: uncertain means do not intercept.
 *
 * <p>It also knows one thing it cannot do. Cups into grams depends on what is in the cup, so when
 * no ingredient is named Orbit says so in one sentence, and when an ingredient <em>is</em> named
 * the question goes to the provider rather than to an invented density table.
 */
public final class KitchenMathRouter {
    private KitchenMathRouter() {}

    /**
     * Past this, a message is a conversation that happens to contain a measurement rather than a
     * request to convert one.
     */
    private static final int MAX_LENGTH = 140;

    private static final String UNITS = KitchenUnit.aliasAlternation();
    /** {@code 12}, {@code 1.5}, {@code 3/4}, {@code 1 1/2} — every way a cook writes an amount. */
    private static final String AMOUNT =
            "(\\d+\\s+\\d+\\s*/\\s*\\d+|\\d+\\s*/\\s*\\d+|\\d+(?:\\.\\d+)?|\\.\\d+)";
    private static final String TEMPERATURE_UNITS = "fahrenheit|celsius|centigrade|f|c";
    /** Words that mean "express this as". Deliberately short: an implied target is not a request. */
    private static final String CONNECTIVE = "in|to|into|as|equals";

    private static final String UNIT_TOKEN = "(?<![a-z])(" + UNITS + ")(?![a-z])";
    private static final String TEMP_TOKEN = "(?<![a-z])(" + TEMPERATURE_UNITS + ")(?![a-z])";

    private static final Pattern TEMPERATURE = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)\\s*" + TEMP_TOKEN
                    + "[a-z\\s]{0,24}?\\b(?:" + CONNECTIVE + ")\\b[a-z\\s]{0,16}?" + TEMP_TOKEN);

    /** "2 cups to ml", "convert 12 ounces to grams", "500g in pounds". */
    private static final Pattern CONVERT_FORWARD = Pattern.compile(
            AMOUNT + "\\s*" + UNIT_TOKEN + "\\s+(?:" + CONNECTIVE + ")\\s+" + UNIT_TOKEN);

    /** "How many tablespoons are in 1/3 cup?", "How many grams is one cup?". */
    private static final Pattern CONVERT_HOW_MANY = Pattern.compile(
            "how (?:many|much)\\s+" + UNIT_TOKEN + "[^0-9]{0,20}" + AMOUNT + "\\s*" + UNIT_TOKEN);

    /** "Double 3/4 cup", "half 2 tablespoons". */
    private static final Pattern SCALE_WORD = Pattern.compile(
            "\\b(double|doubling|halve|half|triple|tripling|quadruple|quadrupling)\\s+(?:of\\s+)?"
                    + AMOUNT + "\\s*" + UNIT_TOKEN);

    /** "Scale 1 1/2 cups by 1.5". */
    private static final Pattern SCALE_BY = Pattern.compile(
            "\\bscale\\s+" + AMOUNT + "\\s*" + UNIT_TOKEN + "\\s*(?:by|times|x|×|\\*)\\s*" + AMOUNT);

    /** "3/4 cup times 1.5". */
    private static final Pattern SCALE_TIMES = Pattern.compile(
            AMOUNT + "\\s*" + UNIT_TOKEN + "\\s*(?:times|x|×|\\*)\\s*" + AMOUNT);

    /** "A recipe uses 2 tbsp for 4 people, how much for 6?". */
    private static final Pattern SCALE_SERVINGS_FOR = Pattern.compile(
            AMOUNT + "\\s*" + UNIT_TOKEN
                    + "\\s+for\\s+(\\d+)\\s*(?:people|person|servings?|portions?|guests?)\\b"
                    + ".{0,60}?\\bfor\\s+(\\d+)\\b");

    /** "This recipe serves 6 but I need 4. Scale 3 cups". */
    private static final Pattern SCALE_SERVINGS_SERVES = Pattern.compile(
            "\\bserves?\\s+(\\d+)\\b.{0,60}?"
                    + "\\b(?:need|needs|want|wants|making|make|cooking for|feeding|feed|have)\\s+"
                    + "(?:for\\s+)?(\\d+)\\b.{0,60}?\\bscale\\s+" + AMOUNT + "\\s*" + UNIT_TOKEN);

    /** The ingredient in "a cup of flour", or "a cup of the flour". */
    private static final Pattern INGREDIENT = Pattern.compile(
            "\\bof\\s+(?:the|a|an|some|my|your|our)?\\s*([a-z][a-z-]+)");
    /** Words that follow "of" without naming anything that has a weight. */
    private static final java.util.Set<String> NOT_AN_INGREDIENT = new java.util.HashSet<>(
            java.util.Arrays.asList("course", "it", "that", "this", "them", "these", "those",
                    "mine", "yours", "weight", "volume", "them", "each", "which", "what"));

    /**
     * Words that belong to Orbit's device commands. A message carrying one of them is about the
     * phone, and must never be answered as arithmetic even if it also mentions a measurement.
     */
    private static final Pattern DEVICE_TERRITORY = Pattern.compile(
            "\\b(timer|timers|alarm|alarms|brightness|flashlight|torch|volume|do not disturb|"
                    + "remind|reminder|routine|routines)\\b");

    /**
     * The one sentence Orbit says when a volume must become a weight and nothing says of what.
     *
     * <p>Guessing water here would be wrong for almost every ingredient anybody weighs.
     */
    static final String INGREDIENT_NEEDED =
            "That depends on the ingredient. One cup of flour, sugar, butter, and water all weigh "
                    + "different amounts.";

    // ---- Orbit pipeline entry points ----------------------------------------------------------

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
     * <p>Deliberately free of {@link Context}: kitchen arithmetic depends on nothing about the
     * device, so it can be exercised directly and cannot drift from what the router replies.
     */
    public static String answer(String raw) {
        String q = normalize(raw);
        if (q.isEmpty() || q.length() > MAX_LENGTH) return null;
        if (DEVICE_TERRITORY.matcher(q).find()) return null;

        String temperature = temperature(q);
        if (temperature != null) return temperature;

        String scaled = scaled(q);
        if (scaled != null) return scaled;

        return converted(q);
    }

    // ---- temperature --------------------------------------------------------------------------

    private static String temperature(String q) {
        Matcher m = TEMPERATURE.matcher(q);
        if (!m.find()) return null;
        KitchenQuantity value = KitchenQuantity.parse(m.group(1));
        if (value == null) return null;
        boolean sourceFahrenheit = isFahrenheit(m.group(2));
        boolean targetFahrenheit = isFahrenheit(m.group(3));
        // "180 C in Celsius" asks for nothing; only a real conversion is answered.
        if (sourceFahrenheit == targetFahrenheit) return null;
        return KitchenMath.temperatureLine(value, sourceFahrenheit);
    }

    private static boolean isFahrenheit(String token) {
        return "f".equals(token) || "fahrenheit".equals(token);
    }

    // ---- scaling ------------------------------------------------------------------------------

    private static String scaled(String q) {
        Matcher serves = SCALE_SERVINGS_SERVES.matcher(q);
        if (serves.find()) {
            return scaleForServings(serves.group(3), serves.group(4),
                    serves.group(1), serves.group(2));
        }
        Matcher servingsFor = SCALE_SERVINGS_FOR.matcher(q);
        if (servingsFor.find()) {
            return scaleForServings(servingsFor.group(1), servingsFor.group(2),
                    servingsFor.group(3), servingsFor.group(4));
        }
        Matcher by = SCALE_BY.matcher(q);
        if (by.find()) return scaleByFactor(by.group(1), by.group(2), by.group(3));
        Matcher times = SCALE_TIMES.matcher(q);
        if (times.find()) return scaleByFactor(times.group(1), times.group(2), times.group(3));
        Matcher word = SCALE_WORD.matcher(q);
        if (word.find()) {
            KitchenQuantity factor = wordFactor(word.group(1));
            if (factor == null) return null;
            return scaledLine(word.group(2), word.group(3), factor);
        }
        return null;
    }

    private static KitchenQuantity wordFactor(String word) {
        switch (word) {
            case "double": case "doubling": return KitchenQuantity.of(2);
            case "halve": case "half": return KitchenQuantity.of(1, 2);
            case "triple": case "tripling": return KitchenQuantity.of(3);
            case "quadruple": case "quadrupling": return KitchenQuantity.of(4);
            default: return null;
        }
    }

    private static String scaleByFactor(String amountText, String unitText, String factorText) {
        KitchenQuantity factor = KitchenQuantity.parse(factorText);
        if (factor == null || factor.signum() <= 0) return null;
        return scaledLine(amountText, unitText, factor);
    }

    private static String scaleForServings(String amountText, String unitText,
                                           String originalText, String wantedText) {
        KitchenQuantity original = KitchenQuantity.parse(originalText);
        KitchenQuantity wanted = KitchenQuantity.parse(wantedText);
        if (original == null || wanted == null || original.isZero() || wanted.signum() <= 0) {
            return null;
        }
        return scaledLine(amountText, unitText, wanted.dividedBy(original));
    }

    private static String scaledLine(String amountText, String unitText, KitchenQuantity factor) {
        KitchenQuantity amount = KitchenQuantity.parse(amountText);
        KitchenUnit unit = KitchenUnit.fromAlias(unitText);
        if (amount == null || unit == null || amount.signum() <= 0) return null;
        KitchenMath.Rendered rendered = KitchenMath.format(KitchenMath.scale(amount, factor), unit);
        return rendered.exact ? rendered.text : "≈ " + rendered.text;
    }

    // ---- conversion ---------------------------------------------------------------------------

    private static String converted(String q) {
        Matcher howMany = CONVERT_HOW_MANY.matcher(q);
        if (howMany.find()) return convert(q, howMany.group(2), howMany.group(3), howMany.group(1));
        Matcher forward = CONVERT_FORWARD.matcher(q);
        if (forward.find()) return convert(q, forward.group(1), forward.group(2), forward.group(3));
        return null;
    }

    private static String convert(String q, String amountText, String fromText, String toText) {
        KitchenQuantity amount = KitchenQuantity.parse(amountText);
        KitchenUnit from = KitchenUnit.fromAlias(fromText);
        KitchenUnit to = KitchenUnit.fromAlias(toText);
        if (amount == null || from == null || to == null) return null;
        if (from == to) return null;

        if (from.dimension() != to.dimension()) {
            // Volume into weight, or the reverse. With an ingredient named this is a real question
            // for the provider; with none, the honest answer is that the question is incomplete.
            return namesAnIngredient(q) ? null : INGREDIENT_NEEDED;
        }
        if (amount.signum() < 0) return null;
        return KitchenMath.conversionLine(amount, from, to);
    }

    /**
     * Whether the sentence says what is being measured, rather than only how much.
     *
     * <p>Water counts. Orbit will not assume it, but a user who names it has asked a real
     * question, and that question goes to the provider like every other named ingredient.
     */
    private static boolean namesAnIngredient(String q) {
        Matcher m = INGREDIENT.matcher(q);
        while (m.find()) {
            String word = m.group(1);
            if (KitchenUnit.fromAlias(word) != null) continue;
            if (NOT_AN_INGREDIENT.contains(word)) continue;
            return true;
        }
        return false;
    }

    // ---- normalization ------------------------------------------------------------------------

    private static final String[][] UNICODE_FRACTIONS = {
            {"½", " 1/2 "}, {"⅓", " 1/3 "}, {"⅔", " 2/3 "}, {"¼", " 1/4 "}, {"¾", " 3/4 "},
            {"⅕", " 1/5 "}, {"⅖", " 2/5 "}, {"⅗", " 3/5 "}, {"⅘", " 4/5 "},
            {"⅙", " 1/6 "}, {"⅚", " 5/6 "}, {"⅐", " 1/7 "},
            {"⅛", " 1/8 "}, {"⅜", " 3/8 "}, {"⅝", " 5/8 "}, {"⅞", " 7/8 "},
            {"⅑", " 1/9 "}, {"⅒", " 1/10 "}
    };

    /** Written fractions, longest phrase first so "one and a half" is not read as "a half". */
    private static final String[][] WRITTEN_FRACTIONS = {
            {"\\bone and a half\\b", "1 1/2"},
            {"\\bthree[\\s-]quarters\\b", "3/4"}, {"\\bthree[\\s-]fourths\\b", "3/4"},
            {"\\btwo[\\s-]thirds\\b", "2/3"},
            {"\\b(?:one|a)[\\s-]third\\b", "1/3"},
            {"\\b(?:one|a)[\\s-](?:quarter|fourth)\\b", "1/4"},
            {"\\b(?:one|a)[\\s-]half\\b", "1/2"},
            {"\\bhalf of an?\\b", "1/2"}, {"\\bhalf an?\\b", "1/2"}
    };

    private static final Pattern WORD_NUMBER_BEFORE_UNIT = Pattern.compile(
            "\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty)\\s+(?=(?:"
                    + UNITS + ")(?![a-z]))");
    private static final Pattern ARTICLE_BEFORE_UNIT = Pattern.compile(
            "\\ban?\\s+(?=(?:" + UNITS + ")(?![a-z]))");

    /**
     * The shared tidying every kitchen matcher below reads.
     *
     * <p>Builds on {@link LanguageNormalizer} so this router draws the same line about politeness,
     * case and punctuation as the rest of Orbit's local routing, then folds the notation that is
     * specific to cooking — degree signs, typed fractions, written fractions, and the small
     * counts that stand in front of a measure — into the one plain form the patterns match.
     */
    static String normalize(String raw) {
        String value = LanguageNormalizer.stripPoliteness(LanguageNormalizer.normalize(raw));
        if (value.isEmpty()) return "";

        for (String[] pair : UNICODE_FRACTIONS) value = value.replace(pair[0], pair[1]);

        value = value.replace("=", " equals ");
        value = value.replaceAll("°\\s*(f|fahrenheit)\\b", " $1");
        value = value.replaceAll("°\\s*(c|celsius|centigrade)\\b", " $1");
        value = value.replace("°", " ");
        value = value.replaceAll("\\bdegrees?\\s+(fahrenheit|celsius|centigrade|f|c)\\b", "$1");
        value = value.replaceAll("\\b(fahrenheit|celsius|centigrade)\\s+degrees?\\b", "$1");

        for (String[] pair : WRITTEN_FRACTIONS) value = value.replaceAll(pair[0], pair[1]);

        // "three quarters of a cup" is one amount of one measure, not three quarters of something
        // that then happens to be a cup. Without this the article rule below would turn the cup
        // into a second amount and the answer would be about a whole cup.
        value = value.replaceAll("(\\d)\\s+of\\s+an?\\s+(?=(?:" + UNITS + ")(?![a-z]))", "$1 ");

        value = replaceWordNumbers(value);
        value = ARTICLE_BEFORE_UNIT.matcher(value).replaceAll("1 ");
        return value.replaceAll("\\s+", " ").trim();
    }

    /**
     * "two cups" becomes "2 cups", but only directly in front of a measure.
     *
     * <p>Expanding counts everywhere would rewrite ordinary sentences for no reason; a number is
     * only interesting to this router when it is measuring something.
     */
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
