package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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

    /** A phone-shaped Theme Studio window with a fixed action bar starting at 1980. */
    private static Rect phoneFrame() {
        return new Rect(0, 72, 1080, 2280);
    }

    @Test public void colorMenuCentersOnItsContentPaneAndOpensBelowWhenItFits() {
        Rect frame = phoneFrame();
        Rect content = new Rect(frame);
        // A colour row runs the width of the card, so its own centre is not the reference.
        Rect anchor = new Rect(400, 300, 1020, 450);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, content, 630, 600, 36, 18, 1980, true);

        assertEquals("the menu is centred on the content pane", content.centerX(), popup.centerX());
        assertNotEquals("and not dragged toward the row that opened it",
                anchor.centerX(), popup.centerX());
        assertEquals("with visually balanced side margins",
                popup.left - frame.left, frame.right - popup.right);
        assertEquals("the popup keeps the requested row gap", anchor.bottom + 18, popup.top);
        assertTrue(popup.bottom <= 1980 - 36);
    }

    @Test public void everyColorRowOpensTheMenuInTheSamePlaceHorizontally() {
        Rect frame = phoneFrame();
        Rect content = new Rect(frame);

        // A long palette and a narrower two-row menu, opened from rows at different heights.
        Rect fromAccent = UiKit.anchoredOrbitPopupBounds(
                frame, new Rect(60, 300, 1020, 450), content, 630, 600, 36, 18, 1980, true);
        Rect fromCards = UiKit.anchoredOrbitPopupBounds(
                frame, new Rect(400, 900, 1020, 1050), content, 420, 180, 36, 18, 1980, true);

        assertEquals("a long and a short menu share one horizontal centre",
                fromAccent.centerX(), fromCards.centerX());
        assertEquals("each keeps balanced side margins",
                fromCards.left - frame.left, frame.right - fromCards.right);
        assertEquals("and the wider one keeps its requested width", 630, fromAccent.width());
    }

    @Test public void longColorMenuFlipsAboveWhenItDoesNotFitBelow() {
        Rect frame = phoneFrame();
        Rect content = new Rect(frame);
        Rect anchor = new Rect(400, 1450, 1020, 1600);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, content, 630, 600, 36, 18, 1980, true);

        assertEquals(anchor.top - 18, popup.bottom);
        assertEquals("flipping above does not move it sideways", content.centerX(), popup.centerX());
        assertTrue("the fixed action bar is unavailable", popup.bottom <= 1980 - 36);
    }

    @Test public void shortColorMenuStaysCloseToItsSourceRow() {
        Rect frame = phoneFrame();
        Rect content = new Rect(frame);
        Rect anchor = new Rect(400, 1200, 1020, 1350);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, content, 630, 180, 36, 18, 1980, true);

        assertEquals(anchor.bottom + 18, popup.top);
        assertEquals(content.centerX(), popup.centerX());
    }

    @Test public void actionBarBoundaryForcesEvenAShortMenuAbove() {
        Rect frame = phoneFrame();
        Rect content = new Rect(frame);
        Rect anchor = new Rect(400, 1700, 1020, 1850);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, content, 630, 180, 36, 18, 1980, true);

        assertEquals(anchor.top - 18, popup.bottom);
        assertTrue(popup.bottom <= 1980 - 36);
    }

    @Test public void centeringNeverPushesThePopupPastTheLeadingMargin() {
        Rect frame = phoneFrame();
        Rect content = new Rect(0, 72, 300, 2280);
        Rect anchor = new Rect(20, 300, 280, 450);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, content, 630, 600, 36, 18, 1980, true);

        assertEquals("the leading clamp wins over centring", frame.left + 36, popup.left);
        assertTrue(popup.right <= frame.right - 36);
    }

    @Test public void centeringNeverPushesThePopupPastTheTrailingMargin() {
        Rect frame = phoneFrame();
        Rect content = new Rect(900, 72, 1080, 2280);
        Rect anchor = new Rect(920, 300, 1060, 450);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, content, 630, 600, 36, 18, 1980, true);

        assertEquals("the trailing clamp wins over centring", frame.right - 36, popup.right);
        assertTrue(popup.left >= frame.left + 36);
    }

    @Test public void popupIsClampedInsideANarrowInsetWindow() {
        Rect frame = new Rect(8, 90, 328, 760);
        Rect anchor = new Rect(20, 200, 316, 260);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, new Rect(frame), 300, 500, 12, 6, 700, true);

        assertEquals(20, popup.left);
        assertEquals(316, popup.right);
        assertTrue(popup.top >= 102);
        assertTrue(popup.bottom <= 688);
    }

    /**
     * A tablet keeps its two-pane layout: the controls live in the right pane, so the menu belongs
     * centred in that pane. Centring it across the whole window would leave it floating over the
     * preview, far from the row that opened it.
     */
    @Test public void wideLayoutCentersTheMenuInTheContentPaneNotTheWholeWindow() {
        Rect frame = new Rect(0, 72, 2400, 1600);
        Rect content = new Rect(1200, 150, 2350, 1500);
        Rect anchor = new Rect(1240, 300, 2310, 420);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, content, 630, 600, 36, 18, 1450, true);

        assertEquals(content.centerX(), popup.centerX());
        assertNotEquals(frame.centerX(), popup.centerX());
        assertTrue("it stays inside its own pane", popup.left >= content.left);
        assertTrue(popup.right <= content.right);
        assertEquals(anchor.bottom + 18, popup.top);
    }

    @Test public void existingOrbitMenusKeepTheirCenteredAnchorPolicy() {
        Rect frame = phoneFrame();
        Rect anchor = new Rect(500, 300, 700, 450);

        Rect popup = UiKit.anchoredOrbitPopupBounds(
                frame, anchor, null, 360, 400, 36, 18, frame.bottom, false);

        assertEquals(anchor.centerX(), popup.centerX());
        assertEquals(anchor.bottom + 18, popup.top);
    }
}
