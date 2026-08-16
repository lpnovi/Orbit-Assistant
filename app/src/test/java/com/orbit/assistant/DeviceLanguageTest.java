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

import java.util.List;

/**
 * Everyday phrasing for the device commands Orbit already performs.
 *
 * <p>The bar is deliberately uneven: a command has to be recognisable in the ways people
 * actually say it, while a sentence that merely mentions the subject must never fire an action.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeviceLanguageTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private AssistantReply handled(String prompt) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
        assertNotNull("Orbit should act on: " + prompt, reply);
        return reply;
    }

    private static void notACommand(String prompt) {
        assertFalse("must not become a device action: " + prompt,
                LocalCommandRouter.canHandle(prompt));
    }

    private String actionType(String prompt) {
        List<AssistantReply.Action> actions = handled(prompt).actions;
        assertNotNull(prompt, actions);
        assertTrue(prompt, actions.size() >= 1);
        return actions.get(0).type;
    }

    private int firstParamInt(String prompt, String key) {
        return handled(prompt).actions.get(0).params.optInt(key, Integer.MIN_VALUE);
    }

    // ---- do not disturb ----

    @Test public void doNotDisturbEnableVariants() {
        String[] prompts = {
                "turn on do not disturb", "turn on DND", "enable DND", "enable do not disturb",
                "put my phone on do not disturb", "put my phone on DND", "switch on DND"
        };
        for (String prompt : prompts) {
            assertEquals(prompt, "SET_DND", actionType(prompt));
            assertTrue(prompt, handled(prompt).actions.get(0).params.optBoolean("enabled"));
        }
    }

    @Test public void doNotDisturbDisableVariants() {
        String[] prompts = {
                "turn off do not disturb", "turn off DND", "disable DND", "disable do not disturb",
                "take my phone off do not disturb", "take my phone off DND", "switch off DND"
        };
        for (String prompt : prompts) {
            assertEquals(prompt, "SET_DND", actionType(prompt));
            assertFalse(prompt, handled(prompt).actions.get(0).params.optBoolean("enabled", true));
        }
    }

    @Test public void vagueQuietRequestsAreNotDoNotDisturb() {
        notACommand("be quiet");
        notACommand("silence everything");
        notACommand("leave me alone");
        notACommand("how does do not disturb work");
    }

    // ---- timers ----

    @Test public void naturalTimerWording() {
        assertEquals("SET_TIMER", actionType("set a timer for 10 minutes"));
        assertEquals("SET_TIMER", actionType("set me a timer for 10 minutes"));
        assertEquals("SET_TIMER", actionType("10 minute timer"));
        assertEquals("SET_TIMER", actionType("start a 20 minute timer"));
        assertEquals("SET_TIMER", actionType("give me a 30 second timer"));
        assertEquals("SET_TIMER", actionType("timer for 2 hours"));
        assertEquals("SET_TIMER", actionType("start a timer for an hour"));
    }

    @Test public void timerDurationsAreCorrect() {
        assertEquals(600, firstParamInt("set a timer for 10 minutes", "seconds"));
        assertEquals(1200, firstParamInt("start a 20 minute timer", "seconds"));
        assertEquals(30, firstParamInt("give me a 30 second timer", "seconds"));
        assertEquals(7200, firstParamInt("timer for 2 hours", "seconds"));
        assertEquals(3600, firstParamInt("start a timer for an hour", "seconds"));
    }

    @Test public void conceptualTimerQuestionsAreNotIntercepted() {
        notACommand("how do Android timers work?");
        notACommand("what is the best timer app?");
    }

    // ---- alarms ----

    @Test public void naturalAlarmWording() {
        assertEquals("SET_ALARM", actionType("set an alarm for 8 AM"));
        assertEquals("SET_ALARM", actionType("set me an alarm for 8"));
        assertEquals("SET_ALARM", actionType("alarm at 7:30"));
        assertEquals("SET_ALARM", actionType("wake me up at 8 AM"));
        assertEquals("SET_ALARM", actionType("wake me at 6:45"));
    }

    @Test public void alarmTimesAreParsedCorrectly() {
        assertEquals(8, firstParamInt("set an alarm for 8 AM", "hour"));
        assertEquals(0, firstParamInt("set an alarm for 8 AM", "minute"));
        assertEquals(7, firstParamInt("alarm at 7:30", "hour"));
        assertEquals(30, firstParamInt("alarm at 7:30", "minute"));
        assertEquals(18, firstParamInt("set an alarm for 6 pm", "hour"));
    }

    @Test public void requestedDatesAreNeverSilentlyDiscarded() {
        // SET_ALARM carries only an hour and a minute. Rather than set 8am today and report
        // success, a request naming a day Orbit cannot represent falls through.
        notACommand("wake me up tomorrow at 8");
        notACommand("set an alarm for Monday at 7");
        notACommand("wake me up every day at 8");
        notACommand("set an alarm for 8 on weekdays");
    }

    // ---- apps ----

    @Test public void appLaunchVariants() {
        for (String prompt : new String[]{"open YouTube", "launch YouTube", "start Spotify",
                "bring up Maps", "open Discord for me", "take me to Spotify", "pull up Maps"}) {
            assertEquals(prompt, "OPEN_APP", actionType(prompt));
        }
    }

    @Test public void theAppNameLosesItsFiller() {
        assertEquals("discord", handled("open Discord for me").actions.get(0).params.optString("app"));
        assertEquals("spotify", handled("take me to Spotify").actions.get(0).params.optString("app"));
        assertEquals("maps", handled("bring up the Maps app").actions.get(0).params.optString("app"));
    }

    @Test public void questionsAboutAppsAreNotLaunches() {
        notACommand("How do I open YouTube links in Firefox?");
        notACommand("Why won't Spotify open?");
        notACommand("What happens when I open Maps?");
    }

    // ---- settings and flashlight ----

    @Test public void settingsAndFlashlightWordingStillWorks() {
        assertEquals("OPEN_SETTINGS", actionType("open settings"));
        assertEquals("OPEN_SETTINGS", actionType("open my settings"));
        assertEquals("FLASHLIGHT", actionType("turn on the flashlight"));
        assertEquals("FLASHLIGHT", actionType("turn off the flashlight"));
        assertEquals("FLASHLIGHT", actionType("turn on my torch"));
    }

    // ---- v0.7.3.5 relative levels, unchanged ----

    @Test public void relativeBrightnessIsUnchanged() {
        assertEquals("SET_BRIGHTNESS", actionType("lower my brightness"));
        assertEquals(-RelativeLevelCommand.DEFAULT_STEP, firstParamInt("lower my brightness", "delta"));
        assertEquals(-RelativeLevelCommand.SMALL_STEP,
                firstParamInt("lower my brightness a little", "delta"));
        assertEquals(-RelativeLevelCommand.LARGE_STEP,
                firstParamInt("turn my brightness way down", "delta"));
    }

    @Test public void relativeVolumeIsUnchanged() {
        assertEquals("SET_VOLUME", actionType("raise my volume"));
        assertEquals(RelativeLevelCommand.DEFAULT_STEP, firstParamInt("raise my volume", "delta"));
        assertEquals(RelativeLevelCommand.LARGE_STEP,
                firstParamInt("turn my volume up a lot", "delta"));
    }

    @Test public void absolutePercentagesRemainExact() {
        assertEquals(30, firstParamInt("set brightness to 30%", "percent"));
        assertEquals(40, firstParamInt("set volume to 40%", "percent"));
        assertEquals(Integer.MIN_VALUE, firstParamInt("set brightness to 30%", "delta"));
    }

    @Test public void byXPercentWordingIsNotTreatedAsAnAbsoluteTarget() {
        // Reserved for a later release. What must not happen is "lower brightness by 20%"
        // silently setting brightness to 20%.
        for (String prompt : new String[]{"lower brightness by 20%", "raise volume by 10 points",
                "make it 15% brighter than now"}) {
            AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
            if (reply != null && reply.actions != null && !reply.actions.isEmpty()) {
                int percent = reply.actions.get(0).params.optInt("percent", Integer.MIN_VALUE);
                assertFalse(prompt + " must not become an absolute 20%", percent == 20);
                assertFalse(prompt + " must not become an absolute 10%", percent == 10);
                assertFalse(prompt + " must not become an absolute 15%", percent == 15);
            }
        }
    }

    @Test public void bareItIsNotResolvedToATarget() {
        // Contextual pronoun resolution is deliberately not part of this release.
        notACommand("turn it way down");
        notACommand("turn it all the way up");
    }

    // ---- chains ----

    @Test public void chainedCommandsStillWork() {
        AssistantReply reply = handled("Turn on DND, lower my brightness, and open Spotify.");
        assertEquals(3, reply.actions.size());
        assertEquals("SET_DND", reply.actions.get(0).type);
        assertEquals("SET_BRIGHTNESS", reply.actions.get(1).type);
        assertEquals("OPEN_APP", reply.actions.get(2).type);
    }

    @Test public void punctuationFreeVoiceChainsStillWork() {
        AssistantReply reply = handled("turn on DND and set brightness to 20%");
        assertEquals(2, reply.actions.size());
        assertEquals("SET_DND", reply.actions.get(0).type);
        assertEquals(20, reply.actions.get(1).params.optInt("percent"));
    }

    @Test public void ordinarySentencesAreNotSplitIntoActions() {
        notACommand("I went to the shop and bought some milk");
        notACommand("tell me about brightness and contrast in photography");
    }

    // ---- politeness ----

    @Test public void politeWrappersDoNotBlockCommands() {
        assertEquals("SET_DND", actionType("please turn on do not disturb"));
        assertEquals("OPEN_APP", actionType("can you open Spotify"));
        assertEquals("SET_TIMER", actionType("hey Orbit set a timer for 5 minutes"));
    }
}
