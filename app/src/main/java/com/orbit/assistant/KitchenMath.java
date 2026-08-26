package com.orbit.assistant;

/**
 * Orbit's deterministic kitchen calculations.
 *
 * <p>Pure arithmetic and pure presentation: no Android, no network, no provider. Everything here
 * is exact until the moment a value is written out for a person, and the written form always says
 * whether it is exact ({@code =}) or rounded ({@code ≈}). The router above decides <em>whether</em>
 * a question is one of these; this decides what the answer is.
 *
 * <p>What it deliberately cannot do: turn a volume into a weight. That needs the density of a
 * named ingredient, and inventing one would produce a confident, wrong, unrecoverable answer in
 * the middle of somebody's baking.
 */
public final class KitchenMath {
    private KitchenMath() {}

    private static final KitchenQuantity FIVE_NINTHS = KitchenQuantity.of(5, 9);
    private static final KitchenQuantity NINE_FIFTHS = KitchenQuantity.of(9, 5);
    private static final KitchenQuantity THIRTY_TWO = KitchenQuantity.of(32);
    private static final KitchenQuantity THREE = KitchenQuantity.of(3);

    /** A value written out for a person, and whether that writing is the true value. */
    public static final class Rendered {
        public final String text;
        public final boolean exact;

        Rendered(String text, boolean exact) {
            this.text = text;
            this.exact = exact;
        }

        @Override public String toString() {
            return text;
        }
    }

    // ---- conversion ---------------------------------------------------------------------------

    /** Converts within one dimension. Returns null when the units do not measure the same thing. */
    public static KitchenQuantity convert(KitchenQuantity amount, KitchenUnit from, KitchenUnit to) {
        if (amount == null || from == null || to == null) return null;
        if (from.dimension() != to.dimension()) return null;
        if (from == to) return amount;
        return amount.times(from.toBase()).dividedBy(to.toBase());
    }

    // ---- temperature --------------------------------------------------------------------------

    public static KitchenQuantity fahrenheitToCelsius(KitchenQuantity fahrenheit) {
        return fahrenheit.minus(THIRTY_TWO).times(FIVE_NINTHS);
    }

    public static KitchenQuantity celsiusToFahrenheit(KitchenQuantity celsius) {
        return celsius.times(NINE_FIFTHS).plus(THIRTY_TWO);
    }

    // ---- scaling ------------------------------------------------------------------------------

    public static KitchenQuantity scale(KitchenQuantity amount, KitchenQuantity factor) {
        if (amount == null || factor == null) return null;
        return amount.times(factor);
    }

    /**
     * {@code new = old × wanted / original}, the only recipe-scaling formula there is.
     *
     * <p>Returns null for a zero original serving count rather than dividing by it.
     */
    public static KitchenQuantity scaleForServings(KitchenQuantity amount,
                                                   KitchenQuantity originalServings,
                                                   KitchenQuantity wantedServings) {
        if (amount == null || originalServings == null || wantedServings == null) return null;
        if (originalServings.isZero()) return null;
        return amount.times(wantedServings).dividedBy(originalServings);
    }

    // ---- presentation -------------------------------------------------------------------------

    /**
     * An amount with its unit, in the form a cook would read.
     *
     * <p>Metric units get decimals, because that is how they are written; US measures get
     * fractions, because that is what the spoons and cups in the drawer are marked with. A
     * tablespoon amount that lands exactly on a whole number of teaspoons is split into both,
     * because "5 tbsp + 1 tsp" is something you can actually measure and "5 1/3 tbsp" is not.
     */
    public static Rendered format(KitchenQuantity amount, KitchenUnit unit) {
        if (amount == null || unit == null) return new Rendered("", false);

        Rendered spoons = spoonSplit(amount, unit);
        if (spoons != null) return spoons;

        if (unit.isMetric()) return decimal(amount, unit);

        String exactFraction = amount.cookingFraction();
        if (exactFraction != null) {
            return new Rendered(exactFraction + " " + unit.label(amount), true);
        }
        KitchenQuantity nearby = amount.nearestCookingFraction();
        if (nearby != null) {
            return new Rendered(nearby.mixedNumber() + " " + unit.label(nearby), false);
        }
        return decimal(amount, unit);
    }

    /** "1/3 cup = 5 tbsp + 1 tsp", "2 cups ≈ 473 ml". Null when the units cannot be compared. */
    public static String conversionLine(KitchenQuantity amount, KitchenUnit from, KitchenUnit to) {
        KitchenQuantity result = convert(amount, from, to);
        if (result == null) return null;
        Rendered source = format(amount, from);
        Rendered target = format(result, to);
        String relation = source.exact && target.exact ? "=" : "≈";
        return source.text + " " + relation + " " + target.text;
    }

    /** "425°F ≈ 218°C", "180°C = 356°F". Oven temperatures are read in whole degrees. */
    public static String temperatureLine(KitchenQuantity value, boolean fromFahrenheit) {
        if (value == null) return null;
        KitchenQuantity result = fromFahrenheit ? fahrenheitToCelsius(value) : celsiusToFahrenheit(value);
        String rounded = result.decimal(0);
        // "-0" is arithmetically right and reads as a typo.
        if ("-0".equals(rounded)) rounded = "0";
        boolean exact = result.isExactly(rounded);
        return temperatureText(value) + (fromFahrenheit ? "°F" : "°C")
                + (exact ? " = " : " ≈ ")
                + rounded + (fromFahrenheit ? "°C" : "°F");
    }

    /** The user's own temperature, echoed without inventing precision it did not have. */
    static String temperatureText(KitchenQuantity value) {
        if (value.isInteger()) return value.mixedNumber();
        String written = value.decimal(4);
        return value.isExactly(written) ? written : value.decimal(2);
    }

    /**
     * A tablespoon amount that is a whole number of teaspoons, written as both.
     *
     * <p>Only tablespoons, only when there is at least one whole spoon, and only when the split is
     * exact. Anything else reads better as a plain fraction, so this returns null and lets it.
     */
    private static Rendered spoonSplit(KitchenQuantity amount, KitchenUnit unit) {
        if (unit != KitchenUnit.TBSP) return null;
        if (amount.isInteger() || amount.signum() <= 0) return null;
        KitchenQuantity teaspoons = amount.times(THREE);
        if (!teaspoons.isInteger()) return null;
        java.math.BigInteger whole = amount.wholePart();
        if (whole.signum() <= 0) return null;
        KitchenQuantity leftover = amount.fractionPart().times(THREE);
        if (!leftover.isInteger() || leftover.isZero()) return null;
        return new Rendered(whole + " tbsp + " + leftover.mixedNumber() + " tsp", true);
    }

    /** A decimal at a scale that suits the size of the number, with the unit attached. */
    private static Rendered decimal(KitchenQuantity amount, KitchenUnit unit) {
        double magnitude = Math.abs(amount.toDouble());
        int places = magnitude >= 100 ? 0 : magnitude >= 10 ? 1 : magnitude >= 1 ? 2 : 3;
        String text = amount.decimal(places);
        KitchenQuantity shown = KitchenQuantity.parse(text);
        return new Rendered(text + " " + unit.label(shown == null ? amount : shown),
                amount.isExactly(text));
    }
}
