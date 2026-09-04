package com.orbit.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one place Orbit turns a spoken duration into seconds.
 *
 * <p>Every route that can produce a {@code SET_TIMER} converges here, because the alternative —
 * a regex per router that reads one count and one unit — is exactly what made "set a timer for 4
 * minutes and 30 seconds" arrive at Samsung Clock as 4:00. That parser matched the first
 * {@code (count)(unit)} pair it found and stopped, so the second half of the sentence was silently
 * discarded. Nothing warned anyone, because 240 is a perfectly valid number of seconds.
 *
 * <p>The fix is not a bigger regex. It is to stop treating a duration as a pair and start treating
 * it as a <em>sum of components</em>: a left-to-right scan that accumulates a quantity, emits it
 * when a unit word arrives, and adds every component it finds. "4 minutes and 30 seconds" is two
 * components; so is "1 hour 5 minutes 30 seconds"; so, once fractions are quantities rather than
 * unparseable words, is "4 and a half minutes".
 *
 * <p>Sentences are read as <em>runs</em> rather than whole. A run is a contiguous stretch of
 * duration-shaped words, and only one run is ever taken, because "set a timer for 20 minutes and
 * remind me in 5 minutes" contains two durations that belong to two different actions. Summing
 * across a sentence would turn that into a 25-minute timer. Summing within one run is the fix;
 * summing across the sentence is a second bug wearing the first one's clothes.
 *
 * <p>Deliberately not a symbolic-maths parser. It knows the number words Orbit already knew, the
 * three fractions people actually say out loud, and decimals. Anything outside that is reported as
 * "not a duration" rather than guessed at, because a wrong timer is worse than no timer.
 *
 * <p>The model is never asked to do this arithmetic. A language model that returns 240 for the
 * phrase above is not being unreasonable — it is being asked to be a calculator — so Orbit does the
 * sum itself and accepts a provider's number only when the provider stated one explicitly.
 */
public final class DurationParser {

    /**
     * The longest timer Orbit will hand to Android.
     *
     * <p>Twenty-four hours, which is the ceiling Orbit's action schemas already advertise to every
     * provider. Beyond a day a countdown is a calendar entry, and Samsung Clock treats it as one.
     */
    public static final long MAX_SECONDS = 86_400L;

    /** Returned when the text does not state a duration Orbit is willing to act on. */
    public static final long INVALID = -1L;

    private DurationParser() {}

    // ---- the public shapes ------------------------------------------------------------------------

    /**
     * A parsed duration, plus enough of how it was written to say it back the same way.
     *
     * <p>Orbit has always confirmed a timer in the unit the user actually said — "90 minutes" is
     * echoed as a 90-minute timer, not restated as an hour and a half — and that stays true. Only a
     * duration that genuinely spans units, or one written as a fraction of a single unit, has to be
     * re-expressed, and {@link #singleUnit} is how a caller tells those apart.
     */
    public static final class Parsed {
        /** The normalized duration Android is given. Always a whole number of seconds. */
        public final long seconds;
        /** True when the user named exactly one unit, a whole number of times. */
        public final boolean singleUnit;
        /** The count they said, meaningful only when {@link #singleUnit}. */
        public final long count;
        /** That unit in its singular form: "second", "minute", "hour". */
        public final String unit;

        private Parsed(long seconds, boolean singleUnit, long count, String unit) {
            this.seconds = seconds;
            this.singleUnit = singleUnit;
            this.count = count;
            this.unit = unit;
        }

        public boolean isValid() { return seconds != INVALID; }
    }

    private static final Parsed NONE = new Parsed(INVALID, false, 0L, "second");

    /**
     * The duration stated by a phrase that is <em>entirely</em> a duration, in seconds.
     *
     * <p>For fields and answers that hold nothing else: "4 minutes and 30 seconds", a provider's
     * duration string, a routine editor's typed value. Every component in the text is summed.
     */
    public static long parseSeconds(String phrase) {
        return parse(phrase).seconds;
    }

    /** As {@link #parseSeconds}, keeping how the duration was written. */
    public static Parsed parse(String phrase) {
        return resultOf(components(words(phrase)));
    }

    /** Whether a phrase states any duration at all. */
    public static boolean hasDuration(String phrase) {
        return parseSeconds(phrase) != INVALID;
    }

    /**
     * The first duration run in a sentence that also says other things.
     *
     * <p>Stops at the end of that run, so a chained command's second action keeps its own numbers.
     */
    public static Parsed parseFirstRun(String text) {
        List<String> words = words(text);
        for (int i = 0; i < words.size(); i++) {
            if (!startsRun(words.get(i))) continue;
            Parsed parsed = resultOf(components(words.subList(i, endOfRun(words, i))));
            if (parsed.isValid()) return parsed;
        }
        return NONE;
    }

    /**
     * A duration standing immediately after a keyword: "timer <b>for 4 and a half minutes</b>".
     *
     * <p>Only "for" and "of" may stand between, which is what keeps a duration mentioned later in
     * an unrelated clause from being read as this action's duration.
     */
    public static Parsed parseAdjacentAfter(String text) {
        List<String> words = words(text);
        int i = 0;
        while (i < words.size() && ("for".equals(words.get(i)) || "of".equals(words.get(i)))) i++;
        if (i >= words.size() || !startsRun(words.get(i))) return NONE;
        return resultOf(components(words.subList(i, endOfRun(words, i))));
    }

    /**
     * A duration standing immediately before a keyword: "<b>a 4 minute 30 second</b> timer".
     *
     * <p>Read backwards from the end so only the run touching the keyword is taken; a duration
     * mentioned earlier in the sentence belongs to something else.
     */
    public static Parsed parseTrailingBefore(String text) {
        List<String> words = words(text);
        for (int i = words.size() - 1; i >= 0; i--) {
            if (!inRun(words.get(i))) return runEndingAt(words, i + 1);
        }
        return runEndingAt(words, 0);
    }

    private static Parsed runEndingAt(List<String> words, int start) {
        while (start < words.size() && !startsRun(words.get(start))) start++;
        if (start >= words.size()) return NONE;
        return resultOf(components(words.subList(start, words.size())));
    }

    // ---- runs -------------------------------------------------------------------------------------

    /** A run may only begin on something that carries a quantity. */
    private static boolean startsRun(String word) {
        return numberValue(word) != null
                || fractionValue(word) != null
                || writtenFractionValue(word) != null;
    }

    /** Words that may stand inside a run without ending it. */
    private static boolean inRun(String word) {
        return numberValue(word) != null
                || fractionValue(word) != null
                || writtenFractionValue(word) != null
                || unitSeconds(word) > 0
                || "and".equals(word)
                || isFiller(word);
    }

    private static int endOfRun(List<String> words, int start) {
        int end = start;
        while (end < words.size() && inRun(words.get(end))) end++;
        return end;
    }

    // ---- scanning ---------------------------------------------------------------------------------

    private static final int SECOND = 1;
    private static final int MINUTE = 60;
    private static final int HOUR = 3600;

    /** One recognized quantity-and-unit pair, before the components are summed. */
    private static final class Component {
        final double quantity;
        final int unitSeconds;

        Component(double quantity, int unitSeconds) {
            this.quantity = quantity;
            this.unitSeconds = unitSeconds;
        }
    }

    private static Parsed resultOf(List<Component> components) {
        if (components == null || components.isEmpty()) return NONE;
        double total = 0d;
        for (Component component : components) {
            double part = component.quantity * component.unitSeconds;
            if (!isFinite(part) || part < 0d) return NONE;
            total += part;
            // Checked as it accumulates rather than only at the end, so a phrase built from several
            // enormous components cannot ride a rounding error past the ceiling.
            if (total > MAX_SECONDS) return NONE;
        }
        if (!isFinite(total) || total <= 0d) return NONE;
        // Half a second is still a duration the user asked for; rounding it away to zero and then
        // reporting "no duration" would be a lie. Rounding it up to one second is not.
        long seconds = Math.max(1L, Math.round(total));
        if (seconds > MAX_SECONDS) return NONE;

        Component only = components.size() == 1 ? components.get(0) : null;
        boolean whole = only != null && only.quantity == Math.rint(only.quantity)
                && only.quantity >= 1d && only.quantity <= MAX_SECONDS;
        return whole
                ? new Parsed(seconds, true, (long) only.quantity, singularUnit(only.unitSeconds))
                : new Parsed(seconds, false, 0L, "second");
    }

    private static String singularUnit(int unitSeconds) {
        if (unitSeconds == HOUR) return "hour";
        if (unitSeconds == MINUTE) return "minute";
        return "second";
    }

    /**
     * Walks the words once, emitting a component every time a unit arrives.
     *
     * <p>Two accumulators rather than one. {@code main} holds the quantity being built; {@code
     * addend} holds a quantity introduced by "and", which is what lets "4 and a half minutes" reach
     * the unit as a single 4.5 while "4 minutes and 30 seconds" reaches it as two separate
     * components. Without that split, one of those two phrases is always wrong.
     */
    private static List<Component> components(List<String> words) {
        List<Component> out = new ArrayList<>();
        if (words == null || words.isEmpty()) return out;

        Double main = null;
        Double addend = null;
        boolean afterAnd = false;
        // True while the live quantity came from a bare article, so "a half" is a half rather than
        // one followed by a half.
        boolean fromArticle = false;
        int lastUnitSeconds = 0;

        for (String word : words) {
            if (word.isEmpty()) continue;

            int unit = unitSeconds(word);
            if (unit > 0) {
                if (main != null || addend != null) {
                    double quantity = (main == null ? 0d : main) + (addend == null ? 0d : addend);
                    if (!isFinite(quantity) || quantity <= 0d) return new ArrayList<>();
                    out.add(new Component(quantity, unit));
                    lastUnitSeconds = unit;
                }
                main = null;
                addend = null;
                afterAnd = false;
                fromArticle = false;
                continue;
            }

            if ("and".equals(word)) {
                // Only meaningful while a quantity is already in hand. Between two finished
                // components it is ordinary conjunction and carries nothing.
                if (main != null && addend == null) afterAnd = true;
                continue;
            }

            if (isFiller(word)) continue;

            Double written = writtenFractionValue(word);
            if (written != null) {
                // A written fraction after a whole number is a mixed number and *adds*: "4 1/2" is
                // four and a half. A spoken fraction in the same position multiplies, because
                // "three quarters" is three of them. Those two rules genuinely disagree, and this
                // is the only place the difference matters: nobody writes "3 1/4" meaning three
                // quarters, and nobody says "three quarters" meaning 3.25.
                if (afterAnd) {
                    addend = (addend == null ? 0d : addend) + written;
                } else if (main == null) {
                    main = written;
                } else {
                    main = main + written;
                }
                fromArticle = false;
                continue;
            }

            Double fraction = fractionValue(word);
            if (fraction != null) {
                if (afterAnd) {
                    addend = (addend == null ? 1d : addend) * fraction;
                } else if (main == null) {
                    main = fraction;
                } else if (fromArticle) {
                    // "a half" — the article was the fraction's determiner, not a count of one.
                    main = fraction;
                } else {
                    // "three quarters" — the count multiplies the fraction.
                    main = main * fraction;
                }
                fromArticle = false;
                continue;
            }

            Double number = numberValue(word);
            if (number == null) {
                // An unrecognized word ends the quantity being built rather than being skipped, so
                // a parser can never bridge across unrelated words and invent a duration.
                main = null;
                addend = null;
                afterAnd = false;
                fromArticle = false;
                continue;
            }

            boolean article = isArticle(word);
            if (afterAnd) {
                if (addend == null) {
                    addend = number;
                    fromArticle = article;
                }
            } else if (main == null) {
                main = number;
                fromArticle = article;
            } else if (article) {
                // A determiner after a count is not a second count: "half an hour".
                fromArticle = false;
            } else {
                main = number;
                fromArticle = false;
            }
        }

        // "a minute and a half" — the trailing fraction has no unit of its own and belongs to the
        // unit just stated. Restricted to a genuine fraction, so a bare leftover number ("set a
        // timer for 5 minutes and open 3") is never absorbed into the duration.
        double leftover = (main == null ? 0d : main) + (addend == null ? 0d : addend);
        if (leftover > 0d && leftover < 1d && lastUnitSeconds > 0) {
            out.add(new Component(leftover, lastUnitSeconds));
        }
        return out;
    }

    // ---- words ------------------------------------------------------------------------------------

    private static List<String> words(String phrase) {
        List<String> out = new ArrayList<>();
        if (phrase == null) return out;
        String value = phrase.toLowerCase(Locale.US).replace('’', '\'');
        // A vulgar fraction is the same number written as one glyph, so it becomes the ordinary
        // form before anything else looks at it. The leading space is what turns "4½" into two
        // tokens rather than one unparseable one.
        value = expandVulgarFractions(value);
        // A hyphen joining a count to its unit ("20-minute") is a separator. One standing in front
        // of a number is a minus sign and is kept attached, so "-5 minutes" arrives as the token
        // "-5", which is not a number this parser accepts and therefore states no duration. Erasing
        // every hyphen indiscriminately turned a negative duration into a positive one.
        value = value.replaceAll("(?<=[a-z0-9])-(?=[a-z0-9])", " ");
        // The solidus survives. Stripping it is the whole of the reported bug: "4 and 1/2 minutes"
        // became the tokens "4", "and", "1", "2", the "and 1" was read as an addend of one, the
        // stray "2" was discarded, and Samsung Clock received a five-minute timer. A lone slash
        // that is not between digits is not a number and simply ends the quantity being built.
        value = value.replaceAll("[^a-z0-9./\\- ]", " ");
        // A full stop that is not between digits is sentence punctuation, not a decimal point.
        value = value.replaceAll("(?<![0-9])\\.|\\.(?![0-9])", " ");
        for (String word : value.split("\\s+")) if (!word.isEmpty()) out.add(word);
        return out;
    }

    /** Seconds in one of this unit, or 0 when the word does not name a unit Orbit acts on. */
    private static int unitSeconds(String word) {
        switch (word) {
            case "second": case "seconds": case "sec": case "secs": return SECOND;
            case "minute": case "minutes": case "min": case "mins": return MINUTE;
            case "hour": case "hours": case "hr": case "hrs": return HOUR;
            default: return 0;
        }
    }

    /** The everyday fractions people say out loud, and only those. */
    private static Double fractionValue(String word) {
        switch (word) {
            case "half": case "halves": return 0.5d;
            case "quarter": case "quarters": return 0.25d;
            default: return null;
        }
    }

    /**
     * Rewrites every Unicode vulgar fraction as its {@code n/d} form.
     *
     * <p>A leading space is always inserted, because these arrive stuck to the count they modify:
     * "4½ minutes" is one token to any tokenizer that does not know the glyph, and dropping the
     * glyph instead turns the phrase into a four-minute timer without telling anyone.
     */
    private static String expandVulgarFractions(String value) {
        if (value == null || value.isEmpty()) return value;
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            String expansion = vulgarFraction(ch);
            if (expansion == null) {
                out.append(ch);
            } else {
                out.append(' ').append(expansion).append(' ');
            }
        }
        return out.toString();
    }

    private static String vulgarFraction(char ch) {
        switch (ch) {
            case '½': return "1/2";
            case '¼': return "1/4";
            case '¾': return "3/4";
            case '⅓': return "1/3";
            case '⅔': return "2/3";
            case '⅕': return "1/5";
            case '⅖': return "2/5";
            case '⅗': return "3/5";
            case '⅘': return "4/5";
            case '⅙': return "1/6";
            case '⅚': return "5/6";
            case '⅛': return "1/8";
            case '⅜': return "3/8";
            case '⅝': return "5/8";
            case '⅞': return "7/8";
            default: return null;
        }
    }

    /** The largest numerator or denominator a written fraction may have. */
    private static final int MAX_FRACTION_PART = 999;

    /**
     * The value of a written fraction such as {@code 1/2}, or null when the token is not one.
     *
     * <p>Generic rather than a list, because there is no reason "2/3 of an hour" should work and
     * "3/5 of an hour" should not. The guards are what keep it honest: a denominator of zero, a
     * missing half, more than one solidus, a leading minus, or parts large enough to be a date
     * rather than a fraction all return null, which ends the quantity being built rather than
     * inventing a duration from a token nobody meant as one.
     */
    private static Double writtenFractionValue(String word) {
        if (word == null) return null;
        int slash = word.indexOf('/');
        if (slash <= 0 || slash != word.lastIndexOf('/') || slash == word.length() - 1) return null;
        String top = word.substring(0, slash);
        String bottom = word.substring(slash + 1);
        if (!top.matches("\\d{1,3}") || !bottom.matches("\\d{1,3}")) return null;
        int numerator = Integer.parseInt(top);
        int denominator = Integer.parseInt(bottom);
        if (denominator <= 0 || denominator > MAX_FRACTION_PART) return null;
        if (numerator > MAX_FRACTION_PART) return null;
        double value = numerator / (double) denominator;
        if (!isFinite(value) || value < 0d || value > MAX_SECONDS) return null;
        return value;
    }

    private static boolean isArticle(String word) {
        return "a".equals(word) || "an".equals(word);
    }

    /** Words that sit inside a duration without changing it. */
    private static boolean isFiller(String word) {
        return "of".equals(word) || "the".equals(word);
    }

    /**
     * A digit run, a decimal, or one of the written numbers Orbit already knew.
     *
     * <p>Decimals are parsed rather than pattern-matched, so "1.5 hours" and "one and a half hours"
     * are literally the same duration by the time anything downstream sees them.
     */
    private static Double numberValue(String word) {
        if (word.matches("\\d+(\\.\\d+)?")) {
            try {
                double value = Double.parseDouble(word);
                // parseDouble accepts values a timer never should. Rejected rather than clamped, so
                // an absurd request is refused instead of quietly becoming a 24-hour timer.
                if (!isFinite(value) || value < 0d || value > MAX_SECONDS) return null;
                return value;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (isArticle(word)) return 1d;
        int written = LanguageNormalizer.wordNumber(word);
        return written > 0 ? (double) written : null;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    // ---- saying it back ---------------------------------------------------------------------------

    /**
     * The compact form an action card shows: "4m 30s", "1h 5m 30s", "5m".
     *
     * <p>Every non-zero part is shown. Rendering 270 seconds as "4m" is the display half of the same
     * bug — the timer would be right and the card would still be lying about it.
     */
    public static String compactLabel(long seconds) {
        long safe = Math.max(0L, seconds);
        if (safe <= 0L) return "0s";
        long hours = safe / 3600L;
        long minutes = (safe % 3600L) / 60L;
        long remainder = safe % 60L;
        StringBuilder out = new StringBuilder();
        if (hours > 0L) out.append(hours).append('h');
        if (minutes > 0L) append(out, minutes + "m");
        if (remainder > 0L) append(out, remainder + "s");
        return out.toString();
    }

    /**
     * The single unit a duration is naturally said in, in seconds, or 0 when it spans units.
     *
     * <p>Whole hours and whole minutes are obvious. The third case is the judgement call: 90
     * seconds is a 90-second timer to everyone who has ever set one, not a one-minute-thirty-second
     * timer, and the same is true up to about two minutes. Past that people switch to minutes, and
     * "a 270-second timer" stops being English — which is the whole reason a spanning form exists.
     */
    static int naturalSingleUnit(long seconds) {
        if (seconds <= 0L) return SECOND;
        if (seconds % 3600L == 0L) return HOUR;
        if (seconds % 60L == 0L) return MINUTE;
        if (seconds < 120L) return SECOND;
        return 0;
    }

    /**
     * The duration as it stands in front of the word "timer": "4 minute 30 second", "20-minute".
     *
     * <p>A single unit keeps the hyphenated compound English wants — "a 20-minute timer" — which is
     * the form Orbit has always used. A duration that genuinely spans units cannot take that hyphen
     * without reading as machinery ("a 4-minute-30-second timer"), so its parts are simply spoken
     * in order, singular, as a person would say them.
     */
    public static String spokenModifier(long seconds) {
        long safe = Math.max(1L, seconds);
        int single = naturalSingleUnit(safe);
        if (single > 0) {
            return RoutineActionCatalog.durationModifier(safe / single, singularUnit(single));
        }
        long hours = safe / 3600L;
        long minutes = (safe % 3600L) / 60L;
        long remainder = safe % 60L;
        StringBuilder out = new StringBuilder();
        if (hours > 0L) out.append(hours).append(" hour");
        if (minutes > 0L) append(out, minutes + " minute");
        if (remainder > 0L) append(out, remainder + " second");
        return out.toString();
    }

    private static void append(StringBuilder out, String part) {
        if (out.length() > 0) out.append(' ');
        out.append(part);
    }
}
