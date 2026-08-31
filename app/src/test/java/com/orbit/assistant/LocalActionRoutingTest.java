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

/**
 * Where the on-device action model sits in Orbit's pipeline, and everything it must not take.
 *
 * <p>The value of a semantic fallback is entirely in it being a fallback. If it ran first it would
 * make instant, exact commands slow and probabilistic; if it ran on everything it would burn the
 * battery on conversation; if it ran when it was not installed it would break Orbit for people who
 * never wanted it. So this file is mostly about the gate rather than about the model.
 *
 * <p>Nothing here loads a model or runs inference. The gate is a pure function of the text and of
 * whether the model is installed, which is exactly what makes it testable.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class LocalActionRoutingTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        RecentActionContext.clear();
        Prefs.get(context).edit().clear().commit();
    }

    // ---- the deterministic routers win --------------------------------------------------------

    /**
     * The rule that keeps Orbit fast.
     *
     * <p>Every one of these is recognised exactly by a parser Orbit already has, so none of them may
     * ever reach a neural network. This is asserted from the router's own answer rather than from
     * the gate, because being handled earlier is what actually makes the gate unreachable.
     */
    @Test public void exactCommandsNeverReachTheModel() {
        for (String phrase : new String[]{
                "flashlight on", "turn off the flashlight", "turn on do not disturb",
                "set brightness to 40%", "set volume to 20%", "set a timer for 10 minutes",
                "open settings", "pause the music", "put my phone on vibrate"}) {
            AssistantReply handled = LocalCommandRouter.tryHandle(context, phrase);
            assertNotNull("\"" + phrase + "\" must be handled deterministically", handled);
            assertFalse(handled.actions.isEmpty());
        }
    }

    /** And a question about a state is answered by the status router, not by either of them. */
    @Test public void statusQuestionsAreAnsweredBeforeAnyCommandParsing() {
        assertNotNull(DeviceStatusRouter.topic("is do not disturb on"));
        assertNull("and are not instructions",
                LocalCommandRouter.tryHandle(context, "what's my battery at"));
    }

    // ---- the gate ------------------------------------------------------------------------------

    /** Instructions about things Orbit can control are worth one attempt. */
    @Test public void naturalInstructionsLookActionable() {
        for (String phrase : new String[]{
                "could you make the screen a little dimmer?",
                "this is way too loud, bring it down some",
                "I can't see anything, brighten the screen",
                "kill the torch",
                "wake me up at seven",
                "give me ten minutes for the pasta",
                "open up Spotify for me",
                "pull up Spotify",
                "can you make it quieter"}) {
            assertTrue("\"" + phrase + "\" should reach the action model",
                    OrbitLocalActionRouter.looksActionable(phrase));
        }
    }

    /**
     * The assertion that keeps a model off the critical path of ordinary conversation.
     *
     * <p>None of these could produce an allowed action even if the model tried, so running inference
     * on them would be pure cost and pure latency.
     */
    @Test public void ordinaryConversationNeverReachesTheModel() {
        for (String phrase : new String[]{
                "what's the capital of France",
                "write me a haiku about rain",
                "summarise this article for me",
                "why does my battery seem worse lately",
                "how does do not disturb work",
                "what is a good screen brightness for reading",
                "can you explain quantum tunnelling",
                "thanks, that was helpful",
                "tell me a joke",
                ""}) {
            assertFalse("\"" + phrase + "\" must go to the provider",
                    OrbitLocalActionRouter.looksActionable(phrase));
        }
        assertFalse(OrbitLocalActionRouter.looksActionable(null));
    }

    @Test public void aVeryLongMessageIsNotAnInstruction() {
        StringBuilder essay = new StringBuilder("please make the screen dimmer because ");
        while (essay.length() < 200) essay.append("it is very bright in here and ");
        assertFalse(OrbitLocalActionRouter.looksActionable(essay.toString()));
    }

    // ---- availability ---------------------------------------------------------------------------

    /**
     * With no component and no model installed, nothing about this feature happens.
     *
     * <p>This is the case for every cloud user who never opens Orbit Local, and it must cost them
     * nothing at all.
     */
    @Test public void withoutTheModelTheFallbackIsSimplyAbsent() {
        assertFalse(OrbitLocalActionRouter.available(context));
        assertFalse("and a perfectly actionable phrase still goes to the provider",
                OrbitLocalActionRouter.shouldTry(context, "kill the torch"));
    }

    /** Turning the feature off is enough on its own, without deleting anything. */
    @Test public void theSwitchIsRespectedBeforeAnythingElseIsChecked() {
        Prefs.get(context).edit().putBoolean(Prefs.LOCAL_DEVICE_ACTIONS, false).commit();
        assertFalse(Prefs.localDeviceActions(context));
        assertFalse(OrbitLocalActionRouter.available(context));
        assertFalse(OrbitLocalActionRouter.shouldTry(context, "kill the torch"));
    }

    @Test public void theSwitchDefaultsToOnBecauseInstallingItWasTheChoice() {
        assertTrue(Prefs.localDeviceActions(context));
    }

    // ---- Orbit's own last word -------------------------------------------------------------------

    /**
     * An alarm naming a day is refused, exactly as the deterministic parser refuses it.
     *
     * <p>{@code SET_ALARM} carries an hour and a minute and nothing else. Accepting "wake me up
     * tomorrow at seven thirty" would drop the day and then report success for an alarm that may
     * well be set for today. The deterministic router has always refused those, and the semantic
     * path must not quietly start accepting them.
     */
    @Test public void anAlarmThatNamesADayIsRefusedOnBothPaths() throws Exception {
        AssistantReply.Action alarm = new AssistantReply.Action("SET_ALARM",
                new JSONObject().put("hour", 7).put("minute", 30), false);

        assertTrue(OrbitLocalActionRouter.refusedByOrbit("wake me up tomorrow at seven thirty", alarm));
        assertTrue(OrbitLocalActionRouter.refusedByOrbit("set an alarm for monday at 7", alarm));
        assertTrue(OrbitLocalActionRouter.refusedByOrbit("wake me every day at 7", alarm));
        assertFalse("a plain time is representable and is allowed",
                OrbitLocalActionRouter.refusedByOrbit("wake me up at seven thirty", alarm));

        assertNull("and the deterministic router refuses it too",
                LocalCommandRouter.tryHandle(context, "wake me up tomorrow at 7:30"));
    }

    @Test public void aNullActionIsAlwaysRefused() {
        assertTrue(OrbitLocalActionRouter.refusedByOrbit("anything", null));
    }

    // ---- what Orbit says -------------------------------------------------------------------------

    /** The reply text is Orbit's, written from the validated action, never the model's prose. */
    @Test public void orbitWritesItsOwnConfirmation() throws Exception {
        assertEquals("Turning off the flashlight.", OrbitLocalActionRouter.speak(
                new AssistantReply.Action("FLASHLIGHT", new JSONObject().put("on", false), false)));
        assertEquals("Setting brightness to 30%.", OrbitLocalActionRouter.speak(
                new AssistantReply.Action("SET_BRIGHTNESS", new JSONObject().put("percent", 30), false)));
        assertEquals("Putting the phone on vibrate.", OrbitLocalActionRouter.speak(
                new AssistantReply.Action("SET_RINGER_MODE", new JSONObject().put("mode", "vibrate"), false)));
        assertEquals("Pausing playback.", OrbitLocalActionRouter.speak(
                new AssistantReply.Action("MEDIA_CONTROL", new JSONObject().put("command", "PAUSE"), false)));
        assertEquals("Setting a 10-minute timer for pasta.", OrbitLocalActionRouter.speak(
                new AssistantReply.Action("SET_TIMER",
                        new JSONObject().put("seconds", 600).put("label", "Pasta"), false)));
        assertEquals("Opening Spotify.", OrbitLocalActionRouter.speak(
                new AssistantReply.Action("OPEN_APP", new JSONObject().put("app", "Spotify"), false)));
    }

    // ---- the prompt ------------------------------------------------------------------------------

    /**
     * The model is given one instruction and two readings, and nothing else.
     *
     * <p>No conversation, no screen content, no memory, no attachments. That is a privacy property
     * and it is also why a 0.5B model can be dependable here at all.
     */
    @Test public void thePromptCarriesTheInstructionAndTheDeviceReadingsOnly() {
        String prompt = OrbitLocalActionRouter.buildPrompt(context, "make it a bit dimmer");
        assertTrue(prompt.contains("make it a bit dimmer"));
        assertTrue("every allowed action must be described", prompt.contains("SET_BRIGHTNESS"));
        assertTrue("and the way to decline", prompt.contains("\"NONE\""));
        for (String forbidden : new String[]{"Conversation so far", "untrusted_screen_content",
                "SMS", "DIAL", "OPEN_URL", "CREATE_EVENT"}) {
            assertFalse("the action prompt must not mention " + forbidden,
                    prompt.contains(forbidden));
        }
    }

    @Test public void thePromptBoundsAVeryLongInstruction() {
        StringBuilder huge = new StringBuilder();
        while (huge.length() < 5000) huge.append("dim the screen ");
        String prompt = OrbitLocalActionRouter.buildPrompt(context, huge.toString());
        assertTrue("the instruction must be bounded", prompt.length() < 3000);
    }
}
