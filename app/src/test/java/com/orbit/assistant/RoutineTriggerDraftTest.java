package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Drafted automation must stay a proposal: validated against the real trigger model, resolved from
 * local saved places, and never scheduled by planning.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineTriggerDraftTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("orbit_routine_triggers", Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("orbit_saved_places", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private RoutineDraft parse(String json) {
        return RoutineDraft.fromPlannerJson(context, json);
    }

    private static String withTrigger(String triggerJson) {
        return "{\"name\":\"Bedtime\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}],"
                + "\"trigger\":" + triggerJson + "}";
    }

    private void savePlace(String name) {
        SavedPlaceStore.upsert(context, SavedPlaceStore.create(name, 51.5d, -0.12d));
    }

    @Test public void aDailyTimeTriggerIsDrafted() {
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"time\",\"recurrence\":\"daily\",\"hour\":21,\"minute\":0}"));
        assertNotNull(draft);
        assertTrue(draft.hasTrigger());
        assertTrue(draft.trigger.isTime());
        assertEquals(RoutineTriggerStore.MODE_DAILY, draft.trigger.mode);
        assertEquals(21, draft.trigger.hour);
        assertEquals(0, draft.trigger.minute);
    }

    @Test public void aWeekdayTriggerIsDrafted() {
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"time\",\"recurrence\":\"weekdays\",\"hour\":23,\"minute\":0}"));
        assertNotNull(draft);
        assertEquals(RoutineTriggerStore.MODE_WEEKDAYS, draft.trigger.mode);
        assertTrue(draft.trigger.summary(context).contains("Weekdays"));
    }

    @Test public void anExplicitWeekdaySetIsDrafted() {
        // Monday + Wednesday + Friday = 1 + 4 + 16.
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"time\",\"recurrence\":\"weekly\",\"hour\":7,\"minute\":30,"
                + "\"weekdayMask\":21}"));
        assertNotNull(draft);
        assertEquals(RoutineTriggerStore.MODE_WEEKLY, draft.trigger.mode);
        assertEquals(21, draft.trigger.weekdayMask);
        String summary = draft.trigger.summary(context);
        assertTrue(summary.contains("Mon"));
        assertTrue(summary.contains("Fri"));
    }

    @Test public void anInvalidClockTimeIsRejected() {
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"time\",\"recurrence\":\"daily\",\"hour\":31,\"minute\":0}"));
        assertNotNull(draft);
        assertFalse("an impossible time must not become a trigger", draft.hasTrigger());
        assertFalse(draft.warnings.isEmpty());
    }

    @Test public void aVagueTimeIsLeftForTheUser() {
        // The planner omitted the hour rather than guessing; Orbit must not guess either.
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"time\",\"recurrence\":\"daily\"}"));
        assertNotNull(draft);
        assertFalse(draft.hasTrigger());
        assertTrue(draft.warnings.toString().toLowerCase(java.util.Locale.US)
                .contains("specific time"));
    }

    @Test public void anUnknownRecurrenceIsRejected() {
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"time\",\"recurrence\":\"every_third_tuesday\",\"hour\":9,\"minute\":0}"));
        assertNotNull(draft);
        assertFalse(draft.hasTrigger());
    }

    @Test public void anArriveTriggerResolvesASavedPlaceLocally() {
        savePlace("Home");
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"location\",\"transition\":\"arrive\",\"place\":\"Home\"}"));
        assertNotNull(draft);
        assertTrue(draft.hasTrigger());
        assertTrue(draft.trigger.isLocation());
        assertEquals("arrive", draft.trigger.transition);
        assertEquals("Home", draft.trigger.placeLabel);
        assertTrue("coordinates come from local storage", draft.trigger.resolved);
        assertEquals(51.5d, draft.trigger.latitude, 0.0001d);
    }

    @Test public void aLeaveTriggerIsDrafted() {
        savePlace("Work");
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"location\",\"transition\":\"leave\",\"place\":\"Work\"}"));
        assertNotNull(draft);
        assertEquals("leave", draft.trigger.transition);
        assertTrue(draft.trigger.summary(context).startsWith("Leave"));
    }

    @Test public void anUnknownPlaceIsFlaggedRatherThanInvented() {
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"location\",\"transition\":\"arrive\",\"place\":\"girlfriend's house\"}"));
        assertNotNull(draft);
        assertTrue(draft.hasTrigger());
        assertFalse("no coordinates may be invented", draft.trigger.resolved);
        assertEquals(0d, draft.trigger.latitude, 0.0001d);
        assertTrue(draft.warnings.toString().contains("girlfriend's house"));
    }

    @Test public void anUnclearTransitionIsRejected() {
        savePlace("Home");
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"location\",\"transition\":\"maybe\",\"place\":\"Home\"}"));
        assertNotNull(draft);
        assertFalse(draft.hasTrigger());
    }

    @Test public void radiusIsValidatedAgainstTheSupportedRange() {
        savePlace("Home");
        RoutineDraft tooBig = parse(withTrigger(
                "{\"type\":\"location\",\"transition\":\"arrive\",\"place\":\"Home\","
                + "\"radiusMeters\":900000}"));
        assertNotNull(tooBig);
        assertTrue("an impossible radius falls back to the normal default",
                tooBig.trigger.radiusMeters >= 50f && tooBig.trigger.radiusMeters <= 5000f);

        RoutineDraft explicit = parse(withTrigger(
                "{\"type\":\"location\",\"transition\":\"arrive\",\"place\":\"Home\","
                + "\"radiusMeters\":500}"));
        assertEquals(500f, explicit.trigger.radiusMeters, 0.1f);
    }

    @Test public void anUnknownTriggerTypeIsRejected() {
        RoutineDraft draft = parse(withTrigger("{\"type\":\"when_battery_low\"}"));
        assertNotNull(draft);
        assertFalse(draft.hasTrigger());
        assertFalse(draft.warnings.isEmpty());
    }

    @Test public void aRoutineWithoutAutomationHasNoTrigger() {
        RoutineDraft draft = parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        assertFalse(draft.hasTrigger());
    }

    @Test public void aTimeConditionIsNowAllowed() {
        RoutineDraft draft = parse("{\"name\":\"Night\",\"steps\":["
                + "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"time\",\"startMinute\":1320,"
                + "\"endMinute\":420,\"nextSteps\":1}},"
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        assertEquals(2, draft.actions.size());
        assertEquals(RoutineActionCatalog.IF_CONDITION, draft.actions.get(0).type);
    }

    @Test public void aMalformedConditionIsRejected() {
        RoutineDraft draft = parse("{\"name\":\"Night\",\"steps\":["
                + "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"time\",\"startMinute\":9999,"
                + "\"endMinute\":420,\"nextSteps\":1}},"
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        assertEquals("only the valid step survives", 1, draft.actions.size());
        assertEquals(RoutineActionCatalog.SET_DND, draft.actions.get(0).type);
    }

    @Test public void anElseBranchIsWarnedAboutRatherThanFaked() {
        RoutineDraft draft = parse("{\"name\":\"Home\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}],"
                + "\"elseRequested\":true}");
        assertNotNull(draft);
        assertTrue(draft.warnings.toString().toLowerCase(java.util.Locale.US)
                .contains("otherwise"));
    }

    @Test public void aSecondTriggerIsReportedRatherThanDiscarded() {
        RoutineDraft draft = parse("{\"name\":\"Work\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}],"
                + "\"trigger\":{\"type\":\"time\",\"recurrence\":\"weekdays\",\"hour\":8,\"minute\":0},"
                + "\"additionalTriggers\":[{\"type\":\"location\",\"transition\":\"arrive\","
                + "\"place\":\"Work\"}]}");
        assertNotNull(draft);
        assertTrue(draft.hasTrigger());
        assertTrue(draft.warnings.toString().toLowerCase(java.util.Locale.US)
                .contains("automatic triggers"));
    }

    @Test public void partialSuccessKeepsTheTriggerAndTheValidSteps() {
        RoutineDraft draft = parse("{\"name\":\"Morning\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SEND_WHATSAPP\",\"params\":{}}],"
                + "\"trigger\":{\"type\":\"time\",\"recurrence\":\"weekdays\",\"hour\":8,\"minute\":0},"
                + "\"unsupported\":[\"message John on WhatsApp\"]}");
        assertNotNull(draft);
        assertTrue(draft.hasTrigger());
        assertEquals(1, draft.actions.size());
        assertTrue(draft.warnings.contains("message John on WhatsApp"));
    }

    @Test public void planningSchedulesNothing() {
        savePlace("Home");
        parse(withTrigger("{\"type\":\"location\",\"transition\":\"arrive\",\"place\":\"Home\"}"));
        parse(withTrigger("{\"type\":\"time\",\"recurrence\":\"daily\",\"hour\":22,\"minute\":0}"));

        assertTrue("no routine may be created by planning", RoutineStore.list(context).isEmpty());
        assertTrue("no trigger may be registered by planning",
                RoutineTriggerStore.list(context).isEmpty());
    }

    @Test public void aTriggerSurvivesThePayloadRoundTrip() {
        savePlace("Home");
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"location\",\"transition\":\"arrive\",\"place\":\"Home\"}"));
        assertNotNull(draft);

        RoutineDraft restored = RoutineDraft.fromPayload(context, draft.toPayload());
        assertNotNull(restored);
        assertTrue(restored.hasTrigger());
        assertEquals("arrive", restored.trigger.transition);
        assertEquals("Home", restored.trigger.placeLabel);
        assertEquals(51.5d, restored.trigger.latitude, 0.0001d);
    }

    @Test public void aTimeTriggerSurvivesThePayloadRoundTrip() {
        RoutineDraft draft = parse(withTrigger(
                "{\"type\":\"time\",\"recurrence\":\"weekdays\",\"hour\":23,\"minute\":15}"));
        RoutineDraft restored = RoutineDraft.fromPayload(context, draft.toPayload());
        assertNotNull(restored);
        assertEquals(RoutineTriggerStore.MODE_WEEKDAYS, restored.trigger.mode);
        assertEquals(23, restored.trigger.hour);
        assertEquals(15, restored.trigger.minute);
    }

    @Test public void aTamperedTriggerPayloadIsRejected() {
        String tampered = "{\"schema\":1,\"name\":\"Bad\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}],"
                + "\"trigger\":{\"type\":\"when_battery_low\",\"mode\":\"daily\"},\"warnings\":[]}";
        RoutineDraft restored = RoutineDraft.fromPayload(context, tampered);
        assertNotNull(restored);
        assertFalse("an unsupported trigger type must not survive", restored.hasTrigger());
    }

    @Test public void aTamperedTriggerTimeIsRejected() {
        String tampered = "{\"schema\":1,\"name\":\"Bad\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}],"
                + "\"trigger\":{\"type\":\"time\",\"mode\":\"daily\",\"hour\":77,\"minute\":0},"
                + "\"warnings\":[]}";
        RoutineDraft restored = RoutineDraft.fromPayload(context, tampered);
        assertNotNull(restored);
        assertFalse(restored.hasTrigger());
    }

    @Test public void savedPlaceCoordinatesAreNeverSentToThePlanner() {
        savePlace("Home");
        String places = RoutinePlanner.savedPlaces(context);
        assertTrue("the label is what the planner sees", places.contains("Home"));
        assertFalse("coordinates must stay on the device", places.contains("51.5"));
        assertFalse(places.contains("-0.12"));

        String prompt = RoutinePlanner.prompt(context, "When I get home turn on DND");
        assertFalse(prompt.contains("51.5"));
        assertFalse(prompt.contains("-0.12"));
        assertFalse(prompt.toLowerCase(java.util.Locale.US).contains("latitude"));
    }

    @Test public void thePlannerIsToldTheSupportedRecurrences() {
        String prompt = RoutinePlanner.prompt(context, "every weekday at 8");
        for (String mode : new String[]{RoutineTriggerStore.MODE_DAILY,
                RoutineTriggerStore.MODE_WEEKDAYS, RoutineTriggerStore.MODE_WEEKLY}) {
            assertTrue("the planner must know " + mode, prompt.contains(mode));
        }
        assertTrue(prompt.contains("arrive"));
        assertTrue(prompt.contains("leave"));
        assertTrue("guessing a time must be forbidden", prompt.contains("Never guess a clock time"));
    }
}
