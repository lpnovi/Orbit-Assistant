package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Orbit Launch Sequence: three bodies, a core, and nothing else going on.
 *
 * <p>A hidden, entirely decorative scene. It has no effect on anything Orbit does, reaches no
 * provider, touches no network, reads no conversation, and writes nothing to disk. Luna, Terra and
 * Sol appear here as orbital bodies because they are Orbit's own vocabulary, not because tapping
 * one selects a model — nothing in this file can change a setting.
 *
 * <p>Plain Canvas drawing on one {@link View}. No game engine, no bitmaps, no shaders, and no
 * allocation inside {@link #onDraw}: everything it needs is built once. The animation is driven by
 * {@link #postOnAnimationDelayed}, which stops completely the moment the view leaves the window or
 * the Activity pauses, so a scene the user walked away from costs exactly nothing.
 *
 * <h2>Motion, and doing without it</h2>
 *
 * <p>Android's animator duration scale is the platform's own "reduce motion" signal, and a device
 * set to zero means it. That device gets a still composition — the same bodies, on their same
 * orbits, drawn once — plus a short pulse when something is touched, so the scene is still
 * responsive and still readable without anything moving continuously.
 */
final class OrbitLaunchSequenceView extends View {

    /** How much of a full orbit each body sweeps per second, in radians. */
    private static final float[] SPEEDS = {0.85f, 0.42f, 0.19f};
    /** Orbit radii, as a fraction of the scene's half-size. */
    private static final float[] RADII = {0.30f, 0.52f, 0.78f};
    private static final float[] BODY_SIZES = {0.030f, 0.044f, 0.058f};
    private static final String[] NAMES = {"Luna", "Terra", "Sol"};

    /** How quickly a nudged orbit settles back onto its natural track. Per second. */
    private static final float SETTLE_PER_SECOND = 1.9f;
    /** The largest displacement a flick may add, as a fraction of the half-size. */
    private static final float MAX_NUDGE = 0.16f;
    /** How long a tap's pulse takes to fade, in milliseconds. */
    private static final long PULSE_MS = 620L;
    /** Frame pacing. Deliberately not as fast as the display can go; this is decoration. */
    private static final long FRAME_MS = 16L;

    /** One orbital body, and whatever the finger has done to it lately. */
    private static final class Body {
        float angle;
        float nudgeRadius;
        float nudgeAngle;
        float pulse;
        float x;
        float y;
        float radius;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Body[] bodies = new Body[3];
    private final Random random = new Random();

    private long lastFrameAt;
    private boolean running;
    private boolean reducedMotion;
    private int accent;

    /** Core pulse, 0 at rest. Separate from the bodies so tapping the middle reads differently. */
    private float corePulse;

    /**
     * How many times something has been touched in this sitting.
     *
     * <p>In memory only, and gone when the scene closes. The rare fourth object depends on it, and
     * nothing persistent is worth that.
     */
    private int interactions;
    private float visitorProgress;
    private boolean visitorActive;

    /** The list is built once so the touch handler allocates nothing per event. */
    private final List<Body> hitOrder = new ArrayList<>(3);

    private float downX;
    private float downY;
    private long downAt;
    private Body dragging;

    OrbitLaunchSequenceView(Context context) {
        super(context);
        setFocusable(true);
        setContentDescription("Orbit launch sequence. A decorative animation. "
                + "Press Back to leave.");
        for (int i = 0; i < bodies.length; i++) {
            Body body = new Body();
            // A fixed spread rather than a random one, so the scene opens looking composed.
            body.angle = (float) (Math.PI * 2 * i / 3f);
            bodies[i] = body;
            hitOrder.add(body);
        }
        refreshTheme();
    }

    /** Re-reads accent and motion settings. Called on create and on resume. */
    void refreshTheme() {
        accent = UiKit.accent(getContext());
        reducedMotion = prefersReducedMotion(getContext());
        invalidate();
    }

    /**
     * Whether this device has asked for less movement.
     *
     * <p>Android has no single "reduce motion" flag; the animator duration scale is what the
     * Developer options switch and the accessibility shortcuts actually set, and a zero there is an
     * unambiguous instruction. An unreadable value is treated as normal motion rather than as a
     * preference nobody expressed.
     */
    static boolean prefersReducedMotion(Context context) {
        try {
            float scale = Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale <= 0.01f;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- the frame loop -----------------------------------------------------------------------

    void start() {
        if (running) return;
        running = true;
        lastFrameAt = System.currentTimeMillis();
        if (!reducedMotion) postOnAnimationDelayed(frame, FRAME_MS);
        invalidate();
    }

    /** Stops the loop and drops every pending callback. Safe to call more than once. */
    void stop() {
        running = false;
        removeCallbacks(frame);
        removeCallbacks(settleFrame);
    }

    /** Whether the scene is currently animating. For tests, and for nothing else. */
    boolean isRunning() {
        return running;
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            float seconds = Math.min(0.05f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;
            advance(seconds);
            invalidate();
            postOnAnimationDelayed(this, FRAME_MS);
        }
    };

    @Override protected void onDetachedFromWindow() {
        // Whatever else happens, a view that is gone stops asking for frames.
        stop();
        super.onDetachedFromWindow();
    }

    /** One step of the simulation. Pure arithmetic on the fields above. */
    void advance(float seconds) {
        for (int i = 0; i < bodies.length; i++) {
            Body body = bodies[i];
            body.angle += SPEEDS[i] * seconds;
            if (body.angle > Math.PI * 2) body.angle -= (float) (Math.PI * 2);
            // A perturbed orbit is pulled back towards its natural track rather than snapped to it.
            float settle = Math.max(0f, 1f - SETTLE_PER_SECOND * seconds);
            body.nudgeRadius *= settle;
            body.nudgeAngle *= settle;
            body.pulse = Math.max(0f, body.pulse - seconds * (1000f / PULSE_MS));
        }
        corePulse = Math.max(0f, corePulse - seconds * (1000f / PULSE_MS));
        if (visitorActive) {
            visitorProgress += seconds * 0.22f;
            if (visitorProgress >= 1f) {
                visitorActive = false;
                visitorProgress = 0f;
            }
        }
    }

    // ---- drawing ------------------------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float half = Math.min(cx, cy);
        if (half <= 0f) return;

        canvas.drawColor(UiKit.BG);

        // The orbits themselves, as faint rings.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, half * 0.004f));
        for (int i = 0; i < bodies.length; i++) {
            paint.setColor(UiKit.withAlpha(accent, 34));
            canvas.drawCircle(cx, cy, half * RADII[i], paint);
        }
        paint.setStyle(Paint.Style.FILL);

        // The core: Orbit's own mark, at rest in the middle.
        float coreRadius = half * (0.10f + 0.02f * corePulse);
        paint.setColor(UiKit.withAlpha(accent, (int) (40 + 90 * corePulse)));
        canvas.drawCircle(cx, cy, coreRadius * 1.9f, paint);
        paint.setColor(accent);
        canvas.drawCircle(cx, cy, coreRadius, paint);
        paint.setColor(UiKit.orbitShellColor());
        canvas.drawCircle(cx, cy, coreRadius * 0.55f, paint);

        for (int i = 0; i < bodies.length; i++) {
            Body body = bodies[i];
            float orbit = half * RADII[i] + half * body.nudgeRadius;
            float angle = body.angle + body.nudgeAngle;
            body.x = cx + (float) Math.cos(angle) * orbit;
            body.y = cy + (float) Math.sin(angle) * orbit;
            body.radius = half * BODY_SIZES[i] * (1f + 0.35f * body.pulse);

            paint.setColor(UiKit.withAlpha(accent, (int) (30 + 110 * body.pulse)));
            canvas.drawCircle(body.x, body.y, body.radius * 2.4f, paint);
            paint.setColor(bodyColor(i));
            canvas.drawCircle(body.x, body.y, body.radius, paint);
        }

        if (visitorActive) drawVisitor(canvas, cx, cy, half);
    }

    /** Each body reads differently without needing a second accent. */
    private int bodyColor(int index) {
        switch (index) {
            case 0: return UiKit.blend(UiKit.TEXT, accent, 0.55f);
            case 1: return accent;
            default: return UiKit.blend(accent, Color.WHITE, 0.78f);
        }
    }

    /**
     * The rare fourth object.
     *
     * <p>Unnamed, unexplained, and gone in a few seconds. It appears only after a good deal of
     * poking about, and only sometimes, which is the entire point of it.
     */
    private void drawVisitor(Canvas canvas, float cx, float cy, float half) {
        float travel = visitorProgress;
        float x = cx + half * (1.25f - 2.5f * travel);
        float y = cy - half * (0.62f - 0.35f * travel);
        float fade = (float) Math.sin(Math.PI * travel);
        paint.setColor(UiKit.withAlpha(UiKit.TEXT, (int) (200 * fade)));
        canvas.drawCircle(x, y, half * 0.012f, paint);
        paint.setColor(UiKit.withAlpha(UiKit.TEXT, (int) (60 * fade)));
        canvas.drawCircle(x + half * 0.03f, y - half * 0.012f, half * 0.006f, paint);
    }

    // ---- interaction --------------------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downAt = System.currentTimeMillis();
                dragging = bodyAt(downX, downY);
                return true;
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_UP: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                float distance = (float) Math.hypot(dx, dy);
                long held = System.currentTimeMillis() - downAt;
                if (distance < UiKit.dp(getContext(), 12) && held < 500L) tap(downX, downY);
                else if (dragging != null) flick(dragging, dx, dy);
                dragging = null;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = null;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void tap(float x, float y) {
        Body body = bodyAt(x, y);
        if (body != null) {
            body.pulse = 1f;
            noteInteraction();
            UiKit.haptic(this, android.view.HapticFeedbackConstants.CLOCK_TICK);
            if (reducedMotion) drawOnceThenSettle();
            return;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float half = Math.min(cx, cy);
        if (Math.hypot(x - cx, y - cy) < half * 0.22f) {
            corePulse = 1f;
            noteInteraction();
            UiKit.haptic(this, android.view.HapticFeedbackConstants.CLOCK_TICK);
            if (reducedMotion) drawOnceThenSettle();
        }
    }

    /** A flick perturbs the orbit, bounded, and the settling in {@link #advance} undoes it. */
    private void flick(Body body, float dx, float dy) {
        float half = Math.max(1f, Math.min(getWidth(), getHeight()) / 2f);
        body.nudgeRadius = clamp((float) Math.hypot(dx, dy) / half * 0.35f, 0f, MAX_NUDGE)
                * (dy < 0 ? 1f : -1f);
        body.nudgeAngle = clamp(dx / half * 0.6f, -0.9f, 0.9f);
        body.pulse = 0.7f;
        noteInteraction();
        UiKit.haptic(this, android.view.HapticFeedbackConstants.CLOCK_TICK);
        if (reducedMotion) drawOnceThenSettle();
    }

    /**
     * On a reduced-motion device, a touch still does something.
     *
     * <p>One short run of the loop rather than a permanent one: the pulse fades, the nudge settles,
     * and the scene goes still again. A device asking for less motion gets a reaction, not a
     * refusal, and never a continuous animation.
     */
    private void drawOnceThenSettle() {
        if (!running) return;
        removeCallbacks(settleFrame);
        lastFrameAt = System.currentTimeMillis();
        postOnAnimationDelayed(settleFrame, FRAME_MS);
    }

    private final Runnable settleFrame = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            float seconds = Math.min(0.05f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;
            // The orbits do not turn on a reduced-motion device; only the reaction decays.
            for (Body body : bodies) {
                float settle = Math.max(0f, 1f - SETTLE_PER_SECOND * seconds);
                body.nudgeRadius *= settle;
                body.nudgeAngle *= settle;
                body.pulse = Math.max(0f, body.pulse - seconds * (1000f / PULSE_MS));
            }
            corePulse = Math.max(0f, corePulse - seconds * (1000f / PULSE_MS));
            invalidate();
            if (stillReacting()) postOnAnimationDelayed(this, FRAME_MS);
        }
    };

    private boolean stillReacting() {
        if (corePulse > 0.01f) return true;
        for (Body body : bodies) {
            if (body.pulse > 0.01f || Math.abs(body.nudgeRadius) > 0.001f
                    || Math.abs(body.nudgeAngle) > 0.001f) return true;
        }
        return false;
    }

    /** Counts a touch, and very occasionally lets something drift past. */
    private void noteInteraction() {
        interactions++;
        if (reducedMotion || visitorActive) return;
        if (interactions >= 12 && random.nextInt(14) == 0) {
            visitorActive = true;
            visitorProgress = 0f;
        }
    }

    private Body bodyAt(float x, float y) {
        float slop = UiKit.dp(getContext(), 22);
        for (Body body : hitOrder) {
            if (body.radius <= 0f) continue;
            if (Math.hypot(x - body.x, y - body.y) <= body.radius + slop) return body;
        }
        return null;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The body names, for the accessible description. Never drawn. */
    static String[] names() {
        return NAMES.clone();
    }
}
