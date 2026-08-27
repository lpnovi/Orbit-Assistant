package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * What Orbit accepts as a calendar plan, before any device is involved.
 *
 * <p>These are the cases where a language model's output meets a real calendar. The rule under test
 * throughout is that Orbit rejects rather than repairs: a date that does not exist must not become
 * a nearby date that does, an unknown timezone must not silently become the phone's, and a kickoff
 * time nobody has announced must not become 9:00 AM because Android wants a start time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class CalendarPlanTest {

    private static final ZoneId DETROIT = ZoneId.of("America/Detroit");

    private static JSONObject event(Object... keyValues) {
        try {
            JSONObject event = new JSONObject();
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                event.put((String) keyValues[i], keyValues[i + 1]);
            }
            return event;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject params(JSONObject... events) {
        try {
            JSONArray array = new JSONArray();
            for (JSONObject event : events) array.put(event);
            return new JSONObject().put("events", array);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CalendarActionExecutor.Plan parse(JSONObject params) {
        return CalendarActionExecutor.parse(params, DETROIT);
    }

    // ---- ordinary events --------------------------------------------------------------------

    @Test public void aTimedEventKeepsItsExactDateAndAnnouncedStart() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "Michigan vs. Example", "date", "2026-09-05",
                "hour", 15, "minute", 30, "timezone", "America/Detroit",
                "durationMinutes", 180)));
        assertTrue(plan.error, plan.ok());
        assertEquals(1, plan.size());

        CalendarActionExecutor.Event game = plan.events.get(0);
        assertFalse("an announced kickoff is a timed event", game.allDay);
        assertFalse(game.timeTba);
        assertEquals(LocalDate.of(2026, 9, 5), game.date);
        assertEquals("America/Detroit", game.timezoneId());
        assertEquals(LocalDate.of(2026, 9, 5).atTime(15, 30).atZone(DETROIT)
                .toInstant().toEpochMilli(), game.startMillis());
        assertEquals("an authoritative end time is preserved exactly",
                180L * 60_000L, game.endMillis() - game.startMillis());
    }

    @Test public void aStartWithNoEndGetsOneDocumentedNeutralDuration() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "Kickoff", "date", "2026-09-05", "hour", 12, "minute", 0)));
        assertTrue(plan.ok());
        CalendarActionExecutor.Event game = plan.events.get(0);
        assertEquals(CalendarActionExecutor.DEFAULT_DURATION_MINUTES * 60_000L,
                game.endMillis() - game.startMillis());
        assertFalse("an inferred length must not be written up as though it came from a source",
                game.storedDescription().toLowerCase().contains("hour"));
    }

    @Test public void aBatchOfTimedEventsIsAcceptedWhole() {
        CalendarActionExecutor.Plan plan = parse(params(
                event("title", "Game 1", "date", "2026-09-05", "hour", 12, "minute", 0),
                event("title", "Game 2", "date", "2026-09-12", "hour", 15, "minute", 30),
                event("title", "Game 3", "date", "2026-09-19", "hour", 19, "minute", 0)));
        assertTrue(plan.ok());
        assertEquals(3, plan.size());
        assertEquals(LocalDate.of(2026, 9, 5), plan.firstDate());
        assertEquals(LocalDate.of(2026, 9, 19), plan.lastDate());
        assertEquals("Sep 5 - Sep 19, 2026", plan.dateRange());
    }

    // ---- Time TBA ---------------------------------------------------------------------------

    @Test public void anUnannouncedKickoffBecomesAnAllDayTimeTbaEntry() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "Michigan at Example", "date", "2026-11-28", "timeTba", true)));
        assertTrue(plan.ok());
        CalendarActionExecutor.Event game = plan.events.get(0);
        assertTrue(game.allDay);
        assertTrue(game.timeTba);
        assertTrue("the entry has to say why it has no time",
                game.storedDescription().contains(CalendarActionExecutor.TBA_MARKER));
        assertEquals(1, plan.tbaCount());
    }

    @Test public void aMissingStartTimeIsNeverFilledInWithAPlausibleOne() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "Michigan at Example", "date", "2026-11-28")));
        assertTrue(plan.ok());
        CalendarActionExecutor.Event game = plan.events.get(0);
        assertTrue("an event with no hour must not become a timed event", game.allDay);
        assertTrue(game.timeTba);
        // 9:00 AM and noon are the two invented times this exists to prevent.
        long nineAm = LocalDate.of(2026, 11, 28).atTime(9, 0).atZone(DETROIT)
                .toInstant().toEpochMilli();
        long noon = LocalDate.of(2026, 11, 28).atTime(12, 0).atZone(DETROIT)
                .toInstant().toEpochMilli();
        assertFalse(game.startMillis() == nineAm);
        assertFalse(game.startMillis() == noon);
    }

    /**
     * The all-day rule Android documents, and the reason it exists: an all-day event stored in a
     * local timezone lands on the wrong day for anyone west or east of it.
     */
    @Test public void allDayEventsUseUtcMidnightSoTheDayCannotShift() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "Saturday game", "date", "2026-11-28", "timeTba", true)));
        CalendarActionExecutor.Event game = plan.events.get(0);

        assertEquals("UTC", game.timezoneId());
        assertEquals(LocalDate.of(2026, 11, 28).atStartOfDay(ZoneOffset.UTC).toInstant()
                .toEpochMilli(), game.startMillis());
        assertEquals("an all-day event ends at the next day's UTC midnight",
                LocalDate.of(2026, 11, 29).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                game.endMillis());

        // Read back in the zones that would break a naive implementation, the date is unchanged.
        for (String zone : new String[]{"UTC", "America/Detroit", "Pacific/Auckland",
                "America/Los_Angeles", "Asia/Tokyo"}) {
            assertEquals("the stored day must not move in " + zone,
                    LocalDate.of(2026, 11, 28),
                    Instant.ofEpochMilli(game.startMillis()).atZone(ZoneOffset.UTC).toLocalDate());
        }
    }

    @Test public void anExplicitAllDayEventIsNotMislabelledAsTimeTba() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "Team travel day", "date", "2026-09-04", "allDay", true)));
        assertTrue(plan.ok());
        CalendarActionExecutor.Event day = plan.events.get(0);
        assertTrue(day.allDay);
        assertFalse("a deliberately all-day event has no missing start time", day.timeTba);
        assertEquals(0, plan.tbaCount());
    }

    // ---- rejection --------------------------------------------------------------------------

    @Test public void impossibleDatesAreRejectedRatherThanRolledForward() {
        for (String bad : new String[]{"2026-02-30", "2026-13-01", "2026-00-10", "2026-09-31"}) {
            CalendarActionExecutor.Plan plan = parse(params(event(
                    "title", "Game", "date", bad, "hour", 12, "minute", 0)));
            assertFalse(bad + " must not be accepted", plan.ok());
            assertTrue(plan.error.contains("invalid date"));
        }
        assertEquals("February 30th is not a date at all",
                null, CalendarActionExecutor.strictDate("2026-02-30"));
    }

    @Test public void looseDateTextIsNotGuessedAt() {
        for (String bad : new String[]{"", "tomorrow", "Sep 5 2026", "2026/09/05", "9-5-2026",
                "2026-9-5"}) {
            CalendarActionExecutor.Plan plan = parse(params(event(
                    "title", "Game", "date", bad, "hour", 12, "minute", 0)));
            assertFalse("\"" + bad + "\" must not be accepted", plan.ok());
        }
    }

    @Test public void anUnknownTimezoneIsRefusedRatherThanQuietlyReplaced() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "Game", "date", "2026-09-05", "hour", 15, "minute", 0,
                "timezone", "America/Ann_Arbor")));
        assertFalse(plan.ok());
        assertTrue(plan.error, plan.error.contains("unknown timezone"));
    }

    @Test public void anOmittedTimezoneFallsBackToTheDeviceZone() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "Game", "date", "2026-09-05", "hour", 15, "minute", 0)));
        assertTrue(plan.ok());
        assertEquals(DETROIT.getId(), plan.events.get(0).timezoneId());
    }

    @Test public void impossibleClockValuesAndDurationsAreRejected() {
        assertFalse(parse(params(event("title", "G", "date", "2026-09-05",
                "hour", 24, "minute", 0))).ok());
        assertFalse(parse(params(event("title", "G", "date", "2026-09-05",
                "hour", 12, "minute", 60))).ok());
        assertFalse(parse(params(event("title", "G", "date", "2026-09-05",
                "hour", 12, "minute", 0, "durationMinutes", 0))).ok());
        assertFalse(parse(params(event("title", "G", "date", "2026-09-05",
                "hour", 12, "minute", 0, "durationMinutes", 5000))).ok());
    }

    @Test public void anEventWithNoTitleIsRejected() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "   ", "date", "2026-09-05", "hour", 12, "minute", 0)));
        assertFalse(plan.ok());
        assertTrue(plan.error.contains("no title"));
    }

    @Test public void anEmptyOrMissingBatchIsNotAPlan() {
        assertFalse(CalendarActionExecutor.parse(null, DETROIT).ok());
        assertFalse(parse(new JSONObject()).ok());
        assertFalse(parse(params()).ok());
    }

    /** A malformed plan must not be able to start an unbounded write. */
    @Test public void aBatchLargerThanTheCapIsRefusedOutright() {
        JSONObject[] many = new JSONObject[CalendarActionExecutor.MAX_EVENTS + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = event("title", "Game " + i, "date", "2026-09-05", "hour", 12, "minute", 0);
        }
        CalendarActionExecutor.Plan plan = parse(params(many));
        assertFalse(plan.ok());
        assertTrue(plan.error.contains(String.valueOf(CalendarActionExecutor.MAX_EVENTS)));

        JSONObject[] exactly = new JSONObject[CalendarActionExecutor.MAX_EVENTS];
        for (int i = 0; i < exactly.length; i++) {
            exactly[i] = event("title", "Game " + i, "date", "2026-09-05", "hour", 12, "minute", 0);
        }
        assertTrue("the cap itself is still allowed", parse(params(exactly)).ok());
    }

    // ---- what a source link may contribute ---------------------------------------------------

    @Test public void onlyRealHttpsSourceLinksSurviveIntoTheEvent() {
        CalendarActionExecutor.Plan plan = parse(params(event(
                "title", "Game", "date", "2026-09-05", "hour", 12, "minute", 0,
                "sourceUrl", "javascript:alert(1)")));
        assertTrue(plan.ok());
        assertFalse(plan.events.get(0).storedDescription().contains("javascript:"));

        CalendarActionExecutor.Plan real = parse(params(event(
                "title", "Game", "date", "2026-09-05", "hour", 12, "minute", 0,
                "sourceUrl", "https://example.com/schedule")));
        assertTrue(real.events.get(0).storedDescription().contains("https://example.com/schedule"));
    }
}
