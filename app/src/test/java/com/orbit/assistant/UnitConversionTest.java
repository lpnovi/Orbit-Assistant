package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * General-purpose conversion: the exact values, the restraint, and the promise to the kitchen.
 *
 * <p>Two things are being guarded here at once. The first is arithmetic — every factor is a legal
 * definition, so an inch is exactly 2.54 cm and an acre is exactly 4046.8564224 square metres, and
 * a test that accepted "close enough" would let a rounded constant in. The second is that Kitchen
 * Utilities were not rebuilt: {@link KitchenUnit} still owns cooking, this table owns everything
 * else, and the two share no spelling at all.
 */
public final class UnitConversionTest {

    private static String answer(String text) {
        return UnitConversionRouter.answer(text);
    }

    // ---- exact values ------------------------------------------------------------------------------

    @Test public void lengthUsesLegalDefinitions() {
        assertEquals("1 in = 2.54 cm", answer("1 inch in cm"));
        assertEquals("1 ft = 0.3048 m", answer("1 foot in metres"));
        assertEquals("1 mi = 1.609344 km", answer("1 mile in km"));
        assertEquals("1 yd = 0.9144 m", answer("1 yard to m"));
        assertEquals("100 cm = 1 m", answer("100 cm in m"));
        assertEquals("2500 mm = 2.5 m", answer("2500 mm to m"));
    }

    @Test public void speedUsesLegalDefinitions() {
        assertEquals("60 mph = 96.56064 km/h", answer("60 mph in km/h"));
        assertEquals("100 km/h ≈ 27.7777777778 m/s", answer("100 km/h in m/s"));
        assertEquals("10 m/s = 36 km/h", answer("10 m/s to km/h"));
    }

    @Test public void areaUsesLegalDefinitions() {
        assertEquals("1 acre = 4046.8564224 m2", answer("1 acre in m2"));
        assertEquals("1 ha = 10000 m2", answer("1 hectare in square metres"));
        assertEquals("1 sq ft = 0.09290304 m2", answer("1 square foot in m2"));
        assertEquals("1 m2 = 10000 cm2", answer("1 m2 in cm2"));
        assertEquals("superscripts and carets are the same spelling",
                "1 m2 = 10000 cm2", answer("1 m² in cm²"));
    }

    @Test public void storageDistinguishesDecimalFromBinary() {
        String gbToMb = answer("1 GB in MB");
        assertNotNull(gbToMb);
        assertTrue(gbToMb.startsWith("1 GB = 1000 MB"));
        assertTrue("SI units must say so", gbToMb.contains("SI decimal"));

        String gibToMib = answer("1 GiB in MiB");
        assertNotNull(gibToMib);
        assertTrue(gibToMib.startsWith("1 GiB = 1024 MiB"));
        assertTrue("binary units must say so", gibToMib.contains("1024"));

        String across = answer("1 GiB in GB");
        assertNotNull(across);
        assertTrue("crossing the two conventions must state both",
                across.contains("SI decimal") && across.contains("binary"));
        assertTrue(across.startsWith("1 GiB = 1.073741824 GB"));
    }

    // ---- how people write it ------------------------------------------------------------------------

    @Test public void aliasesPluralsAndCapitalisation() {
        assertEquals("5 ft = 1.524 m", answer("5 feet in meters"));
        assertEquals("5 ft = 1.524 m", answer("5 FEET IN METRES"));
        assertEquals("5 ft = 1.524 m", answer("Convert 5 ft to m"));
        assertEquals("5 ft = 1.524 m", answer("five feet in metres"));
        assertEquals("3 in = 7.62 cm", answer("how many cm are in 3 inches"));
    }

    @Test public void everyConnectiveWorks() {
        for (String connective : new String[]{"in", "to", "into", "as"}) {
            assertEquals(connective + " must be a conversion request",
                    "1 mi = 1.609344 km", answer("1 mile " + connective + " km"));
        }
    }

    // ---- restraint ----------------------------------------------------------------------------------

    /** A measurement in a sentence is a description, not a request to convert it. */
    @Test public void descriptionsAreNotConversionRequests() {
        assertNull(answer("a 5 foot table"));
        assertNull(answer("I walked 3 miles today"));
        assertNull(answer("the car does 60 mph"));
        assertNull(answer("my phone has 128 GB"));
        assertNull(answer("what is a hectare"));
        assertNull(answer("how many miles should I run a week"));
    }

    @Test public void mismatchedDimensionsAreRefused() {
        assertNull("a length is not a speed", answer("5 km in mph"));
        assertNull("an area is not a length", answer("5 m2 in m"));
        assertNull("storage is not distance", answer("5 GB in km"));
        assertNull("converting a unit into itself asks nothing", answer("5 km in km"));
    }

    @Test public void deviceAndKitchenTerritoryIsLeftAlone() {
        assertNull(answer("set a 5 minute timer in the kitchen"));
        assertNull(answer("what is my battery in percent"));
        assertNull(answer("set brightness to 5 m"));
    }

    // ---- the kitchen is untouched --------------------------------------------------------------------

    /**
     * The two tables cannot collide, because they share no spelling.
     *
     * <p>This is the assertion that keeps Kitchen Utilities safe from the general table. If a
     * future unit is added here that a cook also writes — a litre, an ounce — this fails, and it
     * should: the kitchen router runs first and would silently keep taking those requests while
     * this one looked like it handled them.
     */
    @Test public void noSpellingIsClaimedByBothTables() {
        Set<String> kitchen = new HashSet<>(KitchenUnit.aliasesLongestFirst());
        for (String alias : MeasureUnit.aliasesLongestFirst()) {
            assertFalse("\"" + alias + "\" is claimed by both KitchenUnit and MeasureUnit",
                    kitchen.contains(alias));
        }
    }

    /** And the kitchen's own answers are byte-for-byte what they were. */
    @Test public void kitchenConversionsAreUnchanged() {
        assertEquals("2 cups ≈ 473 ml", KitchenMathRouter.answer("2 cups to ml"));
        assertEquals("1/3 cup = 5 tbsp + 1 tsp", KitchenMathRouter.answer("1/3 cup in tbsp"));
        assertEquals("500 g ≈ 1.1 lb", KitchenMathRouter.answer("500g in pounds"));
        assertEquals("425°F ≈ 218°C", KitchenMathRouter.answer("425f in celsius"));
    }

    /** The cup-into-grams guard is exactly as careful as it was. */
    @Test public void theDensityGuardIsUnchanged() {
        assertEquals(KitchenMathRouter.INGREDIENT_NEEDED,
                KitchenMathRouter.answer("2 cups in grams"));
        assertNull("a named ingredient is a real question for the provider",
                KitchenMathRouter.answer("2 cups of flour in grams"));
    }

    // ---- the table itself ------------------------------------------------------------------------------

    @Test public void everyUnitConvertsToItsOwnBaseExactly() {
        assertEquals(KitchenQuantity.of(254, 10000), MeasureUnit.INCH.toBase());
        assertEquals(KitchenQuantity.of(1609344, 1000), MeasureUnit.MILE.toBase());
        assertEquals(KitchenQuantity.of(40468564224L, 10000000L), MeasureUnit.ACRE.toBase());
        assertEquals(KitchenQuantity.of(1024), MeasureUnit.KIBIBYTE.toBase());
        assertEquals(KitchenQuantity.of(1000), MeasureUnit.KILOBYTE.toBase());
    }

    @Test public void unknownSpellingsResolveToNothing() {
        assertNull(MeasureUnit.fromAlias("furlong"));
        assertNull(MeasureUnit.fromAlias(""));
        assertNull(MeasureUnit.fromAlias(null));
        assertEquals(MeasureUnit.FOOT, MeasureUnit.fromAlias("FEET"));
        assertEquals(MeasureUnit.MILES_PER_HOUR, MeasureUnit.fromAlias("mph"));
    }

    @Test public void recognitionMatchesTheAnswer() {
        assertTrue(UnitConversionRouter.canHandle("1 mile in km"));
        assertFalse(UnitConversionRouter.canHandle("a 5 foot table"));
    }
}
