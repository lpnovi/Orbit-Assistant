package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * Orbit's signature thinking indicator: a small breathing core with two particles on offset
 * elliptical orbits.
 *
 * <p>Drawing is driven by the animation clock rather than an {@link android.animation.Animator}.
 * {@link #onDraw} schedules the next frame with {@link #postInvalidateOnAnimation()}, so frames
 * stop by construction once the view is detached, hidden, or told to settle — nothing can keep
 * running behind a finished response. All paints and geometry are allocated once; the frame loop
 * allocates nothing.
 *
 * <p>Every colour is derived from the current Orbit accent, so accents, Dynamic accent, light,
 * dark, and AMOLED all follow the rest of the app with no colour of its own.
 */
public class OrbitThinkingView extends View {
    private static final long CYCLE_MS = 2600L;
    private static final long CORE_BREATH_MS = 1700L;
    /** Fraction of the outer orbit radius used by the inner orbit. */
    private static final float INNER_SCALE = 0.58f;
    /** Inner particle sweeps faster so the two never sit in lockstep. */
    private static final float INNER_RATE = 1.7f;
    private static final float OUTER_TILT_DEGREES = -18f;
    private static final float INNER_TILT_DEGREES = 34f;
    /** Orbits are ellipses rather than circles so the motion reads as dimensional. */
    private static final float OUTER_FLATTEN = 0.42f;
    private static final float INNER_FLATTEN = 0.60f;
    private static final long SETTLE_MS = 220L;

    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int accent;
    private boolean running;
    private long startedAt;
    /** Non-zero once the run is resolving; particles fall inward and the core fades. */
    private long settleStartedAt;
    private float settleProgress;

    public OrbitThinkingView(Context context) {
        super(context);
        setWillNotDraw(false);
        applyAccent();
        pathPaint.setStyle(Paint.Style.STROKE);
    }

    /** Re-reads the current accent. Cached because reading preferences per frame would be wasteful. */
    public void applyAccent() {
        accent = UiKit.accent(getContext());
        corePaint.setColor(accent);
        haloPaint.setColor(UiKit.withAlpha(accent, 46));
        pathPaint.setColor(UiKit.withAlpha(accent, 38));
        particlePaint.setColor(UiKit.withAlpha(accent, 224));
        invalidate();
    }

    public void start() {
        settleStartedAt = 0L;
        settleProgress = 0f;
        if (running) return;
        running = true;
        startedAt = AnimationUtils.currentAnimationTimeMillis();
        invalidate();
    }

    /**
     * Resolves the thinking state: the particles collapse toward the core and the whole indicator
     * fades as the answer takes its place. Never blocks the response; callers show content at once.
     */
    public void settle() {
        if (!running || settleStartedAt != 0L) return;
        if (!UiKit.animationsEnabled()) {
            stop();
            return;
        }
        settleStartedAt = AnimationUtils.currentAnimationTimeMillis();
        invalidate();
    }

    public void stop() {
        running = false;
        settleStartedAt = 0L;
        settleProgress = 0f;
    }

    public boolean isRunning() {
        return running;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int preferred = UiKit.dp(getContext(), 30);
        setMeasuredDimension(resolveSize(preferred, widthMeasureSpec),
                resolveSize(preferred, heightMeasureSpec));
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Frames only continue while actually shown, so a hidden indicator costs nothing.
        if (visibility == VISIBLE && running) invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!running) return;

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float unit = Math.min(getWidth(), getHeight()) / 2f;
        if (unit <= 0f) return;

        long now = AnimationUtils.currentAnimationTimeMillis();
        boolean animate = UiKit.animationsEnabled();

        if (settleStartedAt != 0L) {
            settleProgress = Math.min(1f, (now - settleStartedAt) / (float) SETTLE_MS);
            if (settleProgress >= 1f) {
                stop();
                return;
            }
        }

        // Eased collapse: particles draw inward and everything dims as the answer arrives.
        float collapse = settleProgress * settleProgress;
        float fade = 1f - settleProgress;
        float orbitScale = 1f - (0.85f * collapse);

        float phase = animate ? ((now - startedAt) % CYCLE_MS) / (float) CYCLE_MS : 0.12f;
        float breath = animate
                ? (float) Math.sin(((now - startedAt) % CORE_BREATH_MS) / (float) CORE_BREATH_MS
                        * 2f * Math.PI)
                : 0f;

        float outerRadius = unit * 0.78f * orbitScale;
        float innerRadius = outerRadius * INNER_SCALE;

        pathPaint.setStrokeWidth(Math.max(1f, unit * 0.045f));
        pathPaint.setAlpha(Math.round(38 * fade));
        drawOrbitPath(canvas, cx, cy, outerRadius, OUTER_FLATTEN, OUTER_TILT_DEGREES);
        drawOrbitPath(canvas, cx, cy, innerRadius, INNER_FLATTEN, INNER_TILT_DEGREES);

        // Core: a soft halo plus a solid centre that breathes very slightly.
        float coreRadius = unit * 0.24f * (1f + 0.09f * breath) * (1f - 0.35f * collapse);
        haloPaint.setAlpha(Math.round((40 + 16 * (breath + 1f) / 2f) * fade));
        canvas.drawCircle(cx, cy, coreRadius * 2.15f, haloPaint);
        corePaint.setAlpha(Math.round(255 * fade));
        canvas.drawCircle(cx, cy, coreRadius, corePaint);

        float particleRadius = unit * 0.115f * (1f - 0.3f * collapse);
        drawParticle(canvas, cx, cy, outerRadius, OUTER_FLATTEN, OUTER_TILT_DEGREES,
                phase, particleRadius, 224, fade);
        drawParticle(canvas, cx, cy, innerRadius, INNER_FLATTEN, INNER_TILT_DEGREES,
                phase * INNER_RATE + 0.35f, particleRadius * 0.82f, 190, fade);

        // A still frame is enough when the system has animations off; state stays readable.
        if (animate || settleStartedAt != 0L) postInvalidateOnAnimation();
    }

    private void drawOrbitPath(Canvas canvas, float cx, float cy, float radius, float flatten,
                               float tiltDegrees) {
        canvas.save();
        canvas.rotate(tiltDegrees, cx, cy);
        canvas.drawOval(cx - radius, cy - radius * flatten, cx + radius, cy + radius * flatten,
                pathPaint);
        canvas.restore();
    }

    private void drawParticle(Canvas canvas, float cx, float cy, float radius, float flatten,
                              float tiltDegrees, float phase, float particleRadius,
                              int baseAlpha, float fade) {
        double angle = phase * 2f * Math.PI;
        float localX = (float) Math.cos(angle) * radius;
        float localY = (float) Math.sin(angle) * radius * flatten;

        double tilt = Math.toRadians(tiltDegrees);
        float x = cx + (float) (localX * Math.cos(tilt) - localY * Math.sin(tilt));
        float y = cy + (float) (localX * Math.sin(tilt) + localY * Math.cos(tilt));

        // The far half of the orbit reads as behind the core: smaller and dimmer.
        float depth = (float) ((Math.sin(angle) + 1f) / 2f);
        float depthScale = 0.74f + 0.26f * depth;
        particlePaint.setAlpha(Math.round(baseAlpha * (0.55f + 0.45f * depth) * fade));
        canvas.drawCircle(x, y, particleRadius * depthScale, particlePaint);
    }
}
