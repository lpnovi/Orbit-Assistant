package com.orbit.assistant;

/**
 * The transform of one image inside the attachment viewer, and the rule about who owns a gesture.
 *
 * <p>Written as plain arithmetic rather than as a {@code Matrix} so the two things that actually go
 * wrong in an image viewer can be stated and tested without a laid-out view. The first is scale
 * running away - a pinch that accumulates a factor per frame reaches a scale of nothing or of
 * infinity within a second, and what the user sees is a blank screen they cannot recover from. The
 * second is a zoomed image that changes attachment while it is being panned, which is the
 * difference between "I was looking at the corner of photo 2" and "photo 3 is now on screen and I
 * did not ask for it".
 *
 * <p>So scale is always clamped into a real range, translation is always clamped to the content
 * that exists, and {@link #canPanHorizontally(int)} is the single question the pager asks before it
 * is allowed to take a sideways drag away from the image. A fitted image answers no in both
 * directions, which is exactly why an unzoomed swipe changes the page; a zoomed one answers yes
 * until its edge is reached, which is why panning never turns into paging halfway through.
 *
 * <p>Scale here is relative to fit-to-view, not to the source pixels. 1 means the whole image is on
 * screen whatever its size, so "minimum zoom" means the same thing for a panorama and for an icon,
 * and no image can be scrolled when there is nothing off screen to scroll to.
 */
public final class ZoomPanController {

    /** Fit-to-view. The image is never allowed to be smaller than the screen shows it. */
    public static final float MIN_SCALE = 1f;
    /** Enough to read small text in a screenshot, and far short of a pathological transform. */
    public static final float MAX_SCALE = 4f;
    /** Where a double tap lands. Deliberately inside the range rather than at its ceiling. */
    public static final float DOUBLE_TAP_SCALE = 2.5f;
    /** Within this of fit, the image counts as unzoomed. Guards float drift, not the user. */
    public static final float FIT_EPSILON = 0.01f;
    /** Sub-pixel slack, so a rounding error at an edge cannot look like pannable content. */
    private static final float EDGE_EPSILON = 0.5f;

    private float viewWidth;
    private float viewHeight;
    private float imageWidth;
    private float imageHeight;

    private float scale = MIN_SCALE;
    private float translationX;
    private float translationY;

    /**
     * Tells the controller what it is transforming.
     *
     * <p>Resets whenever the content or the viewport genuinely changes shape, because a
     * translation measured against one set of bounds means nothing against another: keeping it
     * across a rotation or a reused page is how an image ends up parked off screen with no way
     * back.
     */
    public void setBounds(float viewWidth, float viewHeight, float imageWidth, float imageHeight) {
        float nextViewWidth = positive(viewWidth);
        float nextViewHeight = positive(viewHeight);
        float nextImageWidth = positive(imageWidth);
        float nextImageHeight = positive(imageHeight);
        boolean changed = nextViewWidth != this.viewWidth || nextViewHeight != this.viewHeight
                || nextImageWidth != this.imageWidth || nextImageHeight != this.imageHeight;
        this.viewWidth = nextViewWidth;
        this.viewHeight = nextViewHeight;
        this.imageWidth = nextImageWidth;
        this.imageHeight = nextImageHeight;
        if (changed) reset();
    }

    /** True once there is both a viewport and an image to put in it. */
    public boolean hasContent() {
        return viewWidth > 0f && viewHeight > 0f && imageWidth > 0f && imageHeight > 0f;
    }

    public float scale() { return scale; }

    public float translationX() { return translationX; }

    public float translationY() { return translationY; }

    /** How the source pixels are scaled at zoom 1, so the whole image is on screen. */
    public float fitScale() {
        if (!hasContent()) return 1f;
        return Math.min(viewWidth / imageWidth, viewHeight / imageHeight);
    }

    /** The scale actually applied to the source bitmap. */
    public float appliedScale() { return fitScale() * scale; }

    /** True when the image is meaningfully larger than the screen shows it fitted. */
    public boolean isZoomed() { return scale > MIN_SCALE + FIT_EPSILON; }

    /** Back to fit-to-screen, centred. */
    public void reset() {
        scale = MIN_SCALE;
        translationX = 0f;
        translationY = 0f;
    }

    /**
     * Zooms to an absolute scale, keeping the point under the focus where it is.
     *
     * <p>An absolute target rather than an accumulated factor: a pinch reports a total span, and
     * multiplying frame by frame is what lets a scale drift away from anything real.
     */
    public void zoomTo(float target, float focusX, float focusY) {
        if (!hasContent() || !finite(target) || !finite(focusX) || !finite(focusY)) return;
        float next = clamp(target, MIN_SCALE, MAX_SCALE);
        float ratio = next / scale;
        if (!finite(ratio) || ratio <= 0f) {
            scale = next;
            clampTranslation();
            return;
        }
        translationX = (focusX - viewWidth / 2f) * (1f - ratio) + translationX * ratio;
        translationY = (focusY - viewHeight / 2f) * (1f - ratio) + translationY * ratio;
        scale = next;
        clampTranslation();
    }

    /** A pinch step: the span factor since the gesture began, applied about its focal point. */
    public void scaleBy(float factor, float focusX, float focusY) {
        if (!finite(factor) || factor <= 0f) return;
        zoomTo(scale * factor, focusX, focusY);
    }

    /**
     * What a double tap does: in to {@link #DOUBLE_TAP_SCALE} about the tapped point, or back to
     * fit. Always one or the other, so two taps in the same place always undo each other.
     */
    public void toggleZoom(float focusX, float focusY) {
        if (isZoomed()) reset();
        else zoomTo(DOUBLE_TAP_SCALE, focusX, focusY);
    }

    /** Moves the image by a drag, within the content that exists. True when it actually moved. */
    public boolean panBy(float dx, float dy) {
        if (!hasContent() || !finite(dx) || !finite(dy)) return false;
        float wasX = translationX;
        float wasY = translationY;
        translationX += dx;
        translationY += dy;
        clampTranslation();
        return translationX != wasX || translationY != wasY;
    }

    /** How far the image may move from centre before its edge reaches the viewport edge. */
    public float maxTranslationX() {
        if (!hasContent()) return 0f;
        return Math.max(0f, (imageWidth * appliedScale() - viewWidth) / 2f);
    }

    public float maxTranslationY() {
        if (!hasContent()) return 0f;
        return Math.max(0f, (imageHeight * appliedScale() - viewHeight) / 2f);
    }

    /**
     * Whether the image itself can still absorb a horizontal drag in this direction.
     *
     * <p>The pager asks this before it is allowed to change attachment, and Android's own
     * convention is used so the answer reads the same way everywhere: a negative direction is
     * scrolling towards the start of the content, a positive one towards its end.
     *
     * <p>A fitted image answers no both ways, because there is genuinely nothing off screen. That
     * is the whole arbitration: an unzoomed swipe is a page change, a zoomed drag is a pan, and a
     * zoomed image already at its edge hands the gesture on rather than trapping the user.
     */
    public boolean canPanHorizontally(int direction) {
        if (!hasContent() || direction == 0) return false;
        float limit = maxTranslationX();
        if (limit <= EDGE_EPSILON) return false;
        return direction < 0
                ? translationX < limit - EDGE_EPSILON
                : translationX > -limit + EDGE_EPSILON;
    }

    /** As above, vertically. Used only to decide whether a drag is the image to keep. */
    public boolean canPanVertically(int direction) {
        if (!hasContent() || direction == 0) return false;
        float limit = maxTranslationY();
        if (limit <= EDGE_EPSILON) return false;
        return direction < 0
                ? translationY < limit - EDGE_EPSILON
                : translationY > -limit + EDGE_EPSILON;
    }

    private void clampTranslation() {
        float limitX = maxTranslationX();
        float limitY = maxTranslationY();
        translationX = clamp(translationX, -limitX, limitX);
        translationY = clamp(translationY, -limitY, limitY);
    }

    private static float clamp(float value, float min, float max) {
        if (!finite(value)) return min;
        return value < min ? min : (value > max ? max : value);
    }

    private static float positive(float value) {
        return finite(value) && value > 0f ? value : 0f;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
