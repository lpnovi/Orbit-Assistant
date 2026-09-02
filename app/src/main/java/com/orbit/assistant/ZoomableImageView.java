package com.orbit.assistant;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * One page of the attachment viewer: an image that can be pinched, double-tapped and panned.
 *
 * <p>All of the arithmetic lives in {@link ZoomPanController}; this is the part that cannot be
 * tested without a finger. Its whole job is to turn Android's gesture streams into the small number
 * of instructions that controller understands, and to answer one question honestly for the pager
 * above it: can this image still absorb a sideways drag.
 *
 * <p>Pinch owns a gesture outright. While two fingers are down the view asks its parents to stop
 * intercepting, so a pinch can never be mistaken for a swipe, and the scale detector's own focal
 * point is used rather than a midpoint of Orbit's own so zooming stays anchored where the fingers
 * are.
 *
 * <p>Nothing here is an editor. There is no crop, no rotation, no filter, and no export: the image
 * is drawn through a transform for looking at, and the bitmap it was given is never modified.
 */
public final class ZoomableImageView extends View {

    /** What the page tells the screen around it. */
    public interface Listener {
        /** A single confirmed tap: show or hide the viewer chrome. */
        void onTapped();
        /** The transform changed, so the pager may need to re-read who owns a drag. */
        void onTransformChanged();
    }

    private final ZoomPanController controller = new ZoomPanController();
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector taps;
    private final ScaleGestureDetector pinch;

    private Bitmap bitmap;
    private Listener listener;
    /** True from the moment a second finger lands until every finger is lifted. */
    private boolean pinching;
    private float lastPanX;
    private float lastPanY;
    private boolean panning;

    public ZoomableImageView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTapped();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                controller.toggleZoom(e.getX(), e.getY());
                applyTransform();
                return true;
            }
        });
        taps.setIsLongpressEnabled(false);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                controller.scaleBy(detector.getScaleFactor(),
                        detector.getFocusX(), detector.getFocusY());
                applyTransform();
                return true;
            }
        });
    }

    public void setListener(Listener listener) { this.listener = listener; }

    /**
     * Gives this page an image, or takes one away.
     *
     * <p>Taking one away is how the viewer keeps its memory bounded: a page outside the window
     * around the current one is handed null and stops holding a reference to anything decoded.
     * Setting an image always resets the transform, because a page reused for a different image
     * must not inherit the previous one's zoom.
     */
    public void setBitmap(Bitmap bitmap) {
        if (this.bitmap == bitmap) return;
        this.bitmap = bitmap;
        syncBounds();
        controller.reset();
        applyTransform();
        invalidate();
    }

    public boolean hasImage() { return bitmap != null && !bitmap.isRecycled(); }

    /** Back to fit-to-screen. Used when a page stops being the current one. */
    public void resetTransform() {
        controller.reset();
        applyTransform();
        invalidate();
    }

    public boolean isZoomed() { return controller.isZoomed(); }

    /** Whether the image itself still has content to reveal in this direction. */
    public boolean canPanHorizontally(int direction) {
        return controller.canPanHorizontally(direction);
    }

    /** For tests and for the pager: the live transform. */
    ZoomPanController controller() { return controller; }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        syncBounds();
        applyTransform();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        // Pinch first: while it is running nothing else may claim the gesture, and the parent is
        // told to keep its hands off so a two-finger zoom can never settle as a page change.
        pinch.onTouchEvent(event);
        taps.onTouchEvent(event);

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                pinching = false;
                panning = false;
                lastPanX = event.getX();
                lastPanY = event.getY();
                // A zoomed image expects to pan, so it asks to keep the gesture until it is proved
                // to be a page change. A fitted one makes no such claim.
                disallowIntercept(controller.isZoomed());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                pinching = true;
                panning = false;
                disallowIntercept(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (pinching || pinch.isInProgress() || event.getPointerCount() > 1) break;
                float x = event.getX();
                float y = event.getY();
                if (!panning) {
                    panning = true;
                    lastPanX = x;
                    lastPanY = y;
                    break;
                }
                float dx = x - lastPanX;
                float dy = y - lastPanY;
                lastPanX = x;
                lastPanY = y;
                // Panning happens only while zoomed. A drag on a fitted image is not a pan with
                // nowhere to go, it is a page change, and it belongs to the pager.
                if (!controller.isZoomed()) {
                    disallowIntercept(false);
                    break;
                }
                boolean moved = controller.panBy(dx, dy);
                applyTransform();
                // The image has reached its edge and the finger is still going that way: hand the
                // gesture up rather than holding a drag that can no longer do anything.
                int horizontal = dx > 0 ? -1 : (dx < 0 ? 1 : 0);
                if (!moved && horizontal != 0 && !controller.canPanHorizontally(horizontal)) {
                    disallowIntercept(false);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pinching = false;
                panning = false;
                disallowIntercept(false);
                break;
            default:
                break;
        }
        return true;
    }

    private void disallowIntercept(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    private void syncBounds() {
        controller.setBounds(getWidth(), getHeight(),
                hasImage() ? bitmap.getWidth() : 0, hasImage() ? bitmap.getHeight() : 0);
    }

    private void applyTransform() {
        if (listener != null) listener.onTransformChanged();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!hasImage() || !controller.hasContent()) return;
        float applied = controller.appliedScale();
        float drawnWidth = bitmap.getWidth() * applied;
        float drawnHeight = bitmap.getHeight() * applied;
        matrix.reset();
        matrix.postScale(applied, applied);
        matrix.postTranslate((getWidth() - drawnWidth) / 2f + controller.translationX(),
                (getHeight() - drawnHeight) / 2f + controller.translationY());
        canvas.drawBitmap(bitmap, matrix, paint);
    }
}
