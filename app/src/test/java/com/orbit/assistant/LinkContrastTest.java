package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Link colour against the surface a link actually sits on.
 *
 * <p>The reported failure: a purple accent on a purple assistant bubble painted links in the
 * bubble's own colour, so they were effectively invisible. Links used the raw accent regardless
 * of what was behind them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class LinkContrastTest {
    /** The floor the helper works to; readable for link-sized text on these surfaces. */
    private static final double MIN = 3.0d;

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
    }

    private void withAccent(String accent) {
        Prefs.get(context).edit().putString(Prefs.ACCENT, accent).commit();
        UiKit.syncTheme(context);
    }

    private void assertReadable(String label, int background) {
        int link = UiKit.linkColorOn(context, background);
        double ratio = UiKit.contrastRatio(link, background);
        assertTrue(label + " produced an unreadable link (ratio " + ratio + ")", ratio >= MIN);
    }

    // ---- the reported bug ----

    @Test public void anAccentColouredBubbleDoesNotGetAccentColouredLinks() {
        withAccent("blurple");
        int bubble = UiKit.accent(context);

        int link = UiKit.linkColorOn(context, bubble);
        assertFalse("accent-on-accent is exactly the reported bug", link == bubble);
        assertReadable("accent bubble", bubble);
    }

    @Test public void identicalColoursNeverSurviveAsALink() {
        for (String accent : new String[]{"blurple", "mint", "rose", "nova", "blue"}) {
            withAccent(accent);
            int bubble = UiKit.accent(context);
            assertEquals("ratio against itself is 1.0", 1.0d,
                    UiKit.contrastRatio(bubble, bubble), 0.0001d);
            assertReadable(accent + " bubble", bubble);
        }
    }

    @Test public void aNearlyIdenticalBubbleAlsoMovesTheLink() {
        withAccent("blurple");
        int accent = UiKit.accent(context);
        // A bubble a few shades off the accent still collapses visually.
        int nearly = UiKit.blend(accent, Color.WHITE, 0.94f);
        assertReadable("near-accent bubble", nearly);
        assertTrue("a near-collision must move the colour",
                UiKit.contrastRatio(UiKit.linkColorOn(context, nearly), nearly) >
                        UiKit.contrastRatio(accent, nearly));
    }

    // ---- accent is kept when it reads ----

    @Test public void areadableAccentIsKeptExactly() {
        withAccent("mint");
        int accent = UiKit.accent(context);
        int darkSurface = Color.rgb(18, 20, 26);
        if (UiKit.contrastRatio(accent, darkSurface) >= MIN) {
            assertEquals("a readable accent should not be altered",
                    accent, UiKit.linkColorOn(context, darkSurface));
        }
        assertReadable("dark surface", darkSurface);
    }

    // ---- the customisation matrix ----

    @Test public void everyCombinationOfAccentAndBubbleStaysReadable() {
        int[] bubbles = {
                Color.BLACK,                 // AMOLED true black
                Color.rgb(18, 20, 26),       // ordinary dark bubble
                Color.rgb(44, 46, 58),       // lighter dark bubble
                Color.WHITE,                 // very light bubble
                Color.rgb(238, 238, 245),    // near-white bubble
                Color.rgb(60, 32, 120),      // dark custom purple
                Color.rgb(205, 195, 245)     // light custom purple
        };
        for (String accent : new String[]{"blurple", "mint", "rose", "nova", "blue",
                "pastelpink", "pastelblue"}) {
            withAccent(accent);
            for (int bubble : bubbles) {
                assertReadable(accent + " on #" + Integer.toHexString(bubble), bubble);
            }
            // And against the accent itself, the reported case.
            assertReadable(accent + " on its own accent", UiKit.accent(context));
        }
    }

    /** Simple perceptual luminance, enough to say "darker" or "lighter" in a test. */
    private static double luma(int color) {
        return (0.2126 * Color.red(color) + 0.7152 * Color.green(color)
                + 0.0722 * Color.blue(color)) / 255.0;
    }

    @Test public void lightOnLightBecomesDarkAndDarkOnDarkBecomesLight() {
        withAccent("pastelpink");
        int lightBubble = Color.rgb(245, 245, 250);
        int lightLink = UiKit.linkColorOn(context, lightBubble);
        assertTrue("a light surface needs a darker link",
                luma(lightLink) < luma(lightBubble));
        assertReadable("light bubble", lightBubble);

        withAccent("nova");
        int darkBubble = Color.rgb(10, 10, 14);
        int darkLink = UiKit.linkColorOn(context, darkBubble);
        assertTrue("a dark surface needs a lighter link",
                luma(darkLink) > luma(darkBubble));
        assertReadable("dark bubble", darkBubble);
    }

    @Test public void amoledTrueBlackIsReadable() {
        Prefs.get(context).edit().putBoolean(Prefs.AMOLED_MODE, true).commit();
        UiKit.syncTheme(context);
        for (String accent : new String[]{"blurple", "mint", "nova"}) {
            withAccent(accent);
            assertReadable("AMOLED " + accent, Color.BLACK);
        }
    }

    // ---- behaviour of the helper itself ----

    @Test public void theResultIsStableForTheSameInputs() {
        withAccent("blurple");
        int bubble = UiKit.accent(context);
        assertEquals(UiKit.linkColorOn(context, bubble), UiKit.linkColorOn(context, bubble));
    }

    @Test public void aChangedAccentChangesTheAnswer() {
        int dark = Color.rgb(18, 20, 26);
        withAccent("mint");
        int mint = UiKit.linkColorOn(context, dark);
        withAccent("rose");
        int rose = UiKit.linkColorOn(context, dark);
        assertFalse("links must follow the accent where they can", mint == rose);
    }
}
