package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Create with Orbit and branching.
 *
 * <p>A drafted ELSE is positional — it is the steps immediately after the IF path — so the risk is
 * not that the planner refuses, but that a step it asked for gets dropped during validation and
 * every later step silently shifts into the wrong branch. These prove Orbit reports that instead,
 * and that a request beyond one dependable level of branching stays unsupported rather than being
 * approximated.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineBranchPlanningTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    private static String plan(String steps) {
        return "{\"name\":\"Home or away\",\"steps\":[" + steps + "]}";
    }

    private static String dnd(boolean enabled) {
        return "{\"type\":\"SET_DND\",\"params\":{\"enabled\":" + enabled + "}}";
    }

    private static String ifTime(int trueSteps, int elseSteps) {
        return "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"time\",\"startMinute\":1080,"
                + "\"endMinute\":1320,\"nextSteps\":" + trueSteps
                + ",\"elseSteps\":" + elseSteps + "}}";
    }

    // ---- the simple case the release promises -------------------------------------------------

    /** "If it's the evening, turn on DND, otherwise turn it off." */
    @Test public void aSimpleOtherwiseRequestDraftsAnElsePath() {
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context,
                plan(ifTime(1, 1) + "," + dnd(true) + "," + dnd(false)));
        assertNotNull(draft);
        assertEquals(3, draft.actions.size());
        assertEquals(1, RoutineBranch.trueSteps(draft.actions.get(0)));
        assertEquals(1, RoutineBranch.elseSteps(draft.actions.get(0)));
        assertTrue(RoutineBranch.structureValid(draft.actions));
        assertTrue("a clean plan needs no warning", draft.warnings.isEmpty());
    }

    @Test public void aDraftedBranchStillOpensInTheEditorUnchanged() {
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context,
                plan(ifTime(1, 2) + "," + dnd(true) + "," + dnd(false) + ","
                        + "{\"type\":\"SET_VOLUME\",\"params\":{\"percent\":40}}"));
        assertNotNull(draft);
        RoutineDraft reopened = RoutineDraft.fromPayload(context, draft.toPayload());
        assertNotNull("the draft has to survive the handoff to the editor", reopened);
        assertEquals(draft.actions.size(), reopened.actions.size());
        assertEquals(2, RoutineBranch.elseSteps(reopened.actions.get(0)));
        assertTrue(RoutineBranch.structureValid(reopened.actions));
    }

    @Test public void aPlanWithNoBranchIsUnaffected() {
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context,
                plan(dnd(true) + ",{\"type\":\"SET_VOLUME\",\"params\":{\"percent\":20}}"));
        assertNotNull(draft);
        assertEquals(2, draft.actions.size());
        assertFalse(RoutineDraft.hasBranch(draft.actions));
        assertTrue(draft.warnings.isEmpty());
    }

    // ---- a branch Orbit cannot reproduce is reported, never guessed at ---------------------------

    @Test public void aDroppedBranchStepRemovesTheElseRatherThanShiftingActionsIntoIt() {
        // The middle step is not a supported action, so it never reaches the draft. Rebuilding the
        // branch from the shortened list would move the "off" step onto the IF path.
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context,
                plan(ifTime(1, 1) + ",{\"type\":\"LAUNCH_ROCKET\",\"params\":{}}," + dnd(false)));
        assertNotNull(draft);
        assertEquals(0, RoutineBranch.elseSteps(draft.actions.get(0)));
        assertTrue(warningsMention(draft, "otherwise"));
    }

    @Test public void aBranchCutShortByTheStepLimitLosesItsElse() {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < RoutineActionCatalog.MAX_STEPS - 1; i++) {
            steps.append("{\"type\":\"SET_VOLUME\",\"params\":{\"percent\":").append(i).append("}},");
        }
        // The branch is declared right at the limit, so its ELSE step is truncated away.
        steps.append(ifTime(1, 1)).append(",").append(dnd(true)).append(",").append(dnd(false));
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context, plan(steps.toString()));
        assertNotNull(draft);
        assertTrue(RoutineBranch.structureValid(draft.actions));
        for (AssistantReply.Action action : draft.actions) {
            assertFalse("a truncated branch must not survive as a half branch",
                    RoutineBranch.hasElse(action));
        }
    }

    @Test public void nestedBranchingIsRefusedRatherThanApproximated() {
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context,
                plan(ifTime(2, 1) + "," + dnd(true) + "," + ifTime(1, 0) + "," + dnd(false)));
        assertNotNull(draft);
        assertTrue("the draft must be saveable", RoutineBranch.structureValid(draft.actions));
        assertFalse("no nested branch may reach the editor", RoutineDraft.hasBranch(draft.actions));
        assertTrue(warningsMention(draft, "otherwise"));
    }

    @Test public void anElseCountBeyondTheSupportedRangeIsRejectedOutright() {
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context,
                plan("{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"time\",\"startMinute\":0,"
                        + "\"endMinute\":60,\"nextSteps\":1,\"elseSteps\":9}}," + dnd(true)));
        // The condition itself fails catalog validation, so only the plain step survives.
        assertNotNull(draft);
        for (AssistantReply.Action action : draft.actions) {
            assertFalse(RoutineConditionEvaluator.isCondition(action));
        }
    }

    // ---- the planner is told what it may and may not produce --------------------------------------

    @Test public void thePromptDescribesTheBranchLayoutAndItsOneLevelLimit() {
        String prompt = RoutinePlanner.prompt(context, "if I'm home turn on dnd otherwise turn it off");
        assertTrue(prompt.contains("elseSteps"));
        assertTrue(prompt.contains("otherwise"));
        assertTrue("the layout has to be unambiguous", prompt.contains("then elseSteps steps"));
        assertTrue(prompt.contains("Exactly one path runs"));
        assertTrue("nesting and loops stay out of scope",
                prompt.contains("One level of branching only"));
        assertTrue(prompt.contains("loops"));
        assertFalse("branching is no longer refused outright",
                prompt.contains("Do not invent an \"otherwise\""));
    }

    /** A planner still answering in the old shape must not silently lose the request. */
    @Test public void aLegacyElseRequestedFlagIsStillReported() {
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context,
                "{\"name\":\"Legacy\",\"elseRequested\":true,\"steps\":[" + dnd(true) + "]}");
        assertNotNull(draft);
        assertTrue(warningsMention(draft, "otherwise"));
    }

    @Test public void aLegacyElseRequestedFlagIsIgnoredWhenTheBranchWasBuilt() {
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context,
                "{\"name\":\"Both\",\"elseRequested\":true,\"steps\":["
                        + ifTime(1, 1) + "," + dnd(true) + "," + dnd(false) + "]}");
        assertNotNull(draft);
        assertTrue(RoutineDraft.hasBranch(draft.actions));
        assertFalse("nothing is wrong, so nothing should be warned about",
                warningsMention(draft, "otherwise"));
    }

    private static boolean warningsMention(RoutineDraft draft, String fragment) {
        for (String warning : draft.warnings) {
            if (warning.toLowerCase().contains(fragment.toLowerCase())) return true;
        }
        return false;
    }
}
