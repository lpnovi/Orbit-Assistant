package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The grid's arithmetic, asserted on real measured bounds rather than on a screenshot.
 *
 * <p>A pixel comparison would break on every accent change and prove nothing about the thing that
 * actually goes wrong in a hand-written {@code ViewGroup}: tiles overlapping, a wide tile that is
 * not really two columns, a row that does not grow when its tallest child does, or a layout that
 * silently drops a child. Those are all properties of the laid-out rectangles, so that is what is
 * measured here.
 *
 * <p>Every child is given an explicit minimum height rather than text, because Robolectric measures
 * all text to zero width and never wraps it — a height assertion that depended on wrapping would
 * pass whether the layout worked or not.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckLayoutGeometryTest {

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
    }

    private View addTile(int span, int minHeight) {
        View child = new View(context);
        child.setMinimumHeight(minHeight);
        grid.addView(child, new DeckGridLayout.LayoutParams(span));
        return child;
    }

    private void layout() {
        grid.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, WIDTH, grid.getMeasuredHeight());
    }

    private static Rect boundsOf(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }

    private List<Rect> allBounds() {
        List<Rect> out = new ArrayList<>();
        for (View child : grid.orderedChildren()) out.add(boundsOf(child));
        return out;
    }

    /** No two tiles may share a pixel, at any column count or mix of sizes. */
    private void assertNoOverlaps() {
        List<Rect> bounds = allBounds();
        for (int i = 0; i < bounds.size(); i++) {
            for (int j = i + 1; j < bounds.size(); j++) {
                assertFalse("tiles " + i + " and " + j + " overlap: "
                                + bounds.get(i) + " / " + bounds.get(j),
                        Rect.intersects(bounds.get(i), bounds.get(j)));
            }
        }
    }

    // ---- columns and spans -------------------------------------------------------------------------

    @Test public void aPhoneGetsTwoColumnsAndATabletMore() {
        assertEquals("a phone", 2, DeckGridLayout.columnsForWidth(411));
        assertEquals("a large phone is still two", 2, DeckGridLayout.columnsForWidth(480));
        assertEquals("a tablet", 3, DeckGridLayout.columnsForWidth(800));
        assertEquals("the Tab S9 Plus in portrait", 3, DeckGridLayout.columnsForWidth(753));
        assertEquals("a wide landscape tablet", 4, DeckGridLayout.columnsForWidth(1280));
    }

    /**
     * A tile is about the same size on a phone and on a large tablet.
     *
     * <p>The capped content width is what makes that true: without it a wide tablet would keep the
     * same column count and simply stretch each tile, which is the difference between a grid that
     * looks designed and one that looks like a phone layout pulled sideways.
     */
    @Test public void aTileIsRoughlyTheSameSizeOnAPhoneAndATablet() {
        int cap = DeckActivity.MAX_CONTENT_WIDTH_DP;
        int sidePadding = 18 * 2;
        int spacingDp = 12;

        int phoneColumns = DeckGridLayout.columnsForWidth(480);
        int phoneTile = (480 - sidePadding - spacingDp * (phoneColumns - 1)) / phoneColumns;

        int tabletWidth = Math.min(1280, cap);
        int tabletColumns = DeckGridLayout.columnsForWidth(tabletWidth);
        int tabletTile = (tabletWidth - sidePadding - spacingDp * (tabletColumns - 1)) / tabletColumns;

        assertTrue("a tablet earns more columns", tabletColumns > phoneColumns);
        assertTrue("but the tiles stay a similar size, not stretched: "
                        + phoneTile + "dp vs " + tabletTile + "dp",
                Math.abs(tabletTile - phoneTile) < 60);
    }

    @Test public void standardTilesFillTheColumnsEvenly() {
        grid.setColumns(2);
        View a = addTile(1, 100);
        View b = addTile(1, 100);
        layout();

        assertEquals("both tiles are the same width", a.getWidth(), b.getWidth());
        assertEquals("and share a row", a.getTop(), b.getTop());
        assertTrue(a.getRight() < b.getLeft());
        assertNoOverlaps();
    }

    @Test public void aWideTileSpansTwoColumns() {
        grid.setColumns(2);
        View wide = addTile(2, 100);
        View standard = addTile(1, 100);
        layout();

        assertTrue("a wide tile is close to twice a standard one",
                wide.getWidth() > standard.getWidth() * 1.8);
        assertTrue("and starts a new row for the tile after it",
                standard.getTop() > wide.getTop());
        assertNoOverlaps();
    }

    /**
     * A wide tile never gets squeezed into a leftover column.
     *
     * <p>With one standard tile placed first there is a single column left in that row, so the wide
     * tile has to begin the next one rather than shrink.
     */
    @Test public void aWideTileThatDoesNotFitStartsTheNextRow() {
        grid.setColumns(2);
        View standard = addTile(1, 100);
        View wide = addTile(2, 100);
        layout();

        assertTrue(wide.getTop() > standard.getTop());
        assertTrue("and it is genuinely full width",
                wide.getWidth() > standard.getWidth() * 1.8);
        assertNoOverlaps();
    }

    @Test public void theDefaultDeckLaysOutWithoutOverlapOrGap() {
        grid.setColumns(2);
        for (DeckTile tile : DeckLayoutStore.defaults()) {
            addTile(tile.size == DeckTile.Size.WIDE ? 2 : 1, 100);
        }
        layout();

        assertEquals(7, grid.orderedChildren().size());
        assertNoOverlaps();
        // One wide plus six standard is four full rows, so the last row is not ragged.
        List<Rect> bounds = allBounds();
        int lastRowTop = bounds.get(bounds.size() - 1).top;
        int inLastRow = 0;
        for (Rect rect : bounds) if (rect.top == lastRowTop) inLastRow++;
        assertEquals("the final row is full", 2, inLastRow);
    }

    @Test public void aTabletUsesTheExtraWidthRatherThanStretchingTwoColumns() {
        grid.setColumns(2);
        for (int i = 0; i < 6; i++) addTile(1, 100);
        layout();
        int phoneWidth = grid.orderedChildren().get(0).getWidth();
        int phoneHeight = grid.getMeasuredHeight();

        grid.setColumns(4);
        layout();
        int tabletWidth = grid.orderedChildren().get(0).getWidth();

        assertTrue("tiles get narrower rather than enormous", tabletWidth < phoneWidth);
        assertTrue("and the grid gets shorter", grid.getMeasuredHeight() < phoneHeight);
        assertNoOverlaps();
    }

    // ---- growing rather than clipping ---------------------------------------------------------------

    /**
     * A row is as tall as its tallest tile.
     *
     * <p>This is the large-text guarantee in mechanical form: nothing in the grid caps a tile's
     * height, so a title that needs two lines makes its row taller instead of being cut off.
     */
    @Test public void aRowGrowsToItsTallestTile() {
        grid.setColumns(2);
        View shortTile = addTile(1, UiKit.dp(context, 100));
        View tallTile = addTile(1, UiKit.dp(context, 260));
        layout();

        assertEquals("both tiles fill the row's height",
                tallTile.getHeight(), shortTile.getHeight());
        assertTrue(shortTile.getHeight() >= UiKit.dp(context, 260));
        assertTrue("and the grid reports that height",
                grid.getMeasuredHeight() >= UiKit.dp(context, 260));
        assertNoOverlaps();
    }

    @Test public void theGridGrowsWithTheNumberOfTiles() {
        grid.setColumns(2);
        addTile(1, 100);
        addTile(1, 100);
        layout();
        int twoTiles = grid.getMeasuredHeight();

        for (int i = 0; i < 4; i++) addTile(1, 100);
        layout();
        assertTrue("six tiles are taller than two", grid.getMeasuredHeight() > twoTiles);
        assertNoOverlaps();
    }

    @Test public void everyTileStaysInsideTheGrid() {
        grid.setColumns(3);
        for (int i = 0; i < 7; i++) addTile(i == 0 ? 2 : 1, 100);
        layout();

        for (View child : grid.orderedChildren()) {
            assertTrue("a tile starts left of the grid", child.getLeft() >= 0);
            assertTrue("a tile runs past the right edge", child.getRight() <= WIDTH);
            assertTrue("a tile runs past the bottom",
                    child.getBottom() <= grid.getMeasuredHeight());
        }
        assertNoOverlaps();
    }

    /** A span wider than the grid is clamped rather than overflowing it. */
    @Test public void aSpanWiderThanTheGridIsClamped() {
        grid.setColumns(1);
        View wide = addTile(2, 100);
        layout();

        assertTrue(wide.getRight() <= WIDTH);
        assertNoOverlaps();
    }

    // ---- reordering ---------------------------------------------------------------------------------

    @Test public void reorderingChangesPositionsAndKeepsEveryTile() {
        grid.setColumns(2);
        View first = addTile(1, 100);
        View second = addTile(1, 100);
        layout();
        int firstLeft = first.getLeft();

        List<View> ordered = grid.orderedChildren();
        assertEquals(first, ordered.get(0));
        assertEquals(second, ordered.get(1));

        grid.removeView(first);
        grid.addView(first, new DeckGridLayout.LayoutParams(1));
        layout();

        assertEquals("both tiles survive", 2, grid.orderedChildren().size());
        assertEquals(second, grid.orderedChildren().get(0));
        assertTrue("and the moved tile actually moved", first.getLeft() != firstLeft
                || first.getTop() != 0);
        assertNoOverlaps();
    }

    @Test public void aRemovedTileLeavesNoHole() {
        grid.setColumns(2);
        addTile(1, 100);
        View middle = addTile(1, 100);
        View last = addTile(1, 100);
        layout();

        grid.removeView(middle);
        layout();

        assertEquals(2, grid.orderedChildren().size());
        assertEquals("the tile after it moves up into the gap", 0, last.getTop());
        assertNoOverlaps();
    }

    @Test public void goneChildrenTakeNoSpace() {
        grid.setColumns(2);
        View hidden = addTile(1, 100);
        View visible = addTile(1, 100);
        hidden.setVisibility(ViewGroup.GONE);
        layout();

        assertEquals("the visible tile takes the first slot", 0, visible.getLeft());
        assertEquals(0, visible.getTop());
    }

    /**
     * Carries one tile onto another, the way a finger does.
     *
     * <p>The pointer is put at the destination tile's centre because that is where the middle of a
     * carried card actually is when it covers a neighbour. Aiming two pixels inside an edge — which
     * is what these drags used to do — describes a finger hovering on a seam, and a seam is
     * deliberately no longer enough to move anything.
     */
    private void dragOnto(View carried, View destination) {
        grid.updateDrag(
                destination.getLeft() + destination.getWidth() / 2f
                        - (carried.getLeft() + carried.getWidth() / 2f),
                destination.getTop() + destination.getHeight() / 2f
                        - (carried.getTop() + carried.getHeight() / 2f));
    }

    @Test public void draggingFourthBeforeSecondCreatesARealProvisionalOrder() {
        grid.setColumns(2);
        View a = addTile(1, 100);
        View b = addTile(1, 100);
        View c = addTile(1, 100);
        View d = addTile(1, 100);
        layout();

        grid.beginDrag(d);
        dragOnto(d, b);
        layout();

        assertEquals(java.util.Arrays.asList(a, d, b, c), grid.orderedChildren());
        assertTrue(grid.endDrag());
        layout();
        assertNoOverlaps();
    }

    @Test public void cancellationRestoresTheCommittedOrderAndDoesNotCommit() {
        grid.setColumns(2);
        View a = addTile(1, 100);
        View b = addTile(1, 100);
        View c = addTile(1, 100);
        View d = addTile(1, 100);
        layout();
        final int[] commits = {0};
        grid.setOnReorderListener(ordered -> commits[0]++);

        grid.beginDrag(d);
        dragOnto(d, b);
        layout();
        assertEquals(java.util.Arrays.asList(a, d, b, c), grid.orderedChildren());

        grid.cancelDrag();
        layout();
        assertEquals(java.util.Arrays.asList(a, b, c, d), grid.orderedChildren());
        assertEquals(0, commits[0]);
        assertNoOverlaps();
    }

    @Test public void aStandardTileCanCrossAWideBoundaryWithoutOverlap() {
        grid.setColumns(2);
        View wide = addTile(2, 100);
        View b = addTile(1, 100);
        View c = addTile(1, 100);
        View d = addTile(1, 100);
        View e = addTile(1, 100);
        layout();

        grid.beginDrag(b);
        dragOnto(b, wide);
        layout();

        assertEquals(java.util.Arrays.asList(b, wide, c, d, e), grid.orderedChildren());
        grid.endDrag();
        layout();
        assertNoOverlaps();
        assertTrue("the wide card remains a full-row span", wide.getWidth() > b.getWidth() * 1.8f);
    }

    /** A finger resting on the seam between two tiles belongs to neither, so nothing moves. */
    @Test public void hoveringOnASeamDoesNotReorder() {
        grid.setColumns(2);
        View a = addTile(1, 100);
        View b = addTile(1, 100);
        View c = addTile(1, 100);
        View d = addTile(1, 100);
        layout();

        grid.beginDrag(d);
        grid.updateDrag(b.getLeft() + 2f - (d.getLeft() + d.getWidth() / 2f),
                b.getTop() + b.getHeight() / 2f - (d.getTop() + d.getHeight() / 2f));
        layout();
        assertEquals("a seam hover is not a reorder",
                java.util.Arrays.asList(a, b, c, d), grid.orderedChildren());
        assertFalse("and nothing was committed", grid.endDrag());
    }
}
