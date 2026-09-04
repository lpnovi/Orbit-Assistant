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
     */
    private int insertionIndexAt(float x, float y) {
        if (getWidth() <= 0) return -1;
        for (Slot slot : slotsFor(order, cellWidth(getWidth()))) {
            if (slot.child == dragging) continue;
            if (slot.contains(x, y, SLOT_INSET)) return order.indexOf(slot.child);
        }
        return -1;
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
