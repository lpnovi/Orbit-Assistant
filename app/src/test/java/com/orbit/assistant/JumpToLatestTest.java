package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Jump to latest is the inverse of the existing near-bottom follow rule, so the control cannot
 * appear while full chat is still treating the newest messages as on screen.
 */
public final class JumpToLatestTest {
    private static final int SLOP = 96;

    @Test public void aShortConversationNeverNeedsTheControl() {
        assertTrue(JumpToLatest.nearBottom(400, 800, 0, SLOP));
        assertFalse(JumpToLatest.shouldShow(400, 800, 0, SLOP));
        assertTrue(JumpToLatest.nearBottom(800, 800, 0, SLOP));
        assertFalse(JumpToLatest.shouldShow(800, 800, 0, SLOP));
    }

    @Test public void theLatestMessagesHideTheControl() {
        int content = 2000;
        int viewport = 800;
        int bottom = content - viewport;
        assertTrue(JumpToLatest.nearBottom(content, viewport, bottom, SLOP));
        assertFalse(JumpToLatest.shouldShow(content, viewport, bottom, SLOP));
        assertTrue(JumpToLatest.nearBottom(content, viewport, bottom - SLOP, SLOP));
        assertFalse(JumpToLatest.shouldShow(content, viewport, bottom - SLOP, SLOP));
    }

    @Test public void scrollingUpFarEnoughShowsTheControl() {
        int content = 2000;
        int viewport = 800;
        int bottom = content - viewport;
        assertTrue(JumpToLatest.shouldShow(content, viewport, bottom - SLOP - 1, SLOP));
        assertTrue(JumpToLatest.shouldShow(content, viewport, 0, SLOP));
        assertTrue(JumpToLatest.shouldShow(content, viewport, 400, SLOP));
    }

    @Test public void returningToTheBottomHidesItAgain() {
        int content = 2000;
        int viewport = 800;
        int bottom = content - viewport;
        assertTrue(JumpToLatest.shouldShow(content, viewport, 0, SLOP));
        assertFalse(JumpToLatest.shouldShow(content, viewport, bottom, SLOP));
    }

    @Test public void aNegativeSlopCannotForceTheControlOnAtTheBottom() {
        assertFalse(JumpToLatest.shouldShow(2000, 800, 1200, -40));
        assertTrue(JumpToLatest.nearBottom(2000, 800, 1200, -40));
    }

    @Test public void slopMatchesTheExistingFollowDistance() {
        assertEquals(0, JumpToLatest.slopPx(null));
    }
}
