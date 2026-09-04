package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where a highlight lands, asserted as arithmetic.
 *
 * <p>This is the part of PDF search that cannot be checked by eye without a device and a real
 * textbook, and it is also the part with the most ways to be subtly wrong: an inverted axis, a page
 * rotation applied twice, a fraction taken against the wrong dimension. Each of those produces a
 * coloured box in a plausible-looking place that is not on the word.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DocumentPageMappingTest {

    private static final float E = 0.0005f;

    // ---- page size --------------------------------------------------------------------------------

    @Test public void aQuarterTurnExchangesWidthAndHeight() {
        assertFalse(DocumentPageMapping.quarterTurned(0));
        assertTrue(DocumentPageMapping.quarterTurned(90));
        assertFalse(DocumentPageMapping.quarterTurned(180));
        assertTrue(DocumentPageMapping.quarterTurned(270));
        // A PDF may state its rotation as a multiple or as a negative; both are the same page.
        assertTrue(DocumentPageMapping.quarterTurned(450));
        assertTrue(DocumentPageMapping.quarterTurned(-90));
    }

    @Test public void aRotatedPageIsMeasuredInItsDisplayedOrientation() {
        assertEquals(612f, DocumentPageMapping.displayWidth(612f, 792f, 0), E);
        assertEquals(792f, DocumentPageMapping.displayHeight(612f, 792f, 0), E);
        assertEquals(792f, DocumentPageMapping.displayWidth(612f, 792f, 90), E);
        assertEquals(612f, DocumentPageMapping.displayHeight(612f, 792f, 90), E);
        assertEquals(612f, DocumentPageMapping.displayWidth(612f, 792f, 180), E);
        assertEquals(792f, DocumentPageMapping.displayWidth(612f, 792f, 270), E);
    }

    // ---- one character ----------------------------------------------------------------------------

    /** PDFBox reports the character's lower edge from the top, so the top is that minus its height. */
    @Test public void aCharacterBecomesAFractionOfThePage() {
        RectF box = DocumentPageMapping.normalize(100f, 200f, 50f, 20f, 400f, 800f);
        assertNotNull(box);
        assertEquals(0.25f, box.left, E);
        assertEquals((200f - 20f) / 800f, box.top, E);
        assertEquals(150f / 400f, box.right, E);
        assertEquals(0.25f, box.bottom, E);
    }

    @Test public void aCharacterOverTheCropEdgeIsClampedRatherThanLost() {
        RectF box = DocumentPageMapping.normalize(-2f, 801f, 10f, 12f, 400f, 800f);
        assertNotNull(box);
        assertEquals(0f, box.left, E);
        assertEquals(1f, box.bottom, E);
        assertTrue(box.top >= 0f && box.top <= 1f);
        assertTrue(box.right >= 0f && box.right <= 1f);
    }

    @Test public void nonsenseGeometryProducesNoBox() {
        assertNull(DocumentPageMapping.normalize(Float.NaN, 10f, 5f, 5f, 400f, 800f));
        assertNull(DocumentPageMapping.normalize(10f, Float.POSITIVE_INFINITY, 5f, 5f, 400f, 800f));
        assertNull(DocumentPageMapping.normalize(10f, 10f, 5f, 5f, 0f, 800f));
        assertNull(DocumentPageMapping.normalize(10f, 10f, 5f, 5f, 400f, -1f));
    }

    // ---- joining characters -----------------------------------------------------------------------

    private static RectF at(float left, float top, float right, float bottom) {
        return new RectF(left, top, right, bottom);
    }

    @Test public void charactersOnOneLineBecomeOneHighlight() {
        List<RectF> merged = DocumentPageMapping.mergeLineFragments(Arrays.asList(
                at(0.10f, 0.20f, 0.12f, 0.24f),
                at(0.12f, 0.20f, 0.14f, 0.24f),
                at(0.14f, 0.20f, 0.16f, 0.24f)));
        assertEquals(1, merged.size());
        assertEquals(0.10f, merged.get(0).left, E);
        assertEquals(0.16f, merged.get(0).right, E);
        assertEquals(0.20f, merged.get(0).top, E);
        assertEquals(0.24f, merged.get(0).bottom, E);
    }

    /** A phrase that wraps is two highlights, not one rectangle swallowing the space between. */
    @Test public void aMatchSpanningTwoLinesBecomesTwoHighlights() {
        List<RectF> merged = DocumentPageMapping.mergeLineFragments(Arrays.asList(
                at(0.80f, 0.20f, 0.86f, 0.24f),
                at(0.86f, 0.20f, 0.92f, 0.24f),
                at(0.08f, 0.28f, 0.14f, 0.32f),
                at(0.14f, 0.28f, 0.20f, 0.32f)));
        assertEquals(2, merged.size());
        assertEquals(0.24f, merged.get(0).bottom, E);
        assertEquals(0.28f, merged.get(1).top, E);
        assertTrue("the two lines do not merge into one tall block",
                merged.get(0).bottom <= merged.get(1).top);
    }

    /** A wide gap on the same line is a separate fragment, not one box across the whole column. */
    @Test public void aWideGapOnOneLineIsNotBridged() {
        List<RectF> merged = DocumentPageMapping.mergeLineFragments(Arrays.asList(
                at(0.08f, 0.20f, 0.14f, 0.24f),
                at(0.70f, 0.20f, 0.76f, 0.24f)));
        assertEquals(2, merged.size());
    }

    @Test public void nothingInMeansNothingOut() {
        assertTrue(DocumentPageMapping.mergeLineFragments(null).isEmpty());
        assertTrue(DocumentPageMapping.mergeLineFragments(new ArrayList<>()).isEmpty());
        assertTrue(DocumentPageMapping.mergeLineFragments(Arrays.asList((RectF) null, null))
                .isEmpty());
    }

    // ---- onto the screen --------------------------------------------------------------------------

    @Test public void aFractionBecomesAPositionInTheDrawnPage() {
        RectF onScreen = DocumentPageMapping.toView(at(0.25f, 0.5f, 0.5f, 0.6f),
                100f, 40f, 400f, 800f);
        assertNotNull(onScreen);
        assertEquals(200f, onScreen.left, E);
        assertEquals(440f, onScreen.top, E);
        assertEquals(300f, onScreen.right, E);
        assertEquals(520f, onScreen.bottom, E);
    }

    /**
     * The same fraction follows the page through a zoom.
     *
     * <p>This is the property that makes the highlight stay on its word: nothing about the geometry
     * changes when the transform does, so there is no cached screen position to go stale.
     */
    @Test public void aHighlightTracksTheDrawnPageThroughZoomAndPan() {
        RectF fraction = at(0.25f, 0.5f, 0.5f, 0.6f);
        RectF fitted = DocumentPageMapping.toView(fraction, 0f, 0f, 400f, 800f);
        RectF zoomed = DocumentPageMapping.toView(fraction, -200f, -400f, 800f, 1600f);
        assertNotNull(fitted);
        assertNotNull(zoomed);
        // Twice the scale, offset by the pan: the highlight is exactly where the word now is.
        assertEquals(fitted.left * 2f - 200f, zoomed.left, E);
        assertEquals(fitted.top * 2f - 400f, zoomed.top, E);
        assertEquals(fitted.width() * 2f, zoomed.width(), E);
        assertEquals(fitted.height() * 2f, zoomed.height(), E);
    }

    @Test public void anUndrawablePageProducesNoScreenRectangle() {
        assertNull(DocumentPageMapping.toView(at(0f, 0f, 1f, 1f), 0f, 0f, 0f, 100f));
        assertNull(DocumentPageMapping.toView(null, 0f, 0f, 100f, 100f));
    }

    // ---- revealing a result -----------------------------------------------------------------------

    /** A result already on screen is not chased. Pressing Next should not jolt the page. */
    @Test public void aVisibleResultIsLeftAlone() {
        float[] delta = DocumentPageMapping.panToReveal(
                at(200f, 300f, 260f, 320f), 1000f, 2000f, 24f);
        assertEquals(0f, delta[0], E);
        assertEquals(0f, delta[1], E);
    }

    @Test public void aResultOffTheEdgeIsBroughtInByTheSmallestMove() {
        float[] up = DocumentPageMapping.panToReveal(
                at(200f, -80f, 260f, -60f), 1000f, 2000f, 24f);
        assertEquals(0f, up[0], E);
        assertEquals("moved down just far enough to clear the margin", 104f, up[1], E);

        float[] right = DocumentPageMapping.panToReveal(
                at(1040f, 300f, 1100f, 320f), 1000f, 2000f, 24f);
        assertEquals(-124f, right[0], E);
        assertEquals(0f, right[1], E);
    }

    /** Something larger than the viewport shows its start rather than being centred on nothing. */
    @Test public void anOversizedResultShowsItsStart() {
        float[] delta = DocumentPageMapping.panToReveal(
                at(-500f, -500f, 5000f, 5000f), 1000f, 2000f, 20f);
        assertEquals(520f, delta[0], E);
        assertEquals(520f, delta[1], E);
    }

    @Test public void nothingToRevealMovesNothing() {
        float[] none = DocumentPageMapping.panToReveal(null, 1000f, 2000f, 24f);
        assertEquals(0f, none[0], E);
        assertEquals(0f, none[1], E);
        float[] noView = DocumentPageMapping.panToReveal(at(0f, 0f, 1f, 1f), 0f, 0f, 24f);
        assertEquals(0f, noView[0], E);
        assertEquals(0f, noView[1], E);
    }
}
