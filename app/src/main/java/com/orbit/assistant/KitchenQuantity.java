package com.orbit.assistant;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One exact quantity, shared by every Orbit kitchen calculation.
 *
 * <p>Cooking amounts are written as fractions far more often than as decimals, and a recipe scaled
 * through {@code double} arithmetic produces answers like "1.1249999999999998 cups". This holds
 * the value as an exact rational instead, so three quarters of a cup times one and a half is
 * exactly nine eighths and can be read back as "1 1/8 cups" rather than as a rounding artifact.
 *
 * <p>{@link BigInteger} rather than {@code long}: the exact US customary conversion factors are
 * large fractions (a cup is 473176473/2000000 millilitres), and chaining a few of them together
 * overflows 64 bits long before it stops being ordinary cooking. Nothing here is Android-specific
 * and nothing here is approximate.
 */
public final class KitchenQuantity implements Comparable<KitchenQuantity> {

    public static final KitchenQuantity ZERO = new KitchenQuantity(BigInteger.ZERO, BigInteger.ONE);
    public static final KitchenQuantity ONE = new KitchenQuantity(BigInteger.ONE, BigInteger.ONE);

    /** Denominators a cook actually measures with, used when reading an exact value back. */
    private static final int[] COOKING_DENOMINATORS = {2, 3, 4, 6, 8};
    /** The tighter set used when only an approximation is available; 1/6 of a cup is not a spoon. */
    private static final int[] APPROXIMATION_DENOMINATORS = {1, 2, 3, 4, 8};
    /** How far an approximation may sit from the true value before Orbit prefers a decimal. */
    private static final double APPROXIMATION_TOLERANCE = 0.005;

    private static final Pattern MIXED = Pattern.compile("^(-?\\d+)\\s+(\\d+)\\s*/\\s*(\\d+)$");
    private static final Pattern FRACTION = Pattern.compile("^(-?\\d+)\\s*/\\s*(\\d+)$");
    private static final Pattern DECIMAL = Pattern.compile("^(-?)(\\d*)(?:\\.(\\d+))?$");

    private final BigInteger num;
    private final BigInteger den;

    private KitchenQuantity(BigInteger num, BigInteger den) {
        if (den.signum() == 0) throw new ArithmeticException("a quantity cannot have a zero denominator");
        BigInteger n = num;
        BigInteger d = den;
        if (d.signum() < 0) {
            n = n.negate();
            d = d.negate();
        }
        BigInteger gcd = n.gcd(d);
        if (gcd.signum() != 0 && !gcd.equals(BigInteger.ONE)) {
            n = n.divide(gcd);
            d = d.divide(gcd);
        }
        this.num = n;
        this.den = d;
    }

    public static KitchenQuantity of(long value) {
        return new KitchenQuantity(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public static KitchenQuantity of(long num, long den) {
        return new KitchenQuantity(BigInteger.valueOf(num), BigInteger.valueOf(den));
    }

    /**
     * Reads the ways a cook writes a quantity: {@code 12}, {@code 1.5}, {@code 3/4}, {@code 1 1/2}.
     *
     * <p>Unicode fractions are folded into the {@code 1 1/2} form before they arrive here, so there
     * is one parser rather than one per surface. Returns null for anything else — an unreadable
     * quantity must never become a confidently wrong answer.
     */
    public static KitchenQuantity parse(String text) {
        if (text == null) return null;
        String value = text.trim().replace('−', '-');
        if (value.isEmpty()) return null;

        Matcher mixed = MIXED.matcher(value);
        if (mixed.matches()) {
            BigInteger whole = new BigInteger(mixed.group(1));
            BigInteger part = new BigInteger(mixed.group(2));
            BigInteger over = new BigInteger(mixed.group(3));
            if (over.signum() == 0) return null;
            BigInteger scaled = whole.abs().multiply(over).add(part);
            if (whole.signum() < 0 || mixed.group(1).startsWith("-")) scaled = scaled.negate();
            return new KitchenQuantity(scaled, over);
        }

        Matcher fraction = FRACTION.matcher(value);
        if (fraction.matches()) {
            BigInteger over = new BigInteger(fraction.group(2));
            if (over.signum() == 0) return null;
            return new KitchenQuantity(new BigInteger(fraction.group(1)), over);
        }

        Matcher decimal = DECIMAL.matcher(value);
        if (decimal.matches()) {
            String whole = decimal.group(2) == null ? "" : decimal.group(2);
            String places = decimal.group(3) == null ? "" : decimal.group(3);
            if (whole.isEmpty() && places.isEmpty()) return null;
            BigInteger scaled = new BigInteger((whole.isEmpty() ? "0" : whole) + places);
            BigInteger over = BigInteger.TEN.pow(places.length());
            if ("-".equals(decimal.group(1))) scaled = scaled.negate();
            return new KitchenQuantity(scaled, over);
        }
        return null;
    }

    public KitchenQuantity plus(KitchenQuantity other) {
        return new KitchenQuantity(num.multiply(other.den).add(other.num.multiply(den)),
                den.multiply(other.den));
    }

    public KitchenQuantity minus(KitchenQuantity other) {
        return new KitchenQuantity(num.multiply(other.den).subtract(other.num.multiply(den)),
                den.multiply(other.den));
    }

    public KitchenQuantity times(KitchenQuantity other) {
        return new KitchenQuantity(num.multiply(other.num), den.multiply(other.den));
    }

    public KitchenQuantity dividedBy(KitchenQuantity other) {
        if (other.num.signum() == 0) throw new ArithmeticException("cannot divide a quantity by zero");
        return new KitchenQuantity(num.multiply(other.den), den.multiply(other.num));
    }

    public boolean isInteger() {
        return den.equals(BigInteger.ONE);
    }

    public boolean isZero() {
        return num.signum() == 0;
    }

    public int signum() {
        return num.signum();
    }

    public double toDouble() {
        return new BigDecimal(num).divide(new BigDecimal(den), MathContext.DECIMAL64).doubleValue();
    }

    public BigInteger numerator() {
        return num;
    }

    public BigInteger denominator() {
        return den;
    }

    /** The whole part, truncated toward zero. */
    public BigInteger wholePart() {
        return num.divide(den);
    }

    /** What is left after {@link #wholePart()}, keeping this value's sign. */
    public KitchenQuantity fractionPart() {
        return minus(new KitchenQuantity(wholePart(), BigInteger.ONE));
    }

    /**
     * The value as a cook would write it: {@code 2}, {@code 3/4}, {@code 1 1/2}.
     *
     * <p>Always exact — the denominator is whatever the arithmetic produced, so this can also
     * return something unhelpful such as {@code 4157/1250}. {@link #cookingFraction()} is the form
     * that decides whether a fraction is worth showing at all.
     */
    public String mixedNumber() {
        BigInteger magnitude = num.abs();
        BigInteger whole = magnitude.divide(den);
        BigInteger rest = magnitude.subtract(whole.multiply(den));
        String sign = num.signum() < 0 ? "-" : "";
        if (rest.signum() == 0) return sign + whole;
        if (whole.signum() == 0) return sign + rest + "/" + den;
        return sign + whole + " " + rest + "/" + den;
    }

    /** True when {@link #mixedNumber()} reads as a measure someone owns a spoon or a cup for. */
    public boolean isCookingFraction() {
        if (isInteger()) return true;
        if (den.bitLength() > 31) return false;
        int value = den.intValue();
        for (int candidate : COOKING_DENOMINATORS) {
            if (value == candidate) return true;
        }
        return false;
    }

    /** {@link #mixedNumber()} when it is a natural measure, otherwise null. */
    public String cookingFraction() {
        return isCookingFraction() ? mixedNumber() : null;
    }

    /**
     * The closest natural cooking fraction within half a percent, or null when there is none.
     *
     * <p>Half a percent is the line between "near enough to measure" and a number Orbit would be
     * quietly changing. 750 ml is 3.17 cups; the nearest spoonable fraction is 3 1/8, which is more
     * than a percent away, so this returns null and the decimal is shown instead.
     */
    public KitchenQuantity nearestCookingFraction() {
        if (isZero()) return null;
        double exact = toDouble();
        for (int candidate : APPROXIMATION_DENOMINATORS) {
            BigDecimal scaled = new BigDecimal(num).multiply(BigDecimal.valueOf(candidate))
                    .divide(new BigDecimal(den), 0, RoundingMode.HALF_UP);
            KitchenQuantity rounded = new KitchenQuantity(scaled.toBigInteger(),
                    BigInteger.valueOf(candidate));
            if (rounded.isZero()) continue;
            if (rounded.equals(this)) return null;
            double error = Math.abs(rounded.toDouble() - exact) / Math.abs(exact);
            if (error <= APPROXIMATION_TOLERANCE) return rounded;
        }
        return null;
    }

    /** A plain decimal at the given number of places, with trailing zeros trimmed. */
    public String decimal(int places) {
        BigDecimal value = new BigDecimal(num)
                .divide(new BigDecimal(den), Math.max(0, places), RoundingMode.HALF_UP)
                .stripTrailingZeros();
        if (value.scale() < 0) value = value.setScale(0);
        return value.toPlainString();
    }

    /** Whether a rendering of this value reads it back exactly. */
    public boolean isExactly(String rendered) {
        KitchenQuantity parsed = parse(rendered);
        return parsed != null && parsed.equals(this);
    }

    @Override public int compareTo(KitchenQuantity other) {
        return num.multiply(other.den).compareTo(other.num.multiply(den));
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof KitchenQuantity)) return false;
        KitchenQuantity that = (KitchenQuantity) other;
        return num.equals(that.num) && den.equals(that.den);
    }

    @Override public int hashCode() {
        return num.hashCode() * 31 + den.hashCode();
    }

    @Override public String toString() {
        return mixedNumber();
    }
}
