package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The layer between what a provider actually returns and what Orbit will run.
 *
 * <p>These tests deliberately start from raw provider response text rather than from ideal JSON
 * handed straight to {@link RoutineDraft}: the v0.7.3.1 failure lived entirely in that gap, and
 * every test here that starts from a perfect object would have passed while the feature was
 * completely broken on a real device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutinePlanResponseTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private RoutineDraft.Outcome parse(String raw) {
        return RoutineDraft.parse(context, raw);
    }

    private void assertDndAndBrightness(RoutineDraft draft, boolean dndOn, int percent) {
        assertNotNull("a draft was expected", draft);
        assertEquals(2, draft.actions.size());
        assertEquals(RoutineActionCatalog.SET_DND, draft.actions.get(0).type);
        assertEquals(dndOn, draft.actions.get(0).params.optBoolean("enabled", !dndOn));
        assertEquals(RoutineActionCatalog.SET_BRIGHTNESS, draft.actions.get(1).type);
        assertEquals(percent, draft.actions.get(1).params.optInt("percent", -1));
    }

    // ---------------------------------------------------------------- root cause

    @Test public void plannerJsonIsNotAChatReply() throws Exception {
        // The v0.7.3.1 defect in one assertion: the chat parser reads a reply's "text" field, and
        // a plan object has none, so a perfectly correct plan arrived at the builder as "".
        String plan = "{\"name\":\"Focus mode\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}";
        AssistantReply asChat = AssistantReply.fromJson(new JSONObject(plan));
        assertEquals("", asChat.text);
        assertTrue(asChat.actions.isEmpty());

        // The planning path reads the same response correctly.
        assertNotNull(parse(plan).draft);
    }

    // ---------------------------------------------------------------- exact reproduction

    @Test public void theReportedFailureNowProducesADraft() {
        // "turn on do not disturb and set brightness to 30%", as a bare plan object.
        RoutineDraft.Outcome outcome = parse("{\"name\":\"Focus mode\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":30}}],"
                + "\"trigger\":null,\"unsupported\":[]}");
        assertDndAndBrightness(outcome.draft, true, 30);
        assertEquals("Focus mode", outcome.draft.name);
        assertNull("no trigger was asked for", outcome.draft.trigger);
        assertEquals(2, outcome.stepsAccepted);
    }

    @Test public void theReportedFailureAlsoWorksFromTheChatEnvelope() {
        // The same request when the transport applied Orbit's ordinary chat response format and
        // the plan arrived inside the text field.
        RoutineDraft.Outcome outcome = parse("{\"text\":\"{\\\"name\\\":\\\"Focus mode\\\","
                + "\\\"steps\\\":[{\\\"type\\\":\\\"SET_DND\\\",\\\"params\\\":{\\\"enabled\\\":true}},"
                + "{\\\"type\\\":\\\"SET_BRIGHTNESS\\\",\\\"params\\\":{\\\"percent\\\":30}}]}\","
                + "\"actions\":[]}");
        assertDndAndBrightness(outcome.draft, true, 30);
        assertTrue(outcome.shape.contains("chat envelope"));
    }

    @Test public void theReportedFailureAlsoWorksFromChatActions() {
        // And when it came back as ordinary Orbit chat actions, which are the same {type, params}
        // shape a routine step uses.
        RoutineDraft.Outcome outcome = parse("{\"text\":\"Set up a focus routine.\",\"actions\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":30}}]}");
        assertDndAndBrightness(outcome.draft, true, 30);
    }

    @Test public void theOppositeRequestIsDraftedToo() {
        assertDndAndBrightness(parse("{\"name\":\"Day\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":false}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":75}}]}").draft, false, 75);
    }

    @Test public void bothEndsOfTheBrightnessRangeSurvive() {
        for (int percent : new int[]{0, 100}) {
            RoutineDraft draft = parse("{\"name\":\"B\",\"steps\":["
                    + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":" + percent + "}}]}").draft;
            assertNotNull("brightness " + percent + " must be valid", draft);
            assertEquals(percent, draft.actions.get(0).params.optInt("percent", -1));
        }
    }

    // ---------------------------------------------------------------- response shapes

    @Test public void cleanJsonIsRead() {
        assertNotNull(parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}").draft);
    }

    @Test public void aMarkdownCodeFenceIsStripped() {
        RoutineDraft.Outcome outcome = parse("```json\n{\"name\":\"Focus\",\"steps\":"
                + "[{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}\n```");
        assertNotNull(outcome.draft);
        assertTrue(outcome.shape.startsWith("fenced"));
    }

    @Test public void proseBeforeTheObjectIsTolerated() {
        assertNotNull(parse("Sure! Here is the routine:\n{\"name\":\"Focus\",\"steps\":"
                + "[{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}").draft);
    }

    @Test public void proseAfterTheObjectIsTolerated() {
        assertNotNull(parse("{\"name\":\"Focus\",\"steps\":"
                + "[{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}\n\nLet me know!").draft);
    }

    @Test public void aBraceInTrailingProseCannotExtendTheObject() {
        // A first-brace-to-last-brace scan would swallow the trailing text and fail to parse.
        RoutineDraft draft = parse("{\"name\":\"Focus\",\"steps\":"
                + "[{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}\n"
                + "Note: use {curly braces} carefully.").draft;
        assertNotNull(draft);
        assertEquals("Focus", draft.name);
    }

    @Test public void aBraceInsideAStringDoesNotTruncateTheObject() {
        RoutineDraft draft = parse("{\"name\":\"Focus }\",\"steps\":"
                + "[{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}").draft;
        assertNotNull(draft);
        assertEquals(1, draft.actions.size());
    }

    @Test public void harmlessExtraFieldsAreIgnored() {
        assertNotNull(parse("{\"name\":\"Focus\",\"version\":2,\"confidence\":0.9,"
                + "\"explanation\":\"a focus routine\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true},\"note\":\"quiet\"}]}").draft);
    }

    @Test public void malformedJsonIsReportedAsUnreadable() {
        RoutineDraft.Outcome outcome = parse("{\"name\":\"Focus\",\"steps\":[{\"type\":");
        assertNull(outcome.draft);
        assertTrue(outcome.isUnreadable());
    }

    @Test public void aResponseWithNoObjectIsReportedAsUnreadable() {
        RoutineDraft.Outcome outcome = parse("I can help with that, but I need more detail first.");
        assertNull(outcome.draft);
        assertTrue(outcome.isUnreadable());
        assertFalse(outcome.planFound);
    }

    @Test public void anEmptyResponseIsUnreadable() {
        assertTrue(parse("").isUnreadable());
        assertTrue(parse(null).isUnreadable());
        assertTrue(parse("   ").isUnreadable());
    }

    @Test public void anObjectWithoutStepsIsUnreadable() {
        // Nothing to validate: worth one correction request rather than a wrong error message.
        RoutineDraft.Outcome outcome = parse("{\"name\":\"Focus\",\"summary\":\"quiet mode\"}");
        assertNull(outcome.draft);
        assertTrue(outcome.isUnreadable());
    }

    // ---------------------------------------------------------------- normalisation

    @Test public void knownEquivalentTypeSpellingsAreCanonicalised() {
        assertEquals(RoutineActionCatalog.SET_DND, RoutinePlanResponse.canonicalType("SET_DO_NOT_DISTURB"));
        assertEquals(RoutineActionCatalog.SET_DND, RoutinePlanResponse.canonicalType("dnd"));
        assertEquals(RoutineActionCatalog.SET_DND, RoutinePlanResponse.canonicalType("do not disturb"));
        assertEquals(RoutineActionCatalog.SET_BRIGHTNESS, RoutinePlanResponse.canonicalType("brightness"));
        assertEquals(RoutineActionCatalog.SET_VOLUME, RoutinePlanResponse.canonicalType("media-volume"));
        assertEquals(RoutineActionCatalog.FLASHLIGHT, RoutinePlanResponse.canonicalType("torch"));
        assertEquals(RoutineActionCatalog.OPEN_APP, RoutinePlanResponse.canonicalType("launch_app"));
        assertEquals(RoutineActionCatalog.SET_DND, RoutinePlanResponse.canonicalType("set_dnd"));
    }

    @Test public void casingAndSpacingDifferencesStillDraft() {
        assertDndAndBrightness(parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"set_do_not_disturb\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"Set Brightness\",\"params\":{\"percent\":30}}]}").draft, true, 30);
    }

    @Test public void aPercentageStringIsConverted() {
        assertDndAndBrightness(parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":\"30%\"}}]}").draft, true, 30);
    }

    @Test public void aNumericStringPercentageIsConverted() {
        assertEquals(30, RoutinePlanResponse.asPercent("30"));
        assertEquals(30, RoutinePlanResponse.asPercent("30%"));
        assertEquals(30, RoutinePlanResponse.asPercent(30));
        assertEquals(30, RoutinePlanResponse.asPercent(0.3d));
        assertEquals(-1, RoutinePlanResponse.asPercent("dim"));
    }

    @Test public void equivalentPercentParameterNamesAreMapped() {
        RoutineDraft draft = parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"brightness\":30}},"
                + "{\"type\":\"SET_VOLUME\",\"params\":{\"level\":40}}]}").draft;
        assertNotNull(draft);
        assertEquals(30, draft.actions.get(0).params.optInt("percent", -1));
        assertEquals(40, draft.actions.get(1).params.optInt("percent", -1));
    }

    @Test public void unambiguousBooleanSpellingsAreNormalised() {
        assertTrue(RoutinePlanResponse.asBoolean("on", false));
        assertTrue(RoutinePlanResponse.asBoolean("true", false));
        assertTrue(RoutinePlanResponse.asBoolean("enabled", false));
        assertFalse(RoutinePlanResponse.asBoolean("off", true));
        assertFalse(RoutinePlanResponse.asBoolean("no", true));
        // Anything ambiguous keeps the action's own default rather than being guessed.
        assertTrue(RoutinePlanResponse.asBoolean("maybe", true));
        assertFalse(RoutinePlanResponse.asBoolean("sometimes", false));
    }

    @Test public void aBooleanStringDraftsCorrectly() {
        RoutineDraft draft = parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":\"true\"}},"
                + "{\"type\":\"FLASHLIGHT\",\"params\":{\"on\":\"off\"}}]}").draft;
        assertNotNull(draft);
        assertTrue(draft.actions.get(0).params.optBoolean("enabled", false));
        assertFalse(draft.actions.get(1).params.optBoolean("on", true));
    }

    @Test public void aStepThatCarriedItsValuesInlineIsUnderstood() {
        assertDndAndBrightness(parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"enabled\":true},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"percent\":30}]}").draft, true, 30);
    }

    @Test public void timerAndAlarmValueFormsAreNormalised() {
        RoutineDraft draft = parse("{\"name\":\"Mixed\",\"steps\":["
                + "{\"type\":\"SET_TIMER\",\"params\":{\"minutes\":5}},"
                + "{\"type\":\"SET_ALARM\",\"params\":{\"time\":\"07:30\"}}]}").draft;
        assertNotNull(draft);
        assertEquals(300, draft.actions.get(0).params.optInt("seconds", -1));
        assertEquals(7, draft.actions.get(1).params.optInt("hour", -1));
        assertEquals(30, draft.actions.get(1).params.optInt("minute", -1));
    }

    // ---------------------------------------------------------------- safety

    @Test public void normalisationNeverInventsAnActionName() {
        // Not an alias of anything: it stays unknown and is reported, not approximated.
        RoutineDraft.Outcome outcome = parse("{\"name\":\"Bad\",\"steps\":["
                + "{\"type\":\"SEND_WHATSAPP\",\"params\":{\"to\":\"someone\"}},"
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(outcome.draft);
        assertEquals(1, outcome.draft.actions.size());
        assertEquals(RoutineActionCatalog.SET_DND, outcome.draft.actions.get(0).type);
        assertFalse(outcome.rejected.isEmpty());
        assertTrue(outcome.rejected.get(0).contains("SEND_WHATSAPP"));
    }

    @Test public void normalisationCannotBypassParameterValidation() {
        // A normalised value is still only a value: the catalog decides whether it is allowed.
        assertNull(parse("{\"name\":\"Bad\",\"steps\":["
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":\"400%\"}}]}").draft);
        assertNull(parse("{\"name\":\"Bad\",\"steps\":["
                + "{\"type\":\"BRIGHTNESS\",\"params\":{\"level\":-20}}]}").draft);
    }

    @Test public void aDangerousInventedActionIsNeverAccepted() {
        assertNull(parse("{\"name\":\"Bad\",\"steps\":["
                + "{\"type\":\"WIPE_DEVICE\",\"params\":{}},"
                + "{\"type\":\"FACTORY_RESET\",\"params\":{}}]}").draft);
    }

    @Test public void aPlanOfOnlyUnsupportedActionsIsNotAFormatProblem() {
        // Read perfectly well; simply not something Orbit can do. This must not look unreadable,
        // because that would trigger a pointless correction request.
        RoutineDraft.Outcome outcome = parse("{\"name\":\"Coffee\",\"steps\":["
                + "{\"type\":\"START_COFFEE_MAKER\",\"params\":{}}]}");
        assertNull(outcome.draft);
        assertFalse("this is an answer, not a formatting failure", outcome.isUnreadable());
        assertTrue(outcome.planFound);
        assertTrue(outcome.stepsArrayFound);
        assertEquals(1, outcome.stepsReturned);
        assertEquals(0, outcome.stepsAccepted);
    }

    @Test public void aMixtureKeepsOnlyTheSupportedSteps() {
        RoutineDraft.Outcome outcome = parse("{\"name\":\"Morning\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":false}},"
                + "{\"type\":\"ORDER_COFFEE\",\"params\":{}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":80}}]}");
        assertNotNull(outcome.draft);
        assertEquals(2, outcome.draft.actions.size());
        assertEquals(3, outcome.stepsReturned);
        assertEquals(2, outcome.stepsAccepted);
        assertFalse(outcome.draft.warnings.isEmpty());
    }

    @Test public void anExtensionActionStillRequiresAnEnabledExtension() {
        assertNull(parse("{\"name\":\"Notify\",\"steps\":["
                + "{\"type\":\"EXTENSION_ACTION\",\"params\":{\"extensionId\":\"made.up\","
                + "\"actionId\":\"send\"}}]}").draft);
    }

    // ---------------------------------------------------------------- natural wording

    @Test public void realisticEverydayRequestsDraft() {
        // Each entry is a realistic provider response for the phrase in its comment.
        // "Make me a focus routine that turns on DND and sets brightness to 30%."
        assertDndAndBrightness(parse("```json\n{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":30}}]}\n```").draft, true, 30);

        // "Turn on Do Not Disturb and dim brightness to 20%."
        assertDndAndBrightness(parse("{\"name\":\"Dim\",\"steps\":["
                + "{\"type\":\"SET_DO_NOT_DISTURB\",\"params\":{\"enabled\":\"on\"}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":\"20%\"}}]}").draft, true, 20);

        // "Create a bedtime routine with DND on and media volume at 15%."
        RoutineDraft bedtime = parse("{\"name\":\"Bedtime\",\"steps\":["
                + "{\"type\":\"DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"MEDIA_VOLUME\",\"params\":{\"percent\":15}}]}").draft;
        assertNotNull(bedtime);
        assertEquals(RoutineActionCatalog.SET_DND, bedtime.actions.get(0).type);
        assertEquals(RoutineActionCatalog.SET_VOLUME, bedtime.actions.get(1).type);
        assertEquals(15, bedtime.actions.get(1).params.optInt("percent", -1));

        // "Flashlight off and brightness 50%."
        RoutineDraft flashlight = parse("{\"name\":\"Reset\",\"steps\":["
                + "{\"type\":\"FLASHLIGHT\",\"params\":{\"on\":false}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":50}}]}").draft;
        assertNotNull(flashlight);
        assertFalse(flashlight.actions.get(0).params.optBoolean("on", true));
        assertEquals(50, flashlight.actions.get(1).params.optInt("percent", -1));

        // "Open Spotify and set media volume to 40%."
        RoutineDraft spotify = parse("{\"name\":\"Music\",\"steps\":["
                + "{\"type\":\"LAUNCH_APP\",\"params\":{\"appName\":\"Spotify\"}},"
                + "{\"type\":\"SET_VOLUME\",\"params\":{\"percent\":40}}]}").draft;
        assertNotNull(spotify);
        assertEquals(RoutineActionCatalog.OPEN_APP, spotify.actions.get(0).type);
        assertEquals("Spotify", spotify.actions.get(0).params.optString("app", ""));
        assertEquals(40, spotify.actions.get(1).params.optInt("percent", -1));
    }

    // ---------------------------------------------------------------- v0.7.3.1 automation

    @Test public void aWeekdayTriggerStillDraftsAfterNormalisation() {
        // "Every weekday at 11 PM, turn on Do Not Disturb and set brightness to 15%."
        RoutineDraft draft = parse("{\"name\":\"Night\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":15}}],"
                + "\"trigger\":{\"type\":\"time\",\"recurrence\":\"weekdays\",\"hour\":23,\"minute\":0}}").draft;
        assertDndAndBrightness(draft, true, 15);
        assertNotNull("the weekday trigger must survive", draft.trigger);
        assertTrue(draft.hasTrigger());
        assertEquals(RoutineTriggerStore.TYPE_TIME, draft.trigger.type);
        assertEquals(RoutineTriggerStore.MODE_WEEKDAYS, draft.trigger.mode);
        assertEquals(23, draft.trigger.hour);
        assertEquals(0, draft.trigger.minute);
    }

    @Test public void aDailyTriggerWithASavedPlaceConditionStillDrafts() {
        // "At 9 PM every day, if I'm Home, turn on Do Not Disturb."
        SavedPlaceStore.upsert(context, SavedPlaceStore.create("Home", 51.5d, -0.12d));

        RoutineDraft draft = parse("{\"name\":\"Home quiet\",\"steps\":["
                + "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"location\","
                + "\"locationName\":\"Home\",\"nextSteps\":1}},"
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}],"
                + "\"trigger\":{\"type\":\"time\",\"recurrence\":\"daily\",\"hour\":21,\"minute\":0}}").draft;
        assertNotNull(draft);
        assertEquals(2, draft.actions.size());
        assertEquals(RoutineActionCatalog.IF_CONDITION, draft.actions.get(0).type);
        assertEquals(RoutineActionCatalog.SET_DND, draft.actions.get(1).type);
        assertNotNull(draft.trigger);
        assertEquals(RoutineTriggerStore.MODE_DAILY, draft.trigger.mode);
        assertEquals(21, draft.trigger.hour);
    }

    @Test public void aConditionPlaceIsStillResolvedLocally() {
        SavedPlaceStore.upsert(context, SavedPlaceStore.create("Home", 51.5d, -0.12d));
        RoutineDraft draft = parse("{\"name\":\"Quiet\",\"steps\":["
                + "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"location\","
                + "\"place\":\"Home\",\"nextSteps\":1}},"
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}").draft;
        assertNotNull("the place alias must map to locationName", draft);
        assertEquals(RoutineActionCatalog.IF_CONDITION, draft.actions.get(0).type);
        assertEquals(51.5d, draft.actions.get(0).params.optDouble("latitude", 0d), 0.0001d);
    }

    @Test public void aDraftStillCannotReachStorage() {
        RoutineDraft draft = parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}],"
                + "\"trigger\":{\"type\":\"time\",\"recurrence\":\"daily\",\"hour\":7,\"minute\":0}}").draft;
        assertNotNull(draft);
        assertTrue("planning must save nothing", RoutineStore.list(context).isEmpty());
        assertTrue("planning must schedule nothing",
                RoutineTriggerStore.list(context).isEmpty());
    }
}
