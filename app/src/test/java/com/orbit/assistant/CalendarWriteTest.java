package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowContentResolver;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Orbit writing to a calendar, and refusing to.
 *
 * <p>The acceptance criterion for 0.7.7.7 is not "Orbit opened the calendar app". It is that after
 * a confirmation and a real permission grant, Orbit itself persists the events, verifies them,
 * avoids duplicates, and reports what actually happened. Each of those four is a case here, and so
 * is each way the whole thing must instead write nothing at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class CalendarWriteTest {

    private static final int OWNER = CalendarContract.Calendars.CAL_ACCESS_OWNER;
    private static final int CONTRIBUTOR = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
    private static final int READ_ONLY = CalendarContract.Calendars.CAL_ACCESS_READ;

    private Context context;
    private FakeCalendarProvider provider;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitCalendarStore.forgetTarget(context);
        DiagnosticStore.prefs(context).edit().clear().commit();
        provider = new FakeCalendarProvider();
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider);
        grantCalendar();
    }

    @After public void tearDown() {
        OrbitCalendarStore.forgetTarget(context);
    }

    private void grantCalendar() {
        shadowApp().grantPermissions(Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR);
    }

    private void denyCalendar() {
        shadowApp().denyPermissions(Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR);
    }

    private ShadowApplication shadowApp() {
        return Shadows.shadowOf((Application) RuntimeEnvironment.getApplication());
    }

    private void personalOnly() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                1L, "Personal", "me@example.com", OWNER, true));
    }

    private void personalHolidaysAndWork() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                1L, "Personal", "me@example.com", OWNER, true));
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                2L, "Holidays", "holidays@example.com", "someone@example.com", CONTRIBUTOR, false));
        // A shared team calendar the user may write to but does not own: writable, not primary.
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                3L, "Work", "me@example.com", "team@example.com", CONTRIBUTOR, false));
    }

    private static JSONObject game(String title, String date, Integer hour, Integer minute) {
        try {
            JSONObject event = new JSONObject().put("title", title).put("date", date)
                    .put("timezone", "America/Detroit");
            if (hour == null) event.put("timeTba", true);
            else event.put("hour", (int) hour).put("minute", minute == null ? 0 : (int) minute);
            return event;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject batch(JSONObject... events) {
        try {
            JSONArray array = new JSONArray();
            for (JSONObject event : events) array.put(event);
            return new JSONObject().put("events", array);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject michiganSchedule() {
        return batch(
                game("Michigan vs. Example State", "2026-09-05", 12, 0),
                game("Michigan at Example Tech", "2026-09-12", 15, 30),
                game("Michigan vs. Example A&M", "2026-09-19", null, null));
    }

    private DeviceActionExecutor.Result run(JSONObject params) {
        return CalendarActionExecutor.execute(context, params);
    }

    // ---- nothing may be written without permission --------------------------------------------

    @Test public void permissionDeniedWritesNothingAtAll() {
        personalOnly();
        denyCalendar();

        DeviceActionExecutor.Result result = run(michiganSchedule());

        assertEquals(DeviceActionExecutor.STATUS_PERMISSION, result.status);
        assertFalse(result.success);
        assertEquals("no insert may even be attempted", 0, provider.insertAttempts);
        assertTrue(provider.events.isEmpty());
        assertFalse("a permission prompt is not a success",
                result.message.toLowerCase().contains("added"));
    }

    @Test public void halfAGrantIsNotAGrant() {
        personalOnly();
        denyCalendar();
        shadowApp().grantPermissions(Manifest.permission.WRITE_CALENDAR);

        assertFalse("writing without reading cannot verify anything",
                OrbitCalendarStore.hasAccess(context));
        DeviceActionExecutor.Result result = run(michiganSchedule());
        assertEquals(DeviceActionExecutor.STATUS_PERMISSION, result.status);
        assertEquals(0, provider.insertAttempts);
    }

    // ---- calendar discovery and target choice ---------------------------------------------------

    @Test public void onlyWritableCalendarsAreOffered() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                1L, "Personal", "me@example.com", OWNER, true));
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                9L, "Subscribed sports", "feed@example.com", "feed@example.com", READ_ONLY, false));

        List<OrbitCalendarStore.Target> writable = OrbitCalendarStore.writableCalendars(context);
        assertEquals(1, writable.size());
        assertEquals("Personal", writable.get(0).label());
    }

    @Test public void theOnlyWritableCalendarIsUsedWithoutAsking() {
        personalOnly();
        assertNotNull(OrbitCalendarStore.resolveTarget(context));
        assertFalse(OrbitCalendarStore.needsChooser(context));

        DeviceActionExecutor.Result result = run(batch(game("Game", "2026-09-05", 12, 0)));
        assertTrue(result.message, result.success);
        assertEquals(1, provider.eventsIn(1L).size());
    }

    @Test public void oneClearPrimaryIsPreferredOverOtherWritableCalendars() {
        personalHolidaysAndWork();
        OrbitCalendarStore.Target target = OrbitCalendarStore.resolveTarget(context);
        assertNotNull(target);
        assertEquals("Personal", target.label());
    }

    /** Several writable calendars and nothing clearly primary is the user's decision, not Orbit's. */
    @Test public void ambiguousCalendarsAskInsteadOfGuessing() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                2L, "Holidays", "holidays@example.com", "other@example.com", CONTRIBUTOR, false));
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                3L, "Work", "work@example.com", "other@example.com", CONTRIBUTOR, false));

        assertNull(OrbitCalendarStore.resolveTarget(context));
        assertTrue(OrbitCalendarStore.needsChooser(context));

        DeviceActionExecutor.Result result = run(michiganSchedule());
        assertEquals(DeviceActionExecutor.STATUS_UNAVAILABLE, result.status);
        assertEquals("an unresolved destination must not be written to", 0, provider.insertAttempts);
    }

    @Test public void aChosenCalendarIsRememberedAndUsed() {
        personalHolidaysAndWork();
        OrbitCalendarStore.rememberTarget(context, 3L);

        DeviceActionExecutor.Result result = run(batch(game("Standup", "2026-09-05", 9, 0)));
        assertTrue(result.message, result.success);
        assertTrue(result.message.contains("Work"));
        assertEquals(1, provider.eventsIn(3L).size());
        assertTrue(provider.eventsIn(1L).isEmpty());
    }

    @Test public void aRememberedCalendarThatIsGoneFallsBackToTheClearDefault() {
        personalHolidaysAndWork();
        OrbitCalendarStore.rememberTarget(context, 404L);

        OrbitCalendarStore.Target target = OrbitCalendarStore.resolveTarget(context);
        assertNotNull(target);
        assertEquals("Personal", target.label());
    }

    @Test public void noWritableCalendarWritesNothingAndSaysWhy() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                9L, "Subscribed sports", "feed@example.com", "feed@example.com", READ_ONLY, false));

        DeviceActionExecutor.Result result = run(michiganSchedule());
        assertEquals(DeviceActionExecutor.STATUS_UNAVAILABLE, result.status);
        assertFalse(result.success);
        assertTrue(result.message.toLowerCase().contains("no writable calendar"));
        assertEquals(0, provider.insertAttempts);
    }

    // ---- the write itself -----------------------------------------------------------------------

    @Test public void aSingleTimedEventIsStoredWithItsRealStartAndZone() {
        personalOnly();
        DeviceActionExecutor.Result result = run(batch(
                game("Michigan vs. Example State", "2026-09-05", 15, 30)));

        assertTrue(result.message, result.success);
        assertEquals("Added 1 event to Personal.", result.message);

        List<FakeCalendarProvider.Event> stored = provider.eventsIn(1L);
        assertEquals(1, stored.size());
        FakeCalendarProvider.Event event = stored.get(0);
        assertEquals(0, event.allDay);
        assertEquals("America/Detroit", event.timezone);
        assertEquals(LocalDate.of(2026, 9, 5).atTime(15, 30)
                .atZone(ZoneId.of("America/Detroit")).toInstant().toEpochMilli(), event.dtStart);
    }

    @Test public void aWholeScheduleIsWrittenFromOneAction() {
        personalOnly();
        DeviceActionExecutor.Result result = run(michiganSchedule());

        assertTrue(result.message, result.success);
        assertEquals("Added 3 events to Personal.", result.message);
        assertEquals(3, provider.eventsIn(1L).size());
    }

    @Test public void aTimeTbaGameIsStoredAsAnAllDayEventOnTheRightDay() {
        personalOnly();
        run(batch(game("Michigan vs. Example A&M", "2026-09-19", null, null)));

        FakeCalendarProvider.Event event = provider.eventsIn(1L).get(0);
        assertEquals("an unannounced kickoff is all-day", 1, event.allDay);
        assertEquals("and must carry UTC so the day cannot shift", "UTC", event.timezone);
        assertEquals(LocalDate.of(2026, 9, 19).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli(), event.dtStart);
        assertTrue(event.description.contains(CalendarActionExecutor.TBA_MARKER));
    }

    // ---- verification ---------------------------------------------------------------------------

    /** The heart of the truthfulness rule: an insert that does not survive is not an addition. */
    @Test public void anInsertThatCannotBeReadBackIsNeverCountedAsAdded() {
        personalOnly();
        provider.loseInsertedRows = true;

        DeviceActionExecutor.Result result = run(michiganSchedule());

        assertFalse(result.success);
        assertEquals("No events were added. (the calendar did not keep the event)", result.message);
        assertFalse(result.message.toLowerCase().contains("added 3"));
    }

    @Test public void aProviderThatRefusesTheInsertProducesNoSuccessClaim() {
        personalOnly();
        provider.refuseInserts = true;

        DeviceActionExecutor.Result result = run(michiganSchedule());

        assertFalse(result.success);
        assertTrue(result.message.startsWith("No events were added."));
        assertTrue(provider.events.isEmpty());
    }

    // ---- duplicates -----------------------------------------------------------------------------

    @Test public void anEventAlreadyOnTheCalendarIsSkippedNotDuplicated() {
        personalOnly();
        long start = LocalDate.of(2026, 9, 5).atTime(12, 0)
                .atZone(ZoneId.of("America/Detroit")).toInstant().toEpochMilli();
        provider.seedEvent(1L, "Michigan vs. Example State", start, start + 3_600_000L,
                false, "America/Detroit");

        DeviceActionExecutor.Result result = run(batch(
                game("Michigan vs. Example State", "2026-09-05", 12, 0)));

        assertTrue(result.success);
        assertEquals("No events were added. All 1 was already on Personal.", result.message);
        assertEquals("the existing event must be left exactly as it was",
                1, provider.countTitled(1L, "Michigan vs. Example State"));
    }

    @Test public void importingTheSameScheduleTwiceConvergesOnOneCopyOfEachEvent() {
        personalOnly();

        DeviceActionExecutor.Result first = run(michiganSchedule());
        assertEquals("Added 3 events to Personal.", first.message);

        DeviceActionExecutor.Result second = run(michiganSchedule());
        assertTrue(second.success);
        assertTrue(second.message, second.message.contains("already on"));

        assertEquals("three games, not six", 3, provider.eventsIn(1L).size());
        assertEquals(1, provider.countTitled(1L, "Michigan vs. Example State"));
        assertEquals(1, provider.countTitled(1L, "Michigan at Example Tech"));
        assertEquals(1, provider.countTitled(1L, "Michigan vs. Example A&M"));
    }

    @Test public void aPartiallyImportedScheduleReportsBothCounts() {
        personalOnly();
        long start = LocalDate.of(2026, 9, 5).atTime(12, 0)
                .atZone(ZoneId.of("America/Detroit")).toInstant().toEpochMilli();
        provider.seedEvent(1L, "Michigan vs. Example State", start, start + 3_600_000L,
                false, "America/Detroit");

        DeviceActionExecutor.Result result = run(michiganSchedule());

        assertTrue(result.success);
        assertEquals("Added 2 events to Personal. 1 was already on your calendar.", result.message);
        assertEquals(3, provider.eventsIn(1L).size());
    }

    /** A minute of drift on a re-researched kickoff is the same game, not a second one. */
    @Test public void aSlightlyDifferentStartOnTheSameDayIsStillTheSameEvent() {
        personalOnly();
        run(batch(game("Michigan at Example Tech", "2026-09-12", 15, 30)));
        DeviceActionExecutor.Result again = run(batch(
                game("Michigan at Example Tech", "2026-09-12", 15, 35)));

        assertTrue(again.message, again.success);
        assertEquals(1, provider.countTitled(1L, "Michigan at Example Tech"));
    }

    @Test public void aDifferentDayIsADifferentEvent() {
        personalOnly();
        run(batch(game("Michigan at Example Tech", "2026-09-12", 15, 30)));
        run(batch(game("Michigan at Example Tech", "2026-09-19", 15, 30)));

        assertEquals(2, provider.countTitled(1L, "Michigan at Example Tech"));
    }

    @Test public void aTimedEventDoesNotCollideWithAnAllDayOneOnTheSameDay() {
        personalOnly();
        run(batch(game("Michigan vs. Example A&M", "2026-09-19", null, null)));
        run(batch(game("Michigan vs. Example A&M", "2026-09-19", 19, 0)));

        assertEquals("an announced kickoff is genuinely new information",
                2, provider.countTitled(1L, "Michigan vs. Example A&M"));
    }

    @Test public void aDuplicateCheckThatCannotRunDoesNotInsertAnyway() {
        personalOnly();
        // Discovery succeeds, then the provider starts failing before the duplicate scan.
        OrbitCalendarStore.Target target = OrbitCalendarStore.resolveTarget(context);
        assertNotNull(target);
        CalendarActionExecutor.Plan plan = CalendarActionExecutor.parse(michiganSchedule());
        provider.failQueries = true;

        CalendarActionExecutor.Outcome outcome =
                CalendarActionExecutor.write(context, plan, target);

        assertEquals(0, outcome.added);
        assertEquals(3, outcome.failed);
        assertEquals("nothing may be inserted while Orbit cannot tell new from existing",
                0, provider.insertAttempts);
    }

    // ---- what the user is told --------------------------------------------------------------------

    @Test public void resultWordingAlwaysMatchesWhatTheCalendarDid() {
        assertEquals("Added 12 events to Personal.", CalendarActionExecutor.resultFor(
                new CalendarActionExecutor.Outcome(12, 0, 0, "Personal", "")).message);
        assertEquals("Added 10 events to Personal. 2 were already on your calendar.",
                CalendarActionExecutor.resultFor(
                        new CalendarActionExecutor.Outcome(10, 2, 0, "Personal", "")).message);
        assertEquals("No events were added.", CalendarActionExecutor.resultFor(
                new CalendarActionExecutor.Outcome(0, 0, 3, "Personal", "")).message);
        assertFalse("a total failure is never reported as a success",
                CalendarActionExecutor.resultFor(
                        new CalendarActionExecutor.Outcome(0, 0, 3, "Personal", "")).success);
    }

    // ---- diagnostics ------------------------------------------------------------------------------

    @Test public void diagnosticsRecordCountsAndNeverCalendarContents() {
        personalOnly();
        run(batch(
                game("Michigan vs. Example State", "2026-09-05", 12, 0),
                game("Dentist, Dr. Example, 4 Elm Street", "2026-09-06", 9, 0)));

        String report = CalendarDiagnostics.report(context);
        assertTrue(report.contains("Permission: granted"));
        assertTrue(report.contains("Writable calendars: 1"));
        assertTrue(report.contains("Selected calendar: Personal"));
        assertTrue(report.contains("Last requested events: 2"));
        assertTrue(report.contains("Last added: 2"));
        assertTrue(report.contains("Last already present: 0"));
        assertTrue(report.contains("Last failed: 0"));

        assertFalse("event titles must never reach diagnostics", report.contains("Michigan"));
        assertFalse(report.contains("Dentist"));
        assertFalse(report.contains("Elm Street"));
        assertFalse("nor dates or times of anything on the calendar", report.contains("2026-09-05"));
        assertFalse("nor account addresses", report.contains("@example.com"));
    }

    @Test public void diagnosticsShowZeroWritesAfterADenial() {
        personalOnly();
        run(batch(game("Game", "2026-09-05", 12, 0)));
        denyCalendar();
        run(batch(game("Another game", "2026-09-06", 12, 0)));

        String report = CalendarDiagnostics.report(context);
        assertTrue(report.contains("Permission: not granted"));
        assertTrue("stale success counts must not stand after a blocked write",
                report.contains("Last added: 0"));
    }
}
