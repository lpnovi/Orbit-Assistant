package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.provider.AlarmClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Cooking timer labels, and the guarantee that Android still owns the timer.
 *
 * <p>A phone timer is nearly always timing food, and four Clock notifications that all say "Orbit
 * timer" are useless the moment there is more than one. Orbit now reads the name out of the
 * request — "Steak", "Potatoes" — and passes it to the Clock app as the timer's label.
 *
 * <p>What deliberately did <em>not</em> change is the timer itself. Orbit hands every timer to
 * Android's standard {@link AlarmClock#ACTION_SET_TIMER}, which is what puts it on the lock
 * screen, in the notification shade, and in Samsung's own system timer surfaces. Orbit runs no
 * timer of its own, and the second half of this file exists to keep it that way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class CookingTimerTest {

    private Application context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        // Robolectric has no Clock app installed, and Orbit refuses to fire an intent nothing can
        // handle. Standing one in for the system Clock is what lets the real intent be inspected.
        ResolveInfo clock = new ResolveInfo();
        clock.activityInfo = new ActivityInfo();
        clock.activityInfo.packageName = "com.android.deskclock";
        clock.activityInfo.name = "com.android.deskclock.TimerActivity";
        shadowOf(context.getPackageManager())
                .addResolveInfoForIntent(new Intent(AlarmClock.ACTION_SET_TIMER), clock);
    }

    private AssistantReply.Action timer(String prompt) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
        assertNotNull("Orbit should still act on: " + prompt, reply);
        assertTrue(prompt, reply.actions.size() >= 1);
        assertEquals(prompt, "SET_TIMER", reply.actions.get(0).type);
        return reply.actions.get(0);
    }

    private String label(String prompt) {
        return timer(prompt).params.optString("label", "");
    }

    private String spoken(String prompt) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
        assertNotNull("Orbit should still act on: " + prompt, reply);
        return reply.text;
    }

    // ---- cooking labels -------------------------------------------------------------------------

    @Test public void aNamedTimerTakesItsNameFromTheRequest() {
        assertEquals("Steak", label("Set a steak timer for 4 minutes"));
        assertEquals("Pasta", label("Start the pasta timer for 9 minutes"));
        assertEquals("Rice", label("set a rice timer for 12 minutes"));
    }

    @Test public void aSubjectSaidAfterTheTimerIsAlsoUsed() {
        assertEquals("Potatoes", label("Start a 20 minute timer for the potatoes"));
        assertEquals("Potatoes", label("Timer for the potatoes, 20 minutes"));
        assertEquals("Chicken", label("Set a timer for 25 minutes for the chicken"));
        assertEquals("Bread", label("set a timer for the bread for 30 minutes"));
    }

    @Test public void theLabelIsSaidBackNaturally() {
        assertEquals("Setting a 4-minute timer for steak.",
                spoken("Set a steak timer for 4 minutes"));
        assertEquals("Setting a 20-minute timer for potatoes.",
                spoken("Start a 20 minute timer for the potatoes"));
    }

    /** Nothing was named, so nothing is invented: the ordinary Orbit label is kept. */
    @Test public void anUnnamedTimerKeepsTheGenericLabel() {
        assertEquals("Orbit timer", label("Set a timer for 12 minutes"));
        assertEquals("Orbit timer", label("set a timer for 20 minutes"));
        assertEquals("Orbit timer", label("start a 10 minute timer"));
        assertEquals("Orbit timer", label("set a timer for five minutes"));
        assertEquals("Orbit timer", label("set a new timer for 3 minutes"));
        assertEquals("Orbit timer", label("set another timer for 3 minutes"));
    }

    /** The duration is never mistaken for the thing being timed. */
    @Test public void countsAndUnitsAreNeverReadAsALabel() {
        for (String prompt : new String[]{"set a 45 second timer", "set a timer for 2 hours",
                "set a timer for 90 secs", "start a 5 minute timer", "set a timer for one minute"}) {
            assertEquals(prompt, "Orbit timer", label(prompt));
        }
    }

    /**
     * Orbit only starts a timer when it is asked to. A sentence about cooking times that never
     * says "timer" is a question for the assistant, not a silent countdown.
     */
    @Test public void loosePhrasingIsNotTurnedIntoATimer() {
        assertNull(LocalCommandRouter.tryHandle(context, "Give the chicken another 8 minutes"));
        assertNull(LocalCommandRouter.tryHandle(context, "The potatoes need 20 minutes"));
        assertNull(LocalCommandRouter.tryHandle(context, "How long should I cook this for"));
    }

    // ---- Android still owns the timer -----------------------------------------------------------

    /**
     * The whole point. Orbit's timer is Android's timer: the same standard intent, the same
     * duration, and now a better label. There is no Orbit-owned timer service anywhere in this
     * path, and this test fails the moment one appears.
     */
    @Test public void everyTimerIsHandedToTheSystemClock() {
        DeviceActionExecutor.Result result =
                DeviceActionExecutor.executeDetailed(context, timer("Set a steak timer for 4 minutes"));
        assertTrue(result.success);

        Intent started = shadowOf(context).getNextStartedActivity();
        assertNotNull("Orbit must start the system Clock's timer", started);
        assertEquals(AlarmClock.ACTION_SET_TIMER, started.getAction());
        assertEquals(240, started.getIntExtra(AlarmClock.EXTRA_LENGTH, -1));
        assertEquals("Steak", started.getStringExtra(AlarmClock.EXTRA_MESSAGE));
    }

    @Test public void anUnnamedTimerGoesToTheSameSystemClockPath() {
        DeviceActionExecutor.executeDetailed(context, timer("set a timer for 20 minutes"));
        Intent started = shadowOf(context).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(AlarmClock.ACTION_SET_TIMER, started.getAction());
        assertEquals(1200, started.getIntExtra(AlarmClock.EXTRA_LENGTH, -1));
        assertEquals("Orbit timer", started.getStringExtra(AlarmClock.EXTRA_MESSAGE));
    }

    /** Durations are exactly what they were before labels existed. */
    @Test public void theDurationIsUnchangedByLabelling() {
        assertEquals(240, timer("Set a steak timer for 4 minutes").params.optInt("seconds", -1));
        assertEquals(1200, timer("Start a 20 minute timer for the potatoes").params.optInt("seconds", -1));
        assertEquals(1200, timer("set a timer for 20 minutes").params.optInt("seconds", -1));
        assertEquals(30, timer("set a timer for 30 seconds").params.optInt("seconds", -1));
        assertEquals(7200, timer("set a timer for 2 hours").params.optInt("seconds", -1));
    }

    /** A labelled timer still chains with other device commands. */
    @Test public void aLabelledTimerStillChains() {
        String chained = spoken("Set a steak timer for 4 minutes and turn on the flashlight");
        assertTrue(chained, chained.contains("set a 4-minute timer for steak"));
        assertTrue(chained, chained.contains("turn on the flashlight"));
    }
}
