package com.orbit.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * A chat card that moves with the finger, revealing what letting go would do.
 *
 * <p>The whole design brief for this release is in the first sentence. A gesture that is
 * recognised on release and then played back as an animation always feels like a request the app
 * granted a moment later; one that moves under the finger feels like the card itself. So there is
 * no detector here and no post-hoc animation: {@link #displacement} is the single piece of state,
 * touch writes it directly, and everything visible - the card's position, how far the action
 * surface has opened, how bright its glyph is - is a pure function of it. Animation exists only
 * for the part the finger is not doing any more, which is the settle after release.
 *
 * <p>The action surfaces are drawn, not laid out. Two more child views under every chat card would
 * be two more things for TalkBack to land on and two more things to keep in step with a list that
 * rebuilds itself constantly; a rounded rectangle and a glyph in {@link #dispatchDraw} are neither.
 * The card keeps its own background and stays fully readable while it is part way across, so the
 * user can still see which chat they are about to act on.
 *
 * <p>Arbitration with the surrounding scroll is deliberate rather than a threshold on {@code dx}.
 * A chat list is a vertical surface first: a drag that starts out mostly vertical is conceded to
 * the scroller for the rest of the gesture and can never become a swipe, and only once horizontal
 * movement clearly dominates does this take the gesture and tell its parents to stop intercepting.
 * Diagonal input therefore resolves the way the user's hand actually meant it.
 */
public class OrbitSwipeRow extends FrameLayout {

    /** Nothing is armed for this direction. */
    public static final int ACTION_NONE = 0;
    /** Left swipe: the card moves left and the delete surface opens on the right. */
    public static final int ACTION_DELETE = 1;
    /** Right swipe: the card moves right and the pin surface opens on the left. */
    public static final int ACTION_PIN = 2;

    /** What the card must cross, as a share of its own width, for release to commit. */
    private static final float COMMIT_FRACTION = 0.30f;
    /** How far it can go at all. Past the commit point the drag gets heavier rather than freer. */
    private static final float MAX_FRACTION = 0.62f;
    /** Share of further finger travel the card follows once past the commit point. */
    private static final float RESISTANCE = 0.42f;
    /** Horizontal movement must beat vertical by this much before the gesture is taken. */
    private static final float HORIZONTAL_BIAS = 1.25f;
    private static final long SETTLE_MS = 220L;
    private static final long COMMIT_MS = 190L;

    /**
     * The one card in the whole list allowed to be displaced.
     *
     * <p>Static because the constraint is about the list, not about any card in it. Touching a
     * second card settles the first, so the user can never be left with two half-open rows and no
     * memory of which one they were acting on.
     */
    private static OrbitSwipeRow active;

    /** What a completed swipe means. Called once, after the card has finished leaving. */
    public interface Listener {
        void onSwipeCommitted(OrbitSwipeRow row, int action);
    }

    private final View card;
    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF surfaceBounds = new RectF();
    private final int touchSlop;
    private final int minFlingVelocity;
    private final int cornerRadius;

    private Drawable deleteGlyph;
    private Drawable pinGlyph;

    private int leftAction = ACTION_NONE;
    private int rightAction = ACTION_NONE;
    private boolean pinned;
    private Listener listener;

    private float displacement;
    private float downX;
    private float downY;
    private float dragStartDisplacement;
    private boolean dragging;
    private boolean concededToScroll;
    private boolean pastCommit;
    private boolean committing;
    private VelocityTracker velocity;
    private ValueAnimator settle;

    public OrbitSwipeRow(Context context, View card) {
        super(context);
        this.card = card;
        ViewConfiguration config = ViewConfiguration.get(context);
        this.touchSlop = config.getScaledTouchSlop();
        this.minFlingVelocity = config.getScaledMinimumFlingVelocity();
        this.cornerRadius = UiKit.dp(context, 18);
        setClipChildren(false);
        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        applyAccessibilityActions();
    }

    public View card() { return card; }

    /**
     * Arms this row.
     *
     * @param leftAction  what a leftward swipe does, or {@link #ACTION_NONE}
     * @param rightAction what a rightward swipe does, or {@link #ACTION_NONE}
     * @param pinned      whether this chat is currently pinned, which decides whether the pin
     *                    surface means Pin or Unpin
     */
    public void configure(int leftAction, int rightAction, boolean pinned, Listener listener) {
        this.leftAction = leftAction;
        this.rightAction = rightAction;
        this.pinned = pinned;
        this.listener = listener;
        setWillNotDraw(leftAction == ACTION_NONE && rightAction == ACTION_NONE);
        applyAccessibilityActions();
    }

    /** True when this row responds to horizontal drags at all. */
    public boolean swipeEnabled() {
        return leftAction != ACTION_NONE || rightAction != ACTION_NONE;
    }

    // ---- touch ---------------------------------------------------------------------------------

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!swipeEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTracking(event);
                // Never taken on the way down: a tap has to reach the card, and a scroll has to
                // reach the scroller. Only sustained horizontal movement earns this gesture.
                return false;
            case MotionEvent.ACTION_MOVE:
                evaluateDirection(event);
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseTracking();
                return false;
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!swipeEnabled()) return super.onTouchEvent(event);
        if (velocity != null) velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTracking(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                evaluateDirection(event);
                if (dragging) applyDrag(event.getX() - downX);
                return true;
            case MotionEvent.ACTION_UP:
                finishGesture(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishGesture(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void beginTracking(MotionEvent event) {
        downX = event.getX();
        downY = event.getY();
        dragStartDisplacement = displacement;
        dragging = false;
        concededToScroll = false;
        if (velocity != null) velocity.recycle();
        velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
    }

    /**
     * Decides, once per gesture, whether this is a swipe or a scroll.
     *
     * <p>Vertical intent is checked first and is final: a drag that began as a scroll stays a
     * scroll even if the finger later curves sideways, because the user is reading the list and
     * would not thank Orbit for deleting something they dragged past. Horizontal intent has to beat
     * vertical movement outright, not merely exceed the slop, so a diagonal flick resolves to
     * whichever the hand actually meant.
     */
    private void evaluateDirection(MotionEvent event) {
        if (dragging || concededToScroll) return;
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        float absX = Math.abs(dx);
        float absY = Math.abs(dy);
        if (absY > touchSlop && absY >= absX) {
            concededToScroll = true;
            return;
        }
        if (absX > touchSlop && absX > absY * HORIZONTAL_BIAS && actionFor(dx) != ACTION_NONE) {
            startDragging();
        }
    }

    private void startDragging() {
        dragging = true;
        pastCommit = false;
        cancelSettle();
        if (active != null && active != this) active.settleBack(true);
        active = this;
        // The scroller must stop competing for the rest of this gesture, or a slight upward drift
        // half way through a swipe would hand the card back mid-movement.
        android.view.ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
    }

    private void applyDrag(float rawDx) {
        float target = dragStartDisplacement + rawDx;
        // Beyond the point where letting go would act, the card stops keeping up with the finger.
        // The drag gets heavier exactly where the meaning stops changing, which is what makes the
        // commit point findable without a label saying where it is.
        float limit = commitDistance();
        float magnitude = Math.abs(target);
        if (magnitude > limit) {
            magnitude = limit + (magnitude - limit) * RESISTANCE;
            float max = getWidth() * MAX_FRACTION;
            if (magnitude > max) magnitude = max;
            target = Math.signum(target) * magnitude;
        }
        if (actionFor(target) == ACTION_NONE) target = 0f;
        setDisplacement(target);

        boolean nowPastCommit = Math.abs(displacement) >= limit && actionFor(displacement) != ACTION_NONE;
        if (nowPastCommit != pastCommit) {
            pastCommit = nowPastCommit;
            // One tick, on the way in. Crossing back out is silent, and holding the card above the
            // line makes no further sound, so the gesture never buzzes while the finger moves.
            if (nowPastCommit) UiKit.haptic(this, HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void finishGesture(boolean released) {
        boolean wasDragging = dragging;
        dragging = false;
        float vx = 0f;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            vx = velocity.getXVelocity();
        }
        releaseTracking();
        if (!wasDragging) return;
        if (!released) {
            settleBack(true);
            return;
        }
        int action = actionFor(displacement);
        if (action == ACTION_NONE) {
            settleBack(true);
            return;
        }
        boolean farEnough = Math.abs(displacement) >= commitDistance();
        // A short but deliberate flick counts. Direction has to agree with where the card already
        // is, so a fast correction back towards centre cannot commit the action being abandoned.
        boolean fastEnough = Math.abs(vx) >= minFlingVelocity
                && Math.signum(vx) == Math.signum(displacement)
                && Math.abs(displacement) > touchSlop * 3;
        if (farEnough || fastEnough) commit(action);
        else settleBack(true);
    }

    private void releaseTracking() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    // ---- state ---------------------------------------------------------------------------------

    private float commitDistance() {
        int width = getWidth();
        return (width > 0 ? width : getResources().getDisplayMetrics().widthPixels) * COMMIT_FRACTION;
    }

    private int actionFor(float value) {
        if (value < 0f) return leftAction;
        if (value > 0f) return rightAction;
        return ACTION_NONE;
    }

    private void setDisplacement(float value) {
        displacement = value;
        card.setTranslationX(value);
        invalidate();
    }

    /** How far open the action surface is, from 0 to 1. Everything drawn reads from this. */
    private float progress() {
        float limit = commitDistance();
        if (limit <= 0f) return 0f;
        float p = Math.abs(displacement) / limit;
        return p > 1f ? 1f : p;
    }

    /** Returns the card to rest. Used on release below threshold, and when another row takes over. */
    public void settleBack(boolean animate) {
        cancelSettle();
        pastCommit = false;
        if (active == this) active = null;
        if (displacement == 0f) return;
        if (!animate || !UiKit.animationsEnabled()) {
            setDisplacement(0f);
            return;
        }
        settle = ValueAnimator.ofFloat(displacement, 0f);
        settle.setDuration(SETTLE_MS);
        settle.setInterpolator(new DecelerateInterpolator(1.6f));
        settle.addUpdateListener(a -> setDisplacement((Float) a.getAnimatedValue()));
        settle.start();
    }

    private void commit(int action) {
        if (committing) return;
        committing = true;
        if (active == this) active = null;
        cancelSettle();
        float target = Math.signum(displacement) * getWidth();
        Runnable fire = () -> {
            committing = false;
            if (listener != null) listener.onSwipeCommitted(this, action);
        };
        if (!UiKit.animationsEnabled()) {
            setDisplacement(0f);
            fire.run();
            return;
        }
        // Pin resolves in place rather than throwing the card off screen: the chat is not going
        // anywhere, it is only changing which section it belongs to, and a card that flew away and
        // then reappeared above would read as two different chats.
        if (action == ACTION_PIN) {
            settle = ValueAnimator.ofFloat(displacement, 0f);
            settle.setDuration(COMMIT_MS);
            settle.setInterpolator(new DecelerateInterpolator(1.6f));
            settle.addUpdateListener(a -> setDisplacement((Float) a.getAnimatedValue()));
            settle.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) { fire.run(); }
            });
            settle.start();
            return;
        }
        settle = ValueAnimator.ofFloat(displacement, target);
        settle.setDuration(COMMIT_MS);
        settle.setInterpolator(new DecelerateInterpolator(1.2f));
        settle.addUpdateListener(a -> {
            setDisplacement((Float) a.getAnimatedValue());
            card.setAlpha(1f - progressToward(target));
        });
        settle.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                card.setAlpha(1f);
                fire.run();
            }
        });
        settle.start();
    }

    private float progressToward(float target) {
        if (target == 0f) return 0f;
        float p = Math.abs(displacement) / Math.abs(target);
        return p > 1f ? 1f : p;
    }

    private void cancelSettle() {
        if (settle != null) {
            settle.cancel();
            settle = null;
        }
    }

    /**
     * A list that rebuilt itself, an activity that paused, a theme that changed.
     *
     * <p>None of them are events this row hears about, and all of them detach it, so the reset
     * lives here. Nothing about a drag is remembered anywhere: gesture progress is state that
     * belongs to a finger that is no longer on the screen.
     */
    @Override protected void onDetachedFromWindow() {
        cancelSettle();
        releaseTracking();
        if (active == this) active = null;
        displacement = 0f;
        card.setTranslationX(0f);
        card.setAlpha(1f);
        dragging = false;
        committing = false;
        super.onDetachedFromWindow();
    }

    /** Settles every open row. Called when the surface hosting them goes away or is rebuilt. */
    public static void resetActive() {
        if (active != null) active.settleBack(false);
        active = null;
    }

    // ---- drawing -------------------------------------------------------------------------------

    @Override protected void dispatchDraw(Canvas canvas) {
        if (displacement != 0f) drawActionSurface(canvas);
        super.dispatchDraw(canvas);
    }

    /**
     * The surface the card is being pulled off, opening from the edge the card came from.
     *
     * <p>Scaled and faded by gesture progress rather than switched on at a threshold, so the
     * user's own movement is what makes the action legible. No text label: the card underneath is
     * the thing that has to stay readable, and a word crammed into the few millimetres of an early
     * drag is noise rather than clarity.
     */
    private void drawActionSurface(Canvas canvas) {
        int action = actionFor(displacement);
        if (action == ACTION_NONE) return;
        float progress = progress();
        boolean fromRight = displacement < 0f;
        float open = Math.abs(displacement);

        int tint = action == ACTION_DELETE ? UiKit.DANGER : UiKit.accent(getContext());
        // Restrained at rest, more committed as the gesture becomes one. Never a solid slab.
        int alpha = Math.round(34 + 108 * progress);
        surfacePaint.setColor(UiKit.withAlpha(tint, alpha));
        surfacePaint.setStyle(Paint.Style.FILL);

        int top = card.getTop();
        int bottom = card.getBottom();
        if (bottom <= top) { top = 0; bottom = getHeight(); }
        if (fromRight) surfaceBounds.set(getWidth() - open, top, getWidth(), bottom);
        else surfaceBounds.set(0, top, open, bottom);
        canvas.drawRoundRect(surfaceBounds, cornerRadius, cornerRadius, surfacePaint);

        Drawable glyph = glyphFor(action);
        if (glyph == null) return;
        int size = UiKit.dp(getContext(), 22);
        float scale = 0.62f + 0.38f * progress;
        int drawn = Math.max(1, Math.round(size * scale));
        // Held a fixed inset from the edge the surface opened from, so it appears to be revealed
        // rather than dragged along, and it never slides under the card.
        int inset = UiKit.dp(getContext(), 26);
        float cx = fromRight ? getWidth() - inset : inset;
        float cy = (top + bottom) / 2f;
        if (open < inset + drawn / 2f) return;
        glyph.setColorFilter(new PorterDuffColorFilter(
                UiKit.withAlpha(onSurfaceInk(tint), Math.round(140 + 115 * progress)),
                PorterDuff.Mode.SRC_IN));
        glyph.setBounds(Math.round(cx - drawn / 2f), Math.round(cy - drawn / 2f),
                Math.round(cx + drawn / 2f), Math.round(cy + drawn / 2f));
        glyph.draw(canvas);
    }

    /** Keeps the glyph legible on a tint that may itself be light or dark. */
    private int onSurfaceInk(int tint) {
        return UiKit.contrastRatio(tint, UiKit.BG) < 2.0d ? UiKit.TEXT : tint;
    }

    private Drawable glyphFor(int action) {
        if (action == ACTION_DELETE) {
            if (deleteGlyph == null) deleteGlyph = getResources().getDrawable(R.drawable.ic_delete, null);
            return deleteGlyph == null ? null : deleteGlyph.mutate();
        }
        if (pinGlyph == null) pinGlyph = getResources().getDrawable(R.drawable.ic_pin, null);
        return pinGlyph == null ? null : pinGlyph.mutate();
    }

    // ---- accessibility -------------------------------------------------------------------------

    /**
     * The same two actions, without the gesture.
     *
     * <p>Put on the card rather than on this container because the card is what carries the chat's
     * name and what TalkBack lands on. The drawn surfaces are not views and so were never focus
     * targets to begin with, which is the other half of this: there is nothing hidden underneath a
     * resting card for a screen reader to find and be confused by.
     */
    private void applyAccessibilityActions() {
        if (card == null) return;
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (rightAction == ACTION_PIN) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.orbit_action_pin, pinned ? "Unpin chat" : "Pin chat"));
                }
                if (leftAction == ACTION_DELETE) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.orbit_action_delete, "Delete chat"));
                }
            }

            @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                if (action == R.id.orbit_action_pin && rightAction == ACTION_PIN && listener != null) {
                    listener.onSwipeCommitted(OrbitSwipeRow.this, ACTION_PIN);
                    return true;
                }
                if (action == R.id.orbit_action_delete && leftAction == ACTION_DELETE && listener != null) {
                    listener.onSwipeCommitted(OrbitSwipeRow.this, ACTION_DELETE);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }
}
