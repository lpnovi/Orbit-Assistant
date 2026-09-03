package com.orbit.assistant;

import android.content.Context;
import android.graphics.Point;
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
        order.remove(view);
        lastPositions.remove(view);
        super.removeView(view);
    }

    @Override public void removeAllViews() {
        order.clear();
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

    // ---- measurement ------------------------------------------------------------------------------

    /**
     * Packs the tiles into rows and reports the height that needs.
     *
     * <p>A wide tile that will not fit in what is left of a row starts the next one rather than
     * being squeezed, so a wide tile is always genuinely two columns across.
     */
    /** One row of tiles and the height they all share. */
    private static final class Row {
        final List<View> children = new ArrayList<>();
        int height;
    }

    /**
     * Packs the visible tiles into rows and measures each one.
     *
     * <p>A wide tile that will not fit in what is left of a row starts the next one rather than
     * being squeezed, so a wide tile is always genuinely two columns across.
     *
     * <p>Every tile in a row is then given that row's height. Sizing each tile to its own content
     * instead would leave a short tile floating beside a tall one, which is the difference between
     * a grid and a pile of cards — and it is also what makes a long title grow its whole row rather
     * than being clipped inside one tile.
     */
    private List<Row> rows(int cell) {
        List<Row> out = new ArrayList<>();
        Row current = new Row();
        int column = 0;

        for (View child : visibleChildren()) {
            int span = spanOf(child);
            if (column > 0 && column + span > columns) {
                out.add(current);
                current = new Row();
                column = 0;
            }
            int width = cell * span + spacing * (span - 1);
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            current.children.add(child);
            current.height = Math.max(current.height,
                    Math.max(minRowHeight, child.getMeasuredHeight()));
            column += span;
            if (column >= columns) {
                out.add(current);
                current = new Row();
                column = 0;
            }
        }
        if (!current.children.isEmpty()) out.add(current);
        return out;
    }

    private int cellWidth(int totalWidth) {
        int available = totalWidth - getPaddingLeft() - getPaddingRight();
        int usable = Math.max(0, available - spacing * (columns - 1));
        return columns > 0 ? usable / columns : usable;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        List<Row> rows = rows(cellWidth(width));

        int total = 0;
        for (int i = 0; i < rows.size(); i++) {
            total += (i == 0 ? 0 : spacing) + rows.get(i).height;
        }
        setMeasuredDimension(width, total + getPaddingTop() + getPaddingBottom());
    }

    // ---- layout -----------------------------------------------------------------------------------

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int cell = cellWidth(getWidth());
        List<Row> rows = rows(cell);

        boolean animate = animateNextLayout && UiKit.animationsEnabled();
        animateNextLayout = false;

        int y = getPaddingTop();
        for (Row row : rows) {
            int x = getPaddingLeft();
            for (View child : row.children) {
                int width = cell * spanOf(child) + spacing * (spanOf(child) - 1);
                // The row's height, not the child's: every tile in a row is the same height.
                place(child, x, y, width, row.height, animate);
                x += width + spacing;
            }
            y += row.height + spacing;
        }
    }

    /**
     * Puts one tile where it belongs, sliding it there when it has moved.
     *
     * <p>The dragged tile is exempt: it is already following the finger, and animating it towards a
     * slot it is being carried away from would fight the gesture.
     */
    private void place(View child, int x, int y, int width, int height, boolean animate) {
        Point previous = lastPositions.get(child);
        child.layout(x, y, x + width, y + height);
        lastPositions.put(child, new Point(x, y));

        if (child == dragging) {
            child.setTranslationX(dragCenterX - (x + width / 2f));
            child.setTranslationY(dragCenterY - (y + height / 2f));
            return;
        }
        if (!animate || previous == null) return;
        if (previous.x == x && previous.y == y) return;

        child.setTranslationX(previous.x - x);
        child.setTranslationY(previous.y - y);
        child.animate().translationX(0f).translationY(0f)
                .setDuration(180L)
                .setInterpolator(UiKit.motionEasing())
                .start();
    }

    private List<View> visibleChildren() {
        List<View> out = new ArrayList<>();
        for (View child : order) {
            if (child.getParent() == this && child.getVisibility() != GONE) out.add(child);
        }
        return out;
    }

    private int spanOf(View child) {
        ViewGroup.LayoutParams params = child.getLayoutParams();
        int span = params instanceof LayoutParams ? ((LayoutParams) params).span : 1;
        return Math.max(1, Math.min(columns, span));
    }

    // ---- dragging ---------------------------------------------------------------------------------

    /** Picks a tile up and snapshots the last committed arrangement. */
    public void beginDrag(View child) {
        if (child == null || child.getParent() != this || dragging != null) return;
        orderBeforeDrag.clear();
        orderBeforeDrag.addAll(order);
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
     * shuffle out of the way animate once into their new positions instead of being re-laid every
     * frame.
     */
    public void updateDrag(float dx, float dy) {
        if (dragging == null) return;
        dragCenterX = dragStartCenterX + dx;
        dragCenterY = dragStartCenterY + dy;
        dragging.setTranslationX(dragCenterX
                - (dragging.getLeft() + dragging.getWidth() / 2f));
        dragging.setTranslationY(dragCenterY
                - (dragging.getTop() + dragging.getHeight() / 2f));

        int target = insertionIndexAt(dragCenterX, dragCenterY);
        int current = order.indexOf(dragging);
        if (target < 0 || current < 0) return;

        order.remove(current);
        if (current < target) target--;
        if (target == current) {
            order.add(current, dragging);
            return;
        }
        order.add(Math.min(target, order.size()), dragging);
        animateNextLayout = true;
        requestLayout();
    }

    /** Puts the carried tile down and reports the resulting order. */
    public boolean endDrag() {
        View child = dragging;
        dragging = null;
        if (child == null) return false;
        boolean changed = !order.equals(orderBeforeDrag);
        // Settle from wherever the finger left it back into its slot.
        if (UiKit.animationsEnabled()) {
            child.animate().translationX(0f).translationY(0f)
                    .setDuration(160L).setInterpolator(UiKit.motionEasing()).start();
        } else {
            child.setTranslationX(0f);
            child.setTranslationY(0f);
        }
        if (changed && reorderListener != null) reorderListener.onReorder(orderedChildren());
        orderBeforeDrag.clear();
        return changed;
    }

    /**
     * Which insertion slot a point falls in, excluding the carried tile itself.
     *
     * <p>Beta 1 made the carried tile's centre equal to the pointer before asking which tile was
     * nearest. It therefore won with distance zero on every frame and the target never changed.
     * The stationary cards define the slots here; repacking the ordered list through the existing
     * span-aware row layout makes wide cards real row boundaries rather than overlap targets.
     */
    private int insertionIndexAt(float x, float y) {
        View closest = null;
        double bestDistance = Double.MAX_VALUE;
        for (View child : visibleChildren()) {
            if (child == dragging) continue;
            float cx = child.getLeft() + child.getWidth() / 2f;
            float cy = child.getTop() + child.getHeight() / 2f;
            // Distance to the card, not merely its centre. A full-row wide card can be hundreds of
            // pixels from its own centre while the finger is visibly inside its left edge; centre
            // distance would incorrectly choose a standard card in the next row.
            float nearestX = Math.max(child.getLeft(), Math.min(x, child.getRight()));
            float nearestY = Math.max(child.getTop(), Math.min(y, child.getBottom()));
            double distance = Math.pow(nearestX - x, 2) + Math.pow(nearestY - y, 2);
            if (distance == bestDistance && closest != null) {
                double centreDistance = Math.pow(cx - x, 2) + Math.pow(cy - y, 2);
                float oldCx = closest.getLeft() + closest.getWidth() / 2f;
                float oldCy = closest.getTop() + closest.getHeight() / 2f;
                double oldCentreDistance = Math.pow(oldCx - x, 2) + Math.pow(oldCy - y, 2);
                if (centreDistance < oldCentreDistance) closest = child;
                continue;
            }
            if (distance < bestDistance) { bestDistance = distance; closest = child; }
        }
        if (closest == null) return 0;
        int index = order.indexOf(closest);
        if (index < 0) return -1;
        float centreY = closest.getTop() + closest.getHeight() / 2f;
        float centreX = closest.getLeft() + closest.getWidth() / 2f;
        float rowTolerance = Math.max(spacing, closest.getHeight() * 0.22f);
        boolean after = y > centreY + rowTolerance
                || (Math.abs(y - centreY) <= rowTolerance && x > centreX);
        return index + (after ? 1 : 0);
    }

    /** Restores the pickup snapshot without notifying storage. */
    public void cancelDrag() {
        View child = dragging;
        dragging = null;
        if (child == null) return;
        order.clear();
        order.addAll(orderBeforeDrag);
        orderBeforeDrag.clear();
        child.animate().cancel();
        child.setTranslationX(0f);
        child.setTranslationY(0f);
        animateNextLayout = UiKit.animationsEnabled();
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
}
