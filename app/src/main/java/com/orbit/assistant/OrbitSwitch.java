package com.orbit.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Orbit's binary settings control: a rounded pill track whose thumb slides across as the track
 * takes on the current accent.
 *
 * <p>Used for persistent on/off state. Selection inside a form or a list stays on a real
 * {@code CheckBox}, because a switch reads as "this is in effect now" rather than "this is
 * ticked".
 *
 * <p>Programmatic changes through {@link #setChecked} never fire the listener, so a caller that
 * has to roll a setting back — a permission the user denied, a value the store rejected — can
 * correct the control without re-entering its own handler. Only a user gesture, or an explicit
 * {@link #toggle()} standing in for one, reports a change.
 */
public class OrbitSwitch extends View {
    public interface OnCheckedChangeListener {
        void onCheckedChanged(OrbitSwitch view, boolean checked);
    }

    private static final int TRACK_WIDTH_DP = 50;
    private static final int TRACK_HEIGHT_DP = 30;
    private static final int THUMB_DP = 24;
    /** Comfortable target regardless of how tightly the surrounding row is packed. */
    private static final int TOUCH_HEIGHT_DP = 44;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final float trackWidth;
    private final float trackHeight;
    private final float thumbSize;

    private boolean checked;
    /** 0 at rest on the off side, 1 on the on side. Drives both thumb travel and track colour. */
    private float progress;
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    private int accentColor;
    private int thumbOnColor;
    private int trackOffColor;
    private int thumbOffColor;

    public OrbitSwitch(Context context) {
        super(context);
        trackWidth = UiKit.dp(context, TRACK_WIDTH_DP);
        trackHeight = UiKit.dp(context, TRACK_HEIGHT_DP);
        thumbSize = UiKit.dp(context, THUMB_DP);
        setClickable(true);
        setFocusable(true);
        applyAccent(context);
    }

    /**
     * Re-reads accent and surface colours. Called on construction and again whenever Orbit's
     * appearance changes, so a live accent or AMOLED switch reaches an already-visible control.
     */
    public void applyAccent(Context context) {
        accentColor = UiKit.accent(context);
        thumbOnColor = UiKit.onAccent(context);
        // Lifted off the card rather than a fixed grey, so "off" stays legible on both the
        // ordinary dark surface and a true-black AMOLED one without reading as broken.
        trackOffColor = UiKit.blend(UiKit.SURFACE_2, Color.rgb(126, 132, 148), 0.42f);
        thumbOffColor = Color.rgb(214, 218, 228);
        invalidate();
    }

    public boolean isChecked() {
        return checked;
    }

    /** Sets state with animation and without notifying the listener. */
    public void setChecked(boolean value) {
        setChecked(value, true);
    }

    /** Sets state without notifying the listener, optionally skipping the animation. */
    public void setChecked(boolean value, boolean animate) {
        if (checked == value) {
            // Still settle the visual, in case a previous animation was interrupted.
            if (!animate) applyProgressImmediately(value ? 1f : 0f);
            return;
        }
        checked = value;
        moveTo(value ? 1f : 0f, animate);
        announceState();
    }

    /** Flips state as a user gesture would, notifying the listener once. */
    public void toggle() {
        if (!isEnabled()) return;
        checked = !checked;
        moveTo(checked ? 1f : 0f, true);
        announceState();
        if (listener != null) listener.onCheckedChanged(this, checked);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener value) {
        listener = value;
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
        invalidate();
    }

    @Override public boolean performClick() {
        // Runs for accessibility activation as well as an ordinary tap.
        super.performClick();
        toggle();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        return super.onTouchEvent(event);
    }

    private void moveTo(float target, boolean animate) {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (!animate || !UiKit.animationsEnabled()) {
            applyProgressImmediately(target);
            return;
        }
        ValueAnimator running = ValueAnimator.ofFloat(progress, target);
        // Orbit's shared timing for a state change, rather than a duration invented here.
        running.setDuration(UiKit.MOTION_STANDARD);
        running.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator = running;
        running.start();
    }

    private void applyProgressImmediately(float target) {
        progress = target;
        invalidate();
    }

    private void announceState() {
        if (Build.VERSION.SDK_INT >= 30) {
            setStateDescription(checked ? "On" : "Off");
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize(Math.round(trackWidth), widthMeasureSpec),
                resolveSize(UiKit.dp(getContext(), TOUCH_HEIGHT_DP), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = (getWidth() - trackWidth) / 2f;
        float top = (getHeight() - trackHeight) / 2f;
        track.set(left, top, left + trackWidth, top + trackHeight);
        float radius = trackHeight / 2f;

        // blend() returns a packed int, so the colour for each frame costs no allocation.
        paint.setColor(UiKit.blend(accentColor, trackOffColor, progress));
        canvas.drawRoundRect(track, radius, radius, paint);

        float inset = (trackHeight - thumbSize) / 2f;
        float travel = trackWidth - thumbSize - (inset * 2f);
        float thumbLeft = left + inset + (travel * progress);
        float thumbCenterY = top + (trackHeight / 2f);
        paint.setColor(UiKit.blend(thumbOnColor, thumbOffColor, progress));
        canvas.drawCircle(thumbLeft + (thumbSize / 2f), thumbCenterY, thumbSize / 2f, paint);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        // Announced as a real switch so TalkBack reads and toggles it like any other.
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
        info.setClickable(isEnabled());
        info.setEnabled(isEnabled());
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }
}
