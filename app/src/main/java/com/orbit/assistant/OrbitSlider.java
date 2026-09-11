package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Orbit's control for a value along a range: a rounded track that fills with the accent behind a
 * round thumb.
 *
 * <p>Orbit had no such control before Theme Studio Pro, which is the only reason this file exists.
 * The alternative was a stock {@code SeekBar}, and a stock SeekBar in the middle of Theme Studio
 * would be the one control on the screen that did not look like Orbit - a different track height, a
 * different thumb, a platform ripple, and a colour that follows the Material theme rather than the
 * theme the user is editing. {@link OrbitSwitch} exists for exactly the same reason and is the
 * model this follows, down to the rule that a programmatic {@link #setValue} never calls the
 * listener while a gesture always does.
 *
 * <p>The value is an integer with a minimum and a maximum, because every setting Orbit adjusts this
 * way is one: a corner radius in points, an alpha, a percentage. A continuous float with a
 * formatter would be more general and would also make it possible to store a bubble radius of
 * 17.3183, which is not a thing anybody wants to have chosen.
 *
 * <p>Dragging reports continuously so a preview can follow the finger, and reports once more when
 * the finger lifts. That second report is what lets a caller do the expensive part of an update -
 * re-deriving identity, rebuilding a gallery - once at the end rather than on every pixel, which is
 * the difference between a slider that tracks and one that stutters.
 */
public class OrbitSlider extends View {

    public interface OnValueChangeListener {
        /**
         * @param settled false while the finger is still down, true once the gesture has finished
         *                or the value was changed by a key press or accessibility action.
         */
        void onValueChanged(OrbitSlider view, int value, boolean settled);
    }

    /** A short phrase for the current value, spoken and drawn beside the control by the caller. */
    public interface Labeller {
        String label(int value);
    }

    private static final int TRACK_HEIGHT_DP = 6;
    private static final int THUMB_DP = 20;
    /** Comfortable target, and the same figure OrbitSwitch reserves for the same reason. */
    private static final int TOUCH_HEIGHT_DP = 44;
    /** So a slider is never so short that a single step is a sub-pixel move. */
    private static final int MIN_WIDTH_DP = 160;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final float trackHeight;
    private final float thumbSize;

    private int min;
    private int max;
    private int value;

    private int accentColor;
    private int trackOffColor;
    private int thumbInkColor;

    private OnValueChangeListener listener;
    private Labeller labeller;
    private String title = "";
    private boolean dragging;

    public OrbitSlider(Context context) {
        super(context);
        trackHeight = UiKit.dp(context, TRACK_HEIGHT_DP);
        thumbSize = UiKit.dp(context, THUMB_DP);
        setClickable(true);
        setFocusable(true);
        applyAccent(context);
    }

    /** Re-reads the accent and surface colours, as OrbitSwitch does, for a live appearance change. */
    public void applyAccent(Context context) {
        accentColor = UiKit.accent(context);
        // Lifted off the card rather than a fixed grey, so the unfilled part of the track stays
        // visible on an ordinary dark surface and on a true-black AMOLED one alike.
        trackOffColor = UiKit.blend(UiKit.SURFACE_2, UiKit.TEXT, 0.18f);
        thumbInkColor = UiKit.onAccent(accentColor);
        invalidate();
    }

    /**
     * Sets the range and the starting value together.
     *
     * <p>One call rather than three setters, because a slider whose minimum has been updated and
     * whose maximum has not is briefly a slider with an impossible range, and the value would be
     * clamped into it.
     */
    public void setRange(int minimum, int maximum, int startAt) {
        min = minimum;
        max = Math.max(minimum, maximum);
        value = clamp(startAt);
        updateAccessibility();
        invalidate();
    }

    /** What this slider is called, for the value it announces to a screen reader. */
    public void setTitle(String value) {
        title = value == null ? "" : value;
        updateAccessibility();
    }

    public void setLabeller(Labeller value) {
        labeller = value;
        updateAccessibility();
    }

    public void setOnValueChangeListener(OnValueChangeListener value) {
        listener = value;
    }

    public int getValue() {
        return value;
    }

    public int getMinimum() {
        return min;
    }

    public int getMaximum() {
        return max;
    }

    /** Sets the value without notifying the listener. The programmatic path, as on OrbitSwitch. */
    public void setValue(int next) {
        int clamped = clamp(next);
        if (clamped == value) return;
        value = clamped;
        updateAccessibility();
        invalidate();
    }

    /** Sets the value as a gesture would: one notification, and one light tick. */
    private void changeTo(int next, boolean settled) {
        int clamped = clamp(next);
        boolean moved = clamped != value;
        if (!moved && !settled) return;
        value = clamped;
        updateAccessibility();
        invalidate();
        if (moved && Prefs.haptics(getContext())) {
            try { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
            catch (Exception ignored) {}
        }
        if (listener != null) listener.onValueChanged(this, value, settled);
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Theme Studio's controls live inside a ScrollView, and without this the first
                // vertical wobble of a drag hands the gesture to the scroller mid-adjustment.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                changeTo(valueAt(event.getX()), false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) changeTo(valueAt(event.getX()), false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (dragging) {
                    dragging = false;
                    // Settles the value the drag already reached. It deliberately does not read
                    // the lift coordinate: ACTION_UP carries its own x, which is routinely a few
                    // pixels from the last ACTION_MOVE as the finger rolls off the glass, so
                    // sampling it again moved the thumb one last time after the user had stopped.
                    // On the device that read as the control correcting itself. ACTION_CANCEL has
                    // no meaningful coordinate at all, and sampling it could land anywhere.
                    changeTo(value, true);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * Arrow keys move one step.
     *
     * <p>For a keyboard, for a connected mouse, and for anyone who finds a 20 point thumb a hard
     * target to drag accurately. Each press settles immediately, because there is no gesture in
     * progress to wait for.
     */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isEnabled()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MINUS) {
                changeTo(value - 1, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_PLUS) {
                changeTo(value + 1, true);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private int valueAt(float x) {
        float inset = thumbSize / 2f;
        float span = getWidth() - (inset * 2f);
        if (span <= 0f) return value;
        float fraction = (x - inset) / span;
        return min + Math.round(fraction * (max - min));
    }

    private int clamp(int next) {
        return next < min ? min : Math.min(next, max);
    }

    private float progress() {
        return max == min ? 0f : (value - min) / (float) (max - min);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize(UiKit.dp(getContext(), MIN_WIDTH_DP), widthMeasureSpec),
                resolveSize(UiKit.dp(getContext(), TOUCH_HEIGHT_DP), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float inset = thumbSize / 2f;
        float centreY = getHeight() / 2f;
        float left = inset;
        float right = getWidth() - inset;
        if (right <= left) return;
        float radius = trackHeight / 2f;

        paint.setColor(trackOffColor);
        track.set(left, centreY - radius, right, centreY + radius);
        canvas.drawRoundRect(track, radius, radius, paint);

        float filled = left + ((right - left) * progress());
        paint.setColor(accentColor);
        track.set(left, centreY - radius, Math.max(left, filled), centreY + radius);
        canvas.drawRoundRect(track, radius, radius, paint);

        // A thin ring of the accent's own foreground under the thumb, so the thumb stays a
        // distinct object where the filled track passes behind it on any accent.
        paint.setColor(thumbInkColor);
        canvas.drawCircle(filled, centreY, thumbSize / 2f, paint);
        paint.setColor(accentColor);
        canvas.drawCircle(filled, centreY, (thumbSize / 2f) - UiKit.dp(getContext(), 3), paint);
    }

    /**
     * What the control says it is, in words rather than only in position.
     *
     * <p>A filled track communicates nothing to a screen reader and very little to anybody who
     * cannot judge a small colour difference, so the current value is stated. Where the caller gave
     * a {@link Labeller} the phrase is used instead of the number, because "Orbit default" is the
     * answer somebody is looking for and "18" is not.
     */
    private void updateAccessibility() {
        String spoken = labeller == null ? String.valueOf(value) : labeller.label(value);
        setContentDescription(title.isEmpty() ? spoken : title + ", " + spoken);
        if (Build.VERSION.SDK_INT >= 30) setStateDescription(spoken);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, min, max, value));
    }
}
