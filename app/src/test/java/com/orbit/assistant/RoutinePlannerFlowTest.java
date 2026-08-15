package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The builder flow against real provider response text: what reaches the user, when a correction
 * request is worth making, and what is recorded for diagnostics.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutinePlannerFlowTest {
    private static final String GOOD_PLAN = "{\"name\":\"Focus mode\",\"steps\":["
            + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
            + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":30}}]}";
    private static final String REQUEST = "turn on do not disturb and set brightness to 30%";

    private Context context;

    /** Replays scripted provider responses and remembers what it was asked. */
    private static final class FakeProvider implements RoutinePlanner.Transport {
        final List<String> prompts = new ArrayList<>();
        private final String[] responses;
        private final String error;
        private int call;

        FakeProvider(String... responses) { this.responses = responses; this.error = null; }
        private FakeProvider(String error, boolean failing) {
            this.responses = new String[0];
            this.error = error;
        }

        static FakeProvider failing(String message) { return new FakeProvider(message, true); }

        @Override public void plan(Context context, String prompt,
                                   AssistantClient.PlanCallback callback) {
            prompts.add(prompt);
            if (error != null) { callback.onError(error); return; }
            String response = call < responses.length ? responses[call] : "";
            call++;
            callback.onText(response, "Test provider");
        }
    }

    private static final class Result implements RoutinePlanner.Callback {
        RoutineDraft draft;
        String notice;
        String error;
        int drafts;
        int errors;

        @Override public void onDraft(RoutineDraft draft, String automationNotice) {
            this.draft = draft; this.notice = automationNotice; this.drafts++;
        }
        @Override public void onError(String message) { this.error = message; this.errors++; }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        RoutinePlanner.setTransport(null);
    }

    private Result build(FakeProvider provider, String description) {
        RoutinePlanner.setTransport(provider);
        Result result = new Result();
        RoutinePlanner.build(context, description, result);
        return result;
    }

    // ---------------------------------------------------------------- the reported failure

    @Test public void theReportedRequestProducesADraft() {
        FakeProvider provider = new FakeProvider(GOOD_PLAN);
        Result result = build(provider, REQUEST);

        assertNotNull("the reported request must now draft", result.draft);
        assertEquals(1, result.drafts);
        assertEquals(0, result.errors);
        assertEquals("Focus mode", result.draft.name);
        assertEquals(2, result.draft.actions.size());
        assertEquals(RoutineActionCatalog.SET_DND, result.draft.actions.get(0).type);
        assertTrue(result.draft.actions.get(0).params.optBoolean("enabled", false));
        assertEquals(30, result.draft.actions.get(1).params.optInt("percent", -1));
        assertNull("no trigger was requested", result.draft.trigger);
        assertEquals("one call, no correction needed", 1, provider.prompts.size());
    }

    @Test public void nothingIsSavedOrScheduledByBuilding() {
        build(new FakeProvider(GOOD_PLAN), REQUEST);
        assertTrue(RoutineStore.list(context).isEmpty());
        assertTrue(RoutineTriggerStore.list(context).isEmpty());
    }

    // ---------------------------------------------------------------- repair

    @Test public void anUnreadableResponseIsRepairedOnce() {
        FakeProvider provider = new FakeProvider("I'd be glad to help with that!", GOOD_PLAN);
        Result result = build(provider, REQUEST);

        assertNotNull("the corrected response must be used", result.draft);
        assertEquals(2, result.draft.actions.size());
        assertEquals("exactly one correction request", 2, provider.prompts.size());
        assertTrue("the correction must carry the invalid reply",
                provider.prompts.get(1).contains("I'd be glad to help with that!"));
        assertTrue("the correction must restate the schema",
                provider.prompts.get(1).contains("SET_DND"));
    }

    @Test public void repairIsAttemptedAtMostOnce() {
        FakeProvider provider = new FakeProvider("not json", "still not json", GOOD_PLAN);
        Result result = build(provider, REQUEST);

        assertNull(result.draft);
        assertEquals(RoutinePlanner.UNREADABLE_MESSAGE, result.error);
        assertEquals("the third response must never be requested", 2, provider.prompts.size());
    }

    @Test public void aFailedRepairReportsCleanly() {
        FakeProvider provider = new FakeProvider("not json", "{\"name\":\"X\"}");
        Result result = build(provider, REQUEST);

        assertNull(result.draft);
        assertEquals(1, result.errors);
        assertEquals(RoutinePlanner.UNREADABLE_MESSAGE, result.error);
    }

    @Test public void anUnsupportedRequestIsNotRepaired() {
        // Read perfectly well; simply nothing Orbit can do. Asking again would change nothing.
        FakeProvider provider = new FakeProvider(
                "{\"name\":\"Coffee\",\"steps\":[{\"type\":\"START_COFFEE_MAKER\",\"params\":{}}]}",
                GOOD_PLAN);
        Result result = build(provider, "start my coffee maker");

        assertNull(result.draft);
        assertEquals(RoutinePlanner.UNSUPPORTED_MESSAGE, result.error);
        assertEquals("no pointless correction request", 1, provider.prompts.size());
    }

    @Test public void aRepairKeepsTheOriginalRequest() {
        FakeProvider provider = new FakeProvider("oops", GOOD_PLAN);
        build(provider, REQUEST);
        assertTrue("the description must be restated so the routine is preserved",
                provider.prompts.get(1).contains(REQUEST));
        assertTrue(provider.prompts.get(1).toLowerCase(java.util.Locale.US)
                .contains("do not add, remove, or substitute"));
    }

    // ---------------------------------------------------------------- error distinction

    @Test public void aProviderErrorIsPassedThrough() {
        Result result = build(FakeProvider.failing("Network unavailable"), REQUEST);
        assertNull(result.draft);
        assertEquals("Network unavailable", result.error);
    }

    @Test public void theThreeFailuresReadDifferently() {
        assertFalse(RoutinePlanner.UNREADABLE_MESSAGE.equals(RoutinePlanner.UNSUPPORTED_MESSAGE));
        assertTrue(RoutinePlanner.UNREADABLE_MESSAGE.toLowerCase(java.util.Locale.US)
                .contains("planning response"));
        assertTrue(RoutinePlanner.UNSUPPORTED_MESSAGE.toLowerCase(java.util.Locale.US)
                .contains("supported routine actions"));
    }

    @Test public void anEmptyDescriptionNeverReachesTheProvider() {
        FakeProvider provider = new FakeProvider(GOOD_PLAN);
        Result result = build(provider, "   ");
        assertEquals(0, provider.prompts.size());
        assertEquals(1, result.errors);
    }

    // ---------------------------------------------------------------- automation still works

    @Test public void aWeekdayTriggerRequestStillDrafts() {
        Result result = build(new FakeProvider("{\"name\":\"Night\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":15}}],"
                + "\"trigger\":{\"type\":\"time\",\"recurrence\":\"weekdays\",\"hour\":23,\"minute\":0}}"),
                "Every weekday at 11 PM, turn on Do Not Disturb and set brightness to 15%.");

        assertNotNull(result.draft);
        assertNotNull(result.draft.trigger);
        assertEquals(RoutineTriggerStore.MODE_WEEKDAYS, result.draft.trigger.mode);
        assertEquals(23, result.draft.trigger.hour);
        assertEquals(15, result.draft.actions.get(1).params.optInt("percent", -1));
        assertEquals("the trigger was drafted, so no notice is needed", "", result.notice);
    }

    @Test public void aScheduledRequestWithNoTriggerStillExplainsItself() {
        Result result = build(new FakeProvider(GOOD_PLAN),
                "every weekday at 8 turn on do not disturb");
        assertNotNull(result.draft);
        assertFalse("the user must be told timing was not drafted", result.notice.isEmpty());
    }

    // ---------------------------------------------------------------- diagnostics

    @Test public void aSuccessfulPlanIsRecorded() {
        build(new FakeProvider(GOOD_PLAN), REQUEST);
        SharedPreferences d = DiagnosticStore.prefs(context);

        assertEquals("Test provider", d.getString("plan_provider", ""));
        assertTrue(d.getBoolean("plan_parsed", false));
        assertEquals(2, d.getInt("plan_steps_returned", 0));
        assertEquals(2, d.getInt("plan_steps_accepted", 0));
        assertTrue(d.getString("plan_types", "").contains("SET_DND"));
        assertFalse(d.getBoolean("plan_repair", true));
        assertEquals("", d.getString("plan_failure", "x"));
        assertTrue(d.getLong("plan_updated", 0L) > 0L);
    }

    @Test public void aFailedPlanRecordsTheReasonAndTheResponse() {
        build(new FakeProvider("no json here", "still nothing"), REQUEST);
        SharedPreferences d = DiagnosticStore.prefs(context);

        assertFalse(d.getBoolean("plan_parsed", true));
        assertTrue(d.getBoolean("plan_repair", false));
        assertEquals(RoutinePlanner.UNREADABLE_MESSAGE, d.getString("plan_failure", ""));
        assertTrue("the raw response is what makes this diagnosable",
                d.getString("plan_raw", "").contains("still nothing"));
    }

    @Test public void aRejectedStepIsRecordedWithItsReason() {
        build(new FakeProvider("{\"name\":\"Mixed\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SEND_WHATSAPP\",\"params\":{}}]}"), REQUEST);

        String rejected = DiagnosticStore.prefs(context).getString("plan_rejected", "");
        assertTrue(rejected.contains("SEND_WHATSAPP"));
        assertTrue(rejected.contains("unsupported action type"));
    }

    @Test public void theRawTraceIsBounded() {
        StringBuilder huge = new StringBuilder("prose ");
        for (int i = 0; i < 4000; i++) huge.append("padding ");
        build(new FakeProvider(huge.toString(), huge.toString()), REQUEST);

        assertTrue("the trace must stay small",
                DiagnosticStore.prefs(context).getString("plan_raw", "").length() < 1400);
    }

    @Test public void theDiagnosticTraceCarriesNoUnrelatedPrivateContext() {
        MemoryStore.add(context, "personal", "My passport number is 12345");
        SavedPlaceStore.upsert(context, SavedPlaceStore.create("Home", 51.5d, -0.12d));

        build(new FakeProvider(GOOD_PLAN), REQUEST);
        String trace = DiagnosticStore.prefs(context).getAll().toString();

        assertFalse("no memory content", trace.contains("passport"));
        assertFalse("no saved-place coordinates", trace.contains("51.5"));
        assertFalse("no saved-place coordinates", trace.contains("-0.12"));
    }

    // ---------------------------------------------------------------- privacy

    @Test public void thePlanningPromptCarriesNoPrivateContext() {
        MemoryStore.add(context, "personal", "My passport number is 12345");
        SavedPlaceStore.upsert(context, SavedPlaceStore.create("Home", 51.5d, -0.12d));

        FakeProvider provider = new FakeProvider("nope", GOOD_PLAN);
        build(provider, REQUEST);

        for (String prompt : provider.prompts) {
            assertFalse("memory contents must never be sent", prompt.contains("passport"));
            assertFalse("coordinates must never be sent", prompt.contains("51.5"));
            assertFalse("coordinates must never be sent", prompt.contains("-0.12"));
            assertTrue("only the place label may be sent", prompt.contains("Home"));
        }
    }
}
