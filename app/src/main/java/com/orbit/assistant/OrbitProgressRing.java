package com.orbit.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;

/**
 * A small circular progress mark in the current accent, for work that belongs inside a control
 * rather than on a card of its own (v0.8.1.0-beta.2, first used by Smart Vault's model download).
 *
 * <p>Determinate progress glides to each new value over {@link UiKit#MOTION_STANDARD}, so a
 * download reporting every few hundred kilobytes reads as one continuous movement rather than a
 * series of jumps, and the owner never has to rebuild anything to show it. Indeterminate mode is a
 * short arc turning slowly. With system animations off, both are drawn still: the arc at its value,
 * or a fixed quarter for indeterminate.
 */
public class OrbitProgressRing extends View {

    private static final int SIZE_DP = 22;
    private static final float STROKE_DP = 2.6f;
    private static final long TURN_MS = 1100L;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private boolean indeterminate;
    private int target;
    /** What is drawn, 0 to 100; trails {@link #target} while an animation runs. */
    private float shown;
    private float spin;
    private ValueAnimator glide;
    private ValueAnimator turn;

    public OrbitProgressRing(Context context) {
        super(context);
        float stroke = UiKit.dp(context, 1) * STROKE_DP;
        for (Paint p : new Paint[]{track, arc}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        applyAccent(context);
    }

    /** Re-reads the accent, so a live accent or AMOLED change reaches a visible ring. */
    public void applyAccent(Context context) {
        int accent = UiKit.accent(context);
        arc.setColor(accent);
        track.setColor(UiKit.withAlpha(accent, 46));
        invalidate();
    }

    public int progress() {
        return target;
    }

    /** What is drawn this frame, which trails {@link #progress()} while gliding. */
    float drawnProgress() {
        return shown;
    }

    public boolean isIndeterminate() {
        return indeterminate;
    }

    /** Moves to {@code percent}, gliding when animations are on. */
    public void setProgress(int percent) {
        int value = Math.max(0, Math.min(100, percent));
        boolean wasIndeterminate = indeterminate;
        indeterminate = false;
        stopTurning();
        if (value == target && !wasIndeterminate) return;
        target = value;
        if (glide != null) glide.cancel();
        glide = null;
        if (!UiKit.animationsEnabled() || !isAttachedToWindow() || wasIndeterminate) {
            shown = value;
            invalidate();
            return;
        }
        ValueAnimator running = ValueAnimator.ofFloat(shown, value);
        running.setDuration(UiKit.MOTION_STANDARD);
        running.setInterpolator(UiKit.motionEasing());
        running.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        glide = running;
        running.start();
    }

    /** Work whose size is not known yet. */
    public void setIndeterminate() {
        if (indeterminate) return;
        indeterminate = true;
        if (glide != null) glide.cancel();
        glide = null;
        startTurning();
        invalidate();
    }

    private void startTurning() {
        stopTurning();
        if (!indeterminate || !UiKit.animationsEnabled() || !isAttachedToWindow()) return;
        ValueAnimator running = ValueAnimator.ofFloat(0f, 360f);
        running.setDuration(TURN_MS);
        running.setRepeatCount(ValueAnimator.INFINITE);
        running.setInterpolator(new LinearInterpolator());
        running.addUpdateListener(a -> {
            spin = (float) a.getAnimatedValue();
            invalidate();
        });
        turn = running;
        running.start();
    }

    private void stopTurning() {
        if (turn != null) turn.cancel();
        turn = null;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (indeterminate) startTurning();
    }

    @Override protected void onDetachedFromWindow() {
        stopTurning();
        if (glide != null) glide.cancel();
        glide = null;
        shown = target;
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = UiKit.dp(getContext(), SIZE_DP);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float inset = arc.getStrokeWidth() / 2f + 1f;
        float side = Math.min(getWidth(), getHeight()) - inset * 2f;
        float left = (getWidth() - side) / 2f;
        float top = (getHeight() - side) / 2f;
        bounds.set(left, top, left + side, top + side);
        canvas.drawOval(bounds, track);
        if (indeterminate) {
            canvas.drawArc(bounds, spin - 90f, 90f, false, arc);
        } else if (shown > 0f) {
            canvas.drawArc(bounds, -90f, 360f * shown / 100f, false, arc);
        }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.ProgressBar");
        if (!indeterminate) {
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, target));
        }
    }
}
