package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Conversational follow-ups on the device target Orbit last acted on.
 *
 * <p>The rule under test is restraint: a follow-up may only borrow a target when it names none
 * itself, exactly one target was recently acted on, and the operation makes sense for it.
 * Anything less certain falls through, because guessing changes the user's phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RecentActionContextTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        RecentActionContext.clear();
    }

    @After public void tearDown() {
        RecentActionContext.clear();
    }

    private AssistantReply handled(String prompt) {
        return LocalCommandRouter.tryHandle(context, prompt);
    }

    private String typeOf(String prompt) {
        AssistantReply reply = handled(prompt);
        assertNotNull("expected a device action for: " + prompt, reply);
        return reply.actions.get(0).type;
    }

    // ---- resolving a follow-up ----

    @Test public void aBrightnessFollowUpResolvesToBrightness() {
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 65);
        assertEquals("SET_BRIGHTNESS", typeOf("a little more"));
    }

    @Test public void aVolumeFollowUpResolvesToVolume() {
        RecentActionContext.recordLevel(RecentActionContext.Target.VOLUME, 40);
        assertEquals("SET_VOLUME", typeOf("make it quieter"));
        assertTrue(handled("make it quieter").actions.get(0).params.optInt("delta", 0) < 0);
    }

    @Test public void aFlashlightFollowUpResolvesToTheFlashlight() {
        RecentActionContext.recordFlashlight(true);
        assertEquals("FLASHLIGHT", typeOf("turn it off"));
        assertFalse(handled("turn it off").actions.get(0).params.optBoolean("on", true));
    }

    @Test public void followUpMagnitudesBehaveLikeTheFullPhrase() {
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 65);
        assertEquals(-RelativeLevelCommand.SMALL_STEP,
                handled("a little less").actions.get(0).params.optInt("delta"));
        assertEquals(RelativeLevelCommand.DEFAULT_STEP,
                handled("more").actions.get(0).params.optInt("delta"));
    }

    // ---- restraint ----

    @Test public void aPhraseNamingItsOwnTargetIsNotAFollowUp() {
        RecentActionContext.recordLevel(RecentActionContext.Target.VOLUME, 40);
        // Naming brightness must win over the remembered volume.
        assertEquals("SET_BRIGHTNESS", typeOf("lower my brightness"));
    }

    @Test public void withNoRecentActionAFollowUpIsNotResolved() {
        assertFalse("nothing to borrow from", LocalCommandRouter.canHandle("a little more"));
        assertNull(handled("turn it off"));
    }

    @Test public void anExpiredContextIsNotUsed() {
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 65);
        assertNotNull(RecentActionContext.current());

        // Aged out: the same phrase must stop resolving.
        RecentActionContext.clear();
        assertNull(RecentActionContext.current());
        assertNull(handled("a little more"));
    }

    @Test public void aFlashlightTargetRefusesLevelLanguage() {
        RecentActionContext.recordFlashlight(true);
        // "a little more" means nothing for a torch, so it is not invented into one.
        assertNull(handled("a little more"));
    }

    @Test public void adirectionlessFollowUpIsRefused() {
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 65);
        assertNull(handled("do that"));
        assertNull(handled("hmm"));
    }

    @Test public void ordinaryConversationIsNeverAFollowUp() {
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 65);
        assertNull(handled("what is the capital of Ireland"));
        assertNull(handled("tell me a joke"));
    }

    @Test public void namingATargetMakesItNotBare() {
        assertFalse(RecentActionContext.isBareFollowUp("lower my brightness"));
        assertFalse(RecentActionContext.isBareFollowUp("turn the volume up"));
        assertFalse(RecentActionContext.isBareFollowUp("turn off the flashlight"));
        assertTrue(RecentActionContext.isBareFollowUp("a little more"));
    }

    // ---- reversal ----

    @Test public void reversalRestoresAKnownPreviousLevel() {
        RecentActionContext.recordLevel(RecentActionContext.Target.VOLUME, 40);
        AssistantReply reply = handled("put it back");
        assertNotNull(reply);
        assertEquals("SET_VOLUME", reply.actions.get(0).type);
        assertEquals("the level Orbit actually observed", 40,
                reply.actions.get(0).params.optInt("percent"));
    }

    @Test public void reversalIsRefusedWhenThePreviousLevelIsUnknown() {
        // -1 means Orbit could not read the level before changing it.
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, -1);
        assertNull("Orbit must not invent a level it never observed", handled("put it back"));
    }

    @Test public void flashlightReversalUsesTheRecordedPreviousState() {
        RecentActionContext.recordFlashlight(true);
        AssistantReply reply = handled("put it back");
        assertNotNull(reply);
        assertEquals("FLASHLIGHT", reply.actions.get(0).type);
        assertFalse("it was off before Orbit turned it on",
                reply.actions.get(0).params.optBoolean("on", true));
    }

    @Test public void reversalWithNoContextDoesNothing() {
        assertNull(handled("put it back"));
    }

    // ---- recording rules ----

    @Test public void onlyARealReadingIsStoredAsThePreviousLevel() {
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 150);
        assertEquals("an out-of-range reading is not a reading", -1,
                RecentActionContext.previousPercent());
    }

    @Test public void theMostRecentActionWins() {
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 65);
        RecentActionContext.recordLevel(RecentActionContext.Target.VOLUME, 40);
        assertEquals(RecentActionContext.Target.VOLUME, RecentActionContext.current());
        assertEquals("SET_VOLUME", typeOf("a little more"));
    }

    @Test public void clearingForgetsEverything() {
        RecentActionContext.recordLevel(RecentActionContext.Target.VOLUME, 40);
        RecentActionContext.clear();
        assertNull(RecentActionContext.current());
        assertEquals(-1, RecentActionContext.previousPercent());
    }
}
