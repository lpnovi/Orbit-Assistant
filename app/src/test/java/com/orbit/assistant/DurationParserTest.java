package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The arithmetic behind every Orbit timer.
 *
 * <p>Two of these are the exact phrases that failed on a Galaxy S25 Ultra: "4 and a half minutes"
 * and "4 minutes and 30 seconds" both produced a four-minute timer, because the parser they went
 * through read one count and one unit and stopped. Both are pinned here permanently, alongside the
 * ordinary forms that already worked, because the failure mode was silent — 240 is a perfectly
 * plausible number of seconds, and nothing downstream could tell it was wrong.
 */
public final class DurationParserTest {

    private static void expect(long seconds, String phrase) {
        assertEquals(phrase, seconds, DurationParser.parseSeconds(phrase));
    }

    // ---- the two device failures ----------------------------------------------------------------

    /** The reported bug, in its own test, so it can never quietly come back. */
    @Test public void fourMinutesAndThirtySecondsIsTwoHundredAndSeventy() {
        expect(270L, "4 minutes and 30 seconds");
    }

    /** The other reported bug: a fraction the old parser could not read at all. */
    @Test public void fourAndAHalfMinutesIsTwoHundredAndSeventy() {
        expect(270L, "4 and a half minutes");
    }

    /**
     * The third device failure, and the reason this file exists a second time.
     *
     * <p>Typed on a Galaxy S25 Ultra against 0.7.8.2 Stable: Orbit answered "Setting a 5-minute
     * timer" and Samsung Clock received 5:00. The tokenizer stripped every character it did not
     * recognise, and the solidus was one of them, so "1/2" reached the scan as the two separate
     * tokens "1" and "2". The "and 1" was then read as an addend of one whole minute and the
     * stray "2" was discarded, which is how four and a half became five.
     */
    @Test public void fourAndOneHalfMinutesIsTwoHundredAndSeventy() {
        expect(270L, "4 and 1/2 minutes");
    }

    /** The same duration in every way a person writes it, all reaching the same seconds. */
    @Test public void everyWayOfWritingFourAndAHalfMinutesAgrees() {
        for (String phrase : new String[]{
                "4 and 1/2 minutes", "4 1/2 minutes", "4\u00BD minutes", "4 \u00BD minutes",
                "4 and a half minutes", "4.5 minutes", "4 minutes and 30 seconds"}) {
            expect(270L, phrase);
        }
    }

    @Test public void writtenFractionsAreUnderstood() {
        expect(270L, "4 and 1/2 minutes");
        expect(270L, "4 1/2 minutes");
        expect(90L, "1 and 1/2 minutes");
        expect(90L, "1 1/2 minutes");
        expect(30L, "1/2 minute");
        expect(15L, "1/4 minute");
        expect(45L, "3/4 minute");
        expect(135L, "2 and 1/4 minutes");
        expect(165L, "2 and 3/4 minutes");
        expect(1800L, "1/2 hour");
        expect(2700L, "3/4 hour");
        // Generic rather than a shortlist: nothing about thirds is harder than halves.
        expect(1200L, "1/3 hour");
        expect(2400L, "2/3 hour");
    }

    @Test public void unicodeFractionsAreUnderstood() {
        expect(270L, "4\u00BD minutes");
        expect(270L, "4 \u00BD minutes");
        expect(90L, "1\u00BD minutes");
        expect(30L, "\u00BD minute");
        expect(15L, "\u00BC minute");
        expect(45L, "\u00BE minute");
        expect(1800L, "\u00BD hour");
        expect(5400L, "1\u00BD hours");
    }

    /**
     * A mixed number adds; a spoken fraction after a count multiplies.
     *
     * <p>These two rules genuinely disagree, and both are right for the phrasing they belong to.
     * Nobody writes "3 1/4" meaning three quarters, and nobody says "three quarters" meaning 3.25.
     */
    @Test public void aWrittenFractionAddsWhileASpokenOneMultiplies() {
        expect(195L, "3 1/4 minutes");
        expect(2700L, "three quarters of an hour");
        expect(6300L, "one and three quarters hours");
    }

    /** A fraction that is not a number is not quietly turned into one. */
    @Test public void malformedFractionsStateNoDuration() {
        for (String phrase : new String[]{"1/0 minutes", "1/ minutes", "/2 minutes",
                "1/2/3 minutes", "-1/2 minutes", "0/2 minutes", "1/1000 minutes",
                "1000/2 minutes"}) {
            assertEquals(phrase, DurationParser.INVALID, DurationParser.parseSeconds(phrase));
        }
    }

    /** A date is not a duration, and the number beside it still belongs to the timer. */
    @Test public void aDateShapedTokenIsNotAbsorbedIntoADuration() {
        assertEquals(DurationParser.INVALID, DurationParser.parseSeconds("9/11"));
        assertEquals(300L, DurationParser.parseFirstRun("on 9/11 set a timer for 5 minutes").seconds);
    }

    // ---- every required form ----------------------------------------------------------------------

    @Test public void mixedUnitsAreSummedRatherThanTruncated() {
        expect(270L, "4 minutes 30 seconds");
        expect(270L, "4 minutes and 30 seconds");
        expect(5400L, "1 hour 30 minutes");
        expect(5400L, "1 hour and 30 minutes");
        expect(3900L, "1 hour 5 minutes");
        expect(3930L, "1 hour 5 minutes 30 seconds");
        expect(3930L, "one hour five minutes and thirty seconds");
    }

    @Test public void fractionsAreUnderstood() {
        expect(270L, "4 and a half minutes");
        expect(90L, "a minute and a half");
        expect(90L, "one and a half minutes");
        expect(135L, "2 and a quarter minutes");
        expect(5400L, "an hour and a half");
        expect(6300L, "one and three quarters hours");
        expect(1800L, "half an hour");
        expect(900L, "a quarter of an hour");
    }

    @Test public void decimalsAreUnderstood() {
        expect(270L, "4.5 minutes");
        expect(135L, "2.25 minutes");
        expect(5400L, "1.5 hours");
        expect(30L, "0.5 minutes");
    }

    /** Nothing that already worked is allowed to stop working. */
    @Test public void theOrdinaryFormsAreUnchanged() {
        expect(300L, "5 minutes");
        expect(7200L, "2 hours");
        expect(30L, "30 seconds");
        expect(90L, "90 seconds");
        expect(60L, "1 minute");
        expect(300L, "five minutes");
        expect(1200L, "20 mins");
        expect(10800L, "3 hrs");
        expect(45L, "45 secs");
        expect(1200L, "20-minute");
    }

    // ---- what is not a duration -------------------------------------------------------------------

    @Test public void unrelatedTextStatesNoDuration() {
        for (String phrase : new String[]{"", "   ", "turn on the flashlight", "5", "minutes",
                "banana", "a few", "some minutes", "half", "and", "page 31 of 388"}) {
            assertEquals(phrase, DurationParser.INVALID, DurationParser.parseSeconds(phrase));
            assertFalse(phrase, DurationParser.hasDuration(phrase));
        }
    }

    /** A number that is not attached to a unit is not silently adopted by a nearby one. */
    @Test public void aLooseNumberIsNotAbsorbed() {
        expect(300L, "5 minutes and open 3");
        expect(300L, "5 minutes then 7");
    }

    @Test public void impossibleAndOutOfRangeValuesAreRefused() {
        assertEquals(DurationParser.INVALID, DurationParser.parseSeconds("0 minutes"));
        assertEquals(DurationParser.INVALID, DurationParser.parseSeconds("0 seconds"));
        assertEquals(DurationParser.INVALID, DurationParser.parseSeconds("-5 minutes"));
        assertEquals(DurationParser.INVALID, DurationParser.parseSeconds("25 hours"));
        assertEquals(DurationParser.INVALID, DurationParser.parseSeconds("100000 seconds"));
        assertEquals(DurationParser.INVALID,
                DurationParser.parseSeconds("999999999999999999999 minutes"));
        assertEquals(DurationParser.INVALID, DurationParser.parseSeconds("NaN minutes"));
        assertEquals(DurationParser.INVALID, DurationParser.parseSeconds("Infinity minutes"));
        assertEquals(DurationParser.INVALID, DurationParser.parseSeconds(null));
    }

    /** Exactly a day is allowed; a second past it is not. */
    @Test public void theCeilingIsADay() {
        expect(86_400L, "24 hours");
        assertEquals(DurationParser.INVALID,
                DurationParser.parseSeconds("24 hours and 1 second"));
    }

    // ---- runs -------------------------------------------------------------------------------------

    /** A chained command's second action keeps its own numbers. */
    @Test public void onlyOneRunIsTakenFromASentence() {
        assertEquals(1200L,
                DurationParser.parseFirstRun("for 20 minutes and turn on the flashlight").seconds);
        assertEquals(1200L,
                DurationParser.parseFirstRun("for 20 minutes and remind me in 5 minutes").seconds);
        assertEquals(270L,
                DurationParser.parseFirstRun("for 4 minutes and 30 seconds").seconds);
    }

    @Test public void anAdjacentDurationMustActuallyBeAdjacent() {
        assertEquals(1200L, DurationParser.parseAdjacentAfter(" for 20 minutes").seconds);
        assertEquals(1200L, DurationParser.parseAdjacentAfter(" 20 minutes").seconds);
        assertEquals(270L, DurationParser.parseAdjacentAfter(" for 4 and a half minutes").seconds);
        assertEquals(DurationParser.INVALID,
                DurationParser.parseAdjacentAfter(" for the potatoes 20 minutes").seconds);
    }

    /** Read backwards, so a duration mentioned earlier belongs to whatever it was said about. */
    @Test public void aTrailingDurationTakesOnlyTheRunTouchingTheKeyword() {
        assertEquals(1200L, DurationParser.parseTrailingBefore("set a 20 minute ").seconds);
        assertEquals(270L, DurationParser.parseTrailingBefore("set a 4 minute 30 second ").seconds);
        assertEquals(1200L,
                DurationParser.parseTrailingBefore("i have 5 minutes set a 20 minute ").seconds);
        assertEquals(DurationParser.INVALID, DurationParser.parseTrailingBefore("set a ").seconds);
    }

    // ---- how it is written back -------------------------------------------------------------------

    /** The unit the user said survives, so 90 minutes is not restated as an hour and a half. */
    @Test public void aSingleUnitIsReportedAsTheUserSaidIt() {
        DurationParser.Parsed ninety = DurationParser.parse("90 minutes");
        assertTrue(ninety.singleUnit);
        assertEquals(90L, ninety.count);
        assertEquals("minute", ninety.unit);

        DurationParser.Parsed mixed = DurationParser.parse("4 minutes and 30 seconds");
        assertFalse("a duration spanning units has no single original form", mixed.singleUnit);

        DurationParser.Parsed fractional = DurationParser.parse("4.5 minutes");
        assertFalse("nor does a fractional one", fractional.singleUnit);
    }

    @Test public void theCompactCardLabelShowsEveryPart() {
        assertEquals("4m 30s", DurationParser.compactLabel(270));
        assertEquals("1h 5m 30s", DurationParser.compactLabel(3930));
        assertEquals("5m", DurationParser.compactLabel(300));
        assertEquals("2h", DurationParser.compactLabel(7200));
        assertEquals("30s", DurationParser.compactLabel(30));
        assertEquals("1h 30m", DurationParser.compactLabel(5400));
    }

    @Test public void theSpokenModifierReadsLikeEnglish() {
        assertEquals("4 minute 30 second", DurationParser.spokenModifier(270));
        assertEquals("1 hour 5 minute 30 second", DurationParser.spokenModifier(3930));
        assertEquals("20-minute", DurationParser.spokenModifier(1200));
        assertEquals("2-hour", DurationParser.spokenModifier(7200));
        assertEquals("30-second", DurationParser.spokenModifier(30));
        // Under two minutes people still count in seconds, so this keeps the compact form.
        assertEquals("90-second", DurationParser.spokenModifier(90));
    }
}
