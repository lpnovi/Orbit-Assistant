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

    /** How quickly a nudged orbit settles back onto its natural track. Per second. */
    private static final float SETTLE_PER_SECOND = 1.9f;
    /** The largest displacement a drag may hold, as a fraction of the half-size. */
    private static final float MAX_NUDGE = 0.34f;
    /** The largest extra spin a release may impart, in radians per second. */
    private static final float MAX_SPIN = 3.2f;
    /** The largest radial momentum a release may impart, in half-units per second. */
    private static final float MAX_RADIAL_VELOCITY = 0.9f;
    /** How long a tap's pulse takes to fade, in milliseconds. */
    private static final long PULSE_MS = 620L;
    /** Frame pacing. Deliberately not as fast as the display can go; this is decoration. */
    private static final long FRAME_MS = 16L;
    /** How far outside its drawn edge a body can still be grabbed. */
    private static final int GRAB_SLOP_DP = 28;

    /** One orbital body, and whatever the finger has done to it lately. */
    private static final class Body {
        float angle;
        float nudgeRadius;
        float nudgeAngle;
        float pulse;
        float x;
        float y;
        float radius;
        /**
         * The finger has this body.
         *
         * <p>While true, the body's own orbital motion does not run at all. The two must not both
         * be writing its position — a body that keeps orbiting under a finger that is holding it
         * still fights the user, and the user wins.
         */
        boolean held;
        /** Extra angular velocity carried out of a release, decaying back to the natural speed. */
        float spin;
        /** Radial momentum carried out of a release, decaying as the orbit settles. */
        float radialVelocity;
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
    /** The pointer that grabbed a body. One body at a time; other fingers are ignored. */
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private android.view.VelocityTracker velocity;

    OrbitLaunchSequenceView(Context context) {
        super(context);
        setFocusable(true);
        // Deliberately unnamed. The three orbiters used to be called Luna, Terra and Sol, which are
        // Orbit's Fast/Balanced/Deep model codenames, and on a real device that read as though
        // touching one changed the AI mode. Nothing here selects anything, so nothing here is named.
        setContentDescription("Orbit launch sequence. A decorative animation you can nudge. "
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
        // A finger that was holding a body when the scene went away is not still holding it.
        if (dragging != null) dragging.held = false;
        dragging = null;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        recycleVelocity();
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
            float settle = Math.max(0f, 1f - SETTLE_PER_SECOND * seconds);
            if (body.held) {
                // The finger owns this one completely. Only the pulse decays; its angle and its
                // distance from the core are being written by the touch handler.
                body.pulse = Math.max(0f, body.pulse - seconds * (1000f / PULSE_MS));
                continue;
            }
            // Its own speed plus whatever momentum the release gave it, which fades.
            body.angle += (SPEEDS[i] + body.spin) * seconds;
            if (body.angle > Math.PI * 2) body.angle -= (float) (Math.PI * 2);
            if (body.angle < 0) body.angle += (float) (Math.PI * 2);
            // A perturbed orbit is pulled back towards its natural track rather than snapped to it,
            // and any radial momentum is carried for a moment first so the return is not abrupt.
            body.nudgeRadius = clamp(body.nudgeRadius + body.radialVelocity * seconds,
                    -MAX_NUDGE, MAX_NUDGE);
            body.spin *= settle;
            body.radialVelocity *= settle;
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

        positionBodies();
        for (int i = 0; i < bodies.length; i++) {
            Body body = bodies[i];
            paint.setColor(UiKit.withAlpha(accent, (int) (30 + 110 * body.pulse)));
            canvas.drawCircle(body.x, body.y, body.radius * 2.4f, paint);
            paint.setColor(bodyColor(i));
            canvas.drawCircle(body.x, body.y, body.radius, paint);
        }

        if (visitorActive) drawVisitor(canvas, cx, cy, half);
    }

    /**
     * Where each body currently is, in view coordinates.
     *
     * <p>Called at the top of every draw and by the hit test, so what is drawn and what can be
     * grabbed are the same numbers rather than two calculations that could drift apart. Before
     * Beta 2 the positions were written only inside {@code onDraw}, which meant nothing outside a
     * draw pass could reason about where a body was.
     */
    private void positionBodies() {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float half = Math.min(cx, cy);
        if (half <= 0f) return;
        for (int i = 0; i < bodies.length; i++) {
            Body body = bodies[i];
            float orbit = half * RADII[i] + half * body.nudgeRadius;
            float angle = body.angle + body.nudgeAngle;
            body.x = cx + (float) Math.cos(angle) * orbit;
            body.y = cy + (float) Math.sin(angle) * orbit;
            body.radius = half * BODY_SIZES[i] * (1f + 0.35f * body.pulse);
        }
    }

    /** Where a body is right now, for tests. Index 0 is the innermost. */
    float bodyX(int index) {
        positionBodies();
        return bodies[index].x;
    }

    float bodyY(int index) {
        positionBodies();
        return bodies[index].y;
    }

    /** Whether a body is currently being held by a finger. For tests. */
    boolean isDragging() {
        return dragging != null;
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

    /**
     * The touch handling, rewritten in Beta 2 because it did not do what the scene claimed.
     *
     * <p>The original consumed {@code ACTION_MOVE} and did nothing with it, then computed a single
     * displacement at {@code ACTION_UP} and applied a one-off nudge. So a body never moved while the
     * finger was down; it jumped after release. On the device that reads as an animation reacting to
     * you rather than an object you are holding, and it is what "the bodies do not follow the
     * finger" describes exactly.
     *
     * <p>Now a grabbed body is written directly from the finger on every move event, in polar
     * coordinates around the core — which keeps it inside the orbital idiom, keeps the radius
     * bounded, and makes a pathological coordinate impossible. Release converts the real gesture
     * velocity into a little momentum and hands the body back to {@link #advance}.
     */
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX();
                downY = event.getY();
                downAt = System.currentTimeMillis();
                activePointerId = event.getPointerId(0);
                trackVelocity(event);
                grab(bodyAt(downX, downY));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging == null) return true;
                int index = event.findPointerIndex(activePointerId);
                if (index < 0) return true;
                trackVelocity(event);
                // The whole fix: the body is moved here, on the move event, every time.
                dragTo(dragging, event.getX(index), event.getY(index));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // Only the finger that grabbed the body ends the drag; a second finger lifting is
                // not this gesture ending.
                if (event.getPointerId(event.getActionIndex()) != activePointerId) return true;
                finishGesture(event, true);
                return true;
            }
            case MotionEvent.ACTION_UP:
                finishGesture(event, true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                // The window took the gesture away. Let go immediately and keep no momentum.
                finishGesture(event, false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** Takes hold of a body, if the finger came down near one. */
    private void grab(Body body) {
        if (body == null) return;
        dragging = body;
        body.held = true;
        body.spin = 0f;
        body.radialVelocity = 0f;
        body.pulse = Math.max(body.pulse, 0.5f);
        // One short tick on the grab, so picking something up is felt. Release is deliberately
        // silent: two haptics for one gesture is fussy.
        UiKit.haptic(this, android.view.HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
    }

    /**
     * Puts a held body under the finger.
     *
     * <p>Written in the scene's own terms rather than as free translation: the finger's angle
     * around the core becomes the body's angle, and its distance becomes an offset from the body's
     * natural ring, clamped. A body can therefore be swung around and pulled in or out, and cannot
     * be dragged to an absurd coordinate however far the finger travels.
     */
    private void dragTo(Body body, float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float half = Math.min(cx, cy);
        if (half <= 0f) return;
        int index = indexOf(body);
        if (index < 0) return;

        float dx = x - cx;
        float dy = y - cy;
        float distance = (float) Math.hypot(dx, dy);
        // Dead centre has no meaningful angle, so hold the last one rather than snapping.
        if (distance > 0.001f) body.angle = (float) Math.atan2(dy, dx);
        body.nudgeAngle = 0f;
        body.nudgeRadius = clamp(distance / half - RADII[index], -MAX_NUDGE, MAX_NUDGE);
    }

    /**
     * Lets go, and decides whether the gesture was a tap or a drag.
     *
     * <p>A short, still touch is still a tap, so tapping a body or the core is unchanged. A real
     * drag hands back whatever velocity the finger actually had, bounded, and the body carries it
     * for a moment before settling.
     */
    private void finishGesture(MotionEvent event, boolean allowMomentum) {
        Body held = dragging;
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        float distance = (float) Math.hypot(dx, dy);
        long elapsed = System.currentTimeMillis() - downAt;
        boolean tapped = distance < UiKit.dp(getContext(), 12) && elapsed < 500L;

        if (held != null) {
            held.held = false;
            if (allowMomentum && !tapped) release(held);
            noteInteraction();
        }
        if (tapped) tap(downX, downY);

        dragging = null;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        recycleVelocity();
        if (reducedMotion) drawOnceThenSettle();
        invalidate();
    }

    /** Turns the finger's real velocity into a bounded amount of orbital momentum. */
    private void release(Body body) {
        int index = indexOf(body);
        if (index < 0) return;
        float half = Math.max(1f, Math.min(getWidth(), getHeight()) / 2f);
        float vx = 0f;
        float vy = 0f;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            vx = velocity.getXVelocity();
            vy = velocity.getYVelocity();
        }
        if (reducedMotion) {
            // A device asking for less motion gets the settle, not a fling.
            body.spin = 0f;
            body.radialVelocity = 0f;
            return;
        }
        // The component of the throw along the orbit becomes spin; the component away from the core
        // becomes radial momentum. Both are clamped, so a violent flick is enthusiastic rather than
        // ballistic.
        float tangentX = -(float) Math.sin(body.angle);
        float tangentY = (float) Math.cos(body.angle);
        float radialX = (float) Math.cos(body.angle);
        float radialY = (float) Math.sin(body.angle);
        float orbitRadius = Math.max(half * 0.05f, half * (RADII[index] + body.nudgeRadius));

        body.spin = clamp((vx * tangentX + vy * tangentY) / orbitRadius, -MAX_SPIN, MAX_SPIN);
        body.radialVelocity = clamp((vx * radialX + vy * radialY) / half,
                -MAX_RADIAL_VELOCITY, MAX_RADIAL_VELOCITY);
    }

    private int indexOf(Body body) {
        for (int i = 0; i < bodies.length; i++) if (bodies[i] == body) return i;
        return -1;
    }

    private void trackVelocity(MotionEvent event) {
        if (velocity == null) velocity = android.view.VelocityTracker.obtain();
        velocity.addMovement(event);
    }

    private void recycleVelocity() {
        if (velocity == null) return;
        velocity.recycle();
        velocity = null;
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

    /**
     * The body nearest the finger, if any is near enough.
     *
     * <p>The touch target is deliberately much larger than the drawn body and independent of it:
     * the outermost orbiter is a few millimetres across and is moving, so requiring the finger to
     * land on the pixels would make it feel broken rather than delicate. Nearest rather than first,
     * so two bodies that happen to overlap resolve to the one actually being reached for.
     */
    private Body bodyAt(float x, float y) {
        positionBodies();
        float slop = UiKit.dp(getContext(), GRAB_SLOP_DP);
        Body best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Body body : hitOrder) {
            if (body.radius <= 0f) continue;
            double distance = Math.hypot(x - body.x, y - body.y);
            if (distance <= body.radius + slop && distance < bestDistance) {
                best = body;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** How many orbiters the scene draws. They are deliberately unnamed. */
    static int bodyCount() {
        return SPEEDS.length;
    }
}
