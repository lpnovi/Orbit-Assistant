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
            78, 8, OverlayStretch.SHEET_CORNER_DP, 18, 36, 18, 40, 12, 240, 22, false);
    /**
     * A compact floating capsule: lighter dim, a slimmer header, and one composer card that also
     * carries the screen controls, so there is no separate context bar.
     */
    static final OverlayStyle FLOAT = new OverlayStyle(Prefs.OVERLAY_STYLE_FLOAT,
            44, 12, 32f, 14, 28, 15, 36, 16, 220, 26, true);

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
    /** Resting conversation height, in dp. */
    final int conversationDp;
    final float composerRadiusDp;
    /** True when the screen controls live inside the composer card instead of their own bar. */
    final boolean integratedComposer;

    private OverlayStyle(String id, int scrimAlpha, int marginDp, float cornerDp,
                         int sidePaddingDp, int markDp, float titleSp, int iconDp,
                         float controlRadiusDp, int conversationDp, float composerRadiusDp,
                         boolean integratedComposer) {
        this.id = id;
        this.scrimAlpha = scrimAlpha;
        this.marginDp = marginDp;
        this.cornerDp = cornerDp;
        this.sidePaddingDp = sidePaddingDp;
        this.markDp = markDp;
        this.titleSp = titleSp;
        this.iconDp = iconDp;
        this.controlRadiusDp = controlRadiusDp;
        this.conversationDp = conversationDp;
        this.composerRadiusDp = composerRadiusDp;
        this.integratedComposer = integratedComposer;
    }

    static OverlayStyle of(String id) {
        return Prefs.OVERLAY_STYLE_FLOAT.equals(id) ? FLOAT : CLASSIC;
    }

    static OverlayStyle current(Context c) {
        return of(Prefs.overlayStyle(c));
    }
}
