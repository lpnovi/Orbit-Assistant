package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The readability arithmetic every theme is judged by.
 *
 * <p>Worth being precise about, because this is the only thing standing between a user typing a hex
 * value and an app they cannot read. The expected numbers here are WCAG's, computed independently
 * rather than recorded from a run of this code, so a change to the formula fails rather than
 * quietly redefining what "readable" means.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitContrastTest {

    private static final int WHITE = Color.WHITE;
    private static final int BLACK = Color.BLACK;
    private static final int MID_GREY = Color.rgb(128, 128, 128);

    // ---- luminance -------------------------------------------------------------------------------

    @Test public void luminanceSpansZeroToOne() {
        assertEquals(0.0d, OrbitContrast.relativeLuminance(BLACK), 0.0001d);
        assertEquals(1.0d, OrbitContrast.relativeLuminance(WHITE), 0.0001d);
    }

    /** sRGB mid grey is far darker than half, which is the whole reason for gamma correction. */
    @Test public void luminanceIsGammaCorrected() {
        double grey = OrbitContrast.relativeLuminance(MID_GREY);
        assertEquals(0.2159d, grey, 0.002d);
        assertTrue("a naive average would put this at 0.5", grey < 0.30d);
    }

    @Test public void luminanceUsesTheStandardChannelWeights() {
        double red = OrbitContrast.relativeLuminance(Color.rgb(255, 0, 0));
        double green = OrbitContrast.relativeLuminance(Color.rgb(0, 255, 0));
        double blue = OrbitContrast.relativeLuminance(Color.rgb(0, 0, 255));
        assertEquals(0.2126d, red, 0.0001d);
        assertEquals(0.7152d, green, 0.0001d);
        assertEquals(0.0722d, blue, 0.0001d);
    }

    // ---- contrast --------------------------------------------------------------------------------

    @Test public void blackOnWhiteIsTheMaximumRatio() {
        assertEquals(21.0d, OrbitContrast.contrastRatio(BLACK, WHITE), 0.01d);
        assertEquals("the ratio is symmetric",
                OrbitContrast.contrastRatio(BLACK, WHITE),
                OrbitContrast.contrastRatio(WHITE, BLACK), 0.0001d);
    }

    @Test public void aColourAgainstItselfIsOne() {
        assertEquals(1.0d, OrbitContrast.contrastRatio(MID_GREY, MID_GREY), 0.0001d);
        assertEquals(1.0d, OrbitContrast.contrastRatio(UiKit.DEFAULT_ACCENT,
                UiKit.DEFAULT_ACCENT), 0.0001d);
    }

    @Test public void knownPairingsMatchTheStandard() {
        assertEquals(5.32d, OrbitContrast.contrastRatio(BLACK, MID_GREY), 0.02d);
        assertEquals(3.95d, OrbitContrast.contrastRatio(WHITE, MID_GREY), 0.02d);
    }

    // ---- ink choice ------------------------------------------------------------------------------

    @Test public void lightGroundsTakeDarkInkAndDarkGroundsTakeLight() {
        assertTrue(OrbitContrast.prefersDarkInk(WHITE));
        assertFalse(OrbitContrast.prefersDarkInk(BLACK));
        assertEquals(OrbitContrast.DARK_INK, OrbitContrast.inkOn(WHITE));
        assertEquals(OrbitContrast.LIGHT_INK, OrbitContrast.inkOn(BLACK));
    }

    /** Mid grey is the case a naive luminance cutoff gets wrong: dark ink genuinely reads better. */
    @Test public void midGreyTakesDarkInk() {
        assertTrue(OrbitContrast.prefersDarkInk(MID_GREY));
        assertTrue(OrbitContrast.contrastRatio(OrbitContrast.inkOn(MID_GREY), MID_GREY)
                > OrbitContrast.contrastRatio(OrbitContrast.LIGHT_INK, MID_GREY));
    }

    @Test public void theChosenInkIsAlwaysAtLeastAsGoodAsTheOther() {
        int[] grounds = {
                WHITE, BLACK, MID_GREY,
                Color.rgb(139, 124, 255), Color.rgb(88, 101, 242), Color.rgb(80, 151, 255),
                Color.rgb(69, 204, 166), Color.rgb(244, 110, 150), Color.rgb(76, 0, 255),
                Color.rgb(255, 209, 220), Color.rgb(203, 229, 242),
                Color.rgb(255, 255, 0), Color.rgb(0, 255, 0), Color.rgb(255, 0, 0),
                Color.rgb(10, 12, 17), Color.rgb(22, 25, 33)
        };
        for (int ground : grounds) {
            int chosen = OrbitContrast.inkOn(ground);
            int other = chosen == OrbitContrast.DARK_INK
                    ? OrbitContrast.LIGHT_INK : OrbitContrast.DARK_INK;
            assertTrue("ink on " + Integer.toHexString(ground) + " was not the better of the two",
                    OrbitContrast.contrastRatio(chosen, ground)
                            >= OrbitContrast.contrastRatio(other, ground));
        }
    }

    /** A very bright accent and a very dark one must land on opposite inks. */
    @Test public void brightnessExtremesResolveOppositely() {
        assertEquals(OrbitContrast.DARK_INK, OrbitContrast.inkOn(Color.rgb(255, 240, 120)));
        assertEquals(OrbitContrast.LIGHT_INK, OrbitContrast.inkOn(Color.rgb(20, 8, 60)));
    }

    // ---- one ink for two grounds -------------------------------------------------------------------

    @Test public void primaryInkServesBothGroundsAsWellAsPossible() {
        int background = Color.rgb(7, 11, 18);
        int surface = Color.rgb(20, 28, 40);
        int chosen = OrbitContrast.primaryInk(background, surface);
        int other = chosen == OrbitContrast.DARK_INK
                ? OrbitContrast.LIGHT_INK : OrbitContrast.DARK_INK;
        double chosenWorst = Math.min(OrbitContrast.contrastRatio(chosen, background),
                OrbitContrast.contrastRatio(chosen, surface));
        double otherWorst = Math.min(OrbitContrast.contrastRatio(other, background),
                OrbitContrast.contrastRatio(other, surface));
        assertTrue(chosenWorst >= otherWorst);
        assertEquals(OrbitContrast.LIGHT_INK, chosen);
    }

    /**
     * The case this exists for: a light page with a dark card. Neither ink is right for both, and
     * choosing for one alone is how a theme ends up unreadable on the other.
     */
    @Test public void aSplitThemeStillGetsTheLessBadInk() {
        int chosen = OrbitContrast.primaryInk(Color.rgb(250, 250, 252), Color.rgb(24, 26, 32));
        int other = chosen == OrbitContrast.DARK_INK
                ? OrbitContrast.LIGHT_INK : OrbitContrast.DARK_INK;
        double chosenWorst = Math.min(
                OrbitContrast.contrastRatio(chosen, Color.rgb(250, 250, 252)),
                OrbitContrast.contrastRatio(chosen, Color.rgb(24, 26, 32)));
        double otherWorst = Math.min(
                OrbitContrast.contrastRatio(other, Color.rgb(250, 250, 252)),
                OrbitContrast.contrastRatio(other, Color.rgb(24, 26, 32)));
        assertTrue(chosenWorst >= otherWorst);
    }

    // ---- muted ink -------------------------------------------------------------------------------

    @Test public void mutedInkStaysReadable() {
        int[] grounds = {Color.rgb(22, 25, 33), BLACK, WHITE, Color.rgb(26, 22, 38),
                Color.rgb(36, 26, 24), Color.rgb(22, 33, 29)};
        for (int ground : grounds) {
            int ink = OrbitContrast.inkOn(ground);
            int muted = OrbitContrast.mutedInkOn(ink, ground);
            assertTrue("muted ink on " + Integer.toHexString(ground) + " fell below the threshold",
                    OrbitContrast.contrastRatio(muted, ground) >= OrbitContrast.LARGE_TEXT_MIN);
        }
    }

    @Test public void mutedInkIsActuallyQuieterThanTheInk() {
        int ground = Color.rgb(22, 25, 33);
        int ink = OrbitContrast.inkOn(ground);
        int muted = OrbitContrast.mutedInkOn(ink, ground);
        assertTrue(OrbitContrast.contrastRatio(muted, ground)
                < OrbitContrast.contrastRatio(ink, ground));
    }

    /** With nowhere to go, it gives back the full-strength ink rather than something unreadable. */
    @Test public void mutedInkGivesUpRatherThanGoingUnreadable() {
        int ground = Color.rgb(120, 120, 120);
        int ink = OrbitContrast.inkOn(ground);
        int muted = OrbitContrast.mutedInkOn(ink, ground);
        assertTrue(OrbitContrast.contrastRatio(muted, ground) >= OrbitContrast.LARGE_TEXT_MIN
                || muted == ink);
    }

    // ---- thresholds ------------------------------------------------------------------------------

    @Test public void lowContrastUsesWcagAa() {
        assertEquals(4.5d, OrbitContrast.BODY_TEXT_MIN, 0.0001d);
        assertEquals(3.0d, OrbitContrast.LARGE_TEXT_MIN, 0.0001d);
        assertFalse(OrbitContrast.isLowContrast(BLACK, WHITE));
        assertTrue(OrbitContrast.isLowContrast(Color.rgb(150, 150, 150), WHITE));
        assertTrue("an accent on itself is invisible",
                OrbitContrast.isLowContrastForUi(MID_GREY, MID_GREY));
    }

    // ---- blending and elevation ------------------------------------------------------------------

    @Test public void blendingIsLinearAndClamped() {
        assertEquals(WHITE, OrbitContrast.blend(WHITE, BLACK, 1f));
        assertEquals(BLACK, OrbitContrast.blend(WHITE, BLACK, 0f));
        assertEquals(WHITE, OrbitContrast.blend(WHITE, BLACK, 5f));
        assertEquals(BLACK, OrbitContrast.blend(WHITE, BLACK, -5f));
        int half = OrbitContrast.blend(WHITE, BLACK, 0.5f);
        assertEquals(128, Color.red(half));
        assertEquals(255, Color.alpha(half));
    }

    /** A dark surface elevates lighter; a light one elevates darker. One formula, both directions. */
    @Test public void elevationFollowsTheSurfaceItLifts() {
        int dark = Color.rgb(22, 25, 33);
        int raisedDark = OrbitContrast.elevate(dark, 0.05f);
        assertTrue(OrbitContrast.relativeLuminance(raisedDark)
                > OrbitContrast.relativeLuminance(dark));

        int light = Color.rgb(248, 248, 250);
        int raisedLight = OrbitContrast.elevate(light, 0.05f);
        assertTrue(OrbitContrast.relativeLuminance(raisedLight)
                < OrbitContrast.relativeLuminance(light));
    }

    // ---- the shipped role cutoffs are preserved ----------------------------------------------------

    /**
     * The reason {@code onAccent} and {@code onBubble} were not moved onto {@link
     * OrbitContrast#prefersDarkInk}. These four accents shipped with white content on them, and
     * "fixing" that would change the appearance of every install already using them.
     */
    @Test public void theShippedAccentForegroundsAreUnchanged() {
        assertEquals(Color.WHITE, UiKit.onAccent(Color.rgb(139, 124, 255)));
        assertEquals(Color.WHITE, UiKit.onAccent(Color.rgb(80, 151, 255)));
        assertEquals(Color.WHITE, UiKit.onAccent(Color.rgb(244, 110, 150)));
        assertEquals(Color.WHITE, UiKit.onAccent(Color.rgb(88, 101, 242)));
        assertEquals(Color.WHITE, UiKit.onAccent(Color.rgb(76, 0, 255)));
        assertEquals("pastels have always taken dark content",
                Color.rgb(22, 25, 33), UiKit.onAccent(Color.rgb(255, 209, 220)));
        assertEquals(Color.rgb(22, 25, 33), UiKit.onAccent(Color.rgb(203, 229, 242)));
    }

    @Test public void theShippedBubbleForegroundsAreUnchanged() {
        assertEquals(Color.rgb(244, 244, 248), UiKit.onBubble(Color.rgb(22, 25, 33)));
        assertEquals(Color.rgb(244, 244, 248), UiKit.onBubble(Color.rgb(139, 124, 255)));
        assertEquals(Color.rgb(24, 27, 34), UiKit.onBubble(Color.rgb(255, 209, 220)));
        assertEquals("mint sits just above the bubble cutoff",
                Color.rgb(24, 27, 34), UiKit.onBubble(Color.rgb(69, 204, 166)));
    }

    /**
     * A bubble's ink follows the bubble, never the page.
     *
     * <p>Before Theme Studio these were the same thing, because the page was always dark. With a
     * light background a themed app would otherwise put dark ink on a dark bubble.
     */
    @Test public void bubbleInkIgnoresTheThemedPageInk() {
        int before = UiKit.onBubble(Color.rgb(30, 34, 44));
        UiKit.applyTheme(OrbitThemeTokens.resolve(
                org.robolectric.RuntimeEnvironment.getApplication(),
                OrbitTheme.custom("light", OrbitTheme.DYNAMIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, "#F7F7FA", "#FFFFFF", false)));
        assertEquals("a dark bubble keeps light ink whatever the page is doing",
                before, UiKit.onBubble(Color.rgb(30, 34, 44)));
        UiKit.applyTheme(OrbitThemeTokens.resolve(
                org.robolectric.RuntimeEnvironment.getApplication(), OrbitTheme.orbitDefault()));
    }
}
