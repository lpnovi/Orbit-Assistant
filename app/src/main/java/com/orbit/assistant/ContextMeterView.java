package com.orbit.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The chat header's context-window indicator (0.8.3.0-beta.3+): one thin ring, nothing else.
 *
 * <p>Deliberately small and quiet. It has no number on it; tapping it opens the details. At normal
 * usage the arc is drawn in the muted text colour and the whole ring recedes, so an ordinary chat
 * looks as it always did. It takes the accent once the window is filling, a warm amber when it is
 * high, and a soft red only in the last few percent - never a bright warning at forty percent.
 *
 * <p>When Orbit does not know the model's window there is no arc at all: a faint dashed circle says
 * "unknown" without pretending to a fullness it cannot measure.
 *
 * <p>Colours are read from {@link UiKit} every draw, so AMOLED and Theme Studio changes apply the
 * moment the header redraws. Arc changes animate briefly, and not at all with reduced motion.
 */
final class ContextMeterView extends View {
    static final int AMBER = Color.rgb(226, 172, 92);
    static final int SOFT_RED = Color.rgb(232, 122, 122);

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float ringDp;
    private float shown;
    private ContextEstimate estimate;
    private ValueAnimator animator;

    ContextMeterView(Context context) {
        this(context, 15f);
    }

    ContextMeterView(Context context, float ringDp) {
        super(context);
        this.ringDp = ringDp;
        track.setStyle(Paint.Style.STROKE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        float stroke = UiKit.dp(context, 2);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        setContentDescription("Context window. Tap for details.");
        setAlpha(0.7f);
    }

    /** Shows a new estimate. Null clears the ring back to its resting, unknown state. */
    void setEstimate(ContextEstimate value) {
        estimate = value;
        float target = value == null ? 0f : value.fraction();
        setContentDescription(describe(value));
        // Recede when there is nothing to say; come forward as the window fills.
        ContextEstimate.Level level = level();
        setAlpha(level == ContextEstimate.Level.NORMAL || level == ContextEstimate.Level.UNKNOWN
                ? 0.7f : 1f);
        if (animator != null) animator.cancel();
        if (!UiKit.animationsEnabled() || !isAttachedToWindow() || Math.abs(target - shown) < 0.002f) {
            shown = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(shown, target);
        animator.setDuration(UiKit.MOTION_STANDARD);
        animator.setInterpolator(UiKit.motionEasing());
        animator.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    ContextEstimate estimate() { return estimate; }

    ContextEstimate.Level level() {
        return estimate == null ? ContextEstimate.Level.UNKNOWN : estimate.level();
    }

    /** The arc colour for a level, from the live theme. */
    static int colorFor(Context c, ContextEstimate.Level level) {
        switch (level) {
            case CRITICAL: return SOFT_RED;
            case HIGH: return AMBER;
            case FILLING: return UiKit.accent(c);
            default: return UiKit.withAlpha(UiKit.MUTED, 220);
        }
    }

    static String describe(ContextEstimate value) {
        if (value == null) return "Context window. Tap for details.";
        if (!value.knowsLimit()) {
            return "Context window: about " + ContextEstimate.approx(value.tokens).substring(1)
                    + " tokens, limit unknown. Tap for details.";
        }
        return "Context window: about " + value.percent() + " percent used. Tap for details.";
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = UiKit.dp(getContext(), ringDp);
        float inset = track.getStrokeWidth() / 2f;
        float left = (getWidth() - size) / 2f + inset;
        float top = (getHeight() - size) / 2f + inset;
        bounds.set(left, top, left + size - 2 * inset, top + size - 2 * inset);

        ContextEstimate.Level level = level();
        boolean unknown = level == ContextEstimate.Level.UNKNOWN;
        track.setColor(UiKit.withAlpha(UiKit.MUTED, unknown ? 120 : 70));
        track.setPathEffect(unknown ? new DashPathEffect(new float[]{
                UiKit.dp(getContext(), 2), UiKit.dp(getContext(), 2.4f)}, 0) : null);
        canvas.drawOval(bounds, track);
        if (unknown) return;
        // A sliver is always visible once anything is sent, so "nearly empty" still reads as a meter.
        float sweep = Math.max(shown * 360f, shown > 0f ? 10f : 0f);
        if (sweep <= 0f) return;
        arc.setColor(colorFor(getContext(), level));
        canvas.drawArc(bounds, -90f, Math.min(360f, sweep), false, arc);
    }
}
