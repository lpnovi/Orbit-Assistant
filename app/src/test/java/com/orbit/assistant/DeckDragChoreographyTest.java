package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What the Deck grid must be true of at every moment of a drag, not only at the end.
 *
 * <p>Beta 2 reordered correctly and still looked wrong: neighbours appeared to overlap, a card
 * could seem to be in two places at once, and the reflow snapped rather than flowed. An
 * end-state-only test cannot catch any of that, because the end state was already right. So these
 * assert the invariant during the gesture — after every pointer event the provisional grid must be
 * exactly one valid arrangement, with one slot per tile, no overlaps, and nothing appearing twice.
 *
 * <p>Deliberately not screenshots. What went wrong was layout state, and layout state is what is
 * measured; a pixel comparison would break on every accent change while proving nothing about
 * whether two tiles believed they owned the same cell.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckDragChoreographyTest {

    private static final int WIDTH = 1080;

    private Context context;
    private DeckGridLayout grid;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
        grid = new DeckGridLayout(context);
        grid.setSpacing(UiKit.dp(context, 12));
        grid.setMinRowHeight(UiKit.dp(context, 100));
        grid.setColumns(2);
    }

    private View addTile(int span) {
        View child = new View(context);
        child.setMinimumHeight(100);
        grid.addView(child, new DeckGridLayout.LayoutParams(span));
        return child;
    }

    private void layout() {
        grid.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, WIDTH, grid.getMeasuredHeight());
    }

    /** Long enough for any reflow or settle animation to have finished. */
    private void finishAnimations() {
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
    }

    /** Where the finger went down, so later moves are offsets from there rather than from now. */
    private float startX;
    private float startY;

    private void beginDrag(View carried) {
        startX = carried.getLeft() + carried.getWidth() / 2f;
        startY = carried.getTop() + carried.getHeight() / 2f;
        grid.beginDrag(carried);
    }

    /**
     * Carries the tile onto another's provisional slot and re-lays, checking the invariant.
     *
     * <p>The offset is measured from where the gesture began, because that is what a drag reports —
     * a finger's total travel, not its travel since the grid last reflowed. Measuring from the
     * carried tile's current position instead would shrink every move after the first, which says
     * nothing about the grid and everything about the test.
     */
    private void dragOnto(View destination) {
        float[] centre = grid.slotCenterForTest(destination);
        assertTrue("the destination should have a slot", centre != null);
        grid.updateDrag(centre[0] - startX, centre[1] - startY);
        assertProvisionalGridIsOneArrangement();
        layout();
        assertProvisionalGridIsOneArrangement();
    }

    /**
     * The invariant. One valid ordering, always.
     *
     * <p>No two tiles in the same cell, nothing missing, nothing counted twice, and no impossible
     * geometry — including the wide-tile case, where the failure would be another card packed
     * beneath a span that owns the whole row.
     */
    private void assertProvisionalGridIsOneArrangement() {
        List<View> ordered = grid.orderedChildren();
        Set<View> unique = new HashSet<>(ordered);
        assertEquals("no tile may appear twice in the provisional order",
                ordered.size(), unique.size());
        assertTrue("the provisional grid must be one valid arrangement: "
                + grid.occupancyForTest(), grid.occupancyIsValidForTest());
        assertEquals("every tile must have exactly one slot",
                ordered.size(), grid.occupancyForTest().size());
    }

    private void assertNoOverlappingBounds() {
        List<Rect> bounds = new ArrayList<>();
        for (View child : grid.orderedChildren()) {
            bounds.add(new Rect(child.getLeft(), child.getTop(),
                    child.getRight(), child.getBottom()));
        }
        for (int i = 0; i < bounds.size(); i++) {
            for (int j = i + 1; j < bounds.size(); j++) {
                assertFalse("laid-out tiles " + i + " and " + j + " overlap: "
                                + bounds.get(i) + " / " + bounds.get(j),
                        Rect.intersects(bounds.get(i), bounds.get(j)));
            }
        }
    }

    // ---- rapid reordering -------------------------------------------------------------------------

    /** Several adjacent moves in one gesture, with no layout pass between some of them. */
    @Test public void rapidAdjacentReorderStaysOneValidArrangement() {
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();

        beginDrag(d);
        dragOnto(c);
        dragOnto(b);
        dragOnto(a);
        assertEquals(Arrays.asList(d, a, b, c), grid.orderedChildren());
        assertTrue(grid.endDrag());
        layout();
        finishAnimations();
        assertNoOverlappingBounds();
        assertTrue(grid.everyIdleTileIsSettledForTest());
    }

    /**
     * Pointer events arriving faster than layout passes.
     *
     * <p>This is the window the old hit test could not survive: it asked the children where they
     * were, and between a reorder and the layout that followed, the children had not moved yet.
     */
    @Test public void severalPointerEventsBetweenLayoutPassesStayCoherent() {
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();

        beginDrag(d);
        float[] target = grid.slotCenterForTest(b);
        float dx = target[0] - (d.getLeft() + d.getWidth() / 2f);
        float dy = target[1] - (d.getTop() + d.getHeight() / 2f);
        // Three moves, no layout in between at all.
        grid.updateDrag(dx * 0.4f, dy * 0.4f);
        grid.updateDrag(dx * 0.7f, dy * 0.7f);
        grid.updateDrag(dx, dy);
        assertProvisionalGridIsOneArrangement();
        layout();
        assertProvisionalGridIsOneArrangement();
        assertEquals(Arrays.asList(a, d, b, c), grid.orderedChildren());
    }

    /** Going one way and immediately back must land where it started, not somewhere between. */
    @Test public void reversingDirectionMidDragReturnsTheOriginalOrder() {
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();

        beginDrag(a);
        dragOnto(b);
        assertEquals(Arrays.asList(b, a, c, d), grid.orderedChildren());
        dragOnto(b);
        assertEquals("reversing puts it back", Arrays.asList(a, b, c, d),
                grid.orderedChildren());
        assertFalse("and nothing was actually reordered", grid.endDrag());
        layout();
        finishAnimations();
        assertTrue(grid.everyIdleTileIsSettledForTest());
    }

    /** Many moves in one edit session leave no accumulated drift. */
    @Test public void repeatedReorderingInOneSessionLeavesNoResidue() {
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();

        for (int round = 0; round < 6; round++) {
            View carried = grid.orderedChildren().get(3);
            View destination = grid.orderedChildren().get(0);
            beginDrag(carried);
            dragOnto(destination);
            grid.endDrag();
            layout();
            finishAnimations();
            assertProvisionalGridIsOneArrangement();
            assertTrue("round " + round + " left a tile off its slot",
                    grid.everyIdleTileIsSettledForTest());
        }
        Set<View> unique = new HashSet<>(grid.orderedChildren());
        assertEquals("every tile survives every round", 4, unique.size());
        assertTrue(unique.containsAll(Arrays.asList(a, b, c, d)));
        assertNoOverlappingBounds();
    }

    // ---- wide tiles -------------------------------------------------------------------------------

    /**
     * The Wide A / B C / D E arrangement, moved through in every direction.
     *
     * <p>A wide tile owns its whole row, so the failure this guards against is another card packed
     * beside or beneath that span — a grid state that cannot be laid out and therefore never
     * appears cleanly on screen.
     */
    @Test public void standardTilesMoveAroundAWideTileWithoutInvalidOccupancy() {
        View wide = addTile(2);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        View e = addTile(1);
        layout();

        // A standard tile from below the wide card to above it.
        beginDrag(e);
        dragOnto(wide);
        assertEquals(Arrays.asList(e, wide, b, c, d), grid.orderedChildren());
        grid.endDrag();
        layout();
        finishAnimations();
        assertNoOverlappingBounds();

        // And back down past it, into the standard rows.
        beginDrag(e);
        dragOnto(wide);
        dragOnto(b);
        grid.endDrag();
        layout();
        finishAnimations();
        assertProvisionalGridIsOneArrangement();
        assertNoOverlappingBounds();
        assertTrue("the wide card is still a full-row span",
                wide.getWidth() > b.getWidth() * 1.8f);
    }

    /** The wide tile itself moving between standard rows. */
    @Test public void aWideTileMovesBetweenStandardRowsWithoutInvalidOccupancy() {
        View wide = addTile(2);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        View e = addTile(1);
        layout();

        beginDrag(wide);
        dragOnto(b);
        dragOnto(d);
        grid.endDrag();
        layout();
        finishAnimations();
        assertProvisionalGridIsOneArrangement();
        assertNoOverlappingBounds();
        assertTrue("a wide tile stays two columns wherever it lands",
                wide.getWidth() > c.getWidth() * 1.8f);
        assertEquals(5, grid.orderedChildren().size());
        assertEquals(5, new HashSet<>(grid.orderedChildren()).size());
        assertTrue(grid.orderedChildren().containsAll(Arrays.asList(wide, b, c, d, e)));
    }

    /** No tile is ever packed into the row a wide tile occupies. */
    @Test public void nothingIsEverPlacedInsideAWideTilesRow() {
        View wide = addTile(2);
        View b = addTile(1);
        View c = addTile(1);
        layout();

        beginDrag(c);
        dragOnto(wide);
        List<Rect> occupancy = grid.occupancyForTest();
        Rect wideSlot = occupancy.get(grid.orderedChildren().indexOf(wide));
        for (int i = 0; i < occupancy.size(); i++) {
            if (grid.orderedChildren().get(i) == wide) continue;
            assertFalse("a tile shares the wide card's row: " + occupancy.get(i),
                    Rect.intersects(wideSlot, occupancy.get(i)));
        }
        assertEquals("the wide card spans the full content width",
                WIDTH, wideSlot.width());
    }

    // ---- drop and cancel --------------------------------------------------------------------------

    /** A drop commits once, and only when the order actually changed. */
    @Test public void dropCommitsExactlyOnceAndOnlyWhenSomethingMoved() {
        View a = addTile(1);
        View b = addTile(1);
        addTile(1);
        addTile(1);
        layout();
        final List<List<View>> commits = new ArrayList<>();
        grid.setOnReorderListener(commits::add);

        beginDrag(a);
        dragOnto(b);
        assertTrue(grid.endDrag());
        assertEquals("one commit for one drop", 1, commits.size());
        assertEquals(grid.orderedChildren(), commits.get(0));

        // A drag that goes nowhere writes nothing.
        beginDrag(a);
        grid.updateDrag(1f, 1f);
        assertFalse(grid.endDrag());
        assertEquals("a drag that changed nothing commits nothing", 1, commits.size());
    }

    /** After the settle, no tile carries residual translation into the next gesture. */
    @Test public void everyTileEndsExactlyOnItsSlotAfterADrop() {
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        addTile(1);
        layout();

        beginDrag(c);
        dragOnto(a);
        grid.endDrag();
        layout();
        finishAnimations();
        for (View child : grid.orderedChildren()) {
            assertEquals("residual translationX on " + child, 0f, child.getTranslationX(), 0.001f);
            assertEquals("residual translationY on " + child, 0f, child.getTranslationY(), 0.001f);
        }
        assertTrue(grid.everyIdleTileIsSettledForTest());
        assertNoOverlappingBounds();
        assertTrue(grid.orderedChildren().containsAll(Arrays.asList(a, b, c)));
    }

    /** A cancelled drag restores the committed order and leaves nothing behind. */
    @Test public void cancellingRestoresEverythingCleanly() {
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();
        final int[] commits = {0};
        grid.setOnReorderListener(ordered -> commits[0]++);

        beginDrag(d);
        dragOnto(b);
        dragOnto(a);
        grid.cancelDrag();
        layout();
        finishAnimations();

        assertEquals(Arrays.asList(a, b, c, d), grid.orderedChildren());
        assertEquals("cancelling never persists", 0, commits[0]);
        assertEquals("no tile is lost", 4, grid.orderedChildren().size());
        assertEquals("and none is duplicated", 4, new HashSet<>(grid.orderedChildren()).size());
        assertTrue(grid.everyIdleTileIsSettledForTest());
        assertFalse(grid.isDragging());
        assertNoOverlappingBounds();
    }

    /** Cancelling part-way through a reflow animation still settles cleanly. */
    @Test public void cancellingMidAnimationStillSettles() {
        View a = addTile(1);
        addTile(1);
        addTile(1);
        View d = addTile(1);
        layout();

        beginDrag(d);
        dragOnto(a);
        // No finishAnimations() here: the reflow is deliberately still in flight.
        grid.cancelDrag();
        layout();
        finishAnimations();
        assertTrue(grid.everyIdleTileIsSettledForTest());
        assertProvisionalGridIsOneArrangement();
        assertNoOverlappingBounds();
    }

    // ---- what a drag must not disturb -------------------------------------------------------------

    /** The carried tile is lifted out of its slot, and that slot stays reserved for it. */
    @Test public void theCarriedTileKeepsExactlyOneReservedSlot() {
        View a = addTile(1);
        View b = addTile(1);
        addTile(1);
        addTile(1);
        layout();

        beginDrag(a);
        grid.updateDrag(300f, 200f);
        assertProvisionalGridIsOneArrangement();
        assertEquals("the carried tile is still in the order exactly once",
                1, java.util.Collections.frequency(grid.orderedChildren(), a));
        assertTrue("and it is translated away from that slot",
                Math.abs(a.getTranslationX()) > 1f || Math.abs(a.getTranslationY()) > 1f);
        assertEquals("while its neighbour sits still", 0f, b.getTranslationX(), 0.001f);
        grid.cancelDrag();
        layout();
        finishAnimations();
    }

    /** Removing a tile mid-drag cannot leave the grid holding a detached view. */
    @Test public void removingTheCarriedTileEndsTheDragSafely() {
        View a = addTile(1);
        addTile(1);
        layout();

        beginDrag(a);
        grid.removeView(a);
        assertFalse("the drag is over", grid.isDragging());
        layout();
        assertProvisionalGridIsOneArrangement();
        assertEquals(1, grid.orderedChildren().size());
    }
}
