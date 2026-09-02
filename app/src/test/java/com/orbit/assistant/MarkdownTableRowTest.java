package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Every cell in a Markdown table row occupies that row's height.
 *
 * <p>The defect this file was written for was plainly visible on a real device. Cells were laid
 * out at their own content height, so a row whose four columns held different amounts of text came
 * out as four cards of four different heights — each cell's background and border stopping where
 * its own words did, with the assistant bubble showing through underneath the shorter ones. On a
 * purple bubble the eye read purple gutters inside what was supposed to be one row.
 *
 * <p>The properties asserted here are structural and relative rather than pixel values: cells
 * within a row agree, rows do not have to agree with one another, and no height is fixed, so a row
 * still grows with its content and with the chat text-size preference.
 *
 * <h2>Why the tall cells are made tall with minimum heights</h2>
 *
 * <p>This suite runs without a real font: every string measures to zero width, so no wording makes
 * a cell wrap and every cell would come out exactly one line tall. An equal-height assertion built
 * on long text would therefore pass whether or not the fix were present. A minimum height is
 * honoured by measurement regardless of font metrics, so it reproduces the situation the device
 * showed — several cells in one row each wanting a different height — and lets the rule that
 * resolves it be asserted for what it is.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class MarkdownTableRowTest {

    /** A realistic phone width, so the table is laid out in the room a phone actually has. */
    private static final int PHONE_WIDTH_DP = 411;

    private Context context;
    private int fill;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
        fill = UiKit.assistantBubbleFill(context, UiKit.SURFACE);
    }

    /**
     * A cell asks for the height of its row, not its own.
     *
     * <p>This is the whole fix, and it is a layout rule rather than a measurement: a horizontal
     * {@link android.widget.LinearLayout} whose own height wraps already re-measures its
     * {@code MATCH_PARENT} children against the tallest of them. Asserting the intent as well as
     * the outcome is what stops it being quietly undone by a later edit that "tidies" the layout
     * params back to {@code WRAP_CONTENT}.
     */
    @Test public void everyCellAsksForTheHeightOfItsRow() {
        TableLayout table = table("| A | B | C |\n| --- | --- | --- |\n| 1 | 2 | 3 |");
        for (TableRow row : rows(table)) {
            for (int i = 0; i < row.getChildCount(); i++) {
                assertEquals("a cell must take its row's height",
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        row.getChildAt(i).getLayoutParams().height);
            }
        }
    }

    /** Cells in one row end up the same height once laid out, however uneven their content. */
    @Test public void cellsInARowShareOneHeight() {
        TableLayout table = table(fourColumnTable());
        List<TableRow> rows = rows(table);
        wantHeights(rows.get(1), 40, 80, 160, 60);
        layOut(table);

        List<Integer> heights = cellHeights(rows.get(1));
        assertEquals(4, heights.size());
        for (int height : heights) {
            assertEquals("every cell must reach the height of the tallest in its row: " + heights,
                    160, height);
        }
    }

    /**
     * Equal height is a rule within a row, never across the table.
     *
     * <p>A short header row is allowed to stay short above a tall body row; levelling the whole
     * table to its tallest row would trade one ugly table for another.
     */
    @Test public void rowsKeepTheirOwnHeights() {
        TableLayout table = table(fourColumnTable());
        List<TableRow> rows = rows(table);
        assertEquals(3, rows.size());
        wantHeights(rows.get(0), 70, 70, 70, 70);
        wantHeights(rows.get(1), 90, 130, 240, 110);
        wantHeights(rows.get(2), 80, 100, 80, 80);
        layOut(table);

        for (int height : cellHeights(rows.get(1))) assertEquals(240, height);
        for (int height : cellHeights(rows.get(2))) assertEquals(100, height);
        assertEquals("a short header row stays short",
                70, cellHeights(rows.get(0)).get(0).intValue());
        assertTrue("and rows are not levelled against each other",
                cellHeights(rows.get(1)).get(0) > cellHeights(rows.get(2)).get(0));
    }

    /** Row height still comes from content: a row that needs more room gets more room. */
    @Test public void rowHeightStillFollowsItsContent() {
        TableLayout table = table(fourColumnTable());
        wantHeights(rows(table).get(1), 40, 80, 160, 60);
        layOut(table);
        int before = cellHeights(rows(table).get(1)).get(0);

        wantHeights(rows(table).get(1), 40, 80, 300, 60);
        layOut(table);
        int after = cellHeights(rows(table).get(1)).get(0);

        assertEquals("no row height is hardcoded", 160, before);
        assertEquals(300, after);
        assertTrue("a row grows with what is in it", after > before);
    }

    /** Nothing about the horizontal overflow model changed: a wide table still scrolls. */
    @Test public void theTableStillScrollsHorizontally() {
        View rendered = OrbitRichResponseRenderer.render(context, fourColumnTable(), fill, false);
        List<View> scrollers = new ArrayList<>();
        for (View view : descendants(rendered)) {
            if (view instanceof HorizontalScrollView) scrollers.add(view);
        }
        assertEquals("the table keeps exactly one horizontal scroller", 1, scrollers.size());
        HorizontalScrollView scroll = (HorizontalScrollView) scrollers.get(0);
        assertTrue(scroll.isHorizontalScrollBarEnabled());
        assertTrue("with the table inside it", scroll.getChildAt(0) instanceof TableLayout);
        assertEquals("Markdown table", String.valueOf(scroll.getContentDescription()));
    }

    /** Cells keep the width bounds and header styling that make a table readable. */
    @Test public void cellWidthsAndHeaderStylingAreUnchanged() {
        TableLayout table = table(fourColumnTable());
        float scale = Prefs.chatTextScale(context);
        for (TableRow row : rows(table)) {
            for (int i = 0; i < row.getChildCount(); i++) {
                TextView cell = (TextView) row.getChildAt(i);
                assertEquals(UiKit.dp(context, Math.round(104 * scale)), cell.getMinWidth());
                assertEquals(UiKit.dp(context, Math.round(220 * scale)), cell.getMaxWidth());
                assertEquals("a cell's width is still its own",
                        ViewGroup.LayoutParams.WRAP_CONTENT, cell.getLayoutParams().width);
                assertTrue("and every cell still has its border",
                        cell.getBackground() instanceof android.graphics.drawable.GradientDrawable);
            }
        }
        assertTrue("the header row keeps its own fill",
                fillOf(rows(table).get(0).getChildAt(0)) != fillOf(rows(table).get(1).getChildAt(0)));
    }

    private static int fillOf(View cell) {
        android.content.res.ColorStateList colors =
                ((android.graphics.drawable.GradientDrawable) cell.getBackground()).getColor();
        return colors == null ? 0 : colors.getDefaultColor();
    }

    /** Inline Markdown inside a cell survives, including the combined emphasis fixed alongside. */
    @Test public void inlineMarkupInsideCellsStillRenders() {
        TableLayout table = table("| Name | Note |\n| --- | --- |\n"
                + "| **bold** | ***both*** and `code` |");
        TextView first = (TextView) rows(table).get(1).getChildAt(0);
        TextView second = (TextView) rows(table).get(1).getChildAt(1);
        assertEquals("bold", first.getText().toString());
        assertEquals("both and code", second.getText().toString());
    }

    /** The Side-button overlay builds its tables through the same path, so the rule holds there. */
    @Test public void theOverlayGetsTheSameRowGeometry() {
        View overlay = OrbitRichResponseRenderer.render(context, fourColumnTable(), fill, true);
        TableLayout table = null;
        for (View view : descendants(overlay)) {
            if (view instanceof TableLayout) table = (TableLayout) view;
        }
        assertTrue("the overlay still draws a table", table != null);
        for (TableRow row : rows(table)) {
            for (int i = 0; i < row.getChildCount(); i++) {
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                        row.getChildAt(i).getLayoutParams().height);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** A four-column table with a header, one body row, and a second, shorter body row. */
    private static String fourColumnTable() {
        return "| One | Two | Three | Four |\n"
                + "| --- | --- | --- | --- |\n"
                + "| alpha | beta | gamma | delta |\n"
                + "| a | b | c | d |";
    }

    private TableLayout table(String markdown) {
        View rendered = OrbitRichResponseRenderer.render(context, markdown, fill, false);
        for (View view : descendants(rendered)) {
            if (view instanceof TableLayout) return (TableLayout) view;
        }
        throw new AssertionError("no table was rendered for: " + markdown);
    }

    /** Gives each cell of a row a different height to want, as differing wrapped text would. */
    private static void wantHeights(TableRow row, int... heights) {
        for (int i = 0; i < heights.length && i < row.getChildCount(); i++) {
            View cell = row.getChildAt(i);
            cell.setMinimumHeight(heights[i]);
            if (cell instanceof TextView) ((TextView) cell).setMinHeight(heights[i]);
        }
    }

    /** Measures and lays the table out in the room a phone actually has. */
    private void layOut(TableLayout table) {
        View root = table;
        while (root.getParent() instanceof View) root = (View) root.getParent();
        int width = UiKit.dp(context, PHONE_WIDTH_DP);
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        root.layout(0, 0, width, root.getMeasuredHeight());
    }

    private static List<TableRow> rows(TableLayout table) {
        List<TableRow> out = new ArrayList<>();
        for (int i = 0; i < table.getChildCount(); i++) {
            if (table.getChildAt(i) instanceof TableRow) out.add((TableRow) table.getChildAt(i));
        }
        return out;
    }

    /** The drawn height of each cell in a row — the height its background and border cover. */
    private static List<Integer> cellHeights(TableRow row) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) out.add(row.getChildAt(i).getHeight());
        return out;
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                out.addAll(descendants(group.getChildAt(i)));
            }
        }
        return out;
    }
}
