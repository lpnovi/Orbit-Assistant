package com.orbit.assistant;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one confirmation Orbit puts in front of a protected emergency or crisis number.
 *
 * <p>Written once and used by both surfaces. Full chat shows it inside an Orbit dialog and the
 * Side-button overlay shows it inline in the sheet, but it is the same card: same question, same
 * words, same order, same treatment of the two answers. Two hand-built versions of a safety
 * confirmation is how the two surfaces end up disagreeing about the most important question the
 * app ever asks, and this is a place where that must not be allowed to happen quietly.
 *
 * <p>It is deliberately small. The Beta 3 card was correct and looked like a warning banner:
 * full-sheet width, a large empty middle, and two stock buttons big enough to hit by accident.
 * Size is not seriousness. What this asks for is one clear decision, so it is sized to its own
 * content, its body is one muted line, and the two answers sit close underneath where the eye
 * already is. Cancel stays first and stays quiet; Open dialer is an accent pill, obvious but not
 * loud, and nothing about it is pre-focused or given a countdown - the user reaches for it or
 * the question waits.
 *
 * <p>This is presentation only. Nothing here decides whether a dial is allowed: the grant is
 * issued by {@link EmergencyDialGuard.Confirmation#confirm()} and spent at the executor, and this
 * card cannot reach either except by a person pressing the button.
 */
public final class ProtectedDialConfirmationView {

    private ProtectedDialConfirmationView() {}

    /** The widest the card is allowed to get, so a question this short never spans a tablet. */
    private static final int MAX_WIDTH_DP = 248;

    /**
     * Builds the card.
     *
     * @param compact the overlay's slightly tighter sizing. The layout, wording, order and
     *                accessibility are identical either way; only the numbers differ.
     */
    public static View build(Context c, String number, boolean compact,
                             Runnable onCancel, Runnable onConfirm) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(c, 14), UiKit.dp(c, compact ? 11 : 12),
                UiKit.dp(c, 14), UiKit.dp(c, compact ? 10 : 11));
        card.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(c), 110), 17, c));

        TextView title = UiKit.text(c, EmergencyDialGuard.titleFor(number),
                compact ? 14.5f : 15.5f, UiKit.TEXT, true);
        card.addView(title);

        TextView body = UiKit.text(c, EmergencyDialGuard.messageFor(number),
                compact ? 12f : 12.5f, UiKit.MUTED, false);
        body.setLineSpacing(0, 1.1f);
        // The cap is what keeps the card content-sized: the body is the widest line in it, so
        // bounding the body bounds the card without a custom measure pass.
        body.setMaxWidth(UiKit.dp(c, MAX_WIDTH_DP));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = UiKit.dp(c, 4);
        card.addView(body, bodyLp);

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        // Cancel first in reading order, in focus order, and in the order a thumb sweeps. The safe
        // answer is the one reached without effort.
        Button cancel = ghostButton(c, EmergencyDialGuard.cancelLabel(), compact);
        cancel.setContentDescription(EmergencyDialGuard.cancelLabel()
                + ", close this without opening the dialer");
        actions.addView(cancel);

        Button confirm = accentPillButton(c, EmergencyDialGuard.confirmLabel(), compact);
        confirm.setContentDescription(EmergencyDialGuard.confirmLabel() + " for " + number);
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        confirmLp.setMarginStart(UiKit.dp(c, 6));
        actions.addView(confirm, confirmLp);

        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = UiKit.dp(c, compact ? 8 : 9);
        card.addView(actions, actionsLp);

        if (onCancel != null) cancel.setOnClickListener(v -> onCancel.run());
        if (onConfirm != null) confirm.setOnClickListener(v -> onConfirm.run());
        return card;
    }

    /** What a screen reader is told the moment the card appears. */
    public static String announcement(String number) {
        return EmergencyDialGuard.titleFor(number) + " " + EmergencyDialGuard.messageFor(number);
    }

    /**
     * The quiet answer: text on nothing, with a real touch target underneath it.
     *
     * <p>A stock Button carries a 64dp minimum width and its own inset background, which is most
     * of why the Beta 3 card looked oversized. Those are cleared and the height is set back to
     * 40dp explicitly, so the control is small to look at and still comfortably larger than the
     * platform's minimum to hit.
     */
    private static Button ghostButton(Context c, String text, boolean compact) {
        Button b = baseButton(c, text, compact);
        b.setTextColor(UiKit.MUTED);
        b.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.withAlpha(UiKit.accent(c), 70),
                99, c));
        return b;
    }

    /** The primary answer: a compact accent pill, obvious without being the whole card. */
    private static Button accentPillButton(Context c, String text, boolean compact) {
        Button b = baseButton(c, text, compact);
        b.setTextColor(UiKit.onAccent(c));
        b.setBackground(UiKit.ripple(UiKit.accent(c), UiKit.onAccent(c), 99, c));
        return b;
    }

    private static Button baseButton(Context c, String text, boolean compact) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(compact ? 12.5f : 13f);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(UiKit.dp(c, 40));
        b.setMinimumHeight(UiKit.dp(c, 40));
        b.setPadding(UiKit.dp(c, 14), UiKit.dp(c, 6), UiKit.dp(c, 14), UiKit.dp(c, 6));
        UiKit.pressScale(b);
        return b;
    }
}
