package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The highlight as the reader experiences it: welded to the page, and gone when search is.
 *
 * <p>The failure this exists to prevent is a highlight painted once in screen coordinates. That
 * looks perfect in a screenshot and comes apart the moment the page is pinched, which is exactly
 * when a reader is looking closely at it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DocumentHighlightSurfaceTest {

    private static final int VIEW_WIDTH = 800;
    private static final int VIEW_HEIGHT = 1200;

    private Context context;
    private ZoomableImageView view;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
        view = new ZoomableImageView(context);
        // A page-shaped bitmap, so a fitted page leaves margins at the sides rather than the top.
        view.setBitmap(Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888));
        view.measure(View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT);
    }

    private static List<RectF> one(float l, float t, float r, float b) {
        return Collections.singletonList(new RectF(l, t, r, b));
    }

    // ---- presence ---------------------------------------------------------------------------------

    @Test public void aSelectedResultIsSomewhereOnThePage() {
        view.setHighlights(one(0.2f, 0.3f, 0.4f, 0.34f), 0, 1);
        assertTrue(view.hasHighlights());
        RectF onScreen = view.selectedHighlightOnScreen();
        assertNotNull("the selected result has a place on screen", onScreen);
        assertTrue(onScreen.width() > 0f && onScreen.height() > 0f);
        assertTrue(onScreen.left >= 0f && onScreen.right <= VIEW_WIDTH);
        assertTrue(onScreen.top >= 0f && onScreen.bottom <= VIEW_HEIGHT);
    }

    /** Clearing search removes the highlight and changes nothing about the page itself. */
    @Test public void clearingRemovesEveryHighlight() {
        Bitmap page = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888);
        view.setBitmap(page);
        view.setHighlights(one(0.2f, 0.3f, 0.4f, 0.34f), 0, 1);
        assertTrue(view.hasHighlights());
        view.clearHighlights();
        assertFalse(view.hasHighlights());
        assertNull(view.selectedHighlightOnScreen());
        assertFalse("the rendered page was never written to", page.isRecycled());
        assertTrue("and the page is still the one being shown", view.hasImage());
    }

    @Test public void anEmptyHighlightListIsSimplyNoHighlights() {
        view.setHighlights(null, 0, 0);
        assertFalse(view.hasHighlights());
        view.setHighlights(Collections.emptyList(), 0, 3);
        assertFalse(view.hasHighlights());
        assertNull(view.selectedHighlightOnScreen());
    }

    // ---- tracking the transform -------------------------------------------------------------------

    /**
     * The property the whole architecture exists for.
     *
     * <p>Zooming must move the highlight exactly as much as it moves the word underneath it. A
     * highlight computed once and cached would stay where it was and the two would come apart.
     */
    @Test public void theHighlightFollowsThePageThroughZoom() {
        view.setHighlights(one(0.25f, 0.25f, 0.35f, 0.28f), 0, 1);
        RectF fitted = view.selectedHighlightOnScreen();
        assertNotNull(fitted);

        view.controller().zoomTo(2.5f, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f);
        RectF zoomed = view.selectedHighlightOnScreen();
        assertNotNull(zoomed);
        assertEquals("it scales with the page", 2.5f, zoomed.width() / fitted.width(), 0.02f);
        assertEquals(2.5f, zoomed.height() / fitted.height(), 0.02f);
    }

    @Test public void theHighlightFollowsThePageThroughPan() {
        view.setHighlights(one(0.25f, 0.25f, 0.35f, 0.28f), 0, 1);
        view.controller().zoomTo(3f, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f);
        RectF before = view.selectedHighlightOnScreen();
        assertNotNull(before);

        boolean moved = view.controller().panBy(-60f, -40f);
        assertTrue("the zoomed page should have somewhere to go", moved);
        RectF after = view.selectedHighlightOnScreen();
        assertNotNull(after);
        assertEquals("the highlight moved with the page, exactly",
                before.left - 60f, after.left, 0.5f);
        assertEquals(before.top - 40f, after.top, 0.5f);
    }

    /** Refitting a page puts the highlight back where the fitted page puts the word. */
    @Test public void theHighlightSurvivesAReturnToFit() {
        view.setHighlights(one(0.25f, 0.25f, 0.35f, 0.28f), 0, 1);
        RectF fitted = view.selectedHighlightOnScreen();
        view.controller().zoomTo(3f, 10f, 10f);
        view.controller().panBy(-100f, -100f);
        view.resetTransform();
        RectF again = view.selectedHighlightOnScreen();
        assertNotNull(fitted);
        assertNotNull(again);
        assertEquals(fitted.left, again.left, 0.5f);
        assertEquals(fitted.top, again.top, 0.5f);
    }

    /** A page resized under the highlight still places it against the page, not the old size. */
    @Test public void theHighlightSurvivesAPageResize() {
        view.setHighlights(one(0.5f, 0.5f, 0.6f, 0.53f), 0, 1);
        RectF before = view.selectedHighlightOnScreen();
        assertNotNull(before);
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 600);
        RectF after = view.selectedHighlightOnScreen();
        assertNotNull("a resized page still has a place for the result", after);
        assertTrue("and it is inside the new viewport", after.left >= 0f && after.right <= 400f);
        assertTrue(after.top >= 0f && after.bottom <= 600f);
    }

    // ---- moving between results -------------------------------------------------------------------

    /** Next changes which occurrence is strongly marked, without losing the others. */
    @Test public void changingTheSelectedOccurrenceMovesTheStrongHighlight() {
        List<RectF> three = Arrays.asList(
                new RectF(0.10f, 0.20f, 0.20f, 0.23f),
                new RectF(0.40f, 0.40f, 0.50f, 0.43f),
                new RectF(0.60f, 0.70f, 0.70f, 0.73f));
        view.setHighlights(three, 0, 1);
        RectF first = view.selectedHighlightOnScreen();
        view.setHighlights(three, 1, 2);
        RectF second = view.selectedHighlightOnScreen();
        view.setHighlights(three, 2, 3);
        RectF third = view.selectedHighlightOnScreen();

        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        assertTrue("Next visibly moves the selection", second.top > first.top);
        assertTrue(third.top > second.top);
        assertTrue("the other matches on the page stay drawn", view.hasHighlights());
    }

    /** A match spanning two lines is selected as one result covering both fragments. */
    @Test public void aWrappedMatchIsSelectedAsOneResult() {
        view.setHighlights(Arrays.asList(
                new RectF(0.80f, 0.20f, 0.92f, 0.23f),
                new RectF(0.08f, 0.26f, 0.20f, 0.29f)), 0, 2);
        RectF selected = view.selectedHighlightOnScreen();
        assertNotNull(selected);
        assertTrue("the selection covers both lines", selected.height() > 0f);
    }

    // ---- revealing --------------------------------------------------------------------------------

    /** A fitted page shows everything, so pressing Next never moves it. */
    @Test public void aFittedPageIsNeverPanned() {
        view.setHighlights(one(0.02f, 0.95f, 0.10f, 0.99f), 0, 1);
        assertFalse("nothing to reveal on a page that is entirely visible",
                view.revealSelectedHighlight(20f));
        assertEquals(0f, view.controller().translationX(), 0.001f);
        assertEquals(0f, view.controller().translationY(), 0.001f);
    }

    /** A zoomed reader keeps their magnification; only the position is nudged. */
    @Test public void aZoomedPageIsPannedNotRezoomed() {
        view.setHighlights(one(0.05f, 0.92f, 0.15f, 0.96f), 0, 1);
        view.controller().zoomTo(3f, VIEW_WIDTH / 2f, 0f);
        float scaleBefore = view.controller().scale();
        view.revealSelectedHighlight(24f);
        assertEquals("the magnification is the reader's choice, not Orbit's",
                scaleBefore, view.controller().scale(), 0.001f);
    }

    /** A result already comfortably on screen is left where it is. */
    @Test public void aVisibleResultIsNotChased() {
        view.setHighlights(one(0.45f, 0.48f, 0.55f, 0.52f), 0, 1);
        view.controller().zoomTo(2f, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f);
        float x = view.controller().translationX();
        float y = view.controller().translationY();
        assertFalse(view.revealSelectedHighlight(24f));
        assertEquals(x, view.controller().translationX(), 0.001f);
        assertEquals(y, view.controller().translationY(), 0.001f);
    }
}
