package com.orbit.assistant;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * The horizontal pager the attachment viewer swipes through.
 *
 * <p>Hand-written rather than pulled in, because the interesting part of an image pager is not the
 * paging - it is the arbitration, and a general-purpose pager gets that wrong by default. Its
 * child is a zoomable image, so a drag is ambiguous by construction: the same finger movement means
 * "pan this photo" while there is photo left to reveal and "show me the next photo" once there is
 * not. A pager that intercepts on horizontal movement alone steals every pan; one that never
 * intercepts never changes page.
 *
 * <p>So this pager takes a drag only when the current image has said it cannot use it. Two fingers
 * are never a page change, because a pinch is a pinch. And an image zoomed into its left edge can
 * still be dragged rightwards to reveal more of itself before the gesture becomes a page change,
 * which is the behaviour that makes zooming into a photo feel like looking at it rather than like
 * fighting the screen.
 */
public final class AttachmentPagerView extends ViewGroup {

    /** What one page can be asked about the gesture it is holding. */
    public interface Page {
        /** Whether the page itself still has content to reveal in this direction. */
        boolean canPanHorizontally(int direction);
        /** Put back to its resting transform, when the page stops being the current one. */
        void resetTransform();
    }

    /** Told when the settled page changes. */
    public interface OnPageChanged {
        void onPageChanged(int index);
    }

    /** The gap drawn between pages, so a swipe reads as two images rather than one seam. */
    private static final int GAP_DP = 16;
    /** How far a drag must go before it settles onto the next page rather than snapping back. */
    private static final float SETTLE_FRACTION = 0.32f;

    private final List<Page> pages = new ArrayList<>();
    private final OverScroller scroller;
    private final int touchSlop;
    private final int minimumFlingVelocity;
    private final int maximumFlingVelocity;
    private final int gap;

    private VelocityTracker velocity;
    private OnPageChanged onPageChanged;
    private int current;
    private boolean dragging;
    private float downX;
    private float downY;
    private float lastX;

    public AttachmentPagerView(Context context) {
        super(context);
        scroller = new OverScroller(context);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        maximumFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        gap = UiKit.dp(context, GAP_DP);
        setWillNotDraw(true);
    }

    public void setOnPageChanged(OnPageChanged listener) { this.onPageChanged = listener; }

    /** Adds one page, in order. The pager draws it; the caller owns what is inside it. */
    public void addPage(View content, Page page) {
        pages.add(page);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** Removes one page and everything under it, keeping the remaining order. */
    public void removePage(int index) {
        if (index < 0 || index >= pages.size()) return;
        pages.remove(index);
        removeViewAt(index);
        if (current >= pages.size()) current = Math.max(0, pages.size() - 1);
    }

    public int pageCount() { return pages.size(); }

    public int currentPage() { return current; }

    /**
     * Moves to a page.
     *
     * <p>Animated only when the system has animations on, so a device set to reduced motion gets an
     * immediate change of image rather than a movement it asked not to see.
     */
    public void setCurrentPage(int index, boolean animate) {
        int next = clamp(index);
        boolean changed = next != current;
        current = next;
        int target = next * pageStride();
        scroller.abortAnimation();
        if (animate && UiKit.animationsEnabled() && getWidth() > 0) {
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0,
                    (int) UiKit.MOTION_STANDARD);
            postInvalidateOnAnimation();
        } else {
            scrollTo(target, 0);
        }
        if (changed) settleTransforms();
        if (changed && onPageChanged != null) onPageChanged.onPageChanged(current);
    }

    /** Everything except the current page goes back to fit, so nothing keeps a stale zoom. */
    private void settleTransforms() {
        for (int i = 0; i < pages.size(); i++) {
            if (i != current) pages.get(i).resetTransform();
        }
    }

    private int pageStride() { return getWidth() + gap; }

    private int clamp(int index) {
        if (pages.isEmpty()) return 0;
        if (index < 0) return 0;
        return index >= pages.size() ? pages.size() - 1 : index;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidth, childHeight);
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        int stride = width + gap;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(i * stride, 0, i * stride + width, height);
        }
        // A rotation changes the stride, so the resting offset has to be recomputed or the pager
        // settles between two pages showing half of each. Asked as "is it finished" rather than by
        // computing an offset, which would advance an animation from inside a layout pass.
        if (scroller.isFinished() && !dragging) scrollTo(current * stride, 0);
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            lastX = downX;
            dragging = false;
            if (!scroller.isFinished()) {
                // Touching a settling pager grabs it, the way every scroller behaves.
                scroller.abortAnimation();
                dragging = true;
                startTracking(event);
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            dragging = false;
            return false;
        }
        // Two fingers are a pinch, always. The pager never takes one.
        if (event.getPointerCount() > 1) return false;
        if (action == MotionEvent.ACTION_MOVE && !dragging && shouldTake(event)) {
            dragging = true;
            lastX = event.getX();
            startTracking(event);
            return true;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (velocity != null) velocity.addMovement(event);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                lastX = downX;
                startTracking(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1) return true;
                if (!dragging && shouldTake(event)) {
                    dragging = true;
                    lastX = event.getX();
                }
                if (dragging) {
                    float x = event.getX();
                    dragBy(lastX - x);
                    lastX = x;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) settleAfterDrag(action == MotionEvent.ACTION_UP);
                dragging = false;
                stopTracking();
                return true;
            default:
                return true;
        }
    }

    /**
     * Whether this drag is the pager's to take.
     *
     * <p>Three things all have to be true: the movement is past the slop, it is more horizontal
     * than vertical, and the current image says it cannot use it. The last one is the whole point -
     * a zoomed photo with more of itself to reveal in that direction keeps the gesture, and only
     * hands it over once there is genuinely nothing left that way.
     */
    private boolean shouldTake(MotionEvent event) {
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        if (Math.abs(dx) < touchSlop || Math.abs(dx) <= Math.abs(dy)) return false;
        Page page = current >= 0 && current < pages.size() ? pages.get(current) : null;
        // A finger moving right asks to see content towards the start of the image.
        int direction = dx > 0 ? -1 : 1;
        return page == null || !page.canPanHorizontally(direction);
    }

    private void dragBy(float dx) {
        int stride = pageStride();
        if (stride <= 0) return;
        int max = Math.max(0, (pages.size() - 1) * stride);
        int next = Math.round(getScrollX() + dx);
        if (next < 0) next = 0;
        if (next > max) next = max;
        scrollTo(next, 0);
    }

    private void settleAfterDrag(boolean allowFling) {
        int stride = pageStride();
        if (stride <= 0) { setCurrentPage(current, false); return; }
        float position = getScrollX() / (float) stride;
        int target = current;
        float travelled = position - current;
        int flung = allowFling ? flingDirection() : 0;
        if (flung != 0) target = current + flung;
        else if (travelled > SETTLE_FRACTION) target = current + 1;
        else if (travelled < -SETTLE_FRACTION) target = current - 1;
        // Always through setCurrentPage, including when the answer is the page already showing:
        // it animates to the resting offset either way, so a drag that did not travel far enough
        // slides back rather than being left parked between two images.
        setCurrentPage(target, true);
    }

    /** -1 for a fling towards the previous page, 1 for the next, 0 for neither. */
    private int flingDirection() {
        if (velocity == null) return 0;
        velocity.computeCurrentVelocity(1000, maximumFlingVelocity);
        float x = velocity.getXVelocity();
        if (Math.abs(x) < minimumFlingVelocity) return 0;
        return x > 0 ? -1 : 1;
    }

    private void startTracking(MotionEvent event) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        else velocity.clear();
        velocity.addMovement(event);
    }

    private void stopTracking() {
        if (velocity == null) return;
        velocity.recycle();
        velocity = null;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopTracking();
        scroller.abortAnimation();
    }
}
