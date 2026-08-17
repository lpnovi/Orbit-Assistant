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
 * Exact relative brightness and media-volume changes, and the boundary that keeps them apart
 * from absolute levels.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ExactRelativeLevelTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        RecentActionContext.clear();
    }

    private static RelativeLevelCommand parsed(String phrase) {
        RelativeLevelCommand c = RelativeLevelCommand.parse(phrase);
        assertNotNull("expected \"" + phrase + "\" to be understood", c);
        return c;
    }

    private int deltaFor(String prompt) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
        assertNotNull(prompt, reply);
        List<AssistantReply.Action> actions = reply.actions;
        assertNotNull(prompt, actions);
        return actions.get(0).params.optInt("delta", Integer.MIN_VALUE);
    }

    private int percentFor(String prompt) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, prompt);
        assertNotNull(prompt, reply);
        return reply.actions.get(0).params.optInt("percent", Integer.MIN_VALUE);
    }

    // ---- exact brightness ----

    @Test public void exactBrightnessDeltas() {
        assertEquals(-10, parsed("lower brightness by 10%").delta);
        assertEquals(-20, parsed("turn brightness down 20 percent").delta);
        assertEquals(5, parsed("increase brightness by 5%").delta);
        assertEquals(15, parsed("make the screen 15 percent brighter").delta);
    }

    @Test public void exactBrightnessReachesTheActionAsADelta() {
        assertEquals(-10, deltaFor("lower brightness by 10%"));
        assertEquals("SET_BRIGHTNESS",
                LocalCommandRouter.tryHandle(context, "lower brightness by 10%").actions.get(0).type);
    }

    // ---- exact volume ----

    @Test public void exactVolumeDeltas() {
        assertEquals(-10, parsed("lower the volume by 10%").delta);
        assertEquals(20, parsed("increase media volume by 20 percent").delta);
        assertEquals(5, parsed("turn the volume up by 5").delta);
        assertEquals(-15, parsed("drop media volume 15 percent").delta);
    }

    @Test public void exactVolumeReachesTheActionAsADelta() {
        assertEquals(-10, deltaFor("lower the volume by 10%"));
        assertEquals("SET_VOLUME",
                LocalCommandRouter.tryHandle(context, "lower the volume by 10%").actions.get(0).type);
    }

    // ---- the absolute boundary ----

    @Test public void absolutePercentagesStayAbsolute() {
        assertEquals(20, percentFor("set brightness to 20%"));
        assertEquals(40, percentFor("set volume to 40%"));
        assertEquals(Integer.MIN_VALUE, deltaFor("set brightness to 20%"));
    }

    @Test public void namingALevelIsNeverAMovement() {
        assertNull(RelativeLevelCommand.parse("set brightness to 30%"));
        assertNull(RelativeLevelCommand.parse("set volume to 40%"));
        assertNull(RelativeLevelCommand.parse("brightness at 25"));
        assertNull(RelativeLevelCommand.parse("lower brightness to 20"));
    }

    @Test public void loweringToAValueStillMeansThatValue() {
        assertEquals(20, percentFor("lower brightness to 20"));
    }

    // ---- qualitative behaviour is unchanged ----

    @Test public void qualitativeMagnitudesAreUntouched() {
        assertEquals(-RelativeLevelCommand.SMALL_STEP, parsed("lower my brightness a little").delta);
        assertEquals(-RelativeLevelCommand.DEFAULT_STEP, parsed("lower my brightness").delta);
        assertEquals(-RelativeLevelCommand.LARGE_STEP, parsed("turn my brightness way down").delta);
        assertEquals(RelativeLevelCommand.DEFAULT_STEP, parsed("raise my volume").delta);
    }

    @Test public void extremesAreUntouched() {
        assertTrue(parsed("maximum brightness").absolute);
        assertEquals(100, parsed("maximum brightness").percent);
        assertEquals(0, parsed("mute the media volume").percent);
    }

    // ---- clamping and refusal ----

    @Test public void resultsStillClamp() {
        assertEquals(0, RelativeLevelCommand.clampPercent(5 - 30));
        assertEquals(100, RelativeLevelCommand.clampPercent(95 + 30));
    }

    @Test public void anImplausibleAmountIsRefused() {
        assertNull(RelativeLevelCommand.parse("lower brightness by 400%"));
        assertNull(RelativeLevelCommand.parse("lower brightness by 0%"));
    }

    @Test public void aNumberWithoutRelativeGrammarIsNotADelta() {
        // No direction word, so nothing here expresses a movement.
        assertNull(RelativeLevelCommand.parse("brightness 40"));
        assertNull(RelativeLevelCommand.parse("volume 30 percent"));
    }

    @Test public void exactDeltasAreDescribedWithTheirAmount() {
        assertEquals("Lowering brightness by 10%.", parsed("lower brightness by 10%").confirmation());
        assertEquals("Raising media volume by 5%.", parsed("turn the volume up by 5").confirmation());
        assertTrue(parsed("lower brightness by 10%").exact);
        assertFalse(parsed("lower my brightness").exact);
    }

    @Test public void ordinaryLanguageIsStillNotADeviceAction() {
        assertNull(RelativeLevelCommand.parse("what is my brightness"));
        assertNull(RelativeLevelCommand.parse("dim sum for lunch tomorrow"));
        assertFalse(LocalCommandRouter.canHandle("how do notifications work"));
    }
}
