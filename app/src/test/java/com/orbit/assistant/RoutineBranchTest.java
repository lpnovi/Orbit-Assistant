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

import java.util.ArrayList;
import java.util.List;

/**
 * The IF / ELSE branch model: its geometry, what counts as malformed, and how it persists.
 *
 * <p>The compatibility tests matter most. A Routine saved before v0.7.5.0 has no {@code elseSteps}
 * anywhere, and every one of these has to keep proving that such a Routine describes exactly the
 * execution it described in v0.7.4.2.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineBranchTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
    }

    // ---- builders ---------------------------------------------------------------------------

    /** An always-true time condition: equal endpoints mean all day, as they always have. */
    static AssistantReply.Action condition(int trueSteps, int elseSteps) {
        try {
            JSONObject params = new JSONObject()
                    .put("mode", RoutineConditionEvaluator.MODE_TIME)
                    .put("startMinute", 0)
                    .put("endMinute", 0)
                    .put("nextSteps", trueSteps);
            if (elseSteps > 0) params.put(RoutineBranch.KEY_ELSE_STEPS, elseSteps);
            return new AssistantReply.Action(RoutineActionCatalog.IF_CONDITION, params, false);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** A condition whose outcome is fixed, exactly as an automatic run locks it before executing. */
    static AssistantReply.Action lockedCondition(boolean matched, int trueSteps, int elseSteps) {
        AssistantReply.Action base = condition(trueSteps, elseSteps);
        try {
            base.params.put("_orbitLockedMatch", matched);
            base.params.put("_orbitLockedMessage",
                    matched ? "Condition matched" : "Time condition not met");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return base;
    }

    /** A distinguishable executable step: the timer length identifies which step ran. */
    static AssistantReply.Action timer(int seconds) {
        try {
            return new AssistantReply.Action(RoutineActionCatalog.SET_TIMER,
                    new JSONObject().put("seconds", seconds), false);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static List<AssistantReply.Action> chain(AssistantReply.Action... actions) {
        List<AssistantReply.Action> out = new ArrayList<>();
        for (AssistantReply.Action action : actions) out.add(action);
        return out;
    }

    // ---- reading a condition ------------------------------------------------------------------

    @Test public void aConditionWithoutElseReportsNoElsePath() {
        AssistantReply.Action ifOnly = condition(2, 0);
        assertEquals(2, RoutineBranch.trueSteps(ifOnly));
        assertEquals(0, RoutineBranch.elseSteps(ifOnly));
        assertFalse(RoutineBranch.hasElse(ifOnly));
        assertFalse("nothing may be written for a condition with no ELSE",
                ifOnly.params.has(RoutineBranch.KEY_ELSE_STEPS));
    }

    @Test public void aConditionWithElseReportsBothPaths() {
        AssistantReply.Action branching = condition(2, 3);
        assertEquals(2, RoutineBranch.trueSteps(branching));
        assertEquals(3, RoutineBranch.elseSteps(branching));
        assertTrue(RoutineBranch.hasElse(branching));
    }

    @Test public void branchCountsAreClampedToTheSupportedRange() {
        assertEquals(RoutineBranch.MAX_BRANCH_STEPS, RoutineBranch.elseSteps(condition(1, 99)));
        assertEquals(0, RoutineBranch.elseSteps(condition(1, -4)));
        assertEquals(0, RoutineBranch.elseSteps(timer(60)));
        assertEquals(0, RoutineBranch.trueSteps(timer(60)));
    }

    // ---- geometry ------------------------------------------------------------------------------

    @Test public void spanNamesWhereEachPathStartsAndEnds() {
        List<AssistantReply.Action> actions = chain(
                condition(2, 1), timer(1), timer(2), timer(3), timer(4));
        RoutineBranch.Span span = RoutineBranch.spanAt(actions, 0);
        assertNotNull(span);
        assertEquals(1, span.trueStart);
        assertEquals(3, span.trueEnd);
        assertEquals(3, span.elseStart);
        assertEquals(4, span.elseEnd);
        assertEquals("the routine continues after the whole branch", 4, span.continueAt());
        assertTrue(span.hasTruePath());
        assertTrue(span.hasElsePath());
    }

    @Test public void spanIsNullForAStepThatIsNotACondition() {
        assertNull(RoutineBranch.spanAt(chain(timer(1)), 0));
        assertNull(RoutineBranch.spanAt(chain(timer(1)), 7));
        assertNull(RoutineBranch.spanAt(null, 0));
    }

    @Test public void spansAreClampedToTheRoutineTheyBelongTo() {
        // The long-standing tolerance for an IF that gates more steps than exist.
        List<AssistantReply.Action> actions = chain(condition(5, 0), timer(1));
        RoutineBranch.Span span = RoutineBranch.spanAt(actions, 0);
        assertEquals(1, span.trueStart);
        assertEquals(2, span.trueEnd);
        assertEquals(2, span.elseEnd);
        assertFalse(span.hasElsePath());
    }

    @Test public void branchMapLabelsEveryStep() {
        List<AssistantReply.Action> actions = chain(
                condition(2, 2), timer(1), timer(2), timer(3), timer(4), timer(5));
        int[] map = RoutineBranch.branchMap(actions);
        assertEquals(RoutineBranch.BRANCH_NONE, map[0]);
        assertEquals(RoutineBranch.BRANCH_TRUE, map[1]);
        assertEquals(RoutineBranch.BRANCH_TRUE, map[2]);
        assertEquals(RoutineBranch.BRANCH_ELSE, map[3]);
        assertEquals(RoutineBranch.BRANCH_ELSE, map[4]);
        assertEquals("the step after the branch always runs", RoutineBranch.BRANCH_NONE, map[5]);

        int[] owners = RoutineBranch.branchOwners(actions);
        assertEquals(0, owners[1]);
        assertEquals(0, owners[4]);
        assertEquals(-1, owners[5]);
    }

    @Test public void gatedStepsAreLabelledEvenWithoutAnElse() {
        List<AssistantReply.Action> actions = chain(condition(1, 0), timer(1), timer(2));
        int[] map = RoutineBranch.branchMap(actions);
        assertEquals(RoutineBranch.BRANCH_TRUE, map[1]);
        assertEquals(RoutineBranch.BRANCH_NONE, map[2]);
    }

    // ---- validation ----------------------------------------------------------------------------

    @Test public void aWellFormedBranchIsValid() {
        assertTrue(RoutineBranch.structureValid(
                chain(condition(1, 1), timer(1), timer(2))));
        assertTrue(RoutineBranch.structureValid(
                chain(condition(2, 2), timer(1), timer(2), timer(3), timer(4), timer(5))));
    }

    @Test public void anElseThatRunsPastTheLastStepIsMalformed() {
        String problem = RoutineBranch.structureProblem(
                chain(condition(2, 2), timer(1), timer(2), timer(3)));
        assertFalse(problem.isEmpty());
        assertTrue("the message has to name the step", problem.contains("Step 1"));
    }

    @Test public void aConditionInsideABranchIsMalformed() {
        String problem = RoutineBranch.structureProblem(
                chain(condition(2, 1), timer(1), condition(1, 0), timer(2), timer(3)));
        assertFalse(problem.isEmpty());
        assertTrue(problem.contains("nested"));
    }

    @Test public void aBranchingConditionInsideAnotherConditionsGateIsMalformed() {
        String problem = RoutineBranch.structureProblem(
                chain(condition(3, 0), timer(1), condition(1, 1), timer(2), timer(3)));
        assertFalse(problem.isEmpty());
        assertTrue(problem.contains("nested"));
    }

    @Test public void twoBranchesSideBySideAreFine() {
        assertTrue(RoutineBranch.structureValid(chain(
                condition(1, 1), timer(1), timer(2),
                condition(1, 1), timer(3), timer(4))));
    }

    /** The compatibility guarantee: an ELSE-free routine can never fail the new structure rules. */
    @Test public void oldStyleRoutinesAreNeverCalledMalformed() {
        assertTrue(RoutineBranch.structureValid(chain(condition(5, 0), timer(1))));
        assertTrue(RoutineBranch.structureValid(
                chain(condition(2, 0), timer(1), condition(2, 0), timer(2))));
        assertTrue(RoutineBranch.structureValid(chain(timer(1), timer(2))));
        assertTrue(RoutineBranch.structureValid(new ArrayList<>()));
        assertTrue(RoutineBranch.structureValid(null));
    }

    // ---- execution flow ------------------------------------------------------------------------

    @Test public void flowStepsOverTheElsePathAfterTheIfPath() {
        List<AssistantReply.Action> actions = chain(
                condition(2, 2), timer(1), timer(2), timer(3), timer(4), timer(5));
        RoutineBranch.Flow flow = RoutineBranch.flow(actions);
        assertEquals(1, flow.nextAfter(0));
        assertEquals(2, flow.nextAfter(1));
        assertEquals("the last IF step jumps past the whole ELSE path", 5, flow.nextAfter(2));
        assertEquals(3, flow.skipFromAfter(2));
        assertEquals(5, flow.skipToAfter(2));
        assertEquals(6, flow.nextAfter(5));
    }

    @Test public void flowIsPlainSequentialWithoutAnElse() {
        List<AssistantReply.Action> actions = chain(condition(2, 0), timer(1), timer(2), timer(3));
        RoutineBranch.Flow flow = RoutineBranch.flow(actions);
        for (int i = 0; i < actions.size(); i++) {
            assertEquals(i + 1, flow.nextAfter(i));
            assertEquals("nothing may be reported as skipped", 0, flow.skipToAfter(i));
        }
    }

    // ---- which steps a run will step over --------------------------------------------------------

    @Test public void aTrueConditionSkipsTheElsePath() {
        List<AssistantReply.Action> actions = chain(
                condition(1, 2), timer(1), timer(2), timer(3), timer(4));
        boolean[] skipped = RoutineBranch.skippedSteps(actions, (i, a) -> Boolean.TRUE);
        assertFalse(skipped[1]);
        assertTrue(skipped[2]);
        assertTrue(skipped[3]);
        assertFalse("the rest of the routine always runs", skipped[4]);
    }

    @Test public void aFalseConditionSkipsTheIfPath() {
        List<AssistantReply.Action> actions = chain(
                condition(1, 2), timer(1), timer(2), timer(3), timer(4));
        boolean[] skipped = RoutineBranch.skippedSteps(actions, (i, a) -> Boolean.FALSE);
        assertTrue(skipped[1]);
        assertFalse(skipped[2]);
        assertFalse(skipped[3]);
        assertFalse(skipped[4]);
    }

    @Test public void anUndecidableConditionSkipsNothing() {
        List<AssistantReply.Action> actions = chain(condition(1, 1), timer(1), timer(2));
        boolean[] skipped = RoutineBranch.skippedSteps(actions, (i, a) -> null);
        assertFalse(skipped[1]);
        assertFalse(skipped[2]);
    }

    @Test public void aFalseConditionWithoutElseSkipsExactlyTheGatedSteps() {
        List<AssistantReply.Action> actions = chain(
                condition(2, 0), timer(1), timer(2), timer(3));
        boolean[] skipped = RoutineBranch.skippedSteps(actions, (i, a) -> Boolean.FALSE);
        assertTrue(skipped[1]);
        assertTrue(skipped[2]);
        assertFalse(skipped[3]);
    }

    // ---- catalog -------------------------------------------------------------------------------

    @Test public void theCatalogAcceptsAnElseInRangeAndRejectsOneOutOfIt() throws Exception {
        assertTrue(RoutineActionCatalog.isValid(condition(1, 5)));
        AssistantReply.Action tooMany = condition(1, 0);
        tooMany.params.put(RoutineBranch.KEY_ELSE_STEPS, 6);
        assertFalse(RoutineActionCatalog.isValid(tooMany));
        AssistantReply.Action negative = condition(1, 0);
        negative.params.put(RoutineBranch.KEY_ELSE_STEPS, -1);
        assertFalse(RoutineActionCatalog.isValid(negative));
    }

    @Test public void theSummaryExplainsTheBranchWithoutChangingTheOldWording() {
        assertEquals("Runs the next step only during the saved time window",
                RoutineActionCatalog.summary(condition(1, 0)));
        String branching = RoutineActionCatalog.summary(condition(1, 2));
        assertTrue(branching, branching.contains("otherwise"));
        assertTrue(branching, branching.contains("continues the routine"));
        assertTrue(RoutineActionCatalog.title(condition(1, 2)).contains("with ELSE"));
        assertFalse(RoutineActionCatalog.title(condition(1, 0)).contains("ELSE"));
    }

    // ---- persistence ----------------------------------------------------------------------------

    @Test public void aBranchingRoutineSurvivesSaveAndReload() {
        List<AssistantReply.Action> actions = chain(
                condition(1, 1), timer(11), timer(22), timer(33));
        RoutineStore.Routine routine = RoutineStore.create("Branching", actions);
        assertTrue(RoutineStore.upsert(context, routine));

        RoutineStore.Routine loaded = RoutineStore.findById(context, routine.id);
        assertNotNull(loaded);
        assertEquals(4, loaded.actions.size());
        assertEquals(1, RoutineBranch.elseSteps(loaded.actions.get(0)));
        assertEquals(1, RoutineBranch.trueSteps(loaded.actions.get(0)));
        assertTrue(RoutineBranch.structureValid(loaded.actions));
    }

    @Test public void aRoutineWithNoElseIsStoredWithoutTheNewKey() {
        RoutineStore.Routine routine = RoutineStore.create("Plain",
                chain(condition(1, 0), timer(5)));
        assertTrue(RoutineStore.upsert(context, routine));
        String raw = context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE)
                .getString("routines_v1", "[]");
        assertFalse("stored data must stay byte-identical to v0.7.4.2 until an ELSE is added",
                raw.contains(RoutineBranch.KEY_ELSE_STEPS));
    }

    @Test public void aMalformedBranchIsRefusedBeforeItIsWritten() {
        // Declares one ELSE step but the routine ends first.
        RoutineStore.Routine routine = RoutineStore.create("Broken",
                chain(condition(1, 1), timer(1)));
        assertFalse(RoutineStore.upsert(context, routine));
        assertTrue(RoutineStore.list(context).isEmpty());
    }

    /** A routine written by an earlier release loads and behaves exactly as it did. */
    @Test public void aV0742RoutineMigratesUntouched() {
        String legacy = "[{\"id\":\"legacy-1\",\"name\":\"Bedtime\",\"createdAt\":1,\"updatedAt\":2,"
                + "\"lastRunAt\":0,\"actions\":["
                + "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"time\",\"startMinute\":1080,"
                + "\"endMinute\":1320,\"nextSteps\":2},\"requiresConfirmation\":false},"
                + "{\"type\":\"SET_TIMER\",\"params\":{\"seconds\":60},\"requiresConfirmation\":false},"
                + "{\"type\":\"SET_TIMER\",\"params\":{\"seconds\":120},\"requiresConfirmation\":false},"
                + "{\"type\":\"SET_TIMER\",\"params\":{\"seconds\":180},\"requiresConfirmation\":false}]}]";
        assertTrue(RoutineStore.restoreBackupJson(context, legacy));

        RoutineStore.Routine loaded = RoutineStore.findById(context, "legacy-1");
        assertNotNull("an existing routine must still load", loaded);
        assertEquals(4, loaded.actions.size());
        assertEquals(2, RoutineBranch.trueSteps(loaded.actions.get(0)));
        assertEquals("no ELSE is invented for it", 0, RoutineBranch.elseSteps(loaded.actions.get(0)));
        assertFalse(RoutineBranch.hasElse(loaded.actions.get(0)));

        RoutineBranch.Span span = RoutineBranch.spanAt(loaded.actions, 0);
        assertEquals(1, span.trueStart);
        assertEquals(3, span.trueEnd);
        assertFalse(span.hasElsePath());
        assertEquals("continuation is unchanged from v0.7.4.2", 3, span.continueAt());
    }

    @Test public void backupKeepsBranchInformationAndRestoresIt() {
        RoutineStore.Routine routine = RoutineStore.create("Home or away",
                chain(condition(1, 2), timer(1), timer(2), timer(3)));
        assertTrue(RoutineStore.upsert(context, routine));

        String backup = RoutineStore.backupJson(context);
        assertTrue(backup.contains(RoutineBranch.KEY_ELSE_STEPS));

        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
        assertTrue(RoutineStore.list(context).isEmpty());
        assertTrue(RoutineStore.restoreBackupJson(context, backup));

        RoutineStore.Routine restored = RoutineStore.findById(context, routine.id);
        assertNotNull(restored);
        assertEquals(2, RoutineBranch.elseSteps(restored.actions.get(0)));
        assertEquals(4, restored.actions.size());
    }

    @Test public void anOldBackupWithNoBranchesStillRestores() {
        String legacy = "[{\"id\":\"old-1\",\"name\":\"Focus\",\"createdAt\":1,\"updatedAt\":1,"
                + "\"lastRunAt\":0,\"actions\":["
                + "{\"type\":\"SET_VOLUME\",\"params\":{\"percent\":20},\"requiresConfirmation\":false}]}]";
        assertTrue(RoutineStore.restoreBackupJson(context, legacy));
        RoutineStore.Routine restored = RoutineStore.findById(context, "old-1");
        assertNotNull(restored);
        assertEquals(1, restored.actions.size());
    }

    @Test public void aTamperedBranchIsRejectedRatherThanHalfExecuted() {
        String tampered = "[{\"id\":\"bad-1\",\"name\":\"Tampered\",\"createdAt\":1,\"updatedAt\":1,"
                + "\"lastRunAt\":0,\"actions\":["
                + "{\"type\":\"IF_CONDITION\",\"params\":{\"mode\":\"time\",\"startMinute\":0,"
                + "\"endMinute\":0,\"nextSteps\":2,\"elseSteps\":3},\"requiresConfirmation\":false},"
                + "{\"type\":\"SET_TIMER\",\"params\":{\"seconds\":60},\"requiresConfirmation\":false}]}]";
        assertTrue(RoutineStore.restoreBackupJson(context, tampered));
        assertNull("a branch that cannot fit its own steps must not load",
                RoutineStore.findById(context, "bad-1"));
    }
}
