package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
 * How Orbit says a timer back to the user.
 *
 * <p>English hyphenates a counted unit standing in front of a noun and keeps it singular - "a
 * 20-minute timer" - while the same duration standing on its own stays plural: "20 minutes".
 * Composing the standalone form with the noun is what produced "Setting a 20 minutes timer." on
 * the device, so both halves of that distinction are pinned here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class TimerWordingTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private String spokenReply(String prompt) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
        assertNotNull("Orbit should act on: " + prompt, reply);
        return reply.text;
    }

    private static AssistantReply.Action timerAction(int seconds) {
        try {
            return new AssistantReply.Action(RoutineActionCatalog.SET_TIMER,
                    new JSONObject().put("seconds", seconds).put("label", "Orbit timer"), false);
        } catch (Exception e) {
            throw new AssertionError("could not build a timer action", e);
        }
    }

    // ---- what the user hears after a direct command -----------------------------------------

    @Test public void aSpokenTimerIsConfirmedInNaturalEnglish() {
        assertEquals("Setting a 1-minute timer.", spokenReply("set a timer for 1 minute"));
        assertEquals("Setting a 20-minute timer.", spokenReply("set a timer for 20 minutes"));
        assertEquals("Setting a 30-second timer.", spokenReply("set a timer for 30 seconds"));
        assertEquals("Setting a 2-hour timer.", spokenReply("set a timer for 2 hours"));
    }

    /** The exact phrasing observed on the device, which used to read "20 minutes timer". */
    @Test public void theReportedPhraseIsFixed() {
        assertEquals("Setting a 20-minute timer.", spokenReply("set a timer for 20 minutes"));
    }

    @Test public void theCountSaidBeforeTheWordIsPhrasedTheSameWay() {
        assertEquals("Setting a 20-minute timer.", spokenReply("set a 20 minute timer"));
        assertEquals("Setting a 10-minute timer.", spokenReply("start a 10 minute timer"));
        assertEquals("Setting a 45-second timer.", spokenReply("set a 45 second timer"));
    }

    @Test public void shortenedUnitsAreSpelledOutProperly() {
        assertEquals("Setting a 5-minute timer.", spokenReply("set a timer for 5 mins"));
        assertEquals("Setting a 90-second timer.", spokenReply("set a timer for 90 secs"));
        assertEquals("Setting a 3-hour timer.", spokenReply("set a timer for 3 hrs"));
    }

    @Test public void writtenNumbersArePhrasedTheSameWay() {
        assertEquals("Setting a 5-minute timer.", spokenReply("set a timer for five minutes"));
        assertEquals("Setting a 1-minute timer.", spokenReply("set a timer for one minute"));
    }

    /**
     * The confirmation restates the unit the user actually said. Asking for 90 minutes is
     * confirmed as 90 minutes, not silently rewritten as an hour and a half.
     */
    @Test public void theUnitTheUserSaidIsKept() {
        assertEquals("Setting a 90-minute timer.", spokenReply("set a timer for 90 minutes"));
        assertEquals("Setting a 60-second timer.", spokenReply("set a timer for 60 seconds"));
    }

    // ---- chained commands inherit the same wording ------------------------------------------

    /**
     * A chain composes each command's own summary, so this proves the corrected phrase is
     * inherited rather than a second formatter being kept alive beside it.
     */
    @Test public void chainedCommandsInheritTheCorrectedSummary() {
        String chained = spokenReply("set a timer for 20 minutes and turn on the flashlight");
        assertTrue("chain summary should read naturally, was: " + chained,
                chained.contains("set a 20-minute timer"));
        assertTrue("the broken form must be gone, was: " + chained,
                !chained.contains("20 minutes timer"));
    }

    @Test public void everyChainedUnitReadsNaturally() {
        assertTrue(spokenReply("set a timer for 30 seconds and turn on the flashlight")
                .contains("set a 30-second timer"));
        assertTrue(spokenReply("set a timer for 2 hours and turn on the flashlight")
                .contains("set a 2-hour timer"));
        assertTrue(spokenReply("set a timer for 1 minute and turn on the flashlight")
                .contains("set a 1-minute timer"));
    }

    // ---- Routine summaries -------------------------------------------------------------------

    @Test public void routineTimerSummariesReadNaturally() {
        assertEquals("Starts a 1-minute timer", RoutineActionCatalog.summary(timerAction(60)));
        assertEquals("Starts a 20-minute timer", RoutineActionCatalog.summary(timerAction(1200)));
        assertEquals("Starts a 30-second timer", RoutineActionCatalog.summary(timerAction(30)));
        assertEquals("Starts a 2-hour timer", RoutineActionCatalog.summary(timerAction(7200)));
    }

    @Test public void aNamedRoutineTimerKeepsItsLabelAfterTheCorrectedPhrase() throws Exception {
        AssistantReply.Action named = new AssistantReply.Action(RoutineActionCatalog.SET_TIMER,
                new JSONObject().put("seconds", 1200).put("label", "Pasta"), false);
        assertEquals("Starts a 20-minute timer · Pasta", RoutineActionCatalog.summary(named));
    }

    // ---- the compact title is deliberately left alone ----------------------------------------

    /**
     * "Timer · 20 minutes" reads a duration as a standalone value after a separator, not as a
     * modifier, so it keeps ordinary plural English and stays compact.
     */
    @Test public void theCompactRoutineTitleIsUnchanged() {
        assertEquals("Timer · 20 minutes", RoutineActionCatalog.title(timerAction(1200)));
        assertEquals("Timer · 1 minute", RoutineActionCatalog.title(timerAction(60)));
        assertEquals("Timer · 30 seconds", RoutineActionCatalog.title(timerAction(30)));
        assertEquals("Timer · 2 hours", RoutineActionCatalog.title(timerAction(7200)));
    }

    // ---- standalone durations must stay plural -----------------------------------------------

    /**
     * The guard against over-correcting. A duration on its own is still ordinary English, and
     * {@code durationLabel} is used well beyond timers.
     */
    @Test public void standaloneDurationsStillPluralize() {
        assertEquals("1 minute", RoutineActionCatalog.durationLabel(60));
        assertEquals("20 minutes", RoutineActionCatalog.durationLabel(1200));
        assertEquals("30 seconds", RoutineActionCatalog.durationLabel(30));
        assertEquals("2 hours", RoutineActionCatalog.durationLabel(7200));
        assertEquals("1 second", RoutineActionCatalog.durationLabel(1));
        assertEquals("1 hour", RoutineActionCatalog.durationLabel(3600));
    }

    @Test public void theModifierFormIsHyphenatedAndSingular() {
        assertEquals("1-minute", RoutineActionCatalog.durationModifierLabel(60));
        assertEquals("20-minute", RoutineActionCatalog.durationModifierLabel(1200));
        assertEquals("30-second", RoutineActionCatalog.durationModifierLabel(30));
        assertEquals("2-hour", RoutineActionCatalog.durationModifierLabel(7200));
        assertEquals("1-second", RoutineActionCatalog.durationModifierLabel(1));
        assertEquals("1-hour", RoutineActionCatalog.durationModifierLabel(3600));
    }

    @Test public void theTwoFormsStayDistinct() {
        for (int seconds : new int[]{1, 30, 60, 90, 1200, 3600, 7200}) {
            String standalone = RoutineActionCatalog.durationLabel(seconds);
            String modifier = RoutineActionCatalog.durationModifierLabel(seconds);
            assertTrue("a standalone duration is spaced: " + standalone, standalone.contains(" "));
            assertTrue("a modifier is hyphenated: " + modifier, modifier.contains("-"));
            assertTrue("a modifier never carries a space: " + modifier, !modifier.contains(" "));
            assertTrue("a modifier is never plural: " + modifier, !modifier.endsWith("s"));
        }
    }

    // ---- nothing about the timer itself changed -----------------------------------------------

    /** Wording only: the seconds Orbit acts on are exactly what they were. */
    @Test public void theTimerItselfIsUnchanged() {
        assertEquals(60, timerSeconds("set a timer for 1 minute"));
        assertEquals(1200, timerSeconds("set a timer for 20 minutes"));
        assertEquals(30, timerSeconds("set a timer for 30 seconds"));
        assertEquals(7200, timerSeconds("set a timer for 2 hours"));
        assertEquals(1200, timerSeconds("set a 20 minute timer"));
    }

    private int timerSeconds(String prompt) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
        assertNotNull(prompt, reply);
        assertTrue(prompt, reply.actions.size() >= 1);
        assertEquals(prompt, "SET_TIMER", reply.actions.get(0).type);
        return reply.actions.get(0).params.optInt("seconds", -1);
    }
}
