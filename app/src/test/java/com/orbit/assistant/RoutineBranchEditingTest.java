package com.orbit.assistant;

import static com.orbit.assistant.RoutineBranchTest.chain;
import static com.orbit.assistant.RoutineBranchTest.condition;
import static com.orbit.assistant.RoutineBranchTest.timer;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Authoring a branch as two visible paths.
 *
 * <p>v0.7.5.0 stored a branch as {@code nextSteps} and {@code elseSteps} and made the user pick
 * those numbers. The numbers stay — they are still exactly what executes — but they are now
 * bookkeeping Orbit maintains. These tests are about that bookkeeping staying correct through every
 * add, remove and reorder the editor offers, because a count that drifts out of step with the list
 * silently moves an action from one path to the other.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineBranchEditingTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** THEN actions, read the way the editor reads them. */
    private static List<Integer> thenPath(List<AssistantReply.Action> actions, int conditionIndex) {
        return pathTimers(actions, conditionIndex, RoutineBranch.BRANCH_TRUE);
    }

    /** OTHERWISE actions, read the way the editor reads them. */
    private static List<Integer> otherwisePath(List<AssistantReply.Action> actions, int conditionIndex) {
        return pathTimers(actions, conditionIndex, RoutineBranch.BRANCH_ELSE);
    }

    private static List<Integer> pathTimers(List<AssistantReply.Action> actions,
                                            int conditionIndex, int kind) {
        RoutineBranch.Span span = RoutineBranch.spanAt(actions, conditionIndex);
        List<Integer> out = new ArrayList<>();
        if (span == null) return out;
        int from = kind == RoutineBranch.BRANCH_ELSE ? span.elseStart : span.trueStart;
        int to = kind == RoutineBranch.BRANCH_ELSE ? span.elseEnd : span.trueEnd;
        for (int i = from; i < to; i++) {
            out.add(actions.get(i).params.optInt("seconds", -1));
        }
        return out;
    }

    private static List<Integer> tail(List<AssistantReply.Action> actions) {
        List<Integer> out = new ArrayList<>();
        RoutineBranch.Unit first = RoutineBranch.units(actions).get(0);
        for (int i = first.end; i < actions.size(); i++) {
            out.add(actions.get(i).params.optInt("seconds", -1));
        }
        return out;
    }

    private static List<Integer> list(int... values) {
        List<Integer> out = new ArrayList<>();
        for (int value : values) out.add(value);
        return out;
    }

    // ---- how existing routines appear ---------------------------------------------------------

    /** Case A: a pre-v0.7.5 IF-only routine reads as THEN with actions and OTHERWISE showing None. */
    @Test public void anIfOnlyRoutineReadsAsThenPlusAnEmptyOtherwise() {
        List<AssistantReply.Action> actions = chain(condition(2, 0), timer(11), timer(12), timer(90));
        assertEquals(list(11, 12), thenPath(actions, 0));
        assertTrue("OTHERWISE shows None rather than an action", otherwisePath(actions, 0).isEmpty());
        assertEquals("the step after the branch stays outside it", list(90), tail(actions));
        assertFalse(RoutineBranch.hasElse(actions.get(0)));
    }

    /** Case B: a v0.7.5.0 routine's spans land in the right sections with no migration step. */
    @Test public void anExistingElseRoutineMapsStraightIntoBothSections() {
        List<AssistantReply.Action> actions = chain(
                condition(2, 1), timer(11), timer(12), timer(21), timer(90));
        assertEquals(list(11, 12), thenPath(actions, 0));
        assertEquals(list(21), otherwisePath(actions, 0));
        assertEquals(list(90), tail(actions));
    }

    @Test public void openingARoutineNeverRewritesIt() {
        // A legacy IF whose gate reaches past the last step. Reading it must change nothing.
        List<AssistantReply.Action> actions = chain(condition(4, 0), timer(11));
        String before = actions.get(0).params.toString();
        RoutineBranch.units(actions);
        RoutineBranch.branchMap(actions);
        RoutineBranch.spanAt(actions, 0);
        assertEquals("reading a routine must not touch it", before, actions.get(0).params.toString());
        assertEquals(4, RoutineBranch.trueSteps(actions.get(0)));
    }

    // ---- units --------------------------------------------------------------------------------

    @Test public void aBranchIsOneUnitAndOrdinaryStepsAreTheirOwn() {
        List<AssistantReply.Action> actions = chain(
                timer(1), condition(1, 1), timer(11), timer(21), timer(90));
        List<RoutineBranch.Unit> units = RoutineBranch.units(actions);
        assertEquals(3, units.size());
        assertFalse(units.get(0).branch);
        assertTrue(units.get(1).branch);
        assertEquals(1, units.get(1).start);
        assertEquals(4, units.get(1).end);
        assertFalse(units.get(2).branch);
    }

    @Test public void aTrailingConditionIsStillItsOwnUnit() {
        List<AssistantReply.Action> actions = chain(timer(1), condition(1, 0));
        List<RoutineBranch.Unit> units = RoutineBranch.units(actions);
        assertEquals(2, units.size());
        assertTrue(units.get(1).branch);
        assertEquals(1, units.get(1).size());
    }

    // ---- adding -------------------------------------------------------------------------------

    @Test public void addingAThenActionExtendsOnlyTheThenPath() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21), timer(90));
        assertTrue(RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_TRUE, timer(12)));

        assertEquals(list(11, 12), thenPath(actions, 0));
        assertEquals("the OTHERWISE action must not be absorbed", list(21), otherwisePath(actions, 0));
        assertEquals(list(90), tail(actions));
        assertEquals(2, RoutineBranch.trueSteps(actions.get(0)));
        assertEquals(1, RoutineBranch.elseSteps(actions.get(0)));
    }

    @Test public void addingAnOtherwiseActionExtendsOnlyTheOtherwisePath() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21), timer(90));
        assertTrue(RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_ELSE, timer(22)));

        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(21, 22), otherwisePath(actions, 0));
        assertEquals("the step after the branch stays after it", list(90), tail(actions));
        assertEquals(2, RoutineBranch.elseSteps(actions.get(0)));
    }

    @Test public void theFirstOtherwiseActionTurnsAnIfOnlyRoutineIntoABranch() {
        List<AssistantReply.Action> actions = chain(condition(1, 0), timer(11), timer(90));
        assertTrue(RoutineBranch.canAddTo(actions, 0, RoutineBranch.BRANCH_ELSE));
        assertTrue(RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_ELSE, timer(21)));

        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(21), otherwisePath(actions, 0));
        assertEquals(list(90), tail(actions));
        assertTrue(RoutineBranch.structureValid(actions));
    }

    /** A legacy gate that overruns its steps is brought in line the first time it is edited. */
    @Test public void anOverrunningLegacyGateIsCorrectedOnFirstEdit() {
        List<AssistantReply.Action> actions = chain(condition(4, 0), timer(11));
        assertTrue(RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_ELSE, timer(21)));

        assertEquals("the declared count now matches the actions actually present",
                1, RoutineBranch.trueSteps(actions.get(0)));
        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(21), otherwisePath(actions, 0));
    }

    @Test public void anOtherwisePathIsNotOfferedUntilThenHasSomething() {
        List<AssistantReply.Action> actions = chain(timer(1), condition(1, 0));
        assertFalse("an ELSE with no IF path cannot be stored, so it is not offered",
                RoutineBranch.canAddTo(actions, 1, RoutineBranch.BRANCH_ELSE));
        assertFalse(RoutineBranch.addToPath(actions, 1, RoutineBranch.BRANCH_ELSE, timer(21)));
        assertTrue(RoutineBranch.canAddTo(actions, 1, RoutineBranch.BRANCH_TRUE));
    }

    @Test public void aPathStopsAcceptingActionsAtItsLimit() {
        List<AssistantReply.Action> actions = chain(condition(1, 0), timer(11));
        for (int i = 1; i < RoutineBranch.MAX_BRANCH_STEPS; i++) {
            assertTrue(RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_TRUE, timer(11 + i)));
        }
        assertEquals(RoutineBranch.MAX_BRANCH_STEPS, thenPath(actions, 0).size());
        assertFalse(RoutineBranch.canAddTo(actions, 0, RoutineBranch.BRANCH_TRUE));
        assertFalse(RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_TRUE, timer(99)));
    }

    @Test public void anOrdinaryStepIsAppendedOutsideTheBranch() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21));
        assertTrue(RoutineBranch.addStep(actions, timer(90)));
        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(21), otherwisePath(actions, 0));
        assertEquals(list(90), tail(actions));
    }

    // ---- removing -----------------------------------------------------------------------------

    @Test public void removingTheLastOtherwiseActionReturnsToNone() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21), timer(90));
        assertTrue(RoutineBranch.removeStep(actions, 2));

        assertTrue("OTHERWISE reads None again", otherwisePath(actions, 0).isEmpty());
        assertFalse("and nothing is stored for it", RoutineBranch.hasElse(actions.get(0)));
        assertFalse(actions.get(0).params.has(RoutineBranch.KEY_ELSE_STEPS));
        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(90), tail(actions));
    }

    @Test public void removingOneOfSeveralOtherwiseActionsShrinksOnlyThatPath() {
        List<AssistantReply.Action> actions = chain(
                condition(1, 2), timer(11), timer(21), timer(22), timer(90));
        assertTrue(RoutineBranch.removeStep(actions, 2));
        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(22), otherwisePath(actions, 0));
        assertEquals(list(90), tail(actions));
    }

    @Test public void removingOneOfSeveralThenActionsShrinksOnlyThatPath() {
        List<AssistantReply.Action> actions = chain(
                condition(2, 1), timer(11), timer(12), timer(21), timer(90));
        assertTrue(RoutineBranch.removeStep(actions, 1));
        assertEquals(list(12), thenPath(actions, 0));
        assertEquals(list(21), otherwisePath(actions, 0));
        assertEquals(list(90), tail(actions));
    }

    @Test public void emptyingTheThenPathIsRefused() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21), timer(90));
        assertFalse("whatever followed would silently become the THEN path",
                RoutineBranch.removeStep(actions, 1));
        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(21), otherwisePath(actions, 0));
    }

    @Test public void removingAConditionThroughRemoveStepIsRefused() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21));
        assertFalse(RoutineBranch.removeStep(actions, 0));
        assertEquals(3, actions.size());
    }

    @Test public void removingABranchTakesBothPathsWithIt() {
        List<AssistantReply.Action> actions = chain(
                timer(1), condition(1, 1), timer(11), timer(21), timer(90));
        assertTrue(RoutineBranch.removeBranch(actions, 1));
        assertEquals(2, actions.size());
        assertEquals(1, actions.get(0).params.optInt("seconds"));
        assertEquals("only the steps outside the branch survive",
                90, actions.get(1).params.optInt("seconds"));
    }

    // ---- reordering ---------------------------------------------------------------------------

    @Test public void reorderingWithinThenStaysWithinThen() {
        List<AssistantReply.Action> actions = chain(
                condition(2, 1), timer(11), timer(12), timer(21), timer(90));
        assertTrue(RoutineBranch.move(actions, 2, -1));
        assertEquals(list(12, 11), thenPath(actions, 0));
        assertEquals(list(21), otherwisePath(actions, 0));
        assertEquals(list(90), tail(actions));
    }

    @Test public void reorderingWithinOtherwiseStaysWithinOtherwise() {
        List<AssistantReply.Action> actions = chain(
                condition(1, 2), timer(11), timer(21), timer(22), timer(90));
        assertTrue(RoutineBranch.move(actions, 3, -1));
        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(22, 21), otherwisePath(actions, 0));
        assertEquals(list(90), tail(actions));
    }

    @Test public void anActionCannotBeMovedOutOfItsOwnPath() {
        List<AssistantReply.Action> actions = chain(
                condition(1, 1), timer(11), timer(21), timer(90));
        assertFalse("the last THEN action cannot fall into OTHERWISE",
                RoutineBranch.canMove(actions, 1, 1));
        assertFalse("the first OTHERWISE action cannot climb into THEN",
                RoutineBranch.canMove(actions, 2, -1));
        assertFalse("nor can it escape past the end of the branch",
                RoutineBranch.canMove(actions, 2, 1));
        assertFalse(RoutineBranch.move(actions, 1, 1));
        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(21), otherwisePath(actions, 0));
    }

    @Test public void anOrdinaryStepStepsOverAWholeBranchRatherThanIntoIt() {
        List<AssistantReply.Action> actions = chain(
                condition(1, 1), timer(11), timer(21), timer(90));
        assertTrue(RoutineBranch.move(actions, 3, -1));

        assertEquals("the branch is intact and now second", 0,
                RoutineBranch.units(actions).get(0).start);
        assertFalse(RoutineBranch.units(actions).get(0).branch);
        assertEquals(90, actions.get(0).params.optInt("seconds"));
        assertEquals(list(11), thenPath(actions, 1));
        assertEquals(list(21), otherwisePath(actions, 1));
        assertTrue(RoutineBranch.structureValid(actions));
    }

    @Test public void movingABranchCarriesBothPathsWithIt() {
        List<AssistantReply.Action> actions = chain(
                timer(1), condition(1, 1), timer(11), timer(21));
        assertTrue(RoutineBranch.move(actions, 1, -1));

        assertTrue("the branch is now first", RoutineBranch.units(actions).get(0).branch);
        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(21), otherwisePath(actions, 0));
        assertEquals(1, actions.get(3).params.optInt("seconds"));
        assertTrue(RoutineBranch.structureValid(actions));
    }

    @Test public void twoBranchesCanSwapWithoutTangling() {
        List<AssistantReply.Action> actions = chain(
                condition(1, 1), timer(11), timer(21),
                condition(1, 1), timer(31), timer(41));
        assertTrue(RoutineBranch.move(actions, 0, 1));

        assertEquals(list(31), thenPath(actions, 0));
        assertEquals(list(41), otherwisePath(actions, 0));
        assertEquals(list(11), thenPath(actions, 3));
        assertEquals(list(21), otherwisePath(actions, 3));
        assertTrue(RoutineBranch.structureValid(actions));
    }

    @Test public void thereIsNowhereToMoveAtTheEnds() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21));
        assertFalse(RoutineBranch.canMove(actions, 0, -1));
        assertFalse(RoutineBranch.canMove(actions, 0, 1));
        assertFalse(RoutineBranch.canMove(actions, 1, -1));
        assertFalse(RoutineBranch.canMove(actions, 2, 1));
    }

    // ---- duplicating ----------------------------------------------------------------------------

    @Test public void duplicatingAPathActionKeepsItOnThatPath() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21), timer(90));
        assertTrue(RoutineBranch.duplicateStep(actions, 2));
        assertEquals(list(11), thenPath(actions, 0));
        assertEquals(list(21, 21), otherwisePath(actions, 0));
        assertEquals(list(90), tail(actions));
    }

    @Test public void aConditionIsNotDuplicated() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21));
        assertFalse("copying a condition alone would leave a branch pointing at nothing",
                RoutineBranch.duplicateStep(actions, 0));
        assertEquals(3, actions.size());
    }

    // ---- the shape can never go wrong -----------------------------------------------------------

    @Test public void everyEditLeavesTheStructureValid() {
        List<AssistantReply.Action> actions = chain(condition(1, 0), timer(11));
        assertTrue(RoutineBranch.structureValid(actions));

        RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_TRUE, timer(12));
        assertTrue(RoutineBranch.structureValid(actions));
        RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_ELSE, timer(21));
        assertTrue(RoutineBranch.structureValid(actions));
        RoutineBranch.addStep(actions, timer(90));
        assertTrue(RoutineBranch.structureValid(actions));
        RoutineBranch.move(actions, 1, 1);
        assertTrue(RoutineBranch.structureValid(actions));
        RoutineBranch.duplicateStep(actions, 3);
        assertTrue(RoutineBranch.structureValid(actions));
        RoutineBranch.removeStep(actions, 3);
        assertTrue(RoutineBranch.structureValid(actions));
        RoutineBranch.move(actions, 5, -1);
        assertTrue(RoutineBranch.structureValid(actions));
        RoutineBranch.removeBranch(actions, RoutineBranch.units(actions).get(1).start);
        assertTrue(RoutineBranch.structureValid(actions));
    }

    @Test public void declaredCountsAlwaysMatchTheActionsPresent() {
        List<AssistantReply.Action> actions = chain(
                condition(1, 1), timer(11), timer(21), timer(90));
        RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_TRUE, timer(12));
        RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_ELSE, timer(22));
        RoutineBranch.removeStep(actions, 1);

        RoutineBranch.Span span = RoutineBranch.spanAt(actions, 0);
        assertEquals(span.trueEnd - span.trueStart, RoutineBranch.trueSteps(actions.get(0)));
        assertEquals(span.elseEnd - span.elseStart, RoutineBranch.elseSteps(actions.get(0)));
    }

    // ---- persistence ------------------------------------------------------------------------------

    @Test public void authoredPathsSurviveSaveAndReload() {
        List<AssistantReply.Action> actions = chain(condition(1, 0), timer(11));
        RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_TRUE, timer(12));
        RoutineBranch.addToPath(actions, 0, RoutineBranch.BRANCH_ELSE, timer(21));
        RoutineBranch.addStep(actions, timer(90));

        RoutineStore.Routine routine = RoutineStore.create("Authored", actions);
        assertTrue(RoutineStore.upsert(context, routine));
        RoutineStore.Routine loaded = RoutineStore.findById(context, routine.id);
        assertNotNull(loaded);
        assertEquals(list(11, 12), thenPath(loaded.actions, 0));
        assertEquals(list(21), otherwisePath(loaded.actions, 0));
        assertEquals(list(90), tail(loaded.actions));
    }

    @Test public void aBranchThatLostItsOtherwiseIsStoredAsAPlainIfAgain() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(11), timer(21));
        RoutineBranch.removeStep(actions, 2);
        RoutineStore.Routine routine = RoutineStore.create("Back to plain", actions);
        assertTrue(RoutineStore.upsert(context, routine));

        String raw = context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE)
                .getString("routines_v1", "[]");
        assertFalse("nothing may be left behind for a path with no actions",
                raw.contains(RoutineBranch.KEY_ELSE_STEPS));
    }

    @Test public void backupAndRestoreKeepBothPaths() {
        List<AssistantReply.Action> actions = chain(
                condition(2, 1), timer(11), timer(12), timer(21), timer(90));
        RoutineStore.Routine routine = RoutineStore.create("Backed up", actions);
        assertTrue(RoutineStore.upsert(context, routine));

        String backup = RoutineStore.backupJson(context);
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
        assertTrue(RoutineStore.restoreBackupJson(context, backup));

        RoutineStore.Routine restored = RoutineStore.findById(context, routine.id);
        assertNotNull(restored);
        assertEquals(list(11, 12), thenPath(restored.actions, 0));
        assertEquals(list(21), otherwisePath(restored.actions, 0));
        assertEquals(list(90), tail(restored.actions));
    }

    @Test public void aLegacyRoutineStillLoadsAndReadsAsThenOnly() {
        String legacy = "[{\"id\":\"legacy-1\",\"name\":\"Bedtime\",\"createdAt\":1,\"updatedAt\":2,"
                + "\"lastRunAt\":0,\"actions\":["
                + "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"time\",\"startMinute\":1080,"
                + "\"endMinute\":1320,\"nextSteps\":2},\"requiresConfirmation\":false},"
                + "{\"type\":\"SET_TIMER\",\"params\":{\"seconds\":11},\"requiresConfirmation\":false},"
                + "{\"type\":\"SET_TIMER\",\"params\":{\"seconds\":12},\"requiresConfirmation\":false},"
                + "{\"type\":\"SET_TIMER\",\"params\":{\"seconds\":90},\"requiresConfirmation\":false}]}]";
        assertTrue(RoutineStore.restoreBackupJson(context, legacy));

        RoutineStore.Routine loaded = RoutineStore.findById(context, "legacy-1");
        assertNotNull(loaded);
        assertEquals(list(11, 12), thenPath(loaded.actions, 0));
        assertTrue(otherwisePath(loaded.actions, 0).isEmpty());
        assertEquals(list(90), tail(loaded.actions));
    }

    // ---- Create with Orbit ------------------------------------------------------------------------

    @Test public void aDraftedOtherwiseOpensGroupedIntoTheTwoPaths() {
        RoutineDraft draft = RoutineDraft.fromPlannerJson(context,
                "{\"name\":\"Home or away\",\"steps\":["
                        + "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"time\","
                        + "\"startMinute\":1080,\"endMinute\":1320,\"nextSteps\":1,\"elseSteps\":1}},"
                        + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":true}},"
                        + "{\"type\":\"SET_DND\",\"params\":{\"enabled\":false}}]}");
        assertNotNull(draft);
        List<RoutineBranch.Unit> units = RoutineBranch.units(draft.actions);
        assertEquals("the whole draft reads as one branch", 1, units.size());
        assertTrue(units.get(0).branch);

        RoutineBranch.Span span = RoutineBranch.spanAt(draft.actions, 0);
        assertEquals(1, span.trueEnd - span.trueStart);
        assertEquals(1, span.elseEnd - span.elseStart);
        assertTrue(draft.actions.get(span.trueStart).params.optBoolean("enabled"));
        assertFalse(draft.actions.get(span.elseStart).params.optBoolean("enabled"));
    }

    // ---- execution is untouched --------------------------------------------------------------------

    @Test public void authoringThroughTheEditorProducesTheSameExecutionAsBefore() throws Exception {
        List<AssistantReply.Action> authored = chain(condition(1, 0), timer(11));
        RoutineBranch.addToPath(authored, 0, RoutineBranch.BRANCH_ELSE, timer(21));
        RoutineBranch.addStep(authored, timer(90));

        // Byte for byte what v0.7.5.0 would have stored for the same branch.
        JSONObject expected = new JSONObject(condition(1, 1).params.toString());
        assertEquals(expected.optInt("nextSteps"),
                authored.get(0).params.optInt("nextSteps"));
        assertEquals(expected.optInt(RoutineBranch.KEY_ELSE_STEPS),
                authored.get(0).params.optInt(RoutineBranch.KEY_ELSE_STEPS));

        RoutineBranch.Flow flow = RoutineBranch.flow(authored);
        assertEquals("finishing the THEN action steps over OTHERWISE", 3, flow.nextAfter(1));
        boolean[] whenTrue = RoutineBranch.skippedSteps(authored, (i, a) -> Boolean.TRUE);
        assertFalse(whenTrue[1]);
        assertTrue(whenTrue[2]);
        boolean[] whenFalse = RoutineBranch.skippedSteps(authored, (i, a) -> Boolean.FALSE);
        assertTrue(whenFalse[1]);
        assertFalse(whenFalse[2]);
    }
}
