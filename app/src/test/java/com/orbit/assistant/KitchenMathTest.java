package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The arithmetic behind Orbit's kitchen answers.
 *
 * <p>Two things are being protected here. The first is that the numbers are right: every US
 * customary factor is the exact legal definition, so a cup is 236.5882365 ml and a pound is
 * 453.59237 g, and a chain of conversions must not drift away from that. The second is that a cook
 * can read the result — three quarters of a cup scaled by one and a half is exactly nine eighths,
 * and it must read as "1 1/8 cups" and never as 1.1249999999999998.
 *
 * <p>Plain JUnit deliberately: none of this touches Android, and it should stay that way.
 */
public final class KitchenMathTest {

    private static KitchenQuantity q(String text) {
        KitchenQuantity parsed = KitchenQuantity.parse(text);
        assertNotNull("could not read the quantity: " + text, parsed);
        return parsed;
    }

    private static String convert(String amount, KitchenUnit from, KitchenUnit to) {
        return KitchenMath.conversionLine(q(amount), from, to);
    }

    // ---- temperature --------------------------------------------------------------------------

    @Test public void fahrenheitBecomesCelsius() {
        assertEquals("425°F ≈ 218°C", KitchenMath.temperatureLine(q("425"), true));
        assertEquals("350°F ≈ 177°C", KitchenMath.temperatureLine(q("350"), true));
        assertEquals("212°F = 100°C", KitchenMath.temperatureLine(q("212"), true));
        assertEquals("32°F = 0°C", KitchenMath.temperatureLine(q("32"), true));
    }

    @Test public void celsiusBecomesFahrenheit() {
        assertEquals("180°C = 356°F", KitchenMath.temperatureLine(q("180"), false));
        assertEquals("200°C = 392°F", KitchenMath.temperatureLine(q("200"), false));
        assertEquals("100°C = 212°F", KitchenMath.temperatureLine(q("100"), false));
    }

    /** Freezers and the one temperature both scales agree on. */
    @Test public void negativeTemperaturesConvert() {
        assertEquals("-40°F = -40°C", KitchenMath.temperatureLine(q("-40"), true));
        assertEquals("-40°C = -40°F", KitchenMath.temperatureLine(q("-40"), false));
        assertEquals("0°F ≈ -18°C", KitchenMath.temperatureLine(q("0"), true));
        assertEquals("-18°C ≈ 0°F", KitchenMath.temperatureLine(q("-18"), false));
    }

    @Test public void decimalTemperaturesKeepTheirInput() {
        assertEquals("98.6°F = 37°C", KitchenMath.temperatureLine(q("98.6"), true));
        assertEquals("37.5°C ≈ 100°F", KitchenMath.temperatureLine(q("37.5"), false));
    }

    /** An exact result says so; a rounded one is never dressed up as exact. */
    @Test public void roundingIsAdmittedRatherThanHidden() {
        assertTrue(KitchenMath.temperatureLine(q("425"), true).contains("≈"));
        assertTrue(KitchenMath.temperatureLine(q("180"), false).contains("="));
        assertFalse(KitchenMath.temperatureLine(q("180"), false).contains("≈"));
    }

    // ---- fractions ----------------------------------------------------------------------------

    @Test public void everyWrittenFormOfAQuantityIsRead() {
        assertEquals(KitchenQuantity.of(12), q("12"));
        assertEquals(KitchenQuantity.of(3, 2), q("1.5"));
        assertEquals(KitchenQuantity.of(3, 4), q("3/4"));
        assertEquals(KitchenQuantity.of(3, 2), q("1 1/2"));
        assertEquals(KitchenQuantity.of(1, 2), q(".5"));
        assertEquals(KitchenQuantity.of(-3, 2), q("-1.5"));
        assertEquals(KitchenQuantity.of(-3, 2), q("-1 1/2"));
    }

    @Test public void unreadableQuantitiesAreRefused() {
        assertNull(KitchenQuantity.parse("cup"));
        assertNull(KitchenQuantity.parse("1/0"));
        assertNull(KitchenQuantity.parse(""));
        assertNull(KitchenQuantity.parse(null));
        assertNull(KitchenQuantity.parse("1..5"));
    }

    @Test public void fractionsSimplifyToTheirLowestTerms() {
        assertEquals("1/2", q("2/4").mixedNumber());
        assertEquals("2", q("8/4").mixedNumber());
        assertEquals("1 1/2", q("6/4").mixedNumber());
        assertEquals("3/4", q("0.75").mixedNumber());
    }

    /** The reason this class exists rather than a double: the answer must be exact. */
    @Test public void repeatedScalingProducesNoFloatingPointGarbage() {
        KitchenQuantity value = q("3/4").times(q("1.5"));
        assertEquals("1 1/8", value.mixedNumber());
        assertEquals(KitchenQuantity.of(9, 8), value);

        KitchenQuantity third = q("1/3");
        assertEquals(KitchenQuantity.ONE, third.times(KitchenQuantity.of(3)));
        assertEquals("1", third.times(KitchenQuantity.of(3)).mixedNumber());
    }

    @Test public void aMixedNumberSplitsIntoItsWholeAndFractionParts() {
        KitchenQuantity value = q("2 1/4");
        assertEquals("2", value.wholePart().toString());
        assertEquals(KitchenQuantity.of(1, 4), value.fractionPart());
    }

    /** Halves through eighths are measures; a denominator of 1250 is not. */
    @Test public void onlyRealMeasuresAreShownAsFractions() {
        assertTrue(q("3/4").isCookingFraction());
        assertTrue(q("1/3").isCookingFraction());
        assertTrue(q("5/6").isCookingFraction());
        assertTrue(q("7/8").isCookingFraction());
        assertFalse(q("3/7").isCookingFraction());
        assertNull(q("3/7").cookingFraction());
    }

    // ---- volume -------------------------------------------------------------------------------

    @Test public void spoonsAndCupsRelateCorrectly() {
        assertEquals("1 tbsp = 3 tsp", convert("1", KitchenUnit.TBSP, KitchenUnit.TSP));
        assertEquals("1 cup = 16 tbsp", convert("1", KitchenUnit.CUP, KitchenUnit.TBSP));
        assertEquals("1 cup = 48 tsp", convert("1", KitchenUnit.CUP, KitchenUnit.TSP));
        assertEquals("1 cup = 8 fl oz", convert("1", KitchenUnit.CUP, KitchenUnit.FLUID_OUNCE));
    }

    @Test public void pintsAndQuartsRelateCorrectly() {
        assertEquals("1 pint = 2 cups", convert("1", KitchenUnit.PINT, KitchenUnit.CUP));
        assertEquals("1 quart = 2 pints", convert("1", KitchenUnit.QUART, KitchenUnit.PINT));
        assertEquals("1 quart = 4 cups", convert("1", KitchenUnit.QUART, KitchenUnit.CUP));
        assertEquals("2 pints = 1 quart", convert("2", KitchenUnit.PINT, KitchenUnit.QUART));
        assertEquals("1 gallon = 4 quarts", convert("1", KitchenUnit.GALLON, KitchenUnit.QUART));
    }

    /** The exact definitions: an inch is 2.54 cm, so a cup is 236.5882365 ml and nothing else. */
    @Test public void metricVolumeUsesTheExactDefinitions() {
        assertEquals("1 cup ≈ 237 ml", convert("1", KitchenUnit.CUP, KitchenUnit.MILLILITER));
        assertEquals("2 cups ≈ 473 ml", convert("2", KitchenUnit.CUP, KitchenUnit.MILLILITER));
        assertEquals("1 tsp ≈ 4.93 ml", convert("1", KitchenUnit.TSP, KitchenUnit.MILLILITER));
        assertEquals("1 tbsp ≈ 14.8 ml", convert("1", KitchenUnit.TBSP, KitchenUnit.MILLILITER));
        assertEquals("1 fl oz ≈ 29.6 ml", convert("1", KitchenUnit.FLUID_OUNCE, KitchenUnit.MILLILITER));
        assertEquals("1 L ≈ 4.23 cups", convert("1", KitchenUnit.LITER, KitchenUnit.CUP));
    }

    @Test public void fractionalAndDecimalVolumesBothConvert() {
        assertEquals("1/2 cup = 8 tbsp", convert("1/2", KitchenUnit.CUP, KitchenUnit.TBSP));
        assertEquals("3/4 cup = 12 tbsp", convert("3/4", KitchenUnit.CUP, KitchenUnit.TBSP));
        assertEquals("1 1/2 tbsp = 4 1/2 tsp", convert("1.5", KitchenUnit.TBSP, KitchenUnit.TSP));
        assertEquals("6 fl oz = 3/4 cup", convert("6", KitchenUnit.FLUID_OUNCE, KitchenUnit.CUP));
        assertEquals("750 ml ≈ 3.17 cups", convert("750", KitchenUnit.MILLILITER, KitchenUnit.CUP));
    }

    /**
     * A third of a cup is five and a third tablespoons, which nobody can measure. Split into the
     * two spoons that are actually in the drawer, it is exact and usable.
     */
    @Test public void awkwardSpoonAmountsSplitIntoSpoonsYouOwn() {
        assertEquals("1/3 cup = 5 tbsp + 1 tsp", convert("1/3", KitchenUnit.CUP, KitchenUnit.TBSP));
        assertEquals("2/3 cup = 10 tbsp + 2 tsp", convert("2/3", KitchenUnit.CUP, KitchenUnit.TBSP));
    }

    /** The split is only used when it helps; a clean fraction is left alone. */
    @Test public void aCleanSpoonFractionIsNotSplitUpUnnecessarily() {
        assertEquals("1 1/2 tbsp", KitchenMath.format(q("1.5"), KitchenUnit.TBSP).text);
        assertEquals("4 tbsp", KitchenMath.format(q("4"), KitchenUnit.TBSP).text);
        assertEquals("1/3 tbsp", KitchenMath.format(q("1/3"), KitchenUnit.TBSP).text);
    }

    // ---- mass ---------------------------------------------------------------------------------

    @Test public void weightsConvertCorrectly() {
        assertEquals("12 oz ≈ 340 g", convert("12", KitchenUnit.OUNCE, KitchenUnit.GRAM));
        assertEquals("1 lb = 16 oz", convert("1", KitchenUnit.POUND, KitchenUnit.OUNCE));
        assertEquals("1 lb ≈ 454 g", convert("1", KitchenUnit.POUND, KitchenUnit.GRAM));
        assertEquals("1 1/2 lb ≈ 680 g", convert("1.5", KitchenUnit.POUND, KitchenUnit.GRAM));
        assertEquals("500 g ≈ 1.1 lb", convert("500", KitchenUnit.GRAM, KitchenUnit.POUND));
        assertEquals("1 kg ≈ 2.2 lb", convert("1", KitchenUnit.KILOGRAM, KitchenUnit.POUND));
        assertEquals("2 kg ≈ 4.41 lb", convert("2", KitchenUnit.KILOGRAM, KitchenUnit.POUND));
        assertEquals("2.5 kg = 2500 g", convert("2.5", KitchenUnit.KILOGRAM, KitchenUnit.GRAM));
    }

    // ---- what the layer refuses to do ---------------------------------------------------------

    /**
     * Volume is not mass. A cup of flour and a cup of honey weigh very different amounts, and this
     * layer has no density for either, so it returns nothing rather than a confident wrong number.
     */
    @Test public void volumeNeverSilentlyBecomesMass() {
        assertNull(KitchenMath.convert(q("1"), KitchenUnit.CUP, KitchenUnit.GRAM));
        assertNull(KitchenMath.convert(q("1"), KitchenUnit.GRAM, KitchenUnit.CUP));
        assertNull(KitchenMath.convert(q("8"), KitchenUnit.OUNCE, KitchenUnit.FLUID_OUNCE));
        assertNull(KitchenMath.conversionLine(q("2"), KitchenUnit.CUP, KitchenUnit.KILOGRAM));
    }

    // ---- scaling ------------------------------------------------------------------------------

    @Test public void doublingAndHalvingStayReadable() {
        assertEquals("1 1/2 cups",
                KitchenMath.format(KitchenMath.scale(q("3/4"), KitchenQuantity.of(2)),
                        KitchenUnit.CUP).text);
        assertEquals("1 tbsp",
                KitchenMath.format(KitchenMath.scale(q("2"), KitchenQuantity.of(1, 2)),
                        KitchenUnit.TBSP).text);
        assertEquals("3/8 cup",
                KitchenMath.format(KitchenMath.scale(q("3/4"), KitchenQuantity.of(1, 2)),
                        KitchenUnit.CUP).text);
    }

    @Test public void anArbitraryFactorScalesExactly() {
        assertEquals("2 1/4 cups",
                KitchenMath.format(KitchenMath.scale(q("1 1/2"), q("1.5")), KitchenUnit.CUP).text);
        assertEquals("1 1/8 cups",
                KitchenMath.format(KitchenMath.scale(q("3/4"), q("1.5")), KitchenUnit.CUP).text);
        assertEquals("450 g",
                KitchenMath.format(KitchenMath.scale(q("300"), q("1.5")), KitchenUnit.GRAM).text);
    }

    @Test public void servingCountsScaleByTheirRatio() {
        assertEquals(KitchenQuantity.of(3),
                KitchenMath.scaleForServings(q("2"), q("4"), q("6")));
        assertEquals(KitchenQuantity.of(2),
                KitchenMath.scaleForServings(q("3"), q("6"), q("4")));
        assertEquals(KitchenQuantity.of(9, 8),
                KitchenMath.scaleForServings(q("3/4"), q("4"), q("6")));
    }

    @Test public void aZeroServingCountIsRefusedRatherThanDividedBy() {
        assertNull(KitchenMath.scaleForServings(q("2"), q("0"), q("4")));
    }

    // ---- presentation -------------------------------------------------------------------------

    @Test public void unitsAgreeWithTheAmountInFrontOfThem() {
        assertEquals("1 cup", KitchenMath.format(q("1"), KitchenUnit.CUP).text);
        assertEquals("3/4 cup", KitchenMath.format(q("3/4"), KitchenUnit.CUP).text);
        assertEquals("2 cups", KitchenMath.format(q("2"), KitchenUnit.CUP).text);
        assertEquals("1 1/2 cups", KitchenMath.format(q("1.5"), KitchenUnit.CUP).text);
        assertEquals("2 pints", KitchenMath.format(q("2"), KitchenUnit.PINT).text);
        assertEquals("1 quart", KitchenMath.format(q("1"), KitchenUnit.QUART).text);
    }

    @Test public void everySpellingOfAMeasureResolvesToOneUnit() {
        assertEquals(KitchenUnit.TBSP, KitchenUnit.fromAlias("tablespoons"));
        assertEquals(KitchenUnit.TBSP, KitchenUnit.fromAlias("tbsp"));
        assertEquals(KitchenUnit.TSP, KitchenUnit.fromAlias("teaspoon"));
        assertEquals(KitchenUnit.FLUID_OUNCE, KitchenUnit.fromAlias("fl oz"));
        assertEquals(KitchenUnit.OUNCE, KitchenUnit.fromAlias("oz"));
        assertEquals(KitchenUnit.POUND, KitchenUnit.fromAlias("lbs"));
        assertEquals(KitchenUnit.MILLILITER, KitchenUnit.fromAlias("millilitres"));
        assertEquals(KitchenUnit.LITER, KitchenUnit.fromAlias("L"));
        assertEquals(KitchenUnit.KILOGRAM, KitchenUnit.fromAlias("kilos"));
        assertNull(KitchenUnit.fromAlias("furlong"));
    }

    /** "fl oz" is a volume and "oz" is a weight; the longer spelling must win the match. */
    @Test public void fluidOuncesAreNotOunces() {
        assertEquals(KitchenUnit.Dimension.VOLUME, KitchenUnit.fromAlias("fl oz").dimension());
        assertEquals(KitchenUnit.Dimension.MASS, KitchenUnit.fromAlias("oz").dimension());
        assertTrue(KitchenUnit.aliasesLongestFirst().indexOf("fl oz")
                < KitchenUnit.aliasesLongestFirst().indexOf("oz"));
    }
}
