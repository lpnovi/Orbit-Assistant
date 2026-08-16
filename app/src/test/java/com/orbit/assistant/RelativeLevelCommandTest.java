package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Natural relative brightness and media-volume requests, which Orbit answers from the device's
 * own current level instead of asking "how much?".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RelativeLevelCommandTest {

    private static RelativeLevelCommand parsed(String phrase) {
        RelativeLevelCommand command = RelativeLevelCommand.parse(phrase);
        assertNotNull("expected \"" + phrase + "\" to be understood", command);
        return command;
    }

    // ---- brightness ----

    @Test public void loweringBrightnessUsesTheDefaultStep() {
        RelativeLevelCommand c = parsed("lower my brightness");
        assertEquals(RelativeLevelCommand.Target.BRIGHTNESS, c.target);
        assertFalse(c.absolute);
        assertEquals(-RelativeLevelCommand.DEFAULT_STEP, c.delta);
        assertEquals("SET_BRIGHTNESS", c.actionType());
    }

    @Test public void raisingBrightnessUsesTheDefaultStep() {
        assertEquals(RelativeLevelCommand.DEFAULT_STEP, parsed("raise my brightness").delta);
    }

    @Test public void everydayBrightnessWordingIsUnderstood() {
        String[] down = {"lower my brightness", "turn my brightness down", "dim my screen",
                "decrease the brightness", "reduce brightness"};
        for (String phrase : down) {
            assertTrue(phrase, parsed(phrase).delta < 0);
            assertEquals(phrase, RelativeLevelCommand.Target.BRIGHTNESS, parsed(phrase).target);
        }
        String[] up = {"raise my brightness", "turn my brightness up", "brighten my screen",
                "increase the brightness"};
        for (String phrase : up) {
            assertTrue(phrase, parsed(phrase).delta > 0);
        }
    }

    @Test public void aLittleIsASmallStep() {
        assertEquals(-RelativeLevelCommand.SMALL_STEP, parsed("lower my brightness a little").delta);
        assertEquals(-RelativeLevelCommand.SMALL_STEP, parsed("dim the screen a bit").delta);
        assertEquals(-RelativeLevelCommand.SMALL_STEP, parsed("lower brightness slightly").delta);
    }

    @Test public void aLotIsALargeStep() {
        assertEquals(-RelativeLevelCommand.LARGE_STEP, parsed("lower my brightness a lot").delta);
        assertEquals(-RelativeLevelCommand.LARGE_STEP, parsed("turn my brightness way down").delta);
        assertEquals(RelativeLevelCommand.LARGE_STEP, parsed("turn my brightness way up").delta);
        assertEquals(-RelativeLevelCommand.LARGE_STEP,
                parsed("decrease brightness significantly").delta);
    }

    @Test public void theWorkedExampleFromSeventyPercent() {
        // Documented behaviour: 70% is the base for each of these.
        assertEquals(65, RelativeLevelCommand.clampPercent(70 + parsed("lower my brightness a little").delta));
        assertEquals(55, RelativeLevelCommand.clampPercent(70 + parsed("lower my brightness").delta));
        assertEquals(40, RelativeLevelCommand.clampPercent(70 + parsed("lower my brightness a lot").delta));
        assertEquals(85, RelativeLevelCommand.clampPercent(70 + parsed("raise my brightness").delta));
    }

    @Test public void brightnessExtremesNameALevel() {
        RelativeLevelCommand max = parsed("maximum brightness");
        assertTrue(max.absolute);
        assertEquals(100, max.percent);

        RelativeLevelCommand min = parsed("minimum brightness");
        assertTrue(min.absolute);
        assertEquals(0, min.percent);

        assertEquals(100, parsed("turn the brightness all the way up").percent);
        assertEquals(0, parsed("turn the brightness all the way down").percent);
    }

    // ---- media volume ----

    @Test public void everydayVolumeWordingIsUnderstood() {
        String[] down = {"lower my volume", "turn the volume down", "decrease my volume"};
        for (String phrase : down) {
            RelativeLevelCommand c = parsed(phrase);
            assertEquals(phrase, RelativeLevelCommand.Target.VOLUME, c.target);
            assertTrue(phrase, c.delta < 0);
            assertEquals("SET_VOLUME", c.actionType());
        }
        String[] up = {"raise my volume", "turn my volume up", "increase my volume"};
        for (String phrase : up) {
            assertTrue(phrase, parsed(phrase).delta > 0);
        }
    }

    @Test public void volumeMagnitudesMatchBrightness() {
        assertEquals(-RelativeLevelCommand.SMALL_STEP, parsed("turn the volume down a little").delta);
        assertEquals(-RelativeLevelCommand.DEFAULT_STEP, parsed("lower my volume").delta);
        assertEquals(-RelativeLevelCommand.LARGE_STEP, parsed("turn the volume way down").delta);
        assertEquals(RelativeLevelCommand.LARGE_STEP, parsed("raise my volume a lot").delta);
    }

    @Test public void volumeExtremesNameALevel() {
        assertEquals(100, parsed("maximum volume").percent);
        assertEquals(0, parsed("mute the media volume").percent);
        assertEquals(100, parsed("turn the volume all the way up").percent);
        assertEquals(0, parsed("turn the volume all the way down").percent);
    }

    @Test public void otherAudioStreamsAreLeftAlone() {
        // Orbit's "volume" has always meant the media stream; naming another one is not
        // quietly redirected here.
        assertNull(RelativeLevelCommand.parse("lower the ringtone volume"));
        assertNull(RelativeLevelCommand.parse("turn down the alarm volume"));
        assertNull(RelativeLevelCommand.parse("lower the call volume"));
    }

    // ---- what must not be captured ----

    @Test public void explicitPercentagesAreNeverTreatedAsRelative() {
        assertNull(RelativeLevelCommand.parse("set my brightness to 30%"));
        assertNull(RelativeLevelCommand.parse("set my volume to 40%"));
        assertNull(RelativeLevelCommand.parse("lower brightness to 20"));
    }

    @Test public void ordinaryLanguageDoesNotBecomeADeviceAction() {
        String[] harmless = {
                "what is my brightness", "how loud is this", "the screen is too bright",
                "tell me about volume in chemistry", "remind me to lower my expectations",
                "turn on the flashlight", "what time is it", "play some music",
                "dim sum for lunch tomorrow"};
        for (String phrase : harmless) {
            RelativeLevelCommand c = RelativeLevelCommand.parse(phrase);
            if (c != null) {
                // Only a phrase that truly names a target and a direction may pass.
                assertTrue("\"" + phrase + "\" must not become a device action", false);
            }
        }
    }

    @Test public void aTargetWithoutADirectionIsNotACommand() {
        assertNull(RelativeLevelCommand.parse("brightness"));
        assertNull(RelativeLevelCommand.parse("my volume"));
    }

    @Test public void contradictoryDirectionsAreRefused() {
        assertNull(RelativeLevelCommand.parse("raise and lower my brightness"));
    }

    @Test public void nullAndEmptyInputAreSafe() {
        assertNull(RelativeLevelCommand.parse(null));
        assertNull(RelativeLevelCommand.parse(""));
        assertNull(RelativeLevelCommand.parse("   "));
    }

    // ---- clamping and wording ----

    @Test public void resultsClampToTheSafeRange() {
        assertEquals(0, RelativeLevelCommand.clampPercent(5 - RelativeLevelCommand.LARGE_STEP));
        assertEquals(100, RelativeLevelCommand.clampPercent(95 + RelativeLevelCommand.LARGE_STEP));
        assertEquals(0, RelativeLevelCommand.clampPercent(-40));
        assertEquals(100, RelativeLevelCommand.clampPercent(180));
    }

    @Test public void orbitSaysWhatItIsDoing() {
        assertEquals("Lowering brightness.", parsed("lower my brightness").confirmation());
        assertEquals("Raising media volume.", parsed("turn my volume up").confirmation());
        assertEquals("Muting media volume.", parsed("mute the media volume").confirmation());
        assertEquals("Setting brightness to maximum.", parsed("maximum brightness").confirmation());
        assertTrue(parsed("lower my brightness").summary().contains("brightness"));
    }
}
