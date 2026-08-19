package com.orbit.assistant;

import static com.orbit.assistant.RoutineBranchTest.chain;
import static com.orbit.assistant.RoutineBranchTest.lockedCondition;
import static com.orbit.assistant.RoutineBranchTest.timer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.List;

/**
 * Branching under an automatic trigger, driven through the real {@link RoutineTriggerExecution}.
 *
 * <p>Automatic runs are the case most likely to drift, because they scan a routine three times
 * before the engine ever sees it: once to decide whether a trigger alert will be needed, once to
 * preflight special access, and once to work out how far a background run can safely get. All
 * three now read the same branch geometry the engine does, and these prove it — most importantly
 * that a step on the path that will not run cannot block or delay a run it plays no part in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineBranchHeadlessTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // These are all background runs, so Orbit must not be considered on screen. A screen some
        // other suite opened is held weakly and would otherwise still be registered here.
        UiPresence.clearForTests();
        for (String file : new String[]{"orbit_routines", "orbit_routine_history",
                "orbit_routine_triggers"}) {
            context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit();
        }
        ComponentName clock = new ComponentName("com.orbit.test.clock", "com.orbit.test.clock.Timer");
        ShadowPackageManager packages = Shadows.shadowOf(context.getPackageManager());
        packages.addActivityIfNotPresent(clock);
        IntentFilter filter = new IntentFilter(AlarmClock.ACTION_SET_TIMER);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(clock, filter);
        drainTimers();
    }

    /** Background-safe and free of special access, so it never trips the preflight. */
    private static AssistantReply.Action volume(int percent) {
        try {
            return new AssistantReply.Action(RoutineActionCatalog.SET_VOLUME,
                    new JSONObject().put("percent", percent), false);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private List<Integer> drainTimers() {
        List<Integer> lengths = new ArrayList<>();
        ShadowApplication shadow = Shadows.shadowOf((Application) RuntimeEnvironment.getApplication());
        Intent started;
        while ((started = shadow.getNextStartedActivity()) != null) {
            if (AlarmClock.ACTION_SET_TIMER.equals(started.getAction())) {
                lengths.add(started.getIntExtra(AlarmClock.EXTRA_LENGTH, -1));
            }
        }
        java.util.Collections.reverse(lengths);
        return lengths;
    }

    private RoutineTriggerStore.Trigger triggerFor(String routineId) {
        return new RoutineTriggerStore.Trigger("trig-" + routineId, routineId,
                RoutineTriggerStore.TYPE_TIME, true, RoutineTriggerStore.MODE_DAILY,
                7, 0, 0, 0, 0, 0, 0, "", "", 0d, 0d, 0f, "", 0L, 0L, 0L, 0L, "");
    }

    private RoutineStore.Routine save(String name, List<AssistantReply.Action> actions) {
        RoutineStore.Routine routine = RoutineStore.create(name, actions);
        assertTrue("the routine has to be storable", RoutineStore.upsert(context, routine));
        return routine;
    }

    private List<Integer> runAutomatically(RoutineStore.Routine routine) {
        RoutineTriggerExecution.execute(context, routine, triggerFor(routine.id));
        return drainTimers();
    }

    // ---- an untaken branch must not get in the way -------------------------------------------

    @Test public void aForegroundOnlyStepOnTheUntakenIfPathDoesNotBlockABackgroundRun() {
        // The IF path needs Orbit visible; the condition is false, so it never runs.
        RoutineStore.Routine routine = save("Away",
                chain(lockedCondition(false, 1, 1), timer(11), volume(30), volume(40)));

        List<Integer> timers = runAutomatically(routine);
        assertTrue("the untaken IF path must not start anything", timers.isEmpty());

        List<RoutineRunHistoryStore.Entry> history = RoutineRunHistoryStore.list(context);
        assertEquals(1, history.size());
        assertTrue("the run must complete rather than wait for a handoff", history.get(0).success);
        assertEquals(RoutineRunHistoryStore.SOURCE_TRIGGER, history.get(0).source);
    }

    @Test public void aForegroundOnlyStepOnTheUntakenElsePathDoesNotBlockABackgroundRun() {
        RoutineStore.Routine routine = save("Home",
                chain(lockedCondition(true, 1, 1), volume(25), timer(22), volume(45)));

        List<Integer> timers = runAutomatically(routine);
        assertTrue("the untaken ELSE path must not start anything", timers.isEmpty());
        assertTrue(RoutineRunHistoryStore.list(context).get(0).success);
    }

    @Test public void aConfirmationOnTheUntakenPathIsNeverAskedFor() {
        AssistantReply.Action confirmed = new AssistantReply.Action(
                RoutineActionCatalog.SET_VOLUME, volume(80).params, true);
        RoutineStore.Routine routine = save("Quiet",
                chain(lockedCondition(false, 1, 1), confirmed, volume(20), volume(35)));

        runAutomatically(routine);
        List<RoutineRunHistoryStore.Entry> history = RoutineRunHistoryStore.list(context);
        assertEquals(1, history.size());
        assertTrue("a confirmation on the path that will not run cannot stop the run",
                history.get(0).success);
    }

    // ---- but a step on the taken path still behaves exactly as before ---------------------------

    /**
     * The mirror of the two tests above, and the reason they mean anything: the same shape with the
     * branch decision flipped, so the foreground-only step is now on the path that will run. It has
     * to defer instead of completing.
     */
    @Test public void aForegroundOnlyStepOnTheTakenPathStillDefersTheRun() {
        RoutineStore.Routine routine = save("Needs Orbit",
                chain(lockedCondition(true, 1, 1), timer(11), volume(30), volume(40)));

        List<Integer> timers = runAutomatically(routine);
        assertTrue("a foreground-only step must not run from the background", timers.isEmpty());

        for (RoutineRunHistoryStore.Entry entry : RoutineRunHistoryStore.list(context)) {
            assertTrue("the run has to stop short and wait for a handoff",
                    entry.completedSteps < entry.totalSteps);
        }
    }

    @Test public void anAutomaticRunTakesTheSameBranchAsAManualOne() {
        RoutineStore.Routine routine = save("Either way",
                chain(lockedCondition(false, 1, 1), volume(10), volume(60), volume(35)));
        runAutomatically(routine);
        assertTrue(RoutineRunHistoryStore.list(context).get(0).success);

        // The same chain through the ordinary engine, which is what a manual run uses.
        List<String> reported = new ArrayList<>();
        OrbitActionEngine.execute(context, routine.actions, null, new OrbitActionEngine.Listener() {
            @Override public void onStep(AssistantReply.Action action,
                                         DeviceActionExecutor.Result result, int index, int total) {
                reported.add(index + ":" + (result == null ? "" : result.message));
            }
            @Override public void onFinished(boolean all, int completed, int total) {}
        });
        assertTrue("the IF path is skipped either way", reported.get(1).contains("Skipped"));
        assertFalse("the ELSE path runs either way", reported.get(2).contains("Skipped"));
    }

    // ---- an existing routine is untouched -------------------------------------------------------

    @Test public void anIfOnlyRoutineRunsAutomaticallyExactlyAsItDidBefore() {
        RoutineStore.Routine routine = save("Legacy",
                chain(lockedCondition(false, 2, 0), volume(10), volume(20), volume(70)));

        runAutomatically(routine);
        List<RoutineRunHistoryStore.Entry> history = RoutineRunHistoryStore.list(context);
        assertEquals(1, history.size());
        assertTrue(history.get(0).success);
        assertEquals("every step is still accounted for", 4, history.get(0).totalSteps);
    }

    @Test public void theRoutineIsMarkedAsRunEitherWay() {
        RoutineStore.Routine routine = save("Marked",
                chain(lockedCondition(true, 1, 1), volume(15), volume(55), volume(35)));
        runAutomatically(routine);
        RoutineStore.Routine after = RoutineStore.findById(context, routine.id);
        assertNotNull(after);
        assertTrue(after.lastRunAt > 0L);
    }
}
