package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Exact geometry checks for Orbit's shared anchored popup placement. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitPopupPositionTest {

    @Test public void colorMenuAlignsItsRightEdgeAndOpensBelowWhenItFits() {
        Rect frame = new Rect(0, 72, 1080, 2280);
        Rect anchor = new Rect(60, 300, 1020, 450);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, 630, 600, 36, 18, 1980, true, true);

        assertEquals("trailing edges stay anchored", anchor.right, popup.right);
        assertEquals("the popup keeps the requested row gap", anchor.bottom + 18, popup.top);
        assertTrue(popup.left >= frame.left + 36);
        assertTrue(popup.bottom <= 1980 - 36);
    }

    @Test public void longColorMenuFlipsAboveWhenItDoesNotFitBelow() {
        Rect frame = new Rect(0, 72, 1080, 2280);
        Rect anchor = new Rect(60, 1450, 1020, 1600);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, 630, 600, 36, 18, 1980, true, true);

        assertEquals(anchor.top - 18, popup.bottom);
        assertEquals(anchor.right, popup.right);
        assertTrue("the fixed action bar is unavailable", popup.bottom <= 1980 - 36);
    }

    @Test public void shortColorMenuStaysCloseToItsSourceRow() {
        Rect frame = new Rect(0, 72, 1080, 2280);
        Rect anchor = new Rect(60, 1200, 1020, 1350);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, 630, 180, 36, 18, 1980, true, true);

        assertEquals(anchor.bottom + 18, popup.top);
        assertEquals(anchor.right, popup.right);
    }

    @Test public void actionBarBoundaryForcesEvenAShortMenuAbove() {
        Rect frame = new Rect(0, 72, 1080, 2280);
        Rect anchor = new Rect(60, 1700, 1020, 1850);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, 630, 180, 36, 18, 1980, true, true);

        assertEquals(anchor.top - 18, popup.bottom);
        assertTrue(popup.bottom <= 1980 - 36);
    }

    @Test public void popupIsClampedInsideANarrowInsetWindow() {
        Rect frame = new Rect(8, 90, 328, 760);
        Rect anchor = new Rect(20, 200, 316, 260);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, 300, 500, 12, 6, 700, true, true);

        assertEquals(20, popup.left);
        assertEquals(316, popup.right);
        assertTrue(popup.top >= 102);
        assertTrue(popup.bottom <= 688);
    }

    @Test public void existingOrbitMenusKeepTheirCenteredAnchorPolicy() {
        Rect frame = new Rect(0, 72, 1080, 2280);
        Rect anchor = new Rect(500, 300, 700, 450);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, 360, 400, 36, 18, frame.bottom, false, false);

        assertEquals(anchor.centerX(), popup.centerX());
        assertEquals(anchor.bottom + 18, popup.top);
    }
}
