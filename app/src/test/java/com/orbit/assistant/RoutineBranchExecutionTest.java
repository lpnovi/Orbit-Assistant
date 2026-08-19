package com.orbit.assistant;

import static com.orbit.assistant.RoutineBranchTest.chain;
import static com.orbit.assistant.RoutineBranchTest.condition;
import static com.orbit.assistant.RoutineBranchTest.lockedCondition;
import static com.orbit.assistant.RoutineBranchTest.timer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.AlarmClock;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Branch execution, driven through the real {@link OrbitActionEngine}.
 *
 * <p>These do not model the runner; they run it. Every executable step is a timer with its own
 * length, and the test reads the intents Android was actually asked to start, so "the unchosen
 * branch never executes" is proven by the absence of its side effect rather than by the engine's
 * own report of what it did. The reported step results are checked separately, because run history
 * and the result cards are built from those.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineBranchExecutionTest {

    private Context context;
    private final List<String> messages = new ArrayList<>();
    private boolean finished;
    private boolean completedAll;
    private int completedSteps;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        messages.clear();
        finished = false;
        completedAll = false;
        completedSteps = 0;
        installClockApp();
        drainStartedActivities();
    }

    /**
     * A clock app for the timer steps to reach. {@code DeviceActionExecutor} refuses to start an
     * intent nothing can handle, so without one every probe step would fail for the wrong reason.
     */
    private void installClockApp() {
        ComponentName clock = new ComponentName("com.orbit.test.clock", "com.orbit.test.clock.Timer");
        ShadowPackageManager packages = Shadows.shadowOf(context.getPackageManager());
        packages.addActivityIfNotPresent(clock);
        IntentFilter filter = new IntentFilter(AlarmClock.ACTION_SET_TIMER);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(clock, filter);
    }

    // ---- harness ----------------------------------------------------------------------------

    /** Timer lengths Android was actually asked to start, in order. */
    private List<Integer> run(List<AssistantReply.Action> actions) {
        OrbitActionEngine.execute(context, actions, null, new OrbitActionEngine.Listener() {
            @Override public void onStep(AssistantReply.Action action,
                                         DeviceActionExecutor.Result result, int index, int total) {
                messages.add(index + ":" + (result == null ? "" : result.message));
            }
            @Override public void onFinished(boolean all, int completed, int total) {
                finished = true;
                completedAll = all;
                completedSteps = completed;
            }
        });
        return drainStartedActivities();
    }

    private List<Integer> drainStartedActivities() {
        List<Integer> lengths = new ArrayList<>();
        ShadowApplication shadow = Shadows.shadowOf((Application) RuntimeEnvironment.getApplication());
        Intent started;
        while ((started = shadow.getNextStartedActivity()) != null) {
            if (AlarmClock.ACTION_SET_TIMER.equals(started.getAction())) {
                lengths.add(started.getIntExtra(AlarmClock.EXTRA_LENGTH, -1));
            }
        }
        // The shadow hands back the most recent launch first; these assertions are about the order
        // the routine ran in.
        java.util.Collections.reverse(lengths);
        return lengths;
    }

    private String messageAt(int index) {
        for (String message : messages) {
            if (message.startsWith(index + ":")) return message.substring((index + ":").length());
        }
        return "";
    }

    /** A time window that deliberately excludes right now, so the condition is genuinely false. */
    private static AssistantReply.Action liveFalseCondition(int trueSteps, int elseSteps) {
        Calendar now = Calendar.getInstance();
        int minuteNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int start = (minuteNow + 20) % 1440;
        int end = (start + 30) % 1440;
        try {
            JSONObject params = new JSONObject()
                    .put("mode", RoutineConditionEvaluator.MODE_TIME)
                    .put("startMinute", start)
                    .put("endMinute", end)
                    .put("nextSteps", trueSteps);
            if (elseSteps > 0) params.put(RoutineBranch.KEY_ELSE_STEPS, elseSteps);
            return new AssistantReply.Action(RoutineActionCatalog.IF_CONDITION, params, false);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---- the four cases the release is about --------------------------------------------------

    @Test public void aTrueConditionRunsOnlyTheIfPath() {
        List<Integer> ran = run(chain(
                lockedCondition(true, 2, 1), timer(11), timer(12), timer(21)));
        assertEquals(java.util.Arrays.asList(11, 12), ran);
        assertTrue(finished);
        assertTrue(completedAll);
    }

    @Test public void aFalseConditionRunsOnlyTheElsePath() {
        List<Integer> ran = run(chain(
                lockedCondition(false, 2, 1), timer(11), timer(12), timer(21)));
        assertEquals(java.util.Arrays.asList(21), ran);
        assertTrue(completedAll);
    }

    @Test public void aFalseConditionWithNoElseBehavesExactlyAsBefore() {
        List<Integer> ran = run(chain(
                lockedCondition(false, 2, 0), timer(11), timer(12), timer(99)));
        assertEquals("the gated steps are skipped and the routine continues",
                java.util.Arrays.asList(99), ran);
        assertEquals("Time condition not met · skipped 2 steps", messageAt(0));
        assertEquals("Skipped · condition not met", messageAt(1));
        assertEquals("Skipped · condition not met", messageAt(2));
        assertTrue(completedAll);
    }

    @Test public void aTrueConditionWithNoElseBehavesExactlyAsBefore() {
        List<Integer> ran = run(chain(
                lockedCondition(true, 1, 0), timer(11), timer(99)));
        assertEquals(java.util.Arrays.asList(11, 99), ran);
        assertEquals("Condition matched", messageAt(0));
    }

    @Test public void bothPathsContinueIntoTheRestOfTheRoutine() {
        assertEquals(java.util.Arrays.asList(11, 90, 91),
                run(chain(lockedCondition(true, 1, 1), timer(11), timer(22), timer(90), timer(91))));

        setUp();
        assertEquals(java.util.Arrays.asList(22, 90, 91),
                run(chain(lockedCondition(false, 1, 1), timer(11), timer(22), timer(90), timer(91))));
    }

    @Test public void exactlyOneBranchEverExecutes() {
        for (int trueSteps = 1; trueSteps <= 3; trueSteps++) {
            for (int elseSteps = 1; elseSteps <= 3; elseSteps++) {
                for (boolean matched : new boolean[]{true, false}) {
                    setUp();
                    List<AssistantReply.Action> actions = new ArrayList<>();
                    actions.add(lockedCondition(matched, trueSteps, elseSteps));
                    for (int i = 0; i < trueSteps; i++) actions.add(timer(100 + i));
                    for (int i = 0; i < elseSteps; i++) actions.add(timer(200 + i));
                    actions.add(timer(900));

                    List<Integer> ran = run(actions);
                    int expected = matched ? trueSteps : elseSteps;
                    assertEquals("one branch plus the continuation", expected + 1, ran.size());
                    for (int i = 0; i < expected; i++) {
                        assertEquals((matched ? 100 : 200) + i, (int) ran.get(i));
                    }
                    assertEquals(900, (int) ran.get(ran.size() - 1));
                }
            }
        }
    }

    // ---- what the run reports -------------------------------------------------------------------

    @Test public void skippedStepsAreReportedAsSkippedAndNamedByPath() {
        run(chain(lockedCondition(false, 1, 1), timer(11), timer(22), timer(90)));
        assertTrue(messageAt(0), messageAt(0).contains("running the ELSE path"));
        assertEquals("Skipped · ELSE path ran instead", messageAt(1));
        assertFalse("the step that ran must not be reported as skipped",
                messageAt(2).startsWith("Skipped"));

        setUp();
        run(chain(lockedCondition(true, 1, 1), timer(11), timer(22), timer(90)));
        assertTrue(messageAt(0), messageAt(0).contains("running the IF path"));
        assertFalse(messageAt(1).startsWith("Skipped"));
        assertEquals("Skipped · IF path ran instead", messageAt(2));
    }

    @Test public void everyStepIsStillReportedExactlyOnce() {
        run(chain(lockedCondition(true, 2, 2), timer(1), timer(2), timer(3), timer(4), timer(5)));
        assertEquals(6, messages.size());
        for (int i = 0; i < 6; i++) {
            assertFalse("step " + i + " was never reported", messageAt(i).isEmpty());
        }
    }

    // ---- live evaluation, not just locked decisions ------------------------------------------------

    @Test public void aLiveAllDayConditionTakesTheIfPath() {
        // Equal endpoints have always meant all day, so this condition is genuinely true.
        assertEquals(java.util.Arrays.asList(11, 90),
                run(chain(condition(1, 1), timer(11), timer(22), timer(90))));
    }

    @Test public void aLiveOutOfWindowConditionTakesTheElsePath() {
        assertEquals(java.util.Arrays.asList(22, 90),
                run(chain(liveFalseCondition(1, 1), timer(11), timer(22), timer(90))));
    }

    // ---- failures and stop/continue ------------------------------------------------------------------

    @Test public void aFailureOnTheElsePathStopsTheRoutineDeterministically() throws Exception {
        // An app that is not installed fails without continuing, exactly as it does outside a branch.
        AssistantReply.Action missingApp = new AssistantReply.Action(RoutineActionCatalog.OPEN_APP,
                new JSONObject().put("app", "definitely.not.installed"), false);
        List<Integer> ran = run(chain(
                lockedCondition(false, 1, 1), timer(11), missingApp, timer(90)));
        assertTrue("the IF path must not run", ran.isEmpty());
        assertFalse("a failing branch step stops the routine", completedAll);
        assertTrue(finished);
        assertEquals("it stopped at the failing step", 3, completedSteps);
    }

    @Test public void aFailureOnTheIfPathStopsBeforeTheElsePathIsEverReached() throws Exception {
        AssistantReply.Action missingApp = new AssistantReply.Action(RoutineActionCatalog.OPEN_APP,
                new JSONObject().put("app", "definitely.not.installed"), false);
        List<Integer> ran = run(chain(
                lockedCondition(true, 1, 1), missingApp, timer(22), timer(90)));
        assertTrue("neither the ELSE path nor the continuation may run", ran.isEmpty());
        assertFalse(completedAll);
    }

    @Test public void anUndecidableConditionStopsWithoutRunningEitherPath() throws Exception {
        // A location condition with no coordinates cannot be evaluated.
        AssistantReply.Action broken = new AssistantReply.Action(RoutineActionCatalog.IF_CONDITION,
                new JSONObject()
                        .put("mode", RoutineConditionEvaluator.MODE_LOCATION)
                        .put("nextSteps", 1)
                        .put(RoutineBranch.KEY_ELSE_STEPS, 1), false);
        List<Integer> ran = run(chain(broken, timer(11), timer(22), timer(90)));
        assertTrue("nothing may run when the branch cannot be decided", ran.isEmpty());
        assertFalse(completedAll);
    }

    @Test public void cancellingTheLastIfStepStillStepsOverTheElsePath() {
        AssistantReply.Action needsConfirmation = new AssistantReply.Action(
                RoutineActionCatalog.SET_TIMER, timer(11).params, true);
        List<AssistantReply.Action> actions = chain(
                lockedCondition(true, 1, 1), needsConfirmation, timer(22), timer(90));

        OrbitActionEngine.execute(context, actions,
                (action, onAllow, onCancel) -> onCancel.run(),
                new OrbitActionEngine.Listener() {
                    @Override public void onStep(AssistantReply.Action action,
                                                 DeviceActionExecutor.Result result, int index, int total) {
                        messages.add(index + ":" + (result == null ? "" : result.message));
                    }
                    @Override public void onFinished(boolean all, int completed, int total) {
                        completedAll = all;
                    }
                });

        List<Integer> ran = drainStartedActivities();
        assertEquals("a cancelled IF step must not fall through into the ELSE path",
                java.util.Arrays.asList(90), ran);
        assertEquals("Skipped · IF path ran instead", messageAt(2));
        assertTrue(completedAll);
    }

    // ---- degenerate shapes -----------------------------------------------------------------------

    @Test public void anEmptyChainStillFinishesCleanly() {
        assertTrue(run(new ArrayList<>()).isEmpty());
        assertTrue(finished);
        assertTrue(completedAll);
    }

    @Test public void aBranchTruncatedByTheEndOfTheRoutineDoesNotReadPastIt() {
        // Declared but not present: the engine clamps rather than running something else.
        List<Integer> ran = run(chain(lockedCondition(true, 1, 3), timer(11), timer(22)));
        assertEquals(java.util.Arrays.asList(11), ran);
        assertTrue(completedAll);
    }
}
