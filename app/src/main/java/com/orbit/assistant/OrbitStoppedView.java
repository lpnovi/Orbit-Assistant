package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * What Orbit's thinking indicator becomes when the user stops a reply: an interrupted orbit, come
 * to rest.
 *
 * <p>Deliberately the same visual language as {@link OrbitThinkingView} rather than an icon
 * borrowed from somewhere else. The core is still there and the orbit is still there, but the
 * orbit no longer closes and nothing moves. Read next to a live indicator it is obviously the same
 * object, stopped.
 *
 * <p>Beta 3 drew that idea as a small square glyph, and on a real Galaxy S25 Ultra it still read as
 * an icon sitting at the left edge of the response lane rather than as the response lane coming to
 * an end. The shape here is the same idea given the width it needed: a wide, shallow orbit spanning
 * the lane, broken symmetrically above and below its centre, with a particle at rest at each
 * extreme and the core sitting in the break between them. Left to right it reads
 * {@code particle — orbit — core — orbit — particle}, which is a line that has stopped rather than
 * a badge that has been placed.
 *
 * <p>What it is <em>not</em> is as much of the design as what it is. No red, no cross, no warning
 * triangle, no exclamation, no error colour of any kind, because stopping a reply is not a failure:
 * it is the user getting what they asked for. The whole mark is drawn in the same muted ink the
 * status line uses, so it sits below an answer in the visual hierarchy rather than shouting over
 * one. It carries no text; the words live in its content description, for the people who need them.
 *
 * <p>Drawing is static by default. The one exception is {@link #resolve()}, used only when a stop
 * happens while the user is watching, which plays a single settling motion so the live indicator
 * appears to come to rest here rather than being replaced by it. It ends on its own; there is no
 * loop and nothing to leak.
 */
public class OrbitStoppedView extends View {

    /**
     * The mark's natural size, as a wide lane-spanning line rather than a square glyph.
     *
     * <p>Beta 2 drew it at 22dp square and Beta 3 at 28dp square. Both read as small icons. The
     * width here is what carries the meaning; the height stays close to Beta 3's so the mark's
     * vertical footprint in a conversation is unchanged and it still cannot be mistaken for a
     * message. Full chat uses this; the overlay is given a slightly tighter width below.
     */
    public static final int WIDTH_DP = 116;
    public static final int HEIGHT_DP = 30;
    /** The overlay sheet is narrower than full chat, so the same mark is drawn a little tighter. */
    public static final int OVERLAY_WIDTH_DP = 96;

    /** How much of the orbit is missing at each break. Two of these, top and bottom of centre. */
    private static final float CUT_DEGREES = 58f;
    /** A gentle tilt: enough to echo the live indicator's dimensionality, not enough to slant. */
    private static final float TILT_DEGREES = -5f;
    /** Below this the mark is lost against its background, so the muting is abandoned. */
    private static final double MIN_CONTRAST = 2.4d;
    /** How much of the full ink the resting mark keeps. Quieter than an answer, still legible. */
    private static final float MUTED = 0.62f;

    /**
     * How long the live indicator takes to become this.
     *
     * <p>Beta 3's 260ms was over before it could be read, which made a stop look like a swap rather
     * than a settle. This is long enough for the four things that happen in it to be seen
     * separately and short enough that nobody is waiting on it: the mark is already terminal state
     * from the first frame, and skipping the whole animation loses nothing but the pleasure of it.
     */
    private static final long RESOLVE_MS = 620L;
    /** How far ahead of rest the particles start, so there is visible travel to decelerate from. */
    private static final float SPIN_DEGREES = 132f;
    /** How wide the orbit starts, as a fraction of its final width: about the live indicator's. */
    private static final float START_WIDTH = 0.30f;

    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF orbitBounds = new android.graphics.RectF();

    private int haloInk;
    private float haloRadius;
    private long resolveStartedAt;
    private float progress = 1f;

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
     * A very faint glow under the core, so the mark settles on the page instead of floating on it.
     *
     * <p>Deliberately a soft gradient that fades to nothing rather than a filled shape: the mark
     * must never acquire the outline of a bubble, because a bubble would say a message is here and
     * the whole point is that no message arrived.
     */
    private void prepareHalo(float radius) {
        if (radius <= 0f || Math.abs(radius - haloRadius) < 0.5f) return;
        haloRadius = radius;
        haloPaint.setShader(new android.graphics.RadialGradient(0f, 0f, radius,
                new int[]{UiKit.withAlpha(haloInk, 32), UiKit.withAlpha(haloInk, 17), UiKit.withAlpha(haloInk, 0)},
                new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
    }

    /**
     * Plays the one-time settle, for a stop the user is watching happen.
     *
     * <p>Four things happen, in overlapping order, so the mark is arrived at rather than cut to:
     * the particles decelerate out of their orbit, the orbit widens from roughly the live
     * indicator's compact size out to the full lane-spanning one, the two breaks open at the top
     * and bottom of centre, and the core settles down onto its halo. Skipped entirely when the
     * system has animations turned down, in which case the finished mark is simply drawn.
     */
    public void resolve() {
        if (!UiKit.animationsEnabled()) {
            progress = 1f;
            resolveStartedAt = 0L;
            invalidate();
            return;
        }
        progress = 0f;
        resolveStartedAt = AnimationUtils.currentAnimationTimeMillis();
        invalidate();
    }

    /** True while the one-time settle is still playing. Nothing repeats after it finishes. */
    public boolean isResolving() { return resolveStartedAt != 0L; }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize(UiKit.dp(getContext(), WIDTH_DP), widthMeasureSpec),
                resolveSize(UiKit.dp(getContext(), HEIGHT_DP), heightMeasureSpec));
    }

    /** Decelerating ease. Everything in the settle slows into place; nothing accelerates out of it. */
    private static float settle(float t) {
        float clamped = t < 0f ? 0f : (t > 1f ? 1f : t);
        float inverse = 1f - clamped;
        return 1f - inverse * inverse * inverse;
    }

    /** Maps overall progress onto one overlapping phase of the settle. */
    private static float phase(float p, float from, float to) {
        return settle((p - from) / (to - from));
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float halfHeight = getHeight() / 2f;
        float halfWidth = getWidth() / 2f;
        if (halfHeight <= 0f || halfWidth <= 0f) return;

        if (resolveStartedAt != 0L) {
            long elapsed = AnimationUtils.currentAnimationTimeMillis() - resolveStartedAt;
            progress = Math.min(1f, elapsed / (float) RESOLVE_MS);
            if (progress >= 1f) resolveStartedAt = 0L;
        }
        float p = progress;

        // The four overlapping phases. At rest every one of them is 1 and this is a static shape.
        float spin = SPIN_DEGREES * (1f - phase(p, 0f, 0.45f));
        float widen = START_WIDTH + (1f - START_WIDTH) * phase(p, 0.10f, 0.72f);
        float opened = phase(p, 0.50f, 1f);
        float landed = phase(p, 0.30f, 1f);

        // Width is what makes this the stopped state rather than a small glyph, so the orbit is
        // measured from the view's width and only its depth from the height.
        float rx = halfWidth * 0.88f * widen;
        float ry = halfHeight * 0.60f;
        float coreRadius = halfHeight * 0.30f * (1f + 0.20f * (1f - landed));
        float particleRadius = halfHeight * 0.155f;
        float cut = CUT_DEGREES * opened;

        // The faint backing goes down first, so every stroke above sits on it rather than in it.
        prepareHalo(halfHeight * 1.05f);
        if (haloPaint.getShader() != null) {
            canvas.save();
            canvas.translate(cx, cy);
            int haloAlpha = Math.round(255 * landed);
            haloPaint.setAlpha(haloAlpha);
            canvas.drawCircle(0f, 0f, haloRadius, haloPaint);
            canvas.restore();
        }

        arcPaint.setStrokeWidth(Math.max(1.4f, halfHeight * 0.105f));
        canvas.save();
        canvas.rotate(TILT_DEGREES, cx, cy);
        orbitBounds.set(cx - rx, cy - ry, cx + rx, cy + ry);
        // Two arcs, broken symmetrically above and below the centre, so the interruption sits
        // where the core is rather than off at one shoulder.
        float sweep = 180f - cut;
        canvas.drawArc(orbitBounds, -(sweep / 2f), sweep, false, arcPaint);
        canvas.drawArc(orbitBounds, 180f - (sweep / 2f), sweep, false, arcPaint);
        canvas.restore();

        // The core, at rest in the break: no breathing, smaller than the live indicator's.
        canvas.drawCircle(cx, cy, coreRadius, corePaint);

        // One particle at rest at each extreme of the orbit. During the settle they are still
        // travelling, which is what makes the finished mark look arrived at.
        drawRestingParticle(canvas, cx, cy, rx, ry, spin, particleRadius, 225);
        drawRestingParticle(canvas, cx, cy, rx, ry, 180f + spin, particleRadius * 0.88f, 196);

        if (resolveStartedAt != 0L) postInvalidateOnAnimation();
    }

    private void drawRestingParticle(Canvas canvas, float cx, float cy, float rx, float ry,
                                     float angleDegrees, float particleRadius, int alpha) {
        double angle = Math.toRadians(angleDegrees);
        float localX = (float) Math.cos(angle) * rx;
        float localY = (float) Math.sin(angle) * ry;
        double tilt = Math.toRadians(TILT_DEGREES);
        float x = cx + (float) (localX * Math.cos(tilt) - localY * Math.sin(tilt));
        float y = cy + (float) (localX * Math.sin(tilt) + localY * Math.cos(tilt));
        particlePaint.setAlpha(alpha);
        canvas.drawCircle(x, y, particleRadius, particlePaint);
    }
}
