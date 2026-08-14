package com.orbit.assistant;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Orbit's attachment chooser, drawn inside the surface that opened it rather than in a window of
 * its own.
 *
 * <p>A {@link android.widget.PopupWindow} is a separate, focusable window: showing one moves input
 * focus off the composer, and Android tears down the keyboard with it. No window flag can avoid
 * that, because the editor that owned the keyboard is in a different window. This panel is added
 * straight to the host's own view hierarchy, and every view in it is explicitly non-focusable, so
 * the composer never stops being the focused editor and the keyboard is never touched at all.
 *
 * <p>The same code serves the full chat and the Side-button overlay: both provide a
 * {@link FrameLayout} to host it, so neither needs a window token and neither can be clipped by
 * one. Because sibling views are drawn and hit-tested in Z order rather than insertion order, the
 * layer raises itself above whatever the host already contains rather than assuming that being
 * added last puts it on top: the overlay's sheet carries a real elevation, and a fixed elevation
 * here would leave the chooser drawn behind it and untappable.
 */
final class OrbitAttachmentMenu {
    interface OnChoice {
        void onChoice(int index, String label);
    }

    /** Marks the panel and its scrim so exactly one menu can be open per host. */
    private static final String TAG = "orbit_attachment_menu";
    private static final int GAP_DP = 8;
    private static final int EDGE_DP = 12;
    /** Clearance placed above the host's tallest existing layer, and the card above the scrim. */
    private static final int SCRIM_LIFT_DP = 8;
    private static final int CARD_LIFT_DP = 4;
    /** Enough of the card to keep on screen if the anchor sits very near the top. */
    private static final int MIN_VISIBLE_DP = 96;

    private OrbitAttachmentMenu() {}

    static boolean isShowing(ViewGroup host) {
        return host != null && host.findViewWithTag(TAG) != null;
    }

    /** Removes any open menu. Safe to call when nothing is showing. */
    static boolean dismiss(ViewGroup host) {
        if (host == null) return false;
        boolean removed = false;
        View existing;
        while ((existing = host.findViewWithTag(TAG)) != null) {
            host.removeView(existing);
            removed = true;
        }
        return removed;
    }

    /**
     * Shows the chooser above {@code anchor}. Nothing here requests focus, so a visible keyboard
     * stays visible and a hidden one stays hidden.
     */
    static void show(ViewGroup host, View anchor, String[] labels, OnChoice choice) {
        if (host == null || anchor == null || labels == null || labels.length == 0) return;
        dismiss(host);
        Context c = host.getContext();

        // Measured from what the host actually contains, so the chooser sits above the overlay's
        // elevated sheet without hard-coding a number that a later layout change could overtake.
        float topZ = 0f;
        for (int i = 0; i < host.getChildCount(); i++) {
            topZ = Math.max(topZ, host.getChildAt(i).getZ());
        }
        float scrimZ = topZ + UiKit.dp(c, SCRIM_LIFT_DP);
        float cardZ = scrimZ + UiKit.dp(c, CARD_LIFT_DP);

        // Catches taps outside the card. Clickable but never focusable, so it cannot become the
        // focused view and displace the composer.
        View scrim = new View(c);
        scrim.setTag(TAG);
        scrim.setClickable(true);
        scrim.setFocusable(false);
        scrim.setFocusableInTouchMode(false);
        scrim.setOnClickListener(v -> dismiss(host));
        scrim.setElevation(scrimZ);
        host.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.bringToFront();

        LinearLayout card = new LinearLayout(c);
        card.setTag(TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(false);
        card.setFocusableInTouchMode(false);
        card.setPadding(UiKit.dp(c, 6), UiKit.dp(c, 6), UiKit.dp(c, 6), UiKit.dp(c, 6));
        card.setBackground(UiKit.outlined(UiKit.SURFACE_2, UiKit.withAlpha(UiKit.accent(c), 72), 18, c));
        card.setElevation(cardZ);

        int maxChars = 0;
        for (String label : labels) if (label != null) maxChars = Math.max(maxChars, label.length());
        int widthDp = Math.max(180, Math.min(300, 132 + (maxChars * 7)));

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            final String label = labels[i];
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(UiKit.dp(c, 13), 0, UiKit.dp(c, 10), 0);
            row.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(c), 13, c));
            row.setClickable(true);
            row.setFocusable(false);
            row.setFocusableInTouchMode(false);

            TextView text = UiKit.text(c, label, 14, UiKit.TEXT, false);
            text.setFocusable(false);
            row.addView(text, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            row.setOnClickListener(v -> {
                dismiss(host);
                if (choice != null) choice.onChoice(index, label);
            });
            UiKit.pressScale(row);
            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 44)));
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                UiKit.dp(c, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);

        int[] anchorPos = new int[2];
        int[] hostPos = new int[2];
        anchor.getLocationInWindow(anchorPos);
        host.getLocationInWindow(hostPos);
        int anchorTop = anchorPos[1] - hostPos[1];
        int anchorLeft = anchorPos[0] - hostPos[0];
        int edge = UiKit.dp(c, EDGE_DP);

        // Measured from the host's bottom so the card sits above the composer, which already sits
        // above the keyboard. No separate window means it cannot land behind the IME. Capped so an
        // anchor near the top of the host cannot push the card off the top edge.
        int maxBottom = Math.max(edge, host.getHeight() - UiKit.dp(c, MIN_VISIBLE_DP));
        int desiredBottom = host.getHeight() - anchorTop + UiKit.dp(c, GAP_DP);
        lp.bottomMargin = Math.max(edge, Math.min(desiredBottom, maxBottom));
        int maxLeft = Math.max(edge, host.getWidth() - UiKit.dp(c, widthDp) - edge);
        lp.leftMargin = Math.max(edge, Math.min(anchorLeft, maxLeft));

        host.addView(card, lp);
        card.bringToFront();
        UiKit.enterContent(card);
    }
}
