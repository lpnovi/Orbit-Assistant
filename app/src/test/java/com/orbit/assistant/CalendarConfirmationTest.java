package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agreeing to a calendar write, and refusing one.
 *
 * <p>A batch gets one confirmation, not one per event, and that single confirmation has to carry
 * everything the agreement is actually about: which calendar, what date range, a few of the events,
 * and how many have no announced start time. Cancelling it has to leave the calendar untouched,
 * which is checked here through the real {@link OrbitActionEngine} rather than by reasoning about
 * the surfaces.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class CalendarConfirmationTest {

    private Context context;
    private FakeCalendarProvider provider;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitCalendarStore.forgetTarget(context);
        provider = new FakeCalendarProvider();
        provider.calendars.add(new FakeCalendarProvider.Calendar(1L, "Personal",
                "me@example.com", CalendarContract.Calendars.CAL_ACCESS_OWNER, true));
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider);
        Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR);
    }

    private static JSONObject twelveGames() {
        try {
            JSONArray events = new JSONArray();
            for (int i = 0; i < 12; i++) {
                JSONObject event = new JSONObject()
                        .put("title", "Michigan game " + (i + 1))
                        .put("date", String.format("2026-09-%02d", i + 5))
                        .put("timezone", "America/Detroit");
                // The last three kickoffs have not been announced.
                if (i < 9) event.put("hour", 12).put("minute", 0);
                else event.put("timeTba", true);
                events.put(event);
            }
            return new JSONObject().put("events", events);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AssistantReply.Action scheduleAction() {
        return new AssistantReply.Action(CalendarActionExecutor.ACTION_TYPE, twelveGames(), true);
    }

    // ---- one confirmation for the whole batch ----------------------------------------------------

    @Test public void aTwelveGameScheduleAsksOnce() {
        AtomicInteger confirmations = new AtomicInteger();
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(scheduleAction());

        OrbitActionEngine.execute(context, actions,
                (action, onAllow, onCancel) -> { confirmations.incrementAndGet(); onAllow.run(); },
                new OrbitActionEngine.Listener() {
                    @Override public void onStep(AssistantReply.Action action,
                                                 DeviceActionExecutor.Result result,
                                                 int index, int total) {}
                    @Override public void onFinished(boolean all, int done, int total) {}
                });

        assertEquals("twelve dialogs for twelve games is exactly what this avoids",
                1, confirmations.get());
        assertEquals(12, provider.eventsIn(1L).size());
    }

    @Test public void cancellingTheConfirmationWritesNothing() {
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(scheduleAction());
        final DeviceActionExecutor.Result[] reported = new DeviceActionExecutor.Result[1];

        OrbitActionEngine.execute(context, actions,
                (action, onAllow, onCancel) -> onCancel.run(),
                new OrbitActionEngine.Listener() {
                    @Override public void onStep(AssistantReply.Action action,
                                                 DeviceActionExecutor.Result result,
                                                 int index, int total) {
                        reported[0] = result;
                    }
                    @Override public void onFinished(boolean all, int done, int total) {}
                });

        assertEquals(0, provider.insertAttempts);
        assertTrue(provider.events.isEmpty());
        assertEquals(DeviceActionExecutor.STATUS_CANCELLED, reported[0].status);
        assertFalse("a cancellation must never read as success", reported[0].success);
        assertFalse(reported[0].message.toLowerCase().contains("added"));
    }

    /**
     * A model that omits requiresConfirmation must not be able to skip the question. The
     * requirement belongs to the action, so every execution path inherits it.
     */
    @Test public void aCalendarWriteAlwaysRequiresConfirmationEvenIfTheModelSaysOtherwise() {
        AssistantReply.Action unconfirmed = new AssistantReply.Action(
                CalendarActionExecutor.ACTION_TYPE, twelveGames(), false);
        assertTrue(unconfirmed.requiresConfirmation);

        AtomicInteger confirmations = new AtomicInteger();
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(unconfirmed);
        OrbitActionEngine.execute(context, actions,
                (action, onAllow, onCancel) -> { confirmations.incrementAndGet(); onCancel.run(); },
                new OrbitActionEngine.Listener() {
                    @Override public void onStep(AssistantReply.Action action,
                                                 DeviceActionExecutor.Result result,
                                                 int index, int total) {}
                    @Override public void onFinished(boolean all, int done, int total) {}
                });
        assertEquals(1, confirmations.get());
        assertEquals(0, provider.insertAttempts);
    }

    @Test public void anActionArrivingFromJsonWithoutConfirmationStillConfirms() throws Exception {
        JSONObject reply = new JSONObject()
                .put("text", "I can add those.")
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "ADD_CALENDAR_EVENTS")
                        .put("params", twelveGames())
                        .put("requiresConfirmation", false)));
        AssistantReply parsed = AssistantReply.fromJson(reply);
        assertEquals(1, parsed.actions.size());
        assertTrue(parsed.actions.get(0).requiresConfirmation);
    }

    @Test public void ordinaryActionsKeepTheirOwnConfirmationSetting() {
        assertFalse(new AssistantReply.Action("SET_TIMER", new JSONObject(), false)
                .requiresConfirmation);
        assertTrue(new AssistantReply.Action("SET_TIMER", new JSONObject(), true)
                .requiresConfirmation);
        assertFalse("opening the composer is not a persistent write",
                new AssistantReply.Action("CREATE_EVENT", new JSONObject(), false)
                        .requiresConfirmation);
    }

    // ---- what the confirmation says ----------------------------------------------------------------

    @Test public void theConfirmationNamesTheCalendarAndPreviewsTheBatch() {
        CalendarConfirmation.Preview preview =
                CalendarConfirmation.of(context, scheduleAction());

        assertEquals("Add 12 events to Personal?", preview.title);
        assertEquals(12, preview.eventCount);
        assertEquals("the destination is a field of its own, not a line of prose",
                "Personal", preview.selectorLabel);
        assertTrue("and Add may run, because Orbit knows where these go", preview.canAdd());
        assertTrue(preview.detail().contains("Sep 5 - Sep 16, 2026"));
        assertTrue("a few real events, not a bare count",
                preview.detail().contains("Michigan game 1"));
        assertTrue(preview.detail().contains("and 9 more events"));
        assertTrue("the TBA entries are called out, because they change what gets created",
                preview.detail().contains("3 events have no announced start time yet"));
        assertTrue(preview.detail().contains(CalendarActionExecutor.TBA_MARKER));
    }

    @Test public void aSingleEventReadsInTheSingular() {
        JSONObject one = CalendarPlanFixtures.singleEvent();
        CalendarConfirmation.Preview preview = CalendarConfirmation.of(context,
                new AssistantReply.Action(CalendarActionExecutor.ACTION_TYPE, one, true));
        assertEquals("Add 1 event to Personal?", preview.title);
        assertFalse(preview.detail().contains("more events"));
    }

    @Test public void oneWritableCalendarOffersNoPointlessChangeControl() {
        assertFalse(CalendarConfirmation.of(context, scheduleAction()).canChangeCalendar);
    }

    @Test public void severalWritableCalendarsOfferAChoice() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(2L, "Work",
                "me@example.com", "team@example.com",
                CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, false));

        CalendarConfirmation.Preview preview = CalendarConfirmation.of(context, scheduleAction());
        assertTrue(preview.canChangeCalendar);

        String[] choices = CalendarConfirmation.calendarChoices(
                OrbitCalendarStore.writableCalendars(context));
        assertEquals(2, choices.length);
        assertEquals("distinct names need no account noise", "Personal", choices[0]);
        assertEquals("Work", choices[1]);
    }

    /** Two calendars with the same name are the case an account address actually resolves. */
    @Test public void calendarsSharingANameAreToldApartByTheirAccount() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(2L, "Personal",
                "work@example.com", "work@example.com",
                CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, false));

        String[] choices = CalendarConfirmation.calendarChoices(
                OrbitCalendarStore.writableCalendars(context));
        assertEquals(2, choices.length);
        assertTrue(choices[0].contains("me@example.com"));
        assertTrue(choices[1].contains("work@example.com"));
    }

    @Test public void anInvalidPlanStillExplainsItselfRatherThanFailingSilently() {
        try {
            JSONObject bad = new JSONObject().put("events", new JSONArray().put(
                    new JSONObject().put("title", "Game").put("date", "2026-02-30")));
            CalendarConfirmation.Preview preview = CalendarConfirmation.of(context,
                    new AssistantReply.Action(CalendarActionExecutor.ACTION_TYPE, bad, true));
            assertEquals("Add calendar events?", preview.title);
            assertEquals(0, preview.eventCount);
            assertTrue(preview.detail().contains("invalid date"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test public void theCardTitleSummarisesTheBatch() {
        assertEquals("Calendar · 12 events",
                CalendarConfirmation.cardTitle(scheduleAction()));
        assertEquals("Calendar · 1 event", CalendarConfirmation.cardTitle(
                new AssistantReply.Action(CalendarActionExecutor.ACTION_TYPE,
                        CalendarPlanFixtures.singleEvent(), true)));
    }

    // ---- both surfaces resolve permission before the write ---------------------------------------------

    @Test public void bothSurfacesRunApprovedCalendarActionsThroughTheGate() {
        String chat = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatActivity.java");
        assertTrue(chat.contains("CalendarActionGate.afterApproval(this, action, onAllow)"));
        assertTrue("full chat shows the batch confirmation",
                chat.contains("confirmCalendarBatch(action, onAllow, onCancel)"));

        String overlay = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertTrue("the overlay cannot request permissions itself and uses the same gate",
                overlay.contains("CalendarActionGate.afterApproval(c, action,"));
    }

    /** Permission is asked for at the point of use, never opportunistically. */
    @Test public void theGateOnlyPausesForCalendarWrites() {
        AtomicInteger continued = new AtomicInteger();
        CalendarActionGate.afterApproval(context,
                new AssistantReply.Action("SET_TIMER", new JSONObject(), true),
                continued::incrementAndGet);
        assertEquals("an ordinary action must not be delayed by calendar plumbing",
                1, continued.get());

        // Permission is already held here, so a calendar write continues immediately too.
        CalendarActionGate.afterApproval(context, scheduleAction(), continued::incrementAndGet);
        assertEquals(2, continued.get());
    }
}
