package com.orbit.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Orbit Dock's compact answer card: one quiet status line (Orbit's mark, what is happening, and a
 * chevron that says it opens), then two or three lines of the answer.
 *
 * <p>It only presents. {@link OrbitSession} decides when it is shown, and {@link DockText}
 * decides the words. Text set while the state is unchanged, which is every streaming update,
 * is never animated; only a change of state cross-fades.
 */
final class DockPreviewCard extends LinearLayout {
    static final int THINKING = 0, WRITING = 1, DONE = 2, STOPPED = 3, FAILED = 4;
    static final int RADIUS_DP = 20;
    private static final String[] LABELS = {"Thinking", "Writing", "Orbit", "Stopped", "Needs attention"};
    static final String OPEN_HINT = "Tap to open the conversation.";

    private final TextView label;
    private final TextView body;
    private final Dots dots;
    private int state = -1;

    /** {@code surfaceAlpha} is the Dock style's own see-through level, shared with its panel. */
    DockPreviewCard(Context c, int surfaceAlpha) {
        super(c);
        setOrientation(VERTICAL);
        setPadding(UiKit.dp(c, 14), UiKit.dp(c, 9), UiKit.dp(c, 10), UiKit.dp(c, 11));
        // The composer's own surface and hairline, so the two read as one piece of furniture.
        GradientDrawable surface = UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.MUTED, 44), RADIUS_DP, c);
        surface.setAlpha(surfaceAlpha);
        setBackground(surface);
        setElevation(UiKit.dp(c, 6));

        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams markLp = new LayoutParams(UiKit.dp(c, 18), UiKit.dp(c, 18));
        markLp.rightMargin = UiKit.dp(c, 8);
        header.addView(UiKit.orbitMark(c, 16), markLp);
        label = UiKit.text(c, "", 12, UiKit.MUTED, true);
        label.setSingleLine(true);
        header.addView(label);
        dots = new Dots(c);
        LayoutParams dotsLp = new LayoutParams(UiKit.dp(c, 22), UiKit.dp(c, 12));
        dotsLp.leftMargin = UiKit.dp(c, 7);
        header.addView(dots, dotsLp);
        header.addView(new View(c), new LayoutParams(0, 1, 1));
        ImageView open = new ImageView(c);
        open.setImageResource(R.drawable.ic_chevron_down);
        open.setRotation(180f);
        open.setImageTintList(ColorStateList.valueOf(UiKit.withAlpha(UiKit.MUTED, 190)));
        header.addView(open, new LayoutParams(UiKit.dp(c, 20), UiKit.dp(c, 20)));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, UiKit.dp(c, 24)));

        body = UiKit.text(c, "", 14, UiKit.TEXT, false);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setLineSpacing(0f, 1.12f);
        LayoutParams bodyLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = UiKit.dp(c, 2);
        bodyLp.rightMargin = UiKit.dp(c, 4);
        addView(body, bodyLp);
        body.setVisibility(GONE);
    }

    /** The last state set, or -1 before the first. */
    int state() { return state; }

    String text() { return body.getText().toString(); }

    /** A short window (landscape) gets two lines instead of three. */
    void setMaxLines(int lines) {
        if (body.getMaxLines() != lines) body.setMaxLines(lines);
    }

    void set(int next, String text) {
        String words = text == null ? "" : text;
        boolean changed = next != state;
        boolean hadBody = body.getVisibility() == VISIBLE;
        state = next;
        body.setText(words);
        body.setTextColor(next == STOPPED || next == FAILED ? UiKit.MUTED : UiKit.TEXT);
        body.setVisibility(words.isEmpty() ? GONE : VISIBLE);
        dots.setActive(next == THINKING || next == WRITING);
        setContentDescription(LABELS[next] + ". " + words + (words.isEmpty() ? "" : " ") + OPEN_HINT);
        if (!changed) return;
        if (isShown()) {
            UiKit.swapText(label, LABELS[next]);
            // The answer's first words settle in; a status change over existing words only fades.
            if (!words.isEmpty() && !hadBody) UiKit.enterBlock(body);
        } else {
            label.animate().cancel();
            label.setAlpha(1f);
            label.setText(LABELS[next]);
        }
    }

    /**
     * Three small accent dots that breathe in turn while Orbit is working. One short animator that
     * only repaints this view, runs only while it is on screen, and holds still when Android's
     * animations are off.
     */
    private static final class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator animator;
        private boolean active;
        private float phase;

        Dots(Context c) {
            super(c);
            paint.setColor(UiKit.accent(c));
            setVisibility(GONE);
        }

        void setActive(boolean value) {
            active = value;
            setVisibility(value ? VISIBLE : GONE);
            sync();
        }

        @Override public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            sync();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            sync();
        }

        private void sync() {
            boolean run = active && isAttachedToWindow() && isShown() && UiKit.animationsEnabled();
            if (run == (animator != null)) return;
            if (!run) {
                animator.cancel();
                animator = null;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1200L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            float r = getHeight() / 6f;
            float gap = r * 3.2f;
            for (int i = 0; i < 3; i++) {
                double wave = animator == null ? 0.5
                        : 0.5 + 0.5 * Math.sin(2 * Math.PI * (phase - i * 0.16f));
                paint.setAlpha((int) (70 + 185 * wave));
                canvas.drawCircle(r + i * gap, getHeight() / 2f, r, paint);
            }
        }
    }
}
