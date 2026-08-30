package com.orbit.assistant;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The status line that sits beside Orbit's orbital thinking indicator.
 *
 * <p>One component, used by both the Side-button overlay and full chat, so the two surfaces cannot
 * drift into two different behaviours for the same feature. The orbital animation is untouched and
 * remains the primary signal; this is its restrained companion, deliberately quieter than the
 * answer it precedes.
 *
 * <p><b>Geometry is fixed the moment it is created.</b> The overlay is a small sheet over another
 * app, and a status line that grew and shrank with each update would push the composer around and
 * make the sheet feel unstable. Two lines of space are reserved up front and the text is centred
 * within them, so a one-line update and a two-line update occupy exactly the same height and
 * nothing after them ever moves. Anything longer ellipsizes rather than reflowing the sheet.
 *
 * <p>Colour, typography, and text size all come from {@link UiKit} and {@link Prefs}, so accents,
 * AMOLED, Dynamic accent, custom bubble colours, the app font, and the chat text-size preference
 * are followed rather than re-invented. The ink is muted against the bubble it sits on, but never
 * below a legible contrast: a pastel or accent-coloured bubble falls back to full bubble ink
 * instead of fading into its own background.
 */
public final class ThinkingStatusView extends TextView {

    /** Text size relative to a chat bubble: subordinate to the answer without being small print. */
    private static final float TEXT_SP = 13f;
    /** How much of the bubble's normal ink the muted status keeps. */
    private static final float MUTED_INK = 0.74f;
    /**
     * Below this the muted tone is no longer comfortably readable on its bubble, which is what
     * happens on a light pastel or accent-filled bubble, so the full bubble ink is used instead.
     */
    private static final double MIN_INK_CONTRAST = 3.2d;

    private ViewGroup host;
    private String hostDescription = "";

    public ThinkingStatusView(Context context, int bubbleFill) {
        super(context);
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Prefs.chatTextSp(context, TEXT_SP));
        setTextColor(mutedInk(bubbleFill));
        setMaxLines(2);
        setEllipsize(TextUtils.TruncateAt.END);
        // Centred inside the reserved two lines, so a one-line update sits where a two-line one
        // would rather than shifting the row's optical balance.
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        setFontFeatureSettings("kern");
        UiKit.applyTypography(this);
        UiKit.applyBubbleTextMetrics(this);
        reserveTwoLines();
        // The orbital indicator already says "thinking"; this view's own node would only repeat
        // it. The announcement is made once, by the row, in setStatus below.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Widest the status is ever allowed to be, so a long phrase cannot span the whole screen. */
    private static final int MAX_WIDTH_DP = 236;
    /** Room left for the orbital indicator, the bubble padding, and the conversation margins. */
    private static final int ROW_RESERVE_DP = 132;

    /**
     * A width the status line keeps for its whole life, whatever any update says.
     *
     * <p>Fixed rather than wrapped, and that is the point. A wrapping status would give its bubble
     * a different width for every phrase, which in the Side-button sheet reads as the whole thing
     * twitching. It is sized from the real display so it is right on a compact phone and on a
     * Galaxy S25 Ultra, and clamped so it never becomes a full-width banner.
     */
    static int stableWidth(Context context) {
        int available = context.getResources().getDisplayMetrics().widthPixels
                - UiKit.dp(context, ROW_RESERVE_DP);
        // The floor keeps a narrow or oddly reported display from collapsing the line to nothing.
        return Math.max(UiKit.dp(context, 120),
                Math.min(UiKit.dp(context, MAX_WIDTH_DP), available));
    }

    /**
     * The muted status ink for a bubble, or the bubble's full ink when muting would cost too much
     * contrast. Package-visible so the contrast rule can be asserted rather than assumed.
     */
    static int mutedInk(int bubbleFill) {
        int ink = UiKit.onBubble(bubbleFill);
        int muted = UiKit.blend(ink, bubbleFill, MUTED_INK);
        return UiKit.contrastRatio(muted, bubbleFill) >= MIN_INK_CONTRAST ? muted : ink;
    }

    /**
     * Rough height of one line as a multiple of its text size, used only to reserve space.
     *
     * <p>Deliberately derived from the resolved text size rather than from font metrics. The
     * status view reserves its height before it has ever been measured or laid out, and the exact
     * ascent and descent of a font are not reliably available that early, but the text size always
     * is — and it already carries both the chat text-size preference and Android's font scaling,
     * which are the two things this must follow.
     */
    private static final float LINE_HEIGHT_FACTOR = 1.35f;

    /**
     * Reserves the height of two lines immediately, so the row is the size it will stay at from
     * its very first frame. A one-line update and a two-line update then occupy the same space and
     * nothing below the row ever moves.
     */
    private void reserveTwoLines() {
        int twoLines = Math.round(getTextSize() * LINE_HEIGHT_FACTOR * UiKit.CHAT_LINE_SPACING * 2f);
        setMinHeight(twoLines);
        setMinimumHeight(twoLines);
    }

    /**
     * Ties this status to the row that contains it, so accessibility hears one coherent thing.
     *
     * <p>A {@code ViewGroup} carrying a content description is announced as a single node, so the
     * row is what speaks. It is made a polite live region only while Thinking Updates are actually
     * producing text; the announcements are already spaced by
     * {@link ThinkingUpdateStream#MIN_INTERVAL_MS}, so what TalkBack reads is the same small number
     * of coherent phrases a sighted user sees, not a token stream.
     */
    public void attachTo(ViewGroup row, String description) {
        this.host = row;
        this.hostDescription = description == null ? "" : description;
        if (row != null) row.setContentDescription(hostDescription);
    }

    /**
     * Shows one update, cross-fading from whatever was there.
     *
     * <p>Repeating the current text is ignored outright, so a provider that re-sends the same
     * phrase cannot make the line blink.
     */
    public void setStatus(ThinkingUpdate update) {
        if (update == null || update.text.isEmpty()) return;
        if (update.text.contentEquals(getText())) return;
        UiKit.swapText(this, update.text);
        if (host != null) {
            host.setContentDescription(hostDescription.isEmpty()
                    ? update.text : hostDescription + ". " + update.text);
            host.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
    }

    /**
     * Ends the status cleanly as the answer takes over: the live region is switched off first, so
     * clearing the line cannot itself become an announcement.
     */
    public void clearStatus() {
        if (host != null) {
            host.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
            host.setContentDescription(hostDescription);
        }
        animate().cancel();
        setText("");
        setAlpha(1f);
    }
}
