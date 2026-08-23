package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Orbit's long-press message selection: an accent ripple that travels out from where the finger
 * actually landed, a faint wash carrying it through the message, and a settled hairline ring that
 * holds while the contextual menu is open.
 *
 * <p>Drawn as the message's <em>foreground</em>, so the bubble is never resized, no text reflows,
 * and no surrounding message moves. Nothing here reads a theme at draw time: the accent is
 * resolved once when the selection starts, which is what keeps it correct in Dark, AMOLED, Light,
 * Dynamic, and custom accents alike.
 *
 * <p>Frames follow {@link OrbitListeningHalo}'s model rather than an animator: the drawable
 * schedules its own next frame only while something is actually moving, allocates nothing per
 * frame, and stops scheduling the moment the ripple settles. The held state is therefore a static
 * drawable, and {@link #release(Runnable)} is the one way out of it.
 */
public class OrbitMessageHighlight extends Drawable {
    /** How long the accent ripple takes to travel across the message. */
    private static final long RIPPLE_MS = 430L;
    /** The wash arrives with the ripple's leading edge rather than after it. */
    private static final long WASH_MS = 180L;
    /** The edge pulse peaks early, then settles into the held selection ring. */
    private static final long RING_MS = 300L;
    private static final long RING_PEAK_MS = 110L;
    /** Letting go: everything fades out together, once. */
    private static final long RELEASE_MS = 170L;
    private static final long FRAME_MS = 16L;

    /** Held selection is a wash, not a highlight: the message must stay comfortably readable. */
    private static final int WASH_HOLD_ALPHA = 20;
    private static final int WASH_PEAK_ALPHA = 36;
    private static final int RING_HOLD_ALPHA = 96;
    private static final int RING_PEAK_ALPHA = 178;
    /** Modulates the gradient below, so the travelling band peaks well short of opaque. */
    private static final int RIPPLE_PEAK_ALPHA = 176;
    private static final int RIPPLE_BAND_ALPHA = 150;

    private final Paint washPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF surface = new RectF();
    private final RectF edge = new RectF();
    private final Path clip = new Path();
    private final Matrix rippleMatrix = new Matrix();
    private final Runnable frame = this::invalidateSelf;

    private final int accent;
    private final float cornerRadius;
    private final float rippleUnit;
    private final float ringPeakWidth;
    private final float ringHoldWidth;

    private float pressX;
    private float pressY;
    private boolean pressPointKnown;
    private RadialGradient rippleShader;
    private float rippleMaxScale = 1f;

    private final long startedAt;
    private long releasedAt;
    private boolean released;
    private boolean finished;

    public OrbitMessageHighlight(Context context, float cornerRadiusDp) {
        accent = UiKit.accent(context);
        cornerRadius = UiKit.dp(context, cornerRadiusDp);
        rippleUnit = Math.max(1f, UiKit.dp(context, 30));
        ringPeakWidth = UiKit.dp(context, 2);
        ringHoldWidth = Math.max(1f, UiKit.dp(context, 1));
        washPaint.setColor(accent);
        ringPaint.setColor(accent);
        ringPaint.setStyle(Paint.Style.STROKE);
        startedAt = SystemClock.uptimeMillis();
    }

    /**
     * Points the ripple at the press, in coordinates local to the message. Values outside the
     * bubble are clamped rather than rejected, so a press on a child of a rich reply still
     * originates somewhere sensible inside it.
     */
    public void setPressPoint(float x, float y) {
        pressX = x;
        pressY = y;
        pressPointKnown = true;
        rippleShader = null;
        invalidateSelf();
    }

    /**
     * Starts the fade out. Deliberately reports nothing back: the caller drops the foreground on
     * its own fixed schedule instead, because a drawable that called back from its last frame or
     * from being detached would be asking the view to change its foreground from inside
     * {@code setForeground} and {@code draw}.
     */
    public void release() {
        if (finished || released) return;
        released = true;
        releasedAt = SystemClock.uptimeMillis();
        if (!UiKit.animationsEnabled()) {
            finish();
            return;
        }
        invalidateSelf();
    }

    /** How long a released selection takes to disappear, so callers can time dropping it. */
    static long releaseDurationMs() {
        return RELEASE_MS;
    }

    @Override public void draw(Canvas canvas) {
        if (finished) return;
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        surface.set(b);

        boolean animate = UiKit.animationsEnabled();
        long now = SystemClock.uptimeMillis();
        // A released selection scales everything down together, so the wash, the ring, and any
        // ripple still in flight leave as one gesture instead of three.
        float fade = 1f;
        if (released) {
            fade = animate ? 1f - clamp((now - releasedAt) / (float) RELEASE_MS) : 0f;
            if (fade <= 0f) {
                finish();
                return;
            }
        }

        long elapsed = animate ? Math.max(0L, now - startedAt) : RIPPLE_MS;
        drawWash(canvas, elapsed, fade);
        if (animate && elapsed < RIPPLE_MS) drawRipple(canvas, elapsed, fade);
        drawRing(canvas, elapsed, fade);

        if (released || (animate && elapsed < Math.max(RIPPLE_MS, RING_MS))) {
            scheduleSelf(frame, now + FRAME_MS);
        }
    }

    /** The faint accent wash that makes the whole message read as the selected one. */
    private void drawWash(Canvas canvas, long elapsed, float fade) {
        float alpha;
        if (elapsed <= WASH_MS) {
            alpha = WASH_PEAK_ALPHA * clamp(elapsed / (float) WASH_MS);
        } else {
            float settle = clamp((elapsed - WASH_MS) / (float) RING_MS);
            alpha = WASH_PEAK_ALPHA + (WASH_HOLD_ALPHA - WASH_PEAK_ALPHA) * settle;
        }
        washPaint.setAlpha(alphaOf(alpha * fade));
        canvas.drawRoundRect(surface, cornerRadius, cornerRadius, washPaint);
    }

    /**
     * The travelling band of accent. A radial gradient is built once per press and moved outward
     * with a reused matrix, so the whole effect is one shader and no per-frame allocation.
     */
    private void drawRipple(Canvas canvas, long elapsed, float fade) {
        if (rippleShader == null && !buildRipple()) return;
        float t = clamp(elapsed / (float) RIPPLE_MS);
        // Fast out, gently away: the band is at strength almost immediately and thins as it
        // spreads, which is what makes it read as energy passing through rather than a flash.
        float eased = 1f - (1f - t) * (1f - t);
        float scale = 0.18f + (rippleMaxScale - 0.18f) * eased;
        float strength = t < 0.14f ? t / 0.14f : 1f - ((t - 0.14f) / 0.86f);

        rippleMatrix.setScale(scale, scale, pressX, pressY);
        rippleShader.setLocalMatrix(rippleMatrix);
        ripplePaint.setAlpha(alphaOf(RIPPLE_PEAK_ALPHA * strength * fade));
        int saved = canvas.save();
        // Clipped to the bubble's own rounded shape, so the ripple never paints over a corner or
        // spills onto the conversation behind it.
        clip.rewind();
        clip.addRoundRect(surface, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawRect(surface, ripplePaint);
        canvas.restoreToCount(saved);
    }

    /** One restrained pulse along the bubble's edge, settling into the held selection ring. */
    private void drawRing(Canvas canvas, long elapsed, float fade) {
        float alpha;
        float width;
        if (elapsed <= RING_PEAK_MS) {
            float rise = clamp(elapsed / (float) RING_PEAK_MS);
            alpha = RING_PEAK_ALPHA * rise;
            width = ringHoldWidth + (ringPeakWidth - ringHoldWidth) * rise;
        } else {
            float settle = clamp((elapsed - RING_PEAK_MS) / (float) (RING_MS - RING_PEAK_MS));
            alpha = RING_PEAK_ALPHA + (RING_HOLD_ALPHA - RING_PEAK_ALPHA) * settle;
            width = ringPeakWidth + (ringHoldWidth - ringPeakWidth) * settle;
        }
        float inset = width / 2f;
        edge.set(surface.left + inset, surface.top + inset,
                surface.right - inset, surface.bottom - inset);
        if (edge.width() <= 0 || edge.height() <= 0) return;
        float radius = Math.max(0f, cornerRadius - inset);
        ringPaint.setStrokeWidth(width);
        ringPaint.setAlpha(alphaOf(alpha * fade));
        canvas.drawRoundRect(edge, radius, radius, ringPaint);
    }

    private boolean buildRipple() {
        if (surface.width() <= 0 || surface.height() <= 0) return false;
        if (!pressPointKnown) {
            pressX = surface.centerX();
            pressY = surface.centerY();
        }
        pressX = Math.max(surface.left, Math.min(surface.right, pressX));
        pressY = Math.max(surface.top, Math.min(surface.bottom, pressY));
        // Far enough to leave the bubble entirely, measured from the press rather than the centre,
        // so the band still clears the corner furthest from the finger.
        float reach = (float) Math.hypot(
                Math.max(pressX - surface.left, surface.right - pressX),
                Math.max(pressY - surface.top, surface.bottom - pressY));
        rippleMaxScale = Math.max(1f, (reach + rippleUnit) / rippleUnit);
        try {
            rippleShader = new RadialGradient(pressX, pressY, rippleUnit,
                    new int[]{
                            UiKit.withAlpha(accent, 0),
                            UiKit.withAlpha(accent, RIPPLE_BAND_ALPHA),
                            UiKit.withAlpha(accent, 0)},
                    new float[]{0f, 0.68f, 1f}, Shader.TileMode.CLAMP);
        } catch (Exception ignored) {
            return false;
        }
        ripplePaint.setShader(rippleShader);
        return true;
    }

    private void finish() {
        if (finished) return;
        finished = true;
        unscheduleSelf(frame);
    }

    private static float clamp(float value) {
        return value < 0f ? 0f : value > 1f ? 1f : value;
    }

    private static int alphaOf(float value) {
        int rounded = Math.round(value);
        return rounded < 0 ? 0 : rounded > 255 ? 255 : rounded;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        // The press point is in the message's own coordinates, so a resized bubble needs a
        // rebuilt gradient rather than a stale one scaled to the wrong reach.
        rippleShader = null;
    }

    @Override public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        // A message scrolled off screen or detached keeps no frames, and a selection already let
        // go of stays finished, so returning to the screen cannot replay it.
        if (!visible) {
            unscheduleSelf(frame);
            if (released) finish();
        } else if (!finished) {
            invalidateSelf();
        }
        return changed;
    }

    @Override public void setAlpha(int alpha) {}

    @Override public void setColorFilter(ColorFilter colorFilter) {}

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** True once the selection has faded out completely and holds nothing on screen. */
    boolean isFinished() {
        return finished;
    }

    /** The accent this selection resolved when it started. */
    int accentColor() {
        return accent;
    }
}
