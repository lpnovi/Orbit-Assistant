package com.orbit.assistant;

import android.content.Context;

/**
 * How the Side-button overlay looks. Visual facts only: every style builds the same controls with
 * the same behaviour, and {@link OrbitSession} arranges them from these numbers. Adding a style is a
 * new constant here plus its Settings label, never a second copy of the overlay.
 */
final class OverlayStyle {
    /** The overlay Orbit has always had. Its numbers are exactly the ones it shipped with. */
    static final OverlayStyle CLASSIC = new OverlayStyle(Prefs.OVERLAY_STYLE_CLASSIC,
            78, 8, OverlayStretch.SHEET_CORNER_DP, 18, 36, 18, 40, 12, 240, 240, 22,
            false, false);
    /**
     * Orbit's full floating card, shipped as "Float" in 0.8.4.0-beta.1: lighter dim, a slimmer
     * header, and one composer card that also carries the screen controls. Its conversation fits
     * what it holds, so an empty chat no longer reserves a tall blank middle.
     */
    static final OverlayStyle MODERN = new OverlayStyle(Prefs.OVERLAY_STYLE_MODERN,
            44, 12, 32f, 14, 28, 15, 36, 16, 120, 260, 26,
            true, false);
    /**
     * The compact one: a small bottom card that is little more than a header and the composer
     * until there is a conversation to show, then grows upward to a modest cap and scrolls.
     */
    static final OverlayStyle FLOAT = new OverlayStyle(Prefs.OVERLAY_STYLE_FLOAT,
            30, 10, 28f, 12, 24, 15, 36, 16, 0, 200, 24,
            true, true);

    final String id;
    /** Alpha of the dim behind the sheet. */
    final int scrimAlpha;
    /** Gap between the sheet and the screen's sides and bottom, in dp. */
    final int marginDp;
    /** Resting corner radius of the sheet, in dp. */
    final float cornerDp;
    /** Horizontal padding inside the sheet, in dp. */
    final int sidePaddingDp;
    final int markDp;
    final float titleSp;
    /** Header icon buttons, in dp. */
    final int iconDp;
    /** Corner radius of the AI chip and screen buttons, in dp. */
    final float controlRadiusDp;
    /**
     * Resting conversation height range, in dp. Equal for a fixed-height conversation (Classic);
     * otherwise the conversation fits its content between the two, then scrolls.
     */
    final int conversationMinDp;
    final int conversationDp;
    final float composerRadiusDp;
    /** True when the screen controls live inside the composer card instead of their own bar. */
    final boolean integratedComposer;
    /**
     * Float's density choices: History and New chat share one More menu, an empty chat shows no
     * greeting bubble (the composer's hint carries it), and an empty suggestion row takes no room.
     */
    final boolean compact;

    private OverlayStyle(String id, int scrimAlpha, int marginDp, float cornerDp,
                         int sidePaddingDp, int markDp, float titleSp, int iconDp,
                         float controlRadiusDp, int conversationMinDp, int conversationDp,
                         float composerRadiusDp, boolean integratedComposer, boolean compact) {
        this.id = id;
        this.scrimAlpha = scrimAlpha;
        this.marginDp = marginDp;
        this.cornerDp = cornerDp;
        this.sidePaddingDp = sidePaddingDp;
        this.markDp = markDp;
        this.titleSp = titleSp;
        this.iconDp = iconDp;
        this.controlRadiusDp = controlRadiusDp;
        this.conversationMinDp = conversationMinDp;
        this.conversationDp = conversationDp;
        this.composerRadiusDp = composerRadiusDp;
        this.integratedComposer = integratedComposer;
        this.compact = compact;
    }

    /** True when the conversation grows with its content instead of holding one fixed height. */
    boolean fitsContent() {
        return conversationMinDp < conversationDp;
    }

    /** The composer's resting hint. Float's doubles as the greeting it does not draw. */
    String hint() {
        return compact ? "What can I help with?" : "Ask anything…";
    }

    static OverlayStyle of(String id) {
        if (Prefs.OVERLAY_STYLE_CLASSIC.equals(id)) return CLASSIC;
        if (Prefs.OVERLAY_STYLE_FLOAT.equals(id)) return FLOAT;
        return MODERN;
    }

    static OverlayStyle current(Context c) {
        return of(Prefs.overlayStyle(c));
    }
}
