package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The deterministic calculator, and the far more important question of what it refuses.
 *
 * <p>Getting {@code 17 + 28} right is table stakes. The risk in a calculator that sits in an
 * assistant's routing pipeline is that it answers a message nobody meant as a sum — "I spent 40 on
 * lunch and 12 on coffee" must reach the provider, not come back as 52 — so most of what follows is
 * about restraint rather than arithmetic.
 */
public final class OrbitCalculatorTest {

    private static String answer(String text) {
        return CalculatorRouter.answer(text);
    }

    // ---- the arithmetic ---------------------------------------------------------------------------

    @Test public void theFourOperations() {
        assertEquals("17 + 28 = 45", answer("17 + 28"));
        assertEquals("50 - 8 = 42", answer("50 - 8"));
        assertEquals("125 * 0.8 = 100", answer("125 * 0.8"));
        assertEquals("144 / 12 = 12", answer("144 / 12"));
    }

    @Test public void precedenceAndParentheses() {
        assertEquals("multiplication binds tighter than addition",
                "2 + 3 * 4 = 14", answer("2 + 3 * 4"));
        assertEquals("(14 + 6) / 4 = 5", answer("(14 + 6) / 4"));
        assertEquals("brackets must survive Orbit's text tidying",
                "(2 + 3) * 4 = 20", answer("(2 + 3) * 4"));
    }

    @Test public void unaryNegativesAndDecimals() {
        assertEquals("-5 + 12 = 7", answer("-5 + 12"));
        assertEquals("10 * -3 = -30", answer("10 * -3"));
        assertEquals("0.1 + 0.2 = 0.3", answer("0.1 + 0.2"));
    }

    /** The exact-rational payoff: a repeating binary fraction never leaks into the answer. */
    @Test public void decimalsAreExactRatherThanFloating() {
        assertEquals("0.1 + 0.2 = 0.3", answer("0.1 + 0.2"));
        assertEquals("1.1 * 3 = 3.3", answer("1.1 * 3"));
    }

    @Test public void percentages() {
        assertEquals("17% of 84 = 14.28", answer("17% of 84"));
        assertEquals("20% of 250 = 50", answer("what is 20% of 250"));
        assertEquals("a percentage after a minus is relative to what came before",
                "84 minus 17% = 69.72", answer("84 minus 17%"));
        assertEquals("200 + 10% = 220", answer("200 + 10%"));
    }

    @Test public void powersAndRoots() {
        assertEquals("2^10 = 1024", answer("2^10"));
        assertEquals("sqrt 144 = 12", answer("sqrt 144"));
        assertEquals("square roots of non-squares are marked approximate",
                "sqrt 150 ≈ 12.2474487139", answer("sqrt 150"));
        assertEquals("7 squared = 49", answer("7 squared"));
    }

    @Test public void wordOperators() {
        assertEquals("12 plus 30 = 42", answer("12 plus 30"));
        assertEquals("9 times 9 = 81", answer("9 times 9"));
        assertEquals("100 divided by 8 = 12.5", answer("100 divided by 8"));
    }

    @Test public void leadInsAreStrippedButNotRequired() {
        assertEquals("17 + 28 = 45", answer("what is 17 + 28"));
        assertEquals("17 + 28 = 45", answer("What's 17 + 28?"));
        assertEquals("17 + 28 = 45", answer("calculate 17 + 28"));
        assertEquals("17 + 28 = 45", answer("17 + 28 ="));
        assertEquals("17 + 28 = 45", answer("17 + 28"));
    }

    // ---- refusals that still answer ----------------------------------------------------------------

    /** Dividing by zero is undefined, and saying so is the right answer rather than a pass. */
    @Test public void divisionByZeroIsAnsweredHonestly() {
        assertEquals("Dividing by zero has no answer.", answer("10 / 0"));
        assertEquals("Dividing by zero has no answer.", answer("what is 5 divided by 0"));
        assertFalse("and never a number", String.valueOf(answer("10 / 0")).contains("Infinity"));
    }

    @Test public void enormousExponentsAreRefusedRatherThanAttempted() {
        String result = answer("9 ^ 999999");
        assertNotNull(result);
        assertTrue(result.startsWith("That is beyond"));
    }

    /** A very large but legitimate power still produces a number rather than a refusal. */
    @Test public void ordinaryLargePowersStillWork() {
        assertEquals("2^64 = 18446744073709551616", answer("2^64"));
    }

    // ---- restraint ----------------------------------------------------------------------------------

    @Test public void malformedExpressionsAreLeftAlone() {
        assertNull(answer("17 +"));
        assertNull(answer("((3 + 4)"));
        assertNull(answer("* 9"));
        assertNull(answer("17 ++ * 28"));
    }

    @Test public void aBareNumberIsNotACalculation() {
        assertNull(answer("42"));
        assertNull(answer("-5"));
        assertNull("a stated percentage is a figure, not a question", answer("50%"));
        assertNull(answer(""));
        assertNull(answer(null));
    }

    /**
     * The assertion that matters most.
     *
     * <p>Every one of these contains numbers and something that could be read as an operator, and
     * every one of them is a sentence for the provider.
     */
    @Test public void conversationalSentencesAreNeverIntercepted() {
        assertNull(answer("I spent 40 on lunch and 12 on coffee"));
        assertNull(answer("we have 3 people and 2 dogs"));
        assertNull(answer("how do I calculate a percentage"));
        assertNull(answer("what is 20 percent tipping etiquette in the US"));
        assertNull(answer("my flight is 7 30 to 11 45"));
        assertNull(answer("remind me at 10:30"));
        assertNull(answer("version 1.2.3 broke the build"));
        assertNull(answer("explain 2 + 2 to a five year old"));
    }

    @Test public void deviceAndKitchenRequestsAreNotArithmetic() {
        assertNull(answer("set brightness to 50%"));
        assertNull(answer("set a timer for 10 minutes"));
        assertNull(answer("2 cups to ml"));
        assertNull(answer("5 feet in meters"));
    }

    @Test public void veryLongMessagesAreNotSums() {
        StringBuilder long_ = new StringBuilder("1 + 1");
        while (long_.length() < 200) long_.append(" + 1");
        assertNull(answer(long_.toString()));
    }

    // ---- the evaluator on its own -------------------------------------------------------------------

    @Test public void theEvaluatorRefusesRatherThanGuessing() {
        assertEquals(OrbitCalculator.Refusal.DIVIDE_BY_ZERO,
                OrbitCalculator.evaluate("1 / 0").refusal);
        assertEquals(OrbitCalculator.Refusal.NOT_ARITHMETIC,
                OrbitCalculator.evaluate("hello").refusal);
        assertEquals("a value with no operation in it was never a calculation",
                OrbitCalculator.Refusal.NOT_ARITHMETIC, OrbitCalculator.evaluate("42").refusal);
        assertFalse(OrbitCalculator.evaluate("1 / 0").hasValue());
    }

    @Test public void integerSquareRootIsExact() {
        assertEquals(java.math.BigInteger.valueOf(12),
                OrbitCalculator.integerSqrt(java.math.BigInteger.valueOf(144)));
        assertEquals(java.math.BigInteger.valueOf(12),
                OrbitCalculator.integerSqrt(java.math.BigInteger.valueOf(150)));
        assertEquals(java.math.BigInteger.ZERO,
                OrbitCalculator.integerSqrt(java.math.BigInteger.valueOf(-4)));
        java.math.BigInteger big = java.math.BigInteger.TEN.pow(40);
        assertEquals(java.math.BigInteger.TEN.pow(20), OrbitCalculator.integerSqrt(big));
    }

    /** Recognition and answering are the same decision, so canHandle cannot drift from answer. */
    @Test public void recognitionMatchesTheAnswer() {
        assertTrue(CalculatorRouter.canHandle("17 + 28"));
        assertFalse(CalculatorRouter.canHandle("I spent 40 on lunch and 12 on coffee"));
    }
}
