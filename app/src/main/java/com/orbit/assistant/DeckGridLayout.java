package com.orbit.assistant;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orbit's Deck grid: a column count, two tile widths, and rows that grow when text does.
 *
 * <p>Written rather than imported. Orbit builds every screen from plain Views and carries no
 * RecyclerView dependency, so bringing one in — plus an ItemTouchHelper — to lay out at most a few
 * dozen tiles would add a framework to the APK for a screen that does not need one. What Deck
 * actually needs is three things a general-purpose list does not give for free: a tile that spans
 * two columns, rows sized to their tallest child so a large font scale grows the grid instead of
 * clipping it, and a reorder that animates the tiles that did not move.
 *
 * <p>Row height is measured, never hardcoded. Each child is measured against its own span at an
 * unspecified height and the row takes the tallest, with a minimum so a short tile still reads as a
 * card. That is what makes the grid survive accessibility text sizes.
 *
 * <h2>How a drag stays coherent</h2>
 *
 * <p>Beta 2 could reorder correctly and still look wrong doing it: neighbours appeared to overlap,
 * a card could seem to be in two places, and the grid snapped rather than flowed. None of that was
 * a reordering bug. It was several pieces of state disagreeing about where a tile was.
 *
 * <p>So there is now exactly one provisional truth: {@link #order}. Everything visible is derived
 * from it, and nothing else decides where a tile belongs.
 *
 * <ol>
 *   <li>{@link #order} is the provisional arrangement, mutated at most once per pointer crossing.
 *   <li>{@link #slotsFor} packs that order into logical slots — the same span-aware packing the
 *       layout uses, so the grid a drag is tested against is the grid the user is looking at.
 *   <li>Every non-dragged tile animates towards exactly its slot, starting from where it actually
 *       is on screen rather than from where it was last laid out. That distinction is the whole
 *       fix for the snapping: a tile 60% of the way through one slide used to be teleported back
 *       to where that slide began before the next one started, which is what read as cards
 *       crossing through one another.
 *   <li>The dragged tile keeps its slot in the provisional order — the hole it will drop into is
 *       always reserved, and there is always exactly one — and is translated out of it to follow
 *       the finger. One strategy, not two: there is no overlay copy, so there is nothing that can
 *       look duplicated.
 * </ol>
 *
 * <p>Hit testing asks which provisional slot contains the finger, inset slightly, rather than which
 * card centre is nearest. Containment is self-stabilising: once a tile has moved, the finger is
 * inside its new slot, so jitter at a boundary cannot swap two neighbours back and forth.
 *
 * <h2>Why a wide tile is asked a different question</h2>
 *
 * <p>All of the above holds for tiles one column wide, and Beta 3 made those feel right. It does
 * not hold for a tile two columns wide, because it quietly assumes any order of tiles packs into a
 * full grid — true only when every tile is the same width. Choosing a neighbour for a wide tile
 * could produce an order the packer then laid out somewhere else entirely, leaving a hole where the
 * finger was and splitting a standard pair across two rows for the length of the gesture.
 *
 * <p>So a carried tile wider than one column is asked where it <em>fits</em> instead of who it is
 * beside: {@link #spanInsertionIndexAt} packs each insertion point the span can legally occupy and
 * chooses by where the tile would actually land. The provisional order is picked from arrangements
 * the layout has already produced, so what animates is always a real arrangement.
 */
public final class DeckGridLayout extends ViewGroup {

    /** Told when a drag has settled somewhere new, with the order it settled into. */
    public interface OnReorderListener {
        void onReorder(List<View> orderedChildren);
    }

    public static final class LayoutParams extends ViewGroup.LayoutParams {
        /** How many columns this tile occupies. Clamped to the grid's column count. */
        public int span = 1;

        public LayoutParams(int span) {
            super(MATCH_PARENT, WRAP_CONTENT);
            this.span = Math.max(1, span);
        }
    }

    /** How far inside a slot the finger must be before that slot claims the dragged tile. */
    private static final float SLOT_INSET = 0.18f;
    private static final long REFLOW_MS = 180L;

    private int columns = 2;
    private int spacing;
    private int minRowHeight;

    /** Where each child was last laid out, so a move can be animated from where it actually was. */
    private final Map<View, Point> lastPositions = new HashMap<>();
    /** Children in display order, which is the order the user arranged rather than child index. */
    private final List<View> order = new ArrayList<>();

    private View dragging;
    /** Desired centre of the carried view in this grid's coordinates. */
    private float dragCenterX;
    private float dragCenterY;
    private float dragStartCenterX;
    private float dragStartCenterY;
    /** The last committed order, captured once at pickup so cancellation can restore it exactly. */
    private final List<View> orderBeforeDrag = new ArrayList<>();
    private OnReorderListener reorderListener;
    private boolean animateNextLayout;

    public DeckGridLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        spacing = UiKit.dp(context, 12);
        minRowHeight = UiKit.dp(context, 96);
    }

    public void setColumns(int value) {
        int next = Math.max(1, value);
        if (next == columns) return;
        columns = next;
        requestLayout();
    }

    public int columns() { return columns; }

    public void setSpacing(int px) { spacing = Math.max(0, px); requestLayout(); }

    public void setMinRowHeight(int px) { minRowHeight = Math.max(0, px); requestLayout(); }

    public void setOnReorderListener(OnReorderListener listener) { reorderListener = listener; }

    // ---- children ---------------------------------------------------------------------------------

    @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        if (!order.contains(child)) {
            if (index >= 0 && index <= order.size()) order.add(index, child);
            else order.add(child);
        }
    }

    @Override public void removeView(View view) {
        if (view == dragging) dragging = null;
        order.remove(view);
        orderBeforeDrag.remove(view);
        lastPositions.remove(view);
        super.removeView(view);
    }

    @Override public void removeAllViews() {
        dragging = null;
        order.clear();
        orderBeforeDrag.clear();
        lastPositions.clear();
        super.removeAllViews();
    }

    /** The tiles in the order they are displayed. */
    public List<View> orderedChildren() {
        List<View> out = new ArrayList<>(order.size());
        for (View child : order) if (child.getParent() == this) out.add(child);
        return out;
    }

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams;
    }

    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(1);
    }

    // ---- the provisional grid ---------------------------------------------------------------------

    /** One tile's logical place in the grid: which cell it occupies and how big that cell is. */
    private static final class Slot {
        final View child;
        final int x;
        final int y;
        final int width;
        final int height;

        Slot(View child, int x, int y, int width, int height) {
            this.child = child;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean contains(float px, float py, float inset) {
            float insetX = width * inset;
            float insetY = height * inset;
            return px >= x + insetX && px <= x + width - insetX
                    && py >= y + insetY && py <= y + height - insetY;
        }
    }

    /**
     * Packs an arrangement into slots, without measuring anything.
     *
     * <p>Reads the heights the last measure pass produced, which is what makes it safe to call
     * mid-gesture: a drag changes the order of tiles, never their sizes, so their measured heights
     * are still correct and the grid a pointer is tested against costs nothing to derive.
     *
     * <p>A wide tile that will not fit in what is left of a row starts the next one rather than
     * being squeezed, so a wide tile is always genuinely two columns across and nothing can be
     * packed beside or beneath its span. Every tile in a row then takes that row's height, which is
     * the difference between a grid and a pile of cards, and is also what lets a long title grow
     * its whole row instead of being clipped inside one tile.
     */
    private List<Slot> slotsFor(List<View> arrangement, int cell) {
        List<Slot> out = new ArrayList<>();
        List<View> visible = visibleIn(arrangement);
        int index = 0;
        int y = getPaddingTop();
        while (index < visible.size()) {
            int start = index;
            int column = 0;
            int height = 0;
            // Which tiles share this row, and how tall the row has to be for all of them.
            while (index < visible.size()) {
                View child = visible.get(index);
                int span = spanOf(child);
                if (column > 0 && column + span > columns) break;
                height = Math.max(height, Math.max(minRowHeight, child.getMeasuredHeight()));
                column += span;
                index++;
                if (column >= columns) break;
            }
            int x = getPaddingLeft();
            for (int i = start; i < index; i++) {
                View child = visible.get(i);
                int width = cell * spanOf(child) + spacing * (spanOf(child) - 1);
                out.add(new Slot(child, x, y, width, height));
                x += width + spacing;
            }
            y += height + spacing;
        }
        return out;
    }

    private List<View> visibleIn(List<View> arrangement) {
        List<View> out = new ArrayList<>();
        for (View child : arrangement) {
            if (child.getParent() == this && child.getVisibility() != GONE) out.add(child);
        }
        return out;
    }

    private List<View> visibleChildren() { return visibleIn(order); }

    private int cellWidth(int totalWidth) {
        int available = totalWidth - getPaddingLeft() - getPaddingRight();
        int usable = Math.max(0, available - spacing * (columns - 1));
        return columns > 0 ? usable / columns : usable;
    }

    private int spanOf(View child) {
        ViewGroup.LayoutParams params = child.getLayoutParams();
        int span = params instanceof LayoutParams ? ((LayoutParams) params).span : 1;
        return Math.max(1, Math.min(columns, span));
    }

    // ---- measurement ------------------------------------------------------------------------------

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int cell = cellWidth(width);
        for (View child : visibleChildren()) {
            int childWidth = cell * spanOf(child) + spacing * (spanOf(child) - 1);
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, childWidth), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        int bottom = getPaddingTop();
        for (Slot slot : slotsFor(order, cell)) bottom = Math.max(bottom, slot.y + slot.height);
        setMeasuredDimension(width, bottom + getPaddingBottom());
    }

    // ---- layout -----------------------------------------------------------------------------------

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean animate = animateNextLayout && UiKit.animationsEnabled();
        animateNextLayout = false;
        for (Slot slot : slotsFor(order, cellWidth(getWidth()))) place(slot, animate);
    }

    /**
     * Puts one tile in its slot, sliding it there from wherever it currently appears to be.
     *
     * <p>The slide starts from the tile's <em>visual</em> position — its last layout position plus
     * whatever translation an in-flight animation had reached — not from its last layout position
     * alone. Using the latter is what made a rapid reorder look violent: an interrupted slide was
     * snapped back to its origin before the next one began, so the tile appeared to jump backwards
     * through its neighbour. Retargeting from the live position means an interrupted slide simply
     * bends towards the new destination.
     *
     * <p>The dragged tile is exempt. It is already following the finger, and animating it towards a
     * slot it is being carried away from would fight the gesture.
     */
    private void place(Slot slot, boolean animate) {
        View child = slot.child;
        Point previous = lastPositions.get(child);
        float visualX = previous == null ? slot.x : previous.x + child.getTranslationX();
        float visualY = previous == null ? slot.y : previous.y + child.getTranslationY();

        child.layout(slot.x, slot.y, slot.x + slot.width, slot.y + slot.height);
        lastPositions.put(child, new Point(slot.x, slot.y));

        if (child == dragging) {
            followFinger(child);
            return;
        }
        float offsetX = visualX - slot.x;
        float offsetY = visualY - slot.y;
        if (!animate || previous == null
                || (Math.abs(offsetX) < 1f && Math.abs(offsetY) < 1f)) {
            settleImmediately(child);
            return;
        }
        // Cancelling first discards the previous animation's endpoint, which is now stale. Without
        // it the old animator can finish after the new one and drop the tile back at the slot it
        // was travelling to two reorders ago.
        child.animate().cancel();
        child.setTranslationX(offsetX);
        child.setTranslationY(offsetY);
        child.animate().translationX(0f).translationY(0f)
                .setDuration(REFLOW_MS)
                .setInterpolator(UiKit.motionEasing())
                // A completed slide must leave the tile exactly on its slot, with no residual
                // sub-pixel translation for the next drag to inherit.
                .withEndAction(() -> settleImmediately(child))
                .start();
    }

    private void settleImmediately(View child) {
        child.animate().cancel();
        child.setTranslationX(0f);
        child.setTranslationY(0f);
    }

    /** Keeps the carried tile under the finger, wherever it has just been laid out. */
    private void followFinger(View child) {
        child.setTranslationX(dragCenterX - (child.getLeft() + child.getWidth() / 2f));
        child.setTranslationY(dragCenterY - (child.getTop() + child.getHeight() / 2f));
    }

    // ---- dragging ---------------------------------------------------------------------------------

    /** Picks a tile up and snapshots the last committed arrangement. */
    public void beginDrag(View child) {
        if (child == null || child.getParent() != this || dragging != null) return;
        orderBeforeDrag.clear();
        orderBeforeDrag.addAll(order);
        // A tile picked up while its own settle animation is still running would otherwise carry
        // that animation's remaining translation into the drag and sit offset from the finger.
        settleImmediately(child);
        dragging = child;
        dragStartCenterX = dragCenterX = child.getLeft() + child.getWidth() / 2f;
        dragStartCenterY = dragCenterY = child.getTop() + child.getHeight() / 2f;
        child.bringToFront();
    }

    public boolean isDragging() { return dragging != null; }

    /**
     * Moves the carried tile and, if it now covers a different slot, opens that slot for it.
     *
     * <p>The order changes at most once per crossing rather than continuously, so the tiles that
     * shuffle out of the way animate once into their new positions instead of being re-laid on
     * every pointer event.
     */
    public void updateDrag(float dx, float dy) {
        if (dragging == null) return;
        dragCenterX = dragStartCenterX + dx;
        dragCenterY = dragStartCenterY + dy;
        followFinger(dragging);

        int current = order.indexOf(dragging);
        int target = insertionIndexAt(dragCenterX, dragCenterY);
        if (current < 0 || target < 0 || target == current) return;

        order.remove(current);
        order.add(Math.max(0, Math.min(target, order.size())), dragging);
        animateNextLayout = true;
        requestLayout();
    }

    /**
     * Which tile's provisional slot the finger is inside, or -1.
     *
     * <p>Asked of the slots derived from the current provisional order, never of the children's
     * live layout positions. Those two agree only between a reorder and the layout pass that
     * follows it, and a pointer event can easily arrive inside that window; testing against the
     * stale geometry is how the grid could briefly act on an arrangement that was not on screen.
     *
     * <p>The inset is the hysteresis. A finger resting on the seam between two tiles is inside
     * neither, so nothing moves until it commits to one.
     *
     * <p>A tile wider than one column is asked differently — see {@link #spanInsertionIndexAt}.
     * Picking a neighbour and inserting beside it is only sound when every tile is the same width;
     * a carried span has to be asked where it <em>fits</em>, not who it is next to.
     */
    private int insertionIndexAt(float x, float y) {
        if (getWidth() <= 0) return -1;
        if (dragging != null && spanOf(dragging) > 1) {
            return spanInsertionIndexAt(x, y, cellWidth(getWidth()));
        }
        for (Slot slot : slotsFor(order, cellWidth(getWidth()))) {
            if (slot.child == dragging) continue;
            if (slot.contains(x, y, SLOT_INSET)) return order.indexOf(slot.child);
        }
        return -1;
    }

    /**
     * Where a carried tile that is wider than one column should go.
     *
     * <p>Beta 3 asked one question for every tile: which neighbour is the finger over, and what is
     * that neighbour's index? For a standard tile that is enough, because any order of equal-width
     * tiles packs into a full grid. For a wide tile it is not, and that mismatch is the whole
     * defect. Landing {@code New chat} beside {@code Routines} produced the order
     * {@code Routines, New chat, Reminders, …}, and the packer — which never squeezes a span into
     * what is left of a row — then pushed the wide tile onto a row of its own and left the cell
     * next to Routines empty. Reminders was stranded a row below its partner, the grid held a hole
     * the carried card was sitting over, and the arrangement the tiles animated towards was one the
     * finger had never asked for. The order was repaired on drop, which is why the result looked
     * right and the journey did not.
     *
     * <p>So the question asked here is the one the geometry can actually answer. Rather than
     * choosing a neighbour and hoping the packing agrees, this enumerates the insertion points
     * where the carried span genuinely fits, packs each one, and picks by where the carried tile
     * would <em>actually land</em>. The provisional order is therefore never a guess that the layout
     * has to reconcile: it is chosen from arrangements the layout already produced.
     *
     * <p>Because the packing is decided left to right, an insertion point is sound exactly when the
     * row cursor at that point still has room for the span, or when it is the end of the
     * arrangement. Everything before such a point packs unchanged, the carried tile takes its full
     * declared span starting where it stands, and the remainder reflows from there — so a standard
     * pair is never split by a wide tile dropping between its two halves, and no cell is ever
     * vacated mid-grid.
     *
     * <p>On a two-column phone that reduces to exactly the row boundaries, which is what makes
     * {@code New chat} feel like it moves between rows. It is not written as "every second index"
     * though, because Wide means a declared span of two and a tablet has three or four columns; on
     * those the same rule offers the genuine column offsets a two-wide tile can occupy and nothing
     * assumes a wide tile owns the row.
     *
     * <p>Hysteresis is the same inset containment the standard path uses, so both feel alike. The
     * carried tile's current position is itself one of the candidates, which means a finger resting
     * inside the row it has already claimed re-selects that row and nothing moves; a finger in the
     * gutter between two rows is inside neither candidate and, again, nothing moves. Only once the
     * finger is properly inside a different destination does the grid commit to it.
     */
    private int spanInsertionIndexAt(float x, float y, int cell) {
        int current = order.indexOf(dragging);
        if (current < 0) return -1;
        int span = spanOf(dragging);
        List<View> rest = new ArrayList<>(order);
        rest.remove(dragging);

        int best = current;
        float bestDistance = Float.MAX_VALUE;
        for (int index : fittingInsertionIndices(rest, span)) {
            List<View> candidate = new ArrayList<>(rest);
            candidate.add(index, dragging);
            Slot placed = null;
            for (Slot slot : slotsFor(candidate, cell)) {
                if (slot.child == dragging) { placed = slot; break; }
            }
            if (placed == null || !placed.contains(x, y, SLOT_INSET)) continue;
            // Two candidates can only both contain the finger when a span narrower than the grid
            // has more than one column offset available in one row, which a tablet does. The nearer
            // destination wins, so the tile settles into the offset the finger is actually over.
            float dx = x - (placed.x + placed.width / 2f);
            float dy = y - (placed.y + placed.height / 2f);
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance) { bestDistance = distance; best = index; }
        }
        return best;
    }

    /**
     * The indices of {@code arrangement} where a tile of this span can be inserted without the
     * packer having to wrap it, plus the end.
     *
     * <p>Walks the same row cursor {@link #slotsFor} does — a row breaks when the next span will
     * not fit, and starts fresh once it is full — and offers an index whenever the span still fits
     * in what is left of the current row. Appending is always offered: a tile added after
     * everything else can start a row of its own without disturbing anything, which is what lets a
     * wide tile reach the bottom of a grid holding an odd number of standard tiles.
     */
    private List<Integer> fittingInsertionIndices(List<View> arrangement, int span) {
        List<Integer> out = new ArrayList<>();
        int column = 0;
        for (int i = 0; i <= arrangement.size(); i++) {
            if (column + span <= columns || i == arrangement.size()) out.add(i);
            if (i == arrangement.size()) break;
            View child = arrangement.get(i);
            if (child.getParent() != this || child.getVisibility() == GONE) continue;
            int childSpan = spanOf(child);
            if (column > 0 && column + childSpan > columns) column = 0;
            column += childSpan;
            if (column >= columns) column = 0;
        }
        return out;
    }

    /**
     * Puts the carried tile down and reports the resulting order.
     *
     * <p>The settle is not a special animation. Releasing the tile makes it an ordinary child
     * again, and the layout pass that follows slides it from where the finger left it into its slot
     * through the same path every other tile uses. One mechanism means the dropped tile cannot land
     * somewhere the grid disagrees with, and there is no rebuild to flash.
     */
    public boolean endDrag() {
        View child = dragging;
        if (child == null) return false;
        dragging = null;
        boolean changed = !order.equals(orderBeforeDrag);
        orderBeforeDrag.clear();
        animateNextLayout = UiKit.animationsEnabled();
        if (!animateNextLayout) settleImmediately(child);
        requestLayout();
        if (changed && reorderListener != null) reorderListener.onReorder(orderedChildren());
        return changed;
    }

    /** Restores the pickup snapshot without notifying storage. */
    public void cancelDrag() {
        View child = dragging;
        if (child == null) return;
        dragging = null;
        order.clear();
        order.addAll(orderBeforeDrag);
        orderBeforeDrag.clear();
        animateNextLayout = UiKit.animationsEnabled();
        if (!animateNextLayout) settleImmediately(child);
        requestLayout();
    }

    /** Re-lays the grid with the tiles that moved sliding into place. */
    public void animateNextLayout() {
        animateNextLayout = true;
        requestLayout();
    }

    /**
     * The column count a Deck should use at this width.
     *
     * <p>One responsive rule for phone and tablet rather than a separate tablet screen: a phone
     * stays at two, a large foldable or a Tab S9 Plus earns three, and a genuinely wide landscape
     * tablet earns four. Past that the tiles would start looking like a launcher.
     */
    public static int columnsForWidth(int widthDp) {
        if (widthDp >= 900) return 4;
        if (widthDp >= 600) return 3;
        return 2;
    }

    // ---- test seams -------------------------------------------------------------------------------
    // Layout state rather than pixels: what the grid believes, so a test can check it is believable.

    /** The provisional slot rectangles, in display order. */
    List<Rect> occupancyForTest() {
        List<Rect> out = new ArrayList<>();
        for (Slot slot : slotsFor(order, cellWidth(getWidth()))) {
            out.add(new Rect(slot.x, slot.y, slot.x + slot.width, slot.y + slot.height));
        }
        return out;
    }

    /** True when the provisional grid is one arrangement: no overlaps, no empty cells. */
    boolean occupancyIsValidForTest() {
        List<Rect> rects = occupancyForTest();
        if (rects.size() != visibleChildren().size()) return false;
        for (int i = 0; i < rects.size(); i++) {
            Rect a = rects.get(i);
            if (a.width() <= 0 || a.height() <= 0) return false;
            for (int j = i + 1; j < rects.size(); j++) {
                if (Rect.intersects(a, rects.get(j))) return false;
            }
        }
        return true;
    }

    /** True when every tile that is not being carried sits exactly on its slot. */
    boolean everyIdleTileIsSettledForTest() {
        for (View child : visibleChildren()) {
            if (child == dragging) continue;
            if (child.getTranslationX() != 0f || child.getTranslationY() != 0f) return false;
        }
        return true;
    }

    /**
     * Each visible tile's logical placement as {@code {row, startColumn, span}}, in display order.
     *
     * <p>Rectangles alone cannot express the thing a wide tile's drag kept getting wrong. A grid
     * with a hole beside a half-placed span has no overlapping rectangles at all, so a pixel-level
     * occupancy check calls it valid; what is wrong with it is which cells are claimed. This
     * reports the claim, so a test can say a wide tile owns a whole span starting on a real column
     * boundary and that nothing shares those cells.
     */
    List<int[]> placementForTest() {
        List<int[]> out = new ArrayList<>();
        int cell = cellWidth(getWidth());
        int row = -1;
        int lastY = Integer.MIN_VALUE;
        for (Slot slot : slotsFor(order, cell)) {
            if (slot.y != lastY) { row++; lastY = slot.y; }
            int column = cell + spacing > 0
                    ? Math.round((slot.x - getPaddingLeft()) / (float) (cell + spacing)) : 0;
            out.add(new int[]{row, column, spanOf(slot.child)});
        }
        return out;
    }

    /**
     * True when every tile claims whole cells inside the grid and no two claim the same cell.
     *
     * <p>The invariant a full-span drag has to hold at every provisional step: a tile's span starts
     * on a column boundary, finishes inside the grid, and no other tile is in any of those cells.
     */
    boolean spanPlacementIsValidForTest() {
        List<int[]> placements = placementForTest();
        if (placements.size() != visibleChildren().size()) return false;
        Map<String, Boolean> claimed = new HashMap<>();
        for (int[] placement : placements) {
            int row = placement[0];
            int column = placement[1];
            int span = placement[2];
            if (row < 0 || column < 0 || span < 1 || column + span > columns) return false;
            for (int c = column; c < column + span; c++) {
                if (claimed.put(row + ":" + c, Boolean.TRUE) != null) return false;
            }
        }
        return true;
    }

    /** Where the drag believes the carried tile would be inserted right now. */
    int insertionIndexForTest(float x, float y) { return insertionIndexAt(x, y); }

    /** The centre of one tile's provisional slot, for a test that needs somewhere to point. */
    float[] slotCenterForTest(View child) {
        for (Slot slot : slotsFor(order, cellWidth(getWidth()))) {
            if (slot.child == child) {
                return new float[]{slot.x + slot.width / 2f, slot.y + slot.height / 2f};
            }
        }
        return null;
    }
}
