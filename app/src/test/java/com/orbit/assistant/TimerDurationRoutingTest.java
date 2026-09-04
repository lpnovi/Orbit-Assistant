package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
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
 * What Android is actually told when a person asks Orbit for a timer.
 *
 * <p>{@link DurationParserTest} pins the arithmetic; this pins the route. The device failures were
 * whole sentences, not bare durations, and the defect lived in the step between them — the router
 * read one count and one unit out of the sentence and threw the rest away. A parser that sums
 * correctly is no use if the sentence never reaches it intact.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class TimerDurationRoutingTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    /** The seconds a spoken command puts into the canonical SET_TIMER action. */
    private long timerSeconds(String prompt) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
        assertNotNull("Orbit should act on: " + prompt, reply);
        assertNotNull("a timer request should carry actions: " + prompt, reply.actions);
        for (AssistantReply.Action action : reply.actions) {
            if ("SET_TIMER".equalsIgnoreCase(action.type)) {
                return action.params.optLong("seconds", -1L);
            }
        }
        throw new AssertionError("no SET_TIMER action for: " + prompt);
    }

    private String spoken(String prompt) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
        assertNotNull("Orbit should act on: " + prompt, reply);
        return reply.text;
    }

    // ---- the two exact device failures ------------------------------------------------------------

    /** Typed on a Galaxy S25 Ultra. Samsung Clock received 4:00. */
    @Test public void fourMinutesAndThirtySecondsReachesAndroidAsTwoHundredAndSeventy() {
        assertEquals(270L, timerSeconds("set a timer for 4 minutes and 30 seconds"));
    }

    /** Also typed on the device, and also a four-minute timer. */
    @Test public void fourAndAHalfMinutesReachesAndroidAsTwoHundredAndSeventy() {
        assertEquals(270L, timerSeconds("set a timer for 4 and a half minutes"));
    }

    /**
     * The exact sentence typed on a Galaxy S25 Ultra against 0.7.8.2 Stable.
     *
     * <p>Orbit answered "Setting a 5-minute timer", the action card read "Timer 5m", and Samsung
     * Clock counted down from 5:00. This asserts the whole route at once, because narration, card
     * and payload are only trustworthy together: all three are derived from one normalized number
     * of seconds, and a test that checks one of them would pass while the other two lied.
     */
    @Test public void theDeviceFailureSetsAFourMinuteThirtySecondTimer() {
        String request = "set a timer for 4 and 1/2 minutes";
        assertEquals("Android must be handed 270 seconds", 270L, timerSeconds(request));
        assertEquals("Setting a 4 minute 30 second timer.", spoken(request));
        assertEquals("4m 30s", DurationParser.compactLabel(timerSeconds(request)));
    }

    /** Every phrasing of the same request produces the identical 270-second timer. */
    @Test public void everyPhrasingOfFourAndAHalfMinutesSetsTheSameTimer() {
        for (String request : new String[]{
                "set a timer for 4 and 1/2 minutes",
                "set a timer for 4 1/2 minutes",
                "set a timer for 4\u00BD minutes",
                "set a timer for 4 \u00BD minutes",
                "set a timer for 4 and a half minutes",
                "set a timer for 4.5 minutes",
                "set a timer for 4 minutes and 30 seconds"}) {
            assertEquals(request, 270L, timerSeconds(request));
            assertEquals(request, "Setting a 4 minute 30 second timer.", spoken(request));
        }
    }

    /** Written fractions through the router, including the ones smaller than a whole unit. */
    @Test public void writtenFractionsRouteCorrectly() {
        assertEquals(90L, timerSeconds("set a timer for 1 and 1/2 minutes"));
        assertEquals(90L, timerSeconds("set a timer for 1 1/2 minutes"));
        assertEquals(30L, timerSeconds("set a timer for 1/2 minute"));
        assertEquals(15L, timerSeconds("set a timer for 1/4 minute"));
        assertEquals(45L, timerSeconds("set a timer for 3/4 minute"));
        assertEquals(135L, timerSeconds("set a timer for 2 and 1/4 minutes"));
        assertEquals(165L, timerSeconds("set a timer for 2 and 3/4 minutes"));
        assertEquals(1800L, timerSeconds("set a timer for 1/2 hour"));
    }

    /** A written fraction said in front of the word works the same as one said after it. */
    @Test public void aWrittenFractionBeforeTheWordAlsoWorks() {
        assertEquals(270L, timerSeconds("set a 4 1/2 minute timer"));
        assertEquals(270L, timerSeconds("set a 4\u00BD minute timer"));
    }

    // ---- the rest of the required forms, through the router ---------------------------------------

    @Test public void everyNaturalDurationRoutesToTheRightNumberOfSeconds() {
        assertEquals(270L, timerSeconds("set a timer for 4 minutes 30 seconds"));
        assertEquals(90L, timerSeconds("set a timer for a minute and a half"));
        assertEquals(90L, timerSeconds("set a timer for one and a half minutes"));
        assertEquals(135L, timerSeconds("set a timer for 2 and a quarter minutes"));
        assertEquals(135L, timerSeconds("set a timer for 2.25 minutes"));
        assertEquals(270L, timerSeconds("set a timer for 4.5 minutes"));
        assertEquals(90L, timerSeconds("set a timer for 90 seconds"));
        assertEquals(5400L, timerSeconds("set a timer for 1 hour 30 minutes"));
        assertEquals(5400L, timerSeconds("set a timer for 1 hour and 30 minutes"));
        assertEquals(5400L, timerSeconds("set a timer for 1.5 hours"));
        assertEquals(3900L, timerSeconds("set a timer for 1 hour 5 minutes"));
        assertEquals(3930L, timerSeconds("set a timer for 1 hour 5 minutes 30 seconds"));
        assertEquals(3930L,
                timerSeconds("set a timer for one hour five minutes and thirty seconds"));
    }

    /** The forms that already worked still produce exactly what they always did. */
    @Test public void theOrdinaryFormsAreUnchanged() {
        assertEquals(300L, timerSeconds("set a timer for 5 minutes"));
        assertEquals(7200L, timerSeconds("set a timer for 2 hours"));
        assertEquals(30L, timerSeconds("set a timer for 30 seconds"));
        assertEquals(1200L, timerSeconds("set a 20 minute timer"));
        assertEquals(600L, timerSeconds("start a 10 minute timer"));
    }

    /** A duration said before the word, spanning units. */
    @Test public void aMixedDurationSaidBeforeTheWordAlsoWorks() {
        assertEquals(270L, timerSeconds("set a 4 minute 30 second timer"));
    }

    // ---- narration and card -----------------------------------------------------------------------

    @Test public void narrationDescribesTheDurationActuallySet() {
        assertEquals("Setting a 4 minute 30 second timer.",
                spoken("set a timer for 4 minutes and 30 seconds"));
        assertEquals("Setting a 4 minute 30 second timer.",
                spoken("set a timer for 4 and a half minutes"));
        assertEquals("Setting a 1 hour 5 minute 30 second timer.",
                spoken("set a timer for 1 hour 5 minutes 30 seconds"));
    }

    /** The unit the user chose survives whenever the duration genuinely sits in one. */
    @Test public void aSingleUnitIsStillEchoedAsTheUserSaidIt() {
        assertEquals("Setting a 90-minute timer.", spoken("set a timer for 90 minutes"));
        assertEquals("Setting a 20-minute timer.", spoken("set a timer for 20 minutes"));
        assertEquals("Setting a 2-hour timer.", spoken("set a timer for 2 hours"));
    }

    @Test public void theRoutineSummaryShowsTheWholeDuration() throws Exception {
        AssistantReply.Action action = new AssistantReply.Action(RoutineActionCatalog.SET_TIMER,
                new JSONObject().put("seconds", 270).put("label", "Orbit timer"), false);
        assertEquals("Starts a 4 minute 30 second timer", RoutineActionCatalog.summary(action));
        assertEquals("Timer · 4 minutes 30 seconds", RoutineActionCatalog.title(action));
    }

    // ---- chained and negative cases ---------------------------------------------------------------

    /** A chained command's other action keeps its own numbers out of the timer. */
    @Test public void aChainedTimerTakesOnlyItsOwnDuration() {
        assertEquals(1200L,
                timerSeconds("set a timer for 20 minutes and turn on the flashlight"));
        assertEquals(270L,
                timerSeconds("set a timer for 4 minutes and 30 seconds and turn on the flashlight"));
        assertTrue(spoken("set a timer for 4 minutes and 30 seconds and turn on the flashlight")
                .contains("4 minute 30 second timer"));
    }

    /** A named subject still becomes the Clock label, and does not eat the duration. */
    @Test public void aNamedTimerKeepsBothItsLabelAndItsDuration() {
        AssistantReply reply = LocalCommandRouter.tryHandle(context,
                "set a timer for the pasta for 8 minutes 30 seconds");
        assertNotNull(reply);
        for (AssistantReply.Action action : reply.actions) {
            if ("SET_TIMER".equalsIgnoreCase(action.type)) {
                assertEquals(510L, action.params.optLong("seconds", -1L));
                assertEquals("Pasta", action.params.optString("label", ""));
                return;
            }
        }
        throw new AssertionError("no SET_TIMER action");
    }

    /** Talking about a timer is not asking for one. */
    @Test public void askingAboutATimerStartsNothing() {
        assertNull(LocalCommandRouter.tryHandle(context, "how long is left on my timer"));
    }
}
