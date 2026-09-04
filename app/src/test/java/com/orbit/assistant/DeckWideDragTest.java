package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.concurrent.TimeUnit;

/**
 * What must be true while a full-span tile is being carried, not only once it lands.
 *
 * <p>The defect these exist for was invisible to an end-state test and invisible to an overlap
 * test. Dragging the wide New chat tile through the standard rows produced provisional
 * arrangements shaped like "Routines and an empty cell" above the wide tile above "Reminders and
 * Memories" — no two rectangles overlapping, every tile present exactly once, and the final drop
 * correct. It was still wrong: a cell had been vacated mid-grid, a standard pair had been split
 * across two rows, and the arrangement the cards animated towards was not the one the finger had
 * asked for.
 *
 * <p>So these assert cell occupancy rather than pixels: which cells each tile claims after every
 * pointer event, that a tile always claims its whole declared span, and that a standard pair
 * leaves a row together in one provisional calculation rather than one card at a time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckWideDragTest {

    private static final int WIDTH = 1080;

    private Context context;
    private DeckGridLayout grid;
    private float startX;
    private float startY;

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

    private void finishAnimations() {
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
    }

    private void beginDrag(View carried) {
        startX = carried.getLeft() + carried.getWidth() / 2f;
        startY = carried.getTop() + carried.getHeight() / 2f;
        grid.beginDrag(carried);
    }

    /** Moves the finger to a point in the grid, reported as total travel since the press. */
    private void moveTo(float x, float y) {
        grid.updateDrag(x - startX, y - startY);
        assertValid("during the move");
        layout();
        assertValid("after the layout pass");
    }

    /** Carries the tile onto the centre of another tile's current provisional slot. */
    private void dragOnto(View destination) {
        float[] centre = grid.slotCenterForTest(destination);
        assertNotNull("the destination should have a slot", centre);
        moveTo(centre[0], centre[1]);
    }

    // ---- invariants -------------------------------------------------------------------------------

    private void assertValid(String when) {
        List<View> ordered = grid.orderedChildren();
        assertEquals("a tile appears twice " + when,
                ordered.size(), new HashSet<>(ordered).size());
        assertEquals("every tile must hold exactly one slot " + when,
                ordered.size(), grid.occupancyForTest().size());
        assertTrue("rectangles overlap " + when + ": " + grid.occupancyForTest(),
                grid.occupancyIsValidForTest());
        assertTrue("cells are claimed twice or a span runs off the grid " + when + ": "
                + describe(), grid.spanPlacementIsValidForTest());
    }

    /** Asserts every tile declaring this span claims its full width, wherever it sits. */
    private void assertEverySpanIsWhole(int span) {
        List<View> ordered = grid.orderedChildren();
        List<int[]> placements = grid.placementForTest();
        for (int i = 0; i < ordered.size(); i++) {
            int declared = ((DeckGridLayout.LayoutParams) ordered.get(i).getLayoutParams()).span;
            if (declared != span) continue;
            assertEquals("a wide tile must keep its declared span: " + describe(),
                    Math.min(span, grid.columns()), placements.get(i)[2]);
            assertTrue("a wide tile must start on a column boundary that fits it: " + describe(),
                    placements.get(i)[1] + placements.get(i)[2] <= grid.columns());
        }
    }

    /** The row each tile occupies, in display order — the grid's shape, without pixels. */
    private List<Integer> rows() {
        List<Integer> out = new ArrayList<>();
        for (int[] placement : grid.placementForTest()) out.add(placement[0]);
        return out;
    }

    private int countInRow(int row) {
        int count = 0;
        for (int[] placement : grid.placementForTest()) if (placement[0] == row) count++;
        return count;
    }

    private int rowOf(View child) {
        return grid.placementForTest().get(grid.orderedChildren().indexOf(child))[0];
    }

    /**
     * No cell is vacated above the carried tile.
     *
     * <p>This is the defect stated as an invariant. Inserting a wide tile beside one half of a
     * standard pair pushed it onto a row of its own and left the pair's row half empty, with the
     * carried card sitting over the hole — a grid the user never asked for, which the cards then
     * animated towards. Every row above the carried tile has to be complete.
     *
     * <p>The one exemption is a tile carried to the very end of the order, where a grid holding an
     * odd number of standard tiles genuinely ends on a short row. Nothing was displaced to make it.
     */
    private void assertNoVacatedCellAbove(View carried) {
        List<View> ordered = grid.orderedChildren();
        if (ordered.indexOf(carried) == ordered.size() - 1) return;
        List<int[]> placements = grid.placementForTest();
        int carriedRow = placements.get(ordered.indexOf(carried))[0];
        int[] filled = new int[carriedRow + 1];
        for (int[] placement : placements) {
            if (placement[0] < carriedRow) filled[placement[0]] += placement[2];
        }
        for (int row = 0; row < carriedRow; row++) {
            assertEquals("row " + row + " was left short above the carried tile: " + describe(),
                    grid.columns(), filled[row]);
        }
    }

    private String describe() {
        StringBuilder text = new StringBuilder();
        List<int[]> placements = grid.placementForTest();
        for (int i = 0; i < placements.size(); i++) {
            int[] placement = placements.get(i);
            text.append('[').append(i).append(" r").append(placement[0])
                    .append(" c").append(placement[1])
                    .append(" x").append(placement[2]).append(']');
        }
        return text.toString();
    }

    /** The centre of a whole logical row of the current provisional grid. */
    private float[] rowCentre(int row) {
        List<Rect> rects = grid.occupancyForTest();
        List<int[]> placements = grid.placementForTest();
        for (int i = 0; i < placements.size(); i++) {
            if (placements.get(i)[0] != row) continue;
            Rect rect = rects.get(i);
            return new float[]{WIDTH / 2f, rect.top + rect.height() / 2f};
        }
        return null;
    }

    // ---- the reported defect ----------------------------------------------------------------------

    /**
     * The Galaxy S25 Ultra case: the wide tile above three standard rows, moved down one row.
     *
     * <p>Every state in between has to be a grid somebody could have arranged deliberately.
     */
    @Test public void wideTileMovesFromTopToMiddleReservingWholeRows() {
        View wide = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        View e = addTile(1);
        View f = addTile(1);
        layout();
        assertEquals(Arrays.asList(0, 1, 1, 2, 2, 3, 3), rows());

        beginDrag(wide);
        float[] centre = rowCentre(1);
        moveTo(centre[0], centre[1]);

        assertEquals("the wide tile takes the whole row it was carried onto",
                Arrays.asList(a, b, wide, c, d, e, f), grid.orderedChildren());
        assertEquals("and every row is whole", Arrays.asList(0, 0, 1, 2, 2, 3, 3), rows());
        assertEquals("the wide tile is alone in its row", 1, countInRow(1));
        assertEverySpanIsWhole(2);
        grid.endDrag();
        layout();
        finishAnimations();
        assertValid("after the drop");
    }

    /** All the way to the bottom, then all the way back, one row per step. */
    @Test public void wideTileWalksToTheBottomAndBackWithoutAnInvalidState() {
        View wide = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        View e = addTile(1);
        View f = addTile(1);
        layout();

        beginDrag(wide);
        float[] centre = rowCentre(1);
        moveTo(centre[0], centre[1]);
        assertEquals(Arrays.asList(a, b, wide, c, d, e, f), grid.orderedChildren());
        centre = rowCentre(2);
        moveTo(centre[0], centre[1]);
        assertEquals(Arrays.asList(a, b, c, d, wide, e, f), grid.orderedChildren());
        centre = rowCentre(3);
        moveTo(centre[0], centre[1]);
        assertEquals("the wide tile reaches the last row",
                Arrays.asList(a, b, c, d, e, f, wide), grid.orderedChildren());
        assertEquals(Arrays.asList(0, 0, 1, 1, 2, 2, 3), rows());
        assertEverySpanIsWhole(2);

        centre = rowCentre(2);
        moveTo(centre[0], centre[1]);
        assertEquals(Arrays.asList(a, b, c, d, wide, e, f), grid.orderedChildren());
        centre = rowCentre(1);
        moveTo(centre[0], centre[1]);
        assertEquals(Arrays.asList(a, b, wide, c, d, e, f), grid.orderedChildren());
        centre = rowCentre(0);
        moveTo(centre[0], centre[1]);
        assertEquals("reversing returns it to the top",
                Arrays.asList(wide, a, b, c, d, e, f), grid.orderedChildren());
        assertFalse("and nothing was actually reordered", grid.endDrag());
        layout();
        finishAnimations();
        assertTrue(grid.everyIdleTileIsSettledForTest());
    }

    /**
     * A finger sweeping continuously, with a pointer event every few pixels.
     *
     * <p>Not one event per row: the invariant has to survive the events in between, which is where
     * the old model produced arrangements nobody had asked for and the grid animated towards them
     * anyway.
     */
    @Test public void continuousSweepThroughEveryRowKeepsEveryProvisionalStateValid() {
        View wide = addTile(2);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        layout();
        int height = grid.getMeasuredHeight();

        beginDrag(wide);
        for (int y = 0; y <= height; y += 9) {
            moveTo(WIDTH / 2f, y);
            assertEverySpanIsWhole(2);
            assertNoVacatedCellAbove(wide);
            assertEquals("the wide tile is never sharing a row", 1, countInRow(rowOf(wide)));
        }
        assertEquals("a full sweep down puts it last",
                grid.orderedChildren().size() - 1, grid.orderedChildren().indexOf(wide));
        for (int y = height; y >= 0; y -= 9) {
            moveTo(WIDTH / 2f, y);
            assertEverySpanIsWhole(2);
        }
        assertEquals("and sweeping back returns it to the top",
                0, grid.orderedChildren().indexOf(wide));
        grid.endDrag();
        layout();
        finishAnimations();
        assertValid("after the sweep");
    }

    /** Row one, row two, row three, back to two, back to one — in one uninterrupted gesture. */
    @Test public void repeatedThresholdCrossingNeverInventsAnInvalidRow() {
        View wide = addTile(2);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        layout();

        beginDrag(wide);
        for (int round = 0; round < 3; round++) {
            for (int row : new int[]{1, 2, 3, 2, 1, 0}) {
                float[] centre = rowCentre(row);
                assertNotNull("row " + row + " should exist", centre);
                moveTo(centre[0], centre[1]);
                assertEverySpanIsWhole(2);
            }
            assertEquals("round " + round + " left the wide tile adrift",
                    0, grid.orderedChildren().indexOf(wide));
        }
        grid.cancelDrag();
        layout();
        finishAnimations();
        assertValid("after cancelling");
    }

    /**
     * A tiny wobble around a row boundary must not alternate between two rows.
     *
     * <p>The hysteresis is the same inset containment the standard path uses. A finger in the
     * gutter between two rows is inside neither destination, so the grid holds what it has.
     */
    @Test public void wobblingOnARowBoundaryDoesNotAlternate() {
        View wide = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();
        int boundary = grid.occupancyForTest().get(0).bottom;

        beginDrag(wide);
        for (int round = 0; round < 8; round++) {
            moveTo(WIDTH / 2f, boundary - 2);
            assertEquals("a wobble above the seam moved the tile",
                    Arrays.asList(wide, a, b, c, d), grid.orderedChildren());
            moveTo(WIDTH / 2f, boundary + 2);
            assertEquals("a wobble below the seam moved the tile",
                    Arrays.asList(wide, a, b, c, d), grid.orderedChildren());
        }
        assertFalse("a wobble is not a reorder", grid.endDrag());
        layout();
        finishAnimations();
        assertTrue(grid.everyIdleTileIsSettledForTest());
    }

    /**
     * Both halves of a standard pair leave together.
     *
     * <p>The visible symptom was one card of a pair moving while the other stayed put beneath the
     * carried tile. That is not an animation problem — it is what a provisional order splitting the
     * pair actually described. So the assertion is on the arrangement: the moment the wide tile
     * claims a row, neither of the two tiles that were in it is still there, and they are still
     * together.
     */
    @Test public void aStandardPairVacatesTheRowTogetherInOneCalculation() {
        View wide = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        addTile(1);
        addTile(1);
        layout();

        beginDrag(wide);
        // Onto the left-hand card of the pair, which is where a finger actually is. Choosing the
        // neighbour under it is precisely what used to strand its partner a row below.
        dragOnto(a);

        assertNoVacatedCellAbove(wide);
        assertEquals("the pair stays a pair", rowOf(a), rowOf(b));
        assertFalse("neither half may be left in the wide tile's row", rowOf(a) == rowOf(wide));
        assertEquals("and nothing else is in that row either", 1, countInRow(rowOf(wide)));
        grid.endDrag();
        layout();
        finishAnimations();
        assertValid("after the pair moved");
    }

    /**
     * Carried down the left-hand column, which is where a finger really travels.
     *
     * <p>Sweeping the middle of the grid is a softer test than it looks: it is over the seam
     * between two standard cards, so the old model declined to act on it at all. Following one
     * column puts the finger squarely on individual standard tiles the whole way down, which is the
     * path that used to strand their partners.
     */
    @Test public void carryingTheWideTileDownOneColumnNeverVacatesACell() {
        View wide = addTile(2);
        View a = addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        layout();
        float column = a.getWidth() / 2f;

        beginDrag(wide);
        int height = grid.getMeasuredHeight();
        for (int y = 0; y <= height; y += 17) {
            moveTo(column, y);
            assertEverySpanIsWhole(2);
            assertNoVacatedCellAbove(wide);
            assertEquals("the wide tile never shares a row", 1, countInRow(rowOf(wide)));
            List<Rect> rects = grid.occupancyForTest();
            for (int i = 0; i < rects.size(); i++) {
                for (int j = i + 1; j < rects.size(); j++) {
                    assertFalse("provisional slots overlap at y=" + y + ": " + describe(),
                            Rect.intersects(rects.get(i), rects.get(j)));
                }
            }
        }
        grid.endDrag();
        layout();
        finishAnimations();
        assertTrue("the carried tile never collapsed to a standard width",
                wide.getWidth() > a.getWidth() * 1.4f);
    }

    // ---- pickup, drop, cancel ---------------------------------------------------------------------

    /** Picking a wide tile up reserves its row and lifts only it. */
    @Test public void pickingUpAWideTileReservesItsWholeRowAndLiftsOneCard() {
        View wide = addTile(2);
        View a = addTile(1);
        addTile(1);
        layout();

        beginDrag(wide);
        grid.updateDrag(40f, 30f);
        layout();
        assertValid("while lifted");
        assertTrue("the carried tile follows the finger",
                Math.abs(wide.getTranslationX()) > 1f || Math.abs(wide.getTranslationY()) > 1f);
        assertEquals("the tiles it left behind do not move", 0f, a.getTranslationY(), 0.001f);
        assertEquals("its row is reserved for it exactly once",
                1, java.util.Collections.frequency(grid.orderedChildren(), wide));
        assertEquals("and nothing was packed into it", 1, countInRow(rowOf(wide)));
        grid.cancelDrag();
        layout();
        finishAnimations();
    }

    /** A drop commits the span-aware order once, and the tile settles on its full span. */
    @Test public void droppingAWideTileCommitsOnceAndSettlesOnItsWholeRow() {
        View wide = addTile(2);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        layout();
        final List<List<View>> commits = new ArrayList<>();
        grid.setOnReorderListener(commits::add);

        beginDrag(wide);
        float[] centre = rowCentre(1);
        moveTo(centre[0], centre[1]);
        centre = rowCentre(2);
        moveTo(centre[0], centre[1]);
        assertTrue(grid.endDrag());
        layout();
        finishAnimations();

        assertEquals("one commit for one drop", 1, commits.size());
        assertEquals(grid.orderedChildren(), commits.get(0));
        assertEquals("no residual translation", 0f, wide.getTranslationX(), 0.001f);
        assertEquals("no residual translation", 0f, wide.getTranslationY(), 0.001f);
        assertTrue(grid.everyIdleTileIsSettledForTest());
        assertEverySpanIsWhole(2);
        assertEquals("the wide tile owns its row alone", 1, countInRow(rowOf(wide)));
    }

    /** Cancelling a wide drag restores the committed order exactly and persists nothing. */
    @Test public void cancellingAWideDragRestoresTheCommittedRow() {
        View wide = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();
        final int[] commits = {0};
        grid.setOnReorderListener(ordered -> commits[0]++);

        beginDrag(wide);
        float[] centre = rowCentre(1);
        moveTo(centre[0], centre[1]);
        centre = rowCentre(2);
        moveTo(centre[0], centre[1]);
        grid.cancelDrag();
        layout();
        finishAnimations();

        assertEquals("the committed order is restored",
                Arrays.asList(wide, a, b, c, d), grid.orderedChildren());
        assertEquals("cancelling never persists", 0, commits[0]);
        assertEquals("no tile is lost", 5, grid.orderedChildren().size());
        assertEquals("and none is duplicated", 5, new HashSet<>(grid.orderedChildren()).size());
        assertFalse(grid.isDragging());
        assertTrue(grid.everyIdleTileIsSettledForTest());
        assertEquals("the wide tile is back on a row of its own", 1, countInRow(0));
        assertValid("after cancelling");
    }

    /** Cancelling part-way through a reflow still settles every tile on a whole cell. */
    @Test public void cancellingAWideDragMidAnimationStillSettles() {
        View wide = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();

        beginDrag(wide);
        float[] centre = rowCentre(2);
        moveTo(centre[0], centre[1]);
        // No finishAnimations() here: the reflow is deliberately still in flight.
        grid.cancelDrag();
        layout();
        finishAnimations();
        assertEquals(Arrays.asList(wide, a, b, c, d), grid.orderedChildren());
        assertTrue(grid.everyIdleTileIsSettledForTest());
        assertValid("after cancelling mid-animation");
    }

    // ---- more than one wide tile ------------------------------------------------------------------

    /** Two full-span tiles in one grid, one carried the length of the other. */
    @Test public void aWideTileDraggedPastAnotherWideTileStaysValid() {
        View first = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        View second = addTile(2);
        View c = addTile(1);
        View d = addTile(1);
        layout();
        assertEquals(Arrays.asList(0, 1, 1, 2, 3, 3), rows());

        beginDrag(first);
        int height = grid.getMeasuredHeight();
        for (int y = 0; y <= height; y += 11) {
            moveTo(WIDTH / 2f, y);
            assertNoVacatedCellAbove(first);
            assertEverySpanIsWhole(2);
            assertFalse("two wide tiles must never share a row: " + describe(),
                    rowOf(first) == rowOf(second));
            assertEquals("the carried wide tile is alone in its row",
                    1, countInRow(rowOf(first)));
            assertEquals("and so is the other one", 1, countInRow(rowOf(second)));
        }
        assertTrue(grid.endDrag());
        layout();
        finishAnimations();
        assertValid("after passing another wide tile");
        assertEquals("every tile survives", 6, new HashSet<>(grid.orderedChildren()).size());
        assertTrue(grid.orderedChildren().containsAll(Arrays.asList(first, a, b, second, c, d)));
    }

    /** Dragging the lower of two wide tiles up past the upper one. */
    @Test public void theSecondWideTileCanBeCarriedAboveTheFirst() {
        View first = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        View second = addTile(2);
        addTile(1);
        addTile(1);
        layout();

        beginDrag(second);
        float[] centre = rowCentre(1);
        moveTo(centre[0], centre[1]);
        assertEverySpanIsWhole(2);
        centre = rowCentre(0);
        moveTo(centre[0], centre[1]);
        assertEquals("it takes the very top row", 0, grid.orderedChildren().indexOf(second));
        assertEquals("and the other wide tile keeps a row of its own",
                1, countInRow(rowOf(first)));
        assertTrue(grid.endDrag());
        layout();
        finishAnimations();
        assertValid("after two wide tiles swapped");
        assertTrue(grid.orderedChildren().containsAll(Arrays.asList(first, second, a, b)));
    }

    // ---- responsive grids -------------------------------------------------------------------------

    /**
     * A tablet, where Wide is a declared span of two out of three columns rather than a whole row.
     *
     * <p>The fix has to respect the declared span, not a two-column phone's coincidence that a span
     * of two happens to fill a row. On three columns a wide tile legitimately shares a row with one
     * standard tile, and must still never be squeezed, split, or overlapped.
     */
    @Test public void onAThreeColumnGridAWideTileKeepsItsDeclaredSpanWithoutOwningTheRow() {
        grid.setColumns(3);
        View wide = addTile(2);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        layout();

        beginDrag(wide);
        int height = grid.getMeasuredHeight();
        for (int y = 0; y <= height; y += 13) {
            for (int x = 60; x < WIDTH; x += 120) {
                moveTo(x, y);
                assertEverySpanIsWhole(2);
            }
        }
        grid.endDrag();
        layout();
        finishAnimations();
        assertValid("on a three-column grid");
        assertEquals("the grid is still three columns", 3, grid.columns());
        assertTrue("a two-span tile is narrower than the whole row", wide.getWidth() < WIDTH);
        assertTrue("but wider than one column", wide.getWidth() > WIDTH / 2.5f);
        assertTrue("and it may legitimately share its row on a tablet",
                countInRow(rowOf(wide)) >= 1);
    }

    /** Four columns, the widest responsive Deck. */
    @Test public void onAFourColumnGridAWideTileStillClaimsExactlyTwoCells() {
        grid.setColumns(4);
        View wide = addTile(2);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        layout();

        beginDrag(wide);
        int height = grid.getMeasuredHeight();
        for (int y = 0; y <= height; y += 13) {
            for (int x = 60; x < WIDTH; x += 150) {
                moveTo(x, y);
                assertEverySpanIsWhole(2);
            }
        }
        grid.cancelDrag();
        layout();
        finishAnimations();
        assertValid("on a four-column grid");
        assertTrue(grid.everyIdleTileIsSettledForTest());
    }

    /** One column: every tile is a full row already, and a wide span is clamped to one. */
    @Test public void onASingleColumnGridAWideTileBehavesLikeAnyOther() {
        grid.setColumns(1);
        View wide = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        layout();

        beginDrag(wide);
        float[] centre = rowCentre(1);
        moveTo(centre[0], centre[1]);
        assertValid("on a single column");
        centre = rowCentre(2);
        moveTo(centre[0], centre[1]);
        grid.endDrag();
        layout();
        finishAnimations();
        assertValid("after a single-column drop");
        assertEquals(3, grid.orderedChildren().size());
        assertTrue(grid.orderedChildren().containsAll(Arrays.asList(wide, a, b)));
    }

    // ---- odd tile counts --------------------------------------------------------------------------

    /** A grid whose standard tiles do not divide evenly still lets the wide tile reach the end. */
    @Test public void aWideTileReachesTheBottomOfAnUnevenGrid() {
        View wide = addTile(2);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        addTile(1);
        layout();

        beginDrag(wide);
        int height = grid.getMeasuredHeight();
        for (int y = 0; y <= height + 200; y += 15) {
            moveTo(WIDTH / 2f, y);
            assertEverySpanIsWhole(2);
        }
        assertEquals("the wide tile ends up last",
                grid.orderedChildren().size() - 1, grid.orderedChildren().indexOf(wide));
        assertTrue(grid.endDrag());
        layout();
        finishAnimations();
        assertValid("at the bottom of an uneven grid");
        assertEquals("and it still owns a whole row", 1, countInRow(rowOf(wide)));
    }

    // ---- the standard path, unchanged --------------------------------------------------------------

    /**
     * Dragging a one-column tile still uses the neighbour model, index for index.
     *
     * <p>Standard dragging was validated on the device before this change and must not have been
     * retuned by it, so this pins the exact orders the neighbour model produces — including the
     * ones a span-aware model would produce differently. If a later change to the wide path leaks
     * into the standard path, this fails.
     */
    @Test public void draggingAStandardTileStillUsesTheNeighbourModelExactly() {
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();

        beginDrag(d);
        dragOnto(c);
        assertEquals(Arrays.asList(a, b, d, c), grid.orderedChildren());
        dragOnto(b);
        assertEquals(Arrays.asList(a, d, b, c), grid.orderedChildren());
        dragOnto(a);
        assertEquals(Arrays.asList(d, a, b, c), grid.orderedChildren());
        assertTrue(grid.endDrag());
        layout();
        finishAnimations();
        assertTrue(grid.everyIdleTileIsSettledForTest());
    }

    /**
     * And still uses it in a grid that contains a wide tile.
     *
     * <p>Including the arrangement a standard tile can legitimately produce by landing above a wide
     * one: a half-filled first row. That is the neighbour model's own behaviour, it was on the
     * device and accepted, and the span-aware path deliberately does not reach it.
     */
    @Test public void draggingAStandardTilePastAWideTileIsUnchanged() {
        View wide = addTile(2);
        View a = addTile(1);
        View b = addTile(1);
        View c = addTile(1);
        View d = addTile(1);
        layout();

        beginDrag(d);
        dragOnto(b);
        assertEquals(Arrays.asList(wide, a, d, b, c), grid.orderedChildren());
        dragOnto(wide);
        assertEquals("a standard tile may still be placed above the wide one",
                Arrays.asList(d, wide, a, b, c), grid.orderedChildren());
        assertTrue(grid.endDrag());
        layout();
        finishAnimations();
        assertTrue(grid.everyIdleTileIsSettledForTest());
        assertTrue("the wide tile still spans two columns", wide.getWidth() > a.getWidth() * 1.4f);
        assertEquals("and still owns its row alone", 1, countInRow(rowOf(wide)));
    }
}
