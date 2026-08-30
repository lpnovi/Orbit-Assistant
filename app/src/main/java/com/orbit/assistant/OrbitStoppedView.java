package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * What Orbit's thinking indicator becomes when the user stops a reply: an interrupted orbit.
 *
 * <p>Deliberately the same visual language as {@link OrbitThinkingView} rather than an icon
 * borrowed from somewhere else. The core is still there and the orbit is still there, but the
 * orbit no longer closes: it is drawn as an arc with a clean cut, and the single remaining
 * particle has come to rest at the near edge of that cut. Nothing moves. Read next to a live
 * indicator it is obviously the same object, stopped.
 *
 * <p>What it is <em>not</em> is as much of the design as what it is. No red, no cross, no warning
 * triangle, no exclamation, no error colour of any kind, because stopping a reply is not a
 * failure: it is the user getting what they asked for. The whole mark is drawn in the same muted
 * ink the status line uses, so it sits below an answer in the visual hierarchy rather than
 * shouting over one.
 *
 * <p>Drawing is static by default. The one exception is {@link #resolve()}, used only when a stop
 * happens while the user is watching, which sweeps the cut open once so the live indicator appears
 * to settle into this rather than being replaced by it. It is a single short animation that ends
 * on its own; there is no loop and nothing to leak.
 */
public class OrbitStoppedView extends View {

    /** How much of the orbit is missing. Wide enough to read as deliberate at this size. */
    private static final float CUT_DEGREES = 96f;
    /**
     * The mark's natural size.
     *
     * <p>Beta 2 drew it at 22dp, and on a real Galaxy S25 Ultra that was small enough to be taken
     * for a rendering artifact or a stray flourish rather than a state. This is a little over a
     * quarter larger in each direction, which is enough to read as deliberate while staying far
     * quieter than an answer and well short of competing with the next question.
     */
    static final int SIZE_DP = 28;
    /** The same tilt the live indicator's outer orbit uses, so the two clearly match. */
    private static final float TILT_DEGREES = -18f;
    /** And the same flattening, so it reads as the same ellipse seen from the same angle. */
    private static final float FLATTEN = 0.42f;
    /** A single short sweep of the cut opening, used only for a stop the user is watching. */
    private static final long RESOLVE_MS = 260L;
    /** Below this the mark is lost against its background, so the muting is abandoned. */
    private static final double MIN_CONTRAST = 2.4d;
    /** How much of the full ink the resting mark keeps. Quieter than an answer, still legible. */
    private static final float MUTED = 0.62f;

    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF orbitBounds = new android.graphics.RectF();

    private int haloInk;
    private float haloRadius;
    private long resolveStartedAt;
    private float openness = 1f;

    public OrbitStoppedView(Context context) {
        this(context, UiKit.SURFACE);
    }

    public OrbitStoppedView(Context context, int backgroundColor) {
        super(context);
        setWillNotDraw(false);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        applyAccent(backgroundColor);
    }

    /**
     * Takes the mark's colour from the current accent, muted against whatever it is drawn on.
     *
     * <p>Same rule the live indicator follows: the accent is preferred, but an accent-coloured or
     * pastel bubble would swallow it, so the tone falls back to a readable one rather than
     * disappearing into its own background.
     */
    public void applyAccent(int backgroundColor) {
        int base = UiKit.accent(getContext());
        if (UiKit.contrastRatio(base, backgroundColor) < MIN_CONTRAST) {
            base = UiKit.onBubble(backgroundColor);
        }
        int ink = UiKit.blend(base, backgroundColor, MUTED);
        if (UiKit.contrastRatio(ink, backgroundColor) < MIN_CONTRAST) ink = base;
        corePaint.setColor(ink);
        arcPaint.setColor(UiKit.withAlpha(ink, 165));
        particlePaint.setColor(UiKit.withAlpha(ink, 225));
        haloInk = ink;
        haloRadius = 0f;
        invalidate();
    }

    /**
     * A very faint glow under the mark, so it settles on the page instead of floating on it.
     *
     * <p>Deliberately a soft gradient that fades to nothing rather than a filled shape: the mark
     * must never acquire the outline of a bubble, because a bubble would say a message is here and
     * the whole point is that no message arrived. It only has to lift a few thin strokes off the
     * background enough to look placed.
     */
    private void prepareHalo(float radius) {
        if (radius <= 0f || Math.abs(radius - haloRadius) < 0.5f) return;
        haloRadius = radius;
        haloPaint.setShader(new android.graphics.RadialGradient(0f, 0f, radius,
                new int[]{UiKit.withAlpha(haloInk, 30), UiKit.withAlpha(haloInk, 16), UiKit.withAlpha(haloInk, 0)},
                new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
    }

    /**
     * Plays the one-time settle, for a stop the user is watching happen.
     *
     * <p>The cut sweeps open from a closed ring, which is what makes the live orbital look like it
     * came to rest here instead of vanishing and being replaced. Skipped entirely when the system
     * has animations off, in which case the finished mark is simply drawn.
     */
    public void resolve() {
        if (!UiKit.animationsEnabled()) {
            openness = 1f;
            invalidate();
            return;
        }
        openness = 0f;
        resolveStartedAt = AnimationUtils.currentAnimationTimeMillis();
        invalidate();
    }

    /** True while the one-time settle is still playing. Nothing repeats after it finishes. */
    public boolean isResolving() { return resolveStartedAt != 0L; }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int preferred = UiKit.dp(getContext(), SIZE_DP);
        setMeasuredDimension(resolveSize(preferred, widthMeasureSpec),
                resolveSize(preferred, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float unit = Math.min(getWidth(), getHeight()) / 2f;
        if (unit <= 0f) return;

        if (resolveStartedAt != 0L) {
            long elapsed = AnimationUtils.currentAnimationTimeMillis() - resolveStartedAt;
            openness = Math.min(1f, elapsed / (float) RESOLVE_MS);
            if (openness >= 1f) resolveStartedAt = 0L;
        }

        float radius = unit * 0.80f;
        float cut = CUT_DEGREES * openness;
        // The arc is centred so the gap sits at the upper right, where the eye lands first.
        float start = -48f + cut / 2f;
        float sweep = 360f - cut;

        // The faint backing goes down first, so every stroke above sits on it rather than in it.
        prepareHalo(unit * 0.95f);
        if (haloPaint.getShader() != null) {
            canvas.save();
            canvas.translate(cx, cy);
            canvas.drawCircle(0f, 0f, haloRadius, haloPaint);
            canvas.restore();
        }

        arcPaint.setStrokeWidth(Math.max(1.4f, unit * 0.125f));
        canvas.save();
        canvas.rotate(TILT_DEGREES, cx, cy);
        orbitBounds.set(cx - radius, cy - radius * FLATTEN, cx + radius, cy + radius * FLATTEN);
        canvas.drawArc(orbitBounds, start, sweep, false, arcPaint);
        canvas.restore();

        // The core, at rest: no breathing, smaller than the live indicator's.
        canvas.drawCircle(cx, cy, unit * 0.215f, corePaint);

        // One particle, stopped at the near edge of the cut. Drawn on the ellipse itself so it
        // reads as having come to rest on the orbit rather than being decoration beside it.
        drawRestingParticle(canvas, cx, cy, radius, start + sweep, unit * 0.125f);

        if (resolveStartedAt != 0L) postInvalidateOnAnimation();
    }

    private void drawRestingParticle(Canvas canvas, float cx, float cy, float radius,
                                     float angleDegrees, float particleRadius) {
        double angle = Math.toRadians(angleDegrees);
        float localX = (float) Math.cos(angle) * radius;
        float localY = (float) Math.sin(angle) * radius * FLATTEN;
        double tilt = Math.toRadians(TILT_DEGREES);
        float x = cx + (float) (localX * Math.cos(tilt) - localY * Math.sin(tilt));
        float y = cy + (float) (localX * Math.sin(tilt) + localY * Math.cos(tilt));
        canvas.drawCircle(x, y, particleRadius, particlePaint);
    }
}
