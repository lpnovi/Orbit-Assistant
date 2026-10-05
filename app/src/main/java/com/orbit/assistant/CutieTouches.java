package com.orbit.assistant;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The hearts and sparkles of the secret overlay style ({@link OverlayStyle#cute}). Decoration only:
 * nothing here is focusable, clickable or announced, and every colour is a pastel of the user's own
 * accent rather than a fixed pink. With system animations off it draws the still details and plays
 * nothing.
 */
final class CutieTouches {
    private CutieTouches() {}

    /** The user's accent, softened toward white. */
    static int pastel(Context c) {
        return UiKit.blend(UiKit.accent(c), Color.WHITE, 0.62f);
    }

    /** The Orbit mark wearing a tiny heart, with a sparkle that twinkles once it has landed. */
    static View dressMark(Context c, View mark, int markPx) {
        FrameLayout frame = new FrameLayout(c);
        frame.setClipChildren(false);
        frame.addView(mark, new FrameLayout.LayoutParams(markPx, markPx, Gravity.CENTER));

        TextView heart = glyph(c, "♥", 10f);
        frame.addView(heart, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
        TextView sparkle = glyph(c, "✦", 8f);
        frame.addView(sparkle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));

        if (UiKit.animationsEnabled()) {
            // A pop for the heart and a few twinkles for the sparkle, timed to land just after the
            // sheet does, then both rest. Nothing repeats forever over someone else's app.
            heart.setScaleX(0f);
            heart.setScaleY(0f);
            heart.animate().scaleX(1f).scaleY(1f).setStartDelay(260).setDuration(320)
                    .setInterpolator(new OvershootInterpolator(3f)).start();
            android.animation.ObjectAnimator twinkle = android.animation.ObjectAnimator
                    .ofFloat(sparkle, View.ALPHA, 1f, 0.25f, 1f);
            twinkle.setStartDelay(420);
            twinkle.setDuration(900);
            twinkle.setRepeatCount(2);
            twinkle.start();
        }
        return frame;
    }

    /**
     * A glyph that floats up from {@code anchor} and fades: a sparkle on send, a heart when
     * listening starts. Drawn in {@code root}, which must be a FrameLayout containing the anchor.
     */
    static void puff(ViewGroup root, View anchor, String text) {
        if (root == null || anchor == null || !UiKit.animationsEnabled()
                || !anchor.isAttachedToWindow() || anchor.getWidth() == 0) return;
        Context c = root.getContext();
        TextView puff = glyph(c, text, 15f);
        int[] at = new int[2];
        int[] origin = new int[2];
        anchor.getLocationInWindow(at);
        root.getLocationInWindow(origin);
        puff.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        root.addView(puff, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        puff.setTranslationX(at[0] - origin[0] + (anchor.getWidth() - puff.getMeasuredWidth()) / 2f);
        puff.setTranslationY(at[1] - origin[1] - puff.getMeasuredHeight() / 2f);
        puff.setScaleX(0.6f);
        puff.setScaleY(0.6f);
        puff.animate()
                .translationYBy(-UiKit.dp(c, 34))
                .scaleX(1.15f).scaleY(1.15f)
                .alpha(0f)
                .setDuration(700)
                .withEndAction(() -> root.removeView(puff))
                .start();
    }

    private static TextView glyph(Context c, String text, float sp) {
        TextView t = UiKit.text(c, text, sp, pastel(c), true);
        t.setIncludeFontPadding(false);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.setClickable(false);
        t.setFocusable(false);
        return t;
    }
}
