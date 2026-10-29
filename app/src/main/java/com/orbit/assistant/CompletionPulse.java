package com.orbit.assistant;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Orbit Dock's quiet "done": when an answer finishes in front of the user, its bubble takes a soft
 * glow in their accent and swells a few percent, then a second, gentler beat, and comes to rest.
 *
 * <p>Once, about a second, and never a loop. No sound, no vibration. It plays only through
 * {@link ValueAnimator}, so Android's animator duration scale stretches or shortens it, and with
 * animations removed it does not play at all.
 */
final class CompletionPulse {
    static final long DURATION_MS = 1000L;
    /** The first beat's swell on the collapsed Dock. The expanded highlight passes 0: glow only. */
    static final float SWELL = 0.04f;
    /** The second beat, relative to the first. */
    static final float SECOND_BEAT = 0.45f;
    /** Where the first beat ends and the second begins, as a fraction of the duration. */
    private static final float SPLIT = 0.55f;

    /** At most one pulse at a time; a new one settles the old one first. */
    private static ValueAnimator running;

    private CompletionPulse() {}

    /**
     * Whether a finished answer earns a pulse: the user wants it and this request has not already
     * had one. Android's animation setting is checked by {@link #play} itself. Callers reach this only from a live, visible success, which
     * is what keeps failures, stops and answers that landed while Orbit was closed out of it.
     */
    static boolean shouldPlay(boolean enabled, String requestId, String lastPlayedRequestId) {
        return enabled && requestId != null && !requestId.isEmpty()
                && !requestId.equals(lastPlayedRequestId);
    }

    /**
     * The pulse's strength, 0 to 1, at progress {@code t}: a full beat, then a softer one. Built
     * from squared sines, so it leaves and returns to rest with no visible snap.
     */
    static float envelope(float t) {
        if (t <= 0f || t >= 1f) return 0f;
        double s = t < SPLIT ? Math.sin(Math.PI * t / SPLIT)
                : Math.sin(Math.PI * (t - SPLIT) / (1f - SPLIT));
        float beat = (float) (s * s);
        return t < SPLIT ? beat : SECOND_BEAT * beat;
    }

    /** Plays on {@code target}, whose corners are {@code radiusPx}. Restores it exactly afterwards. */
    static void play(View target, int accent, float radiusPx, float swell) {
        if (target == null || !UiKit.animationsEnabled()) return;
        settle();
        final Drawable foreground = target.getForeground();
        final float elevation = target.getElevation();
        final int spot = target.getOutlineSpotShadowColor();
        final int ambient = target.getOutlineAmbientShadowColor();
        final float lift = UiKit.dp(target.getContext(), 10);

        GradientDrawable glow = new GradientDrawable();
        glow.setCornerRadius(radiusPx);
        glow.setColor(UiKit.withAlpha(accent, 30));
        glow.setStroke(Math.max(1, UiKit.dp(target.getContext(), 1.5f)), UiKit.withAlpha(accent, 210));
        glow.setAlpha(0);
        target.setForeground(glow);
        target.setOutlineSpotShadowColor(accent);
        target.setOutlineAmbientShadowColor(accent);

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            float e = envelope((float) a.getAnimatedValue());
            glow.setAlpha(Math.round(255 * e));
            target.setScaleX(1f + swell * e);
            target.setScaleY(1f + swell * e);
            target.setElevation(elevation + lift * e);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean restored;
            private void restore() {
                if (restored) return;
                restored = true;
                target.setScaleX(1f);
                target.setScaleY(1f);
                target.setElevation(elevation);
                target.setOutlineSpotShadowColor(spot);
                target.setOutlineAmbientShadowColor(ambient);
                if (target.getForeground() == glow) target.setForeground(foreground);
                if (running == animator) running = null;
            }
            @Override public void onAnimationEnd(Animator animation) { restore(); }
            @Override public void onAnimationCancel(Animator animation) { restore(); }
        });
        running = animator;
        animator.start();
    }

    /** Ends any pulse now, leaving its view exactly as it was. Safe when none is playing. */
    static void settle() {
        ValueAnimator current = running;
        running = null;
        if (current != null) current.cancel();
    }
}
