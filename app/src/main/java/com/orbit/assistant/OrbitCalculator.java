package com.orbit.assistant;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Orbit's deterministic everyday calculator.
 *
 * <p>A hand-written recursive-descent parser over {@link KitchenQuantity}, which is already Orbit's
 * exact rational: {@code 17% of 84} is exactly {@code 14.28} rather than {@code 14.280000000000001},
 * and the kitchen line and this one cannot drift apart because there is only one numeric type in
 * the app. Nothing here evaluates code, and nothing here can: the only things the parser will build
 * are the arithmetic nodes below, so an expression stays data all the way through.
 *
 * <p>Everything is exact until a value is written out, and the written form says which it is —
 * {@code =} for a value the decimal reproduces exactly, {@code ~=} for one it only approximates.
 * Division by zero, an overflowing exponent, and a malformed expression are all refusals rather
 * than a number: an answer Orbit is not sure of is worse than no answer at all.
 */
public final class OrbitCalculator {

    /** How large a rational may get before Orbit stops calling it everyday arithmetic. */
    private static final int MAX_BITS = 4096;
    /** Integer exponents only, and small ones: {@code 2^10} is arithmetic, {@code 2^999999} is not. */
    private static final int MAX_EXPONENT = 512;
    private static final int SIGNIFICANT_DIGITS = 12;

    private OrbitCalculator() {}

    /** One evaluated value, and whether every step that produced it was exact. */
    public static final class Value {
        public final KitchenQuantity amount;
        public final boolean exact;
        /**
         * Whether this value was written as a percentage.
         *
         * <p>Carried because {@code 84 - 17%} means seventeen percent <em>of 84</em>, which is how
         * everybody reads a discount. Only the addition and subtraction step consults it, and only
         * when the percentage is the right-hand side.
         */
        boolean percentage;

        Value(KitchenQuantity amount, boolean exact) {
            this.amount = amount;
            this.exact = exact;
        }
    }

    /** Why an expression produced no number, when the reason is worth saying out loud. */
    public enum Refusal {
        /** Not arithmetic Orbit recognises. Say nothing; let the request go to the provider. */
        NOT_ARITHMETIC,
        /** Well-formed, and undefined. Worth answering. */
        DIVIDE_BY_ZERO,
        /** Well-formed, and beyond everyday arithmetic. Worth answering. */
        TOO_LARGE
    }

    /** The complete outcome of one evaluation: a value, or a stated reason there is none. */
    public static final class Outcome {
        public final Value value;
        public final Refusal refusal;

        private Outcome(Value value, Refusal refusal) {
            this.value = value;
            this.refusal = refusal;
        }

        public boolean hasValue() { return value != null; }

        static Outcome of(Value value) { return new Outcome(value, null); }
        static Outcome refused(Refusal refusal) { return new Outcome(null, refusal); }
    }

    private static final class ParseError extends RuntimeException {
        final Refusal refusal;
        ParseError(Refusal refusal) {
            super(refusal.name(), null, false, false);
            this.refusal = refusal;
        }
    }

    // ---- entry points --------------------------------------------------------------------------

    /** Evaluates one normalized expression. Never throws. */
    public static Outcome evaluate(String expression) {
        if (expression == null) return Outcome.refused(Refusal.NOT_ARITHMETIC);
        try {
            Parser parser = new Parser(expression);
            Value value = parser.parseExpression();
            parser.requireEnd();
            // A message that contains a number is not a message that asked for arithmetic.
            if (parser.operations == 0) return Outcome.refused(Refusal.NOT_ARITHMETIC);
            return Outcome.of(value);
        } catch (ParseError e) {
            return Outcome.refused(e.refusal);
        } catch (Exception e) {
            return Outcome.refused(Refusal.NOT_ARITHMETIC);
        }
    }

    /**
     * The value written for a person: {@code 45}, {@code 14.28}, {@code ~= 12.2474487139}.
     *
     * <p>A repeating or irrational result is shown to a fixed number of significant digits and
     * marked approximate, so the reader is never told that a rounded figure is the whole answer.
     */
    public static String format(Value value) {
        if (value == null || value.amount == null) return "";
        KitchenQuantity amount = value.amount;
        if (amount.isInteger() && value.exact) return amount.mixedNumber();

        String text = significant(amount);
        boolean exact = value.exact && amount.isExactly(text);
        return exact ? text : "≈ " + text;
    }

    /** A decimal carrying enough significant digits for the size of the number, zeros trimmed. */
    private static String significant(KitchenQuantity amount) {
        return amount.decimal(MeasureMath.decimalPlacesFor(amount, SIGNIFICANT_DIGITS));
    }

    // ---- the parser ----------------------------------------------------------------------------

    /**
     * The whole grammar, in one small recursive-descent parser.
     *
     * <p>Deliberately not a general expression language. It reads numbers, the everyday operators,
     * parentheses, percentages, and the one function people actually type at a phone, and refuses
     * everything else. There is no variable, no identifier lookup, and nowhere a string could
     * become something executable.
     */
    private static final class Parser {
        private final String text;
        private int index;
        /**
         * How many operations the expression actually asked for.
         *
         * <p>A bare number is not a calculation. Without this, {@code 42} and {@code -5} would both
         * parse cleanly and Orbit would answer a message that never asked it anything.
         */
        int operations;

        Parser(String source) {
            this.text = source == null ? "" : source;
        }

        // expression := term (('+' | '-') term)*
        Value parseExpression() {
            Value left = parseTerm();
            while (true) {
                skipSpace();
                char c = peek();
                if (c != '+' && c != '-') return left;
                index++;
                operations++;
                Value right = parseTerm();
                KitchenQuantity operand = right.percentage
                        ? left.amount.times(right.amount) : right.amount;
                left = combine(left, right,
                        c == '+' ? left.amount.plus(operand) : left.amount.minus(operand));
            }
        }

        // term := power (('*' | '/') power)*
        private Value parseTerm() {
            Value left = parsePower();
            while (true) {
                skipSpace();
                char c = peek();
                if (c == '*' || c == '×') {
                    index++;
                    operations++;
                    Value right = parsePower();
                    left = combine(left, right, left.amount.times(right.amount));
                } else if (c == 'x') {
                    // "x" is only multiplication between two numbers; it never starts one.
                    if (!startsValue(index + 1)) return left;
                    index++;
                    operations++;
                    Value right = parsePower();
                    left = combine(left, right, left.amount.times(right.amount));
                } else if (c == '/' || c == '÷') {
                    index++;
                    operations++;
                    Value right = parsePower();
                    if (right.amount.isZero()) throw new ParseError(Refusal.DIVIDE_BY_ZERO);
                    left = combine(left, right, left.amount.dividedBy(right.amount));
                } else {
                    return left;
                }
            }
        }

        // power := unary ('^' power)?   - right associative, integer exponents only
        private Value parsePower() {
            Value base = parseUnary();
            skipSpace();
            if (peek() != '^') return base;
            index++;
            operations++;
            Value exponent = parsePower();
            return combine(base, exponent, pow(base.amount, exponent.amount));
        }

        // unary := ('-' | '+')? (sqrt | primary)
        private Value parseUnary() {
            skipSpace();
            char c = peek();
            if (c == '-') {
                index++;
                Value inner = parseUnary();
                return new Value(KitchenQuantity.ZERO.minus(inner.amount), inner.exact);
            }
            if (c == '+') {
                index++;
                return parseUnary();
            }
            if (matchWord("sqrt")) {
                operations++;
                return sqrt(parseUnary());
            }
            return parsePrimary();
        }

        // primary := '(' expression ')' | number '%'?
        private Value parsePrimary() {
            skipSpace();
            if (peek() == '(') {
                index++;
                Value inner = parseExpression();
                skipSpace();
                if (peek() != ')') throw new ParseError(Refusal.NOT_ARITHMETIC);
                index++;
                return withPercent(new Value(inner.amount, inner.exact));
            }
            int start = index;
            while (Character.isDigit(peek())) index++;
            if (peek() == '.') {
                index++;
                while (Character.isDigit(peek())) index++;
            }
            if (index == start) throw new ParseError(Refusal.NOT_ARITHMETIC);
            KitchenQuantity number = KitchenQuantity.parse(text.substring(start, index));
            if (number == null) throw new ParseError(Refusal.NOT_ARITHMETIC);
            return withPercent(new Value(number, true));
        }

        /** A trailing {@code %} divides by a hundred and remembers that it was a percentage. */
        private Value withPercent(Value value) {
            skipSpace();
            if (peek() != '%') return value;
            index++;
            operations++;
            Value scaled = new Value(value.amount.dividedBy(KitchenQuantity.of(100)), value.exact);
            scaled.percentage = true;
            return scaled;
        }

        void requireEnd() {
            skipSpace();
            if (index < text.length()) throw new ParseError(Refusal.NOT_ARITHMETIC);
        }

        // ---- helpers ---------------------------------------------------------------------------

        private char peek() {
            return index < text.length() ? text.charAt(index) : '\0';
        }

        private void skipSpace() {
            while (index < text.length() && text.charAt(index) == ' ') index++;
        }

        /** Whether a value could begin at {@code at}, used to tell "3 x 4" from a stray letter. */
        private boolean startsValue(int at) {
            int i = at;
            while (i < text.length() && text.charAt(i) == ' ') i++;
            if (i >= text.length()) return false;
            char c = text.charAt(i);
            return Character.isDigit(c) || c == '(' || c == '.' || c == '-' || c == 's';
        }

        private boolean matchWord(String word) {
            skipSpace();
            if (!text.startsWith(word, index)) return false;
            int after = index + word.length();
            if (after < text.length() && Character.isLetterOrDigit(text.charAt(after))) return false;
            index = after;
            return true;
        }
    }

    // ---- arithmetic that can refuse -------------------------------------------------------------

    private static Value combine(Value left, Value right, KitchenQuantity result) {
        guard(result);
        return new Value(result, left.exact && right.exact);
    }

    private static void guard(KitchenQuantity value) {
        if (value == null) throw new ParseError(Refusal.NOT_ARITHMETIC);
        if (value.numerator().bitLength() > MAX_BITS || value.denominator().bitLength() > MAX_BITS) {
            throw new ParseError(Refusal.TOO_LARGE);
        }
    }

    private static KitchenQuantity pow(KitchenQuantity base, KitchenQuantity exponent) {
        if (!exponent.isInteger()) throw new ParseError(Refusal.NOT_ARITHMETIC);
        BigInteger whole = exponent.wholePart();
        if (whole.abs().bitLength() > 31) throw new ParseError(Refusal.TOO_LARGE);
        int power = whole.intValue();
        if (Math.abs(power) > MAX_EXPONENT) throw new ParseError(Refusal.TOO_LARGE);
        if (power == 0) return KitchenQuantity.ONE;
        if (base.isZero() && power < 0) throw new ParseError(Refusal.DIVIDE_BY_ZERO);
        KitchenQuantity factor = power < 0 ? KitchenQuantity.ONE.dividedBy(base) : base;
        KitchenQuantity result = KitchenQuantity.ONE;
        for (int i = 0; i < Math.abs(power); i++) {
            result = result.times(factor);
            guard(result);
        }
        return result;
    }

    /**
     * A square root, exact when the value genuinely has one.
     *
     * <p>{@code sqrt 144} is 12 and is written with {@code =}; {@code sqrt 150} is irrational, so
     * the decimal only approximates it and is marked as such. A negative value has no real root
     * and is refused rather than answered with something imaginary.
     */
    private static Value sqrt(Value value) {
        KitchenQuantity amount = value.amount;
        if (amount.signum() < 0) throw new ParseError(Refusal.NOT_ARITHMETIC);
        if (amount.isZero()) return new Value(KitchenQuantity.ZERO, value.exact);

        BigInteger num = integerSqrt(amount.numerator());
        BigInteger den = integerSqrt(amount.denominator());
        if (num.multiply(num).equals(amount.numerator())
                && den.multiply(den).equals(amount.denominator())
                && num.bitLength() < 63 && den.bitLength() < 63) {
            KitchenQuantity exact = KitchenQuantity.of(num.longValue(), den.longValue());
            guard(exact);
            return new Value(exact, value.exact);
        }
        // sqrt(n/d) to a fixed number of places, entirely in integers: sqrt(n * 10^2p / d) / 10^p.
        int places = SIGNIFICANT_DIGITS + 4;
        BigInteger scale = BigInteger.TEN.pow(places);
        BigInteger root = integerSqrt(amount.numerator().multiply(scale).multiply(scale)
                .divide(amount.denominator()));
        String text = new BigDecimal(root)
                .divide(new BigDecimal(scale), places, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        KitchenQuantity approx = KitchenQuantity.parse(text);
        if (approx == null) throw new ParseError(Refusal.NOT_ARITHMETIC);
        guard(approx);
        return new Value(approx, false);
    }

    /**
     * The integer square root, by Newton's method.
     *
     * <p>{@code BigInteger.sqrt} and {@code BigDecimal.sqrt} both arrived on Android in API 31 and
     * Orbit still supports API 29, so this is written out rather than borrowed. Newton converges
     * from above; the two corrections afterwards make the answer exactly {@code floor(sqrt(v))}
     * whichever side the iteration happened to stop on.
     */
    static BigInteger integerSqrt(BigInteger value) {
        if (value.signum() <= 0) return BigInteger.ZERO;
        BigInteger guess = BigInteger.ONE.shiftLeft((value.bitLength() + 1) / 2);
        while (true) {
            BigInteger next = guess.add(value.divide(guess)).shiftRight(1);
            if (next.compareTo(guess) >= 0) break;
            guess = next;
        }
        while (guess.multiply(guess).compareTo(value) > 0) guess = guess.subtract(BigInteger.ONE);
        while (guess.add(BigInteger.ONE).multiply(guess.add(BigInteger.ONE)).compareTo(value) <= 0) {
            guess = guess.add(BigInteger.ONE);
        }
        return guess;
    }
}
