package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The thinking indicator has to stay visible against whatever bubble it is drawn on, including a
 * bubble set to the same accent it would otherwise be painted in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ThinkingContrastTest {
    /** Matches the view's own threshold for "this would disappear". */
    private static final double MIN_CONTRAST = 1.55d;

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private static void draw(OrbitThinkingView view, int size) {
        view.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        bitmap.recycle();
    }

    /** The tone the view settles on for a given bubble, mirroring its own selection. */
    private int resolvedTone(int accent, int bubble) {
        if (UiKit.contrastRatio(accent, bubble) < MIN_CONTRAST) {
            return UiKit.blend(accent, UiKit.onBubble(bubble), 0.30f);
        }
        return accent;
    }

    @Test public void anAccentBubbleNoLongerSwallowsTheIndicator() {
        for (String key : UiKit.accentKeys()) {
            Prefs.get(context).edit().putString(Prefs.ACCENT, key).commit();
            int accent = UiKit.accent(context);
            // Orbit bubble = Accent means the bubble is exactly the accent colour.
            int tone = resolvedTone(accent, accent);
            assertTrue("accent " + key + " would vanish on its own bubble",
                    UiKit.contrastRatio(tone, accent) >= MIN_CONTRAST);
        }
    }

    @Test public void aWellContrastedBubbleKeepsTheNormalAccentTreatment() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        int accent = UiKit.accent(context);
        // Orbit's ordinary dark assistant surface contrasts with every accent.
        assertEquals("the normal accent look must be preserved where it already works",
                accent, resolvedTone(accent, UiKit.SURFACE));
    }

    @Test public void theFallbackToneStillCarriesAccent() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "rose").commit();
        int accent = UiKit.accent(context);
        int tone = resolvedTone(accent, accent);

        // Visible, but not a plain white or black shape: some accent survives the blend.
        assertTrue(UiKit.contrastRatio(tone, accent) >= MIN_CONTRAST);
        assertTrue("the fallback must not collapse to pure white",
                tone != Color.WHITE);
        assertTrue("the fallback must not collapse to pure black",
                tone != Color.BLACK);
    }

    @Test public void bothLightAndDarkBubblesGetAVisibleTone() {
        int[] bubbles = {Color.rgb(255, 209, 220), Color.rgb(10, 12, 17), Color.BLACK, Color.WHITE};
        for (String key : UiKit.accentKeys()) {
            Prefs.get(context).edit().putString(Prefs.ACCENT, key).commit();
            int accent = UiKit.accent(context);
            for (int bubble : bubbles) {
                int tone = resolvedTone(accent, bubble);
                assertTrue("accent " + key + " on bubble " + Integer.toHexString(bubble),
                        UiKit.contrastRatio(tone, bubble) >= MIN_CONTRAST);
            }
        }
    }

    @Test public void contrastRatioIsSymmetricAndOneForIdenticalColours() {
        assertEquals(1.0d, UiKit.contrastRatio(Color.RED, Color.RED), 0.0001d);
        assertEquals(UiKit.contrastRatio(Color.WHITE, Color.BLACK),
                UiKit.contrastRatio(Color.BLACK, Color.WHITE), 0.0001d);
        assertTrue(UiKit.contrastRatio(Color.WHITE, Color.BLACK) > 20d);
    }

    @Test public void theViewDrawsAgainstAnAccentBubbleWithoutCrashing() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "blurple").commit();
        Prefs.get(context).edit().putString(Prefs.ASSISTANT_BUBBLE_COLOR, "accent").commit();

        OrbitThinkingView view = new OrbitThinkingView(context);
        view.applyAccent(UiKit.assistantBubbleFill(context, UiKit.SURFACE));
        view.start();
        draw(view, 30);
        assertTrue(view.isRunning());

        view.settle();
        view.stop();
        assertTrue("the indicator must still retire normally", !view.isRunning());
    }

    @Test public void anUnknownBackgroundKeepsThePlainAccentBehaviour() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "nova").commit();
        OrbitThinkingView view = new OrbitThinkingView(context);
        // No background supplied: the pre-existing behaviour is unchanged.
        view.applyAccent();
        view.start();
        draw(view, 30);
        assertTrue(view.isRunning());
        view.stop();
    }

    @Test public void theTwoBubbleColoursRemainIndependent() {
        Prefs.get(context).edit()
                .putString(Prefs.USER_BUBBLE_COLOR, "accent")
                .putString(Prefs.ASSISTANT_BUBBLE_COLOR, "classic")
                .commit();
        assertEquals("accent", Prefs.userBubbleColor(context));
        assertEquals("classic", Prefs.assistantBubbleColor(context));
    }
}
