package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Drag offset to sheet geometry for the overlay's pull-open gesture. These protect the mapping
 * that makes the sheet track the finger, contract again, and stop growing at the screen edge.
 */
public final class OverlayStretchTest {
    private static final int BASE = 240;
    private static final int MAX = 700;

    @Test public void atRestTheConversationKeepsItsRestingHeight() {
        assertEquals(BASE, OverlayStretch.stretchedHeight(BASE, MAX, 0f));
    }

    @Test public void upwardMovementGrowsTheConversationOneToOne() {
        // The top edge has to stay under the fingertip, so movement maps directly onto height.
        assertEquals(BASE + 40, OverlayStretch.stretchedHeight(BASE, MAX, -40f));
        assertEquals(BASE + 150, OverlayStretch.stretchedHeight(BASE, MAX, -150f));
        assertEquals(BASE + 300, OverlayStretch.stretchedHeight(BASE, MAX, -300f));
    }

    @Test public void growthStopsOnceThereIsNoRoomLeft() {
        assertEquals(MAX, OverlayStretch.stretchedHeight(BASE, MAX, -(MAX - BASE)));
        assertEquals(MAX, OverlayStretch.stretchedHeight(BASE, MAX, -5000f));
    }

    @Test public void downwardMovementReturnsToTheRestingHeight() {
        // Dragging back down has to contract the sheet rather than leaving it latched open.
        assertEquals(BASE, OverlayStretch.stretchedHeight(BASE, MAX, 30f));
        assertEquals(BASE, OverlayStretch.stretchedHeight(BASE, MAX, 400f));
    }

    @Test public void theGestureNeverLatchesAtAnIntermediateHeight() {
        // Up, further up, back down, up again: every step is a pure function of the current
        // offset, so nothing is retained from how far the finger previously travelled.
        assertEquals(BASE + 100, OverlayStretch.stretchedHeight(BASE, MAX, -100f));
        assertEquals(BASE + 320, OverlayStretch.stretchedHeight(BASE, MAX, -320f));
        assertEquals(BASE + 60, OverlayStretch.stretchedHeight(BASE, MAX, -60f));
        assertEquals(BASE, OverlayStretch.stretchedHeight(BASE, MAX, 10f));
        assertEquals(BASE + 200, OverlayStretch.stretchedHeight(BASE, MAX, -200f));
    }

    @Test public void heightIsMonotonicAcrossASlowPull() {
        int previous = OverlayStretch.stretchedHeight(BASE, MAX, 0f);
        for (int travel = 1; travel <= 600; travel++) {
            int height = OverlayStretch.stretchedHeight(BASE, MAX, -travel);
            assertTrue("height must never decrease while pulling up", height >= previous);
            assertTrue(height <= MAX);
            previous = height;
        }
        assertEquals(MAX, previous);
    }

    @Test public void aSheetWithNoRoomToGrowSimplyStaysPut() {
        assertEquals(BASE, OverlayStretch.stretchedHeight(BASE, BASE, -200f));
        assertEquals(BASE, OverlayStretch.stretchedHeight(BASE, BASE - 50, -200f));
    }

    @Test public void progressRunsFromRestingToFullyOpen() {
        assertEquals(0f, OverlayStretch.progress(BASE, MAX, BASE), 0.0001f);
        assertEquals(1f, OverlayStretch.progress(BASE, MAX, MAX), 0.0001f);
        assertEquals(0.5f, OverlayStretch.progress(BASE, MAX, BASE + (MAX - BASE) / 2), 0.01f);
    }

    @Test public void progressIsClampedAgainstOutOfRangeHeights() {
        assertEquals(0f, OverlayStretch.progress(BASE, MAX, BASE - 100), 0.0001f);
        assertEquals(1f, OverlayStretch.progress(BASE, MAX, MAX + 100), 0.0001f);
        // A degenerate range must not divide by zero.
        assertEquals(0f, OverlayStretch.progress(BASE, BASE, BASE), 0.0001f);
        assertEquals(1f, OverlayStretch.progress(BASE, BASE, BASE + 10), 0.0001f);
    }

    @Test public void cornersFlattenAsTheSheetApproachesFullHeight() {
        float atRest = OverlayStretch.cornerRadiusDp(0f);
        float halfway = OverlayStretch.cornerRadiusDp(0.5f);
        float open = OverlayStretch.cornerRadiusDp(1f);

        assertEquals(OverlayStretch.SHEET_CORNER_DP, atRest, 0.001f);
        assertTrue("rounding should ease away as it opens", halfway < atRest);
        assertTrue(open < halfway);
        assertTrue("a little rounding remains rather than snapping square", open >= 0f);
    }

    @Test public void cornerRadiusIsClampedForOutOfRangeProgress() {
        assertEquals(OverlayStretch.SHEET_CORNER_DP, OverlayStretch.cornerRadiusDp(-1f), 0.001f);
        assertEquals(OverlayStretch.cornerRadiusDp(1f), OverlayStretch.cornerRadiusDp(4f), 0.001f);
    }

    @Test public void maximumHeightLeavesRoomForTheRestOfTheSheet() {
        // 1800 tall screen, 400 of sheet that is not conversation, 16 of margins.
        assertEquals(1384, OverlayStretch.maxConversationHeight(1800, 400, 16, BASE));
    }

    @Test public void maximumHeightNeverFallsBelowTheRestingHeight() {
        // A short screen or a tall composer must not produce a negative or shrinking ceiling.
        assertEquals(BASE, OverlayStretch.maxConversationHeight(300, 400, 16, BASE));
        assertEquals(BASE, OverlayStretch.maxConversationHeight(0, 0, 0, BASE));
    }
}
