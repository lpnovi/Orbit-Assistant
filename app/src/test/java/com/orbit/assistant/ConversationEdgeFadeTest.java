package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Where a long answer meets the header above it and the composer below it.
 *
 * <p>On the device, scrolling a long reply in the Side-button overlay cut the text off at a hard
 * horizontal line under the header and at another one just above the composer: a paragraph did not
 * pass behind Orbit's chrome, it simply stopped mid-glyph. Both surfaces stack the conversation as
 * a sibling of the chrome, so the ScrollView's own bounds were the cut.
 *
 * <p>The contract tested here is behavioural, not cosmetic. The exact fade depth is a design value
 * and is free to change; what must not change is that the fade exists, that it is vertical only,
 * and above all that the content keeps enough room to travel so no line is ever permanently stuck
 * behind it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ConversationEdgeFadeTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
    }

    /** Fading edge strength is protected on View; this only makes it readable to the test. */
    private static final class ProbeScrollView extends ScrollView {
        ProbeScrollView(Context c) { super(c); }
        float topStrength() { return getTopFadingEdgeStrength(); }
        float bottomStrength() { return getBottomFadingEdgeStrength(); }
    }

    private ProbeScrollView faded(LinearLayout content) {
        ProbeScrollView scroll = new ProbeScrollView(context);
        UiKit.applyConversationEdgeFade(scroll, content);
        return scroll;
    }

    @Test public void theConversationFadesVerticallyRatherThanBeingCutOff() {
        ScrollView scroll = faded(new LinearLayout(context));
        assertTrue("a long answer must dissolve at the chrome, not end at it",
                scroll.isVerticalFadingEdgeEnabled());
        assertEquals(UiKit.dp(context, UiKit.CONVERSATION_FADE_DP),
                scroll.getVerticalFadingEdgeLength());
    }

    @Test public void thereIsNoHorizontalFade() {
        assertFalse("only the header and composer edges are being softened",
                faded(new LinearLayout(context)).isHorizontalFadingEdgeEnabled());
    }

    /**
     * The usability rule this whole treatment lives or dies by: no line may be permanently stuck
     * behind the fade. A ScrollView's fading edge strength is derived from scroll position and
     * falls to zero at both extremes, so the first line is unfaded when scrolled to the top and
     * the last is unfaded when scrolled to the bottom. Nothing needs padding to make that true —
     * which is exactly why the content is only given a small inset, not the fade's whole depth.
     */
    @Test public void theFadeDisappearsAtBothScrollExtremes() {
        LinearLayout content = new LinearLayout(context);
        ProbeScrollView scroll = faded(content);
        scroll.addView(content);
        assertEquals("nothing above the top of the conversation to fade into",
                0f, scroll.topStrength(), 0.0001f);
        assertEquals("and nothing below its bottom either",
                0f, scroll.bottomStrength(), 0.0001f);
    }

    @Test public void bubblesAreNeverFlushAgainstTheChrome() {
        LinearLayout content = new LinearLayout(context);
        faded(content);
        int inset = UiKit.dp(context, UiKit.CONVERSATION_EDGE_INSET_DP);
        assertTrue(content.getPaddingTop() >= inset);
        assertTrue(content.getPaddingBottom() >= inset);
    }

    /**
     * The overlay's conversation is only a few hundred dp tall, so the inset must stay a small
     * fraction of the fade rather than matching it.
     */
    @Test public void theInsetDoesNotEatTheOverlayConversation() {
        assertTrue("padding both ends by the fade depth would cost 60dp of a 240dp sheet",
                UiKit.CONVERSATION_EDGE_INSET_DP * 2 < UiKit.CONVERSATION_FADE_DP);
    }

    @Test public void existingBreathingRoomIsNeverReduced() {
        LinearLayout content = new LinearLayout(context);
        int generous = UiKit.dp(context, 64);
        content.setPadding(7, generous, 9, generous);
        faded(content);
        assertEquals(generous, content.getPaddingTop());
        assertEquals(generous, content.getPaddingBottom());
        assertEquals("horizontal padding is not this helper's business", 7, content.getPaddingLeft());
        assertEquals(9, content.getPaddingRight());
    }

    /**
     * The fade is drawn, not laid out. Nothing is added to the hierarchy, so there is no view that
     * could intercept a scroll gesture, swallow a text selection, or cover a message action.
     */
    @Test public void nothingIsAddedToTheViewHierarchy() {
        LinearLayout content = new LinearLayout(context);
        ScrollView scroll = faded(content);
        assertEquals(0, scroll.getChildCount());
        assertEquals(0, content.getChildCount());
    }

    /**
     * Fading edges fade content to transparent rather than painting a colour over it, so the
     * conversation dissolves into whatever is actually behind it. Nothing here derives, caches, or
     * hard-codes a background, which is what makes it correct on AMOLED true black, on the
     * overlay sheet's accent gradient, and after any appearance change without being told.
     */
    @Test public void theTreatmentCarriesNoColourOfItsOwn() {
        LinearLayout content = new LinearLayout(context);
        ScrollView amoled = faded(content);
        int amoledLength = amoled.getVerticalFadingEdgeLength();

        Prefs.get(context).edit().putBoolean(Prefs.AMOLED_MODE, true).commit();
        UiKit.syncTheme(context);
        ScrollView afterThemeChange = faded(new LinearLayout(context));

        assertEquals("the edge treatment is theme-independent by construction",
                amoledLength, afterThemeChange.getVerticalFadingEdgeLength());
        assertEquals(0, amoled.getSolidColor());
        assertEquals(0, afterThemeChange.getSolidColor());
    }

    @Test public void aMissingContentViewIsSurvivable() {
        ScrollView scroll = new ScrollView(context);
        UiKit.applyConversationEdgeFade(scroll, null);
        assertTrue(scroll.isVerticalFadingEdgeEnabled());
        UiKit.applyConversationEdgeFade(null, new LinearLayout(context));
    }
}
