package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The very first Calendar request on a phone, which is the one Beta 1 got wrong.
 *
 * <p>Beta 1 built its confirmation from {@link OrbitCalendarStore} while Orbit still lacked
 * Calendar permission. Every question it asked the provider — how many writable calendars exist,
 * which is primary, is there a real choice to offer — was answered "none", because that is what
 * the provider says before the grant. So the first request offered no destination and no way to
 * pick one, asked for permission only after Add, and then failed at the executor with nothing the
 * user could do about it. Repeating the request worked, because by then permission was held.
 *
 * <p>These cases pin the fixed ordering: resolve permission, re-read the provider, resolve the
 * target, and only then describe anything. Nothing here is about pixels; it is about which state
 * the confirmation is built from and whether Add is allowed to run.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class CalendarFirstUseTest {

    private static final int OWNER = CalendarContract.Calendars.CAL_ACCESS_OWNER;
    private static final int CONTRIBUTOR = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;

    private Context context;
    private FakeCalendarProvider provider;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitCalendarStore.forgetTarget(context);
        provider = new FakeCalendarProvider();
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider);
        denyCalendar();
        shadowApp().clearNextStartedActivities();
    }

    @After public void tearDown() {
        OrbitCalendarStore.forgetTarget(context);
    }

    private ShadowApplication shadowApp() {
        return Shadows.shadowOf((Application) RuntimeEnvironment.getApplication());
    }

    private void grantCalendar() {
        shadowApp().grantPermissions(Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR);
    }

    private void denyCalendar() {
        shadowApp().denyPermissions(Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR);
    }

    private void personalOnly() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                1L, "Personal", "me@example.com", OWNER, true));
    }

    /** One primary the user owns, plus two writable calendars that are clearly not theirs. */
    private void oneClearPrimaryAmongMany() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                1L, "Personal", "me@example.com", OWNER, true));
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                2L, "Holidays", "holidays@example.com", "someone@example.com", CONTRIBUTOR, false));
    }

    /** Two equally plausible personal calendars: exactly the case Orbit must not guess between. */
    private void twoAmbiguousCalendars() {
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                1L, "Personal", "me@example.com", "someone@example.com", CONTRIBUTOR, false));
        provider.calendars.add(new FakeCalendarProvider.Calendar(
                2L, "School", "school@example.com", "admin@example.com", CONTRIBUTOR, false));
    }

    private static JSONObject schedule() {
        try {
            JSONArray events = new JSONArray();
            for (int i = 0; i < 12; i++) {
                JSONObject event = new JSONObject()
                        .put("title", "Michigan game " + (i + 1))
                        .put("date", String.format("2026-09-%02d", i + 5))
                        .put("timezone", "America/Detroit");
                if (i == 0) event.put("hour", 19).put("minute", 30);
                else if (i == 1) event.put("hour", 12).put("minute", 0);
                else if (i == 2) event.put("hour", 15).put("minute", 30);
                else if (i < 9) event.put("hour", 12).put("minute", 0);
                else event.put("timeTba", true);
                events.put(event);
            }
            return new JSONObject().put("events", events);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AssistantReply.Action scheduleAction() {
        return new AssistantReply.Action(CalendarActionExecutor.ACTION_TYPE, schedule(), true);
    }

    /**
     * Answers the Calendar permission prompt Orbit just opened, the way Android would.
     *
     * <p>The real {@link CalendarPermissionActivity} is driven here rather than simulated, so the
     * token plumbing between the surfaces and Android's prompt is part of what these cases prove.
     */
    private void answerPermissionPrompt(boolean grant) {
        Intent started = shadowApp().getNextStartedActivity();
        assertNotNull("Orbit should have opened its permission bridge", started);
        assertEquals(CalendarPermissionActivity.class.getName(),
                started.getComponent().getClassName());
        if (grant) grantCalendar();
        ActivityController<CalendarPermissionActivity> controller =
                Robolectric.buildActivity(CalendarPermissionActivity.class, started).create();
        // A grant closes the bridge from onCreate; a dismissal is the denial path.
        if (!grant) controller.get().onBackPressed();
    }

    // ---- the first request, end to end ------------------------------------------------------------

    /**
     * The Beta 1 bug, stated as a test: before permission, Orbit knew nothing, and it must not
     * describe that as a destination.
     */
    @Test public void withNoPermissionOrbitKnowsNothingAboutTheDestination() {
        personalOnly();
        CalendarTargetResolver.State state = CalendarTargetResolver.state(context);
        assertFalse(state.permitted);
        assertTrue("the provider hides everything until the grant", state.writable.isEmpty());
        assertNull(state.target);
        assertEquals("Calendar access needed", state.selectorLabel());
    }

    /** The whole intended first-use sequence, in one pass, with no second request. */
    @Test public void theFirstRequestResolvesPermissionThenRereadsTheCalendars() {
        personalOnly();
        List<CalendarTargetResolver.State> ready = new ArrayList<>();

        CalendarTargetResolver.prepare(context, ready::add);

        assertTrue("nothing may be described until Android has answered", ready.isEmpty());
        answerPermissionPrompt(true);

        assertEquals("the first request continues on its own", 1, ready.size());
        CalendarTargetResolver.State state = ready.get(0);
        assertTrue(state.permitted);
        assertEquals("discovery re-ran after the grant rather than reusing the empty result",
                1, state.writable.size());
        assertNotNull("and a single writable calendar resolves without asking", state.target);
        assertEquals("Personal", state.target.label());
    }

    /** The confirmation the user actually sees on that first request. */
    @Test public void theFirstConfirmationNamesTheRealCalendarAndCanBeAdded() {
        personalOnly();
        List<CalendarTargetResolver.State> ready = new ArrayList<>();
        CalendarTargetResolver.prepare(context, ready::add);
        answerPermissionPrompt(true);

        CalendarConfirmation.Preview preview =
                CalendarConfirmation.of(context, scheduleAction(), ready.get(0));
        assertEquals("Add 12 events to Personal?", preview.title);
        assertEquals("Personal", preview.selectorLabel);
        assertTrue(preview.canAdd());
        assertFalse("one calendar is not a choice worth offering", preview.canChangeCalendar);
    }

    /** A denial still ends the flow cleanly, and still writes nothing. */
    @Test public void aDeniedPromptResolvesToNoAccessAndWritesNothing() {
        personalOnly();
        List<CalendarTargetResolver.State> ready = new ArrayList<>();
        CalendarTargetResolver.prepare(context, ready::add);
        answerPermissionPrompt(false);

        assertEquals(1, ready.size());
        assertFalse(ready.get(0).permitted);

        DeviceActionExecutor.Result result =
                CalendarActionExecutor.execute(context, schedule());
        assertEquals(DeviceActionExecutor.STATUS_PERMISSION, result.status);
        assertFalse(result.success);
        assertEquals(0, provider.insertAttempts);
        assertTrue(provider.events.isEmpty());
    }

    // ---- resolving a default, or refusing to ---------------------------------------------------------

    @Test public void oneClearPrimaryIsResolvedWithoutAsking() {
        oneClearPrimaryAmongMany();
        grantCalendar();

        CalendarTargetResolver.State state = CalendarTargetResolver.state(context);
        assertNotNull(state.target);
        assertEquals("Personal", state.target.label());
        assertFalse(state.needsChoice());
        assertTrue("a change is still offered, because there is more than one", state.canChoose());
        assertTrue(CalendarConfirmation.of(context, scheduleAction(), state).canAdd());
    }

    @Test public void severalPlausibleCalendarsAskInsteadOfGuessing() {
        twoAmbiguousCalendars();
        grantCalendar();

        CalendarTargetResolver.State state = CalendarTargetResolver.state(context);
        assertNull("guessing between someone's personal and school calendar is the bad outcome",
                state.target);
        assertTrue(state.needsChoice());
        assertEquals("Choose a calendar", state.selectorLabel());

        CalendarConfirmation.Preview preview =
                CalendarConfirmation.of(context, scheduleAction(), state);
        assertEquals("Add 12 events to a calendar you choose?", preview.title);
        assertEquals("Choose a calendar", preview.selectorLabel);
        assertFalse("Add must not fall through to the executor to manufacture an error",
                preview.canAdd());
        assertTrue("and the batch is still fully previewed while the question stands",
                preview.detail().contains("Michigan game 1"));
    }

    @Test public void choosingACalendarResolvesTheConfirmationImmediately() {
        twoAmbiguousCalendars();
        grantCalendar();
        CalendarTargetResolver.State before = CalendarTargetResolver.state(context);
        assertFalse(CalendarConfirmation.of(context, scheduleAction(), before).canAdd());

        CalendarTargetResolver.State after =
                CalendarTargetResolver.choose(context, before.writable.get(1));

        assertNotNull(after.target);
        assertEquals("School", after.target.label());
        CalendarConfirmation.Preview preview =
                CalendarConfirmation.of(context, scheduleAction(), after);
        assertEquals("Add 12 events to School?", preview.title);
        assertEquals("School", preview.selectorLabel);
        assertTrue(preview.canAdd());
    }

    @Test public void theChosenCalendarIsRememberedForNextTime() {
        twoAmbiguousCalendars();
        grantCalendar();
        CalendarTargetResolver.choose(context,
                CalendarTargetResolver.state(context).writable.get(1));

        // A fresh resolution, as a later request would make.
        CalendarTargetResolver.State next = CalendarTargetResolver.state(context);
        assertNotNull(next.target);
        assertEquals("School", next.target.label());
        assertEquals("only the id is kept", 2L, OrbitCalendarStore.storedTargetId(context));
        assertEquals(1, CalendarTargetResolver.selectedIndex(next));
    }

    /** The remembered account is removed from the phone: fall back cleanly, never write blind. */
    @Test public void aRememberedCalendarThatDisappearsIsForgottenNotCarriedAround() {
        twoAmbiguousCalendars();
        grantCalendar();
        CalendarTargetResolver.choose(context,
                CalendarTargetResolver.state(context).writable.get(1));
        assertEquals(2L, OrbitCalendarStore.storedTargetId(context));

        provider.calendars.removeIf(calendar -> calendar.id == 2L);
        CalendarTargetResolver.State state = CalendarTargetResolver.state(context);

        assertEquals("a stale id must not linger as a target that resolves to nothing",
                -1L, OrbitCalendarStore.storedTargetId(context));
        assertNotNull("the one remaining writable calendar is now the answer", state.target);
        assertEquals("Personal", state.target.label());
    }

    /** A failed provider read must not be mistaken for "your calendar was deleted". */
    @Test public void aFailedCalendarQueryDoesNotErasetheRememberedChoice() {
        twoAmbiguousCalendars();
        grantCalendar();
        CalendarTargetResolver.choose(context,
                CalendarTargetResolver.state(context).writable.get(1));

        provider.failQueries = true;
        CalendarTargetResolver.State state = CalendarTargetResolver.state(context);

        assertEquals(2L, OrbitCalendarStore.storedTargetId(context));
        assertNull(state.target);
        assertTrue(state.noWritableCalendar());
    }

    @Test public void aRememberedCalendarThatBecomesReadOnlyIsAlsoDropped() {
        twoAmbiguousCalendars();
        grantCalendar();
        CalendarTargetResolver.choose(context,
                CalendarTargetResolver.state(context).writable.get(1));

        provider.calendars.removeIf(calendar -> calendar.id == 2L);
        provider.calendars.add(new FakeCalendarProvider.Calendar(2L, "School",
                "school@example.com", "admin@example.com",
                CalendarContract.Calendars.CAL_ACCESS_READ, false));

        CalendarTargetResolver.State state = CalendarTargetResolver.state(context);
        assertEquals(-1L, OrbitCalendarStore.storedTargetId(context));
        assertEquals(1, state.writable.size());
        assertEquals("Personal", state.target.label());
    }

    @Test public void noWritableCalendarSaysSoRatherThanOfferingAnEmptyList() {
        grantCalendar();
        CalendarTargetResolver.State state = CalendarTargetResolver.state(context);
        assertTrue(state.noWritableCalendar());
        assertFalse("there is nothing to choose between", state.canChoose());
        assertFalse("and no question to answer, so Add is not blocked on one", state.needsChoice());
        assertEquals("No writable calendar", state.selectorLabel());
        assertTrue(CalendarConfirmation.of(context, scheduleAction(), state).detail()
                .contains("No writable calendar is set up on this phone"));
    }

    // ---- recovering a persisted card ------------------------------------------------------------------

    /**
     * The card recovery is offered for exactly one failure: the one a calendar choice would fix.
     */
    @Test public void onlyAnUnchosenTargetOffersTheChooseCalendarControl() {
        AssistantReply.Action action = scheduleAction();
        assertTrue(CalendarActionExecutor.needsTargetChoice(action,
                DeviceActionExecutor.STATUS_UNAVAILABLE,
                CalendarActionExecutor.NO_TARGET_CHOSEN));
        assertFalse("no writable calendar is not fixed by choosing one",
                CalendarActionExecutor.needsTargetChoice(action,
                        DeviceActionExecutor.STATUS_UNAVAILABLE,
                        "No writable calendar is set up on this phone, so Orbit has nowhere to add"
                                + " these events."));
        assertFalse("a missing permission already has Grant access",
                CalendarActionExecutor.needsTargetChoice(action,
                        DeviceActionExecutor.STATUS_PERMISSION,
                        "Allow Calendar access so Orbit can add these events."));
        assertFalse("ordinary action cards stay as simple as they were",
                CalendarActionExecutor.needsTargetChoice(
                        new AssistantReply.Action("SET_TIMER", new JSONObject(), false),
                        DeviceActionExecutor.STATUS_UNAVAILABLE,
                        CalendarActionExecutor.NO_TARGET_CHOSEN));
    }

    /**
     * The stranded card, recovered: choose a calendar and the already-approved batch is written.
     *
     * <p>The action re-executed is the one that was persisted, so the model is never asked to
     * research the schedule again.
     */
    @Test public void anUnchosenTargetCardRecoversByChoosingACalendar() {
        twoAmbiguousCalendars();
        grantCalendar();
        AssistantReply.Action action = scheduleAction();

        DeviceActionExecutor.Result stranded =
                DeviceActionExecutor.executeDetailed(context, action);
        assertEquals(DeviceActionExecutor.STATUS_UNAVAILABLE, stranded.status);
        assertEquals(0, provider.insertAttempts);
        assertTrue(CalendarActionExecutor.needsTargetChoice(action, stranded.status,
                stranded.message));

        // Exactly what the card's Choose calendar control does: remember, then re-run the same
        // stored action.
        CalendarTargetResolver.choose(context,
                CalendarTargetResolver.state(context).writable.get(0));
        DeviceActionExecutor.Result retried =
                DeviceActionExecutor.executeDetailed(context, action);

        assertTrue(retried.success);
        assertTrue(retried.message.startsWith("Added 12 events to Personal."));
        assertEquals(12, provider.eventsIn(1L).size());
        assertTrue("nothing landed on the calendar the user did not pick",
                provider.eventsIn(2L).isEmpty());
    }

    /** Recovering twice cannot double the schedule; the executor still recognises its own work. */
    @Test public void recoveringTwiceStillAddsNoDuplicates() {
        twoAmbiguousCalendars();
        grantCalendar();
        AssistantReply.Action action = scheduleAction();
        CalendarTargetResolver.choose(context,
                CalendarTargetResolver.state(context).writable.get(0));

        DeviceActionExecutor.executeDetailed(context, action);
        DeviceActionExecutor.Result again = DeviceActionExecutor.executeDetailed(context, action);

        assertEquals(12, provider.eventsIn(1L).size());
        assertTrue(again.success);
        assertTrue("a repeat says nothing was added, and says why",
                again.message.contains("All 12 were already on Personal."));
    }

    /** The other calendar is untouched by all of this and still writable on its own terms. */
    @Test public void adifferentDestinationCalendarRemainsIndependentlyWritable() {
        twoAmbiguousCalendars();
        grantCalendar();
        AssistantReply.Action action = scheduleAction();

        CalendarTargetResolver.choose(context,
                CalendarTargetResolver.state(context).writable.get(0));
        DeviceActionExecutor.executeDetailed(context, action);
        assertEquals(12, provider.eventsIn(1L).size());

        CalendarTargetResolver.choose(context,
                CalendarTargetResolver.state(context).writable.get(1));
        DeviceActionExecutor.Result second = DeviceActionExecutor.executeDetailed(context, action);

        assertTrue(second.message.startsWith("Added 12 events to School."));
        assertEquals("events on another calendar are not duplicates of these",
                12, provider.eventsIn(2L).size());
    }

    // ---- the clock the phone is actually set to --------------------------------------------------------

    @Test public void aTwelveHourPhonePreviewsTwelveHourTimes() {
        personalOnly();
        grantCalendar();
        ShadowSettings.set24HourTimeFormat(false);

        String detail = CalendarConfirmation.of(context, scheduleAction()).detail();
        assertTrue("7:30 PM, not 19:30, on a phone set to 12-hour time",
                detail.contains("7:30 PM"));
        assertTrue(detail.contains("12:00 PM"));
        assertTrue(detail.contains("3:30 PM"));
        assertFalse(detail.contains("19:30"));
        assertFalse(detail.contains("15:30"));
    }

    @Test public void aTwentyFourHourPhonePreviewsTwentyFourHourTimes() {
        personalOnly();
        grantCalendar();
        ShadowSettings.set24HourTimeFormat(true);

        String detail = CalendarConfirmation.of(context, scheduleAction()).detail();
        assertTrue(detail.contains("19:30"));
        assertTrue(detail.contains("12:00"));
        assertTrue(detail.contains("15:30"));
        assertFalse(detail.contains("PM"));
    }

    /** Neither format may invent a start time for an event that has not announced one. */
    @Test public void aTimeTbaEventShowsNoClockTimeInEitherFormat() {
        CalendarActionExecutor.Plan plan = CalendarActionExecutor.parse(schedule());
        CalendarActionExecutor.Event tba = plan.events.get(11);
        assertTrue(tba.timeTba);
        assertTrue(tba.allDay);

        for (boolean use24Hour : new boolean[]{true, false}) {
            String label = tba.shortLabel(CalendarActionExecutor.clockStyle(use24Hour));
            assertTrue(label.contains(CalendarActionExecutor.TBA_MARKER));
            assertFalse("an all-day TBA entry must never gain a plausible-looking clock time",
                    label.matches(".*\\d+:\\d\\d.*"));
        }
    }

    @Test public void theClockStyleFormatsBothWaysWithoutADevice() {
        CalendarActionExecutor.ClockStyle twelve = CalendarActionExecutor.clockStyle(false);
        assertEquals("7:30 PM", twelve.time(19, 30));
        assertEquals("12:00 PM", twelve.time(12, 0));
        assertEquals("3:30 PM", twelve.time(15, 30));

        CalendarActionExecutor.ClockStyle twentyFour = CalendarActionExecutor.clockStyle(true);
        assertEquals("19:30", twentyFour.time(19, 30));
        assertEquals("12:00", twentyFour.time(12, 0));
        assertEquals("15:30", twentyFour.time(15, 30));
    }

    // ---- both surfaces --------------------------------------------------------------------------------

    /**
     * Full chat and the overlay must resolve the destination the same way.
     *
     * <p>Read from the sources rather than by driving two UIs, because the thing worth pinning is
     * the ordering — prepare, then draw — and that is exactly what regressed in Beta 1 on one
     * surface at a time.
     */
    @Test public void bothSurfacesResolveTheTargetBeforeDrawingTheConfirmation() {
        String chat = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatActivity.java");
        assertTrue("full chat resolves permission and discovery before the dialog exists",
                chat.contains("CalendarTargetResolver.prepare(this, state ->")
                        && chat.contains("showCalendarBatchDialog(action, state, onAllow, onCancel)"));
        assertTrue("and draws the destination as a selector field",
                chat.contains("UiKit.selectorField(this, \"Calendar\""));
        assertTrue("with Add gated on a resolved destination",
                chat.contains("add.setEnabled(preview.canAdd())"));
        assertTrue("and a recovery control on a stranded card",
                chat.contains("actionCardControlButton(\"Choose calendar\""));

        String overlay = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertTrue("the overlay resolves the same way before drawing its sheet",
                overlay.contains("CalendarTargetResolver.prepare(getContext(),")
                        && overlay.contains("drawActionConfirmation(action, state, yes, no)"));
        assertTrue("and draws the same selector field in its own idiom",
                overlay.contains("UiKit.selectorField(c, \"Calendar\""));
        assertTrue("with the same gate on Add",
                overlay.contains("allow.setEnabled(live.canAdd())"));
        assertTrue("and the same recovery control",
                overlay.contains("actionCardControlButton(\"Choose calendar\""));

        assertFalse("the separate Change calendar dialog action is gone from full chat",
                chat.contains("\"Change calendar\""));
        assertFalse("and from the overlay sheet",
                overlay.contains("tinyTextButton(\"Change\")"));
    }

    /** Ordinary actions are untouched by any of this. */
    @Test public void ordinaryActionsAreNotDelayedByCalendarPlumbing() {
        AtomicInteger continued = new AtomicInteger();
        CalendarActionGate.afterApproval(context,
                new AssistantReply.Action("SET_TIMER", new JSONObject(), true),
                continued::incrementAndGet);
        assertEquals(1, continued.get());
        assertNull("and no permission prompt is opened for them",
                shadowApp().getNextStartedActivity());
    }
}
