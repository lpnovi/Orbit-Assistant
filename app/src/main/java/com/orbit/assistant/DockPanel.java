package com.orbit.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Orbit Dock's conversation panel: reveals its one child at the child's own height, up to a limit,
 * and slides between that and nothing. The composer bar below never joins the height animation, so
 * expanding or collapsing never moves what the user is typing into.
 *
 * <p>Adapted from {@code AssistantDockPanel} in Tx13758627780's community fork of Orbit
 * (github.com/Tx13758627780/Orbit-Assistant, commit c7a69ef), used under the Mozilla Public
 * License 2.0 that both projects share.
 */
final class DockPanel extends FrameLayout {
    private boolean expanded;
    private int limit;
    private int revealed;
    private int target;
    private ValueAnimator animator;
    private boolean animateChanges = true;
    private boolean scheduled;
    private final Rect clip = new Rect();

    DockPanel(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        limit = UiKit.dp(context, 280);
    }

    boolean isExpanded() { return expanded; }

    void setExpanded(boolean value, boolean animate) {
        expanded = value;
        animateChanges = animate;
        // A collapsed panel keeps its views (and a running answer) but is not read out.
        setImportantForAccessibility(value ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        requestLayout();
    }

    int heightLimit() { return limit; }

    /** The tallest the panel may be. Follows the user's drag immediately, without animating. */
    void setHeightLimit(int pixels, boolean immediate) {
        pixels = Math.max(0, pixels);
        if (limit == pixels) return;
        limit = pixels;
        // A shrinking window or a rising keyboard takes effect at once, never pushing the bar
        // off screen while an animation catches up.
        if (revealed > limit) revealed = limit;
        // One-shot: the next measure lands exactly, then animation is the default again.
        if (immediate) animateChanges = false;
        requestLayout();
    }

    void cancelMotion() {
        if (animator != null) animator.cancel();
        animator = null;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int available = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? limit : Math.min(limit, MeasureSpec.getSize(heightSpec));
        int natural = 0;
        if (getChildCount() > 0) {
            View child = getChildAt(0);
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
            natural = child.getMeasuredHeight();
        }
        int desired = expanded ? Math.min(available, natural) : 0;
        if (!animateChanges || !UiKit.animationsEnabled() || getWidth() == 0) {
            cancelMotion();
            target = desired;
            revealed = desired;
            animateChanges = true;
        } else if (target != desired) {
            target = desired;
            if (!scheduled) {
                scheduled = true;
                post(() -> { scheduled = false; animateToTarget(); });
            }
        }
        int height = Math.min(available, Math.max(0, revealed));
        // An explicit clip even for an empty rectangle: some renderers do not clip overflowing
        // children when the parent's measured height is zero.
        clip.set(0, 0, width, height);
        setClipBounds(clip);
        if (getChildCount() > 0) getChildAt(0).setVisibility(height == 0 ? INVISIBLE : VISIBLE);
        setMeasuredDimension(width, height);
    }

    private void animateToTarget() {
        cancelMotion();
        if (revealed == target) return;
        animator = ValueAnimator.ofInt(revealed, target);
        animator.setDuration(UiKit.MOTION_ENTER);
        animator.setInterpolator(UiKit.motionEasing());
        animator.addUpdateListener(a -> {
            revealed = (int) a.getAnimatedValue();
            requestLayout();
        });
        animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        cancelMotion();
        super.onDetachedFromWindow();
    }
}
