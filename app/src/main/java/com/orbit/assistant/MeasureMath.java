package com.orbit.assistant;

/**
 * Conversion and presentation for Orbit's general-purpose measures.
 *
 * <p>The counterpart of {@link KitchenMath}, and deliberately a different presentation: nobody owns
 * a three-eighths-of-a-metre ruler, so these read as decimals rather than as cooking fractions.
 * The arithmetic is the same exact rational underneath, and the written form carries the same
 * promise — {@code =} when the decimal shown is the whole value, {@code ~=} when it is rounded.
 */
public final class MeasureMath {
    private MeasureMath() {}

    /** How many significant figures a converted measurement is written to. */
    private static final int SIGNIFICANT_DIGITS = 12;

    /** Converts within one dimension. Returns null when the units do not measure the same thing. */
    public static KitchenQuantity convert(KitchenQuantity amount, MeasureUnit from, MeasureUnit to) {
        if (amount == null || from == null || to == null) return null;
        if (from.dimension() != to.dimension()) return null;
        if (from == to) return amount;
        return amount.times(from.toBase()).dividedBy(to.toBase());
    }

    /** "1 in = 2.54 cm", "60 mph ~= 96.56064 km/h". Null when the units cannot be compared. */
    public static String conversionLine(KitchenQuantity amount, MeasureUnit from, MeasureUnit to) {
        KitchenQuantity result = convert(amount, from, to);
        if (result == null) return null;
        KitchenMath.Rendered source = format(amount, from);
        KitchenMath.Rendered target = format(result, to);
        String relation = source.exact && target.exact ? "=" : "≈";
        String line = source.text + " " + relation + " " + target.text;
        String note = dataNote(from, to);
        return note.isEmpty() ? line : line + "\n" + note;
    }

    /**
     * The one sentence storage conversions need.
     *
     * <p>{@code GB} and {@code GiB} are different units and most software pretends they are not.
     * Orbit converts between them correctly and says which convention it used, so a figure that
     * disagrees with what the phone's storage screen shows is explainable rather than baffling.
     */
    static String dataNote(MeasureUnit from, MeasureUnit to) {
        if (from == null || to == null) return "";
        if (from.dimension() != MeasureUnit.Dimension.DATA) return "";
        boolean decimal = from.isDecimalData() || to.isDecimalData();
        boolean binary = from.isBinaryData() || to.isBinaryData();
        if (decimal && binary) {
            return "KB, MB, GB and TB are SI decimal (×1000); KiB, MiB, GiB and TiB are binary (×1024).";
        }
        if (decimal) return "SI decimal units: 1 KB is 1000 bytes. Use KiB, MiB, GiB or TiB for binary.";
        if (binary) return "Binary units: 1 KiB is 1024 bytes. Use KB, MB, GB or TB for SI decimal.";
        return "";
    }

    /**
     * An amount with its unit, as a decimal at a sensible number of significant figures.
     *
     * <p>Significant figures rather than a fixed number of places, so 1609.344 m and 0.000254 km
     * are both written usefully instead of one of them collapsing to zero.
     */
    public static KitchenMath.Rendered format(KitchenQuantity amount, MeasureUnit unit) {
        if (amount == null || unit == null) return new KitchenMath.Rendered("", false);
        int places = decimalPlacesFor(amount, SIGNIFICANT_DIGITS);
        String text = amount.decimal(places);
        KitchenQuantity shown = KitchenQuantity.parse(text);
        return new KitchenMath.Rendered(text + " " + unit.label(shown == null ? amount : shown),
                amount.isExactly(text));
    }

    /**
     * How many decimal places give {@code significant} significant figures for this value.
     *
     * <p>Shared with {@link OrbitCalculator} so a converted measurement and a calculated number are
     * written to the same precision. A value smaller than one earns extra places rather than
     * collapsing to {@code 0.00}, and the total is capped so nothing produces an unreadable tail.
     */
    static int decimalPlacesFor(KitchenQuantity amount, int significant) {
        if (amount == null || amount.isZero()) return 0;
        double magnitude = Math.abs(amount.toDouble());
        if (magnitude == 0d || Double.isNaN(magnitude) || Double.isInfinite(magnitude)) return 2;
        int integerDigits = (int) Math.floor(Math.log10(magnitude)) + 1;
        return Math.max(0, Math.min(significant + 4, significant - integerDigits));
    }
}
