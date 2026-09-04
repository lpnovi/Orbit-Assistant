package com.orbit.assistant;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a piece of PDF text ends up on screen. Pure arithmetic, no PDF and no View.
 *
 * <p>A highlight has three coordinate systems to cross — the PDF's own page space, the pixels
 * Android's {@code PdfRenderer} produced for it, and wherever the user has since zoomed and panned
 * that bitmap to — and getting any one of them wrong puts a coloured box next to the word instead
 * of on it. Keeping the arithmetic here means it can be tested against known geometry rather than
 * inspected by eye on a phone.
 *
 * <p>The middle system is deliberately eliminated. Everything is normalized to a fraction of the
 * page, so a highlight is "38% across, 12% down" rather than a pixel offset into one particular
 * render. That is what lets the same geometry survive a re-render at a different bitmap size,
 * a rotation, a fit, a pinch and a pan without being recomputed.
 *
 * <p>Page rotation is handled where PDFBox already handles it. Its {@code *DirAdj} text positions
 * are reported in the page's <em>display</em> orientation with the origin at the top-left, which is
 * the same orientation {@code PdfRenderer} rasterizes into, so the only thing left to know is how
 * big the page is once rotated.
 */
public final class DocumentPageMapping {

    /** Two boxes are on the same line when they overlap vertically by at least this much. */
    private static final float SAME_LINE_OVERLAP = 0.4f;
    /** Horizontal gap, as a fraction of line height, that still counts as the same fragment. */
    private static final float FRAGMENT_GAP = 0.75f;

    private DocumentPageMapping() {}

    // ---- page size --------------------------------------------------------------------------------

    /** The width of a page as it is displayed, which swaps with height on a quarter turn. */
    public static float displayWidth(float cropWidth, float cropHeight, int rotation) {
        return quarterTurned(rotation) ? positive(cropHeight) : positive(cropWidth);
    }

    /** The height of a page as it is displayed. */
    public static float displayHeight(float cropWidth, float cropHeight, int rotation) {
        return quarterTurned(rotation) ? positive(cropWidth) : positive(cropHeight);
    }

    /** True for 90 and 270, where the page's width and height exchange places. */
    public static boolean quarterTurned(int rotation) {
        int normalized = ((rotation % 360) + 360) % 360;
        return normalized == 90 || normalized == 270;
    }

    // ---- one character ----------------------------------------------------------------------------

    /**
     * One text position as a fraction of the displayed page, or null when it cannot be placed.
     *
     * <p>{@code y} arrives as the character's lower edge measured from the top of the page, which
     * is PDFBox's convention and not an obvious one; the top is therefore {@code y - height}. A box
     * that lands outside the page is clamped rather than dropped, because a character sitting a
     * fraction of a point over the crop edge is a rounding artefact, not a reason to lose a
     * highlight.
     */
    public static RectF normalize(float x, float lowerY, float width, float height,
                                  float pageWidth, float pageHeight) {
        if (!finite(x) || !finite(lowerY) || !finite(width) || !finite(height)) return null;
        if (pageWidth <= 0f || pageHeight <= 0f) return null;
        float top = lowerY - Math.abs(height);
        float left = x;
        float right = x + Math.abs(width);
        float bottom = lowerY;
        RectF out = new RectF(
                clamp01(left / pageWidth),
                clamp01(top / pageHeight),
                clamp01(right / pageWidth),
                clamp01(bottom / pageHeight));
        if (out.right < out.left) { float swap = out.left; out.left = out.right; out.right = swap; }
        if (out.bottom < out.top) { float swap = out.top; out.top = out.bottom; out.bottom = swap; }
        return out;
    }

    // ---- joining characters into something worth drawing ------------------------------------------

    /**
     * Joins per-character boxes into the fewest rectangles that still describe the match.
     *
     * <p>Drawing one translucent box per character produces a row of overlapping stripes with
     * visible seams where they meet, and a match that wraps a line would be drawn as a single
     * rectangle swallowing everything between the two lines. So boxes are merged along a line and
     * broken between lines: a phrase spanning a line break correctly becomes two highlights.
     */
    public static List<RectF> mergeLineFragments(List<RectF> boxes) {
        List<RectF> out = new ArrayList<>();
        if (boxes == null) return out;
        RectF current = null;
        for (RectF box : boxes) {
            if (box == null || box.width() < 0f || box.height() < 0f) continue;
            if (current == null) {
                current = new RectF(box);
                continue;
            }
            if (sameFragment(current, box)) {
                current.union(box);
            } else {
                out.add(current);
                current = new RectF(box);
            }
        }
        if (current != null) out.add(current);
        return out;
    }

    /** Whether a box continues the fragment being built, rather than starting a new one. */
    private static boolean sameFragment(RectF fragment, RectF box) {
        float overlap = Math.min(fragment.bottom, box.bottom) - Math.max(fragment.top, box.top);
        float shortest = Math.min(fragment.height(), box.height());
        if (shortest <= 0f) return false;
        if (overlap / shortest < SAME_LINE_OVERLAP) return false;
        // Reading order can run either way, so the gap is measured whichever side the box is on.
        float gap = box.left >= fragment.right ? box.left - fragment.right
                : (fragment.left >= box.right ? fragment.left - box.right : 0f);
        return gap <= shortest * FRAGMENT_GAP;
    }

    // ---- onto the screen --------------------------------------------------------------------------

    /**
     * A normalized rectangle in the coordinates of the view drawing the page.
     *
     * <p>Given the same origin and drawn size the image itself is painted with, which is what keeps
     * a highlight welded to its word through every zoom, pan and refit rather than needing to be
     * recomputed by whoever changed the transform.
     */
    public static RectF toView(RectF normalized, float drawnLeft, float drawnTop,
                               float drawnWidth, float drawnHeight) {
        if (normalized == null || drawnWidth <= 0f || drawnHeight <= 0f) return null;
        return new RectF(
                drawnLeft + normalized.left * drawnWidth,
                drawnTop + normalized.top * drawnHeight,
                drawnLeft + normalized.right * drawnWidth,
                drawnTop + normalized.bottom * drawnHeight);
    }

    /**
     * How far to pan so a highlight is on screen, or {@code {0,0}} when it already is.
     *
     * <p>The smallest movement that works, and nothing more: a zoomed reader who presses Next has
     * asked to see the next result, not to be thrown to a different part of the page. A result
     * already visible produces no movement at all, which is why pressing Next repeatedly through
     * matches in one paragraph does not jolt the page each time.
     */
    public static float[] panToReveal(RectF target, float viewWidth, float viewHeight,
                                      float margin) {
        float[] still = {0f, 0f};
        if (target == null || viewWidth <= 0f || viewHeight <= 0f) return still;
        float safeMargin = Math.max(0f, margin);
        float dx = 0f;
        float dy = 0f;
        if (target.left < safeMargin) dx = safeMargin - target.left;
        else if (target.right > viewWidth - safeMargin) dx = viewWidth - safeMargin - target.right;
        if (target.top < safeMargin) dy = safeMargin - target.top;
        else if (target.bottom > viewHeight - safeMargin) dy = viewHeight - safeMargin - target.bottom;
        // A highlight taller or wider than the viewport cannot be fully revealed; showing its start
        // is more useful than centring it and showing neither end.
        if (target.width() > viewWidth - safeMargin * 2f) dx = safeMargin - target.left;
        if (target.height() > viewHeight - safeMargin * 2f) dy = safeMargin - target.top;
        return new float[]{finite(dx) ? dx : 0f, finite(dy) ? dy : 0f};
    }

    private static float clamp01(float value) {
        if (!finite(value)) return 0f;
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    private static float positive(float value) {
        return finite(value) && value > 0f ? value : 0f;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
