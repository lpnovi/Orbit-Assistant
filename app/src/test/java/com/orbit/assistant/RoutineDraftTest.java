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
 * Planner output is untrusted. A draft may only contain actions the user could already have added
 * by hand, and anything else has to surface as a warning rather than becoming a step.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineDraftTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private RoutineDraft parse(String json) {
        return RoutineDraft.fromPlannerJson(context, json);
    }

    @Test public void aSingleStepDraftIsAccepted() {
        RoutineDraft draft = parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        assertEquals("Focus", draft.name);
        assertEquals(1, draft.actions.size());
        assertEquals(RoutineActionCatalog.SET_DND, draft.actions.get(0).type);
    }

    @Test public void multipleStepsKeepTheirOrder() {
        RoutineDraft draft = parse("{\"name\":\"Bedtime\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":20}},"
                + "{\"type\":\"SET_VOLUME\",\"params\":{\"percent\":10}}]}");
        assertNotNull(draft);
        assertEquals(3, draft.actions.size());
        assertEquals(RoutineActionCatalog.SET_DND, draft.actions.get(0).type);
        assertEquals(RoutineActionCatalog.SET_BRIGHTNESS, draft.actions.get(1).type);
        assertEquals(RoutineActionCatalog.SET_VOLUME, draft.actions.get(2).type);
        assertEquals(20, draft.actions.get(1).params.optInt("percent"));
    }

    @Test public void anUnknownActionTypeIsRejected() {
        RoutineDraft draft = parse("{\"name\":\"Bad\",\"steps\":["
                + "{\"type\":\"SEND_WHATSAPP\",\"params\":{\"to\":\"someone\"}},"
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        assertEquals("only the supported step survives", 1, draft.actions.size());
        assertEquals(RoutineActionCatalog.SET_DND, draft.actions.get(0).type);
        assertFalse("the rejected request must be reported", draft.warnings.isEmpty());
    }

    @Test public void anOutOfRangeParameterIsRejected() {
        RoutineDraft draft = parse("{\"name\":\"Bad\",\"steps\":["
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":400}},"
                + "{\"type\":\"SET_VOLUME\",\"params\":{\"percent\":50}}]}");
        assertNotNull(draft);
        assertEquals(1, draft.actions.size());
        assertEquals(RoutineActionCatalog.SET_VOLUME, draft.actions.get(0).type);
    }

    @Test public void aPartlySupportedRequestKeepsItsValidSteps() {
        RoutineDraft draft = parse("{\"name\":\"Morning\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":false}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":80}}],"
                + "\"unsupported\":[\"order coffee\"]}");
        assertNotNull(draft);
        assertEquals(2, draft.actions.size());
        assertTrue(draft.warnings.contains("order coffee"));
    }

    @Test public void anUnsupportedRequestNeverBecomesAStep() {
        RoutineDraft draft = parse("{\"name\":\"Coffee\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}],"
                + "\"unsupported\":[\"start my coffee maker\"]}");
        assertNotNull(draft);
        assertEquals("the warning must not add an executable step", 1, draft.actions.size());
        assertTrue(draft.warnings.contains("start my coffee maker"));
    }

    @Test public void aValidConditionIsKeptAheadOfTheStepsItGuards() {
        // Allowed from v0.7.3.1, and only through the same catalog validation as any other step.
        RoutineDraft draft = parse("{\"name\":\"Nightly\",\"steps\":["
                + "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"time\",\"startMinute\":1320,"
                + "\"endMinute\":1380,\"nextSteps\":1}},"
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        assertEquals(2, draft.actions.size());
        assertEquals(RoutineActionCatalog.IF_CONDITION, draft.actions.get(0).type);
        assertEquals(RoutineActionCatalog.SET_DND, draft.actions.get(1).type);
    }

    @Test public void anExtensionActionIsRejectedWhenNoExtensionIsEnabled() {
        RoutineDraft draft = parse("{\"name\":\"Notify\",\"steps\":["
                + "{\"type\":\"EXTENSION_ACTION\",\"params\":{\"extensionId\":\"made.up\","
                + "\"actionId\":\"send\",\"extensionName\":\"Made Up\",\"actionName\":\"Send\"}},"
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        assertEquals("an unavailable extension action must not be added", 1, draft.actions.size());
        assertEquals(RoutineActionCatalog.SET_DND, draft.actions.get(0).type);
    }

    @Test public void malformedOutputIsRejected() {
        assertNull(parse(null));
        assertNull(parse(""));
        assertNull(parse("not json at all"));
        assertNull(parse("{\"name\":\"X\"}"));
        assertNull(parse("{\"name\":\"X\",\"steps\":[]}"));
    }

    @Test public void proseWrappedJsonIsStillReadable() {
        RoutineDraft draft = parse("Here you go!\n```json\n{\"name\":\"Focus\",\"steps\":"
                + "[{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}\n```\nHope that helps.");
        assertNotNull(draft);
        assertEquals("Focus", draft.name);
    }

    @Test public void aDraftWithNoValidStepsIsRejectedEntirely() {
        assertNull("nothing usable must not become an empty routine",
                parse("{\"name\":\"Nope\",\"steps\":["
                        + "{\"type\":\"LAUNCH_ROCKET\",\"params\":{}}]}"));
    }

    @Test public void tooManyStepsAreCapped() {
        StringBuilder json = new StringBuilder("{\"name\":\"Long\",\"steps\":[");
        for (int i = 0; i < RoutineActionCatalog.MAX_STEPS + 6; i++) {
            if (i > 0) json.append(',');
            json.append("{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}");
        }
        json.append("]}");

        RoutineDraft draft = parse(json.toString());
        assertNotNull(draft);
        assertEquals(RoutineActionCatalog.MAX_STEPS, draft.actions.size());
        assertFalse(draft.warnings.isEmpty());
    }

    @Test public void anOversizedNameIsTrimmedToTheRoutineLimit() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 40; i++) name.append("verylongname ");
        RoutineDraft draft = parse("{\"name\":\"" + name + "\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        assertTrue(draft.name.length() <= RoutineStore.MAX_NAME_LENGTH);
    }

    @Test public void aMissingNameGetsAUsableDefault() {
        RoutineDraft draft = parse("{\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        assertFalse(draft.name.trim().isEmpty());
    }

    @Test public void theDraftPayloadRoundTripsAndIsRevalidated() {
        RoutineDraft draft = parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":35}}],"
                + "\"unsupported\":[\"order coffee\"]}");
        assertNotNull(draft);

        RoutineDraft restored = RoutineDraft.fromPayload(context, draft.toPayload());
        assertNotNull(restored);
        assertEquals("Focus", restored.name);
        assertEquals(2, restored.actions.size());
        assertEquals(35, restored.actions.get(1).params.optInt("percent"));
        assertTrue(restored.warnings.contains("order coffee"));
    }

    @Test public void aTamperedPayloadCannotSmuggleInAnAction() {
        // Even arriving through the editor Intent, every step is validated again.
        String tampered = "{\"schema\":1,\"name\":\"Bad\",\"steps\":["
                + "{\"type\":\"WIPE_DEVICE\",\"params\":{}}],\"warnings\":[]}";
        assertNull(RoutineDraft.fromPayload(context, tampered));
    }

    @Test public void aPayloadWithTheWrongSchemaIsRejected() {
        assertNull(RoutineDraft.fromPayload(context, "{\"schema\":99,\"name\":\"X\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}"));
        assertNull(RoutineDraft.fromPayload(context, ""));
        assertNull(RoutineDraft.fromPayload(context, null));
    }

    @Test public void buildingADraftSavesNothing() {
        RoutineDraft draft = parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}}]}");
        assertNotNull(draft);
        RoutineDraft.fromPayload(context, draft.toPayload());

        assertTrue("a draft must never reach routine storage",
                RoutineStore.list(context).isEmpty());
    }

    @Test public void stepSummariesDescribeEachAction() {
        RoutineDraft draft = parse("{\"name\":\"Focus\",\"steps\":["
                + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                + "{\"type\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":35}}]}");
        assertNotNull(draft);
        assertEquals(2, draft.stepSummaries().size());
        for (String summary : draft.stepSummaries()) assertFalse(summary.trim().isEmpty());
    }
}
