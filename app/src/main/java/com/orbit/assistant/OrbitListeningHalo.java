package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Orbit's listening indicator: the microphone's normal rounded surface with a soft accent ring
 * that breathes with what the microphone is actually hearing.
 *
 * <p>Driven by real {@code onRmsChanged} levels from the speech recognizer rather than a timed
 * loop, so the movement corresponds to the user's own voice. {@link #setLevel} only stores a
 * target; the drawable eases towards it on its own frames with a fast attack and a slower decay,
 * so a stream of recogniser callbacks never starts an animator or allocates. Frames are scheduled
 * only while listening, and {@link #stop()} ends them, so no pulsing can outlive recognition.
 */
public class OrbitListeningHalo extends Drawable {
    /** Quietest and loudest RMS values the recognizer realistically reports, in dB. */
    private static final float RMS_FLOOR_DB = -2f;
    private static final float RMS_CEILING_DB = 10f;
    private static final float ATTACK = 0.34f;
    private static final float DECAY = 0.11f;
    private static final long FRAME_MS = 16L;
    /** Level held when the system has animations turned off, so listening still reads clearly. */
    private static final float STATIC_LEVEL = 0.45f;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float cornerRadius;
    private final Runnable frame = this::invalidateSelf;

    private int accent;
    private boolean listening;
    private float level;
    private float target;

    public OrbitListeningHalo(Context context) {
        cornerRadius = UiKit.dp(context, 18);
        ringPaint.setStyle(Paint.Style.STROKE);
        applyAccent(context);
    }

    /** Re-reads the current accent so the halo follows Orbit's appearance system. */
    public void applyAccent(Context context) {
        accent = UiKit.accent(context);
        basePaint.setColor(UiKit.SURFACE_2);
        ringPaint.setColor(accent);
        glowPaint.setColor(accent);
        invalidateSelf();
    }

    public void start() {
        if (listening) return;
        listening = true;
        level = 0f;
        target = 0f;
        invalidateSelf();
    }

    /** Ends the animation and clears its state so a later session starts from rest. */
    public void stop() {
        if (!listening) return;
        listening = false;
        level = 0f;
        target = 0f;
        unscheduleSelf(frame);
        invalidateSelf();
    }

    public boolean isListening() {
        return listening;
    }

    /**
     * Feeds one recognizer level in dB. Cheap enough to call on every callback: it only records a
     * target, which the next frame eases towards.
     */
    public void setLevel(float rmsdB) {
        if (!listening) return;
        float normalized = (rmsdB - RMS_FLOOR_DB) / (RMS_CEILING_DB - RMS_FLOOR_DB);
        target = Math.max(0f, Math.min(1f, normalized));
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        bounds.set(b);

        // The microphone's ordinary resting surface, so switching into listening never changes
        // the button's shape or footprint.
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, basePaint);
        if (!listening) return;

        boolean animate = UiKit.animationsEnabled();
        if (animate) {
            // Rises quickly with the voice and falls away gently, so speech reads as energy and
            // pauses settle instead of flickering.
            float rate = target > level ? ATTACK : DECAY;
            level += (target - level) * rate;
        } else {
            level = STATIC_LEVEL;
        }

        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float unit = Math.min(bounds.width(), bounds.height()) / 2f;
        float radius = unit * (0.52f + 0.30f * level);

        glowPaint.setAlpha(Math.round(26 + 46 * level));
        canvas.drawCircle(cx, cy, radius * 1.22f, glowPaint);

        ringPaint.setStrokeWidth(Math.max(1f, unit * (0.055f + 0.045f * level)));
        ringPaint.setAlpha(Math.round(110 + 125 * level));
        canvas.drawCircle(cx, cy, radius, ringPaint);

        if (animate) scheduleSelf(frame, SystemClock.uptimeMillis() + FRAME_MS);
    }

    @Override public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        // Nothing keeps drawing while the mic is off screen.
        if (!visible) unscheduleSelf(frame);
        else if (listening) invalidateSelf();
        return changed;
    }

    @Override public void setAlpha(int alpha) {}

    @Override public void setColorFilter(ColorFilter colorFilter) {}

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
